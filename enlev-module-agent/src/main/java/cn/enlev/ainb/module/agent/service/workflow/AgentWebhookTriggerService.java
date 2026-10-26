package cn.enlev.ainb.module.agent.service.workflow;

/**
 * Webhook 入站触发服务（增量14c / P6）：把「一次匿名 HTTP 回调」翻译成「验签 + 无头拉起订阅了该 path 的 Workflow」
 *
 * <h3>它在整条链路里的位置</h3>
 * {@code AgentWebhookController}（匿名 {@code @PermitAll} 端点）只负责取 {@code path}/请求体/签名头，随即委派本服务；
 * 本服务做三件事：① 按 path 反查已发布流程（租户隔离由 ambient {@code tenant-id} 头保证）；② 按 DSL 里配的
 * {@code authType} 验签（fail-close）；③ 把请求体作为输入经 {@code WorkflowTriggerInvoker} 以发布者身份无头拉起。
 * 与 {@code FormDataChangedTriggerListener}（form_event）、{@code WorkflowScheduleTriggerScheduler}（schedule）
 * 是触发器家族的第三个成员，共用同一个 {@code WorkflowTriggerInvoker}。
 *
 * <h3>🔴 安全边界（这是 agent 模块唯一的匿名入站面）</h3>
 * <ul>
 *   <li>验签<b>fail-close</b>：配了 hmac/token 而签名缺失或不符，一律拒（抛 {@code WORKFLOW_WEBHOOK_SIGNATURE_INVALID}），
 *       绝不降级放行；定长时间比较（{@code MessageDigest.isEqual}）防时序侧信道。</li>
 *   <li>反查只认 PUBLISHED，且「不存在」与「未启用」共用一条笼统文案（{@code WORKFLOW_WEBHOOK_NOT_FOUND}），
 *       不让匿名方用可枚举 path 探测本租户配了哪些 hook。</li>
 *   <li>租户隔离<b>绝不</b>用 {@code @TenantIgnore}/{@code executeIgnore} 关掉（agent 架构红线）：
 *       租户来自调用方传的 {@code tenant-id} 头，反查与无头启动都在该 ambient 租户内进行。</li>
 * </ul>
 *
 * @author 走进新时代
 */
public interface AgentWebhookTriggerService {

    /**
     * 处理一次 webhook 入站回调
     *
     * @param path      URL 路径里的 webhook 键（{@code /agent/webhook/{path}}），与 DSL {@code trigger.webhook.config.path} 逐字一致
     * @param rawBody   原始请求体（用于验签的字节源，也作为工作流输入）；可为 null/空
     * @param signature {@code X-Webhook-Signature} 头（authType=hmac 时的 HMAC-SHA256 十六进制摘要，可带 {@code sha256=} 前缀）；可为 null
     * @param token     {@code X-Webhook-Token} 头（authType=token 时的静态令牌）；可为 null
     * @return true=已成功派发（工作流已 subscribe 异步跑）；false=未派发（发布者身份缺失 fail-close，或启动同步失败）
     * @throws cn.enlev.ainb.framework.common.exception.ServiceException path 无对应已发布流程（{@code WORKFLOW_WEBHOOK_NOT_FOUND}），
     *                                                                       或验签失败（{@code WORKFLOW_WEBHOOK_SIGNATURE_INVALID}）
     */
    boolean handleWebhook(String path, String rawBody, String signature, String token);

}
