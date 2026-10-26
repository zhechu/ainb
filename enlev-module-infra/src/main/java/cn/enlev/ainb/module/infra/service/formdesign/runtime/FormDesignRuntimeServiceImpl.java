package cn.enlev.ainb.module.infra.service.formdesign.runtime;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.common.biz.system.permission.dto.DatasetFieldModeRespDTO;
import cn.enlev.ainb.framework.common.exception.ErrorCode;
import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.framework.common.util.json.JsonUtils;
import cn.enlev.ainb.framework.excel.core.util.ExcelUtils;
import cn.enlev.ainb.framework.security.core.util.SecurityFrameworkUtils;
import cn.enlev.ainb.framework.tenant.core.context.TenantContextHolder;
import cn.enlev.ainb.module.infra.api.formdesign.event.FormDataChangedEvent;
import cn.enlev.ainb.module.infra.controller.admin.formdesign.vo.runtime.FormRuntimeConfigRespVO;
import cn.enlev.ainb.module.infra.dal.dataobject.formdesign.FormDesignDO;
import cn.enlev.ainb.module.infra.enums.formdesign.FormDesignStatusEnum;
import cn.enlev.ainb.module.infra.enums.formdesign.PkStrategyEnum;
import cn.enlev.ainb.module.infra.framework.billtemplate.core.BillTemplateFormFiller;
import cn.enlev.ainb.module.infra.framework.formdesign.core.FormFieldNaming;
import cn.enlev.ainb.module.infra.framework.formdesign.core.FormFieldsCodec;
import cn.enlev.ainb.module.infra.framework.formdesign.core.mask.FormDataMasker;
import cn.enlev.ainb.module.infra.framework.formdesign.core.permission.FormDataAuditor;
import cn.enlev.ainb.module.infra.framework.formdesign.core.permission.FormFieldCropper;
import cn.enlev.ainb.module.infra.framework.formdesign.core.relation.RelationFormResolver;
import cn.enlev.ainb.module.infra.framework.formdesign.core.permission.FormRowScopeGuard;
import cn.enlev.ainb.module.infra.framework.formdesign.core.button.CustomButton;
import cn.enlev.ainb.module.infra.framework.formdesign.core.length.ValueLengthGuard;
import cn.enlev.ainb.module.infra.framework.formdesign.core.value.FormValueSerializer;
import cn.idev.excel.metadata.data.DataFormatData;
import cn.idev.excel.metadata.data.WriteCellData;
import cn.enlev.ainb.module.infra.framework.formdesign.core.cellformat.CellFormatContractRegistry;
import cn.enlev.ainb.module.infra.framework.formdesign.core.cellformat.ExportCellRenderer;
import cn.enlev.ainb.module.infra.framework.formdesign.core.export.FormExportOptions;
import cn.enlev.ainb.module.infra.framework.formdesign.core.value.TemporalValueCoercer;
import cn.enlev.ainb.module.infra.framework.formdesign.core.extension.FormSubmitContext;
import cn.enlev.ainb.module.infra.framework.formdesign.core.binding.FieldMapping;
import cn.enlev.ainb.module.infra.framework.formdesign.core.binding.LogicDeleteConfig;
import cn.enlev.ainb.module.infra.framework.formdesign.core.binding.ChangeLogConfig;
import cn.enlev.ainb.module.infra.framework.formdesign.core.binding.OptimisticLockConfig;
import cn.enlev.ainb.module.infra.framework.formdesign.core.changelog.FormDataChangeDiffer;
import cn.enlev.ainb.module.infra.dal.dataobject.formdesign.FormDataChangeLogDO;
import cn.enlev.ainb.module.infra.dal.mysql.formdesign.FormDataChangeLogMapper;
import cn.enlev.ainb.module.infra.framework.formdesign.core.binding.SideTableBinding;
import cn.enlev.ainb.module.infra.framework.formdesign.core.binding.SubTableBinding;
import cn.enlev.ainb.module.infra.framework.formdesign.core.binding.TableBinding;
import cn.enlev.ainb.module.infra.framework.formdesign.core.sql.FormDesignSqlBuilder;
import cn.enlev.ainb.module.infra.framework.formdesign.core.sql.MultiValueShape;
import cn.enlev.ainb.module.infra.framework.formdesign.core.sql.SubTableDiffEngine;
import cn.enlev.ainb.module.infra.framework.formdesign.core.sql.SubTableRowMapper;
import cn.enlev.ainb.module.infra.framework.formdesign.core.validation.FormValidationGuard;
import cn.enlev.ainb.module.infra.service.datainterface.DataInterfaceService;
import cn.enlev.ainb.module.infra.service.formdesign.FormDesignService;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.validation.annotation.Validated;

import java.io.IOException;
import java.sql.PreparedStatement;
import java.util.*;
import java.util.stream.Collectors;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.module.infra.enums.ErrorCodeConstants.*;

/**
 * 表单设计运行时 Service 实现类
 *
 * 把守卫、SQL 构建器、子表 diff 引擎串起来，并负责事务与 SQL 执行。
 *
 * ⚠️ 多数据源现状：本实现目前只在【主库】执行。
 * 项目虽引入了 dynamic-datasource 4.5.0，但仅用于读取配置
 * （DataSourceConfigServiceImpl 用 DynamicDataSourceProperties），
 * 没有任何 @DS 注解或运行时切换代码 —— 既有的 datainterface 模块的 SqlExecutor
 * 也只注入了主库 JdbcTemplate。
 * 因此 dataSourceConfigId != 0 时会直接拒绝，避免「配了别的数据源但其实写进主库」
 * 这种静默错库的事故。多数据源支持需要单独一轮工作。
 *
 * @author 走进新时代
 */
@Service
@Validated
@Slf4j
public class FormDesignRuntimeServiceImpl implements FormDesignRuntimeService {

    /**
     * 分页每页条数上限，防止前端传超大值拖垮数据库
     */
    private static final int MAX_PAGE_SIZE = 200;

    /**
     * 批量删除的单次条数上限
     *
     * 批量删除是「逐条走完整删除流程」的循环，每条都可能带子表与副表，
     * 且整批在一个事务里。条数不设限会让事务持有大量行锁，
     * 期间别人对这张表的写入全部排队 —— 表现为「有人点了批量删除，
     * 整个模块卡住十几秒」。列表一页最多 {@link #MAX_PAGE_SIZE} 条，
     * 这个上限与之对齐。
     */
    private static final int BATCH_DELETE_MAX = 200;

    /**
     * 去重值查询的返回上限
     *
     * 左侧树的节点数超过这个量已经没有筛选意义（用户找不到想点的那个），
     * 且对高基数列（如姓名）不设限会查出几十万行。
     */
    private static final int MAX_DISTINCT_VALUES = 500;

    /**
     * 关联控件候选数据的默认返回条数
     *
     * 下拉框里给用户扫一眼的量。要更多就让他打关键字缩小范围 ——
     * 这正是 REFERENCE 与 query 的分界：前者是「搜一下选一条」，
     * 后者才是「翻着浏览」
     */
    private static final int REFERENCE_DEFAULT_LIMIT = 20;

    /**
     * 关联控件候选数据的硬上限
     *
     * ⚠️ 不能跟着 MAX_PAGE_SIZE 走。REFERENCE 的整个意义是「比 query 弱」，
     * 放到 200 条再配上可传的关键字，就等于把目标表单的数据分批捞干净了 ——
     * 那样这个动作就白设了
     */
    private static final int REFERENCE_MAX_LIMIT = 50;

    /**
     * IN 子句的单批值数量上限
     *
     * 副表回查用 {@code WHERE fk IN (...)}，值太多会让 SQL 文本过长
     * （MySQL 默认 max_allowed_packet 有限制），也让优化器更容易放弃索引。
     * 常规分页最多 {@link #MAX_PAGE_SIZE} 条不会触发分批，
     * 这个阈值是为树形模式或未来放宽分页上限留的保险。
     */
    private static final int IN_CLAUSE_BATCH_SIZE = 500;

    /**
     * 副表回查的耗时告警阈值（毫秒）
     *
     * 走外键索引的话，几张副表加起来通常在 10ms 内。超过这个值意味着
     * 副表配得过多、外键缺索引、或副表数据量异常，值得在日志里留痕。
     */
    private static final long SIDE_TABLE_MERGE_SLOW_MS = 200L;

    /**
     * 外键的虚拟 field 名。不会与 form-create 的真实 field 冲突
     * （form-create 的 field 禁止下划线开头）。
     */
    private static final String FK_VIRTUAL_FIELD = "__fk__";

    /**
     * 允许通过 {@link #updateSystemColumns} 写入的系统列白名单
     *
     * 只放流程对接必需的两列。这个白名单是该方法的安全边界 —— 不要为了一时方便随意扩充，
     * 否则它会退化成绕过表绑定配置的通用写入口子。
     */
    private static final Set<String> SYSTEM_WRITABLE_COLUMNS =
            Set.of("process_instance_id", "process_status");

    @Resource
    private JdbcTemplate jdbcTemplate;
    @Resource
    private FormDesignService formDesignService;
    @Resource
    private FormDesignSqlBuilder sqlBuilder;
    @Resource
    private BillTemplateFormFiller billTemplateFormFiller;
    /**
     * 提交前后置处理：联动规则复校验 + 服务端扩展点。
     * 收在一个组件里而不是在三个入口各写一遍 —— 漏掉一个入口就等于那条路径
     * 没有服务端约束，而这种漏洞从功能上完全看不出来
     */
    @Resource
    private FormSubmitProcessor submitProcessor;
    /**
     * 数据脱敏。三个读取出口（get / page / export）统一在返回前处理 ——
     * 漏掉任何一个都等于开了一扇后门，而导出恰恰是泄露量最大的那个
     */
    @Resource
    private FormDataMasker dataMasker;
    /**
     * 写后落域校验。挡的是「把自己的行改成别人的」——
     * UPDATE 的 WHERE 只校验改之前的值，挡不住这一种
     */
    @Resource
    private FormRowScopeGuard rowScopeGuard;
    /**
     * 列/字段级裁剪。读路径裁 config / get / page，写路径拒 HIDDEN、丢 READONLY
     */
    @Resource
    private FormFieldCropper fieldCropper;

    /**
     * 关联控件配置解析
     *
     * 给 {@code referenceOptions} 用：目标表单与可见字段必须从已发布配置里读，
     * 不能接受调用方传（否则 REFERENCE 就成了「任意表单任意字段」的读取口）
     */
    @Resource
    private RelationFormResolver relationFormResolver;

    /**
     * 敏感操作审计
     *
     * 只审「查看明文」与「调自定义按钮接口」两件事 —— 其余出口都已被
     * 行过滤 + 字段裁剪 + 脱敏挡住，逐个记账只会把真正要查的那两条淹掉
     */
    @Resource
    private FormDataAuditor auditor;
    /**
     * 落库形态与逻辑形态之间的转换。
     * 只在紧挨写库/读库的地方调用，业务逻辑一律看逻辑形态
     */
    @Resource
    private FormValueSerializer valueSerializer;
    /**
     * 导出单元格的渲染。规则来自 cell-format-contracts.json，与前端列表共用一份声明 ——
     * 各写一遍必然漂移，而漂移的表现是「列表显示正常、导出是另一套」
     */
    @Resource
    private ExportCellRenderer cellRenderer;
    @Resource
    private CellFormatContractRegistry cellFormatRegistry;
    /**
     * 超长值拦截。放在运行时服务而不是 FormSubmitProcessor 里：
     * 它要量的是 serialize 之后的落库形态，而那一步在这里才发生
     */
    @Resource
    private ValueLengthGuard valueLengthGuard;
    /**
     * 自定义按钮的「调数据接口」动作。⚠️ 走 invoke（允许写型接口）而不是
     * invokeReadOnly —— 作废单据这类写操作正是这个动作存在的理由
     */
    @Resource
    private DataInterfaceService dataInterfaceService;
    /**
     * 字段级变更对比。它刻意不碰数据库 —— 读旧值那一步在本类里做
     */
    @Resource
    private FormDataChangeDiffer changeDiffer;
    @Resource
    private FormDataChangeLogMapper changeLogMapper;
    /**
     * 校验规则的解析。查库那部分在本类里做 —— 守卫本身刻意不碰数据库
     */
    @Resource
    private FormValidationGuard validationGuard;
    /**
     * 编程式事务，只给 create 的单号冲突重试用
     *
     * 为什么不能靠 @Transactional：重试必须在事务【外】—— 唯一索引冲突会把当前事务
     * 标记为 rollback-only，事务内重试再成功，最终提交时也一样会被回滚。
     * 详见 {@link #create} 的注释
     */
    @Resource
    private TransactionTemplate transactionTemplate;

    /**
     * 表单数据变更事件的广播口（增量6：form_event 触发器的数据源）
     *
     * create/update/delete 成功后各广播一条 {@link FormDataChangedEvent}，agent 侧的 form_event
     * 触发器监听它（{@code @TransactionalEventListener(AFTER_COMMIT)}）自动拉起 AI 流程。
     * 用事件而非反向调 agent：模块依赖单向（agent→infra）；且 AFTER_COMMIT 语义保证
     * 「触发失败绝不回滚用户已提交的数据」——与 {@link cn.enlev.ainb.module.infra.api.formdesign.event.FormDesignPublishedEvent}
     * 的「同事务、失败即回滚」刻意相反，详见 {@link FormDataChangedEvent} 类注释。
     */
    @Resource
    private ApplicationEventPublisher eventPublisher;

    @Override
    public FormRuntimeConfigRespVO getRenderConfig(String code) {
        FormDesignDO design = loadPublishedDesign(code);
        TableBinding binding = parseBinding(design);

        FormRuntimeConfigRespVO vo = new FormRuntimeConfigRespVO();
        vo.setCode(design.getCode());
        vo.setName(design.getName());
        vo.setFormType(design.getFormType());
        vo.setVersion(design.getPublishedVersion());
        vo.setFormConf(design.getFormConf());
        // 字段级裁剪：HIDDEN 的规则整条剔除，READONLY 的注入 props.disabled。
        // ⚠️ 只裁 config 是安全假象 —— get / page 也必须裁，否则抓包照样看到明文
        vo.setFormFields(fieldCropper.cropFormFields(
                design.getCode(), FormFieldsCodec.read(design.getFormFields())));
        // 列表列走 COLUMN 资源，与详情字段分开判定
        vo.setListConf(fieldCropper.cropListConf(design.getCode(), design.getListConf()));
        vo.setPkField(binding.getMainPk());
        // 启用乐观锁时把版本字段名下发。前端据此把详情里的版本号回传，
        // 不下发的话前端不知道该带哪个键，乐观锁在界面这条路径上就形同没开
        OptimisticLockConfig lock = binding.getOptimisticLock();
        vo.setVersionField(lock != null && lock.isUsable() ? lock.getColumn() : null);
        ChangeLogConfig changeLog = binding.getChangeLog();
        vo.setChangeLogEnabled(changeLog != null && changeLog.isEnabled());
        vo.setValidateRules(design.getValidateRules());
        vo.setLogicRules(design.getLogicRules());
        vo.setCustomButtons(design.getCustomButtons());
        vo.setDetailTabs(design.getDetailTabs());
        // 模板为空时前端不放「打印」按钮 —— 无条件放的话点开是一张空白预览
        vo.setPrintTemplate(design.getPrintTemplate());
        vo.setExtensionMode(design.getExtensionMode());
        vo.setFormScript(design.getFormScript());
        vo.setSubmitHookTimeoutAction(design.getSubmitHookTimeoutAction());
        return vo;
    }

    /**
     * 创建
     *
     * <h3>本方法刻意【没有】@Transactional</h3>
     *
     * 单据编号落在唯一索引上时可能与并发的另一次提交撞号，撞了要重取号再来一次。
     * 而重试**必须发生在事务外**：唯一索引冲突会把当前事务标记为 rollback-only，
     * 事务内再怎么重试，最后提交时一样会被回滚 ——
     * 表现为「日志里看到重试成功了，数据库里什么都没有」。
     *
     * 所以这里的结构是：本方法不带事务，每次尝试用 {@link TransactionTemplate}
     * 开一个新事务跑 {@link #createInTransaction}。上一次尝试的事务已经彻底结束
     * （回滚完毕），下一次是干净的开始。
     *
     * <h3>只在「本次确实填了号」时重试</h3>
     *
     * 没有单据字段的表单，冲突一定来自别的唯一索引（比如业务上给手机号建了唯一索引），
     * 重取号毫无意义，重试只会以同样的方式再失败一次，还白白吞掉两次尝试的时间。
     *
     * <p>反过来，有单据字段的表单若因【别的】唯一索引冲突，这里仍会重试满次数。
     * 代价是多消耗两个单号（跳号）——已经接受的取舍，见下面填号处的注释。
     * 精确区分需要解析异常里的索引名，那既依赖数据库方言，也依赖用户怎么命名索引。
     */
    @Override
    public Object create(String code, Map<String, Object> data) {
        for (int attempt = 1; ; attempt++) {
            // 每次尝试都给一份新的数据副本：填号是就地修改的，
            // 不复制的话第二次尝试会看到第一次留下的号，而那个号已经随事务回滚作废了。
            // 也不依赖「各个 Guard 都是写时复制」这种内部实现细节 —— 那随时可能变
            Map<String, Object> attemptData = copyForAttempt(data);
            BillNumberFilled filled = new BillNumberFilled();
            try {
                return transactionTemplate.execute(
                        status -> createInTransaction(code, attemptData, filled));
            } catch (DuplicateKeyException e) {
                if (!filled.value || attempt >= BILL_NUMBER_MAX_ATTEMPTS) {
                    throw e;
                }
                log.warn("[create][表单({})的单据编号撞上唯一索引，第 {} 次重取号重试]",
                        code, attempt, e);
            }
        }
    }

    /**
     * 单据编号重取号的最大尝试次数
     *
     * 取 3：撞号是低概率事件（同一毫秒内两次提交取到同一个号），连撞三次意味着
     * 要么并发极高、要么号码规则本身产不出足够多的号（比如规则里没有序列号段）。
     * 后者再重试也没用，应当去改规则 —— 无限重试只会把它变成一个转圈的请求。
     */
    private static final int BILL_NUMBER_MAX_ATTEMPTS = 3;

    /**
     * 单次尝试是否填了单号
     *
     * 用一个可变容器而不是返回值：{@link #createInTransaction} 的返回值是主键，
     * 已经被占用了。而这个标志必须能从事务内部传到事务外的重试判断处。
     */
    private static final class BillNumberFilled {
        private boolean value;
    }

    /**
     * 为一次尝试复制提交数据
     *
     * 只复制两层：顶层 Map，以及子表明细那一层的行 Map。这两层是填号会就地改的地方。
     * 不做完整深拷贝 —— 值里可能有任意对象（上传文件的元信息、日期），
     * 通用深拷贝要么走序列化（贵且可能失败），要么写一堆类型判断。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> copyForAttempt(Map<String, Object> data) {
        if (data == null) {
            return null;
        }
        Map<String, Object> copy = new LinkedHashMap<>(data);
        for (Map.Entry<String, Object> entry : copy.entrySet()) {
            if (!(entry.getValue() instanceof Collection<?> collection)) {
                continue;
            }
            List<Object> rows = new ArrayList<>(collection.size());
            boolean anyRow = false;
            for (Object item : collection) {
                if (item instanceof Map<?, ?> map) {
                    rows.add(new LinkedHashMap<>((Map<String, Object>) map));
                    anyRow = true;
                } else {
                    rows.add(item);
                }
            }
            // 不是明细行的集合（多选值、上传列表）原样留着，避免把 Set 变成 List
            // 这类语义变化悄悄传下去
            if (anyRow) {
                entry.setValue(rows);
            }
        }
        return copy;
    }

    /**
     * 在一个事务里执行创建
     *
     * ⚠️ 本方法【不带】@Transactional，事务由调用方的 {@link TransactionTemplate} 开。
     * 加上那个注解反而有害：本方法是被同类的 {@link #create} 直接调用的，
     * 走的是 this 而不是 Spring 代理，注解根本不会生效 ——
     * 而看到注解的人会以为它生效了。这是 Spring 里最经典的一个陷阱。
     *
     * @param filled 出参：本次是否填了单号，供事务外的重试判断使用
     */
    private Object createInTransaction(String code, Map<String, Object> data, BillNumberFilled filled) {
        FormDesignDO design = loadPublishedDesign(code);
        TableBinding binding = parseBinding(design);
        Long dataSourceId = resolveDataSourceId(design);
        Long userId = getLoginUserId();

        // 联动规则复校验 + 服务端扩展点 before。
        // 必须在填号之前：净化后的数据才是扩展点该看到的，
        // 而填号会往 data 里写入服务端生成的值，不应被当成前端提交值去校验
        Map<String, Object> submitted = submitProcessor.before(
                design, FormSubmitContext.Operation.CREATE, null, data, userId);
        // 后续一律用净化后的数据，不再碰原始入参
        data = submitted;

        // 单据字段自动填号。必须在 buildInsert 之前 —— 之后 data 已被拼成 SQL 参数。
        // 注意：号码在事务提交前就已从计数器取走，事务回滚会导致跳号，这是有意的取舍
        // （归还号码在并发下会重号，代价远大于跳号）。
        // 返回值告诉事务外的重试逻辑「这次到底有没有填号」——
        // 没填号的表单，唯一索引冲突一定来自别处，重取号没有意义
        filled.value = billTemplateFormFiller.fill(design, binding, data);

        // 按控件契约把值转成落库形态（数组 → JSON 串 / 分隔串 / 首个 URL）。
        // 必须在填号【之后】：单号也是一个字段值，转换要看到它。
        //
        // ⚠️ 转换结果单独放一份，不覆盖 data —— 服务端扩展点的 after 钩子拿的是 data，
        // 它看到的应当和 before 一样是数组，而不是拼好的串。
        // 「按值查库」的那两步（唯一性、业务主键）也要用落库形态：它们比对的是列里的值
        Map<String, Object> dbData = valueSerializer.serialize(design.getFormFields(), data, binding);


        // 超长值拦截。⚠️ 必须量【落库形态】的 dbData，不是逻辑形态的 data ——
        // 多选的逻辑值是 ["a","b"]、落库是 "a,b"，文件字段落库是一整串 JSON，
        // 长度差得远。放在这里也自动满足了「排在富媒体净化之后」：
        // 净化在 submitProcessor.before 里，base64 已经换成 URL 了。
        // 不拦的话，用户拿到的是一句英文的 Data too long for column，
        // 既看不出是界面上的哪个字段，也不知道上限是多少
        ValueLengthGuard.ValidationResult lengthResult =
                valueLengthGuard.validate(dataSourceId, design.getFormFields(), binding, dbData);
        if (!lengthResult.isPassed()) {
            throw exception(FORM_DESIGN_VALUE_TOO_LONG, lengthResult.toMessage());
        }

        // 字段级唯一性校验。与业务主键去重相邻 —— 两者都是「写库前查一次库」，
        // 都不能替代唯一索引，放在一起便于日后一并回顾
        validateUniqueFields(design, dataSourceId, binding, dbData, null);

        // 业务主键去重。放在填号之后、写库之前：
        // 填号会消耗一个单号，若判重不通过则那个号就浪费了 —— 但这是可接受的，
        // 跳号远好于「为了不跳号而把号还回去」（并发下归还会重号）
        validateBusinessKey(dataSourceId, binding, dbData, null);

        // 按主键策略决定是否预生成主键
        Object pkValue = generatePkIfNeeded(binding);

        FormDesignSqlBuilder.SqlWithParams insert =
                sqlBuilder.buildInsert(dataSourceId, binding, dbData, userId, pkValue);

        Object finalPk;
        if (pkValue != null) {
            // 应用层生成的主键，直接执行
            jdbcTemplate.update(insert.getSql(), insert.getParams().toArray());
            finalPk = pkValue;
        } else {
            // 数据库自增：需要取回生成的主键
            finalPk = executeInsertAndReturnKey(insert);
        }

        // 新建的记录必须落在自己的可见域内。否则用户能造出一条自己都看不见的数据
        // （提交成功、列表里没有），或者把数据直接塞进别人的域
        rowScopeGuard.validateInScope(binding, finalPk);

        // 写副表（1:1）。必须在主表之后 —— 外键要填主表主键
        saveSideTables(dataSourceId, binding, dbData, finalPk, false);

        // 写子表（1:N）
        saveSubTables(dataSourceId, binding, dbData, finalPk, userId, false, design.getFormFields());

        // 服务端扩展点 after。此时主键已确定
        submitProcessor.after(design, FormSubmitContext.Operation.CREATE, finalPk, data, userId);
        // 增量6：广播 create 变更事件。放在最后、return 之前 —— 此时主表/副表/子表都已写入且校验通过，
        // 事务即将提交；AFTER_COMMIT 监听器只在提交成功后才触发 form_event 流程（回滚的尝试不触发）
        publishDataChanged(code, FormDataChangedEvent.TYPE_CREATE, finalPk);
        return finalPk;
    }

    /**
     * 广播一条表单数据变更事件（create / update / delete 三个写入路径共用）
     *
     * <p>🔴 必须在数据变更事务【内】调用：{@code publishEvent} 在当前事务里注册一个 AFTER_COMMIT
     * 同步器，事务提交成功后 agent 侧监听器才被回调；事务回滚的尝试天然不触发下游。
     * 监听器内部还会 try/catch，故本方法「发了就不管」——发布动作本身无 I/O、几乎不可能抛，
     * 且即便下游触发失败也绝不影响调用方已提交的数据（这正是 form_event 触发器的 fail-safe 底线）。
     *
     * @param code      表单编码
     * @param eventType {@link FormDataChangedEvent#TYPE_CREATE} / {@code TYPE_UPDATE} / {@code TYPE_DELETE}
     * @param pkValue   变更行主键，字符串化后作为 dataId 下发（供下游 form_data_get 精确取回该行）
     */
    private void publishDataChanged(String code, String eventType, Object pkValue) {
        eventPublisher.publishEvent(new FormDataChangedEvent(
                code, eventType, String.valueOf(pkValue), TenantContextHolder.getTenantId()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(String code, Object pkValue, Map<String, Object> data) {
        FormDesignDO design = loadPublishedDesign(code);
        TableBinding binding = parseBinding(design);
        Long dataSourceId = resolveDataSourceId(design);
        Long userId = getLoginUserId();

        // 更新前的旧行。变更日志与掩码回写保护都要用它，读一次共用 ——
        // 两处各读一次打的是同一条 SQL
        boolean maskGuardNeeded = dataMasker.hasMaskedMainFields(design.getFormFields());
        Map<String, Object> oldRow =
                readRowBeforeUpdate(binding, dataSourceId, pkValue, maskGuardNeeded);

        // 掩码回写保护 —— ⚠️ 必须排在 submitProcessor.before 【之前】
        //
        // 详情接口下发的是脱敏值（138****8000），前端回传的是整条记录。
        // 不处理的话「只改金额」会把真实手机号写成 138****8000。
        //
        // 为什么必须在 before 之前：before 里有格式校验。一个字段同时配了
        // 脱敏与格式校验时（手机号、身份证、银行卡 —— 恰恰是最需要脱敏的那几个），
        // 掩码值过不了正则，于是【在编辑页什么都不改、直接点保存】会被拦住，
        // 报「请输入 11 位手机号」，而用户根本没碰那个字段。
        //
        // 为什么是「填回原值」而不是「移除这个键」：填回之后，下游的格式校验、
        // 必填校验、联动规则、公式、扩展点看到的都是【真实数据】—— 那本来就是
        // 它们该看到的。移除的话，脱敏且必填的字段会被判成「必填项没填」。
        //
        // 填回原值不会在变更日志里留噪音：FormDataChangeDiffer 逐字段比对，
        // 值相同不产生记录。
        if (maskGuardNeeded) {
            data = dataMasker.restoreMaskedEcho(design.getFormFields(), data, oldRow);
        }

        // 联动规则复校验 + 服务端扩展点 before
        data = submitProcessor.before(
                design, FormSubmitContext.Operation.UPDATE, pkValue, data, userId);

        // 落库形态。理由同 create：扩展点的 after 钩子仍拿逻辑形态的 data
        Map<String, Object> dbData = valueSerializer.serialize(design.getFormFields(), data, binding);


        // 超长值拦截。⚠️ 必须量【落库形态】的 dbData，不是逻辑形态的 data ——
        // 多选的逻辑值是 ["a","b"]、落库是 "a,b"，文件字段落库是一整串 JSON，
        // 长度差得远。放在这里也自动满足了「排在富媒体净化之后」：
        // 净化在 submitProcessor.before 里，base64 已经换成 URL 了。
        // 不拦的话，用户拿到的是一句英文的 Data too long for column，
        // 既看不出是界面上的哪个字段，也不知道上限是多少
        ValueLengthGuard.ValidationResult lengthResult =
                valueLengthGuard.validate(dataSourceId, design.getFormFields(), binding, dbData);
        if (!lengthResult.isPassed()) {
            throw exception(FORM_DESIGN_VALUE_TOO_LONG, lengthResult.toMessage());
        }

        // 字段级唯一性校验。更新时要排除自己
        validateUniqueFields(design, dataSourceId, binding, dbData, pkValue);

        // 业务主键去重。更新时要排除自己 —— 否则改一条记录的备注也会被自己拦下
        validateBusinessKey(dataSourceId, binding, dbData, pkValue);

        // 乐观锁：取出前端回传的版本号。它不是表单字段，键就是版本列名 ——
        // 读取时 SELECT 把这一列带出来，前端原样存着再回传
        Object version = resolveSubmittedVersion(binding, data);

        // 变更日志：更新【之前】把旧值读出来。之后再读就已经是新值了。
        // 旧行已在方法开头连同掩码回写保护一起读过，这里不再重复查询

        FormDesignSqlBuilder.SqlWithParams update =
                sqlBuilder.buildUpdate(dataSourceId, binding, dbData, userId, pkValue, version);
        int affected = jdbcTemplate.update(update.getSql(), update.getParams().toArray());
        if (affected == 0) {
            // 影响行数为 0 有两种原因，必须分开报：
            // 「数据不存在」和「你的修改没保存上，因为别人先改了」对用户是完全不同的两件事，
            // 混成一句「数据不存在」会让人以为记录被删了，跑去问谁删的
            throw exception(resolveUpdateFailure(dataSourceId, binding, pkValue));
        }

        // 改完之后必须仍在可见域内。WHERE 校验的是改【之前】的值，
        // 挡不住「把 dept_id 改成别的部门」这种把记录推出自己域的改法
        rowScopeGuard.validateInScope(binding, pkValue);

        // 更新副表。副表行可能不存在（副表是后加的配置），此时改为 INSERT
        saveSideTables(dataSourceId, binding, dbData, pkValue, true);

        // 子表走逐行 diff，保持未变动明细的主键不变
        saveSubTables(dataSourceId, binding, dbData, pkValue, userId, true, design.getFormFields());

        // 变更日志：写在事务内。
        // ⚠️ 用【逻辑形态】的 data 对比而不是 dbData —— 日志给人看，
        // 「标签：a,b」不如「标签：["a","b"]」，而序列化后的串正是前者
        writeChangeLog(design, binding, pkValue, oldRow, data);

        // 服务端扩展点 after
        submitProcessor.after(design, FormSubmitContext.Operation.UPDATE, pkValue, data, userId);
        // 增量6：广播 update 变更事件（事务内发布 → AFTER_COMMIT 触发 form_event 流程）
        publishDataChanged(code, FormDataChangedEvent.TYPE_UPDATE, pkValue);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateSystemColumns(String code, Object pkValue, Map<String, Object> columnValues) {        if (columnValues == null || columnValues.isEmpty()) {
            return;
        }
        FormDesignDO design = loadPublishedDesign(code);
        TableBinding binding = parseBinding(design);
        Long dataSourceId = resolveDataSourceId(design);

        FormDesignSqlBuilder.SqlWithParams update = sqlBuilder.buildSystemColumnUpdate(
                dataSourceId, binding, columnValues, SYSTEM_WRITABLE_COLUMNS, pkValue);
        int affected = jdbcTemplate.update(update.getSql(), update.getParams().toArray());
        if (affected == 0) {
            throw exception(FORM_DESIGN_RUNTIME_DATA_NOT_EXISTS);
        }
        // 不动副表与子表：系统列只存在于主表
    }

    /**
     * 执行 INSERT 并取回数据库生成的自增主键
     */    private Object executeInsertAndReturnKey(FormDesignSqlBuilder.SqlWithParams insert) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(insert.getSql(),
                    PreparedStatement.RETURN_GENERATED_KEYS);
            List<Object> params = insert.getParams();
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key != null ? key.longValue() : null;
    }

    // ==================== 变更日志 ====================

    /**
     * 变更日志：更新前读一次旧值
     *
     * @return 旧值；未启用变更日志或记录读不到时返回 null
     */
    /**
     * 读更新前的旧行
     *
     * 变更日志与掩码回写保护都要它，合成一次查询。两者都不需要时返回 null ——
     * 这是一次额外查库，不能让用不上的表单也付这个代价。
     *
     * @param force 即使没开变更日志也必须读（掩码回写保护要用）
     */
    private Map<String, Object> readRowBeforeUpdate(TableBinding binding, Long dataSourceId,
                                                    Object pkValue, boolean force) {
        ChangeLogConfig config = binding.getChangeLog();
        boolean changeLogEnabled = config != null && config.isEnabled();
        if (!force && !changeLogEnabled) {
            return null;
        }
        FormDesignSqlBuilder.SqlWithParams select = sqlBuilder.buildGet(dataSourceId, binding, pkValue);
        List<Map<String, Object>> rows =
                jdbcTemplate.queryForList(select.getSql(), select.getParams().toArray());
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 变更日志：对比新旧值并落一条时间线记录
     *
     * <h3>为什么写在事务内</h3>
     *
     * 日志与它记录的那次改动是同一件事。放在事务外的话，事务回滚后日志还在 ——
     * 时间线上会出现一次「其实没发生过」的修改；反之日志写失败而数据已提交，
     * 那次改动就永远查不到了。两种都比「一起成功或一起失败」更难解释。
     *
     * <h3>没有变更时不写</h3>
     *
     * 用户点开详情什么都没改就点保存，是很常见的操作。为它记一条空日志，
     * 会让时间线被一堆「无变更」淹掉，真正的改动反而找不到。
     */
    private void writeChangeLog(FormDesignDO design, TableBinding binding, Object pkValue,
                                Map<String, Object> oldRow, Map<String, Object> newData) {
        ChangeLogConfig config = binding.getChangeLog();
        if (config == null || !config.isEnabled()) {
            return;
        }
        List<FormDataChangeDiffer.FieldChange> changes =
                changeDiffer.diff(design.getFormFields(), oldRow, newData);
        if (changes.isEmpty()) {
            return;
        }
        FormDataChangeLogDO log = new FormDataChangeLogDO();
        log.setFormCode(design.getCode());
        log.setDataId(String.valueOf(pkValue));
        log.setChanges(JsonUtils.toJsonString(changes));
        changeLogMapper.insert(log);
    }

    @Override
    public List<FormDataChangeLogDO> getDataChangeLogs(String code, Object pkValue, int limit) {
        // 走一次已发布表单的加载：它顺带校验了表单存在且已发布。
        // 权限由控制层按 code 校验（与 get 同一条判据）—— 能看数据才能看它的变更历史
        FormDesignDO design = loadPublishedDesign(code);
        return changeLogMapper.selectTimeline(design.getCode(), String.valueOf(pkValue), limit);
    }

    // ==================== 乐观锁 ====================

    /**
     * 取出前端回传的版本号
     *
     * <h3>为什么缺版本号要报错，而不是「跳过版本条件」</h3>
     *
     * 跳过的话，SQL 里就没有版本条件，更新照样成功 —— 于是「开了乐观锁」这件事
     * 对任何漏传版本号的客户端都自动失效，而界面上一切正常。
     * 这正是本模块反复在避免的那类问题：配了但不生效，且不报错。
     *
     * 报错是刺眼的，但它至少能让人发现「这条路径没带版本号」。
     *
     * @return 版本号；未启用乐观锁时返回 null
     */
    private Object resolveSubmittedVersion(TableBinding binding, Map<String, Object> data) {
        OptimisticLockConfig lock = binding.getOptimisticLock();
        if (lock == null || !lock.isUsable()) {
            return null;
        }
        Object version = data == null ? null : data.get(lock.getColumn());
        if (version == null) {
            throw exception(FORM_DESIGN_VERSION_MISSING, lock.getColumn());
        }
        return version;
    }

    /**
     * 判断更新影响行数为 0 的真正原因
     *
     * 两种可能：记录真的不在了（被删/被逻辑删），或者版本号对不上（别人先改了）。
     * 靠一次「不带版本条件的存在性查询」区分 —— 只在失败这条路径上多花一次查询，
     * 正常更新不受影响。
     *
     * 不区分的代价：用户看到「数据不存在」，会以为记录被谁删了，
     * 而真相是他的修改被并发挡下了、数据还在。
     */
    private ErrorCode resolveUpdateFailure(Long dataSourceId, TableBinding binding, Object pkValue) {
        OptimisticLockConfig lock = binding.getOptimisticLock();
        if (lock == null || !lock.isUsable()) {
            return FORM_DESIGN_RUNTIME_DATA_NOT_EXISTS;
        }
        FormDesignSqlBuilder.SqlWithParams exists = sqlBuilder.buildGet(dataSourceId, binding, pkValue);
        boolean stillThere = !jdbcTemplate.queryForList(exists.getSql(), exists.getParams().toArray()).isEmpty();
        return stillThere ? FORM_DESIGN_VERSION_CONFLICT : FORM_DESIGN_RUNTIME_DATA_NOT_EXISTS;
    }

    /**
     * 按主键策略生成主键
     *
     * AUTO 返回 null（交给数据库），SNOWFLAKE / UUID 由应用层生成。
     */
    private Object generatePkIfNeeded(TableBinding binding) {
        return generatePk(binding.getPkStrategy());
    }

    /**
     * 按策略生成一个主键值
     *
     * 主表、副表、子表共用同一套策略语义，所以抽出来复用 ——
     * 三处各写一遍最容易出现「主表支持雪花、从表却写死自增」这类不对称。
     *
     * @return 自增策略或策略无法识别时返回 null，表示交给数据库生成
     */
    private Object generatePk(String strategy) {
        if (StrUtil.isBlank(strategy) || PkStrategyEnum.AUTO.getCode().equals(strategy)) {
            return null;
        }
        if (PkStrategyEnum.SNOWFLAKE.getCode().equals(strategy)) {
            return IdUtil.getSnowflakeNextId();
        }
        if (PkStrategyEnum.UUID.getCode().equals(strategy)) {
            return IdUtil.fastSimpleUUID();
        }
        return null;
    }

    /**
     * 保存副表（1:1）
     *
     * 与子表的关键区别：副表是一对一，不需要 diff，直接 UPDATE 或 INSERT。
     *
     * update 场景下先尝试 UPDATE，影响行数为 0 时再确认副表里到底有没有这一行：
     * 没有才 INSERT。原实现「影响行数为 0 就 INSERT」有两个漏洞，
     * 都会在副表里堆出重复行，把 1:1 关系彻底破坏掉：
     * 1. 副表没配字段映射时 builder 返回 null，被当成「更新失败」，于是每次保存都插一行
     * 2. UPDATE 的返回值在不同驱动下语义不同（匹配行数 vs 实际变更行数），
     *    取「实际变更行数」的驱动上，值没变的更新会返回 0，同样会误插
     *
     * @param isUpdate true 走「先 UPDATE，确认不存在才 INSERT」；false 直接 INSERT
     */
    private void saveSideTables(Long dataSourceId, TableBinding binding,
                                Map<String, Object> data, Object mainPkValue, boolean isUpdate) {
        if (binding.getSideTables() == null || binding.getSideTables().isEmpty()) {
            return;
        }
        for (SideTableBinding side : binding.getSideTables()) {
            if (!isSideTableUsable(side)) {
                continue;
            }
            if (isUpdate) {
                FormDesignSqlBuilder.SqlWithParams update =
                        sqlBuilder.buildSideTableUpdate(dataSourceId, binding, side, data, mainPkValue);
                if (update != null
                        && jdbcTemplate.update(update.getSql(), update.getParams().toArray()) > 0) {
                    continue;
                }
                // 走到这里有两种可能：副表真的还没有这一行（配置是后加的），
                // 或者只是 UPDATE 没报告变更。查一下才能区分，否则会插出重复行
                if (sideRowExists(dataSourceId, binding, side, mainPkValue)) {
                    continue;
                }
                log.debug("副表 {} 中主键 {} 无对应行，改为 INSERT", side.getTable(), mainPkValue);
            }
            FormDesignSqlBuilder.SqlWithParams insert =
                    sqlBuilder.buildSideTableInsert(dataSourceId, binding, side, data, mainPkValue,
                            generatePk(side.getPkStrategy()));
            jdbcTemplate.update(insert.getSql(), insert.getParams().toArray());
        }
    }

    /**
     * 副表里是否已存在该主表记录对应的行
     *
     * 只在 UPDATE 没报告变更时才查，正常路径不产生额外开销。
     */
    private boolean sideRowExists(Long dataSourceId, TableBinding binding,
                                  SideTableBinding side, Object mainPkValue) {
        FormDesignSqlBuilder.SqlWithParams select =
                sqlBuilder.buildSideTableSelect(dataSourceId, binding, side, mainPkValue);
        return !jdbcTemplate.queryForList(select.getSql(), select.getParams().toArray()).isEmpty();
    }

    /**
     * 副表配置是否可用
     *
     * 表名与外键列缺一不可 —— 外键是副表与主表的唯一纽带，
     * 缺了它无法定位副表行。发布校验会拦住这种配置，这里是运行时兜底。
     */
    private boolean isSideTableUsable(SideTableBinding side) {
        return side != null && StrUtil.isNotBlank(side.getTable())
                && StrUtil.isNotBlank(side.getForeignKey());
    }

    /**
     * 保存子表明细
     *
     * @param isUpdate true 走逐行 diff（保持未变动明细的主键不变）；false 全量插入
     */
    private void saveSubTables(Long dataSourceId, TableBinding binding, Map<String, Object> data,
                               Object mainPkValue, Long userId, boolean isUpdate,
                               String formFieldsJson) {
        if (binding.getSubTables() == null || binding.getSubTables().isEmpty()) {
            return;
        }
        for (SubTableBinding subTable : binding.getSubTables()) {
            if (!isSubTableUsable(subTable)) {
                continue;
            }
            List<Map<String, Object>> rows = extractSubRows(data.get(subTable.getField()));
            if (isUpdate) {
                applySubTableDiff(dataSourceId, subTable, rows, mainPkValue, userId, formFieldsJson);
            } else {
                for (Map<String, Object> row : rows) {
                    insertSubRow(dataSourceId, subTable, row, mainPkValue, userId);
                }
            }
        }
    }

    /**
     * 子表配置是否可用
     *
     * 表名、控件 field、外键三者缺一不可，且必须配了字段映射 ——
     * 映射为空时读写两侧都只剩主键与外键，明细字段既存不进去也查不出来，
     * 而且不会有任何报错。发布校验会拦住这种配置，这里是运行时兜底：
     * 记警告而不是静默跳过，否则线上排查时完全无迹可循。
     */
    private boolean isSubTableUsable(SubTableBinding subTable) {
        if (subTable == null || StrUtil.isBlank(subTable.getTable())) {
            return false;
        }
        if (StrUtil.isBlank(subTable.getField())) {
            log.warn("子表 {} 未配置控件 field，无法从提交数据里定位明细，已跳过", subTable.getTable());
            return false;
        }
        if (StrUtil.isBlank(subTable.getForeignKey())) {
            log.warn("子表 {} 未配置外键列，明细无法关联主表记录，已跳过", subTable.getTable());
            return false;
        }
        if (subTable.getFieldMapping() == null || subTable.getFieldMapping().isEmpty()) {
            log.warn("子表 {}（控件 {}）没有字段映射，明细的业务字段不会被读写。"
                            + "请在「表单设计」步骤为子表控件内的字段选择物理列，然后重新发布",
                    subTable.getTable(), subTable.getField());
            return false;
        }
        return true;
    }

    /**
     * 按逐行 diff 结果落库
     *
     * 之所以不做「删光再插」：很多业务会关联明细表的 id（外部引用、附件挂载等），
     * 全量替换会让 id 漂移，下游引用直接断掉。
     */
    private void applySubTableDiff(Long dataSourceId, SubTableBinding subTable,
                                   List<Map<String, Object>> rows, Object mainPkValue, Long userId,
                                   String formFieldsJson) {
        Set<Object> existingPks = loadExistingSubPks(dataSourceId, subTable, mainPkValue);
        SubTableDiffEngine.DiffResult diff = SubTableDiffEngine.diff(subTable, rows, existingPks);

        // 明细的掩码回写保护。子表明细同样被脱敏下发，不保护的话「只改一行的数量」
        // 会把同一行的手机号写成 138****5678 —— 与主表同一个坑，只保主表等于留了半扇门。
        // 按主键索引现存明细；新增行没有对应项，stripMaskedEchoForSubRow 会原样放行
        Map<String, Map<String, Object>> currentSubRows =
                dataMasker.hasMaskedSubFields(formFieldsJson, subTable.getField())
                        ? indexSubRowsByPk(loadSubRows(dataSourceId, subTable, mainPkValue), subTable)
                        : Map.of();

        for (Map<String, Object> row : diff.getToInsert()) {
            insertSubRow(dataSourceId, subTable, row, mainPkValue, userId);
        }
        for (Map<String, Object> row : diff.getToUpdate()) {
            Map<String, Object> guarded = currentSubRows.isEmpty() ? row
                    : dataMasker.stripMaskedEchoForSubRow(formFieldsJson, subTable.getField(), row,
                            currentSubRows.get(String.valueOf(row.get(subPk(subTable)))));
            updateSubRow(dataSourceId, subTable, guarded, userId);
        }
        for (Object pk : diff.getToDeletePks()) {
            deleteSubRow(dataSourceId, subTable, pk, userId);
        }
    }

    /**
     * 按主键索引明细行
     *
     * 键统一转成字符串：主键从库里读出来是 Long，从前端回传的可能是字符串或
     * Integer，直接拿对象做 Map 的键会匹配不上（表现为保护静默失效）。
     */
    private Map<String, Map<String, Object>> indexSubRowsByPk(List<Map<String, Object>> rows,
                                                              SubTableBinding subTable) {
        Map<String, Map<String, Object>> result = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Object pk = row.get(subPk(subTable));
            if (pk != null) {
                result.put(String.valueOf(pk), row);
            }
        }
        return result;
    }

    /**
     * 查出该主表记录下现存的明细主键
     */
    private Set<Object> loadExistingSubPks(Long dataSourceId, SubTableBinding subTable, Object mainPkValue) {
        // 白名单必须含外键与逻辑删除列 —— buildSubTableSelect 要用它们校验 WHERE 条件，
        // 只放主键会抛 FORM_DESIGN_IDENTIFIER_INVALID，导致带子表的更新直接失败
        Set<String> pkWhitelist = new HashSet<>();
        pkWhitelist.add(subPk(subTable));
        if (StrUtil.isNotBlank(subTable.getForeignKey())) {
            pkWhitelist.add(subTable.getForeignKey());
        }
        LogicDeleteConfig ld = subTable.getLogicDelete();
        if (ld != null && ld.getEnabled() != null && ld.getEnabled()
                && StrUtil.isNotBlank(ld.getColumn())) {
            pkWhitelist.add(ld.getColumn());
        }
        FormDesignSqlBuilder.SqlWithParams select = sqlBuilder.buildSubTableSelect(
                dataSourceId, subTable.getTable(), subTable.getForeignKey(),
                pkWhitelist, mainPkValue, subTable.getLogicDelete());
        List<Map<String, Object>> existing =
                jdbcTemplate.queryForList(select.getSql(), select.getParams().toArray());
        Set<Object> pks = new HashSet<>();
        for (Map<String, Object> row : existing) {
            Object pk = row.get(subPk(subTable));
            if (pk != null) {
                pks.add(pk);
            }
        }
        return pks;
    }

    private void insertSubRow(Long dataSourceId, SubTableBinding subTable,
                              Map<String, Object> row, Object mainPkValue, Long userId) {
        // 插入时要写外键，把它当成一个虚拟字段挂进映射
        TableBinding subBinding = toSubTableBinding(subTable, true);
        // 外键指回主表主键，由后端强制填充，不信前端传的值
        Map<String, Object> rowData = new HashMap<>(row);
        rowData.put(FK_VIRTUAL_FIELD, mainPkValue);
        // 明细主键按子表自己的策略生成。自增策略返回 null，交给数据库
        Object subPkValue = generatePk(subTable.getPkStrategy());
        FormDesignSqlBuilder.SqlWithParams insert =
                sqlBuilder.buildInsert(dataSourceId, subBinding, rowData, userId, subPkValue);
        jdbcTemplate.update(insert.getSql(), insert.getParams().toArray());
    }

    private void updateSubRow(Long dataSourceId, SubTableBinding subTable,
                              Map<String, Object> row, Long userId) {
        // 更新【不】带外键：明细永远属于同一条主记录，外键不该变。
        // 带上它会让 buildUpdate 把外键也拼进 SET 子句，而提交上来的明细行里
        // 没有那个虚拟字段，取到 null，直接 Column 'xxx_id' cannot be null
        TableBinding subBinding = toSubTableBinding(subTable, false);
        Object pkValue = row.get(subPk(subTable));
        FormDesignSqlBuilder.SqlWithParams update =
                sqlBuilder.buildUpdate(dataSourceId, subBinding, row, userId, pkValue);
        jdbcTemplate.update(update.getSql(), update.getParams().toArray());
    }

    private void deleteSubRow(Long dataSourceId, SubTableBinding subTable,
                             Object pkValue, Long userId) {
        TableBinding subBinding = toSubTableBinding(subTable, false);
        FormDesignSqlBuilder.SqlWithParams delete =
                sqlBuilder.buildDelete(dataSourceId, subBinding, userId, pkValue);
        jdbcTemplate.update(delete.getSql(), delete.getParams().toArray());
    }

    /**
     * 把子表配置适配成 TableBinding，以复用 SqlBuilder
     *
     * ⚠️ 主键策略这里固定传 AUTO：明细主键由 {@link #insertSubRow} 按子表策略生成后
     * 显式传给 buildInsert，不走 binding 里的策略。两处都生成会各生成一个不同的值。
     *
     * @param includeForeignKey 是否把外键当成一个虚拟字段挂进映射。
     *                          INSERT 需要（外键要落库），UPDATE 与 DELETE 不需要 ——
     *                          带上它会让 buildUpdate 把外键写进 SET 子句，
     *                          而值取自提交数据里并不存在的虚拟字段，必然是 null
     */
    private TableBinding toSubTableBinding(SubTableBinding subTable, boolean includeForeignKey) {
        TableBinding binding = new TableBinding();
        binding.setMainTable(subTable.getTable());
        binding.setMainPk(subPk(subTable));
        binding.setPkStrategy(PkStrategyEnum.AUTO.getCode());
        binding.setLogicDelete(subTable.getLogicDelete());
        // 明细行也要审计字段。子表的审计列名可能与主表不同，所以用子表自己的配置
        binding.setAutoFill(subTable.getAutoFill());

        Map<String, FieldMapping> mapping = new HashMap<>();
        if (subTable.getFieldMapping() != null) {
            mapping.putAll(subTable.getFieldMapping());
        }
        // 映射到主键列的字段不能留在 fieldMapping 里：buildInsert / buildUpdate 会把映射里的
        // 每一列都拼进列清单，主键在 INSERT 里会与显式传入的主键撞成两列，在 UPDATE 里则会被
        // 写进 SET 子句（等于改主键）。明细行的主键由 diff 引擎单独取用。
        // 注意按【物理列名】剔除而不是按 field 名 —— 用户可能把主键列映射成任意 field 名
        mapping.values().removeIf(m -> m != null && Objects.equals(m.getColumn(), subPk(subTable)));
        // 外键同理：它由后端强制填充，绝不能让前端提交的值覆盖
        mapping.values().removeIf(
                m -> m != null && Objects.equals(m.getColumn(), subTable.getForeignKey()));
        if (includeForeignKey && StrUtil.isNotBlank(subTable.getForeignKey())) {
            FieldMapping fk = new FieldMapping();
            fk.setTable(subTable.getTable());
            fk.setColumn(subTable.getForeignKey());
            mapping.put(FK_VIRTUAL_FIELD, fk);
        }
        binding.setFieldMapping(mapping);
        return binding;
    }

    private String subPk(SubTableBinding subTable) {
        return SubTableRowMapper.pkColumnOf(subTable);
    }

    /**
     * 从提交数据里取出子表行
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractSubRows(Object value) {
        if (value == null) {
            return Collections.emptyList();
        }
        if (value instanceof List<?> list) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    rows.add((Map<String, Object>) map);
                }
            }
            return rows;
        }
        return Collections.emptyList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String code, Object pkValue) {
        FormDesignDO design = loadPublishedDesign(code);
        TableBinding binding = parseBinding(design);
        Long dataSourceId = resolveDataSourceId(design);
        Long userId = getLoginUserId();

        // 服务端扩展点 beforeDelete。扩展点可以据此拦住删除
        // （比如「已审批的单据不允许删除」这类规则）
        submitProcessor.before(design, FormSubmitContext.Operation.DELETE,
                pkValue, new LinkedHashMap<>(), userId);

        // 删除顺序与创建相反：子表 → 副表 → 主表，避免外键约束报错
        if (binding.getSubTables() != null) {
            for (SubTableBinding subTable : binding.getSubTables()) {
                // 删除只需要表名与外键，不要求 fieldMapping ——
                // 映射漏配的历史数据同样得清理干净，否则主表删了、明细留成孤儿行
                if (subTable == null || StrUtil.isBlank(subTable.getTable())
                        || StrUtil.isBlank(subTable.getForeignKey())) {
                    continue;
                }
                deleteSubTableByForeignKey(dataSourceId, subTable, pkValue, userId);
            }
        }

        // 再删副表
        if (binding.getSideTables() != null) {
            for (SideTableBinding side : binding.getSideTables()) {
                if (!isSideTableUsable(side)) {
                    continue;
                }
                FormDesignSqlBuilder.SqlWithParams sideDelete =
                        sqlBuilder.buildSideTableDelete(dataSourceId, binding, side, pkValue);
                // 副表行不存在是正常的（副表可能是后加的配置），不校验影响行数
                jdbcTemplate.update(sideDelete.getSql(), sideDelete.getParams().toArray());
            }
        }

        // 最后删主表
        FormDesignSqlBuilder.SqlWithParams delete =
                sqlBuilder.buildDelete(dataSourceId, binding, userId, pkValue);
        int affected = jdbcTemplate.update(delete.getSql(), delete.getParams().toArray());
        if (affected == 0) {
            throw exception(FORM_DESIGN_RUNTIME_DATA_NOT_EXISTS);
        }
        // 增量6：广播 delete 变更事件（事务内发布 → AFTER_COMMIT 触发 form_event 流程）。
        // deleteBatch 逐 pk 复用本方法，故批量删除时每行各发一条 delete 事件，无需在 deleteBatch 里另发
        publishDataChanged(code, FormDataChangedEvent.TYPE_DELETE, pkValue);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteBatch(String code, List<Object> pkValues) {
        if (CollUtil.isEmpty(pkValues)) {
            return;
        }
        // 去重：同一个主键传两次会让第二次删不到行而抛「数据不存在」，
        // 而用户那边只是多勾了一下
        List<Object> distinctPks = pkValues.stream().filter(Objects::nonNull).distinct().toList();
        if (distinctPks.size() > BATCH_DELETE_MAX) {
            throw exception(FORM_DESIGN_RUNTIME_BATCH_TOO_LARGE, BATCH_DELETE_MAX);
        }
        // 复用单条删除：子表/副表的删除顺序、逻辑删除、扩展点拦截都在那里，
        // 另写一套批量 SQL 等于把那些规则再实现一遍，且一定会漏掉其中几条。
        // 整个方法一个事务，所以「删到一半失败」不会留下中间状态
        for (Object pkValue : distinctPks) {
            delete(code, pkValue);
        }
    }

    /**
     * 按外键删除子表所有明细
     */
    private void deleteSubTableByForeignKey(Long dataSourceId, SubTableBinding subTable,
                                           Object mainPkValue, Long userId) {
        // 先查出所有明细主键，再逐个删（配了逻辑删除时需要更新 updater/update_time）
        Set<Object> existingPks = loadExistingSubPks(dataSourceId, subTable, mainPkValue);
        for (Object pk : existingPks) {
            deleteSubRow(dataSourceId, subTable, pk, userId);
        }
    }

    @Override
    public Map<String, Object> get(String code, Object pkValue) {
        FormDesignDO design = loadPublishedDesign(code);
        TableBinding binding = parseBinding(design);
        Long dataSourceId = resolveDataSourceId(design);

        FormDesignSqlBuilder.SqlWithParams select = sqlBuilder.buildGet(dataSourceId, binding, pkValue);
        List<Map<String, Object>> rows =
                jdbcTemplate.queryForList(select.getSql(), select.getParams().toArray());
        if (rows.isEmpty()) {
            throw exception(FORM_DESIGN_RUNTIME_DATA_NOT_EXISTS);
        }
        Map<String, Object> mainRow = rows.get(0);

        // 合并副表字段（1:1）。副表的列直接平铺进主行，表单里它们与主表字段无差别
        mergeSideTableData(dataSourceId, binding, mainRow, pkValue);

        // 嵌套子表数据
        if (binding.getSubTables() != null) {
            for (SubTableBinding subTable : binding.getSubTables()) {
                if (!isSubTableUsable(subTable)) {
                    // 配置不完整时给出空数组而不是不放这个键 —— 前端子表控件拿到 undefined
                    // 会保留上一条记录的明细，看起来像「串数据」
                    if (subTable != null && StrUtil.isNotBlank(subTable.getField())) {
                        mainRow.put(subTable.getField(), Collections.emptyList());
                    }
                    continue;
                }
                List<Map<String, Object>> subRows = loadSubRows(dataSourceId, subTable, pkValue);
                mainRow.put(subTable.getField(), subRows);
            }
        }
        // 还原落库形态：JSON 串 / 分隔串 → 数组。必须与写入成对，
        // 只写不读的表现是「提交成功了，打开却是一串原始字符串」。
        // 放在脱敏之前：脱敏按字段处理值，让它看到还原后的形态更直观
        mainRow = valueSerializer.deserialize(design.getFormFields(), mainRow, binding);
        // 时间类值统一成字符串再下发。Jackson 对 java.sql.Date 的默认写法是纪元毫秒数，
        // 而日期控件被 form-create 补了 valueFormat，解析不了毫秒数 ——
        // 表现是「编辑时选过日期，再打开却是空的」。详见 TemporalValueCoercer
        mainRow = TemporalValueCoercer.normalizeRow(mainRow);
        // 脱敏。放在最后一步 —— 前面的副表合并、子表填充都要拿到明文才能正确关联
        mainRow = dataMasker.mask(design.getFormFields(), mainRow);
        // 字段级裁剪。必须与脱敏在同一个出口 —— 漏一个出口就是一扇后门。
        // 详情走 FIELD 资源（列表列走 COLUMN，两者分开控制）
        return fieldCropper.cropRow(design.getCode(), mainRow,
                fieldCropper.collectFieldKeys(design.getFormFields()), false);
    }

    /**
     * 业务主键去重
     *
     * 按配置的几个字段判重：库里已存在这几个字段值全都相同的记录时直接拒绝。
     *
     * <p>这是「重复提交」的最后一道防线。前端的防抖、按钮置灰都只能挡住误触，
     * 挡不住刷新重发、并发双击、或直接构造请求。
     *
     * <p><b>它不能替代唯一索引</b>：两个请求同时通过判重、再同时插入，
     * 仍会产生两条重复数据。真正的兜底是数据库唯一索引 —— 本校验的价值在于
     * 把错误提前到一个用户能看懂的提示，而不是一个约束冲突的堆栈。
     *
     * @param excludePkValue 更新时传自己的主键以排除自身；新增时传 null
     */
    private void validateBusinessKey(Long dataSourceId, TableBinding binding,
                                     Map<String, Object> data, Object excludePkValue) {
        TableBinding.BusinessKeyConfig config = binding.getBusinessKey();
        if (config == null || !config.isEnabled()) {
            return;
        }
        List<FormDesignSqlBuilder.QueryCondition> conditions = new ArrayList<>();
        for (String field : config.getFields()) {
            Object value = data == null ? null : data.get(field);
            if (value == null || (value instanceof CharSequence cs && cs.isEmpty())) {
                // 参与判重的字段有空值时跳过整次判重：
                // 把空值也算进判重条件，会让「两条都还没填某个字段」的记录互相冲突，
                // 而它们其实是两条正常的草稿
                return;
            }
            FormDesignSqlBuilder.QueryCondition condition = new FormDesignSqlBuilder.QueryCondition();
            condition.setField(field);
            condition.setOperator("EQ");
            condition.setValue(value);
            conditions.add(condition);
        }
        if (conditions.isEmpty()) {
            return;
        }

        FormDesignSqlBuilder.SqlWithParams count =
                sqlBuilder.buildCount(dataSourceId, binding, conditions);
        Long existing = jdbcTemplate.queryForObject(count.getSql(), Long.class,
                count.getParams().toArray());
        if (existing == null || existing == 0) {
            return;
        }
        // 更新时命中一条且就是自己，属于正常情况。
        // 这里用「命中数 > 1」而不是去查主键：多查一次只为区分自己，
        // 而命中多条本身就已经是重复数据，无论如何都该拦
        if (excludePkValue != null && existing == 1) {
            return;
        }
        throw exception(FORM_DESIGN_BUSINESS_KEY_DUPLICATE, config.tipOrDefault());
    }

    /**
     * 字段级唯一性校验
     *
     * 与业务主键去重（{@link #validateBusinessKey}）的分工：
     * <ul>
     *   <li>业务主键：一组字段的【组合】不能重复，整张表单配一份，用于挡重复提交</li>
     *   <li>本方法：单个字段自己不能重复（手机号、编号），可以配在任意多个字段上</li>
     * </ul>
     *
     * 两者都不能替代数据库唯一索引 —— 两个请求同时通过校验再同时插入，仍会产生重复。
     * 它们的价值是把错误提前到一个用户能看懂的提示，而不是一个约束冲突的堆栈。
     *
     * <p>空值跳过：没填的字段谈不上重复，而把空值算进条件会让「两条都还没填」的
     * 记录互相冲突。字段该不该填由必填管。
     *
     * @param excludePkValue 更新时传自己的主键以排除自身；新增时传 null
     */
    private void validateUniqueFields(FormDesignDO design, Long dataSourceId, TableBinding binding,
                                      Map<String, Object> data, Object excludePkValue) {
        List<FormValidationGuard.ValidatedField> uniqueFields =
                validationGuard.parseUniqueFields(design.getFormFields());
        if (uniqueFields.isEmpty()) {
            return;
        }
        List<String> duplicated = new ArrayList<>();
        for (FormValidationGuard.ValidatedField field : uniqueFields) {
            Object value = data == null ? null : data.get(field.getField());
            if (value == null || (value instanceof CharSequence cs && cs.isEmpty())) {
                continue;
            }
            if (countByFieldValue(dataSourceId, binding, field.getField(), value, excludePkValue)) {
                duplicated.add(field.label());
            }
        }
        if (!duplicated.isEmpty()) {
            // 多个字段重复时一次性全报：改完一个再提交又被拦下一个，用户要来回好几轮
            throw exception(FORM_DESIGN_UNIQUE_VIOLATION, String.join("、", duplicated));
        }
    }

    /**
     * 某个字段的某个值在库里是否已存在
     *
     * @param excludePkValue 更新时传自己的主键。判定用「命中数 > 1」而不是再查一次主键
     *                       来排除自己 —— 多查一次只为区分自己，而命中多条本身就已经是
     *                       重复数据，无论如何都该拦
     */
    private boolean countByFieldValue(Long dataSourceId, TableBinding binding, String field,
                                      Object value, Object excludePkValue) {
        FormDesignSqlBuilder.QueryCondition condition = new FormDesignSqlBuilder.QueryCondition();
        condition.setField(field);
        condition.setOperator("EQ");
        condition.setValue(value);

        FormDesignSqlBuilder.SqlWithParams count =
                sqlBuilder.buildCount(dataSourceId, binding, List.of(condition));
        Long existing = jdbcTemplate.queryForObject(count.getSql(), Long.class,
                count.getParams().toArray());
        if (existing == null || existing == 0) {
            return false;
        }
        return excludePkValue == null || existing > 1;
    }

    /**
     * 校验某个字段的值是否可用（未被占用）
     *
     * 给前端失焦即时校验用。它与提交时的 {@link #validateUniqueFields} 查的是同一条
     * SQL —— 前端拿到的结论与提交时的结论一致，不会出现「输入时说可以、提交时说重复」。
     *
     * <p>只对【配了唯一性校验的字段】放行。不做这个限制的话，本接口就成了一个
     * 「任意列 + 任意值 → 存在与否」的探测口：拿它可以逐个试出某张表里有哪些手机号，
     * 而这些字段本身可能是脱敏返回的。
     */
    @Override
    public boolean checkFieldUnique(String code, String field, Object value, Object excludePkValue) {
        FormDesignDO design = loadPublishedDesign(code);
        boolean declared = validationGuard.parseUniqueFields(design.getFormFields()).stream()
                .anyMatch(item -> item.getField().equals(field));
        if (!declared) {
            // 不是配了唯一性校验的字段，直接说「可用」而不是报错：
            // 报错会让前端把它当成校验失败提示给用户，而这其实是配置问题
            log.warn("[checkFieldUnique][表单({})的字段({})没有配唯一性校验，跳过检查]", code, field);
            return true;
        }
        if (value == null || (value instanceof CharSequence cs && cs.isEmpty())) {
            return true;
        }
        TableBinding binding = parseBinding(design);
        return !countByFieldValue(resolveDataSourceId(design), binding, field, value, excludePkValue);
    }

    @Override
    public Object getPlainFieldValue(String code, Object id, String field) {
        FormDesignDO design = loadPublishedDesign(code);

        // 只对确实配了脱敏的字段放行。不加这一道，本接口就是一个
        // 「任意行 + 任意列 → 明文」的读取口，比不脱敏还糟 ——
        // 脱敏至少还挡住了列表与导出两条路。思路同 checkFieldUnique
        boolean masked = dataMasker.parseMaskedFields(design.getFormFields()).stream()
                // 只管主表字段：子表明细的明文查看尚未开放，
                // 放行的话这里还得带上「哪一行明细」，接口形状完全不同
                .anyMatch(item -> item.getSubField() == null && item.getField().equals(field));
        if (!masked) {
            throw exception(FORM_DESIGN_FIELD_NOT_MASKED, field);
        }

        // ⚠️ 字段权限必须在这里再判一次，这是本接口最容易被忽略的一道门。
        //
        // 它是第五个读取出口 —— config / get / page / export 四处都裁了，
        // 唯独这里没裁的话，被判为 HIDDEN 的字段可以从这个口子原样读出来，
        // 而且拿到的是**明文**，比不裁剪的 get 还多泄露一层。
        // 实测确认过：get 里 contact_mobile 已删键，本接口却仍返回 13900001111。
        //
        // 判据是「必须 EDITABLE」而不是「不是 HIDDEN」，理由与上面 checkPermission
        // 用 update 而不是 query 一致 —— **看明文是为了修改**。
        // 既然 READONLY 的字段用户改不了（提交值会被 stripNonWritable 丢弃），
        // 那就没有任何需要看明文的场景，放行只是白白扩大泄露面
        String mode = fieldCropper.resolve(code, List.of(field), false).modeOf(field);
        if (!DatasetFieldModeRespDTO.EDITABLE.equals(mode)) {
            log.warn("[getPlainFieldValue][表单({}) 字段({}) 的权限模式是 {}，拒绝返回明文]",
                    code, field, mode);
            // 被拒也要留痕，而且它比成功那条更值得看：
            // 连着去点自己无权查看的字段是探测行为的典型形状
            auditor.plainFieldDenied(code, id, field, mode);
            throw exception(FORM_DESIGN_FIELD_PLAIN_FORBIDDEN, field);
        }

        TableBinding binding = parseBinding(design);
        Long dataSourceId = resolveDataSourceId(design);
        FormDesignSqlBuilder.SqlWithParams select = sqlBuilder.buildGet(dataSourceId, binding, id);
        List<Map<String, Object>> rows =
                jdbcTemplate.queryForList(select.getSql(), select.getParams().toArray());
        if (rows.isEmpty()) {
            throw exception(FORM_DESIGN_RUNTIME_DATA_NOT_EXISTS);
        }
        // 只还原这一个字段，不回传整行 —— 整行会把同一条记录上别的脱敏字段
        // 一并带出去，等于一次点击解开了所有敏感列
        Map<String, Object> row = valueSerializer.deserialize(design.getFormFields(), rows.get(0), binding);
        // ⚠️ 审计记在【真的取到值之后】，而不是入口处 ——
        // 记在入口的话，「数据不存在」「字段没配脱敏」这些根本没看到东西的请求
        // 也会留下一条「查看了明文」，久了这张表就没人信了。
        //
        // ⚠️ 只记字段名，不记值。把明文抄进日志表等于二次泄露：
        // 敏感值就此脱离脱敏与字段权限的管辖，谁有「操作日志」菜单谁就能翻
        auditor.plainFieldViewed(code, id, field);
        return row.get(field);
    }

    /**
     * 合并副表字段到主行
     *
     * 副表是 1:1，其字段在表单里与主表字段没有区别，所以平铺进同一个 Map，
     * 而不是像子表那样嵌套成数组。
     *
     * ⚠️ 1:1 关系数据库层面保证不了：若副表外键上没有唯一索引，
     * 同一个外键值可能查出多行。这里取第一行并记警告日志 ——
     * 静默取第一行会让数据不一致长期隐藏，报错则会让存量脏数据直接不可读。
     */
    private void mergeSideTableData(Long dataSourceId, TableBinding binding,
                                    Map<String, Object> mainRow, Object pkValue) {
        if (binding.getSideTables() == null || binding.getSideTables().isEmpty()) {
            return;
        }
        for (SideTableBinding side : binding.getSideTables()) {
            if (!isSideTableUsable(side)) {
                continue;
            }
            FormDesignSqlBuilder.SqlWithParams select =
                    sqlBuilder.buildSideTableSelect(dataSourceId, binding, side, pkValue);
            List<Map<String, Object>> sideRows =
                    jdbcTemplate.queryForList(select.getSql(), select.getParams().toArray());
            if (sideRows.isEmpty()) {
                continue;
            }
            if (sideRows.size() > 1) {
                log.warn("副表 {} 中外键 {} = {} 查到 {} 行，1:1 关系被破坏，已取第一行。"
                                + "建议在该外键列上加唯一索引",
                        side.getTable(), side.getForeignKey(), pkValue, sideRows.size());
            }
            Map<String, Object> sideRow = sideRows.get(0);
            // 副表列已在 SQL 里 AS 成 yd_表名_yd_列名，与主表列不可能重名，直接合并。
            // 改动前用 putIfAbsent 是因为键是裸列名，主副表同名列（如 id）会撞车 ——
            // 那样副表的值会被静默丢弃，现在前缀隔离后不再需要这层保护。
            mainRow.putAll(sideRow);
        }
    }

    /**
     * 关联表单控件的候选数据（{@code REFERENCE} 弱查询）
     *
     * <h3>它解决的问题</h3>
     *
     * 关联控件原先直接调 {@code /page}，于是「填订单要选客户」这件事
     * 要求用户有<b>客户表单的完整 query 权限</b> —— 给了，他就能浏览客户列表的
     * 全部数据、全部字段、还能导出。不给，关联控件点开是空的，订单填不下去。
     *
     * <h3>为什么目标表单与可见字段都不接受调用方传</h3>
     *
     * 这是本方法全部安全性的来源，与 {@code invokeButtonInterface} 同一条路数：
     * 调用方只能指定「用源表单上的哪个关联控件」，而<b>目标表单编码、显示字段、
     * 带出字段</b>统统从源表单的<b>已发布配置</b>里读。
     *
     * 反过来（让调用方传 targetCode + fields）的话，这个端点就退化成
     * 「任意表单 + 任意字段」的读取口 —— 比它想替代的 query 权限还宽。
     *
     * <h3>返回的字段被限制在三类</h3>
     *
     * 主键、显示字段、带出映射里声明的源字段。除此之外一概不返回 ——
     * 关联控件在界面上也只用得到这些。
     *
     * <h3>仍然受行级规则约束</h3>
     *
     * 走的是 {@code buildPage}，行过滤在 SQL 构建器内部追加，
     * 所以「业务员只能引用自己数据范围内的客户」是自动成立的，不需要额外处理。
     *
     * @param sourceCode  正在填写的那张表单（引用方）
     * @param sourceField 该表单上关联控件的字段名
     * @param keyword     按显示字段模糊搜；为空则取前若干条
     */
    @Override
    public List<Map<String, Object>> referenceOptions(String sourceCode, String sourceField,
                                                      String keyword, Integer limit) {
        FormDesignDO sourceDesign = loadPublishedDesign(sourceCode);
        RelationFormResolver.RelationField relation =
                findRelationField(sourceDesign, sourceField);
        String targetCode = relation.getTargetFormCode();
        FormDesignDO targetDesign = loadPublishedDesign(targetCode);
        TableBinding binding = parseBinding(targetDesign);
        Long dataSourceId = resolveDataSourceId(targetDesign);

        // 只搜显示字段。搜主键没有意义 —— 用户看到的是名称，输入的也是名称
        List<FormDesignSqlBuilder.QueryCondition> conditions = new ArrayList<>();
        if (StrUtil.isNotBlank(keyword) && StrUtil.isNotBlank(relation.getDisplayField())) {
            FormDesignSqlBuilder.QueryCondition condition = new FormDesignSqlBuilder.QueryCondition();
            condition.setField(relation.getDisplayField());
            condition.setOperator("LIKE");
            condition.setValue(keyword);
            conditions.add(condition);
        }
        // ⚠️ 条数上限是这个动作「弱」在哪里的体现之一：它是给「搜一下、选一条」用的，
        // 不是给「翻着浏览」用的。所以不开放 pageNo —— 开放了就等于 query
        int limited = limit == null || limit <= 0
                ? REFERENCE_DEFAULT_LIMIT : Math.min(limit, REFERENCE_MAX_LIMIT);
        FormDesignSqlBuilder.SqlWithParams pageSql = sqlBuilder.buildPage(
                dataSourceId, binding, conditions, null, null, 1, limited);
        List<Map<String, Object>> rows =
                jdbcTemplate.queryForList(pageSql.getSql(), pageSql.getParams().toArray());
        if (rows.isEmpty()) {
            return List.of();
        }
        rows = valueSerializer.deserializeAll(targetDesign.getFormFields(), rows, binding);
        rows = TemporalValueCoercer.normalizeRows(rows);
        // 脱敏照做：被带出的字段里完全可能有手机号
        rows = dataMasker.maskAll(targetDesign.getFormFields(), rows);
        // 字段级裁剪也照做，口径与详情一致（FIELD）——
        // 带出的是某条记录的字段值，语义上就是「详情里的那个字段」
        rows = fieldCropper.cropRows(targetCode, rows,
                fieldCropper.collectFieldKeys(targetDesign.getFormFields()), false);

        // 只留三类键
        Set<String> allowed = new LinkedHashSet<>();
        allowed.add(binding.getMainPk());
        if (StrUtil.isNotBlank(relation.getDisplayField())) {
            allowed.add(relation.getDisplayField());
        }
        for (RelationFormResolver.FieldMapping mapping : relation.getFieldMappings()) {
            if (mapping != null && StrUtil.isNotBlank(mapping.getSourceField())) {
                allowed.add(mapping.getSourceField());
            }
        }
        List<Map<String, Object>> result = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Map<String, Object> slim = new LinkedHashMap<>();
            for (String key : allowed) {
                if (row.containsKey(key)) {
                    slim.put(key, row.get(key));
                }
            }
            result.add(slim);
        }
        return result;
    }

    @Override
    public String resolveReferenceTargetCode(String sourceCode, String sourceField) {
        return findRelationField(loadPublishedDesign(sourceCode), sourceField).getTargetFormCode();
    }

    /**
     * 按字段名找源表单上的关联控件
     *
     * 找不到就抛，而不是返回 null 后在别处 NPE ——
     * 这个入参直接来自前端，配置被改过之后它很容易对不上
     */
    private RelationFormResolver.RelationField findRelationField(FormDesignDO sourceDesign,
                                                                 String sourceField) {
        RelationFormResolver.RelationField relation =
                relationFormResolver.parseRelationFields(sourceDesign.getFormFields()).stream()
                        .filter(item -> item.getField() != null && item.getField().equals(sourceField))
                        .findFirst()
                        .orElseThrow(() -> exception(FORM_DESIGN_RELATION_WIDGET_NOT_EXISTS, sourceField));
        if (StrUtil.isBlank(relation.getTargetFormCode())) {
            // 控件在、但没配目标表单。这种表单本不该发布得出去，
            // 真遇到了也当「没有这个控件」处理，而不是往下走出一个空查询
            throw exception(FORM_DESIGN_RELATION_WIDGET_NOT_EXISTS, sourceField);
        }
        return relation;
    }

    /**
     * 给【用户从查询区发来的】条件打上落库形态标记
     *
     * <p>多选类控件不是一个标量：JSON 列存 {@code ["a","b"]}，varchar 列存 {@code a,b}。
     * 不打标记的话 {@code SqlBuilderSupport} 只能按标量拼 {@code col = ?} / {@code col IN (?,?)}，
     * 那是拿整串与单项比 —— 多选字段作查询条件永远查不出数据，而且<b>不报错</b>。
     *
     * <p>形态从 {@link FormValueSerializer#parseStoredFields} 取，与写入侧、读回侧同源。
     * 三处必须同源，否则「存进去的形态」与「查询时假定的形态」就会分叉。
     *
     * <p>🔴 <b>只在 page 与 exportExcel 里调</b>。服务内部另有三处构造 EQ 条件
     * （业务主键校验、唯一性校验、导入判重），它们传的是<b>已 serialize 的整串</b>，
     * 语义就是「逐字相等」—— 打上标记会把判据松成「交集非空」，
     * 于是两条勾了部分相同选项的记录被判成不重复，静默放过脏数据。
     * 那三处直接 new 出条件、不经过本方法，{@code storedShape} 天然是 null。
     *
     * <p>就地修改入参而不是返回新列表：{@code QueryCondition} 是本次请求独有的可变 DTO，
     * 而 buildCount 与 buildPage 要拿到<b>同一份</b>打过标记的条件 ——
     * 两边一旦分叉，表现是 total 与列表页对不上（分页器说 12 条、页面只显示 3 条）。
     */
    private void tagStoredShapes(FormDesignDO design, TableBinding binding,
                                 List<FormDesignSqlBuilder.QueryCondition> conditions) {
        if (CollUtil.isEmpty(conditions)) {
            return;
        }
        Map<String, MultiValueShape> shapes = new HashMap<>();
        String separator = valueSerializer.separator();
        for (FormValueSerializer.StoredField stored :
                valueSerializer.parseStoredFields(design.getFormFields(), binding)) {
            // 只管主表字段：子表明细不参与列表查询条件（它们在另一张表里，
            // 主表的 WHERE 上根本没有对应的列）
            if (stored.getSubField() != null) {
                continue;
            }
            // FIRST_URL 是文件控件的落库形态，它落库后就是个标量 URL，
            // 按标量查是对的 —— 不打标记
            switch (stored.getMode()) {
                case JSON -> shapes.put(stored.getField(), MultiValueShape.ofJson());
                case DELIMITED -> shapes.put(stored.getField(), MultiValueShape.ofDelimited(separator));
                default -> {
                }
            }
        }
        if (shapes.isEmpty()) {
            return;
        }
        for (FormDesignSqlBuilder.QueryCondition condition : conditions) {
            if (condition != null) {
                condition.setStoredShape(shapes.get(condition.getField()));
            }
        }
    }

    @Override
    public PageResult<Map<String, Object>> page(String code,
                                                List<FormDesignSqlBuilder.QueryCondition> conditions,
                                                String sortField, String sortOrder,
                                                int pageNo, int pageSize) {
        FormDesignDO design = loadPublishedDesign(code);
        TableBinding binding = parseBinding(design);
        Long dataSourceId = resolveDataSourceId(design);
        tagStoredShapes(design, binding, conditions);

        // 限制页大小，防止前端传超大值拖垮数据库
        int limitedPageSize = Math.min(pageSize, MAX_PAGE_SIZE);

        // COUNT
        FormDesignSqlBuilder.SqlWithParams countSql =
                sqlBuilder.buildCount(dataSourceId, binding, conditions);
        Integer total = jdbcTemplate.queryForObject(countSql.getSql(),
                Integer.class, countSql.getParams().toArray());
        if (total == null || total == 0) {
            return PageResult.empty();
        }

        // SELECT
        FormDesignSqlBuilder.SqlWithParams pageSql = sqlBuilder.buildPage(
                dataSourceId, binding, conditions, sortField, sortOrder, pageNo, limitedPageSize);
        List<Map<String, Object>> list =
                jdbcTemplate.queryForList(pageSql.getSql(), pageSql.getParams().toArray());

        // 批量回查副表并拼进各行，让列表也能展示副表字段
        mergeSideTablesBatch(dataSourceId, binding, list);

        // 批量回查子表明细，供主子表列表纵向合并展示
        mergeSubTablesBatch(dataSourceId, design, binding, list);

        // 还原落库形态，理由同 get
        list = valueSerializer.deserializeAll(design.getFormFields(), list, binding);
        // 时间类值统一成字符串，理由同 get
        list = TemporalValueCoercer.normalizeRows(list);
        list = dataMasker.maskAll(design.getFormFields(), list);
        // 字段级裁剪。列表走 COLUMN 资源；模式只解析一次，逐行套用 ——
        // 逐行去问权限会把列表页拖垮
        list = fieldCropper.cropRows(design.getCode(), list,
                fieldCropper.collectFieldKeys(design.getFormFields()), true);
        return new PageResult<>(list, total.longValue());
    }

    /**
     * 批量回查子表明细并挂进列表行
     *
     * 只查 listConf 里真正引用了的子表 —— 一张表单可能配了三张明细表，
     * 列表却只展示其中一张，全查会白白多发两次查询。
     *
     * 分页语义不受影响：明细以嵌套数组挂在主行上，主表行数不变，
     * total 仍是主记录数。展开成多行是前端渲染时做的事，
     * 放在后端做会让 total 与实际行数对不上、分页器直接错乱。
     */
    private void mergeSubTablesBatch(Long dataSourceId, FormDesignDO design,
                                     TableBinding binding, List<Map<String, Object>> rows) {
        if (binding.getSubTables() == null || binding.getSubTables().isEmpty() || rows.isEmpty()) {
            return;
        }
        Set<String> neededFields = resolveListSubTableFields(design);
        if (neededFields.isEmpty()) {
            return;
        }

        String mainPk = binding.getMainPk();
        // 主键值 → 行。键统一转成字符串：JDBC 驱动对同一列可能返回 Long 或 BigInteger，
        // 用原始对象做 key 会匹配不上（与副表回查同一个坑）
        Map<String, Map<String, Object>> rowByPk = new LinkedHashMap<>();
        List<Object> pkValues = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Object pk = row.get(mainPk);
            if (pk != null) {
                rowByPk.put(String.valueOf(pk), row);
                pkValues.add(pk);
            }
        }
        if (rowByPk.isEmpty()) {
            log.warn("列表行里取不到主键 {}，跳过子表回查", mainPk);
            return;
        }

        for (SubTableBinding subTable : binding.getSubTables()) {
            if (!isSubTableUsable(subTable) || !neededFields.contains(subTable.getField())) {
                continue;
            }
            // 先给每行铺一个空数组：没有明细的主记录也要有这个键，
            // 否则前端展开时拿到 undefined，会沿用上一行的明细
            for (Map<String, Object> row : rowByPk.values()) {
                row.put(subTable.getField(), new ArrayList<>());
            }

            Set<String> subColumns = buildSubColumns(subTable);
            String fkColumn = subTable.getForeignKey();
            // 分批，理由与副表回查一致：IN 列表过长会让 SQL 文本超限、优化器放弃索引
            for (int from = 0; from < pkValues.size(); from += IN_CLAUSE_BATCH_SIZE) {
                List<Object> batch = pkValues.subList(from,
                        Math.min(from + IN_CLAUSE_BATCH_SIZE, pkValues.size()));
                FormDesignSqlBuilder.SqlWithParams select =
                        sqlBuilder.buildSubTableSelectBatch(dataSourceId, subTable, subColumns, batch);
                List<Map<String, Object>> rawRows =
                        jdbcTemplate.queryForList(select.getSql(), select.getParams().toArray());

                // 按外键分组后再做列名 → 字段名转换，转换规则与详情接口完全一致
                Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
                for (Map<String, Object> rawRow : rawRows) {
                    Object fk = rawRow.get(fkColumn);
                    if (fk != null) {
                        grouped.computeIfAbsent(String.valueOf(fk), k -> new ArrayList<>()).add(rawRow);
                    }
                }
                grouped.forEach((fk, group) -> {
                    Map<String, Object> target = rowByPk.get(fk);
                    if (target != null) {
                        target.put(subTable.getField(),
                                SubTableRowMapper.toFieldKeyedRows(subTable, group));
                    }
                });
            }
        }
    }

    /**
     * 解析列表配置里引用到的子表控件 field
     *
     * 列表列上带 {@code subField} 的就是明细列，它的值取自主行的该子表数组。
     * 用显式属性而不是把归属编进 field 名：列名是用户可见的，
     * 编码进去既难读也容易与真实字段名撞车。
     *
     * 解析失败返回空集合 —— 列表配置坏掉时退化成「不展示明细」，
     * 好过让整个列表打不开。
     */
    @SuppressWarnings("unchecked")
    private Set<String> resolveListSubTableFields(FormDesignDO design) {
        if (StrUtil.isBlank(design.getListConf())) {
            return Collections.emptySet();
        }
        Map<String, Object> listConf = JsonUtils.parseObjectQuietly(design.getListConf(),
                new TypeReference<Map<String, Object>>() {});
        if (listConf == null || !(listConf.get("columns") instanceof List<?> columns)) {
            return Collections.emptySet();
        }
        Set<String> fields = new HashSet<>();
        for (Object column : columns) {
            if (column instanceof Map<?, ?> col
                    && ((Map<String, Object>) col).get("subField") instanceof String subField
                    && StrUtil.isNotBlank(subField)) {
                fields.add(subField);
            }
        }
        return fields;
    }

    /**
     * 批量回查副表并拼进列表行
     *
     * 为什么不把副表加进 buildPage 的 JOIN：副表的 1:1 没有数据库层面保证，
     * 外键上没有唯一索引时 JOIN 会让主表行数翻倍，分页条数就错了。
     * 分页后用 IN 批量回查，主表行数不受影响，代价是每张副表多一次查询。
     *
     * 两个性能约束：
     * 1. 主键索引一次建好（按字符串），避免逐行遍历比对 —— 那是 O(n²)
     * 2. IN 列表超过 {@link #IN_CLAUSE_BATCH_SIZE} 时分批，防止单条 SQL 过长。
     *    常规分页最多 200 条不会触发，但树形模式若放宽上限就会用到
     */
    private void mergeSideTablesBatch(Long dataSourceId, TableBinding binding,
                                      List<Map<String, Object>> rows) {
        if (binding.getSideTables() == null || binding.getSideTables().isEmpty() || rows.isEmpty()) {
            return;
        }
        String mainPk = binding.getMainPk();
        // 主键值 → 行。键统一转成字符串 ——
        // JDBC 驱动对同一列可能返回 Long 或 BigInteger，用原始对象做 key 会匹配不上
        Map<String, Map<String, Object>> rowByPk = new LinkedHashMap<>();
        List<Object> pkValues = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Object pk = row.get(mainPk);
            if (pk != null) {
                rowByPk.put(String.valueOf(pk), row);
                pkValues.add(pk);
            }
        }
        if (rowByPk.isEmpty()) {
            log.warn("列表行里取不到主键 {}，跳过副表回查", mainPk);
            return;
        }

        long startMs = System.currentTimeMillis();
        int queryCount = 0;

        for (SideTableBinding side : binding.getSideTables()) {
            if (!isSideTableUsable(side)) {
                continue;
            }
            String fkField = FormFieldNaming.sideField(side.getTable(), side.getForeignKey());
            // 分批查询，每批不超过 IN_CLAUSE_BATCH_SIZE 个值
            for (int from = 0; from < pkValues.size(); from += IN_CLAUSE_BATCH_SIZE) {
                int to = Math.min(from + IN_CLAUSE_BATCH_SIZE, pkValues.size());
                List<Object> batch = pkValues.subList(from, to);

                FormDesignSqlBuilder.SqlWithParams select = sqlBuilder.buildSideTableSelectBatch(
                        dataSourceId, binding, side, batch);
                List<Map<String, Object>> sideRows =
                        jdbcTemplate.queryForList(select.getSql(), select.getParams().toArray());
                queryCount++;

                for (Map<String, Object> sideRow : sideRows) {
                    Object fkValue = sideRow.get(fkField);
                    if (fkValue == null) {
                        continue;
                    }
                    // O(1) 查找，键已统一为字符串
                    Map<String, Object> target = rowByPk.get(String.valueOf(fkValue));
                    if (target != null) {
                        // 键已带前缀，与主表列不会重名，直接合并
                        target.putAll(sideRow);
                    }
                }
            }
        }

        // 副表回查是「每张副表一次查询」的固有成本，无法用合并 SQL 消除：
        // UNION ALL 要把不同类型的列压成单列（日期与数字精度会丢），
        // 多副表 JOIN 在 1:1 被破坏时会放大重复行。
        // 因此这里只做可观测：耗时超阈值就记日志，便于发现「副表配太多」的配置。
        long costMs = System.currentTimeMillis() - startMs;
        if (costMs > SIDE_TABLE_MERGE_SLOW_MS) {
            log.warn("副表回查偏慢：耗时 {}ms，副表 {} 张，查询 {} 次，主表行 {} 行。"
                            + "副表过多会让列表每页多 N 次查询，考虑合并表结构",
                    costMs, binding.getSideTables().size(), queryCount, rowByPk.size());
        }
    }

    @Override
    public List<Object> distinctValues(String code, String field) {
        FormDesignDO design = loadPublishedDesign(code);
        TableBinding binding = parseBinding(design);
        Long dataSourceId = resolveDataSourceId(design);

        FormDesignSqlBuilder.SqlWithParams select = sqlBuilder.buildDistinctValues(
                dataSourceId, binding, field, MAX_DISTINCT_VALUES);
        return jdbcTemplate.queryForList(select.getSql(), Object.class,
                select.getParams().toArray());
    }

    /**
     * 最大导出行数限制（防止恶意导出海量数据导致 OOM 或长时间阻塞）
     */
    /**
     * 导出行数上限的默认值
     *
     * 2 万。可由表单设计者在列表设计里改（{@code listConf.exportMaxRows}）。
     *
     * 为什么不放得更宽：导出是把整批数据读进内存再写出去的，
     * 行数上去之后内存与响应时间都不线性。真要几十万行的场景，
     * 该用的是异步导出而不是把这个数字调大。
     */
    private static final int DEFAULT_EXPORT_MAX_ROWS = 20000;

    /**
     * 上限本身的上限
     *
     * 设计者可以调，但不能调到把服务打挂。超过这个数按这个数算，
     * 并在日志里说一声 —— 静默改小的话，设计者会以为自己配的那个数生效了
     */
    private static final int HARD_EXPORT_MAX_ROWS = 200000;

    /**
     * 导当前页时，前端没传页大小的兜底
     */
    private static final int DEFAULT_EXPORT_PAGE_SIZE = 20;

    /**
     * 导出表单数据 —— <b>列表怎么显示，导出就怎么导</b>
     *
     * <h3>为什么是「照列表导出」而不是另造一套</h3>
     *
     * 改造前导出只读了列的 {@code label}，<b>完全没读 {@code formatter}</b>。
     * 后果不只是「不好看」：字典列导出的是 {@code MAKE}、布尔列导出的是
     * {@code 1.0}、金额列的小数位是 1 位而列上配的是 2 位 ——
     * 收表的人看不懂，而且数字本身就是错的。
     *
     * 现在一律照列表来。格式化规则抽在 {@code cell-format-contracts.json}，
     * 前后端共用一份声明（见 {@link ExportCellRenderer}）。
     *
     * <h3>数值与日期写「真值 + 数字格式」</h3>
     *
     * 不是把列表的文本抄进去。Excel 里显示格式与值是两层，
     * 于是「看起来和列表一样」与「导出的文件能改完导回来」同时成立：
     * 金额单元格的真值仍是 8500000，显示成 8,500,000.00。
     *
     * <h3>⚠️ 三处故意不跟随列表</h3>
     *
     * <ol>
     *   <li><b>同值单元格合并</b>：列表上连续相同的值会合并成一格，
     *       导出<b>照常逐行重复填</b>。合并的话被合掉的行那一列是空的，
     *       导回来就丢值了；而且含合并单元格的区域做筛选、透视都会出问题</li>
     *   <li><b>树形</b>：列表是可展开的树，导出是平铺。层级由数据自己的
     *       父字段表达，Excel 里做假的缩进反而让那一列没法用</li>
     *   <li><b>超链接 / 图片 / 附件</b>：列表显示「打开」、缩略图、文件名，
     *       导出一律是 URL。理由见契约文件头</li>
     * </ol>
     *
     * <h3>合计行与序号列跟随</h3>
     *
     * 它们不破坏数据区，而且是用户在列表上看到的一部分。
     * 合计行首列打上「合计」标记，导入侧据此识别并跳过 ——
     * 不打标记的话它会被当成一条记录，报「某某是必填项」。
     */
    @Override
    public void exportExcel(String code, List<FormDesignSqlBuilder.QueryCondition> conditions,
                            FormExportOptions options, HttpServletResponse response)
            throws IOException {
        FormExportOptions opts = options != null ? options : new FormExportOptions();
        FormDesignDO design = loadPublishedDesign(code);
        TableBinding binding = parseBinding(design);
        Long dataSourceId = resolveDataSourceId(design);
        // 与 page 同一份标记逻辑：导出走的是同一个 buildPage，
        // 不打标记的话「列表上筛出来的 3 行」与「导出的 0 行」会同时成立 ——
        // 用户在界面上看得见数据，导出来却是空表，而两边都不报错
        tagStoredShapes(design, binding, conditions);

        // 1. 解析 listConf
        //
        // ⚠️ 必须先按字段权限裁一遍 listConf，再解析 —— 顺序反了就是一扇后门：
        // 「列表里看不到的列，导出能导出来」。
        //
        // 裁在 JSON 这一层而不是裁导出的数据行，有三个好处：
        //   1. **表头与数据天然同步**。裁数据行的话，columns 里还留着这一列，
        //      导出来就是一列有表头、底下全空 —— 看着像导出坏了
        //   2. **合计行自动跟着裁**。buildSummaryRow 只遍历存活的 columns，
        //      否则被隐藏列的合计值会从合计行漏出去（值没了，和还在）
        //   3. **前端传来的 visibleFields 无法复活被裁的列**。它在下面按
        //      byField 过滤，而 byField 是从已裁的 JSON 建的
        //
        // 用的就是 config 出口那份 cropListConf —— v10 §S4c 要求
        // 「导出复用同一份可见列」，复用同一个方法是唯一能保证口径不漂的做法
        ExportListConf listConf = parseExportListConf(
                fieldCropper.cropListConf(design.getCode(), design.getListConf()),
                opts.getVisibleFields());
        List<ExportColumn> columns = listConf.getColumns();
        if (columns.isEmpty()) {
            throw exception(FORM_DESIGN_RUNTIME_EXECUTE_FAIL,
                    "这张表单没有配任何列表列，没有可导出的内容");
        }

        // 2. 构建 SQL
        //
        // 导当前页时用他正看着的那一页的页码与页大小 —— 这才是「所见」。
        // 导全部时多取一条用来判断有没有被上限截断：截断了却不说，
        // 用户会以为导全了，而少掉的那部分他永远不会去找
        int maxRows = listConf.getExportMaxRows();
        int pageNo = 1;
        int pageSize = maxRows + 1;
        if (opts.isCurrentPage()) {
            pageNo = opts.getPageNo() != null && opts.getPageNo() > 0 ? opts.getPageNo() : 1;
            pageSize = opts.getPageSize() != null && opts.getPageSize() > 0
                    ? opts.getPageSize() : DEFAULT_EXPORT_PAGE_SIZE;
            // 当前页也受上限约束：有人把每页设成很大的数时，
            // 「当前页」与「全部」就没区别了
            pageSize = Math.min(pageSize, maxRows);
        }
        FormDesignSqlBuilder.SqlWithParams select = sqlBuilder.buildPage(
                dataSourceId, binding, conditions, opts.getSortField(), opts.getSortOrder(),
                pageNo, pageSize);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                select.getSql(), select.getParams().toArray());
        boolean truncated = !opts.isCurrentPage() && rows.size() > maxRows;
        if (truncated) {
            rows = rows.subList(0, maxRows);
        }

        // 3. 明细列要回查子表，否则导出的明细列全是空的 ——
        //    列表页看得见、导出却没有，是最容易被当成「导出坏了」的那种缺陷
        mergeSubTablesBatch(dataSourceId, design, binding, rows);

        // 4. 脱敏。导出是三个出口里泄露量最大的一个 —— 一次导出上万行明文，
        //    而它又最容易被忘记（列表页看着是脱敏的，导出却是明文）
        rows = valueSerializer.deserializeAll(design.getFormFields(), rows, binding);
        // 时间类值统一。⚠️ 仍然要做：它把 java.sql.Date 这类转成字符串，
        // 而 ExportCellRenderer 再把字符串解析回 LocalDate —— 看着绕，
        // 但中间这一步是脱敏与子表合并都依赖的既有约定，绕开它风险更大
        rows = TemporalValueCoercer.normalizeRows(rows);
        rows = dataMasker.maskAll(design.getFormFields(), rows);

        // 5. 表头。多级分组表头跟随列表：FastExcel 的动态表头本来就是
        //    「每列一个 List，内层每项一行」，正好能表达两层
        List<List<String>> excelHead = buildExportHead(listConf);

        // 6. 数据行
        List<List<Object>> excelData = new ArrayList<>();
        Set<String> usedSubFields = new LinkedHashSet<>();
        for (ExportColumn column : columns) {
            if (StrUtil.isNotBlank(column.getSubField())) {
                usedSubFields.add(column.getSubField());
            }
        }

        // 合计要按「列表上看到的那些行」累加，所以边写边累
        Map<String, java.math.BigDecimal> sums = new LinkedHashMap<>();
        int index = 0;
        for (Map<String, Object> row : rows) {
            // 一条主记录展开成 max(明细行数, 1) 行，与列表页的展开一致
            int rowCount = 1;
            for (String subField : usedSubFields) {
                if (row.get(subField) instanceof List<?> details) {
                    rowCount = Math.max(rowCount, details.size());
                }
            }
            for (int i = 0; i < rowCount; i++) {
                index++;
                List<Object> rowData = new ArrayList<>(columns.size() + 1);
                if (listConf.isShowIndexColumn()) {
                    // 序号跨页连续，与列表页一致
                    rowData.add(new WriteCellData<>(java.math.BigDecimal.valueOf(index)));
                }
                for (ExportColumn column : columns) {
                    Object raw = resolveCellValue(row, column, i);
                    rowData.add(toWriteCell(cellRenderer.render(raw, column.getFormatter())));
                    accumulate(sums, column, raw, i);
                }
                excelData.add(rowData);
            }
        }

        // 7. 合计行
        //
        // 默认跟随列表（它是用户在页面上看到的一部分），但允许显式关掉 ——
        // 要拿去做透视表时，多出来的那一行会碍事。
        //
        // 合计的口径是「导出了哪些行就合计哪些行」：上面是边写边累加的，
        // 所以导当前页时合计的就是这一页，与列表页显示的合计一致
        boolean includeSummary = opts.getIncludeSummary() != null
                ? opts.getIncludeSummary() : listConf.isShowSummary();
        if (includeSummary && !listConf.getSummaryColumns().isEmpty()) {
            excelData.add(buildSummaryRow(listConf, sums));
        }

        // 8. 写出
        String fileName = design.getName()
                + (opts.isCurrentPage() ? "(第" + pageNo + "页)" : "")
                + (truncated ? "(前" + maxRows + "条)" : "")
                + "_" + System.currentTimeMillis() + ".xlsx";
        if (truncated) {
            // ⚠️ 响应头在 ExcelUtils.write 里是写完流才设的，这时候设才来得及。
            //    前端读不到也没关系 —— 文件名里已经带了，用户一眼能看见
            response.setHeader("X-Export-Truncated", "true");
            response.setHeader("X-Export-Max-Rows", String.valueOf(maxRows));
        }
        ExcelUtils.write(response, fileName, design.getName(), excelHead, excelData);
    }

    /**
     * 取一个单元格的原始值
     *
     * @param detailIndex 明细行序号。主表列忽略它
     */
    private Object resolveCellValue(Map<String, Object> row, ExportColumn column, int detailIndex) {
        String subField = column.getSubField();
        if (StrUtil.isBlank(subField)) {
            return row.get(column.getField());
        }
        // 明细列：取该子表第 n 行；这张表的明细比别的表短时留空
        if (row.get(subField) instanceof List<?> details && detailIndex < details.size()) {
            Object detail = details.get(detailIndex);
            return detail instanceof Map<?, ?> detailRow ? detailRow.get(column.getField()) : null;
        }
        return null;
    }

    /**
     * 累加合计
     *
     * ⚠️ 明细列的合计按<b>明细行</b>累加，主表列按<b>主记录</b>累加 ——
     * 主表列在展开后的每一行都重复出现，逐行加会把金额乘上明细条数。
     * 这与列表页的合计口径一致（见 G04 示例）。
     */
    private void accumulate(Map<String, java.math.BigDecimal> sums, ExportColumn column,
                            Object raw, int detailIndex) {
        if (!column.isSummary()) {
            return;
        }
        // 主表列只在展开的第一行累加
        if (StrUtil.isBlank(column.getSubField()) && detailIndex > 0) {
            return;
        }
        java.math.BigDecimal number = toSummaryDecimal(raw);
        if (number == null) {
            return;
        }
        sums.merge(column.getSummaryKey(), number, java.math.BigDecimal::add);
    }

    private java.math.BigDecimal toSummaryDecimal(Object value) {
        if (value instanceof java.math.BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return new java.math.BigDecimal(number.toString());
        }
        if (value instanceof String text && StrUtil.isNotBlank(text)) {
            try {
                return new java.math.BigDecimal(text.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * 合计行
     *
     * 首列打上契约里的「合计」标记 —— 导入侧据此识别并跳过。
     * 不打标记的话，这一行会被当成一条记录，报「某某是必填项」，
     * 而用户完全不知道那一行是自己导出来的合计。
     */
    private List<Object> buildSummaryRow(ExportListConf listConf,
                                         Map<String, java.math.BigDecimal> sums) {
        List<Object> summaryRow = new ArrayList<>();
        boolean labelPlaced = false;
        if (listConf.isShowIndexColumn()) {
            summaryRow.add(new WriteCellData<>(cellFormatRegistry.getRowKinds().getSummaryLabel()));
            labelPlaced = true;
        }
        for (ExportColumn column : listConf.getColumns()) {
            if (!labelPlaced) {
                summaryRow.add(new WriteCellData<>(
                        cellFormatRegistry.getRowKinds().getSummaryLabel()));
                labelPlaced = true;
                continue;
            }
            java.math.BigDecimal sum = column.isSummary() ? sums.get(column.getSummaryKey()) : null;
            if (sum == null) {
                // 没配进合计的列留空，与列表上显示占位符是同一个意思 ——
                // 但 Excel 里写占位符会让那一列变文本，留空更合适
                summaryRow.add(new WriteCellData<>(""));
                continue;
            }
            summaryRow.add(toWriteCell(cellRenderer.render(sum, column.getFormatter())));
        }
        return summaryRow;
    }

    /**
     * 渲染结果 → FastExcel 的单元格
     *
     * <p>用 {@link WriteCellData} 携带数字格式，而不是按列号旁路设样式：
     * 数据与它的格式放在一起，合计行也能带自己的格式，不必再维护一份
     * 「第几列用什么格式」的映射。
     */
    private WriteCellData<?> toWriteCell(ExportCellRenderer.Cell cell) {
        Object value = cell.getValue();
        WriteCellData<?> data;
        if (value == null) {
            return new WriteCellData<>("");
        }
        if (value instanceof java.math.BigDecimal decimal) {
            data = new WriteCellData<>(decimal);
        } else if (value instanceof java.time.LocalDateTime dateTime) {
            data = new WriteCellData<>(dateTime);
        } else if (value instanceof java.time.LocalDate date) {
            data = new WriteCellData<>(date.atStartOfDay());
        } else {
            return new WriteCellData<>(String.valueOf(value));
        }
        if (StrUtil.isNotBlank(cell.getNumFmt())) {
            DataFormatData format = new DataFormatData();
            format.setFormat(cell.getNumFmt());
            data.getOrCreateStyle().setDataFormatData(format);
        }
        return data;
    }

    /**
     * 表头。带序号列与多级分组表头
     */
    private List<List<String>> buildExportHead(ExportListConf listConf) {
        List<List<String>> head = new ArrayList<>();
        boolean grouped = !listConf.getGroupByField().isEmpty();
        if (listConf.isShowIndexColumn()) {
            // 有分组表头时序号列也要占两行，否则 FastExcel 会把它与相邻列错行对齐
            head.add(grouped ? List.of("序号", "序号") : List.of("序号"));
        }
        for (ExportColumn column : listConf.getColumns()) {
            String label = StrUtil.isNotBlank(column.getLabel()) ? column.getLabel() : column.getField();
            if (!grouped) {
                head.add(List.of(label));
                continue;
            }
            String group = listConf.getGroupByField().get(column.getField());
            // 不属于任何分组的列，两行都写自己的名字 —— FastExcel 会把相同的
            // 相邻表头自动合并，于是它看起来就是一个跨两行的单元格
            head.add(List.of(StrUtil.isNotBlank(group) ? group : label, label));
        }
        return head;
    }

    /**
     * 解析导出行数上限
     *
     * 没配、配了个非数字、配成 0 或负数，一律回落到默认值 ——
     * 配成 0 时若当真按 0 处理，导出的会是一张空表，而用户完全看不出为什么。
     */
    private int resolveExportMaxRows(Object raw) {
        Integer configured = null;
        if (raw instanceof Number number) {
            configured = number.intValue();
        } else if (raw instanceof String text && StrUtil.isNotBlank(text)) {
            try {
                configured = Integer.valueOf(text.trim());
            } catch (NumberFormatException ignored) {
                configured = null;
            }
        }
        if (configured == null || configured <= 0) {
            return DEFAULT_EXPORT_MAX_ROWS;
        }
        if (configured > HARD_EXPORT_MAX_ROWS) {
            log.warn("[resolveExportMaxRows][配置的导出上限({})超过硬上限({})，按硬上限处理]",
                    configured, HARD_EXPORT_MAX_ROWS);
            return HARD_EXPORT_MAX_ROWS;
        }
        return configured;
    }

    /**
     * 导出用的列表配置
     */
    @lombok.Data
    private static class ExportListConf {
        private List<ExportColumn> columns = new ArrayList<>();
        private boolean showIndexColumn = true;
        private boolean showSummary;
        private Set<String> summaryColumns = new LinkedHashSet<>();
        /**
         * 字段名 → 所属的分组表头名
         */
        private Map<String, String> groupByField = new LinkedHashMap<>();
        /**
         * 导出行数上限。来自 listConf.exportMaxRows，由表单设计者配
         */
        private int exportMaxRows = DEFAULT_EXPORT_MAX_ROWS;
    }

    /**
     * 导出用的一列
     */
    @lombok.Data
    private static class ExportColumn {
        private String field;
        private String label;
        private String subField;
        private Map<String, Object> formatter;
        private boolean summary;

        /**
         * 合计的累加键
         *
         * ⚠️ 明细列用「子表::字段」，与 listConf.summaryColumns 的写法一致。
         * 只用字段名的话，主表与明细里的同名字段（都叫 amount）会互相污染。
         */
        String getSummaryKey() {
            return StrUtil.isNotBlank(subField) ? subField + "::" + field : field;
        }
    }

    /**
     * 解析 listConf
     *
     * @param visibleFields 用户在列设置里保留下来的列（含顺序）。
     *                      为空表示用设计期的列。
     *                      ⚠️ 这个参数是必要的：用户在列表上隐藏了列、调过顺序，
     *                      导出却按设计期的列来，等于「所见」与「所得」对不上 ——
     *                      而用户隐藏某一列往往正是因为不想要它
     */
    @SuppressWarnings("unchecked")
    private ExportListConf parseExportListConf(String listConfJson, List<String> visibleFields) {
        ExportListConf result = new ExportListConf();
        if (StrUtil.isBlank(listConfJson)) {
            return result;
        }
        Map<String, Object> conf = JsonUtils.parseObject(listConfJson, Map.class);
        if (conf == null) {
            return result;
        }

        result.setShowIndexColumn(!Boolean.FALSE.equals(conf.get("showIndexColumn")));
        result.setExportMaxRows(resolveExportMaxRows(conf.get("exportMaxRows")));
        result.setShowSummary(Boolean.TRUE.equals(conf.get("showSummary")));
        if (conf.get("summaryColumns") instanceof List<?> summaryColumns) {
            for (Object item : summaryColumns) {
                if (item != null) {
                    result.getSummaryColumns().add(String.valueOf(item));
                }
            }
        }

        // 分组表头
        if (conf.get("complexHeaders") instanceof List<?> groups) {
            for (Object item : groups) {
                if (!(item instanceof Map<?, ?> groupMap)) {
                    continue;
                }
                Map<String, Object> group = (Map<String, Object>) groupMap;
                String groupLabel = group.get("label") instanceof String l ? l : null;
                if (StrUtil.isBlank(groupLabel) || !(group.get("children") instanceof List<?> children)) {
                    continue;
                }
                for (Object child : children) {
                    if (child != null) {
                        result.getGroupByField().put(String.valueOf(child), groupLabel);
                    }
                }
            }
        }

        // 列
        Map<String, ExportColumn> byField = new LinkedHashMap<>();
        if (conf.get("columns") instanceof List<?> columns) {
            for (Object item : columns) {
                if (!(item instanceof Map<?, ?> colMap)) {
                    continue;
                }
                Map<String, Object> col = (Map<String, Object>) colMap;
                String field = col.get("field") instanceof String f ? f : null;
                if (StrUtil.isBlank(field)) {
                    continue;
                }
                ExportColumn column = new ExportColumn();
                column.setField(field);
                column.setLabel(col.get("label") instanceof String l ? l : field);
                column.setSubField(col.get("subField") instanceof String sf && StrUtil.isNotBlank(sf)
                        ? sf : null);
                column.setFormatter(col.get("formatter") instanceof Map<?, ?> fmt
                        ? (Map<String, Object>) fmt : null);
                column.setSummary(result.getSummaryColumns().contains(column.getSummaryKey()));
                byField.putIfAbsent(column.getSummaryKey(), column);
            }
        }

        if (visibleFields == null || visibleFields.isEmpty()) {
            result.getColumns().addAll(byField.values());
            return result;
        }
        // 按用户的列设置过滤并重排。认不出的键忽略 ——
        // 列设置存在浏览器本地，表单改过之后可能还留着已删除的列
        for (String key : visibleFields) {
            ExportColumn column = byField.get(key);
            if (column != null) {
                result.getColumns().add(column);
            }
        }
        if (result.getColumns().isEmpty()) {
            // 传来的列一个都对不上时退回设计期的列，而不是导出一张空表
            log.warn("[parseExportListConf][前端传来的可见列一个都没匹配上，退回设计期列]");
            result.getColumns().addAll(byField.values());
        }
        return result;
    }

    /**
     * 把一个单元格的值转成 Excel 写得出去的形态
     *
     * <p>FastExcel 按值的**确切类型**查找 Converter，查不到就抛
     * {@code Can not find 'Converter' support class XXX} —— 而这是个
     * <b>整张表都导不出来</b>的失败，不是「这一格是空的」。
     * 表单的列值形态又恰恰很杂：多选是 {@code List}、上传是 {@code List<Map>}、
     * 组织选择器是 {@code Map}，任何一张表单只要用了其中一个控件就导不了。
     *
     * <p>所以这里兜一道底：只有 FastExcel 原生认的标量原样放行，其余一律转成文本。
     * 宁可某一格是 JSON 串，也不该整张表导不出来。
     *
     * <p>时间类不在这里处理 —— 它们在 {@link TemporalValueCoercer#normalizeRows} 就
     * 已经是字符串了，两处都转会让格式有两个来源。
     */
    private Object toExcelCell(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        // 多值字段：逗号分隔更适合在 Excel 里读，比一串 JSON 直观
        if (value instanceof Collection<?> collection) {
            return collection.stream()
                    .map(this::toExcelCellText)
                    .collect(Collectors.joining(", "));
        }
        return toExcelCellText(value);
    }

    /** 单个元素的文本形态。上传类的元素是 {name,url}，取文件名比印一串 URL 有用 */
    private String toExcelCellText(Object item) {
        if (item == null) {
            return "";
        }
        if (item instanceof Map<?, ?> map) {
            Object name = map.get("name");
            if (name != null) {
                return String.valueOf(name);
            }
            return JsonUtils.toJsonString(map);
        }
        if (item instanceof Collection<?> || item.getClass().isArray()) {
            return JsonUtils.toJsonString(item);
        }
        return String.valueOf(item);
    }

    /**
     * 加载子表明细
     *
     * 列名与字段名的转换规则见 {@link SubTableRowMapper}。
     */
    private List<Map<String, Object>> loadSubRows(Long dataSourceId, SubTableBinding subTable, Object mainPkValue) {
        Set<String> subColumns = buildSubColumns(subTable);
        FormDesignSqlBuilder.SqlWithParams select =
                sqlBuilder.buildSubTableSelect(dataSourceId, subTable, subColumns, mainPkValue);
        List<Map<String, Object>> rawRows =
                jdbcTemplate.queryForList(select.getSql(), select.getParams().toArray());
        return SubTableRowMapper.toFieldKeyedRows(subTable, rawRows);
    }

    private Set<String> buildSubColumns(SubTableBinding subTable) {
        Set<String> columns = new HashSet<>();
        columns.add(subPk(subTable));
        // 外键列必须进白名单：buildSubTableSelect 要用它校验 WHERE 条件的列，
        // 漏掉会抛 FORM_DESIGN_IDENTIFIER_INVALID，导致配了子表的表单查不出详情
        if (StrUtil.isNotBlank(subTable.getForeignKey())) {
            columns.add(subTable.getForeignKey());
        }
        // 逻辑删除列同理 —— 查询要用它过滤已删除的明细
        LogicDeleteConfig ld = subTable.getLogicDelete();
        if (ld != null && ld.getEnabled() != null && ld.getEnabled()
                && StrUtil.isNotBlank(ld.getColumn())) {
            columns.add(ld.getColumn());
        }
        // 明细的审计列也查出来，子表控件里可能配了「创建时间」之类的展示列
        if (subTable.getAutoFill() != null) {
            columns.addAll(subTable.getAutoFill().getAllColumns());
        }
        if (subTable.getFieldMapping() != null) {
            for (FieldMapping mapping : subTable.getFieldMapping().values()) {
                if (mapping != null && StrUtil.isNotBlank(mapping.getColumn())) {
                    columns.add(mapping.getColumn());
                }
            }
        }
        return columns;
    }

    // ==================== 工具方法 ====================

    /**
     * 加载已发布的表单设计
     *
     * 运行时只认已发布的表单 —— 草稿态的配置可能是半成品，不该被运行时消费。
     */
    private FormDesignDO loadPublishedDesign(String code) {
        FormDesignDO design = formDesignService.getFormDesignByCode(code);
        if (design == null) {
            throw exception(FORM_DESIGN_NOT_EXISTS);
        }
        if (design.getPublishedVersion() == null || design.getPublishedVersion() == 0) {
            throw exception(FORM_DESIGN_NOT_PUBLISHED);
        }
        return design;
    }

    private TableBinding parseBinding(FormDesignDO design) {
        if (StrUtil.isBlank(design.getTableBinding())) {
            throw exception(FORM_DESIGN_MAIN_TABLE_REQUIRED);
        }
        TableBinding binding = JsonUtils.parseObject(design.getTableBinding(), TableBinding.class);
        if (binding == null || StrUtil.isBlank(binding.getMainTable())) {
            throw exception(FORM_DESIGN_MAIN_TABLE_REQUIRED);
        }
        if (StrUtil.isBlank(binding.getMainPk())) {
            binding.setMainPk("id");
        }
        return binding;
    }

    /**
     * 解析数据源编号
     *
     * 当前只支持主库。配了别的数据源直接拒绝，而不是静默写进主库 ——
     * 静默错库的后果比报错严重得多。
     */
    private Long resolveDataSourceId(FormDesignDO design) {
        Long dataSourceId = design.getDataSourceConfigId();
        if (dataSourceId != null && dataSourceId != 0L) {
            throw exception(FORM_DESIGN_RUNTIME_EXECUTE_FAIL,
                    "当前版本运行时仅支持主库，请将数据源改为「主库」后重新发布");
        }
        return 0L;
    }

    private Long getLoginUserId() {
        return SecurityFrameworkUtils.getLoginUserId();
    }


    // ==================== 自定义按钮：调数据接口 ====================

    @Override
    public Object invokeButtonInterface(String code, String buttonKey, Object pkValue) {
        FormDesignDO design = loadPublishedDesign(code);

        // ⚠️ 接口编码从【已发布的配置】里取，不接受调用方传。
        // 这一条是这个端点全部安全性的来源：能调到的只有表单设计者在这张表单上
        // 明确接过线的那几个接口，而不是「系统里所有的写型接口」
        CustomButton button = findButton(design.getCustomButtons(), buttonKey);
        if (button == null) {
            throw exception(FORM_DESIGN_BUTTON_NOT_EXISTS, buttonKey);
        }
        CustomButton.ButtonAction action = button.getAction();
        String label = StrUtil.blankToDefault(button.getLabel(), buttonKey);
        if (action == null
                || CustomButton.ButtonAction.Type.parse(action.getType())
                        != CustomButton.ButtonAction.Type.INVOKE) {
            throw exception(FORM_DESIGN_BUTTON_NOT_INVOKE, label);
        }
        if (StrUtil.isBlank(action.getInterfaceCode())) {
            throw exception(FORM_DESIGN_BUTTON_INTERFACE_REQUIRED, label);
        }

        // ⚠️ 入参按配置的字段映射，从【服务端重新读的那条记录】里取，
        // 不用调用方送上来的值 —— 否则等于让它自由构造接口入参
        Map<String, Object> args = buildInterfaceArgs(code, action.getParams(), pkValue);

        // 走 invoke 而不是 invokeReadOnly：作废单据这类写操作正是这个动作存在的理由。
        // 数据接口自己的配置（SQL / URL / 参数签名）仍由接口配置者掌控，
        // 调用方改不了 SQL、改不了地址，只能按签名传参
        log.info("[invokeButtonInterface][表单({}) 按钮({}) 调用数据接口({})，入参 {} 个]",
                code, buttonKey, action.getInterfaceCode(), args.size());
        // ⚠️ 审计记在调用【之前】。与明文查看那处的取舍正好相反，理由也相反：
        // 这是个**写操作**，而且可能是不可逆的（作废单据）。记在之后的话，
        // 接口执行成功但响应链路出问题时，一次已经落地的写操作就没有任何痕迹。
        //
        // ⚠️ 只记参数【名】。入参是服务端按配置从记录里取的，
        // 里面完全可能有手机号、账号 —— 抄进日志表就是二次泄露
        auditor.buttonInvoked(code, buttonKey, action.getInterfaceCode(), pkValue, args.keySet());
        return dataInterfaceService.invoke(action.getInterfaceCode(), args);
    }

    /**
     * 从已发布配置里找按钮
     *
     * 解析失败返回 null —— 由调用方报「按钮不存在」。一份坏掉的按钮配置
     * 不该以「系统异常」的形态抛给用户
     */
    private CustomButton findButton(String customButtonsJson, String buttonKey) {
        if (StrUtil.isBlank(customButtonsJson) || StrUtil.isBlank(buttonKey)) {
            return null;
        }
        List<CustomButton> buttons = JsonUtils.parseObjectQuietly(customButtonsJson,
                new TypeReference<List<CustomButton>>() {});
        if (buttons == null) {
            log.warn("[findButton][表单的自定义按钮 JSON 解析失败]");
            return null;
        }
        return buttons.stream()
                .filter(b -> b != null && buttonKey.equals(b.getKey()))
                .findFirst().orElse(null);
    }

    /**
     * 按字段映射拼接口入参
     *
     * 没配映射、或没有当前记录（工具栏按钮）时返回空 Map —— 接口自己的参数默认值
     * 仍然生效，不需要在这里补。
     */
    private Map<String, Object> buildInterfaceArgs(String code,
                                                   List<CustomButton.ButtonAction.ParamMapping> params,
                                                   Object pkValue) {
        Map<String, Object> args = new LinkedHashMap<>();
        if (params == null || params.isEmpty() || pkValue == null) {
            return args;
        }
        // 重新读一遍记录。走 get() 而不是直接查库：它带上了权限、发布状态、
        // 脱敏与反序列化 —— 按钮拿到的值应当与用户在详情里看到的一致。
        //
        // ⚠️ 顺带说明字段权限在这条链路上的位置：get() 内部已经按 FIELD 裁过，
        // 所以被判为 HIDDEN 的字段**不会**流进数据接口的入参 —— 这个泄露口
        // 在 S4b-2 接入 get 裁剪时就已经堵上了，不需要在这里再裁一次
        Map<String, Object> row = get(code, pkValue);
        if (row == null) {
            return args;
        }
        // 但「裁掉了」和「本来就是空」在 row 里长得一模一样，都是取不到键。
        // 于是会静默地把 null 当入参传给数据接口 —— 而接口那头可能是
        // 「UPDATE ... WHERE mobile = :mobile」这类语句，入参为空时的行为
        // 完全取决于接口怎么写，最坏情况是条件失效、命中全表。
        //
        // 所以这里把「字段被裁掉」与「字段值为空」区分开：前者直接拒掉整次调用。
        // 报错比静默传 null 好 —— 用户至少知道是权限问题，而不是对着一个
        // 「点了没反应」或者「作废了别人的单据」的按钮猜原因
        Set<String> mappedFields = new LinkedHashSet<>();
        for (CustomButton.ButtonAction.ParamMapping param : params) {
            if (param != null && StrUtil.isNotBlank(param.getField())) {
                mappedFields.add(param.getField());
            }
        }
        DatasetFieldModeRespDTO modes = fieldCropper.resolve(code, mappedFields, false);
        for (CustomButton.ButtonAction.ParamMapping param : params) {
            if (param == null || StrUtil.isBlank(param.getField())) {
                continue;
            }
            if (modes.isHidden(param.getField())) {
                log.warn("[buildInterfaceArgs][表单({}) 按钮入参映射的字段({})被字段权限隐藏，拒绝调用]",
                        code, param.getField());
                throw exception(FORM_DESIGN_BUTTON_FIELD_FORBIDDEN, param.getField());
            }
        }
        for (CustomButton.ButtonAction.ParamMapping param : params) {
            if (param == null || StrUtil.isBlank(param.getName())
                    || StrUtil.isBlank(param.getField())) {
                // 配不全的映射在发布校验里已经被拦；这里再跳一次，
                // 是为了「手改过配置的表单」不至于把一个 null 键塞进入参
                continue;
            }
            args.put(param.getName(), row.get(param.getField()));
        }
        return args;
    }


}
