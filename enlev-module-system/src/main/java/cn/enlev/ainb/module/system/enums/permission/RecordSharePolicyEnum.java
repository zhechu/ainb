package cn.enlev.ainb.module.system.enums.permission;

import cn.hutool.core.util.StrUtil;
import lombok.Getter;

/**
 * 记录共享的发起权口径（S7b-2 补）
 *
 * <h3>为什么「谁能分享」必须是一个显式配置</h3>
 *
 * 共享是<b>把数据扩散出去</b>的动作，而且是人人都能做的日常动作。
 * 不定口径的话只有两种极端：要么谁看得到谁就能转出去（扩散没有边界），
 * 要么只有管理员能分享（那这个功能等于没有）。
 *
 * 所以默认取保守的一档，需要放开的数据集<b>单独配</b>。
 *
 * <h3>⚠️ 判不出归属人时一律拒绝分享</h3>
 *
 * {@link #OWNER_ONLY} 要读记录的归属人列。读不到（没配 owner 槽位、
 * 表在别的数据源、记录不存在）时<b>拒绝</b>，不是放行 ——
 * 放行的话，一个没配好槽位的数据集会悄悄变成「人人可分享」，
 * 而这件事没有任何提示。拒绝至少是「有人会来报」的失败方式。
 *
 * @author 走进新时代
 */
@Getter
public enum RecordSharePolicyEnum {

    /**
     * 只有记录的归属人能分享（<b>默认</b>）
     *
     * 归属人 = {@code owner} 槽位映射到的那一列的值。
     * 保守但不至于让功能作废：单据的创建人转给同事看一眼是最常见的场景
     */
    OWNER_ONLY("OWNER_ONLY", "仅归属人可分享"),

    /**
     * 看得到这条记录的人都能分享
     *
     * ⚠️ 放开这一档意味着<b>共享可以被二次转发</b>：A 分享给 B，B 就能再分享给 C，
     * 而 A 不会知道。协作型的数据集（公告、知识库、共享文档）适合，
     * 业务单据一般不适合
     */
    ANY_VIEWER("ANY_VIEWER", "可见者均可分享"),

    /**
     * 禁止分享
     *
     * 给那些「本来就不该逐条扩散」的数据集用（薪资、考核、体检记录）。
     * ⚠️ 它只关掉<b>本通道</b> —— 管理员照样可以用 {@code sys_grant} 授权，
     * 这一条在界面上要说清楚，否则会被理解成「这张表锁死了」
     */
    DISABLED("DISABLED", "禁止分享");

    /**
     * 落库值。⚠️ 直接存在 {@code sys_resource.share_policy}，不要改名
     */
    private final String code;
    private final String displayName;

    RecordSharePolicyEnum(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    /**
     * 解析，解析不出来时返回<b>默认档</b>（而不是 null）
     *
     * ⚠️ 默认档必须是最保守的那个。库里出现一个不认识的值（回滚了一半的版本、
     * 手工改错），退化成 {@link #OWNER_ONLY} 是安全的；
     * 退化成 {@link #ANY_VIEWER} 就是静默放开
     */
    public static RecordSharePolicyEnum parse(String code) {
        if (StrUtil.isBlank(code)) {
            return OWNER_ONLY;
        }
        for (RecordSharePolicyEnum value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        return OWNER_ONLY;
    }

}
