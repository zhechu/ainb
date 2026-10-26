package cn.enlev.ainb.module.system.controller.admin.permission.vo.grant;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.common.validation.InEnum;
import cn.enlev.ainb.module.system.enums.permission.FieldModeEnum;
import cn.enlev.ainb.module.system.enums.permission.GrantActionEnum;
import cn.enlev.ainb.module.system.enums.permission.GrantEffectEnum;
import cn.enlev.ainb.module.system.framework.permission.core.subject.SubjectTypeEnum;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 授权行新增 / 修改请求 VO
 */
@Schema(description = "管理后台 - 授权行新增/修改请求")
@Data
public class GrantSaveReqVO {

    @Schema(description = "授权编号，修改时必填")
    private Long id;

    /**
     * 主体类型，取值见 {@code SubjectTypeEnum}（USER/ROLE/DEPT/POST/GROUP/SYSTEM/ALL）
     *
     * 🔴 下面这条 {@code @InEnum} 是本 VO 四个枚举字段的最后一个：action / effect / fieldMode
     * 早就有了，subjectType 一直只有 {@code @NotBlank}。缺它的后果与那三个一模一样 ——
     * {@code SubjectTypeEnum#parse} 对枚举外的值返回 null（那是给「读库存量」的宽容度），
     * 于是 {@code ROLES}、{@code role} 这类值存得进去、列表里看得见、也不报错，
     * 而主体闭包永远算不出它，<b>这一行是死的</b>。写成 DENY 时更糟：
     * 配的人以为封住了，其实什么都没封。
     *
     * <p>⚠️ 这条 {@code @InEnum} 只管「类型名在不在枚举里」，<b>不管它与另外两个字段的配对</b>
     * （跨字段，字段级注解表达不了）。配对由本类末尾那两条 {@code @AssertTrue} 兜住，
     * 规则与 {@code SubjectKey} 的紧凑构造器逐条对齐
     */
    @Schema(description = "主体类型，取值见 SubjectTypeEnum：USER/ROLE/DEPT/POST/GROUP/SYSTEM/ALL",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "主体类型不能为空")
    @InEnum(value = SubjectTypeEnum.class, message = "主体类型不合法，必须是 {value} 之一")
    private String subjectType;

    // ⚠️ 下面两个字段都是「条件必填」，但刻意不写 requiredMode：
    // 打进 jar 的 swagger-annotations-jakarta 2.2.38 里 Schema.RequiredMode 只有
    // AUTO / REQUIRED / NOT_REQUIRED 三档（javap 实测），没有「看另一个字段而定」这一档。
    // 写成 REQUIRED 会在文档上对 SYSTEM/ALL 说谎，写成 NOT_REQUIRED 则对 ROLE 说谎，
    // 所以留默认 AUTO、把条件写进 description —— 真正拦人的是末尾那两条 @AssertTrue
    @Schema(description = "主体业务表主键。USER/ROLE/DEPT/POST/GROUP 必填；SYSTEM/ALL 不用它寻址")
    private Long subjectRefId;

    @Schema(description = "主体编码。SYSTEM/ALL 必填（ALL 的固定值是 all-users）；USER/ROLE/DEPT/POST/GROUP 不用它寻址")
    private String subjectCode;

    @Schema(description = "资源编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "资源编号不能为空")
    private Long resourceId;

    @Schema(description = "上下文资源编号，null 表示全局")
    private Long contextResourceId;

    @Schema(description = "动作，取值见 GrantActionEnum；接口鉴权实际只匹配 VIEW 与 *，流程类资源另有 START/DESIGN/MANAGE/VIEW_ALL", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "动作不能为空")
    // ⚠️ 这条注解拦住的是「存进去但永远匹配不上」的静默失效行：
    // GrantDecisionEngine 按字符串精确匹配 action，枚举外的值（历史上真出现过 EXEC）
    // 既不报错也不生效 —— 如果那是一行 DENY，配的人以为封住了，其实什么都没封。
    // {value} 由 InEnumValidator 渲染成 GrantActionEnum 的全集，不会与枚举漂移
    @InEnum(value = GrantActionEnum.class, message = "动作不合法，必须是 {value} 之一")
    private String action;

    @Schema(description = "效果：ALLOW 或 DENY", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "效果不能为空")
    // 与上面 action 那条 @InEnum 同理：判据同样是 String.equals（GrantDecisionEngine
    // 比对的是 "DENY".equals(grant.getEffect())），枚举外的 effect 既不匹配 ALLOW
    // 也不匹配 DENY，存进去静默失效 —— 若本意是 DENY，配的人以为封住了，其实什么都没封。
    // {value} 由 InEnumValidator 渲染成 GrantEffectEnum 全集，不会与枚举漂移
    @InEnum(value = GrantEffectEnum.class, message = "效果不合法，必须是 {value} 之一")
    private String effect;

    @Schema(description = "行过滤规则编号，null 表示不过滤")
    private Long ruleId;

    /**
     * 字段显示模式。合法值只有 EDITABLE / READONLY / HIDDEN，「不限制」用 null 表达。
     *
     * 🔴 下面这条 {@code @InEnum} 拦的是与 action / effect 完全同形的坑：
     * {@code FieldModeEnum#parse} 对枚举外的值返回 null，而引擎把 null 读作「这一行没配字段模式」，
     * 于是写成 {@code VISIBLE} 会让<b>整行授权被静默忽略</b> —— 保存成功、列表正常、不报错，
     * 只是权限压根没生效。前端那个下拉一度真的写着 VISIBLE，页面提示文案教人踩这个坑，
     * 已在资源明细面板一侧修掉；这里把入口一并封死，免得别的客户端再照着旧文档填。
     */
    @Schema(description = "字段显示模式：EDITABLE/READONLY/HIDDEN，null 表示不限制")
    @InEnum(value = FieldModeEnum.class, message = "字段显示模式不合法，必须是 {value} 之一")
    private String fieldMode;

    @Schema(description = "到期时间，null 表示永不过期")
    private LocalDateTime expireTime;

    // ========== 主体三件套的配对校验 ==========

    /**
     * 配对校验之一：ref 型主体（USER/ROLE/DEPT/POST/GROUP）必须带 {@code subjectRefId}
     *
     * <h3>🔴 为什么要补这一条</h3>
     *
     * 主体寻址的权威是 {@code SubjectKey}，它的紧凑构造器<b>早就</b>把这条规则写死了：
     * ref 型缺 refId 直接抛 {@code NullPointerException}（{@code Objects.requireNonNull}，
     * 不是 {@code IllegalArgumentException} —— 缺 code 那一支才是后者）。读侧同样判过一遍 ——
     * {@code GrantAdminController#resolveSubject}（{@code /list-by-subject} 与生效权限树
     * 两个读端点共用）缺 refId 时抛 {@code GRANT_SUBJECT_IDENTIFIER_REQUIRED}。唯独<b>写</b>侧一路无人把守：
     * {@code GrantAdminServiceImpl#doUpsert} 拿到 VO 就 {@code BeanUtils.toBean} 落库。
     *
     * <p>后果是一行「建得出来、列表里看得见、永远匹配不上」的死授权 ——
     * {@code GrantMapper#buildSubjectWrapper} 对 ref 型主体拼的条件是
     * {@code subject_type = ? AND subject_ref_id = ?}，refId 为 null 时它恒假，
     * 主体闭包一次也捞不到这行。若那是一行 DENY，配的人以为封住了，其实什么都没封。
     * 与上面 action / effect / fieldMode 三条 {@code @InEnum} 拦的是<b>同一个形状</b>，
     * 那三条是「值在枚举外」，这两条是「值缺了」，殊途同归于静默失效。
     *
     * <h3>⚠️ 为什么加在 VO 上，而不是 Service 里</h3>
     *
     * 写入口只有 {@code GrantAdminController} 的三个 {@code @Valid} 端点
     * （{@code /create}、{@code /create-list}、{@code /update}），批量那条靠
     * {@code GrantBatchSaveReqVO#items} 上的 {@code @Valid} 级联下来 ——
     * 所以加在这个 VO 上就是全覆盖，没有旁路。
     *
     * <p>刻意<b>不</b>放进 {@code GrantAdminServiceImpl}：那里有一条既有用例钉住了
     * 「SYSTEM 主体的 {@code ''} 与 null 判键时归一成同一个键」，那是为了对上库里
     * 历史写入路径不一致的存量行，必须继续宽容。<b>边界严、核心宽</b>，两层各管各的：
     * 新提交不许再产生配不上的行，读存量时不许因为一行脏数据炸掉整个权限求值。
     *
     * <h3>为什么用 {@code @AssertTrue} 而不是自定义 ConstraintValidator</h3>
     *
     * 跨字段约束只有这两条路。本仓库的 {@code ConstraintValidator} 实现一共六个，
     * <b>全部</b>住在 {@code enlev-framework}（{@code enlev-common/validation} 四个、
     * {@code starter-excel} 的字典校验两个），system / infra / bpm / agent 四个业务模块里
     * 一个都没有 —— 也就是「业务模块自带校验器」在这个仓库不是既有模式。
     * 为唯一的一个消费方开这个先例不值当；{@code @AssertTrue} + {@code @JsonIgnore}
     * 才是本仓库的既有写法（见 {@code BpmApprovalDetailReqVO#isValidProcessParam}）。
     *
     * <p>🔴 {@code @JsonIgnore} <b>不可省，这不是洁癖</b>：springdoc 按 JavaBean 属性
     * 枚举请求体字段，一个 {@code isXxx()} 就是一项。本仓库原先漏了两处，
     * 在 {@code /v3/api-docs} 里实测能看到 —— {@code AuthLoginReqVO#isSocialCodeValid}
     * 让<b>登录接口</b>的文档多出一个 {@code socialCodeValid} 布尔请求字段，
     * {@code OAuth2ClientSaveReqVO#isAdditionalInformationJson} 同理多出
     * {@code additionalInformationJson}。照着文档填的人会以为这两个要传。
     * 两处已在本次改动里一并补上注解（同一个形状，一起修）
     *
     * <h3>刻意不拦的一种情况</h3>
     *
     * ref 型主体<b>多带</b>一个 {@code subjectCode} 不在此列 —— {@code SubjectKey}
     * 对它的处理是「强制置 null」而不是抛异常，也就是权威实现认为这无害。
     * 拦它就比两处权威都严，属于本轮范围外另立规则。它确实有个副作用：
     * upsert 判键（{@code GrantKey}）会把 code 算进键里，而闭包查询不算，
     * 于是「带 code 存一次、不带 code 再存一次」会落两行同逻辑键的数据。
     * 现有前端走不到这条路上：三个写入口（{@code GrantForm} 新增/编辑抽屉、
     * {@code GrantBatchForm} 批量授权、{@code ResourceDetailPanel} 的行内新增/编辑）
     * 都把主体写死成 {@code ROLE} + {@code subjectRefId}；编辑那条虽然原样回传
     * {@code row.subjectCode}，但 ROLE 行在库里这一列本来就是 NULL
     */
    @AssertTrue(message = "主体类型为 USER/ROLE/DEPT/POST/GROUP 时必须填写主体编号 subjectRefId")
    @JsonIgnore
    public boolean isSubjectRefIdPaired() {
        SubjectTypeEnum type = SubjectTypeEnum.parse(subjectType);
        // 类型名解析不出来 ⇒ 这里放行，让 subjectType 上的 @NotBlank / @InEnum 去报。
        // 否则一次提交会同时冒出「主体类型不合法」和「缺少主体编号」两条提示，
        // 而后者只是前者的必然结果 —— 会让人以为自己填错了两个地方
        if (type == null) {
            return true;
        }
        return !type.isRefBased() || subjectRefId != null;
    }

    /**
     * 配对校验之二：code 型主体（SYSTEM/ALL）必须带非空 {@code subjectCode}
     *
     * <p>与 {@link #isSubjectRefIdPaired()} 是同一件事的两半，理由、分层、写法全部相同，
     * 不重复。判据用 {@code StrUtil.isNotBlank} 而不是 {@code != null}：
     * 闭包查询拼的是 {@code subject_code = ?}，空串同样恒假，同样是死行。
     * 这也正是 {@code SubjectKey} 与 {@code resolveSubject} 两处权威用的判据
     */
    @AssertTrue(message = "主体类型为 SYSTEM/ALL 时必须填写主体编码 subjectCode（ALL 的固定值是 all-users）")
    @JsonIgnore
    public boolean isSubjectCodePaired() {
        SubjectTypeEnum type = SubjectTypeEnum.parse(subjectType);
        if (type == null) {
            return true;
        }
        return type.isRefBased() || StrUtil.isNotBlank(subjectCode);
    }

}
