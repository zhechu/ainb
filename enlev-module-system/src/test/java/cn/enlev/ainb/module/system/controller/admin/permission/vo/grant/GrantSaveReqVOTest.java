package cn.enlev.ainb.module.system.controller.admin.permission.vo.grant;

import cn.enlev.ainb.module.system.enums.permission.FieldModeEnum;
import cn.enlev.ainb.module.system.enums.permission.GrantActionEnum;
import cn.enlev.ainb.module.system.enums.permission.GrantEffectEnum;
import cn.enlev.ainb.module.system.framework.permission.core.subject.SubjectTypeEnum;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link GrantSaveReqVO} 的单元测试：钉 action / effect / fieldMode / subjectType 四条 {@code @InEnum}，
 * 以及 subjectType ↔ subjectRefId/subjectCode 的两条 {@code @AssertTrue} 配对约束
 *
 * <h3>这四条 {@code @InEnum} 拦的是同一件事</h3>
 *
 * 四个字段的读取端都是 {@code String.equals} 或 {@code parse} 返回 null：
 * {@code GrantDecisionEngine} 靠 {@code GrantDO#actionMatches} 与
 * {@code "DENY".equals(grant.getEffect())} 判定，字段模式靠 {@code FieldModeEnum#parse}，
 * 主体靠 {@code SubjectTypeEnum#parse}。
 * 于是枚举外的值存进去之后<b>既不报错也不生效</b> ——
 * 如果那是一行 DENY，配的人以为封住了，其实什么都没封；
 * 如果那是 fieldMode，整行授权会被字段模式判定直接忽略；
 * 如果那是 subjectType，主体闭包永远算不出它，<b>整行是死的</b>。
 * action 侧库里真出现过 {@code EXEC}（4 行，见 {@code xpy-2026-10-12-grant-action-enum-cleanup.sql}）；
 * fieldMode 侧最常见的是 {@code VISIBLE}（前端那个下拉一度真的写着它，
 * 页面提示文案还教人这么填，已在资源明细面板一侧修掉）；
 * effect 与 subjectType 侧实测只有合法值、暂无脏数据，那两条是防患于未然。
 *
 * <h3>为什么值得单独测</h3>
 *
 * create 与 update 共用这个 VO，注解加一处就覆盖两个入口；但「加一处」这个论断
 * 依赖 Controller 两个方法都带 {@code @Valid}，光看代码容易看漏。这里从校验器
 * 正面把行为钉住，比读注解可靠。
 *
 * effect / fieldMode / subjectType 那几个用例还额外钉住一件事：这三个枚举的全集
 * 就是断言里写的那几项 —— main 代码里仍有二十余处 {@code ALLOW}/{@code DENY}
 * 的字符串字面量（决策引擎、套餐同步等），刻意没有随枚举收敛。
 * 枚举一旦被人加了第三项，那些字面量口径立刻分裂，这条断言就是分裂时最先响的警报。
 * fieldMode 那条钉住的是「合法值里没有 VISIBLE」，subjectType 那条钉住的是
 * 「七种类型一个都不少，POST 也在里面」——它恰好是原先 {@code @Schema} 描述里漏写的那一个
 *
 * <h3>最后那组配对用例钉的是「静默失效」的另一个来源</h3>
 *
 * {@code @InEnum} 管「值在枚举外」，配对管「值缺了」，两者殊途同归：
 * {@code GrantMapper#buildSubjectWrapper} 对 ref 型主体拼的是
 * {@code subject_type = ? AND subject_ref_id = ?}，refId 为 null 时这个条件恒假，
 * 主体闭包一次也捞不到那行授权 —— 建得出来、列表里看得见、永远不生效。
 * 规则本身不是新发明的：{@code SubjectKey} 的紧凑构造器与
 * {@code GrantAdminController#resolveSubject}（读侧）早就在判同一件事，
 * 补的只是<b>写</b>侧那个一直没人把守的口子。
 *
 * <p>⚠️ 这组用例<b>只</b>校验那两个派生属性，不整对象校验：
 * 整对象会把 {@code resourceId} / {@code action} / {@code effect} 的必填违规一起拖进来。
 * 但末尾三条用例是<b>整对象</b>校验的，它们要证的是另一件事 ——
 * 这两条注解在真实请求里确实够得着。少了它们，光靠 {@code validateProperty}
 * 无法区分「约束放行」与「属性名写错、根本没校验到那个属性」
 *
 * @author 走进新时代
 */
public class GrantSaveReqVOTest {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    /**
     * 只校验 action 这一个属性：这个 VO 上还有别的必填项，
     * 整对象校验会让无关字段的违规混进断言里
     */
    private Set<ConstraintViolation<GrantSaveReqVO>> validateAction(String action) {
        GrantSaveReqVO vo = new GrantSaveReqVO();
        vo.setAction(action);
        return VALIDATOR.validateProperty(vo, "action");
    }

    /** 同上，只校验 effect 这一个属性 */
    private Set<ConstraintViolation<GrantSaveReqVO>> validateEffect(String effect) {
        GrantSaveReqVO vo = new GrantSaveReqVO();
        vo.setEffect(effect);
        return VALIDATOR.validateProperty(vo, "effect");
    }

    // ==================== action ====================

    @Test
    @DisplayName("枚举里的 13 个动作码全部放行，含通配符 '*'")
    void testAllEnumCodesAccepted() {
        for (GrantActionEnum action : GrantActionEnum.values()) {
            assertTrue(validateAction(action.getCode()).isEmpty(),
                    "枚举内的动作码不该被拦：" + action.getCode());
        }
        // 与 UI 的动作词表同源：前端 GRANT_ACTION_OPTIONS 也是 13 项
        assertEquals(13, GrantActionEnum.values().length);
    }

    @Test
    @DisplayName("🔴 库里真出现过的 EXEC 被拦下 —— 它存进去既不报错也不生效")
    void testOutOfEnumRejected() {
        Set<ConstraintViolation<GrantSaveReqVO>> violations = validateAction("EXEC");

        assertEquals(1, violations.size());
        // {value} 由 InEnumValidator 渲染成枚举全集，不是留着占位符
        String message = violations.iterator().next().getMessage();
        assertFalse(message.contains("{value}"), "占位符没被渲染：" + message);
        assertTrue(message.contains("VIEW_ALL"), "提示里应当列出可选值：" + message);
    }

    @Test
    @DisplayName("⚠️ 大小写敏感是刻意的 —— 判据 String.equals 也区分大小写")
    void testLowerCaseRejected() {
        assertFalse(validateAction("view").isEmpty(),
                "小写 view 在 GrantDO#actionMatches 里同样匹配不上，"
                        + "校验若放它过去，就又造出一行不生效的授权");
    }

    @Test
    @DisplayName("空值交给 @NotBlank，不是 @InEnum")
    void testNullIsNotBlankConcern() {
        // InEnumValidator 对 null 直接放行，为的是能与 @NotBlank 叠加使用。
        // 这里断言 null 仍然被拦（拦它的是 @NotBlank），免得有人以为漏了校验
        assertEquals(1, validateAction(null).size());
        // 空串两条注解都会拦（@NotBlank 拦空、@InEnum 拦枚举外），所以只断言「被拦」，
        // 不断言条数 —— 钉条数等于把「两个校验器都恰好报了一次」这种实现细节写进测试
        assertFalse(validateAction("").isEmpty());
    }

    // ==================== effect ====================

    @Test
    @DisplayName("effect 枚举全集就是 ALLOW/DENY —— main 代码里二十余处字面量靠这条与枚举对齐")
    void testEffectEnumCodesAccepted() {
        for (GrantEffectEnum effect : GrantEffectEnum.values()) {
            assertTrue(validateEffect(effect.getCode()).isEmpty(),
                    "枚举内的效果值不该被拦：" + effect.getCode());
        }
        assertEquals(2, GrantEffectEnum.values().length);
    }

    @Test
    @DisplayName("🔴 枚举外的 effect 被拦下，且提示列出 ALLOW/DENY")
    void testEffectOutOfEnumRejected() {
        Set<ConstraintViolation<GrantSaveReqVO>> violations = validateEffect("PERMIT");

        assertEquals(1, violations.size());
        String message = violations.iterator().next().getMessage();
        assertFalse(message.contains("{value}"), "占位符没被渲染：" + message);
        assertTrue(message.contains("ALLOW"), "提示里应当列出可选值：" + message);
        assertTrue(message.contains("DENY"), "提示里应当列出可选值：" + message);
    }

    @Test
    @DisplayName("⚠️ effect 同样大小写敏感 —— 判据是 \"DENY\".equals(...)")
    void testEffectLowerCaseRejected() {
        assertFalse(validateEffect("deny").isEmpty(),
                "小写 deny 在 GrantDecisionEngine 里同样匹配不上，"
                        + "校验若放它过去，配的人以为封住了、其实什么都没封");
    }

    @Test
    @DisplayName("effect 空值交给 @NotBlank，不是 @InEnum")
    void testEffectNullIsNotBlankConcern() {
        assertEquals(1, validateEffect(null).size());
        assertFalse(validateEffect("").isEmpty());
    }

    // ==================== fieldMode ====================

    /**
     * 同上，只校验 fieldMode 这一个属性
     *
     * ⚠️ 与 action / effect 的关键差别：fieldMode <b>没有</b> {@code @NotBlank} ——
     * 「不限制字段」是合法配置，用 null 表达
     */
    private Set<ConstraintViolation<GrantSaveReqVO>> validateFieldMode(String fieldMode) {
        GrantSaveReqVO vo = new GrantSaveReqVO();
        vo.setFieldMode(fieldMode);
        return VALIDATOR.validateProperty(vo, "fieldMode");
    }

    @Test
    @DisplayName("fieldMode 枚举全集就是 EDITABLE/READONLY/HIDDEN —— 没有 VISIBLE 这一项")
    void testFieldModeEnumCodesAccepted() {
        for (FieldModeEnum mode : FieldModeEnum.values()) {
            assertTrue(validateFieldMode(mode.getCode()).isEmpty(),
                    "枚举内的字段模式不该被拦：" + mode.getCode());
        }
        assertEquals(3, FieldModeEnum.values().length);
    }

    @Test
    @DisplayName("🔴 VISIBLE 被拦下 —— 它会让整行授权被引擎静默忽略，而前端下拉一度真的写着它")
    void testFieldModeVisibleRejected() {
        Set<ConstraintViolation<GrantSaveReqVO>> violations = validateFieldMode("VISIBLE");

        assertEquals(1, violations.size());
        String message = violations.iterator().next().getMessage();
        assertFalse(message.contains("{value}"), "占位符没被渲染：" + message);
        assertTrue(message.contains("EDITABLE"), "提示里应当列出可选值：" + message);
        assertTrue(message.contains("HIDDEN"), "提示里应当列出可选值：" + message);
        assertFalse(message.contains("VISIBLE"), "提示里不该把非法值当可选值列出来：" + message);
    }

    @Test
    @DisplayName("null 必须放行 —— 它表达「不限制字段」，是这个字段最常见的取值")
    void testFieldModeNullAllowed() {
        assertTrue(validateFieldMode(null).isEmpty(),
                "fieldMode 没有 @NotBlank，null 是合法配置；若这里开始拦，"
                        + "所有「不配字段模式」的授权都会保存失败");
    }

    @Test
    @DisplayName("⚠️ 空串被拦是刻意的：「不限制」请传 null，别传 ''")
    void testFieldModeBlankRejected() {
        // 空串过去会被原样存进库，再由 FieldModeEnum#parse 判 blank 返回 null ——
        // 也就是「碰巧」等效于不限制。现在把它拦下，是为了让契约只有一种写法：
        // 前端 el-select 清空走的是 valueOnClear 默认值 undefined（JSON 里直接不出现这个键），
        // 所以正常操作路径压根到不了这一支
        assertEquals(1, validateFieldMode("").size());
    }

    @Test
    @DisplayName("⚠️ fieldMode 同样大小写敏感 —— 判据是 FieldModeEnum#parse 里的 String.equals")
    void testFieldModeLowerCaseRejected() {
        assertFalse(validateFieldMode("readonly").isEmpty(),
                "小写 readonly 在 parse 里同样匹配不上，校验若放它过去，"
                        + "就又造出一行「配了字段模式但整行不生效」的授权");
    }

    // ==================== subjectType ====================

    /** 同上，只校验 subjectType 这一个属性 */
    private Set<ConstraintViolation<GrantSaveReqVO>> validateSubjectType(String subjectType) {
        GrantSaveReqVO vo = new GrantSaveReqVO();
        vo.setSubjectType(subjectType);
        return VALIDATOR.validateProperty(vo, "subjectType");
    }

    @Test
    @DisplayName("七种主体类型全部放行 —— 含原先 @Schema 描述里漏写的 POST")
    void testSubjectTypeEnumCodesAccepted() {
        for (SubjectTypeEnum type : SubjectTypeEnum.values()) {
            assertTrue(validateSubjectType(type.name()).isEmpty(),
                    "枚举内的主体类型不该被拦：" + type.name());
        }
        assertEquals(7, SubjectTypeEnum.values().length);
        // POST 是那条漏写的：@Schema 原先写「ROLE/USER/DEPT/GROUP/SYSTEM/ALL」，
        // 照着它填的人不会知道岗位也能当授权主体
        assertTrue(validateSubjectType("POST").isEmpty());
    }

    @Test
    @DisplayName("🔴 枚举外的主体类型被拦下 —— 存进去的行主体闭包永远算不出来，整行是死的")
    void testSubjectTypeOutOfEnumRejected() {
        Set<ConstraintViolation<GrantSaveReqVO>> violations = validateSubjectType("ROLES");

        assertEquals(1, violations.size());
        String message = violations.iterator().next().getMessage();
        assertFalse(message.contains("{value}"), "占位符没被渲染：" + message);
        assertTrue(message.contains("ROLE"), "提示里应当列出可选值：" + message);
        assertTrue(message.contains("GROUP"), "提示里应当列出可选值：" + message);
        assertFalse(message.contains("ROLES"), "提示里不该把非法值当可选值列出来：" + message);
    }

    @Test
    @DisplayName("⚠️ 主体类型同样大小写敏感 —— 判据是 SubjectTypeEnum#parse 里的 name().equals")
    void testSubjectTypeLowerCaseRejected() {
        assertFalse(validateSubjectType("role").isEmpty(),
                "小写 role 在 parse 里返回 null，主体闭包算不出这个键，"
                        + "校验若放它过去，就造出一行「看得见却永远不生效」的授权");
    }

    @Test
    @DisplayName("subjectType 空值交给 @NotBlank，不是 @InEnum")
    void testSubjectTypeNullIsNotBlankConcern() {
        assertEquals(1, validateSubjectType(null).size());
        assertFalse(validateSubjectType("").isEmpty());
    }

    // ==================== 主体三件套的配对 ====================

    /**
     * 两个派生属性的名字由 getter 名决定（{@code isSubjectRefIdPaired} ⟹
     * {@code subjectRefIdPaired}），改 getter 名就要改这里
     */
    private static final String PROP_REF_PAIRED = "subjectRefIdPaired";
    private static final String PROP_CODE_PAIRED = "subjectCodePaired";

    /**
     * 只校验那两条配对约束
     *
     * ⚠️ 刻意不整对象校验：{@code resourceId} / {@code action} / {@code effect} 都是必填，
     * 整对象校验会把它们的违规混进断言里。整对象校验只有最后一条用例做，
     * 它要证的是另一件事（见该用例的注释）
     */
    private Set<ConstraintViolation<GrantSaveReqVO>> validatePairing(String subjectType,
                                                                    Long refId, String code) {
        GrantSaveReqVO vo = new GrantSaveReqVO();
        vo.setSubjectType(subjectType);
        vo.setSubjectRefId(refId);
        vo.setSubjectCode(code);
        Set<ConstraintViolation<GrantSaveReqVO>> violations = new HashSet<>();
        violations.addAll(VALIDATOR.validateProperty(vo, PROP_REF_PAIRED));
        violations.addAll(VALIDATOR.validateProperty(vo, PROP_CODE_PAIRED));
        return violations;
    }

    /** 一条各字段都合法的授权项，供整对象校验的用例改写其中一项 */
    private GrantSaveReqVO buildValid(String subjectType, Long refId, String code) {
        GrantSaveReqVO vo = new GrantSaveReqVO();
        vo.setSubjectType(subjectType);
        vo.setSubjectRefId(refId);
        vo.setSubjectCode(code);
        vo.setResourceId(1024L);
        vo.setAction(GrantActionEnum.VIEW.getCode());
        vo.setEffect(GrantEffectEnum.ALLOW.getCode());
        return vo;
    }

    @Test
    @DisplayName("七种类型恰好被 isRefBased 一分为二（5 ref + 2 code）—— 加第八种时必须显式选边")
    void testIsRefBasedPartitionsAllTypes() {
        int refBased = 0;
        for (SubjectTypeEnum type : SubjectTypeEnum.values()) {
            if (type.isRefBased()) {
                refBased++;
            }
        }
        // 这条断言是配对约束的地基：两条 @AssertTrue 全靠 isRefBased 分流，
        // 它一旦漂了，「必填 refId」与「必填 code」就会同时落到同一种类型上，
        // 那种类型将<b>无法提交</b> —— 比漏校验严重得多
        assertEquals(5, refBased, "ref 型应当是 USER/ROLE/DEPT/POST/GROUP 五种");
        assertFalse(SubjectTypeEnum.SYSTEM.isRefBased(), "SYSTEM 用 code 寻址");
        assertFalse(SubjectTypeEnum.ALL.isRefBased(), "ALL 用 code 寻址");
    }

    @Test
    @DisplayName("🔴 ref 型主体缺 subjectRefId 被拦下 —— 这一行闭包永远捞不到，是死的")
    void testRefBasedWithoutRefIdRejected() {
        for (SubjectTypeEnum type : SubjectTypeEnum.values()) {
            if (!type.isRefBased()) {
                continue;
            }
            Set<ConstraintViolation<GrantSaveReqVO>> violations = validatePairing(type.name(), null, null);

            assertEquals(1, violations.size(), type + " 缺 refId 应当恰好报一条：" + violations);
            ConstraintViolation<GrantSaveReqVO> violation = violations.iterator().next();
            assertEquals(PROP_REF_PAIRED, violation.getPropertyPath().toString());
            assertTrue(violation.getMessage().contains("subjectRefId"),
                    "提示里应当点出缺的是哪个字段：" + violation.getMessage());
            // 缺 refId 时不该顺带报「缺 code」—— 两条约束互斥，同时报会让人以为要填两个
            assertFalse(violation.getMessage().contains("subjectCode"), violation.getMessage());
        }
    }

    @Test
    @DisplayName("五种 ref 型主体带 refId 全部放行 —— 这是前端唯一在走的路径")
    void testRefBasedWithRefIdAccepted() {
        for (SubjectTypeEnum type : SubjectTypeEnum.values()) {
            if (!type.isRefBased()) {
                continue;
            }
            assertTrue(validatePairing(type.name(), 7L, null).isEmpty(),
                    type + " + refId 不该被拦");
        }
    }

    @Test
    @DisplayName("🔴 code 型主体缺 subjectCode 被拦下 —— null / 空串 / 纯空格三种写法都拦")
    void testCodeBasedWithoutCodeRejected() {
        for (SubjectTypeEnum type : SubjectTypeEnum.values()) {
            if (type.isRefBased()) {
                continue;
            }
            for (String blank : new String[]{null, "", "   "}) {
                Set<ConstraintViolation<GrantSaveReqVO>> violations =
                        validatePairing(type.name(), null, blank);

                assertEquals(1, violations.size(),
                        type + " + code=" + blank + " 应当恰好报一条：" + violations);
                ConstraintViolation<GrantSaveReqVO> violation = violations.iterator().next();
                assertEquals(PROP_CODE_PAIRED, violation.getPropertyPath().toString());
                assertTrue(violation.getMessage().contains("subjectCode"),
                        "提示里应当点出缺的是哪个字段：" + violation.getMessage());
            }
        }
        // 空串与纯空格必须一起拦：闭包查询拼的是 subject_code = ?，
        // 空串同样恒假、同样是死行。判据用 isNotBlank 与 SubjectKey / resolveSubject 两处权威一致
    }

    @Test
    @DisplayName("SYSTEM + 自定义 code、ALL + all-users 都放行（负对照：别把合法主体一起拦了）")
    void testCodeBasedWithCodeAccepted() {
        assertTrue(validatePairing("SYSTEM", null, "bpm-engine").isEmpty());
        // all-users 就是 SubjectKey#ALL_USERS_CODE 的值，库里 12 行 ALL 授权用的都是它。
        // 刻意不为了读这个常量去 import 框架层的寻址类，硬写一遍即可：
        // 值漂了的话上面 testIsRefBasedPartitionsAllTypes 与库里的存量行会先响
        assertTrue(validatePairing("ALL", null, "all-users").isEmpty());
    }

    @Test
    @DisplayName("⚠️ 类型名不合法时配对约束保持沉默 —— 报「类型不合法」的是 @InEnum，不是这两条")
    void testInvalidSubjectTypeDoesNotDoubleReport() {
        for (String bad : new String[]{null, "", "ROLES", "role"}) {
            assertTrue(validatePairing(bad, null, null).isEmpty(),
                    "类型名 " + bad + " 解析不出来时配对约束必须放行，否则会多报一条必然结果");
            // 而 @InEnum / @NotBlank 那边确实报了 —— 两条注解合起来仍然没有缺口
            assertFalse(validateSubjectType(bad).isEmpty(), "类型名 " + bad + " 本该被拦");
        }
    }

    @Test
    @DisplayName("⚠️ ref 型主体多带一个 subjectCode 是刻意放行的 —— SubjectKey 对它也只是置 null")
    void testRefBasedWithStrayCodeTolerated() {
        assertTrue(validatePairing("ROLE", 7L, "leftover").isEmpty(),
                "拦它就比 SubjectKey 与 resolveSubject 两处权威都严，属于另立新规则。"
                        + "它确有副作用（upsert 判键算 code、闭包查询不算，会落两行同逻辑键的数据），"
                        + "已在 GrantSaveReqVO 的注释里登记");
    }

    @Test
    @DisplayName("🔴 整对象校验：合法的 ROLE 授权项一条违规都没有（别把正常路径拦死）")
    void testWholeObjectValidRoleAccepted() {
        Set<ConstraintViolation<GrantSaveReqVO>> violations =
                VALIDATOR.validate(buildValid("ROLE", 7L, null));

        assertTrue(violations.isEmpty(), () -> "合法的 ROLE 授权项不该有任何违规：" + violations.stream()
                .map(v -> v.getPropertyPath() + "=" + v.getMessage()).collect(Collectors.joining("; ")));
    }

    @Test
    @DisplayName("🔴 整对象校验：缺 refId 时违规真的出现在结果里（证明注解够得着，不是装饰）")
    void testWholeObjectMissingRefIdSurfaces() {
        GrantSaveReqVO vo = buildValid("ROLE", null, null);

        Set<ConstraintViolation<GrantSaveReqVO>> violations = VALIDATOR.validate(vo);

        assertEquals(1, violations.size(), () -> "只该多出配对这一条违规：" + violations.stream()
                .map(v -> v.getPropertyPath() + "=" + v.getMessage()).collect(Collectors.joining("; ")));
        assertEquals(PROP_REF_PAIRED, violations.iterator().next().getPropertyPath().toString());
    }

    @Test
    @DisplayName("🔴 整对象校验：SYSTEM 缺 code 同样出现在结果里")
    void testWholeObjectMissingCodeSurfaces() {
        GrantSaveReqVO vo = buildValid("SYSTEM", null, null);

        Set<ConstraintViolation<GrantSaveReqVO>> violations = VALIDATOR.validate(vo);

        assertEquals(1, violations.size(), () -> "只该多出配对这一条违规：" + violations.stream()
                .map(v -> v.getPropertyPath() + "=" + v.getMessage()).collect(Collectors.joining("; ")));
        assertEquals(PROP_CODE_PAIRED, violations.iterator().next().getPropertyPath().toString());
    }

}
