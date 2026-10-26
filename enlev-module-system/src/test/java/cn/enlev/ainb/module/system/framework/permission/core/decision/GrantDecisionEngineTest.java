package cn.enlev.ainb.module.system.framework.permission.core.decision;

import cn.enlev.ainb.module.system.dal.dataobject.permission.GrantDO;
import cn.enlev.ainb.module.system.dal.dataobject.permission.ResourceDO;
import cn.enlev.ainb.module.system.enums.permission.FieldModeEnum;
import cn.enlev.ainb.module.system.enums.permission.GrantActionEnum;import cn.enlev.ainb.module.system.framework.permission.core.subject.SubjectKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link GrantDecisionEngine} 的单元测试类
 *
 * 纯逻辑，不起 Spring。覆盖 v9 §7.1 第 5 步的五个优先级分支：
 *   全局 DENY > 上下文 DENY > 上下文 ALLOW > 全局 ALLOW > default_effect
 *
 * @author 走进新时代
 */
public class GrantDecisionEngineTest {

    private GrantDecisionEngine engine;

    private static final SubjectKey ROLE_1 = SubjectKey.ofRole(1L);
    private static final SubjectKey ROLE_2 = SubjectKey.ofRole(2L);
    private static final Long RES_A = 100L;
    private static final Long RES_B = 200L; // B 是 A 的父
    private static final Long CTX = 999L;   // 上下文资源 id

    @BeforeEach
    void setUp() {
        engine = new GrantDecisionEngine();
    }

    // ============================= 基础 =============================

    @Test
    public void testIsAllowed_空主体返回false() {
        assertFalse(engine.isAllowed(Set.of(), List.of(resource(RES_A, "ALLOW")),
                List.of(grant(ROLE_1, RES_A, GrantActionEnum.VIEW, "ALLOW", null)), "VIEW", null));
    }

    @Test
    public void testIsAllowed_空资源链返回false() {
        assertFalse(engine.isAllowed(Set.of(ROLE_1), List.of(),
                List.of(grant(ROLE_1, RES_A, GrantActionEnum.VIEW, "ALLOW", null)), "VIEW", null));
    }

    @Test
    public void testIsAllowed_无匹配授权行_回落default_effect_ALLOW() {
        ResourceDO res = resource(RES_A, "ALLOW");
        // 没有任何授权行，回落 default_effect
        assertTrue(engine.isAllowed(Set.of(ROLE_1), List.of(res), List.of(), "VIEW", null));
    }

    @Test
    public void testIsAllowed_无匹配授权行_回落default_effect_DENY() {
        ResourceDO res = resource(RES_A, "DENY");
        assertFalse(engine.isAllowed(Set.of(ROLE_1), List.of(res), List.of(), "VIEW", null));
    }

    // ============================= 全局 DENY 最高优先级 =============================

    @Test
    public void testIsAllowed_全局DENY_一票否决() {
        GrantDO deny = grant(ROLE_1, RES_A, GrantActionEnum.VIEW, "DENY", null);
        GrantDO allow = grant(ROLE_1, RES_A, GrantActionEnum.VIEW, "ALLOW", null);
        // DENY 一票否决，即使同时存在 ALLOW
        assertFalse(engine.isAllowed(Set.of(ROLE_1), List.of(resource(RES_A, "ALLOW")),
                List.of(deny, allow), "VIEW", null));
    }

    @Test
    public void testIsAllowed_全局DENY_来自不同角色也生效() {
        // ROLE_1 有 ALLOW，ROLE_2 有 DENY，但二者都在 subjects 里
        GrantDO allow = grant(ROLE_1, RES_A, GrantActionEnum.VIEW, "ALLOW", null);
        GrantDO deny  = grant(ROLE_2, RES_A, GrantActionEnum.VIEW, "DENY", null);
        assertFalse(engine.isAllowed(Set.of(ROLE_1, ROLE_2), List.of(resource(RES_A, "ALLOW")),
                List.of(allow, deny), "VIEW", null));
    }

    // ============================= 上下文优先级 =============================

    @Test
    public void testIsAllowed_上下文DENY_覆盖全局ALLOW() {
        GrantDO globalAllow = grant(ROLE_1, RES_A, GrantActionEnum.VIEW, "ALLOW", null);
        GrantDO ctxDeny = grant(ROLE_1, RES_A, GrantActionEnum.VIEW, "DENY", CTX);
        // 全局 ALLOW + 上下文 DENY = 拒绝（上下文 DENY 第二高优先级）
        assertFalse(engine.isAllowed(Set.of(ROLE_1), List.of(resource(RES_A, "ALLOW")),
                List.of(globalAllow, ctxDeny), "VIEW", CTX));
    }

    @Test
    public void testIsAllowed_上下文ALLOW_无全局DENY() {
        GrantDO ctxAllow = grant(ROLE_1, RES_A, GrantActionEnum.VIEW, "ALLOW", CTX);
        // 只有上下文 ALLOW，没有任何 DENY
        assertTrue(engine.isAllowed(Set.of(ROLE_1), List.of(resource(RES_A, "DENY")),
                List.of(ctxAllow), "VIEW", CTX));
    }

    @Test
    public void testIsAllowed_上下文不匹配则上下文行不参与决策() {
        GrantDO ctxAllow = grant(ROLE_1, RES_A, GrantActionEnum.VIEW, "ALLOW", CTX);
        Long otherCtx = 888L;
        // 传入的 contextId 和授权行里的 contextResourceId 不同 → 上下文行不参与
        // 资源 default_effect=DENY，全局也没 ALLOW → 拒绝
        assertFalse(engine.isAllowed(Set.of(ROLE_1), List.of(resource(RES_A, "DENY")),
                List.of(ctxAllow), "VIEW", otherCtx));
    }

    @Test
    public void testIsAllowed_全局DENY_上下文ALLOW_仍然拒绝() {
        // 全局 DENY 第一优先级，上下文 ALLOW 救不了
        GrantDO globalDeny = grant(ROLE_1, RES_A, GrantActionEnum.VIEW, "DENY", null);
        GrantDO ctxAllow = grant(ROLE_1, RES_A, GrantActionEnum.VIEW, "ALLOW", CTX);
        assertFalse(engine.isAllowed(Set.of(ROLE_1), List.of(resource(RES_A, "ALLOW")),
                List.of(globalDeny, ctxAllow), "VIEW", CTX));
    }

    // ============================= 通配符动作 =============================

    @Test
    @DisplayName("🔴 上下文授权【不能】影响无上下文的判定 —— 否则给节点配 ALLOW 会让别处全变不可见")
    public void testIsAllowed_上下文授权不影响全局回落() {
        // 只有一条【上下文限定】的 ALLOW，资源 default_effect = ALLOW
        GrantDO ctxAllow = grant(ROLE_1, RES_A, GrantActionEnum.VIEW, "ALLOW", CTX);

        // 在【无上下文】的场景下判定：这条授权与本次判定无关，应当完全不参与，
        // 从而让 default_effect=ALLOW 正常回落
        assertTrue(engine.isAllowed(Set.of(ROLE_1), List.of(resource(RES_A, "ALLOW")),
                List.of(ctxAllow), "VIEW", null),
                "让它参与的话：matched 不为空 → 跳过 default_effect 回落 → "
                        + "contextAllow 又被 contextId==null 挡住 → 最终判 DENY。"
                        + "表现是「给某个审批节点配了 ALLOW，这个字段在其它所有地方反而不可见了」，"
                        + "实测症状是发起流程时必填字段报『请填写』而用户明明填了");
    }

    @Test
    @DisplayName("上下文授权在【匹配的上下文】里照常生效")
    public void testIsAllowed_上下文授权在匹配上下文里生效() {
        GrantDO ctxAllow = grant(ROLE_1, RES_A, GrantActionEnum.VIEW, "ALLOW", CTX);

        assertTrue(engine.isAllowed(Set.of(ROLE_1), List.of(resource(RES_A, "DENY")),
                List.of(ctxAllow), "VIEW", CTX),
                "上下文内提权是它存在的意义，别连这个也一起过滤掉了");
    }

    @Test
    public void testIsAllowed_通配符动作() {
        GrantDO wildcard = grant(ROLE_1, RES_A, GrantActionEnum.WILDCARD, "ALLOW", null);
        assertTrue(engine.isAllowed(Set.of(ROLE_1), List.of(resource(RES_A, "DENY")),
                List.of(wildcard), "VIEW", null));
        assertTrue(engine.isAllowed(Set.of(ROLE_1), List.of(resource(RES_A, "DENY")),
                List.of(wildcard), "DELETE", null));
    }

    @Test
    public void testIsAllowed_动作不匹配() {
        GrantDO viewAllow = grant(ROLE_1, RES_A, GrantActionEnum.VIEW, "ALLOW", null);
        // 有 VIEW 但没有 DELETE
        assertFalse(engine.isAllowed(Set.of(ROLE_1), List.of(resource(RES_A, "DENY")),
                List.of(viewAllow), "DELETE", null));
    }

    // ============================= 资源链（祖先继承）=============================

    @Test
    public void testIsAllowed_授权在父资源上也生效() {
        // RES_B 是 RES_A 的父，授权在 RES_B 上
        GrantDO parentAllow = grant(ROLE_1, RES_B, GrantActionEnum.VIEW, "ALLOW", null);
        // chain 里 RES_A 在前（更具体），RES_B 在后（祖先）
        List<ResourceDO> chain = List.of(resource(RES_A, "DENY"), resource(RES_B, "DENY"));
        assertTrue(engine.isAllowed(Set.of(ROLE_1), chain, List.of(parentAllow), "VIEW", null));
    }

    @Test
    public void testIsAllowed_子资源DENY覆盖父资源ALLOW() {
        // 子 DENY 优先（因为 DENY 是全局最高优先级，不分层级）
        GrantDO childDeny  = grant(ROLE_1, RES_A, GrantActionEnum.VIEW, "DENY",  null);
        GrantDO parentAllow = grant(ROLE_1, RES_B, GrantActionEnum.VIEW, "ALLOW", null);
        List<ResourceDO> chain = List.of(resource(RES_A, "ALLOW"), resource(RES_B, "ALLOW"));
        assertFalse(engine.isAllowed(Set.of(ROLE_1), chain, List.of(childDeny, parentAllow), "VIEW", null));
    }

    // ============================= 过期 =============================

    @Test
    public void testIsAllowed_过期授权不生效() {
        GrantDO expired = grant(ROLE_1, RES_A, GrantActionEnum.VIEW, "ALLOW", null);
        expired.setExpireTime(LocalDateTime.now().minusSeconds(1)); // 已过期
        // 过期后回落 default_effect=DENY
        assertFalse(engine.isAllowed(Set.of(ROLE_1), List.of(resource(RES_A, "DENY")),
                List.of(expired), "VIEW", null));
    }

    @Test
    public void testIsAllowed_过期的DENY也失效_临时封禁到期自动解封() {
        // 🔴 这是一个刻意的语义决定，别当成 bug 改掉。
        //
        // 过期过滤（GrantDO.isActive）对 ALLOW 和 DENY 一视同仁，于是
        //「给某人配一条 3 天后到期的 DENY」= 临时封禁，到期自动解封。
        //
        // 另一种设计是「DENY 永不过期」，但那会让「临时收回权限」变成
        // 一件必须人工记得去撤销的事 —— 而没人会记得，结果是封禁永久化，
        // 且当事人只会说「我突然就没权限了」，查不到任何时间线索。
        GrantDO expiredDeny = grant(ROLE_1, RES_A, GrantActionEnum.VIEW, "DENY", null);
        expiredDeny.setExpireTime(LocalDateTime.now().minusSeconds(1));
        GrantDO allow = grant(ROLE_1, RES_A, GrantActionEnum.VIEW, "ALLOW", null);

        assertTrue(engine.isAllowed(Set.of(ROLE_1), List.of(resource(RES_A, "DENY")),
                List.of(expiredDeny, allow), "VIEW", null),
                "过期的 DENY 还压着 ALLOW 的话，临时封禁就成了永久封禁");
    }

    @Test
    public void testIsAllowed_未过期的DENY照常压过ALLOW() {
        GrantDO liveDeny = grant(ROLE_1, RES_A, GrantActionEnum.VIEW, "DENY", null);
        liveDeny.setExpireTime(LocalDateTime.now().plusDays(1));
        GrantDO allow = grant(ROLE_1, RES_A, GrantActionEnum.VIEW, "ALLOW", null);

        assertFalse(engine.isAllowed(Set.of(ROLE_1), List.of(resource(RES_A, "DENY")),
                List.of(liveDeny, allow), "VIEW", null));
    }

    @Test
    public void testIsAllowed_未过期授权生效() {
        GrantDO valid = grant(ROLE_1, RES_A, GrantActionEnum.VIEW, "ALLOW", null);
        valid.setExpireTime(LocalDateTime.now().plusDays(1));
        assertTrue(engine.isAllowed(Set.of(ROLE_1), List.of(resource(RES_A, "DENY")),
                List.of(valid), "VIEW", null));
    }

    // ============================= 字段模式三层合并（v8 §3.2 三条必测校验）=============================

    /**
     * 校验 1：角色 READONLY（全局）+ 节点基线 EDITABLE → 最终 READONLY
     * 意义：角色定下的字段红线不能被上下文突破
     *
     * 数据：
     *   全局行：ROLE_1 → READONLY（设 ceiling=READONLY）
     *   上下文基线行：subject=null, context=CTX → EDITABLE（上下文基线，value=EDITABLE）
     *   最终 = min(READONLY, EDITABLE) = READONLY
     */
    @Test
    public void testResolveFieldMode_校验1_角色红线不被节点突破() {
        GrantDO globalReadonly = grantWithFieldMode(ROLE_1, RES_A, null, "READONLY");
        // 上下文基线：subject 不匹配任何主体（null 主体用 ROLE_2 模拟，且不在 subjects 里）
        GrantDO ctxBaseline = grantWithFieldMode(ROLE_2, RES_A, CTX, "EDITABLE");
        FieldModeEnum result = engine.resolveFieldMode(
                Set.of(ROLE_1),                    // ROLE_2 不在闭包里 → 作为上下文基线
                List.of(resource(RES_A, "ALLOW")),
                List.of(globalReadonly, ctxBaseline),
                CTX,
                FieldModeEnum.EDITABLE);
        assertEquals(FieldModeEnum.READONLY, result,
                "角色全局 READONLY 是红线，上下文基线 EDITABLE 不能突破它");
    }

    /**
     * 校验 2：节点基线 READONLY + 主管在该节点 EDITABLE → 最终 EDITABLE
     * 意义：上下文授权可以在红线允许的范围内提权（此例全局没有 READONLY 红线）
     *
     * 数据：
     *   上下文基线行：subject=null, context=CTX → READONLY（节点默认只读）
     *   上下文主体行：ROLE_1, context=CTX → EDITABLE（主管可编辑）
     *   ceiling = EDITABLE（默认，无全局限制）
     *   value = 上下文 subject max = EDITABLE（优先于上下文基线 READONLY）
     *   最终 = min(EDITABLE, EDITABLE) = EDITABLE
     */
    @Test
    public void testResolveFieldMode_校验2_上下文可以提权() {
        // 上下文基线：用不在闭包里的 ROLE_2 模拟「subject=null」
        GrantDO ctxBaseline = grantWithFieldMode(ROLE_2, RES_A, CTX, "READONLY");
        // 主管在该节点可 EDITABLE
        GrantDO ctxEditable = grantWithFieldMode(ROLE_1, RES_A, CTX, "EDITABLE");
        FieldModeEnum result = engine.resolveFieldMode(
                Set.of(ROLE_1),
                List.of(resource(RES_A, "ALLOW")),
                List.of(ctxBaseline, ctxEditable),
                CTX,
                FieldModeEnum.EDITABLE);
        assertEquals(FieldModeEnum.EDITABLE, result,
                "上下文 subject EDITABLE 优先于上下文基线 READONLY，且 ceiling 无限制");
    }

    /**
     * 校验 3：角色 EDITABLE（全局）+ 节点基线 READONLY → 最终 READONLY
     * 意义：审批节点自己收紧，不受全局 EDITABLE 覆盖
     *
     * 数据：
     *   全局行：ROLE_1 → EDITABLE（ceiling = EDITABLE，不设红线）
     *   上下文基线行：subject=null, context=CTX → READONLY（节点收紧）
     *   没有上下文 subject 行 → value 回落到上下文基线 READONLY
     *   最终 = min(EDITABLE, READONLY) = READONLY
     */
    @Test
    public void testResolveFieldMode_校验3_审批节点收紧生效() {
        GrantDO globalEditable = grantWithFieldMode(ROLE_1, RES_A, null, "EDITABLE");
        // 上下文基线：用不在闭包里的 ROLE_2 模拟「subject=null」
        GrantDO ctxBaseline = grantWithFieldMode(ROLE_2, RES_A, CTX, "READONLY");
        FieldModeEnum result = engine.resolveFieldMode(
                Set.of(ROLE_1),
                List.of(resource(RES_A, "ALLOW")),
                List.of(globalEditable, ctxBaseline),
                CTX,
                FieldModeEnum.EDITABLE);
        assertEquals(FieldModeEnum.READONLY, result,
                "ceiling=EDITABLE(不限制), value=上下文基线READONLY → min=READONLY");
    }

    @Test
    public void testResolveFieldMode_无任何授权行_回落default() {
        FieldModeEnum result = engine.resolveFieldMode(
                Set.of(ROLE_1), List.of(resource(RES_A, "ALLOW")),
                List.of(), null, FieldModeEnum.READONLY);
        assertEquals(FieldModeEnum.READONLY, result);
    }

    @Test
    public void testResolveFieldMode_多角色取最严格ceiling() {
        // ROLE_1 READONLY，ROLE_2 HIDDEN → ceiling = min = HIDDEN
        GrantDO r1 = grantWithFieldMode(ROLE_1, RES_A, null, "READONLY");
        GrantDO r2 = grantWithFieldMode(ROLE_2, RES_A, null, "HIDDEN");
        FieldModeEnum result = engine.resolveFieldMode(
                Set.of(ROLE_1, ROLE_2), List.of(resource(RES_A, "ALLOW")),
                List.of(r1, r2), null, FieldModeEnum.EDITABLE);
        assertEquals(FieldModeEnum.HIDDEN, result,
                "多角色 ceiling 取最严格，HIDDEN 覆盖 READONLY");
    }

    // ============================= 工具方法 =============================

    private ResourceDO resource(Long id, String defaultEffect) {
        ResourceDO r = new ResourceDO();
        r.setId(id);
        r.setDefaultEffect(defaultEffect);
        r.setStatus(0);
        return r;
    }

    private GrantDO grant(SubjectKey subject, Long resourceId, GrantActionEnum action,
                           String effect, Long contextId) {
        GrantDO g = new GrantDO();
        g.setSubjectType(subject.type().name());
        g.setSubjectRefId(subject.refId());
        g.setSubjectCode(subject.code());
        g.setResourceId(resourceId);
        g.setContextResourceId(contextId);
        g.setAction(action.getCode());
        g.setEffect(effect);
        return g;
    }

    private GrantDO grantWithFieldMode(SubjectKey subject, Long resourceId, Long contextId, String fieldMode) {
        GrantDO g = new GrantDO();
        g.setSubjectType(subject.type().name());
        g.setSubjectRefId(subject.refId());
        g.setSubjectCode(subject.code());
        g.setResourceId(resourceId);
        g.setContextResourceId(contextId);
        g.setAction(GrantActionEnum.VIEW.getCode());
        g.setEffect("ALLOW");
        g.setFieldMode(fieldMode);
        return g;
    }

}
