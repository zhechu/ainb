package cn.enlev.ainb.module.system.enums.permission;

import cn.hutool.core.util.StrUtil;
import lombok.Getter;

import java.util.List;

/**
 * 语义槽位
 *
 * 行过滤规则只引用槽位名（{@code {owner} = :currentUserId}），不写死列名。
 * 槽位到真实列名的映射存在 {@code sys_slot_mapping}，一张表单一份。
 * 这样同一条规则能跨表单复用，也让列名有了白名单（防注入，见 S3b）。
 *
 * <h3>候选列名的用途与边界</h3>
 *
 * {@code candidates} 只服务于 S4a 的<b>自动推断</b>：表单发布时按这个顺序
 * 在主表的物理列里找第一个命中的，推不出来就不写这个槽位。
 *
 * ⚠️ 它是「猜」，不是「约定」。猜错的代价不对称：
 * <ul>
 *   <li>没推出来 → 引用该槽位的规则被跳过并记告警 → 不过滤 → 看得到不该看的</li>
 *   <li>推错了列 → 按错误的列过滤 → 同样是错的可见范围</li>
 * </ul>
 * 两者都要靠 S8b 的规则编辑器让人工复核并覆盖。所以候选名单宁缺毋滥，
 * 只收本项目建表脚本里真实出现过的列名，不做泛化猜测。
 *
 * @author 走进新时代
 */
@Getter
public enum PermissionSlotEnum {

    /**
     * 记录归属人
     *
     * creator 排在最前：芋道的审计列约定就是它，且 {@code AutoFillConfig.creatorColumn}
     * 显式声明时会优先于本列表（显式声明 > 猜）
     */
    OWNER("owner", "记录归属人（创建者）的用户编号",
            List.of("create_user_id", "creator", "owner_id", "owner_user_id", "user_id")),

    /**
     * 记录归属部门
     */
    DEPT("dept", "记录归属部门编号",
            List.of("create_dept_id", "dept_id", "department_id", "org_id")),

    /**
     * 记录状态
     *
     * 在线表单接流程时主表必须有 status 列（见 FormDesignApi 的 flowReady 校验），
     * 所以这个槽位在流程表单上几乎总能推出来
     */
    STATUS("status", "记录状态",
            List.of("status", "audit_status", "approve_status", "state")),

    /**
     * 业务主键
     *
     * <h3>🔴 它和上面三个不是一回事，别照着它们的思路推</h3>
     *
     * owner/dept/status 是「按属性圈一批行」，而 {@code bizKey} 是
     * 「按一串**具体的主键**点名放行」。派生可见性（{@code BUILT_IN_BPM_PARTICIPANT}：
     * 他参与过的流程对应的单据）与记录共享（{@code BUILT_IN_RECORD_SHARED}：
     * 别人单独分享给他的那几条）都落在这个槽位上。
     *
     * <h3>⚠️ 候选列名只有 id，且几乎一定能推出来</h3>
     *
     * 主键列名在本项目里就是 {@code id}。更要紧的是：在线表单的
     * {@code mainPk}、原生数据集的 {@code @PermissionDataset#bizKey}
     * 都会<b>显式给出</b>它，猜只是最后的兜底。
     *
     * <h3>为什么补这个槽位很要紧</h3>
     *
     * S5c 做完派生可见性之后，{@code bizKey} 槽位映射<b>从来没有被自动登记过</b> ——
     * 全库一条都没有。而条件树求不出槽位对应的列时返回空片段，
     * 空片段又会被当成「这条 ALLOW 什么都没授予」而贡献恒假条件（TODO #28）。
     * 于是在任何数据集上配「流程参与者可见」，实际效果是<b>一行都看不到</b> ——
     * 比不配还糟，而且不报错。
     *
     * S5c 的端到端之所以是绿的，是因为那次<b>手工插了一条槽位映射</b>，
     * 验完又清掉了。
     */
    BIZ_KEY("bizKey", "业务主键",
            List.of("id"));

    /**
     * 槽位名。规则模板里用 {@code {owner}} 这种形式引用它
     */
    private final String slot;
    /**
     * 槽位说明，写进 sys_slot_mapping.description，供规则编辑器展示
     */
    private final String description;
    /**
     * 自动推断时的候选列名，按优先级从高到低
     */
    private final List<String> candidates;

    PermissionSlotEnum(String slot, String description, List<String> candidates) {
        this.slot = slot;
        this.description = description;
        this.candidates = candidates;
    }

    public static PermissionSlotEnum parse(String slot) {
        if (StrUtil.isBlank(slot)) {
            return null;
        }
        for (PermissionSlotEnum value : values()) {
            if (value.slot.equals(slot)) {
                return value;
            }
        }
        return null;
    }

}
