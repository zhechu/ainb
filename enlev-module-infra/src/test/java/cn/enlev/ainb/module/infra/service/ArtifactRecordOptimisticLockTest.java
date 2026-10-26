package cn.enlev.ainb.module.infra.service;

import cn.enlev.ainb.framework.common.exception.ServiceException;
import cn.enlev.ainb.framework.test.core.ut.BaseDbUnitTest;
import cn.enlev.ainb.module.infra.controller.admin.formdesign.vo.FormDesignSaveReqVO;
import cn.enlev.ainb.module.infra.dal.dataobject.datamodel.DataModelDO;
import cn.enlev.ainb.module.infra.dal.dataobject.formdesign.FormDesignDO;
import cn.enlev.ainb.module.infra.dal.mysql.datamodel.DataModelMapper;
import cn.enlev.ainb.module.infra.dal.mysql.formdesign.FormDesignMapper;
import cn.enlev.ainb.module.infra.enums.artifact.ArtifactSourceEnum;
import cn.enlev.ainb.module.infra.framework.formdesign.core.file.RichContentGuard;
import cn.enlev.ainb.module.infra.framework.formdesign.core.resource.FormResourceDescriptorBuilder;
import cn.enlev.ainb.module.infra.service.formdesign.FormDesignPublishValidator;
import cn.enlev.ainb.module.infra.service.formdesign.FormDesignServiceImpl;
import cn.enlev.ainb.module.infra.service.formdesign.FormDesignVersionService;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static cn.enlev.ainb.framework.test.core.util.AssertUtils.assertServiceException;
import static cn.enlev.ainb.module.infra.enums.ErrorCodeConstants.FORM_DESIGN_RECORD_VERSION_CONFLICT;
import static cn.enlev.ainb.module.infra.enums.ErrorCodeConstants.FORM_DESIGN_RECORD_VERSION_REQUIRED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「AI 产物治理」记录级乐观锁的<b>真跑</b>测试（迭代七-09 B4-4）
 *
 * <h3>🔴 为什么这一类必须真跑，不能 mock</h3>
 * 方案里那句「mock 出来的乐观锁什么都证明不了」是字面意思。乐观锁在这里由<b>三样东西</b>
 * 共同构成，缺一样就是静默失效，而 mock 恰好把三样全绕过去了：
 * <ol>
 *   <li>{@code @Version} 注解打在 DO 的字段上</li>
 *   <li>{@code OptimisticLockerInnerInterceptor} 真的被注册进了 {@code SqlSessionFactory}
 *       —— 注册点在 {@code EnlevMybatisAutoConfiguration}，而 {@link BaseDbUnitTest}
 *       正是 {@code @Import} 了它，所以本类走的是<b>生产同一条</b>装配路径</li>
 *   <li>调用方<b>检查了影响行数</b>。拦截器在版本对不上时<b>不抛异常</b>，
 *       只是让 UPDATE 命中 0 行 —— 不查行数的话表现是「接口返回成功、对方的改动被静默抹掉」</li>
 * </ol>
 * mock 掉 mapper 的话，第 2 条根本没被装载、第 3 条被 stub 成了想要的返回值，
 * 测试全绿而线上照样并发覆盖。所以本类打的是真 H2 + 真 MyBatis。
 *
 * <h3>⚠️ 覆盖到的三条写路径形态</h3>
 * 拦截器对「实体在哪」是有分别的，三种形态都得单独验：
 * <ul>
 *   <li>{@code updateById(entity)} —— publish / bindMenu / rollback 走这一支。
 *       ⚠️ 这一支<b>清不掉任何列</b>：MP 的 NOT_NULL 策略会把实体上为 null 的字段整个
 *       从 SET 子句里拿掉。unpublish 要清 {@code menu_id}，所以已经改走下一支，
 *       见 {@code FormDesignUnpublishTest}</li>
 *   <li>{@code update(entity, wrapper)} —— 整份保存走这一支（{@code clearableColumnsWrapper}）。
 *       拦截器要把版本条件 {@code apply} 到<b>传进来的那个 wrapper</b> 上，
 *       所以手写的 wrapper 里<b>刻意不写</b>版本条件，写两遍会在 WHERE 里出现两条同样的条件</li>
 *   <li>手写 {@code @Update} —— {@code FormDesignMapper#updateToDelete}。参数里没有实体，
 *       拦截器<b>结构上够不着</b>，版本条件只能手写在 SQL 里</li>
 * </ul>
 *
 * <p>第 3 条还配了一条<b>反向</b>用例（{@link #testNullVersionSilentlySkipsTheLock}）：
 * 实体的 version 为 null 时拦截器<b>静默跳过</b>、锁等于没开、而且<b>不报错</b>。
 * 这正是「加了 {@code @Version} 注解只算 10%」那 90% 的失效形态，
 * 也是两个 Service 里各有一道 {@code validateRecordVersion} 绊线的原因。
 * 把失效形态本身测出来，那道绊线才是有据可依的，而不是「顺手加的防御」。
 *
 * @author 走进新时代
 */
@Import(FormDesignServiceImpl.class)
public class ArtifactRecordOptimisticLockTest extends BaseDbUnitTest {

    @Resource
    private FormDesignServiceImpl formDesignService;
    @Resource
    private FormDesignMapper formDesignMapper;
    @Resource
    private DataModelMapper dataModelMapper;

    @MockitoBean
    private RichContentGuard richContentGuard;
    @MockitoBean
    private FormResourceDescriptorBuilder resourceDescriptorBuilder;
    @MockitoBean
    private FormDesignPublishValidator publishValidator;
    @MockitoBean
    private FormDesignVersionService versionService;

    // ==================== 造数据 ====================

    private DataModelDO insertDataModel(String tableName) {
        DataModelDO dataModel = new DataModelDO();
        dataModel.setDataSourceConfigId(0L);
        dataModel.setTableName(tableName);
        dataModel.setTableComment("乐观锁真跑测试");
        dataModel.setModelName("LockDemoModel");
        dataModel.setSource(ArtifactSourceEnum.MANUAL.getSource());
        dataModel.setManualEditedFlag(false);
        dataModel.setVersion(0);
        dataModelMapper.insert(dataModel);
        return dataModel;
    }

    private FormDesignDO insertFormDesign(String code) {
        FormDesignDO formDesign = new FormDesignDO();
        formDesign.setName("乐观锁真跑测试表单");
        formDesign.setCode(code);
        formDesign.setFormType(1);
        formDesign.setStatus(0);
        formDesign.setSchemaVersion(1);
        formDesign.setPublishedVersion(0);
        formDesign.setSort(0);
        formDesign.setExtensionMode(1);
        formDesign.setSubmitHookTimeoutAction(1);
        formDesign.setSource(ArtifactSourceEnum.MANUAL.getSource());
        formDesign.setManualEditedFlag(false);
        formDesign.setVersion(0);
        formDesignMapper.insert(formDesign);
        return formDesign;
    }

    private FormDesignSaveReqVO saveReq(FormDesignDO formDesign, String newName) {
        FormDesignSaveReqVO reqVO = new FormDesignSaveReqVO();
        reqVO.setId(formDesign.getId());
        reqVO.setName(newName);
        reqVO.setCode(formDesign.getCode());
        reqVO.setFormType(formDesign.getFormType());
        reqVO.setVersion(formDesign.getVersion());
        return reqVO;
    }

    // ==================== updateById(entity) ====================

    /**
     * 🔴 §7② 点名的负对照：读-读-写-写，第二次写<b>真的</b>命中 0 行。
     *
     * <p>这里的交错（两次读都发生在任何一次写之前）正是并发覆盖的形态本身 ——
     * 不需要真的开两个线程去抢，抢到的也是这同一个交错。
     * 线程版在 {@link #testConcurrentUpdateFormDesignExactlyOneWins}。
     */
    @Test
    @DisplayName("🔴 updateById：两人读到同一版本，先写的赢，后写的命中 0 行且改动没有落库")
    void testUpdateByIdWithStaleVersionAffectsZeroRows() {
        DataModelDO inserted = insertDataModel("biz_lock_demo");
        Long id = inserted.getId();
        // 两个人各自读了一份
        DataModelDO first = dataModelMapper.selectById(id);
        DataModelDO second = dataModelMapper.selectById(id);
        assertEquals(0, first.getVersion());
        assertEquals(0, second.getVersion(), "前置：两份快照的版本相同");

        // 第一个人写：命中 1 行，且拦截器把实体上的 version 推进了一格
        first.setModelName("第一个人改的");
        assertEquals(1, dataModelMapper.updateById(first));
        assertEquals(1, first.getVersion(), "拦截器会把新版本回写到实体上（MP_OPTLOCK_VERSION_ORIGINAL 机制）");
        assertEquals(1, dataModelMapper.selectById(id).getVersion(), "库里也推进到了 1");

        // 🔴 第二个人写：手里还是 version=0，命中 0 行
        second.setModelName("第二个人改的");
        assertEquals(0, dataModelMapper.updateById(second),
                "版本对不上时拦截器不抛异常、只让 UPDATE 命中 0 行 —— 所以调用方必须查行数");

        // 第一个人的改动完好，第二个人的没有落库
        DataModelDO after = dataModelMapper.selectById(id);
        assertEquals("第一个人改的", after.getModelName(), "后写的那一次不得覆盖先写的");
        assertEquals(1, after.getVersion());
    }

    /**
     * 🔴 反向用例：把失效形态本身测出来。
     *
     * <p>实体的 version 为 null 时，{@code doOptimisticLocker} 取到 null 就直接 return ——
     * <b>不加</b>版本条件、<b>不抛</b>异常。于是「忘了填 version」的写法照样写成功，
     * 而且把别人刚提交的改动整份盖掉，全程没有任何报错。
     *
     * <p>本用例故意断言它<b>成功</b>：这不是期望的行为，而是<b>必须被绊线拦住</b>的行为。
     * 钉住它，{@code validateRecordVersion} 里那句「没带版本 → 硬拒绝」才有据可依；
     * 将来若有人把那道绊线放宽成「没带版本就不加锁」，这条用例会立刻指出他在放什么。
     *
     * <p>⚠️ 拦截器提供了 {@code setException} 能把这种情况变成失败，但那是<b>单例级、跨全仓</b>的开关，
     * 装在一个连编译都参与不了的模块也在共用的 framework 里，
     * 且共享的异常实例堆栈指向 bean 构造处、会把排查引到错误的位置。所以绊线设在 Service 层。
     */
    @Test
    @DisplayName("🔴 失效形态：version 为 null 时拦截器静默跳过 —— 写成功、版本不推进、别人的改动被盖掉")
    void testNullVersionSilentlySkipsTheLock() {
        DataModelDO inserted = insertDataModel("biz_lock_demo_null");
        Long id = inserted.getId();
        // 别人先改过一次，库里已经是 version=1
        DataModelDO other = dataModelMapper.selectById(id);
        other.setModelName("别人刚提交的");
        assertEquals(1, dataModelMapper.updateById(other));

        // 「忘了填 version」的写法：new 一个 DO、只 set 要改的字段
        DataModelDO forgot = new DataModelDO();
        forgot.setId(id);
        forgot.setModelName("漏带版本号的覆盖");
        assertEquals(1, dataModelMapper.updateById(forgot),
                "⚠️ 这就是失效形态：命中 1 行、没有任何异常");

        DataModelDO after = dataModelMapper.selectById(id);
        assertEquals("漏带版本号的覆盖", after.getModelName(), "别人的改动被静默盖掉了");
        assertEquals(1, after.getVersion(), "版本也没有推进 —— 从库的状态上完全看不出发生过覆盖");
    }

    // ==================== update(entity, wrapper) ====================

    @Test
    @DisplayName("🔴 update(entity, wrapper)：手写 wrapper 里没有版本条件，但拦截器会自己 apply 上去")
    void testUpdateWithWrapperAndStaleVersionAffectsZeroRows() {
        FormDesignDO inserted = insertFormDesign("lock_wrapper_demo");
        Long id = inserted.getId();
        FormDesignDO first = formDesignMapper.selectById(id);
        FormDesignDO second = formDesignMapper.selectById(id);

        first.setName("第一个人改的");
        assertEquals(1, formDesignMapper.update(first,
                new LambdaUpdateWrapper<FormDesignDO>().eq(FormDesignDO::getId, id)));

        second.setName("第二个人改的");
        assertEquals(0, formDesignMapper.update(second,
                        new LambdaUpdateWrapper<FormDesignDO>().eq(FormDesignDO::getId, id)),
                "wrapper 上只有 id 条件，版本条件是拦截器补的 —— 补上了才会是 0 行");
        assertEquals("第一个人改的", formDesignMapper.selectById(id).getName());
    }

    // ==================== 手写 @Update ====================

    /**
     * 🔴 手写 SQL 上拦截器<b>结构上够不着</b>：它要从参数 Map 里取出 {@code et}（实体）
     * 才能读到 version，而 {@code updateToDelete} 的参数只有两个 {@code @Param}。
     * 于是版本条件只能手写在 SQL 里 —— 而手写就意味着<b>可能写错</b>
     * （把 {@code version = #{version}} 写成 {@code version = version}，
     * 表现是「删除永远成功」，谁也看不出来）。所以这条要真跑。
     */
    @Test
    @DisplayName("🔴 updateToDelete：版本对不上 → 0 行且那一行还活着；版本对上 → 1 行且被软删")
    void testUpdateToDeleteCarriesVersionCondition() {
        FormDesignDO inserted = insertFormDesign("lock_tombstone_demo");
        Long id = inserted.getId();

        // 版本对不上：什么都没删
        assertEquals(0, formDesignMapper.updateToDelete(id, "lock_tombstone_demo_del_" + id, 999));
        FormDesignDO stillAlive = formDesignMapper.selectById(id);
        assertNotNull(stillAlive, "版本对不上时那一行必须还在（selectById 带 deleted=0）");
        assertEquals("lock_tombstone_demo", stillAlive.getCode(), "编码也不该被改成墓碑值");
        assertEquals(0, stillAlive.getVersion());

        // 版本对上：删掉了，且 version 被推进
        assertEquals(1, formDesignMapper.updateToDelete(id, "lock_tombstone_demo_del_" + id, 0));
        assertNull(formDesignMapper.selectById(id), "软删之后按逻辑删除条件查不到了");
    }

    @Test
    @DisplayName("🔴 updateToDelete 重放：同一个版本再删一次 → 0 行（不会把已删行的 version 无限推进）")
    void testUpdateToDeleteReplayAffectsZeroRows() {
        FormDesignDO inserted = insertFormDesign("lock_tombstone_replay");
        Long id = inserted.getId();

        assertEquals(1, formDesignMapper.updateToDelete(id, "lock_tombstone_replay_del_" + id, 0));
        assertEquals(0, formDesignMapper.updateToDelete(id, "lock_tombstone_replay_del_" + id, 0),
                "deleted=0 与 version=0 两个条件都已不成立");
        assertEquals(0, formDesignMapper.updateToDelete(id, "lock_tombstone_replay_del_" + id, 1),
                "带上推进后的版本也不行 —— deleted=0 这一条拦住了");
    }

    // ==================== Service 层：绊线与真并发 ====================

    @Test
    @DisplayName("🔴 绊线：整份保存不带 version → FORM_DESIGN_RECORD_VERSION_REQUIRED，绝不退化成不加锁")
    void testUpdateFormDesignWithoutVersionRejected() {
        FormDesignDO inserted = insertFormDesign("lock_required_demo");
        FormDesignSaveReqVO reqVO = saveReq(inserted, "改了名字");
        reqVO.setVersion(null);

        assertServiceException(() -> formDesignService.updateFormDesign(reqVO),
                FORM_DESIGN_RECORD_VERSION_REQUIRED);
        // 拒绝之后库里的东西一个字都没动
        assertEquals("乐观锁真跑测试表单", formDesignMapper.selectById(inserted.getId()).getName());
    }

    @Test
    @DisplayName("🔴 前置复核：版本落后 → CONFLICT，不等到 UPDATE 才发现")
    void testUpdateFormDesignWithStaleVersionRejected() {
        FormDesignDO inserted = insertFormDesign("lock_stale_demo");
        // 别人先存了一次，库里推进到 1
        FormDesignSaveReqVO other = saveReq(inserted, "别人改的");
        formDesignService.updateFormDesign(other);
        assertEquals(1, formDesignMapper.selectById(inserted.getId()).getVersion());

        // 我手里还是 version=0
        FormDesignSaveReqVO mine = saveReq(inserted, "我改的");
        assertServiceException(() -> formDesignService.updateFormDesign(mine),
                FORM_DESIGN_RECORD_VERSION_CONFLICT);
        assertEquals("别人改的", formDesignMapper.selectById(inserted.getId()).getName());
    }

    /**
     * 🔴 §7② 的字面要求：两个<b>并发</b>更新，第二个真的抛出来。
     *
     * <p>用栅栏把两个线程卡在「都已读完、都还没写」的那一刻再同时放行，
     * 拿到的是最坏的那个交错。断言刻意写成「恰好一个成功、恰好一个抛 CONFLICT」，
     * 而不是「第二个抛」—— 谁先谁后取决于调度，写死顺序就是写了一条会随机红的测试。
     * 两种可能的失败点（前置复核读到新版本 / 拦截器让 UPDATE 命中 0 行）报的是同一个错误码，
     * 所以无论落在哪一种交错上，断言都成立。
     */
    @Test
    @DisplayName("🔴 真并发：两个线程同时保存同一张表单 → 恰好一个成功、恰好一个版本冲突")
    void testConcurrentUpdateFormDesignExactlyOneWins() throws Exception {
        FormDesignDO inserted = insertFormDesign("lock_concurrent_demo");
        FormDesignSaveReqVO reqA = saveReq(inserted, "A 的改动");
        FormDesignSaveReqVO reqB = saveReq(inserted, "B 的改动");

        CountDownLatch bothReady = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (FormDesignSaveReqVO reqVO : List.of(reqA, reqB)) {
                futures.add(pool.submit(() -> {
                    bothReady.countDown();
                    // 两个线程都到齐再一起动手，尽量把窗口压到最小
                    start.await();
                    try {
                        formDesignService.updateFormDesign(reqVO);
                        return "OK";
                    } catch (ServiceException ex) {
                        return "CONFLICT:" + ex.getCode();
                    }
                }));
            }
            assertTrue(bothReady.await(10, TimeUnit.SECONDS), "两个线程都该就位");
            start.countDown();

            List<String> outcomes = new ArrayList<>();
            for (Future<String> future : futures) {
                outcomes.add(future.get(30, TimeUnit.SECONDS));
            }
            long succeeded = outcomes.stream().filter("OK"::equals).count();
            long conflicted = outcomes.stream()
                    .filter(outcome -> outcome.equals("CONFLICT:" + FORM_DESIGN_RECORD_VERSION_CONFLICT.getCode()))
                    .count();
            assertEquals(1, succeeded, "实际结果：" + outcomes);
            assertEquals(1, conflicted, "实际结果：" + outcomes);
        } finally {
            pool.shutdownNow();
        }

        // 库里只推进了一格，且内容是赢的那一方写的
        FormDesignDO after = formDesignMapper.selectById(inserted.getId());
        assertEquals(1, after.getVersion());
        assertTrue("A 的改动".equals(after.getName()) || "B 的改动".equals(after.getName()),
                "实际名字：" + after.getName());
    }

    // ==================== 治理字段本身 ====================

    /**
     * 🔴 复制出来的表单必须<b>重置</b>四个治理字段。
     *
     * <p>{@code BeanUtils.toBean} 会把源表单那一行的 version 一并搬过来，
     * 而副本是<b>全新一行</b>：不重置的话它从出生起就带着别人的版本号，
     * 第一个改它的人会撞上一次莫名其妙的版本冲突（库里的初值是 0，他手里的是 3）。
     * {@code source} 不重置的话，「复制一张 AI 建的表单」会得到一张仍然标着 AI 的表单，
     * 而它其实是一个人在界面上点出来的 —— 整条 manual_edited_flag 治理就此失真。
     */
    @Test
    @DisplayName("🔴 复制表单：version 归零、source 归 MANUAL、接管标记清空、指纹清空")
    void testCopyFormDesignResetsGovernanceFields() {
        FormDesignDO source = insertFormDesign("lock_copy_source");
        // 把它伪装成一张「AI 建的、已经被人接管过、改过两轮」的表单
        FormDesignDO aiStamped = new FormDesignDO();
        aiStamped.setId(source.getId());
        aiStamped.setVersion(source.getVersion());
        aiStamped.setSource(ArtifactSourceEnum.AI.getSource());
        aiStamped.setManualEditedFlag(true);
        aiStamped.setSchemaFingerprint("0".repeat(64));
        assertEquals(1, formDesignMapper.updateById(aiStamped));

        Long copyId = formDesignService.copyFormDesign(source.getId());

        FormDesignDO copy = formDesignMapper.selectById(copyId);
        assertNotNull(copy);
        assertEquals(0, copy.getVersion(), "副本是新行，版本号必须归零");
        assertEquals(ArtifactSourceEnum.MANUAL.getSource(), copy.getSource(),
                "复制是人在界面上点的动作，来源不得继承 AI");
        assertEquals(false, copy.getManualEditedFlag(), "接管标记不得继承 —— 副本还没被人改过");
        assertNull(copy.getSchemaFingerprint(), "指纹是『某张表此刻长什么样』的基线，副本没有基线");
    }

    /**
     * 🔴 「人工改一张 AI 建的表单」要把接管标记打上，而且<b>单调不回退</b>。
     *
     * <p>回退的后果是「人改过的 AI 产物」重新变回「AI 可以随便覆盖的东西」，
     * 而下一次 AI 覆盖掉的就是人工的成果 —— 且没人会收到任何提示。
     */
    @Test
    @DisplayName("🔴 人工改 AI 建的表单 → manual_edited_flag 落库为真（B4-7 打标的服务端一侧）")
    void testManualEditOfAiArtifactSetsFlag() {
        FormDesignDO inserted = insertFormDesign("lock_takeover_demo");
        // 先把它标成 AI 建的
        FormDesignDO aiStamped = new FormDesignDO();
        aiStamped.setId(inserted.getId());
        aiStamped.setVersion(inserted.getVersion());
        aiStamped.setSource(ArtifactSourceEnum.AI.getSource());
        assertEquals(1, formDesignMapper.updateById(aiStamped));

        // 人工入口（Controller 走的就是这个一参重载）改一次
        FormDesignDO fresh = formDesignMapper.selectById(inserted.getId());
        formDesignService.updateFormDesign(saveReq(fresh, "人改过了"));

        FormDesignDO after = formDesignMapper.selectById(inserted.getId());
        assertEquals(ArtifactSourceEnum.AI.getSource(), after.getSource(),
                "来源是出生证明：人工改过之后它仍然是 AI 建的");
        assertEquals(true, after.getManualEditedFlag(), "接管标记必须落库为真");

        // 🔴 单调：AI 再改一次也不许把标记擦掉
        FormDesignSaveReqVO aiEdit = saveReq(after, "AI 又改了");
        formDesignService.updateFormDesign(aiEdit, ArtifactSourceEnum.AI);
        assertEquals(true, formDesignMapper.selectById(inserted.getId()).getManualEditedFlag(),
                "接管标记一旦为真就不再回退");
    }

    @Test
    @DisplayName("🔴 新建时 VO 上自带的 version 必须被服务端归零 —— 否则那行从出生起就与别人的基线错位")
    void testCreateIgnoresVersionFromRequest() {
        FormDesignSaveReqVO reqVO = new FormDesignSaveReqVO();
        reqVO.setName("新建的表单");
        reqVO.setCode("lock_create_demo");
        reqVO.setFormType(1);
        reqVO.setVersion(99); // 恶意/误传

        Long id = formDesignService.createFormDesign(reqVO);

        FormDesignDO created = formDesignMapper.selectById(id);
        assertEquals(0, created.getVersion(), "convertToDO 会按名字整份搬，必须显式盖回初值");
        assertEquals(ArtifactSourceEnum.MANUAL.getSource(), created.getSource());
        assertEquals(false, created.getManualEditedFlag());
    }

    @Test
    @DisplayName("🔴 并发写之后抛的必须是 ServiceException，不是被吞掉的 null（守卫第 3 条：查影响行数）")
    void testConflictIsThrownNotSwallowed() {
        FormDesignDO inserted = insertFormDesign("lock_thrown_demo");
        formDesignService.updateFormDesign(saveReq(inserted, "先存一次"));

        FormDesignSaveReqVO stale = saveReq(inserted, "拿旧版本再存");
        ServiceException ex = assertThrows(ServiceException.class,
                () -> formDesignService.updateFormDesign(stale));
        assertEquals(FORM_DESIGN_RECORD_VERSION_CONFLICT.getCode(), ex.getCode());
    }

}
