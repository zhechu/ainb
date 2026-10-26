package cn.enlev.ainb.module.agent.service.workflow;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.HMac;
import cn.hutool.crypto.digest.HmacAlgorithm;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.enlev.ainb.module.agent.dal.dataobject.workflow.AgentWorkflowDefDO;
import cn.enlev.ainb.module.agent.dal.mysql.workflow.AgentWorkflowDefMapper;
import cn.enlev.ainb.module.agent.framework.agent.config.AgentWebhookProperties;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.compiler.DslParser;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.TriggerDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.WorkflowDefDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.trigger.WorkflowTriggerInvoker;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.WORKFLOW_WEBHOOK_BODY_TOO_LARGE;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.WORKFLOW_WEBHOOK_NOT_FOUND;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.WORKFLOW_WEBHOOK_SIGNATURE_INVALID;

/**
 * {@link AgentWebhookTriggerService} 实现（增量14c / P6）
 *
 * <h3>🔴 验签的字节源 = 原始请求体，且用定长时间比较</h3>
 * HMAC 对 {@code rawBody} 的 UTF-8 字节算，与调用方约定的「对原始 JSON 体签名」一致（GitHub/Stripe 同款）。
 * 比较用 {@link MessageDigest#isEqual}（不随首个不同字节早退），杜绝按字节计时推断正确签名的时序侧信道。
 * 十六进制摘要统一小写、容忍 {@code sha256=} 前缀后逐字节比。
 *
 * <h3>🔴 fail-close 四处</h3>
 * ⓪ body 整体超过 {@code enlev.agent.webhook.max-body-length} → {@code WORKFLOW_WEBHOOK_BODY_TOO_LARGE}
 *    （<b>排在最前，连 path 都还没反查</b>，理由见 {@link #handleWebhook} 里那道闸的注释）；
 * ① path 无对应已发布流程 → {@code WORKFLOW_WEBHOOK_NOT_FOUND}（笼统，防探测）；
 * ② authType=hmac/token 而签名/令牌缺失或不符 → {@code WORKFLOW_WEBHOOK_SIGNATURE_INVALID}；
 * ③ authType 缺失、为 {@code none}、或是运行期不认识的值（DSL 被直改等）→ 同样拒。
 * <b>本类不存在免签分支</b>：不鉴权的 webhook 等于把发布者身份挂在公网上（入站 body 会一路流进
 * state → prompt → BPM formVars），「靠不可猜的 path 保安全」这个前提已被证伪（path 会进日志、进前端 URL、
 * 进浏览器历史与代理缓存）。落库侧的同族拦截见 {@code WorkflowTriggerExtractor}。
 *
 * <h3>🔴 入站 body 的两道闸：本类「拒整体」，{@code WorkflowTriggerInvoker} 里「截每个叶子」</h3>
 * body 从进来到落进 {@code state} 会经过<b>两道各自独立</b>的处置，分工与阈值都不同：
 * <ol>
 *   <li><b>本类（HTTP 层）</b>：量整个 body 的字符数，超上限就<b>整条拒掉</b>，body 一个字符都不往下走。
 *       它是<b>唯一会让调用方知情</b>的一道（拿到非零 code）。</li>
 *   <li><b>{@code WorkflowTriggerInvoker#invokeHeadless}（进 state 前）</b>：经
 *       {@code UntrustedInputSanitizer} 按<b>污点源分档</b>逐个字符串叶子限长 + 剥控制字符。
 *       它是<b>静默</b>的：调用方仍拿 {@code 200 + data:true}，只有服务端 warn 记录削掉了什么。
 *       这道闸覆盖 webhook / form_event / schedule <b>三个源</b>，故不可能只在本类里做。</li>
 * </ol>
 * ⚠️ <b>必须知情的空隙</b>：body 整体没超第一道、但某个叶子超了第二道时，
 * 对调用方而言是<b>静默截断</b>（HTTP 200、{@code data:true}、响应体里没有任何提示）。
 * 这是刻意取舍而不是疏漏 —— 要把「被截了」告诉调用方就得改 {@code CommonResult<Boolean>} 的返回形状，
 * 而那个形状已经是对外契约（配置 UI 展示的调用约定里写着 {@code data} 表示是否已派发），改了会打断存量对接方。
 * 观测路径是服务端那条 warn（含源、原长、上限），排障时按它查。
 *
 * <h3>身份与租户</h3>
 * 无头启动经 {@link WorkflowTriggerInvoker#invokeHeadless}：以定义发布者身份（{@code ofSystem(ownerUserId)}）跑，
 * owner 缺失即 fail-close 跳过（返回 false）。租户来自调用方 {@code tenant-id} 头（ambient），反查与启动都在其内，
 * 满足 invoker 要求的「ambient 租户 == def.tenant」。<b>全程不碰 executeIgnore</b>（agent 架构红线）。
 *
 * @author 走进新时代
 */
@Service
@Slf4j
public class AgentWebhookTriggerServiceImpl implements AgentWebhookTriggerService {

    /** 签名头可选的算法前缀（GitHub 风格 {@code sha256=xxx}），比较前剥掉 */
    private static final String SIGNATURE_PREFIX = "sha256=";

    @Resource
    private AgentWorkflowDefMapper defMapper;
    @Resource
    private WorkflowTriggerInvoker triggerInvoker;
    @Resource
    private DslParser dslParser;
    @Resource
    private AgentWebhookProperties webhookProperties;

    @Override
    public boolean handleWebhook(String path, String rawBody, String signature, String token) {
        // ⓪ 第一道闸：body 整体超限 → 整条拒掉。
        //    🔴 刻意排在【path 非空判断】与【反查 def】之前，两个后果都是要的：
        //      (a) 匿名方不能拿一个超大 body 换到我们去做一次 DB 查询 + DSL 解析 + 验签；
        //      (b) 于是「超大 body 被拒」对【任何 path】都成立，包括不存在的 path ——
        //          这条判据才能拿真 HTTP 请求实测，而不必先发布一条真的 webhook 流程、
        //          更不必真的把流程拉起来（那会写执行历史、并可能烧 token）。
        //    🔴 【本行】日志只记长度、不记 body 本身：body 是不可信入站内容，把它写进日志
        //       等于给「用日志二次注入」开门，而且一个 1 MiB 的 body 会把日志刷爆。
        //    ⚠️ 但别把上一句读成「body 不会进日志」—— 那【不是事实】，而且「刷爆」在本仓
        //       不是假设，是本轮已经实测发生的事，只是干的不是这一行：框架的
        //       ApiAccessLogInterceptor#preHandle 在非 prod 环境把整个 body 打进 INFO 日志，
        //       而它由 EnlevApiLogAutoConfiguration#addInterceptors 【无条件】注册，
        //       不看 enlev.access-log.enable —— 那个开关只管 ApiAccessLogFilter 的落库链
        //       （本仓 local / prod 两份 profile 都显式设成 false，故【落库那半边没有发生】：
        //       本次运行 937 条 SQL 全在日志里，api_access_log 与 INSERT 各 0 命中）。
        //       实测后果：被这道闸拒掉的 3 个 body 原样变成了 1048561 / 1048562 / 1199985
        //       字符的日志行（合计约 3.3 MB）——「拦住了流程，没拦住日志」。
        //       🔴 落账 N32，本轮不修（跨模块，且一行修法有真实取舍，见 N32 条目）。
        //    上限未配（yaml 里显式留空）时按 0 = 全拒，fail-close；不 fail-open 成「不限」。
        int maxBodyLength = webhookProperties.getMaxBodyLength() == null
                ? 0 : webhookProperties.getMaxBodyLength();
        int bodyLength = rawBody == null ? 0 : rawBody.length();
        if (bodyLength > maxBodyLength) {
            log.info("[handleWebhook][webhook path({}) 的 body({} 字符) 超过整体上限({})，整条拒绝："
                    + "未反查、未验签、未派发]", path, bodyLength, maxBodyLength);
            throw exception(WORKFLOW_WEBHOOK_BODY_TOO_LARGE, bodyLength, maxBodyLength);
        }
        if (StrUtil.isBlank(path)) {
            throw exception(WORKFLOW_WEBHOOK_NOT_FOUND);
        }
        // ① 反查已发布流程（ambient 租户内过滤，租户来自 tenant-id 头）
        AgentWorkflowDefDO def = defMapper.selectPublishedByWebhookKey(path);
        if (def == null) {
            // 笼统文案：不区分「path 不存在」与「存在但草稿/停用」，防匿名方枚举探测
            log.info("[handleWebhook][webhook path({}) 无对应已发布流程，拒绝]", path);
            throw exception(WORKFLOW_WEBHOOK_NOT_FOUND);
        }
        // ② 验签（fail-close）：authType/secret 从该 def 的 DSL trigger 配置里取
        Map<String, Object> config = resolveTriggerConfig(def);
        verifyAuth(config, rawBody, signature, token);
        // ③ 请求体作为工作流输入，以发布者身份无头拉起
        Map<String, Object> payload = buildPayload(rawBody);
        boolean dispatched = triggerInvoker.invokeHeadless(def, payload);
        log.info("[handleWebhook][webhook path({}) 命中流程({})，派发={}]", path, def.getCode(), dispatched);
        return dispatched;
    }

    /**
     * 解析该 def 的 DSL，取 {@code trigger.webhook.config}（含 authType/secret）。
     * 无 trigger / 无 config 时返回空 map —— 空 map 里的 authType 取出来是 null，
     * 会落进 {@link #verifyAuth} 的 {@code default} 分支被拒（<b>不是</b>免签）。
     * 但能反查命中说明 trigger_type=webhook，正常必有 config
     */
    private Map<String, Object> resolveTriggerConfig(AgentWorkflowDefDO def) {
        WorkflowDefDTO dto = dslParser.parse(def.getDsl());
        TriggerDTO trigger = dto == null ? null : dto.getTrigger();
        Map<String, Object> config = trigger == null ? null : trigger.getConfig();
        return config == null ? Map.of() : config;
    }

    /**
     * 按 authType 验签。fail-close：任何缺失/不符/未知模式都抛 {@code WORKFLOW_WEBHOOK_SIGNATURE_INVALID}
     *
     * <p>🔴 只认 {@code hmac}/{@code token} 两种。<b>没有 {@code none} 分支，缺省也不再归一化成 none</b>——
     * 空值与未知值一律落到 {@code default} 被拒。历史遗留的 {@code authType=none} DSL（落库校验放开前建的）
     * 到这里会一律拒收：这是刻意的，见 {@code WorkflowTriggerExtractor.WEBHOOK_AUTH_TYPES} 的攻击面说明。
     */
    private void verifyAuth(Map<String, Object> config, String rawBody, String signature, String token) {
        String rawAuthType = str(config, "authType");
        // 空白归一成空串（不是 none）：既躲开 switch(null) 的 NPE，又让它落进 default 被拒
        String authType = StrUtil.isBlank(rawAuthType) ? "" : rawAuthType.toLowerCase();
        String secret = str(config, "secret");
        switch (authType) {
            case "hmac" -> {
                // 配了 hmac 却没 secret（DSL 被直改绕过落库校验）→ fail-close，绝不退化成免签
                if (StrUtil.isBlank(secret) || StrUtil.isBlank(signature)) {
                    throw exception(WORKFLOW_WEBHOOK_SIGNATURE_INVALID);
                }
                String expected = hmacSha256Hex(secret, rawBody == null ? "" : rawBody);
                if (!constantTimeEquals(expected, normalizeSignature(signature))) {
                    throw exception(WORKFLOW_WEBHOOK_SIGNATURE_INVALID);
                }
            }
            case "token" -> {
                if (StrUtil.isBlank(secret) || StrUtil.isBlank(token)) {
                    throw exception(WORKFLOW_WEBHOOK_SIGNATURE_INVALID);
                }
                if (!constantTimeEquals(secret, token.trim())) {
                    throw exception(WORKFLOW_WEBHOOK_SIGNATURE_INVALID);
                }
            }
            // 缺省 / none / 任何运行期不认识的 authType：fail-close（绝不默认免签放行）
            default -> throw exception(WORKFLOW_WEBHOOK_SIGNATURE_INVALID);
        }
    }

    /**
     * 把原始请求体转成工作流输入载荷。JSON 对象 → 摊平成 map（字段即 state 键，作者用 {@code input.字段名} 取）；
     * 非 JSON / JSON 数组 / 空 → 包一层 {@code {"raw": body}}。全部 JSON 可序列化（state 红线）
     */
    private Map<String, Object> buildPayload(String rawBody) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (StrUtil.isBlank(rawBody)) {
            return payload;
        }
        try {
            JSONObject obj = JSONUtil.parseObj(rawBody);
            payload.putAll(obj);
        } catch (Exception ex) {
            // 不是合法 JSON 对象（纯文本/数组/畸形）：原样塞进 raw，交给流程自行解析
            payload.put("raw", rawBody);
        }
        return payload;
    }

    private String hmacSha256Hex(String secret, String body) {
        HMac mac = new HMac(HmacAlgorithm.HmacSHA256, secret.getBytes(StandardCharsets.UTF_8));
        return mac.digestHex(body);
    }

    /** 剥掉可选的 {@code sha256=} 前缀、转小写，便于与 hutool 的小写十六进制摘要逐字节比 */
    private String normalizeSignature(String signature) {
        String s = signature.trim();
        if (s.regionMatches(true, 0, SIGNATURE_PREFIX, 0, SIGNATURE_PREFIX.length())) {
            s = s.substring(SIGNATURE_PREFIX.length());
        }
        return s.toLowerCase();
    }

    /** 定长时间比较：不随首个不同字节早退，防时序侧信道 */
    private boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private String str(Map<String, Object> config, String key) {
        Object v = config.get(key);
        return v == null ? null : String.valueOf(v).trim();
    }

}
