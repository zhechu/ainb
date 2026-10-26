package cn.enlev.ainb.module.system.api.menu.dto;

import lombok.Data;

/**
 * 菜单精简信息 Response DTO
 *
 * 只带「把新菜单挂到哪个目录下」决策需要的字段 —— 调用方要的是 id 与名字的对应关系，
 * 不是整棵菜单树的全量字段。
 *
 * @author 走进新时代
 */
@Data
public class MenuSimpleRespDTO {

    /**
     * 菜单编号
     */
    private Long id;
    /**
     * 菜单名称
     */
    private String name;
    /**
     * 父菜单编号
     */
    private Long parentId;
    /**
     * 类型：1 目录 / 2 菜单 / 3 按钮
     */
    private Integer type;

}
