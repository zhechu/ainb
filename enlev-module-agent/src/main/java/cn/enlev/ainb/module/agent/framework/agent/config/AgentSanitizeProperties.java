package cn.enlev.ainb.module.agent.framework.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 不可信输入的处置配置（B2-1：进 state 之前的限长档位）
 *
 * <h3>🔴 为什么必须按源分档，不能一刀切</h3>
 * 五个污点源（{@code UntrustedInputTracer.SourceKind}）的「合理长度」差着两个数量级：
 * 一条知识库检索片段是被<b>切过块</b>的，几千字符就是异常；一个 webhook body 是机器对机器的
 * 整张订单 JSON，几万字符完全正常。给它们同一个数，只有两种结局——
 * 取小值会把合法的 webhook 业务数据截成半截（<b>功能坏掉，且没人报错</b>），
 * 取大值等于对检索片段不设防（<b>而检索片段恰恰是最容易被塞进 prompt 的那一类</b>）。
 *
 * <h3>🔴 为什么走配置而不是写死</h3>
 * 与 {@link AgentWebhookProperties} 同一条理由：这些是<b>运营参数</b>。
 * 现场真出现「某个对接方的合法 body 被截了」时，该调的是配置，不是改代码 + 重新发版。
 *
 * <h3>🔴 刻意不留「总开关」</h3>
 * 限流那边留了 {@code rate-limit-enabled}，因为限流器依赖 Redis、Redis 故障时现场需要一个
 * 不回滚代码就能恢复通行的止血手段。<b>本组件没有那种故障模式</b>：它是纯字符串处理，
 * 不碰任何外部依赖，不会「自己成为故障源」。
 * 于是关掉它能换来的只有「控制字符不再被剥、超长内容不再被截」——
 * 那<b>不是止血，是把防线拆了</b>。单个源的上限若真挡住合法数据，调那一个源的档位即可，
 * 不必为此开一个能把五档一起废掉的口子。
 *
 * @author 走进新时代
 */
@ConfigurationProperties(prefix = "enlev.agent.sanitize")
@Validated
@Data
public class AgentSanitizeProperties {

    /**
     * 聊天用户输入的单值上限（字符数）
     *
     * <p>人在输入框里打字，几千字已经是长篇。给 4000 是留了「粘贴一整段报错日志来提问」的余量，
     * 再往上就没有正当用途了——而这一档的每个字符都会<b>逐字进 prompt</b>，是直接按 token 计钱的。
     */
    private Integer chatMaxLength = 4000;

    /**
     * webhook 请求体的单值上限（字符数）
     *
     * <h3>🔴 为什么这一档比其余四档都大一个数量级</h3>
     * webhook 是机器对机器：对接方推过来的可能是一整张含几十个明细行的订单 JSON。
     * 按「检索片段」那种量级去卡它，会把合法业务数据截成半截 ——
     * 而这种截断<b>不报错、不抛异常</b>，只会让下游流程拿着残缺数据继续跑，是最难查的一类故障。
     *
     * <p>注意这里限的是<b>单个字符串叶子</b>，不是整个 body 的字节数：
     * body 解析成 map 之后逐个叶子过，故「一张有 50 个明细行的订单」不会被这一个数卡住，
     * 被卡住的是「某一个字段里塞了 3 万字」这种形状——那才是不正常的。
     */
    private Integer webhookBodyMaxLength = 32768;

    /**
     * 表单事件提交值的单值上限（字符数）
     *
     * <p>表单字段值由前端控件约束，但仍可能含富文本 / 多行文本控件。
     * 8192 比聊天档大一倍：表单里的长文本是<b>业务数据</b>（会被写回库），
     * 而聊天里的长文本只是<b>一次提问</b>，前者被截的代价更高。
     */
    private Integer formEventValueMaxLength = 8192;

    /**
     * 单条知识库检索片段的长度上限（字符数）
     *
     * <h3>🔴 这一档是五档里最该收紧的</h3>
     * 检索片段来自<b>上传的文档</b>：谁能往知识库里传文件，谁就能往这条流程的 prompt 里写字。
     * 而知识库的上传权限与流程的编辑权限<b>不是同一个权限</b>，
     * 所以它是五个源里「攻击者门槛最低、且内容 100% 逐字进 prompt」的那一个。
     *
     * <p>片段本身是被切过块的（ai 模块按 chunk 切），正常几百到两千字符。
     * 4000 已经给了两倍余量；超出说明知识库的切块配置被改过，或有人塞了畸形文档。
     */
    private Integer retrievalSegmentMaxLength = 4000;

    /**
     * 工具返回值的单值上限（字符数）
     *
     * <p>工具返回的是<b>外部系统的响应</b>（HTTP 工具的 body、数据查询工具的结果集），
     * 长度不由我们控制，故给到 16384。
     * 但它同时是「最容易被模型当指令读」的一类——工具返回的 JSON 里出现
     * 「please ignore previous instructions」是完全可能的（无论有意还是巧合），
     * 所以宁可给大一点让它<b>完整</b>地被包裹进数据段，也不要截断后
     * 让残留的半句话落在包裹之外。
     */
    private Integer toolResultMaxLength = 16384;

    /**
     * 递归处置嵌套 map / list 的最大深度
     *
     * <h3>🔴 这一条不是「精细度」参数，是「别把线程搞死」参数</h3>
     * webhook body 的嵌套深度由调用方决定，无界递归会在图执行线程里抛
     * {@code StackOverflowError}——那<b>不是</b> {@code Exception}，
     * 执行层那些 {@code catch (Exception)} 一个都拦不住（{@code OutputModerator} 的限深是同一个理由）。
     *
     * <p>给 16 而不是 8：webhook body 的正当嵌套（订单 → 明细 → 商品 → 规格）能有五六层，
     * 8 层会误伤；16 层离 {@code StackOverflowError} 还差得远（默认栈能撑几千层）。
     *
     * <p>🔴 超限的处置是<b>丢弃该子树并记 warn</b>，不是「原样保留」——
     * 原样保留等于让一个没被处置过的值进了 state，那是这道闸唯一不能有的形状。
     */
    private Integer maxDepth = 16;

}
