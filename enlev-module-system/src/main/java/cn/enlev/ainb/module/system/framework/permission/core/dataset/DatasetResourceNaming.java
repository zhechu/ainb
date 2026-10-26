package cn.enlev.ainb.module.system.framework.permission.core.dataset;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.module.system.enums.permission.ResourceTypeEnum;

/**
 * 在线表单资源的权限标识命名
 *
 * <h3>为什么数据集复用运行时菜单的权限前缀</h3>
 *
 * 表单发布时会建六个按钮权限：{@code infra:form-design-run:{code}:query} 等
 * （见 {@code FormDesignServiceImpl.buildMenuConfig}）。S2c 的约定式推导按命名约定
 * 逐段建父节点，因此 {@code infra:form-design-run:{code}} 这个节点<b>本来就会存在</b>，
 * 只是类型是 MENU。
 *
 * 数据集资源直接用这个权限串，登记时把它的类型升级成 DATASET —— 而不是另起一个
 * 权限串。另起的话，同一张表单会有两个资源节点：六个动作挂在一个下面，
 * 列/字段挂在另一个下面，祖先链断成两截，「给整张表单授权」就没法落在一个节点上了。
 *
 * <h3>列与字段为什么要分前缀</h3>
 *
 * 同一个名字既可能是列表里的一列，也可能是详情里的一个字段，两者的可见性是
 * 分开控制的（列表看不到 ≠ 详情看不到）。共用标识会让「列表里隐掉手机号」
 * 顺带把详情里的也隐掉。
 *
 * @author 走进新时代
 */
public final class DatasetResourceNaming {

    /**
     * 在线表单运行时的权限前缀，与 {@code FormDesignServiceImpl} 里生成菜单权限时一致
     */
    public static final String RUN_PERMISSION_PREFIX = "infra:form-design-run:";
    /**
     * 列的标识段
     */
    public static final String COLUMN_SEGMENT = ":col:";
    /**
     * 字段的标识段
     */
    public static final String FIELD_SEGMENT = ":fld:";

    private DatasetResourceNaming() {
    }

    /**
     * 数据集资源的权限标识（在线表单）
     */
    public static String dataset(String formCode) {
        return RUN_PERMISSION_PREFIX + formCode;
    }

    /**
     * 列/字段资源的权限标识（在线表单）
     *
     * @param type COLUMN 或 FIELD
     * @param key  列/字段标识，子表明细形如 {@code items.mobile}
     */
    public static String child(String formCode, ResourceTypeEnum type, String key) {
        return childOf(dataset(formCode), type, key);
    }

    /**
     * 列/字段资源的权限标识（<b>入参是数据集的权限标识本身</b>）
     *
     * <h3>为什么要有这一个重载</h3>
     *
     * 原生模块的数据集权限串是模块自己的前缀（{@code system:user}），
     * 不带 {@code infra:form-design-run:}。只有 {@link #child} 一个入口的话，
     * 原生模块的字段资源会被拼成 {@code infra:form-design-run:system:user:fld:mobile} ——
     * 建得出来、查得到、授权也存得下，但它和资源树上真正的那个节点不是同一个串，
     * 于是<b>配了字段权限一律不生效</b>，且没有任何报错（S7a）。
     *
     * @param datasetPermission 数据集资源的权限标识
     */
    public static String childOf(String datasetPermission, ResourceTypeEnum type, String key) {
        String segment = type == ResourceTypeEnum.COLUMN ? COLUMN_SEGMENT : FIELD_SEGMENT;
        return datasetPermission + segment + key;
    }

    /**
     * 从列/字段资源的权限标识里取回列/字段标识
     *
     * @return 取不出来时返回 null
     */
    public static String parseKey(String permission) {
        if (StrUtil.isBlank(permission)) {
            return null;
        }
        for (String segment : new String[]{COLUMN_SEGMENT, FIELD_SEGMENT}) {
            int index = permission.indexOf(segment);
            if (index >= 0) {
                return permission.substring(index + segment.length());
            }
        }
        return null;
    }

}
