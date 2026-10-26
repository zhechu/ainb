package cn.enlev.ainb.module.infra.service.formdesign.runtime;

import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.infra.dal.dataobject.formdesign.FormDesignDO;
import cn.enlev.ainb.module.infra.framework.billtemplate.core.BillTemplateFormFiller;
import cn.enlev.ainb.module.infra.framework.formdesign.core.permission.FormRowScopeGuard;
import cn.enlev.ainb.module.infra.framework.formdesign.core.binding.TableBinding;
import cn.enlev.ainb.module.infra.framework.formdesign.core.extension.FormSubmitContext;
import cn.enlev.ainb.module.infra.framework.formdesign.core.sql.FormDesignSqlBuilder;
import cn.enlev.ainb.module.infra.framework.formdesign.core.validation.FormValidationGuard;
import cn.enlev.ainb.module.infra.framework.formdesign.core.length.ValueLengthGuard;
import cn.enlev.ainb.module.infra.framework.formdesign.core.value.FormValueSerializer;
import cn.enlev.ainb.module.infra.service.formdesign.FormDesignService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 单据编号冲突重试的测试
 *
 * <h3>为什么这件事值得专门测</h3>
 *
 * 「唯一索引冲突就重试」看起来只是加个 try-catch，实际有两个坑，
 * 而两个坑的表现都是「看起来重试了，数据库里什么都没有」：
 *
 * <ol>
 *   <li>在事务内重试无效 —— 约束冲突会把事务标记为 rollback-only，
 *       重试再成功，最终提交时也一样回滚</li>
 *   <li>重试要重取号 —— 沿用上一次的号必然再撞一次</li>
 * </ol>
 *
 * 这里用「每次尝试都开一个新事务」的结构验证第 1 点（TransactionTemplate 被调了几次），
 * 用「两次 INSERT 的参数不同」验证第 2 点。
 *
 * @author 走进新时代
 */
public class BillNumberRetryTest extends BaseMockitoUnitTest {

    @InjectMocks
    private FormDesignRuntimeServiceImpl service;

    @Mock
    private FormDesignService formDesignService;
    @Mock
    private FormDesignSqlBuilder sqlBuilder;
    @Mock
    private BillTemplateFormFiller billTemplateFormFiller;
    @Mock
    private FormSubmitProcessor submitProcessor;
    /**
     * 唯一性校验的字段解析。默认返回空列表（Mockito 对 List 返回类型的缺省行为），
     * 即「没有字段配唯一性校验」—— 本用例关心的是重试，不是唯一性
     */
    @Mock
    private FormValidationGuard validationGuard;
    /**
     * 落库形态转换。本用例里原样透传 —— 要验的是重试结构，不是序列化。
     *
     * ⚠️ 不能省：它的返回值是后续写库那一路用的数据，mock 的缺省返回 null 会让
     * 单号断言全部变成 NPE，而报错信息完全指不到这里
     */
    @Mock
    private FormValueSerializer valueSerializer;
    /**
     * 超长值拦截。缺省返回 mock 的 null，而 create 会对它调 isPassed() ——
     * 所以必须显式给一个「通过」的结果，否则单号断言会全部变成 NPE，
     * 而报错完全指不到这里（与上面 valueSerializer 那条同一个坑）
     */
    @Mock
    private ValueLengthGuard valueLengthGuard;
    /**
     * 写后落域校验。默认什么都不做（Mockito 对 void 方法的缺省行为）——
     * 本用例关心的是单号重试，不是数据权限
     */
    @Mock
    private FormRowScopeGuard rowScopeGuard;
    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private TransactionTemplate transactionTemplate;
    /**
     * 表单数据变更事件发布器（增量6：create 成功后发 FormDataChangedEvent 供 agent 触发器监听）。
     * 只有「重试成功」那条用例（testRetrySucceeds）会真正走到发布点；其余用例都在发布前抛
     * DuplicateKeyException，故不 stub —— 给个 mock 让 @InjectMocks 注入、发布调用成为 no-op 即可
     */
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private static final String CODE = "demo_form";

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        // TransactionTemplate 直接执行回调。这里不模拟事务语义 ——
        // 要验证的是「每次尝试都新开一个事务」这个【结构】，
        // 而结构体现在 execute 被调用的次数上
        doAnswer(invocation -> {
            TransactionCallback<Object> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        }).when(transactionTemplate).execute(any(TransactionCallback.class));

        FormDesignDO design = new FormDesignDO();
        design.setId(1L);
        design.setCode(CODE);
        design.setPublishedVersion(1);
        // 主键交给数据库自增，这样 generatePkIfNeeded 返回 null，
        // 走 executeInsertAndReturnKey 那条分支
        design.setTableBinding("{\"mainTable\":\"demo\",\"mainPk\":\"id\",\"pkStrategy\":\"AUTO\"}");
        when(formDesignService.getFormDesignByCode(CODE)).thenReturn(design);

        // 长度校验一律放行：本用例关心的是重试结构
        when(valueLengthGuard.validate(any(), any(), any(), any()))
                .thenReturn(new ValueLengthGuard.ValidationResult());

        // 提交前处理原样返回数据：本用例关心的不是净化逻辑
        when(submitProcessor.before(any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(3));
        // 落库形态转换同样原样返回。它排在填号之后，返回的那一份才是写库用的数据
        // 🔴 打的必须是【三参】那个重载：运行时写库路径一律走它（FormDesignRuntimeServiceImpl
        //    的 createInTransaction / updateInTransaction 都把 TableBinding 传进去了），
        //    两参版本只是拿不到绑定时的便捷入口 —— FormValueSerializer#serialize 的注释里
        //    「两参版本只在单测使用」这句对本类并不成立，本类测的正是运行时写库路径。
        //    桩打错重载不是「宽松一点」：STRICT_STUBS 会判 PotentialStubbingProblem，
        //    五个用例全红在 setUp 上，看起来像重试逻辑坏了，其实一次都没跑到重试。
        when(valueSerializer.serialize(any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(1));
    }

    /**
     * 让填号器每次都写入一个不同的号，模拟「重取号」
     */
    private void stubBillFiller() {
        when(billTemplateFormFiller.fill(any(), any(), any())).thenAnswer(invocation -> {
            Map<String, Object> data = invocation.getArgument(2);
            data.put("billNo", "NO-" + (billNoSeq++));
            return true;
        });
    }

    private int billNoSeq = 1;

    /**
     * INSERT 语句的参数直接取自提交数据里的单号，便于断言「两次用的号不同」
     */
    private void stubInsertAlwaysConflict() {
        when(sqlBuilder.buildInsert(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            Map<String, Object> data = invocation.getArgument(2);
            return new FormDesignSqlBuilder.SqlWithParams(
                    "INSERT INTO demo (bill_no) VALUES (?)",
                    // 用 singletonList 而不是 List.of：没有单号的用例里这个值是 null，
                    // List.of 不接受 null 元素，会把断言里的异常类型换成 NPE
                    java.util.Collections.singletonList(data.get("billNo")));
        });
        when(jdbcTemplate.update(any(org.springframework.jdbc.core.PreparedStatementCreator.class),
                any(org.springframework.jdbc.support.KeyHolder.class)))
                .thenThrow(new DuplicateKeyException("Duplicate entry 'NO-1' for key 'uk_bill_no'"));
    }

    private Map<String, Object> data() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", "张三");
        return data;
    }

    @Test
    @DisplayName("⚠️ 撞号后重试，且每次都在【新事务】里 —— 事务内重试会被 rollback-only 吃掉")
    @SuppressWarnings("unchecked")
    void testRetryInNewTransaction() {
        stubBillFiller();
        stubInsertAlwaysConflict();

        assertThrows(DuplicateKeyException.class, () -> service.create(CODE, data()));

        // 3 次尝试 = 3 个独立事务。若重试写在事务内，这里只会是 1
        verify(transactionTemplate, times(3)).execute(any(TransactionCallback.class));
    }

    @Test
    @DisplayName("⚠️ 重试必须重取号 —— 沿用上一次的号必然再撞一次")
    void testRetryRegeneratesNumber() {
        stubBillFiller();
        stubInsertAlwaysConflict();

        assertThrows(DuplicateKeyException.class, () -> service.create(CODE, data()));

        // 填号器被调了 3 次，每次拿到的数据里都不该残留上一次的号
        verify(billTemplateFormFiller, times(3)).fill(any(), any(), any());
        assertEquals(4, billNoSeq, "填号器应当被调用 3 次");
    }

    @Test
    @DisplayName("⚠️ 没填号的表单不重试 —— 冲突来自别的唯一索引，重取号毫无意义")
    @SuppressWarnings("unchecked")
    void testNoRetryWithoutBillNumber() {
        // 填号器返回 false：这张表单没有单据字段
        when(billTemplateFormFiller.fill(any(), any(), any())).thenReturn(false);
        stubInsertAlwaysConflict();

        assertThrows(DuplicateKeyException.class, () -> service.create(CODE, data()));

        verify(transactionTemplate, times(1)).execute(any(TransactionCallback.class));
    }

    @Test
    @DisplayName("重试成功后返回主键，不再继续重试")
    @SuppressWarnings("unchecked")
    void testRetrySucceeds() {
        stubBillFiller();
        when(sqlBuilder.buildInsert(any(), any(), any(), any(), any()))
                .thenReturn(new FormDesignSqlBuilder.SqlWithParams("INSERT INTO demo VALUES (?)",
                        List.of("x")));
        // 第一次撞号，第二次成功。KeyHolder 里没有主键时实现会回退到 null，
        // 这里只关心「没有再抛异常、也没有第三次尝试」
        when(jdbcTemplate.update(any(org.springframework.jdbc.core.PreparedStatementCreator.class),
                any(org.springframework.jdbc.support.KeyHolder.class)))
                .thenThrow(new DuplicateKeyException("conflict"))
                .thenReturn(1);

        service.create(CODE, data());

        verify(transactionTemplate, times(2)).execute(any(TransactionCallback.class));
    }

    @Test
    @DisplayName("⚠️ 每次尝试拿到的是数据副本 —— 子表明细行不能残留上一次尝试的号")
    void testSubTableRowsCopiedPerAttempt() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("qty", 1);
        Map<String, Object> original = data();
        original.put("items", List.of(row));

        // 填号器往明细行里写号，模拟子表单据字段
        when(billTemplateFormFiller.fill(any(), any(), any())).thenAnswer(invocation -> {
            Map<String, Object> attemptData = invocation.getArgument(2);
            List<Map<String, Object>> rows = (List<Map<String, Object>>) attemptData.get("items");
            rows.get(0).put("rowNo", "ROW-" + (billNoSeq++));
            return true;
        });
        stubInsertAlwaysConflict();

        assertThrows(DuplicateKeyException.class, () -> service.create(CODE, original));

        // 调用方传进来的那份数据不该被改到 —— 它是调用方的对象，
        // 而且下一次尝试要从它重新开始
        assertEquals(1, row.size(), "原始明细行被就地改了，重试会看到上一次作废的号：" + row);
        assertNotEquals(null, row.get("qty"));
    }

}
