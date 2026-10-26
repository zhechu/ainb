package cn.enlev.ainb.module.agent.framework.agent.core.tool;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 工具声明
 *
 * 每个 AI 工具都要挂这块「权限牌子」，网关在执行前查牌。
 *
 * <p>🔴 <b>为什么工具必须自己声明权限，而不能靠 Controller 的 {@code @PreAuthorize}</b>：
 * AI 直接调 Service，根本不经过 Controller，那些注解对它<b>完全无效</b>。
 * 每个工具都得编程式校验一遍。
 *
 * @author 走进新时代
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface AgentTool {

    /**
     * 工具名，全局唯一。LLM 就是按这个名字选工具的
     *
     * <p>🔴 面向模型，故一律用 {@code snake_case} 英文，不带中文、不带空格。
     * 给人看的是 {@link #displayName()}。
     */
    String name();

    /**
     * 展示名（中文），管理页与工作流设计器给人看的那一个
     *
     * <h3>🔴 为什么不拿 {@link #name()} 直接给人看</h3>
     * {@code datamodel_preview_ddl} 对模型是精确的标识符，对业务人员是一串需要翻译的密码。
     * 管理页原先直接把工具名当展示名列出来（{@code getDisplayName()} 未维护时回落 name），
     * 于是一屏 30 行全是下划线英文，找工具只能靠肉眼扫。展示名与工具名分列后，
     * 「预览建表语句」和 {@code datamodel_preview_ddl} 各归其位。
     *
     * <p>留空则回落 {@link #name()}，故新增工具忘填不会报错，只是管理页仍显示英文名。
     */
    String displayName() default "";

    /**
     * 分组名（中文），管理页与设计器按它归类
     *
     * <p>现有八个分组：数据建模 / 数据接口 / 表单设计 / 表单数据 / 控件契约 / 流程审批 / 平台手册 / 诊断排错。
     * 大体对应 {@code tool/impl/} 下的包，但<b>不严格等同</b>——{@code widget_contract_get} 与表单工具同包，
     * 却单独成组：它是控件白名单的权威副本，和「改一张具体表单」不是一类事，混在表单设计里反而找不到。
     *
     * <p>刻意写成字面量而非引用常量：注解属性要求编译期常量，而分组是给业务人员看的文案，
     * 散在各工具类里比集中一张表更容易在新增工具时就地想清楚「它属于哪一类」。留空表示不分组。
     *
     * <p>🔴 分组名<b>刻意不跟着菜单名走</b>。2026-10 那次菜单改名把「数据建模」改成「数据模型」、
     * 「数据接口」改成「接口管理」，但这两个分组名一个字没动，理由是它们描述的是<b>一类工具在做什么</b>
     * （建模、调接口），不是<b>用户从哪个菜单进去</b>；「接口管理」当分组名反而看不出这组工具能干什么。
     * 改名还要连带迁移 {@code agent_tool_override.group_name} 里已存的覆盖值，
     * 漏一条就会出现同一组工具在管理页被拆成两半。读到这里的不要「顺手统一」
     */
    String groupName() default "";

    /**
     * 一句话说明这个工具干什么，会进 LLM 的 prompt
     */
    String description() default "";

    /**
     * 需要的权限串，如 {@code infra:data-model:create}
     *
     * 与界面按钮用的是<b>同一套权限点</b> —— 走菜单通道注册，
     * 这样授权界面、诊断页、继承树全部能复用，不必为 AI 单开一套。
     */
    String permission();

    /**
     * 追加需要的权限串，与 {@link #permission()} 是<b>且</b>的关系
     *
     * <h3>为什么「原子动作」类工具必须把权限一次性声明全</h3>
     * 这类工具内部串了好几个写操作（{@code form_publish_bundle} 五步、
     * {@code process_create_and_deploy} 两步），只声明第一步的权限的话，
     * 网关会放行，然后<b>卡在中间那一步</b> —— 留下一个发布了但没菜单的表单，
     * 或者一个建了但没部署的流程模型。
     *
     * <p>后者尤其糟：非超管<b>改不了「从未部署过」的流程模型</b>
     *（{@code BpmModelServiceImpl} 的刻意设计），所以那个半成品模型
     * 用户既删不掉也改不了，只能找超管。
     *
     * <p>所以判定要<b>前置</b>：进闸门时就把整条链需要的权限查全，
     * 差一个就一步都不做。
     */
    String[] extraPermissions() default {};

    /**
     * 风险等级，决定是否要进 Plan 等确认
     */
    RiskLevel risk();

    /**
     * 是否默认启用
     *
     * 默认 true。要临时下线某个工具时改成 false，比删代码安全。
     */
    boolean enabled() default true;

    /**
     * 结果回灌给模型时是否允许被截断
     *
     * <h3>🔴 白名单类工具必须设成 false</h3>
     * 截断一份<b>清单</b>和截断一份<b>列表</b>是两件完全不同的事：
     * {@code datamodel_list} 截断了，模型少看到几个候选，顶多再查一次；
     * 而 {@code widget_contract_get} 截断了，模型拿到的是一份
     * <b>看起来完整、实则只剩前几个</b>的白名单 ——
     * 它无从知道自己没看全，于是照着残缺清单选，选不到就<b>凭记忆编一个</b>。
     *
     * <p>实测就是这么炸的：截断上限 4000 字符只装得下前 8 个控件
     *（到 {@code select} 为止），{@code datePicker} 排在第 13 位。
     * 用户要「日期时间」字段时模型在清单里找不到，编了个 {@code dateTimePicker}，
     * 然后反复重查白名单想拿到完整清单 —— 每次都被同样截断，5 轮耗尽、规划失败。
     *
     * <p>所以这类工具要么完整给出，要么在返回值里<b>明说自己不完整</b>。
     * 体积问题应当由工具自己分页/精简来解决（见 {@code WidgetContractGetTool}
     * 的 {@code types} 参数），而不是靠截断。
     */
    boolean truncateResult() default true;

}
