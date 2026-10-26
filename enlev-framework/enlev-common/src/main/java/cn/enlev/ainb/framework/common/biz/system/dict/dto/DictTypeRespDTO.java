package cn.enlev.ainb.framework.common.biz.system.dict.dto;

import cn.enlev.ainb.framework.common.enums.CommonStatusEnum;
import lombok.Data;

/**
 * 字典类型 Response DTO
 *
 * 给跨模块的调用方判断「这个字典能不能用、是什么形态」用。
 * 只暴露判断所需的属性，不带 id、备注这类各模块用不上的字段。
 *
 * @author 走进新时代
 */
@Data
public class DictTypeRespDTO {

    /**
     * 字典类型（编码）
     */
    private String type;
    /**
     * 字典名称
     */
    private String name;
    /**
     * 状态
     *
     * 枚举 {@link CommonStatusEnum}
     */
    private Integer status;
    /**
     * 节点类型：1-分组 2-字典
     *
     * 分组只用于在管理界面上归拢字典，本身不挂字典数据，不能被业务引用
     */
    private Integer nodeType;
    /**
     * 字典数据是否树形：0-平铺 1-树形
     */
    private Integer treeEnable;
    /**
     * 缓存模式：1-全量下发 2-按需懒加载
     *
     * 懒加载的字典不会随全量接口下发给前端，依赖前端本地字典缓存的场景用不了它
     */
    private Integer cacheMode;

}
