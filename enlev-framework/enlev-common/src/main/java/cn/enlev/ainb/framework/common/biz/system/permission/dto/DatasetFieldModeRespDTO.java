package cn.enlev.ainb.framework.common.biz.system.permission.dto;

import lombok.Data;

import java.util.Collections;
import java.util.Map;

/**
 * 数据集列/字段的显示模式
 *
 * <h3>为什么是跨模块 DTO</h3>
 *
 * 在线表单运行时在 infra 模块，而权限判定在 system 模块，infra 不依赖 system。
 * 与 {@link DataRowFilterRespDTO} 同样的路数：走 enlev-common 的
 * {@code PermissionCommonApi} 通道，只传值不传行为。
 *
 * <h3>为什么用字符串而不是枚举</h3>
 *
 * 模式枚举 {@code FieldModeEnum} 定义在 system 模块内部，搬到 common 会把
 * 权限模型的内部概念暴露给所有模块。调用方只需要区分三种取值，
 * 用常量字符串足够，而且以后枚举加值也不会逼着 common 跟着改。
 *
 * @author 走进新时代
 */
@Data
public class DatasetFieldModeRespDTO {

    /**
     * 可编辑：正常下发，可改
     */
    public static final String EDITABLE = "EDITABLE";
    /**
     * 只读：正常下发，但前端置灰、服务端丢弃提交值
     */
    public static final String READONLY = "READONLY";
    /**
     * 隐藏：config 里剔除规则，数据里删掉键，写入时拒绝
     */
    public static final String HIDDEN = "HIDDEN";

    /**
     * 字段标识 → 模式
     *
     * 键与入参的 fieldKeys 一一对应；子表明细形如 {@code items.mobile}。
     * <b>查不到资源行的字段一律是 EDITABLE</b> —— v9 §5.2「按需创建」的前提是
     * 绝大多数字段本来就不落资源行，缺省必须是放行而不是拒绝。
     */
    private Map<String, String> modes;

    /**
     * 全部字段都不受限的结果
     *
     * 给「没配任何字段权限」与「权限模块不可用」两种情况共用 ——
     * 调用方拿到它之后不需要再判空，裁剪逻辑走同一条路径。
     */
    public static DatasetFieldModeRespDTO unrestricted() {
        DatasetFieldModeRespDTO dto = new DatasetFieldModeRespDTO();
        dto.setModes(Collections.emptyMap());
        return dto;
    }

    /**
     * 取某个字段的模式，缺省 EDITABLE
     */
    public String modeOf(String fieldKey) {
        if (modes == null) {
            return EDITABLE;
        }
        return modes.getOrDefault(fieldKey, EDITABLE);
    }

    /**
     * 该字段是否要从 config 与数据里整个剔除
     */
    public boolean isHidden(String fieldKey) {
        return HIDDEN.equals(modeOf(fieldKey));
    }

    /**
     * 该字段是否只读
     */
    public boolean isReadonly(String fieldKey) {
        return READONLY.equals(modeOf(fieldKey));
    }

}
