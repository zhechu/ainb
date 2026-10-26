package cn.enlev.ainb.module.infra.service.formdesign;

import cn.enlev.ainb.framework.test.core.ut.BaseDbUnitTest;
import cn.enlev.ainb.module.infra.dal.dataobject.formdesign.FormDesignDO;
import cn.enlev.ainb.module.infra.dal.dataobject.formdesign.FormDesignMenuDO;
import cn.enlev.ainb.module.infra.dal.mysql.formdesign.FormDesignMapper;
import cn.enlev.ainb.module.infra.dal.mysql.formdesign.FormDesignMenuMapper;
import cn.enlev.ainb.module.infra.controller.admin.formdesign.vo.FormDesignUnpublishRespVO;
import cn.enlev.ainb.module.infra.enums.ErrorCodeConstants;
import cn.enlev.ainb.module.infra.enums.artifact.ArtifactSourceEnum;
import cn.enlev.ainb.module.infra.framework.formdesign.core.file.RichContentGuard;
import cn.enlev.ainb.module.infra.framework.formdesign.core.resource.FormResourceDescriptorBuilder;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static cn.enlev.ainb.framework.test.core.util.AssertUtils.assertServiceException;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 下线（{@code unpublish}）与删除的菜单回收契约，打真 H2 + 真 MyBatis
 *
 * <h3>🔴 为什么必须是真库，不能 mock mapper</h3>
 * 本类守的缺陷是<b>「MyBatis-Plus 静默不把某一列放进 SET 子句」</b>：
 * MP 的字段更新策略默认 NOT_NULL，实体上为 null 的列根本不参与 UPDATE。
 * {@code unpublish} 原来写的是 {@code updateObj.setMenuId(null)} + {@code updateById}，
 * 看上去是「清空菜单编号」，实际是「这一列别动」—— 接口返回成功、事务正常提交、
 * 日志干干净净，只有库里那一列没变。mock 掉 mapper 的测试对此<b>结构上无能为力</b>：
 * 它只会验到「service 调了 updateById」，而调用确实发生了。
 * 与 {@code ArtifactRecordOptimisticLockTest} 选择真 H2 的理由是同一条。
 *
 * <h3>缺陷的实际后果（线上实测复现过）</h3>
 * {@code menu_id} 悬挂之后，这张表单同时踩中两个死路：
 * <ol>
 *   <li><b>删不掉</b>：{@code deleteFormDesign} 的判据含 {@code menu_id != null}，
 *       抛 BIND_MENU，前端据此提示「请先在更多中执行下线」；
 *       而下线又因为 {@code published_version} 已被上一次下线归 0，抛 NOT_PUBLISHED。
 *       两条路互相指对方，用户在界面上看到的就是「点删除没反应」。</li>
 *   <li><b>重新发布也是坏的</b>：{@code publish} 用 {@code menu_id == null} 判 firstPublish，
 *       悬挂时判成「不是首次发布」⟹ {@code needCreateMenu=false} ⟹
 *       前端不去建菜单，而库里那些菜单其实早就是 {@code deleted=1}。
 *       结果是「发布成功了，但菜单不存在、7 个按钮权限也没建」。</li>
 * </ol>
 *
 * <h3>✅ 变异探针（已实测，不是声称）</h3>
 * 把 {@code unpublish} 里的 {@code update(entity, wrapper)} 换回缺陷写法
 * {@code setMenuId(null)} + {@code updateById} 之后重跑本类：
 * <b>3 个用例红 2 个</b>，失败信息是
 * {@code menu_id 没被清掉 …… expected: <null> but was: <9100000001>}
 * 与 {@code testUnpublishTwiceRejected} 里的 {@code expected: <null> but was: <9100000021>}。
 * 剩下那个绿的（{@link #testDeleteStillRefusedWhileMenuLinksAlive}）是<b>反向</b>用例，
 * 它本来就该在两种写法下都绿 —— 它守的是「修复没有顺手把删除门禁拆掉」。
 *
 * @author 走进新时代
 */
@Import(FormDesignServiceImpl.class)
@DisplayName("表单设计：下线必须真的清掉 menu_id")
public class FormDesignUnpublishTest extends BaseDbUnitTest {

    @Resource
    private FormDesignServiceImpl formDesignService;
    @Resource
    private FormDesignMapper formDesignMapper;
    @Resource
    private FormDesignMenuMapper formDesignMenuMapper;

    @MockitoBean
    private RichContentGuard richContentGuard;
    @MockitoBean
    private FormResourceDescriptorBuilder resourceDescriptorBuilder;
    @MockitoBean
    private FormDesignPublishValidator publishValidator;
    @MockitoBean
    private FormDesignVersionService versionService;

    // ==================== 造数据 ====================

    /** 造一张草稿表单 */
    private FormDesignDO insertDraft(String code) {
        FormDesignDO formDesign = new FormDesignDO();
        formDesign.setName("下线契约测试表单");
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

    /**
     * 把一张草稿推进到「已发布且绑好菜单」的形态
     *
     * <p>不走 {@code publish}：那条路径要拉起版本快照、发布校验与事件广播，
     * 与本类要验的东西无关。这里直接把发布与 bindMenu 的<b>落库结果</b>摆出来 ——
     * 本类验的正是「从这个状态出发，下线能不能把该清的都清掉」。
     *
     * @param menuIds 发布时前端建好的菜单编号，第一个是菜单本体、其余是按钮权限
     */
    private FormDesignDO givenPublishedWithMenus(String code, List<Long> menuIds) {
        return givenPublished(code, menuIds, null);
    }

    /**
     * 同 {@link #givenPublishedWithMenus}，额外带上「已发布到 App 端」的导航节点编号
     *
     * @param appNavNodeId 发布时前端建好的导航节点编号；null 表示没发布过 App 端
     */
    private FormDesignDO givenPublished(String code, List<Long> menuIds, Long appNavNodeId) {
        FormDesignDO formDesign = insertDraft(code);
        FormDesignDO published = new FormDesignDO();
        published.setId(formDesign.getId());
        published.setStatus(1);
        published.setPublishedVersion(1);
        published.setMenuId(menuIds.get(0));
        published.setAppNavNodeId(appNavNodeId);
        published.setVersion(formDesign.getVersion());
        formDesignMapper.updateById(published);

        for (int i = 0; i < menuIds.size(); i++) {
            FormDesignMenuDO record = new FormDesignMenuDO();
            record.setDesignId(formDesign.getId());
            record.setMenuId(menuIds.get(i));
            record.setMenuType(i == 0 ? 2 : 3);
            formDesignMenuMapper.insert(record);
        }
        return formDesignMapper.selectById(formDesign.getId());
    }

    // ==================== 用例 ====================

    /**
     * 🔴 本类的主用例，也是那个缺陷的回归守卫
     *
     * <p>断言分两层：<b>列真的被清了</b>（重新从库里读，不看内存对象），
     * 以及<b>清掉之后删除走得通</b>（死循环被打开）。
     * 只断言前者是不够的 —— 悬挂的危害不在于那一列脏，而在于它把删除与重新发布一起堵死。
     */
    @Test
    @DisplayName("🔴 下线之后 menu_id 必须为 NULL（MP 的 NOT_NULL 策略会静默吞掉 setMenuId(null)），且表单随后可删")
    void testUnpublishClearsMenuIdAndUnblocksDelete() {
        FormDesignDO published = givenPublishedWithMenus("unpublish_clear_menu_id",
                List.of(9100000001L, 9100000002L, 9100000003L));
        // 前置条件本身要成立，否则后面的断言没有意义
        assertEquals(9100000001L, published.getMenuId());
        assertEquals(3, formDesignMenuMapper.selectListByDesignId(published.getId()).size());

        FormDesignUnpublishRespVO reclaimed = formDesignService.unpublish(published.getId());

        // 1. 交还给前端去删的菜单编号，必须是全部 3 个、且顺序与登记时一致
        assertEquals(List.of(9100000001L, 9100000002L, 9100000003L), reclaimed.getMenuIds());
        assertNull(reclaimed.getAppNavNodeId(), "没发布过 App 端时不该交还导航节点编号");

        // 2. 🔴 重新从库里读：menu_id 必须真的是 NULL
        FormDesignDO after = formDesignMapper.selectById(published.getId());
        assertNull(after.getMenuId(),
                "menu_id 没被清掉 —— 十有八九是又改回了 setMenuId(null) + updateById："
                        + "MP 的 NOT_NULL 策略会把这一列整个从 SET 子句里拿掉，且不报错");
        assertEquals(0, after.getPublishedVersion());
        assertEquals(0, after.getStatus());
        // 关联行全部逻辑删除，下线才有「精确回收」的凭据
        assertTrue(formDesignMenuMapper.selectListByDesignId(published.getId()).isEmpty());
        // 乐观锁版本号照旧要自增（这条路径改成了 update(entity, wrapper)，拦截器仍够得着）
        assertEquals(published.getVersion() + 1, after.getVersion());

        // 3. 🔴 死循环被打开：下线之后这张表单必须能删掉
        formDesignService.deleteFormDesign(published.getId());
        assertNull(formDesignMapper.selectById(published.getId()), "软删之后应查不到");
    }

    /**
     * 🔴 app_nav_node_id 与 menu_id 是同一条红线、同一个坑
     *
     * <p>漏掉 unpublish wrapper 里那句 {@code .set(FormDesignDO::getAppNavNodeId, null)} 的话，
     * 下线之后移动端导航树上还挂着这个入口，点进去是一个已下线的表单 —— 不报错、只是白屏。
     * 与主用例一样，断言必须重新从库里读，不看内存对象。
     */
    @Test
    @DisplayName("🔴 下线之后 app_nav_node_id 必须为 NULL，且编号交还前端去删导航节点")
    void testUnpublishClearsAppNavNodeId() {
        FormDesignDO published = givenPublished("unpublish_clear_app_nav",
                List.of(9100000031L, 9100000032L), 9200000001L);
        assertEquals(9200000001L, published.getAppNavNodeId());

        FormDesignUnpublishRespVO reclaimed = formDesignService.unpublish(published.getId());

        assertEquals(9200000001L, reclaimed.getAppNavNodeId(),
                "导航节点编号必须交还前端，否则移动端那棵树上会留一个点进去已下线的入口");
        FormDesignDO after = formDesignMapper.selectById(published.getId());
        assertNull(after.getAppNavNodeId(),
                "app_nav_node_id 没被清掉 —— 与 menu_id 同一个坑：setAppNavNodeId(null) + updateById"
                        + " 会被 MP 的 NOT_NULL 策略静默吞掉，必须走 LambdaUpdateWrapper#set");
    }

    /**
     * 反向用例：修好清空逻辑<b>不等于</b>放宽删除的门禁
     *
     * <p>菜单关联还活着的时候必须照样拒绝删除 —— 那些 {@code system_menu} 记录
     * 只能由下线流程交还前端去回收，infra 反向依赖不到 system。
     * 少了这一条，主用例通过也可能是因为门禁被一起拆了。
     */
    @Test
    @DisplayName("菜单关联仍存活时，删除照样被拒（BIND_MENU 门禁没有因为本次修复而放宽）")
    void testDeleteStillRefusedWhileMenuLinksAlive() {
        FormDesignDO published = givenPublishedWithMenus("delete_refused_while_bound",
                List.of(9100000011L, 9100000012L));

        assertServiceException(
                () -> formDesignService.deleteFormDesign(published.getId()),
                ErrorCodeConstants.FORM_DESIGN_DELETE_FAIL_BIND_MENU);
        // 拒绝就是拒绝，不能顺手改坏数据
        assertEquals(9100000011L, formDesignMapper.selectById(published.getId()).getMenuId());
        assertEquals(2, formDesignMenuMapper.selectListByDesignId(published.getId()).size());
    }

    /**
     * 下线是「回收」动作，重复执行必须给出明确的失败而不是静默成功
     *
     * <p>这一条钉住 {@code published_version} 判据的位置：它在任何写操作<b>之前</b>。
     * 主用例里下线成功之后 published_version 归 0，再下一次就落到这条分支 ——
     * 而那正是用户被前端提示「请先执行下线」时会走到的地方，
     * 报「表单尚未发布」虽然听着别扭，至少不是静默把数据又改一遍。
     */
    @Test
    @DisplayName("对已下线的表单再下线，抛 NOT_PUBLISHED 且不留副作用")
    void testUnpublishTwiceRejected() {
        FormDesignDO published = givenPublishedWithMenus("unpublish_twice",
                List.of(9100000021L, 9100000022L));
        formDesignService.unpublish(published.getId());
        FormDesignDO afterFirst = formDesignMapper.selectById(published.getId());

        assertServiceException(
                () -> formDesignService.unpublish(published.getId()),
                ErrorCodeConstants.FORM_DESIGN_NOT_PUBLISHED);

        FormDesignDO afterSecond = formDesignMapper.selectById(published.getId());
        assertEquals(afterFirst.getVersion(), afterSecond.getVersion(), "被拒绝的那次不该动过库");
        assertNull(afterSecond.getMenuId());
    }

}
