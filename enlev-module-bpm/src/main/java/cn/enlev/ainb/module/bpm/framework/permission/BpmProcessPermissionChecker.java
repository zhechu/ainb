package cn.enlev.ainb.module.bpm.framework.permission;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.common.biz.system.permission.PermissionCommonApi;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 流程的静态授权判定（v9 §7.2）
 *
 * <h3>它替代的是什么</h3>
 *
 * 流程定义上原本挂着三个 ID 列表：{@code startUserIds} / {@code startDeptIds} /
 * {@code managerUserIds}。它们是「把授权写死在业务对象上」的典型形态，问题有三：
 *
 * <ol>
 *   <li><b>表达不了角色/岗位</b>。只能点人、点部门，「所有部门经理可发起」写不出来</li>
 *   <li><b>改一次要重新部署流程</b>。人员变动时得去改流程定义</li>
 *   <li><b>与权限体系两套口径</b>。管理员在授权界面上看不到这些，
 *       排查「他为什么能发起」时根本找不到地方</li>
 * </ol>
 *
 * 换成资源 + 授权之后，「谁能发起某流程」与「谁能点某菜单」是同一套模型、
 * 同一个界面、同一条排查路径。
 *
 * <h3>✅ 存量回落已摘（S7b / TODO #30）</h3>
 *
 * S5d 时这里是「授权 OR 旧列表」。摘掉它的前提是那三个列表<b>不再是判据</b>，
 * 而是每次部署时被 {@code ProcessResourceCommonApi#syncProcessAuthorization}
 * <b>投影成授权</b>：
 *
 * <ul>
 *   <li>{@code startUserIds} → USER 主体的 START 授权</li>
 *   <li>{@code startDeptIds} → DEPT 主体的 START 授权
 *       （所以 {@code dept-enabled} 必须打开）</li>
 *   <li>{@code managerUserIds} → USER 主体的 MANAGE 授权</li>
 *   <li><b>两个发起范围都为空</b> → {@code ALL:all-users} 的 START 授权，
 *       即「人人可发起」。这一条最容易漏，漏了的话所有没限定范围的流程
 *       会在摘掉回落的瞬间变成<b>谁都发不了</b></li>
 * </ul>
 *
 * ⚠️ 于是判定处只剩一条路。这也意味着 <b>DENY 现在真的能收紧了</b> ——
 * 回落还在时「给某人配 START 的 DENY，他若仍在旧 {@code startUserIds} 里就照样发得了」。
 *
 * <h3>没被投影的两个动作</h3>
 *
 * {@code VIEW_ALL}（流程监控岗）与 {@code DESIGN}（设计权）在流程定义上<b>没有对应字段</b>，
 * 投影不出授权行，只能由管理员在授权界面上手工配。所以它们默认<b>谁都没有</b>：
 * VIEW_ALL 缺失只是监控岗看不到实例；DESIGN 缺失则由 MANAGE 兜住，见 {@link #canDesign}。
 *
 * @author 走进新时代
 */
@Component
@Slf4j
public class BpmProcessPermissionChecker {

    /**
     * 用 ObjectProvider：bpm 不直接依赖 system 的实现，
     * 且单测里只装配 bpm 的 Bean 时它不存在
     */
    @Resource
    private ObjectProvider<PermissionCommonApi> permissionApiProvider;

    private final AtomicBoolean missingWarned = new AtomicBoolean(false);

    /**
     * 动作码与资源前缀
     *
     * ⚠️ 这里是字符串常量而不是引用 system 的 {@code GrantActionEnum} ——
     * 代价是这几个值与 {@code GrantActionEnum} / {@code ProcessResourceNaming}
     * 各存了一份，改名时必须同步改。
     *
     * 这一处与 {@code SUB_FIELD_SEPARATOR} 看着同族，其实相反：分隔符那份<b>可以</b>引，
     * 因为被引的常量在 infra 的 {@code api/formdesign/dto} 包里（api 包就是给外面引的），
     * 已于台账 #101(b) 收成引用；而 {@code GrantActionEnum} 在 system 的 {@code enums/} 下、
     * {@code ProcessResourceNaming} 在 system 的 {@code framework/} 下，都不在 api 包。
     * 判据不是「跨不跨模块」，是「被引的符号在不在 api 包」——本处才是真正的残余耦合。
     */
    private static final String ACTION_START = "START";
    private static final String ACTION_MANAGE = "MANAGE";
    private static final String ACTION_VIEW_ALL = "VIEW_ALL";
    private static final String ACTION_DESIGN = "DESIGN";
    private static final String PROCESS_RESOURCE_PREFIX = "bpm:process:";

    /**
     * 能否发起某流程
     *
     * @param userId              用户编号
     * @param processDefinitionId 流程定义编号（用来反解 key）
     */
    public boolean canStart(Long userId, String processDefinitionId) {
        return hasAction(userId, processDefinitionId, ACTION_START);
    }

    /**
     * 是否某流程的管理员
     */
    public boolean isManager(Long userId, String processDefinitionId) {
        return hasAction(userId, processDefinitionId, ACTION_MANAGE);
    }

    /**
     * 能否查看该流程的全部实例（流程监控岗）
     */
    public boolean canViewAll(Long userId, String processDefinitionId) {
        return hasAction(userId, processDefinitionId, ACTION_VIEW_ALL);
    }

    /**
     * 能否设计某流程（改流程图、部署、启停）
     *
     * <h3>⚠️ 对 MANAGE 持有者，本方法返回 false</h3>
     *
     * DESIGN 在语义上是 MANAGE 的真子集（MANAGE 还能删模型、清流程数据），
     * 但 {@code sys_grant} 里<b>没有蕴含关系</b>：一行 {@code MANAGE} 授权不会被
     * {@code action = 'DESIGN'} 的查询匹配到。所以「设计权」的判定必须是
     * {@code isManager(...) || canDesign(...)}，调用方不能只看本方法。
     *
     * <h3>⚠️ 反过来不能成立</h3>
     *
     * 不要为了少写一行，把 {@code canDesign} 塞进删模型 / 清数据那条判据里 ——
     * 那是给「只能改流程图」的人顺带发了删除权，是提权。
     * 拆两档的写法见 {@code BpmModelServiceImpl#validateModelDesigner}
     */
    public boolean canDesign(Long userId, String processDefinitionId) {
        return hasAction(userId, processDefinitionId, ACTION_DESIGN);
    }

    private boolean hasAction(Long userId, String processDefinitionId, String action) {
        if (userId == null) {
            return false;
        }
        String key = parseProcessKey(processDefinitionId);
        if (key == null) {
            return false;
        }
        PermissionCommonApi api = permissionApiProvider.getIfAvailable();
        if (api == null) {
            // ⚠️ 回落已摘，这里【只能拒绝】。
            //
            // 放行的话，一次装配问题就等于把流程权限整个关掉，而且没有任何迹象；
            // 拒绝至少是「有人会立刻来报」的失败方式。ERROR 里说清了怎么恢复
            if (missingWarned.compareAndSet(false, true)) {
                log.error("[hasAction][PermissionCommonApi 不可用，流程的静态授权无法判定，"
                        + "本次按【拒绝】处理。请检查 enlev-module-system 是否已装配]");
            }
            return false;
        }
        return api.hasResourceAction(userId, PROCESS_RESOURCE_PREFIX + key, action);
    }

    /**
     * 从流程定义编号反解 key
     *
     * Flowable 的 definitionId 形如 {@code leave:1:febcddb8-...}，第一段就是 key。
     * ⚠️ 资源标识必须用 key 而不是 definitionId —— definitionId 每次部署都变，
     * 拿它当标识的话，流程一改一部署，之前配的授权就全指向了没人用的旧版本，
     * 界面上权限还在、实际已经失效
     */
    private String parseProcessKey(String processDefinitionId) {
        if (StrUtil.isBlank(processDefinitionId)) {
            return null;
        }
        int idx = processDefinitionId.indexOf(':');
        return idx > 0 ? processDefinitionId.substring(0, idx) : processDefinitionId;
    }

}
