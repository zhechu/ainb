package cn.enlev.ainb.module.agent.controller.admin.webhook;

import cn.enlev.ainb.framework.common.pojo.CommonResult;
import cn.enlev.ainb.framework.common.util.servlet.ServletUtils;
import cn.enlev.ainb.module.agent.framework.agent.core.security.AgentWebhookRateLimiter;
import cn.enlev.ainb.module.agent.service.workflow.AgentWebhookTriggerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.annotation.security.PermitAll;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static cn.enlev.ainb.framework.common.pojo.CommonResult.success;

/**
 * Workflow Webhook 匿名入站端点（增量14c / P6）：外部系统 POST 回调 → 触发订阅了该 path 的已发布流程
 *
 * <h3>🔴 这是 agent 模块唯一的匿名可达面，安全模型分四层</h3>
 * <ol>
 *   <li><b>免登录</b>：{@code @PermitAll} 让 Spring Security 放行无 token 的请求（{@code EnlevWebSecurityConfigurerAdapter}
 *       扫描该注解自动加入 permitAll 列表）。外部系统无需 enlev 账号即可回调。</li>
 *   <li><b>限流（B2-5）</b>：{@link AgentWebhookRateLimiter} 按 <b>IP 与 path 两个维度、各自独立阈值</b>计量，
 *       任一层超限即拒。它排在<b>最外层</b>——比验签还靠前，理由见该类注释。
 *       🔴 没有这一层，下面那条「验签」只能证明请求来自持密钥者，证明不了他没在一秒内发一万个；
 *       而每一次命中都是一次无头拉起流程（烧 token、写库、占图执行线程）。
 *       阈值全部走 {@code enlev.agent.webhook.*} 配置，不写死在代码里。</li>
 *   <li><b>租户隔离不降级</b>：调用方<b>必须带标准 {@code tenant-id} 请求头</b>（与前端每个请求一样）。
 *       {@code TenantContextWebFilter} 据此设 ambient 租户，{@code TenantSecurityWebFilter} 校验其合法后放行；
 *       反查 {@code selectPublishedByWebhookKey} 与无头启动都在该 ambient 租户内，A 租户的 hook 绝打不到 B 租户的流程。
 *       <b>刻意不用 {@code @TenantIgnore}</b>——那会关掉隔离、踩 agent 架构红线；改用「租户走请求头」这条标准路径，
 *       既不降级隔离、也无需把 URL 塞进全局 ignore-urls。</li>
 *   <li><b>验签 fail-close</b>：真正的鉴权在 {@link AgentWebhookTriggerService}——按 DSL 配的 {@code authType}
 *       （只支持 hmac/token，<b>没有「不鉴权」这一档</b>）校验 {@code X-Webhook-Signature}/{@code X-Webhook-Token}，
 *       缺失、不符、或 authType 为空/未知一律拒。
 *       验签 + 发布者配额 + 上面那道限流共同兜住「匿名面被滥用烧 token / 改数据」；
 *       path 只是路由键，<b>不承担任何安全职责</b>（它会进日志、进前端 URL、进浏览器历史，猜得到也拿得到）。</li>
 * </ol>
 *
 * <h3>fire-and-forget</h3>
 * 命中即以<b>发布者身份</b>（{@code ofSystem(ownerUserId)}）无头拉起，本端点不等流程跑完即返回 {@code dispatched}
 * （true=已派发）。要确认执行结果另查「工作流执行历史」。这与 form_event/schedule 触发器语义一致：
 * 入站回调只负责「把流程点着」，同步返回快、不被下游执行时长拖住。
 *
 * <h3>调用方约定（配置 UI 会展示完整 URL 与所需头）</h3>
 * {@code POST /admin-api/agent/webhook/{path}}，头 {@code tenant-id: <租户号>}；
 * authType=hmac 时再加 {@code X-Webhook-Signature: <对原始请求体的 HMAC-SHA256 十六进制，可带 sha256= 前缀>}；
 * authType=token 时加 {@code X-Webhook-Token: <静态令牌>}。请求体建议为 JSON 对象，其字段即工作流 {@code input} 变量的键。
 *
 * @author 走进新时代
 */
@Tag(name = "管理后台 - Workflow Webhook 入站（匿名）")
@Slf4j
@RestController
@RequestMapping("/agent/webhook")
@Validated
public class AgentWebhookController {

    @Resource
    private AgentWebhookTriggerService webhookTriggerService;

    @Resource
    private AgentWebhookRateLimiter webhookRateLimiter;

    @PostMapping("/{path}")
    @PermitAll
    @Operation(summary = "接收外部 webhook 回调，触发订阅该 path 的已发布流程",
            description = "匿名可达；须带标准 tenant-id 头定位租户。验签按流程 DSL 的 trigger.webhook.config.authType 进行，fail-close")
    @Parameter(name = "path", description = "webhook 键，与 DSL trigger.webhook.config.path 逐字一致", required = true)
    public CommonResult<Boolean> receive(
            @PathVariable("path") String path,
            @RequestBody(required = false) String body,
            @RequestHeader(value = "X-Webhook-Signature", required = false) String signature,
            @RequestHeader(value = "X-Webhook-Token", required = false) String token) {
        // 🔴 限流必须在最外层，早于验签与 path 反查（B2-5 / R-10 ①）。
        //    IP 在这一行取出、显式传进去 —— 刻意不让下游组件自己去读 request：
        //    服务层签名里出现 HttpServletRequest 就是把 web 关注点漏进了业务层。
        webhookRateLimiter.check(ServletUtils.getClientIP(), path);
        return success(webhookTriggerService.handleWebhook(path, body, signature, token));
    }

}
