package cn.enlev.ainb.module.system.framework.permission.core.scan;

import cn.enlev.ainb.framework.test.core.ut.BaseDbUnitTest;
import cn.enlev.ainb.module.system.dal.dataobject.permission.ResourceDO;
import cn.enlev.ainb.module.system.dal.mysql.permission.ResourceMapper;
import cn.enlev.ainb.module.system.enums.permission.ResourceTypeEnum;
import cn.enlev.ainb.module.system.service.permission.GrantDecisionService;
import cn.enlev.ainb.module.system.service.permission.ResourceServiceImpl;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import cn.enlev.ainb.module.system.framework.permission.config.PermissionConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link PermissionDriftDetector} 的单元测试类
 *
 * 重点：故意拼错权限串，验证漂移检测能发现并告警。
 * 这正是 v10 S2c 要求的「故意拼错权限串验证告警」。
 *
 * @author 走进新时代
 */
@Import({PermissionDriftDetector.class, ResourceServiceImpl.class,
        CollapsedResourceLocator.class, PermissionConfiguration.class})
public class PermissionDriftDetectorTest extends BaseDbUnitTest {

    @Resource
    private PermissionDriftDetector detector;
    @Resource
    private ResourceMapper resourceMapper;
    /** 只为把 ResourceServiceImpl 装进上下文（它的写方法要驱逐资源链缓存），本类不验驱逐 */
    @MockitoBean
    private GrantDecisionService grantDecisionService;

    @Test
    public void testDetect_无漂移() {
        // 先在 sys_resource 里建好资源行
        insertResource("system:user:query");
        insertResource("system:user");
        insertResource("system");

        PermissionDriftDetector.DriftReport report = detector.detect(Set.of("system:user:query"));

        assertFalse(report.hasDrift(), "库里有这个权限串，不应该报漂移");
        assertTrue(report.missing().isEmpty());
    }

    @Test
    public void testDetect_故意拼错权限串_漂移告警() {
        // 库里没有这个权限串——模拟开发者拼错了
        // 正确写法是 system:user:query，这里拼成了 system:user:queery（多一个 e）
        PermissionDriftDetector.DriftReport report = detector.detect(Set.of("system:user:queery"));

        assertTrue(report.hasDrift(), "拼错的权限串在库里不存在，应该报漂移");
        assertTrue(report.missing().contains("system:user:queery"),
                "missing 集合里应该包含拼错的权限串");
    }

    @Test
    public void testDetect_多个权限串_只有部分漂移() {
        insertResource("system:user:query");
        insertResource("system:user");
        insertResource("system");

        // 一个正确，一个不存在
        PermissionDriftDetector.DriftReport report = detector.detect(
                Set.of("system:user:query", "system:user:create_typo"));

        assertTrue(report.hasDrift());
        assertFalse(report.missing().contains("system:user:query"),
                "正确的权限串不应该出现在 missing 里");
        assertTrue(report.missing().contains("system:user:create_typo"),
                "拼错的权限串应该出现在 missing 里");
    }

    @Test
    public void testDetect_约定式推导_自动建中间目录节点() {
        // 初始库里空的，代码里有 system:user:query
        PermissionDriftDetector.DriftReport report = detector.detect(Set.of("system:user:query"));

        // 约定式推导应该建出三个节点：system, system:user, system:user:query
        assertNotNull(findResource("system"), "应该自动建出目录节点 system");
        assertNotNull(findResource("system:user"), "应该自动建出目录节点 system:user");
        // system:user:query 是叶子，也会被建出来
        assertNotNull(findResource("system:user:query"), "应该自动建出叶子节点 system:user:query");
        assertTrue(report.created() >= 3);
    }

    @Test
    public void testDetect_约定式推导_幂等() {
        // 连续调用两次，应该只建一次节点
        detector.detect(Set.of("system:dept:create"));
        long countAfterFirst = resourceMapper.selectCount();

        detector.detect(Set.of("system:dept:create"));
        long countAfterSecond = resourceMapper.selectCount();

        assertEquals(countAfterFirst, countAfterSecond, "幂等：第二次不应该新建节点");
    }

    @Test
    public void testDetect_陈旧权限串_仅告警不删除() {
        // 库里有一个权限串，但代码里不存在（旧功能已删除）
        insertResource("system:old-feature:query");

        PermissionDriftDetector.DriftReport report = detector.detect(Set.of("system:user:query"));

        assertTrue(report.stale().contains("system:old-feature:query"),
                "库里有但代码里没有的 ACTION 节点应该出现在 stale 里");
        // 陈旧只告警，不删除——主动删除是破坏性操作，需要人工确认
        assertNotNull(findResource("system:old-feature:query"),
                "陈旧节点不应该被自动删除");
    }

    // ========== 陈旧的分类：数据集驱动 vs 待查 ==========
    //
    // 判据是「父行的类型」，见 PermissionDriftDetector#datasetDrivenStale。
    // 🔴 这三个测试之前不存在，代价是一个 NPE 一直躺在那条分支里：
    //    判据写成了 datasetTypes.contains(typeOfId.get(parentId))，而 Set.of 造出来的
    //    ImmutableCollections.SetN 对 contains(null) 抛 NPE —— 根级行的 parentId 是 0，
    //    库里没有 id=0 的行，所以「查不到父行」是常态而不是异常分支。
    //    它只在 detect() 里炸，而 detect() 是启动期扫描调的，等于整条权限扫描被掀掉

    @Test
    public void testDetect_陈旧分类_父行是数据集则归入预期() {
        ResourceDO dataset = insertResource("infra:form-design-run:demo",
                ResourceTypeEnum.DATASET.getCode(), 0L);
        insertResource("infra:form-design-run:demo:create",
                ResourceTypeEnum.ACTION.getCode(), dataset.getId());

        PermissionDriftDetector.DriftReport report = detector.detect(Set.of("system:user:query"));

        assertTrue(report.datasetDrivenStale().contains("infra:form-design-run:demo:create"),
                "父行是 DATASET 的 ACTION 行是运行时登记的，应该归入「数据集驱动」");
        // stale 是超集：分类只是把同一批行拆成两条日志，不是把它们从 stale 里挪走
        assertTrue(report.stale().contains("infra:form-design-run:demo:create"),
                "归入数据集驱动之后仍然应该留在 stale 里（stale 是超集）");
        assertFalse(report.unexplainedStale().contains("infra:form-design-run:demo:create"),
                "已经解释清楚的就不该再出现在「待查」里，否则这条日志又变回永久噪声");
    }

    @Test
    public void testDetect_陈旧分类_父行查不到时留在待查() {
        // parentId=0 ⟹ 库里没有对应父行 ⟹ typeOfId.get 返回 null。
        // 归错方向的代价不对称：把一条真该查的塞进「属预期」等于把它永久藏起来，
        // 所以查不到父行时必须留在待查那一堆
        insertResource("system:orphan-action:query", ResourceTypeEnum.ACTION.getCode(), 0L);

        PermissionDriftDetector.DriftReport report = detector.detect(Set.of("system:user:query"));

        assertTrue(report.stale().contains("system:orphan-action:query"));
        assertFalse(report.datasetDrivenStale().contains("system:orphan-action:query"),
                "父行查不到时不该被当成数据集驱动");
        assertTrue(report.unexplainedStale().contains("system:orphan-action:query"),
                "父行查不到的陈旧行应该留在「待查」里");
    }

    @Test
    public void testDetect_陈旧分类_父行是菜单则留在待查() {
        ResourceDO menu = insertResource("system:old-feature", ResourceTypeEnum.MENU.getCode(), 0L);
        insertResource("system:old-feature:query", ResourceTypeEnum.ACTION.getCode(), menu.getId());

        PermissionDriftDetector.DriftReport report = detector.detect(Set.of("system:user:query"));

        assertFalse(report.datasetDrivenStale().contains("system:old-feature:query"),
                "父行是 MENU 说明它挂在约定树上，是代码侧的权限点，扫不到就值得查");
        assertTrue(report.unexplainedStale().contains("system:old-feature:query"),
                "父行是 MENU 的陈旧行应该留在「待查」里——这才是可能是已删功能的那批");
    }

    private void insertResource(String permission) {
        insertResource(permission,
                permission.chars().filter(c -> c == ':').count() >= 2 ? "ACTION" : "MENU", 0L);
    }

    private ResourceDO insertResource(String permission, String type, Long parentId) {
        ResourceDO resource = new ResourceDO();
        resource.setPermission(permission);
        resource.setName(permission);
        resource.setType(type);
        resource.setParentId(parentId);
        resource.setPath("/root/" + permission + "/");
        resource.setDepth((short) 1);
        resource.setSort(0);
        resource.setDefaultEffect("DENY");
        resource.setStatus(0);
        resourceMapper.insert(resource);
        return resource;
    }

    private ResourceDO findResource(String permission) {
        return resourceMapper.selectListByPermission(permission).stream()
                .findFirst().orElse(null);
    }

}
