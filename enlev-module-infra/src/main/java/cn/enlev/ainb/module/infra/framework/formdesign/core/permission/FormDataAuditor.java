package cn.enlev.ainb.module.infra.framework.formdesign.core.permission;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.common.biz.system.logger.OperateLogCommonApi;
import cn.enlev.ainb.framework.common.biz.system.logger.dto.OperateLogCreateReqDTO;
import cn.enlev.ainb.framework.common.enums.UserTypeEnum;
import cn.enlev.ainb.framework.common.util.json.JsonUtils;
import cn.enlev.ainb.framework.common.util.servlet.ServletUtils;
import cn.enlev.ainb.framework.security.core.util.SecurityFrameworkUtils;
import cn.enlev.ainb.framework.web.core.util.WebFrameworkUtils;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 在线表单的敏感操作审计
 *
 * <h3>审计哪两件事，为什么只有这两件</h3>
 *
 * 在线表单的读写出口很多，但绝大多数已经被「行过滤 + 字段裁剪 + 脱敏」三层挡住了 ——
 * 它们泄露的上限就是用户本来就该看到的东西，逐个记账只会把日志淹掉，
 * 真正要查的时候反而找不到。
 *
 * 值得单独留痕的是<b>能让敏感数据整块离开系统</b>的那几个动作：
 *
 * <ol>
 *   <li><b>查看明文</b>（{@code plain-field}）—— 它的全部意义就是绕过脱敏。
 *       别处看到的是 {@code 138****8000}，这里拿到的是真号码</li>
 *   <li><b>调用写型数据接口</b>（{@code invoke-button}）—— 它能触发作废单据这类
 *       不可逆操作，而入参是服务端按配置从记录里取的，事后无法从请求里还原</li>
 * </ol>
 *
 * <h3>⚠️ 绝不记录值本身</h3>
 *
 * 这是本类最要紧的一条约束。审计「谁在什么时候看了哪条记录的哪个字段」是留痕；
 * 把<b>看到的那个明文手机号也抄进日志表</b>就是二次泄露 ——
 * 敏感值从一张有脱敏保护的业务表，复制进了一张只要有「操作日志」菜单就能翻的表，
 * 而且从此不受字段权限管辖。
 *
 * 所以记录的一律是<b>字段名、参数名、记录主键</b>，不含任何字段值。
 *
 * <h3>失败不阻断业务，但必须留痕</h3>
 *
 * 审计写失败时只打 WARN，不让用户的操作失败。这是权衡：
 * 让日志故障演变成「看不了明文」会把一次运维问题放大成业务不可用。
 *
 * 但<b>不能静默吞掉</b> —— 那就成了「审计配了、没生效、没人知道」，
 * 正是本项目反复踩的那一类。所以异常一定打日志。
 *
 * @author 走进新时代
 */
@Component
@Slf4j
public class FormDataAuditor {

    /**
     * 操作模块类型。与 {@code system_operate_log.type} 对应，查询时按它筛
     */
    private static final String LOG_TYPE = "FORM_DATA_SENSITIVE";

    public static final String SUB_TYPE_PLAIN_VIEW = "查看脱敏字段明文";
    public static final String SUB_TYPE_PLAIN_DENIED = "查看明文被拒";
    public static final String SUB_TYPE_BUTTON_INVOKE = "调用自定义按钮数据接口";

    /**
     * 只在第一次缺失时告警，避免刷屏。口径同 {@link FormFieldCropper}
     */
    private final AtomicBoolean missingWarned = new AtomicBoolean(false);

    /**
     * 用 ObjectProvider 的理由同 {@link FormFieldCropper}：本模块不依赖 system 模块。
     * 操作日志的实现在 system 里，这里只认 enlev-common 暴露的接口
     */
    private final ObjectProvider<OperateLogCommonApi> operateLogApiProvider;

    public FormDataAuditor(ObjectProvider<OperateLogCommonApi> operateLogApiProvider) {
        this.operateLogApiProvider = operateLogApiProvider;
    }

    /**
     * 记一次明文查看
     *
     * @param field 字段名。⚠️ 只记名字，不记值
     */
    public void plainFieldViewed(String formCode, Object pkValue, String field) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("formCode", formCode);
        extra.put("field", field);
        extra.put("pk", String.valueOf(pkValue));
        record(SUB_TYPE_PLAIN_VIEW, pkValue,
                String.format("查看了表单「%s」中记录 %s 的字段「%s」的明文", formCode, pkValue, field),
                extra);
    }

    /**
     * 记一次被拒的明文查看
     *
     * <p>被拒也要记 —— 而且它比成功那条更值得看。
     * 一个用户连着去点自己无权查看的字段，是探测行为的典型形状，
     * 只记成功的话这条线索完全不存在。
     *
     * @param mode 该字段当时的权限模式（HIDDEN / READONLY）
     */
    public void plainFieldDenied(String formCode, Object pkValue, String field, String mode) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("formCode", formCode);
        extra.put("field", field);
        extra.put("pk", String.valueOf(pkValue));
        extra.put("fieldMode", mode);
        record(SUB_TYPE_PLAIN_DENIED, pkValue,
                String.format("尝试查看表单「%s」中记录 %s 的字段「%s」的明文，因权限模式为 %s 被拒绝",
                        formCode, pkValue, field, mode),
                extra);
    }

    /**
     * 记一次自定义按钮的数据接口调用
     *
     * @param paramNames 入参的<b>名字</b>。⚠️ 不要传值进来 ——
     *                   入参是服务端从记录里取的，里面完全可能有手机号、账号
     */
    public void buttonInvoked(String formCode, String buttonKey, String interfaceCode,
                              Object pkValue, Collection<String> paramNames) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("formCode", formCode);
        extra.put("buttonKey", buttonKey);
        extra.put("interfaceCode", interfaceCode);
        extra.put("pk", String.valueOf(pkValue));
        extra.put("paramNames", paramNames);
        record(SUB_TYPE_BUTTON_INVOKE, pkValue,
                String.format("在表单「%s」上点击按钮「%s」，调用数据接口「%s」处理记录 %s",
                        formCode, buttonKey, interfaceCode, pkValue),
                extra);
    }

    private void record(String subType, Object pkValue, String action, Map<String, Object> extra) {
        OperateLogCommonApi api = operateLogApiProvider.getIfAvailable();
        if (api == null) {
            // 真实部署里不会走到这里；走到了说明操作日志模块没起来。
            // 此刻敏感操作正在【不留痕】地发生，必须喊出来
            if (missingWarned.compareAndSet(false, true)) {
                log.error("[record][OperateLogCommonApi 不可用，在线表单的敏感操作审计当前未生效！"
                        + "若非单元测试环境，请检查 enlev-module-system 是否已装配]");
            }
            return;
        }
        try {
            Long userId = SecurityFrameworkUtils.getLoginUserId();
            if (userId == null) {
                // userId 是 @NotNull，没有登录上下文就构造不出合法的日志。
                // 但这种情况本身就值得记一笔 —— 敏感接口按说进不来
                log.warn("[record][没有登录上下文，跳过审计：{}]", action);
                return;
            }
            OperateLogCreateReqDTO dto = new OperateLogCreateReqDTO();
            dto.setTraceId(cn.enlev.ainb.framework.common.util.monitor.TracerUtils.getTraceId());
            dto.setUserId(userId);
            Integer userType = WebFrameworkUtils.getLoginUserType();
            dto.setUserType(userType != null ? userType : UserTypeEnum.ADMIN.getValue());
            dto.setType(LOG_TYPE);
            dto.setSubType(subType);
            // bizId 是记录主键。表单主键多用雪花 ID，解析不了时退回 0 ——
            // 解析失败不该让整条审计丢掉，主键原文在 extra 里还留着一份
            dto.setBizId(parseBizId(pkValue));
            dto.setAction(action);
            dto.setExtra(JsonUtils.toJsonString(extra));

            HttpServletRequest request = ServletUtils.getRequest();
            // 这四个字段在 DTO 里都是必填。取不到时给占位值而不是放弃整条记录 ——
            // 「因为拿不到 UA 所以没记下谁看了明文」是说不过去的
            dto.setRequestMethod(request != null ? request.getMethod() : "UNKNOWN");
            dto.setRequestUrl(request != null ? request.getRequestURI() : "UNKNOWN");
            dto.setUserIp(StrUtil.blankToDefault(ServletUtils.getClientIP(), "UNKNOWN"));
            dto.setUserAgent(StrUtil.blankToDefault(ServletUtils.getUserAgent(), "UNKNOWN"));

            api.createOperateLogAsync(dto);
        } catch (Exception e) {
            // 审计失败不阻断业务，但绝不静默
            log.warn("[record][写审计日志失败，业务继续：{}]", action, e);
        }
    }

    private Long parseBizId(Object pkValue) {
        if (pkValue instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(pkValue));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

}
