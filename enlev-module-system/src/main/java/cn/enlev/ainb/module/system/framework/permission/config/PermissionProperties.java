package cn.enlev.ainb.module.system.framework.permission.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 权限体系的配置项
 *
 * @author 走进新时代
 */
@ConfigurationProperties(prefix = "enlev.permission")
@Data
public class PermissionProperties {

    /**
     * 主体相关配置
     */
    private Subject subject = new Subject();

    /**
     * 是否启用新决策引擎（sys_grant 路径）
     *
     * 默认【关闭】，保持用原来的 system_role_menu 路径，不影响现有环境。
     * 迁移脚本跑完、验证权限点都正常后，改为 true 才真正走新路径。
     * 这样可以安全地做「灰度验证 → 切换 → 观察 → 摘掉旧表」而不是一刀切。
     */
    private Boolean grantEnabled = false;

    /**
     * 是否启用「资源身份 × 动作」塌缩后的读路径
     *
     * <h3>🔴 这个开关<b>只</b>能在数据迁移落库、且自校验通过之后才翻成 true</h3>
     *
     * 打开之后，判定侧会把权限串按 {@code PermissionStringResolver} 拆成
     * 「资源身份 + 动作」再去查库（{@code system:user:create} → 查 {@code system:user}、
     * 动作 CREATE），而权限清单侧会把身份按反向展开表还原成塌缩前的原始串。
     * 这两件事都以「{@code sys_resource} 与 {@code sys_grant} 已经被重写过」为前提 ——
     * 数据没迁就打开，判定会去查一批还不存在的身份行，结果是<b>静默判成无权</b>：
     * 全程 200、无异常，只是按钮全没了、接口全 403。
     *
     * 反过来（数据迁了、开关没开）同样是错的，但错得更明显：判定按整串找资源，
     * 而塌缩行已经被软删，{@code selectListByPermission} 返回空 → 一律无权。
     *
     * 所以迁移与翻开关必须<b>同批</b>：落库 → 跑自校验（逐主体逐串 diff，差集为空）
     * → 才翻。校验不过就恢复快照，不在库里手工修
     */
    private Boolean collapseEnabled = false;

    /**
     * 启动时是否强制重跑 system_role_menu → sys_grant 的迁移
     *
     * 默认【关闭】。迁移是 insert-only 的，在旧表还有写入方的时候每次启动都跑，
     * 会让「在菜单权限里取消勾选」的那一半永远传不到 sys_grant ——
     * 表现是<b>权限显示已收回、实际没收回</b>。
     *
     * 关掉之后，{@code GrantMigrateRunner} 只在<b>没迁过的环境</b>（sys_grant 里
     * 一条 ROLE/VIEW 行都没有）自动跑一次，全新安装不受影响。
     * 需要补跑增量时再临时打开，或走 S8a 的手动触发入口
     */
    private Boolean grantMigrateOnStartup = false;

    /**
     * 派生可见性的业务主键数告警阈值
     *
     * 派生可见性把「他参与过的流程」翻译成 {@code bizKey IN (?,?,...)}，
     * 参与 N 条流程就有 N 个参数。N 很大时 SQL 会先慢下来、最终撞上
     * {@code max_allowed_packet} 报一句与业务毫无关系的错。
     *
     * ⚠️ 超过阈值时【只打 ERROR，不改变行为】。三种「处理」都是错的：
     * <ul>
     *   <li>截断 —— 静默少数据，用户看不到本该看到的单据，且无从察觉</li>
     *   <li>拒绝 —— 参与得越多看到的越少，语义上就是反的</li>
     *   <li>放行 —— 直接越权</li>
     * </ul>
     * 真正缺的不是「处理」，是「在它变成线上事故之前能被看见」。
     * 所以这里只负责让它可见，怎么治（改子查询 / 归档参与表）由人来定。
     */
    private Integer derivedKeyWarnThreshold = 5000;

    @Data
    public static class Subject {

        /**
         * 岗位是否作为授权主体
         *
         * 默认开启。岗位天然是「职责」的表达，拿它授权是常见诉求
         */
        private Boolean postEnabled = true;

        /**
         * 部门是否作为授权主体
         *
         * ⚠️ 默认【关闭】。部门的第一用途是数据范围（「本部门及以下的数据」），
         * 而不是功能授权。两者混在一起时，配置者常把「部门 A 能看部门 A 的数据」
         * 误配成「部门 A 的人拥有某功能」，而后者在组织调整时会静默扩散权限 ——
         * 一个人调进部门就自动多了一批功能权限，没有任何审批痕迹。
         *
         * 需要的租户显式打开
         */
        private Boolean deptEnabled = false;

        /**
         * 部门主体开启时，是否把祖先部门一并算进闭包
         *
         * 默认开启，符合组织树的常识：给「研发中心」授权，下属各组的人自然应该有。
         * 关掉的话「给研发中心授权」只对直属于研发中心的人生效，
         * 而这几乎一定会被配置者误解成「继承了」
         */
        private Boolean deptAncestorEnabled = true;

    }

}
