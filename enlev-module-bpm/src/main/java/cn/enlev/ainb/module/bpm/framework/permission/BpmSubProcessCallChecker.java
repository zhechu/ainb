package cn.enlev.ainb.module.bpm.framework.permission;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.common.biz.system.permission.PermissionCommonApi;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 子流程的 SYSTEM 调起授权（v9 §4.2）
 *
 * <h3>它把一件隐式的事变成显式的</h3>
 *
 * 子流程有两种被发起的方式，现状是<b>没人区分过</b>：
 *
 * <pre>
 * 人直接发起  → 该校验「这个人有没有该子流程的 START 权限」
 * 流程调起    → 不该校验人的 START 权限（调起它的是引擎，不是人），
 *               但【要校验主流程有没有被授权调用该子流程】
 * </pre>
 *
 * 不做第二条校验的后果：<b>谁都能间接调起任何子流程</b>。
 * 只要在自己的流程里加一个 CallActivity 指向「薪资核定」，
 * 就绕开了那个子流程上所有的 START 授权 —— 而且看上去完全正常。
 *
 * <h3>为什么是 SYSTEM 身份而不是「以发起人身份」</h3>
 *
 * 子流程的发起人可能被配成主流程发起人、表单里的某个人、子流程管理员
 * （见 {@code BpmCallActivityListener}）。拿这个人去校验 START 权限是错的：
 * 他并没有「发起」这个子流程，是引擎调起的，他只是被记成了发起人。
 * 用他来校验的话，一个合法的流程编排会因为「发起人恰好没有子流程权限」而失败。
 *
 * 所以主体是 {@code SYSTEM:bpm-engine}，被校验的对象是<b>主流程</b>：
 * 「主流程 X 被授权调用子流程 Y 了吗」。
 *
 * <h3>⚠️ 存量回落：没有资源行或没有授权时放行</h3>
 *
 * 与 S5b/S5d 同一条路数。直接收紧的话，所有既有的父子流程编排会在升级瞬间
 * 全部调不起来 —— 那不是收紧权限，是把线上流程打死。
 *
 * 所以当前语义是：<b>配了 DENY 才拦，没配一律放行</b>。
 * 等管理员在 S8 的界面上把该配的配完，再由 S9 收紧成默认拒绝。
 *
 * @author 走进新时代
 */
@Component
@Slf4j
public class BpmSubProcessCallChecker {

    /**
     * 系统身份的主体编码
     *
     * 与 {@code sys_subject} 里的 SYSTEM 主体对应。引擎调起子流程时用它，
     * 而不是用「恰好被记成发起人的那个人」
     */
    public static final String SYSTEM_SUBJECT_CODE = "SYSTEM:bpm-engine";

    private static final String PROCESS_RESOURCE_PREFIX = "bpm:process:";
    private static final String ACTION_START = "START";

    @Resource
    private ObjectProvider<PermissionCommonApi> permissionApiProvider;

    private final AtomicBoolean missingWarned = new AtomicBoolean(false);

    /**
     * 校验主流程能否调起子流程
     *
     * @param parentProcessDefinitionId 主流程的定义编号
     * @param childProcessDefinitionKey 子流程的定义 key
     * @return 是否允许调起
     */
    public boolean canCall(String parentProcessDefinitionId, String childProcessDefinitionKey) {
        if (StrUtil.isBlank(childProcessDefinitionKey)) {
            return true;
        }
        PermissionCommonApi api = permissionApiProvider.getIfAvailable();
        if (api == null) {
            if (missingWarned.compareAndSet(false, true)) {
                log.error("[canCall][PermissionCommonApi 不可用，子流程调起授权当前不生效]");
            }
            return true;
        }
        // ⚠️ 只拦【显式 DENY】，基线是放行。
        //
        // 这里【不能】用 hasResourceAction 判 ALLOW —— 流程资源的 default_effect
        // 是 DENY（S5d §2.2），那样一来所有没补授权的存量父子编排会立刻全部调不起来。
        // 那不是收紧权限，是把线上流程打死。
        //
        // 所以语义是：管理员明确禁止某个子流程被引擎调起时才拦。
        // 这让「子流程调起」从一个谁都不知道的隐式行为，变成一个
        // 【存在的、可被授权界面看到、可被明确禁止】的控制点。
        // 收紧成「默认拒绝」要等管理员把该配的配完，与 TODO #30 一起处理
        boolean denied = api.hasExplicitDeny("SYSTEM", SYSTEM_SUBJECT_CODE,
                buildChildProcessPermission(childProcessDefinitionKey), ACTION_START);
        if (denied) {
            log.warn("[canCall][主流程({})调起子流程({})被显式禁止]",
                    parentProcessDefinitionId, childProcessDefinitionKey);
            return false;
        }
        return true;
    }

    /**
     * 子流程资源的权限标识
     */
    public String buildChildProcessPermission(String childProcessDefinitionKey) {
        return PROCESS_RESOURCE_PREFIX + childProcessDefinitionKey;
    }

    public String getStartAction() {
        return ACTION_START;
    }

}
