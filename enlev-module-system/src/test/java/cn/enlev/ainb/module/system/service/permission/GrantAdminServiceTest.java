package cn.enlev.ainb.module.system.service.permission;

import cn.enlev.ainb.framework.common.enums.CommonStatusEnum;
import cn.enlev.ainb.framework.common.exception.ServiceException;
import cn.enlev.ainb.framework.test.core.ut.BaseDbUnitTest;
import cn.enlev.ainb.module.system.controller.admin.permission.vo.grant.GrantBatchSaveRespVO;
import cn.enlev.ainb.module.system.controller.admin.permission.vo.grant.GrantSaveReqVO;
import cn.enlev.ainb.module.system.dal.dataobject.permission.GrantDO;
import cn.enlev.ainb.module.system.dal.dataobject.permission.ResourceDO;
import cn.enlev.ainb.module.system.dal.mysql.permission.GrantMapper;
import cn.enlev.ainb.module.system.dal.mysql.permission.ResourceMapper;
import cn.enlev.ainb.module.system.enums.permission.GrantActionEnum;
import cn.enlev.ainb.module.system.enums.permission.GrantEffectEnum;
import cn.enlev.ainb.module.system.enums.permission.ResourceTypeEnum;
import cn.enlev.ainb.module.system.framework.permission.core.subject.SubjectTypeEnum;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;
import java.util.List;

import static cn.enlev.ainb.framework.common.util.collection.CollectionUtils.convertList;
import static cn.enlev.ainb.framework.test.core.util.AssertUtils.assertServiceException;
import static cn.enlev.ainb.module.system.enums.ErrorCodeConstants.GRANT_BATCH_KEY_DUPLICATE;
import static cn.enlev.ainb.module.system.enums.ErrorCodeConstants.GRANT_DUPLICATE_CONFLICT;
import static cn.enlev.ainb.module.system.enums.ErrorCodeConstants.GRANT_NOT_EXISTS;
import static cn.enlev.ainb.module.system.enums.ErrorCodeConstants.GRANT_UPDATE_ID_REQUIRED;
import static cn.enlev.ainb.module.system.enums.ErrorCodeConstants.RESOURCE_NOT_EXISTS;
import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link GrantAdminServiceImpl} 的 DB 集成测试（真实 H2，模板见 {@code PermissionServiceTest}）
 *
 * <h3>这些用例钉的是「批量添加」引入的三件事</h3>
 *
 * <ol>
 *   <li><b>upsert 判键正确</b>：同键覆盖而不是再插一行。判键的六元组里有三个可空列，
 *       {@code LambdaQueryWrapper.eq(field, null)} 会生成恒假的 {@code field = null} ——
 *       写错的表现是「库里明明有重复行，却判成新增」，每提交一次多插一行，
 *       恰好批量制造 upsert 想消灭的那种重复。{@link #upsert_contextResourceId不同_不算同键}
 *       与 {@link #upsert_主体为SYSTEM时按code判键} 是这条陷阱的回归测试。</li>
 *   <li><b>收敛有守卫</b>：同键多行且内容一致才收敛；effect / ruleId 不一致必须整批拒绝。
 *       这是安全语义 —— 任意一行 DENY 一票否决所有 ALLOW，机器不能替管理员决定删哪一行。</li>
 *   <li><b>全有或全无</b>：资源不存在 / 批内键重复 / id 不存在，任意一条不过就一行都不落库。</li>
 * </ol>
 *
 * @author 走进新时代
 */
@Import(GrantAdminServiceImpl.class)
public class GrantAdminServiceTest extends BaseDbUnitTest {

    @Resource
    private GrantAdminServiceImpl grantAdminService;

    @Resource
    private GrantMapper grantMapper;
    @Resource
    private ResourceMapper resourceMapper;

    private static final Long ROLE_ID = 100L;

    // ========== upsert：插入与覆盖 ==========

    @Test
    @DisplayName("无同键行时插入新行，并把 id 返回给调用方")
    void upsert_无同键行_插入新行() {
        ResourceDO resource = insertResource();
        GrantSaveReqVO reqVO = buildRoleReqVO(resource.getId(), GrantActionEnum.VIEW.getCode(),
                GrantEffectEnum.ALLOW.getCode());

        Long id = grantAdminService.upsertGrant(reqVO);

        assertNotNull(id);
        List<GrantDO> grants = grantMapper.selectList();
        assertEquals(1, grants.size());
        GrantDO saved = grants.get(0);
        assertEquals(id, saved.getId());
        assertEquals(resource.getId(), saved.getResourceId());
        assertEquals(GrantActionEnum.VIEW.getCode(), saved.getAction());
        assertEquals(GrantEffectEnum.ALLOW.getCode(), saved.getEffect());
        assertEquals(SubjectTypeEnum.ROLE.name(), saved.getSubjectType());
        assertEquals(ROLE_ID, saved.getSubjectRefId());
    }

    @Test
    @DisplayName("🔴 同键行已存在时覆盖，且行数不增 —— 旧实现是裸 insert，点几次就插几行")
    void upsert_同键行已存在_覆盖且不新增() {
        ResourceDO resource = insertResource();
        GrantDO existing = insertGrant(resource.getId(), GrantActionEnum.VIEW.getCode(),
                GrantEffectEnum.ALLOW.getCode(), 5L, null);

        GrantSaveReqVO reqVO = buildRoleReqVO(resource.getId(), GrantActionEnum.VIEW.getCode(),
                GrantEffectEnum.DENY.getCode());
        // ruleId 刻意留空：覆盖必须能把既有的规则绑定清掉
        Long id = grantAdminService.upsertGrant(reqVO);

        List<GrantDO> grants = grantMapper.selectList();
        assertEquals(1, grants.size(), "同键覆盖不该插出第二行");
        assertEquals(existing.getId(), id, "返回的应是被覆盖那个存活行的 id");
        assertEquals(existing.getId(), grants.get(0).getId());
        assertEquals(GrantEffectEnum.DENY.getCode(), grants.get(0).getEffect());
        // ruleId 被清成 null，靠的是 GrantDO 上那条 @TableField(updateStrategy = ALWAYS)：
        // updateById 默认跳过 null 字段，没有它这一步会静默不生效
        assertNull(grants.get(0).getRuleId(), "覆盖必须能把 ruleId 清空");
    }

    @Test
    @DisplayName("命中已过期的同键行时覆盖它，而不是在旁边插第二行")
    void upsert_命中过期行_覆盖而非另插() {
        ResourceDO resource = insertResource();
        GrantDO expired = insertGrant(resource.getId(), GrantActionEnum.VIEW.getCode(),
                GrantEffectEnum.ALLOW.getCode(), null, LocalDateTime.now().minusDays(1));

        GrantSaveReqVO reqVO = buildRoleReqVO(resource.getId(), GrantActionEnum.VIEW.getCode(),
                GrantEffectEnum.ALLOW.getCode());
        grantAdminService.upsertGrant(reqVO);

        List<GrantDO> grants = grantMapper.selectList();
        assertEquals(1, grants.size(), "判键查询刻意不过滤 expireTime：续期是常规操作");
        assertEquals(expired.getId(), grants.get(0).getId());
        assertNull(grants.get(0).getExpireTime(), "续期后到期时间应被提交的 null 覆盖");
    }

    @Test
    @DisplayName("🔴 contextResourceId 不同就不算同键 —— eq(field, null) 恒假陷阱的回归测试")
    void upsert_contextResourceId不同_不算同键() {
        ResourceDO resource = insertResource();
        ResourceDO context = insertResource();
        // 既有行是全局授权（context_resource_id 为 NULL）
        insertGrant(resource.getId(), GrantActionEnum.VIEW.getCode(),
                GrantEffectEnum.ALLOW.getCode(), null, null);

        GrantSaveReqVO reqVO = buildRoleReqVO(resource.getId(), GrantActionEnum.VIEW.getCode(),
                GrantEffectEnum.ALLOW.getCode());
        reqVO.setContextResourceId(context.getId());
        grantAdminService.upsertGrant(reqVO);

        // 上下文不同 = 两条独立授权，必须都在。若判键时把 NULL 上下文写成
        // eq(contextResourceId, null)，WHERE 恒假、既有行永远命中不到，
        // 表现恰好也是「两行」，所以这里反过来验：同上下文时必须命中
        assertEquals(2, grantMapper.selectList().size());
    }

    @Test
    @DisplayName("🔴 主体为 SYSTEM（无 refId、用 code 寻址）时按 code 判键")
    void upsert_主体为SYSTEM时按code判键() {
        ResourceDO resource = insertResource();
        GrantDO existing = new GrantDO();
        existing.setSubjectType(SubjectTypeEnum.SYSTEM.name());
        // subjectRefId 留 null：SYSTEM 主体没有业务表主键，
        // 判键查询必须走 isNull(subject_ref_id) 分支才命中得到它
        existing.setSubjectCode("bpm-engine");
        existing.setResourceId(resource.getId());
        existing.setAction(GrantActionEnum.VIEW.getCode());
        existing.setEffect(GrantEffectEnum.ALLOW.getCode());
        grantMapper.insert(existing);

        GrantSaveReqVO reqVO = new GrantSaveReqVO();
        reqVO.setSubjectType(SubjectTypeEnum.SYSTEM.name());
        reqVO.setSubjectCode("bpm-engine");
        reqVO.setResourceId(resource.getId());
        reqVO.setAction(GrantActionEnum.VIEW.getCode());
        reqVO.setEffect(GrantEffectEnum.DENY.getCode());
        Long id = grantAdminService.upsertGrant(reqVO);

        assertEquals(1, grantMapper.selectList().size(), "SYSTEM 主体的同键行必须命中，不能另插一行");
        assertEquals(existing.getId(), id);
        assertEquals(GrantEffectEnum.DENY.getCode(), grantMapper.selectById(id).getEffect());
    }

    @Test
    @DisplayName("⚠️ subjectCode 的空串与 NULL 视为同键 —— 键的两侧必须同一归一化")
    void upsert_subjectCode空串与null视为同键() {
        ResourceDO resource = insertResource();
        GrantDO existing = new GrantDO();
        existing.setSubjectType(SubjectTypeEnum.SYSTEM.name());
        existing.setSubjectCode(""); // 历史写入路径留下的空串
        existing.setResourceId(resource.getId());
        existing.setAction(GrantActionEnum.VIEW.getCode());
        existing.setEffect(GrantEffectEnum.ALLOW.getCode());
        grantMapper.insert(existing);

        GrantSaveReqVO reqVO = new GrantSaveReqVO();
        reqVO.setSubjectType(SubjectTypeEnum.SYSTEM.name());
        reqVO.setSubjectCode(null); // 新提交用 null
        reqVO.setResourceId(resource.getId());
        reqVO.setAction(GrantActionEnum.VIEW.getCode());
        reqVO.setEffect(GrantEffectEnum.DENY.getCode());
        grantAdminService.upsertGrant(reqVO);

        assertEquals(1, grantMapper.selectList().size(),
                "'' 与 null 不归一就会判成两个键，upsert 照样插出重复行");
    }

    @Test
    @DisplayName("主体不同就不算同键：另一个角色的同资源同动作授权各自独立")
    void upsert_主体不同_不算同键() {
        ResourceDO resource = insertResource();
        insertGrant(resource.getId(), GrantActionEnum.VIEW.getCode(),
                GrantEffectEnum.ALLOW.getCode(), null, null);

        GrantSaveReqVO reqVO = buildRoleReqVO(resource.getId(), GrantActionEnum.VIEW.getCode(),
                GrantEffectEnum.ALLOW.getCode());
        reqVO.setSubjectRefId(200L); // 另一个角色
        grantAdminService.upsertGrant(reqVO);

        assertEquals(2, grantMapper.selectList().size());
    }

    // ========== upsert：收敛与守卫 ==========

    @Test
    @DisplayName("同键多行且内容一致时收敛到 min(id)，其余软删")
    void upsert_同键多行且内容一致_收敛到minId() {
        ResourceDO resource = insertResource();
        // 库里实存的 15 组重复行就是这个形状：三份一模一样的拷贝
        GrantDO first = insertGrant(resource.getId(), GrantActionEnum.VIEW.getCode(),
                GrantEffectEnum.ALLOW.getCode(), null, null);
        GrantDO second = insertGrant(resource.getId(), GrantActionEnum.VIEW.getCode(),
                GrantEffectEnum.ALLOW.getCode(), null, null);
        GrantDO third = insertGrant(resource.getId(), GrantActionEnum.VIEW.getCode(),
                GrantEffectEnum.ALLOW.getCode(), null, null);

        GrantSaveReqVO reqVO = buildRoleReqVO(resource.getId(), GrantActionEnum.VIEW.getCode(),
                GrantEffectEnum.ALLOW.getCode());
        grantAdminService.createGrantList(List.of(reqVO));

        List<GrantDO> alive = grantMapper.selectList();
        assertEquals(1, alive.size());
        assertEquals(first.getId(), alive.get(0).getId(), "存活行应是 id 最小的那条");
        // 其余两行是软删（selectById 查不到），不是物理删除
        assertNull(grantMapper.selectById(second.getId()));
        assertNull(grantMapper.selectById(third.getId()));
    }

    @Test
    @DisplayName("🔴 同键多行但 effect 不一致时整批拒绝，一行都不动 —— 收敛等于替管理员决定删 ALLOW 还是 DENY")
    void upsert_同键多行但effect不一致_拒绝且一行不动() {
        ResourceDO resource = insertResource();
        GrantDO allow = insertGrant(resource.getId(), GrantActionEnum.VIEW.getCode(),
                GrantEffectEnum.ALLOW.getCode(), null, null);
        GrantDO deny = insertGrant(resource.getId(), GrantActionEnum.VIEW.getCode(),
                GrantEffectEnum.DENY.getCode(), null, null);

        GrantSaveReqVO reqVO = buildRoleReqVO(resource.getId(), GrantActionEnum.VIEW.getCode(),
                GrantEffectEnum.ALLOW.getCode());

        // 只断言错误码不断言文案：消息里带的是命中行的 id 列表，其顺序来自查询结果，
        // 钉它等于把「H2 恰好按插入序返回」这种实现细节写进测试
        ServiceException ex = assertThrows(ServiceException.class,
                () -> grantAdminService.createGrantList(List.of(reqVO)));
        assertEquals(GRANT_DUPLICATE_CONFLICT.getCode(), ex.getCode());

        List<GrantDO> alive = grantMapper.selectList();
        assertEquals(2, alive.size(), "拒绝时不能动任何一行");
        assertNotNull(grantMapper.selectById(allow.getId()));
        assertNotNull(grantMapper.selectById(deny.getId()));
        assertEquals(GrantEffectEnum.DENY.getCode(), grantMapper.selectById(deny.getId()).getEffect());
    }

    @Test
    @DisplayName("🔴 同键多行但 ruleId 不一致时同样拒绝 —— 删掉绑了规则的那行等于放大可见数据范围")
    void upsert_同键多行但ruleId不一致_拒绝() {
        ResourceDO resource = insertResource();
        insertGrant(resource.getId(), GrantActionEnum.VIEW.getCode(),
                GrantEffectEnum.ALLOW.getCode(), 5L, null);
        insertGrant(resource.getId(), GrantActionEnum.VIEW.getCode(),
                GrantEffectEnum.ALLOW.getCode(), null, null);

        GrantSaveReqVO reqVO = buildRoleReqVO(resource.getId(), GrantActionEnum.VIEW.getCode(),
                GrantEffectEnum.ALLOW.getCode());

        ServiceException ex = assertThrows(ServiceException.class,
                () -> grantAdminService.createGrantList(List.of(reqVO)));
        assertEquals(GRANT_DUPLICATE_CONFLICT.getCode(), ex.getCode());
        assertEquals(2, grantMapper.selectList().size());
    }

    @Test
    @DisplayName("同键多行且收敛成功时，convergedCount 把清掉的历史行数报给前端")
    void createList_返回收敛计数() {
        ResourceDO resource = insertResource();
        insertGrant(resource.getId(), GrantActionEnum.VIEW.getCode(),
                GrantEffectEnum.ALLOW.getCode(), null, null);
        insertGrant(resource.getId(), GrantActionEnum.VIEW.getCode(),
                GrantEffectEnum.ALLOW.getCode(), null, null);
        insertGrant(resource.getId(), GrantActionEnum.VIEW.getCode(),
                GrantEffectEnum.ALLOW.getCode(), null, null);

        GrantSaveReqVO reqVO = buildRoleReqVO(resource.getId(), GrantActionEnum.VIEW.getCode(),
                GrantEffectEnum.DENY.getCode());
        GrantBatchSaveRespVO respVO = grantAdminService.createGrantList(List.of(reqVO));

        assertEquals(0, respVO.getInsertedCount());
        assertEquals(1, respVO.getUpdatedCount());
        assertEquals(2, respVO.getConvergedCount());
        assertEquals(GrantEffectEnum.DENY.getCode(),
                grantMapper.selectList().get(0).getEffect(), "存活行应被更新为提交值");
    }

    // ========== 批量：校验与计数 ==========

    @Test
    @DisplayName("批内任意一项的资源不存在时整批拒绝，一行都不落库")
    void createList_资源不存在_整批拒绝() {
        ResourceDO resource = insertResource();
        GrantSaveReqVO ok = buildRoleReqVO(resource.getId(), GrantActionEnum.VIEW.getCode(),
                GrantEffectEnum.ALLOW.getCode());
        GrantSaveReqVO bad = buildRoleReqVO(999999L, GrantActionEnum.VIEW.getCode(),
                GrantEffectEnum.ALLOW.getCode());

        assertServiceException(() -> grantAdminService.createGrantList(List.of(ok, bad)),
                RESOURCE_NOT_EXISTS);
        assertEquals(0, grantMapper.selectList().size(), "全有或全无：合法的那项也不能落库");
    }

    @Test
    @DisplayName("批内出现重复键时拒绝 —— 放过去会让后一项覆盖前一项，计数与落库行数对不上")
    void createList_批内键重复_拒绝() {
        ResourceDO resource = insertResource();
        GrantSaveReqVO first = buildRoleReqVO(resource.getId(), GrantActionEnum.VIEW.getCode(),
                GrantEffectEnum.ALLOW.getCode());
        GrantSaveReqVO second = buildRoleReqVO(resource.getId(), GrantActionEnum.VIEW.getCode(),
                GrantEffectEnum.DENY.getCode());

        assertServiceException(() -> grantAdminService.createGrantList(List.of(first, second)),
                GRANT_BATCH_KEY_DUPLICATE, resource.getId(), GrantActionEnum.VIEW.getCode());
        assertEquals(0, grantMapper.selectList().size());
    }

    @Test
    @DisplayName("一批里混合新增与覆盖时，三个计数各自正确")
    void createList_混合新增与覆盖_计数正确() {
        ResourceDO existing = insertResource();
        ResourceDO fresh1 = insertResource();
        ResourceDO fresh2 = insertResource();
        insertGrant(existing.getId(), GrantActionEnum.VIEW.getCode(),
                GrantEffectEnum.ALLOW.getCode(), null, null);

        GrantBatchSaveRespVO respVO = grantAdminService.createGrantList(List.of(
                buildRoleReqVO(existing.getId(), GrantActionEnum.VIEW.getCode(),
                        GrantEffectEnum.ALLOW.getCode()),
                buildRoleReqVO(fresh1.getId(), GrantActionEnum.VIEW.getCode(),
                        GrantEffectEnum.ALLOW.getCode()),
                buildRoleReqVO(fresh2.getId(), GrantActionEnum.VIEW.getCode(),
                        GrantEffectEnum.ALLOW.getCode())));

        assertEquals(2, respVO.getInsertedCount());
        assertEquals(1, respVO.getUpdatedCount());
        assertEquals(0, respVO.getConvergedCount());
        assertEquals(3, grantMapper.selectList().size());
    }

    @Test
    @DisplayName("🔴 提交项带着 id 也不会被当成主键写入 —— 新增路径不信任客户端传的 id")
    void createList_VO带id_插入时id被忽略() {
        ResourceDO resource = insertResource();
        GrantSaveReqVO reqVO = buildRoleReqVO(resource.getId(), GrantActionEnum.VIEW.getCode(),
                GrantEffectEnum.ALLOW.getCode());
        reqVO.setId(999999L); // GrantSaveReqVO 与 /update 共用，id 字段对新增无意义

        grantAdminService.createGrantList(List.of(reqVO));

        List<GrantDO> grants = grantMapper.selectList();
        assertEquals(1, grants.size());
        assertNotEquals(Long.valueOf(999999L), grants.get(0).getId(),
                "带着客户端 id 走 insert 会直插该主键，撞上既有行就是主键冲突");
    }

    @Test
    @DisplayName("同一资源配多个动作时各自成行 —— 笛卡尔展开在后端就是普通的批量 upsert")
    void createList_同资源多动作_各自成行() {
        ResourceDO resource = insertResource();

        GrantBatchSaveRespVO respVO = grantAdminService.createGrantList(List.of(
                buildRoleReqVO(resource.getId(), GrantActionEnum.VIEW.getCode(),
                        GrantEffectEnum.ALLOW.getCode()),
                buildRoleReqVO(resource.getId(), GrantActionEnum.CREATE.getCode(),
                        GrantEffectEnum.ALLOW.getCode()),
                buildRoleReqVO(resource.getId(), GrantActionEnum.DELETE.getCode(),
                        GrantEffectEnum.ALLOW.getCode())));

        assertEquals(3, respVO.getInsertedCount());
        List<String> actions = convertList(grantMapper.selectList(), GrantDO::getAction);
        assertEquals(3, actions.size());
        assertTrue(actions.containsAll(List.of(GrantActionEnum.VIEW.getCode(),
                GrantActionEnum.CREATE.getCode(), GrantActionEnum.DELETE.getCode())));
    }

    // ========== 批量删除 ==========

    @Test
    @DisplayName("批量删除：全部存在时软删")
    void deleteList_全部存在_软删() {
        ResourceDO resource = insertResource();
        GrantDO one = insertGrant(resource.getId(), GrantActionEnum.VIEW.getCode(),
                GrantEffectEnum.ALLOW.getCode(), null, null);
        GrantDO two = insertGrant(resource.getId(), GrantActionEnum.CREATE.getCode(),
                GrantEffectEnum.ALLOW.getCode(), null, null);

        grantAdminService.deleteGrantList(List.of(one.getId(), two.getId()));

        assertEquals(0, grantMapper.selectList().size());
        assertNull(grantMapper.selectById(one.getId()));
    }

    @Test
    @DisplayName("批量删除：含不存在的 id 时全有或全无，已勾选的其它行一行未动")
    void deleteList_含不存在id_全有或全无() {
        ResourceDO resource = insertResource();
        GrantDO alive = insertGrant(resource.getId(), GrantActionEnum.VIEW.getCode(),
                GrantEffectEnum.ALLOW.getCode(), null, null);

        assertServiceException(() -> grantAdminService.deleteGrantList(
                        List.of(alive.getId(), 999999L)), GRANT_NOT_EXISTS);

        assertEquals(1, grantMapper.selectList().size(),
                "静默部分成功会让操作者以为整批都删了");
    }

    @Test
    @DisplayName("批量删除：空列表幂等成功（勾选可能被翻页/重载竞态清空）")
    void deleteList_空列表_幂等成功() {
        ResourceDO resource = insertResource();
        insertGrant(resource.getId(), GrantActionEnum.VIEW.getCode(),
                GrantEffectEnum.ALLOW.getCode(), null, null);

        assertDoesNotThrow(() -> grantAdminService.deleteGrantList(List.of()));
        assertDoesNotThrow(() -> grantAdminService.deleteGrantList(null));
        assertEquals(1, grantMapper.selectList().size());
    }

    @Test
    @DisplayName("批量删除：重复 id 去重后不误判成「不存在」")
    void deleteList_重复id_distinct后不误判() {
        ResourceDO resource = insertResource();
        GrantDO one = insertGrant(resource.getId(), GrantActionEnum.VIEW.getCode(),
                GrantEffectEnum.ALLOW.getCode(), null, null);
        GrantDO two = insertGrant(resource.getId(), GrantActionEnum.CREATE.getCode(),
                GrantEffectEnum.ALLOW.getCode(), null, null);

        // 前端表格勾选可能把同一 id 传两次；不去重时 size 比对会把「重复」当成「缺行」
        grantAdminService.deleteGrantList(List.of(one.getId(), one.getId(), two.getId()));

        assertEquals(0, grantMapper.selectList().size());
    }

    // ========== 修改 ==========

    @Test
    @DisplayName("修改成功：只改 effect 与到期时间，其余字段不受影响")
    void update_成功() {
        ResourceDO resource = insertResource();
        GrantDO existing = insertGrant(resource.getId(), GrantActionEnum.VIEW.getCode(),
                GrantEffectEnum.ALLOW.getCode(), 7L, null);

        GrantSaveReqVO reqVO = buildRoleReqVO(resource.getId(), GrantActionEnum.VIEW.getCode(),
                GrantEffectEnum.DENY.getCode());
        reqVO.setId(existing.getId());
        reqVO.setRuleId(7L);
        reqVO.setExpireTime(LocalDateTime.now().plusDays(3));
        grantAdminService.updateGrant(reqVO);

        GrantDO updated = grantMapper.selectById(existing.getId());
        assertEquals(GrantEffectEnum.DENY.getCode(), updated.getEffect());
        assertEquals(Long.valueOf(7L), updated.getRuleId());
        assertNotNull(updated.getExpireTime());
    }

    @Test
    @DisplayName("修改：缺 id 走错误码，不再是旧实现那个没有码的 IllegalArgumentException")
    void update_缺id() {
        ResourceDO resource = insertResource();
        GrantSaveReqVO reqVO = buildRoleReqVO(resource.getId(), GrantActionEnum.VIEW.getCode(),
                GrantEffectEnum.ALLOW.getCode());

        assertServiceException(() -> grantAdminService.updateGrant(reqVO), GRANT_UPDATE_ID_REQUIRED);
    }

    @Test
    @DisplayName("修改：行不存在时报错，而不是旧实现那样静默返回 true")
    void update_行不存在() {
        ResourceDO resource = insertResource();
        GrantSaveReqVO reqVO = buildRoleReqVO(resource.getId(), GrantActionEnum.VIEW.getCode(),
                GrantEffectEnum.ALLOW.getCode());
        reqVO.setId(999999L);

        assertServiceException(() -> grantAdminService.updateGrant(reqVO), GRANT_NOT_EXISTS);
    }

    @Test
    @DisplayName("修改：资源不存在时拒绝，口径与新增一致")
    void update_资源不存在() {
        ResourceDO resource = insertResource();
        GrantDO existing = insertGrant(resource.getId(), GrantActionEnum.VIEW.getCode(),
                GrantEffectEnum.ALLOW.getCode(), null, null);

        GrantSaveReqVO reqVO = buildRoleReqVO(999999L, GrantActionEnum.VIEW.getCode(),
                GrantEffectEnum.ALLOW.getCode());
        reqVO.setId(existing.getId());

        assertServiceException(() -> grantAdminService.updateGrant(reqVO), RESOURCE_NOT_EXISTS);
    }

    // ========== 测试辅助 ==========

    private ResourceDO insertResource() {
        ResourceDO resource = new ResourceDO();
        resource.setPermission("test:grant:" + System.nanoTime());
        resource.setName(resource.getPermission());
        resource.setType(ResourceTypeEnum.MENU.getCode());
        resource.setParentId(0L);
        resource.setPath("/" + resource.getPermission());
        resource.setDepth((short) 1);
        resource.setSort(0);
        resource.setDefaultEffect(ResourceTypeEnum.MENU.defaultEffect());
        resource.setStatus(CommonStatusEnum.ENABLE.getStatus());
        resourceMapper.insert(resource);
        return resource;
    }

    /** 预置一行授权：ROLE 主体（refId = {@link #ROLE_ID}）+ 无上下文，即授权页批量提交的形状 */
    private GrantDO insertGrant(Long resourceId, String action, String effect,
                                Long ruleId, LocalDateTime expireTime) {
        GrantDO grant = new GrantDO();
        grant.setSubjectType(SubjectTypeEnum.ROLE.name());
        grant.setSubjectRefId(ROLE_ID);
        grant.setResourceId(resourceId);
        grant.setAction(action);
        grant.setEffect(effect);
        grant.setRuleId(ruleId);
        grant.setExpireTime(expireTime);
        grantMapper.insert(grant);
        return grant;
    }

    private GrantSaveReqVO buildRoleReqVO(Long resourceId, String action, String effect) {
        GrantSaveReqVO reqVO = new GrantSaveReqVO();
        reqVO.setSubjectType(SubjectTypeEnum.ROLE.name());
        reqVO.setSubjectRefId(ROLE_ID);
        reqVO.setResourceId(resourceId);
        reqVO.setAction(action);
        reqVO.setEffect(effect);
        return reqVO;
    }

}
