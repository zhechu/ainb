package cn.enlev.ainb.module.system.api.permission;

import cn.enlev.ainb.module.system.api.permission.dto.ResourceDiagnosticRespDTO;

import java.util.List;
import java.util.Set;

/**
 * 权限体系的诊断 API
 *
 * <h3>为什么单独开一个接口，而不是往 PermissionApi 里塞</h3>
 * 它们的<b>语义完全不同</b>：{@code PermissionApi} 回答「这个人能不能做这件事」，
 * 是业务判定；这里回答「这条配置为什么没生效」，是排查。
 * 前者的调用方是业务代码，后者的调用方是人（或替人排查的 AI）。
 *
 * <p>混在一起的坏处很实际：诊断方法返回的是<b>内部结构事实</b>
 *（有几行资源、租户对不对得上），业务代码不该看见这些，
 * 更不该有人拿它们去做判定。
 *
 * @author 走进新时代
 */
public interface SystemDiagnosticApi {

    /**
     * 诊断一个权限点
     *
     * @param permission 权限串，如 {@code bpm:process:oa_leave}
     * @return 诊断信息
     */
    ResourceDiagnosticRespDTO diagnoseResource(String permission);

    /**
     * 获得已登记为「原生数据集」的物理表名
     *
     * 🔴 用途：行级权限规则<b>只对这些表生效</b>。
     * 给一张没标 {@code @PermissionDataset} 的表配行级规则，
     * 规则一次都不会被调用，而且不报错 —— 这是最难反查的静默失效之一。
     *
     * @return 表名集合（小写）
     */
    Set<String> getNativeDatasetTables();

    /**
     * 获得合法的字段模式取值
     *
     * 供调用方校验配置，避免自己抄一份枚举。
     *
     * @return 合法取值，如 EDITABLE / READONLY / HIDDEN
     */
    List<String> getLegalFieldModes();

}
