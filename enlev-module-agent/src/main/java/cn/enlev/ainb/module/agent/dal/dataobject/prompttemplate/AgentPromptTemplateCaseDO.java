package cn.enlev.ainb.module.agent.dal.dataobject.prompttemplate;

import cn.enlev.ainb.framework.common.enums.CommonStatusEnum;
import cn.enlev.ainb.framework.tenant.core.db.TenantBaseDO;
import cn.enlev.ainb.module.agent.enums.AgentIntentEnum;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 提示词模板<b>用例</b> DO（S7 / L5）
 *
 * <h3>它解决的是什么问题</h3>
 * 模板（{@link AgentPromptTemplateDO}）是一篇带 {@code {{占位符}}} 的骨架。骨架本身不难看懂，
 * 难的是<b>往占位符里填什么</b>：业务人员对着 {@code {{字段清单}}} 常常写出一句「就是那些字段」，
 * 于是拿到一句同样含糊的回答，然后断定「这助手不行」。
 *
 * <p>用例就是把某条模板<b>填好之后的完整正文</b>，点一下直接进输入框、可以直接发。
 * 它的价值不在「多一条模板」，而在<b>示范填法</b>：用户看到一条填得具体的用例，
 * 就知道自己那条该怎么写。所以用例正文刻意写满真实细节（具体表名、具体字段、具体报错原文），
 * 而不是又一篇换个说法的骨架。
 *
 * <h3>🔴 用例挂在模板下，不是独立的一张「模板二号表」</h3>
 * {@link #templateId} 是 {@code NOT NULL}：一条用例的存在意义就是「这条模板填好长这样」，
 * 脱离模板它就只是又一条模板，与既有模板库重复，且管理页没有任何地方能挂它。
 * 也因此，删模板时连带删它的用例（见 {@code AgentPromptTemplateServiceImpl#deletePromptTemplate}）——
 * 留着就是一批指向已删模板的行：管理页的用例子表查不到它们，聊天面板也不再显示，
 * 但分页接口还数得出来，表现为「明明有 45 条，页面上只找得到 30 条」。
 *
 * <h3>🔴 {@link #expectHint} / {@link #expectIntent} 是给「验证」用的，不是给模型看的</h3>
 * 这两列<b>不进任何提示词</b>。它们的用途是让一条用例可被机器验收：
 * 把 {@code content} 真发给助手一次，然后拿 {@code expectIntent} 对分型、拿 {@code expectHint}
 * 对答案的结构性要点。没有这两列，45 条用例就只是 45 段没人复核过的文案 ——
 * 而「文案里提到一个平台压根没有的功能」是这类种子数据最常见的腐烂方式。
 *
 * @author 走进新时代
 */
@TableName("agent_prompt_template_case")
@KeySequence("agent_prompt_template_case_seq")
@Data
@EqualsAndHashCode(callSuper = true)
public class AgentPromptTemplateCaseDO extends TenantBaseDO {

    private Long id;

    /**
     * 所属模板编号，关联 {@link AgentPromptTemplateDO#getId()}。
     *
     * <p>🔴 逻辑外键，库里<b>不建</b> {@code FOREIGN KEY}（与本模块其余表一致）。
     * 一致性靠两处兜：写入前 {@code selectById} 校验模板存在（走租户隔离，
     * 故「他租户的模板」与「不存在」同样被拒），删模板时连带删用例。
     */
    private Long templateId;

    /**
     * 用例标题，列表与聊天面板里显示的那一行字（例如「设备报修单，两级审批」）。
     *
     * <p>要短到能在一行里读完：它的作用是让人在几十条用例里一眼认出「这条像我的场景」，
     * 具体细节全在 {@link #content} 里。
     */
    private String title;

    /**
     * 用例正文 —— 点一下<b>原样</b>插进聊天输入框的那段话，即模板填好之后的样子。
     *
     * <p>🔴 插入是<b>原样</b>的：不改写、不补全、不 trim 内部空白。e2e 直接断言
     * 「插进输入框的字符串 == 这一列」，一字不差。任何「顺手美化一下」都会让
     * 用户看到的和他将要发出的不是同一段话。
     *
     * <p>刻意<b>不</b>禁止正文里出现 {@code {{...}}}：工作流 DSL 的取值表达式本身就长这样
     * （{@code {{$.input.tableName}}}），一条教用户问 DSL 的用例必须能把它写出来。
     * 未填的占位符改由前端提示（复用 {@code placeholdersOf}），不在这里一刀切拦掉。
     */
    private String content;

    /**
     * 期望要点：一次合格回答<b>结构上</b>该出现什么（例如「要给出发布体检的入口菜单路径，
     * 并逐条列出体检发现的问题」）。只在管理页展示，不进模型上下文。
     *
     * <p>🔴 写<b>结构</b>不写<b>措辞</b>。要点落成「是否提到 DDL 预览」「是否给出菜单路径」
     * 「是否推荐了控件白名单里的控件」这类可判定的事实；写成「回答应当专业、清晰、有条理」
     * 等于没写 —— 它永远为真，也就永远验不出任何回归。
     */
    private String expectHint;

    /**
     * 期望分型，取值 {@link AgentIntentEnum} 的 {@code CHAT} / {@code BUILD}；<b>可空</b>。
     *
     * <p>🔴 空是<b>有意义的第三态</b>，不是「忘了填」：它表示这条用例正压在
     * 「问怎么做」与「让你去做」那条界线上，两种分型都算对，验收时<b>不要</b>拿它做断言。
     * 分型是一次 temperature=0 的真实模型调用（{@code AgentIntentClassifier}），
     * 边界用例换个模型就会翻面；把猜测写成断言，只会得到一条所有人都学会忽略的红测试。
     * 宁可标空，也不标一个会随机翻的期望值。
     */
    private String expectIntent;

    /**
     * 排序，小的在前（同一条模板的用例内部）。
     */
    private Integer sort;

    /**
     * 状态，枚举 {@link CommonStatusEnum}：0 开启 / 1 关闭。
     * <p>与模板同款语义：关闭的用例<b>不出现在聊天面板</b>，但管理页仍可见可改。
     */
    private Integer status;

}
