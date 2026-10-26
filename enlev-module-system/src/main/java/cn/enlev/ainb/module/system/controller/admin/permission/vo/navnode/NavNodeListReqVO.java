package cn.enlev.ainb.module.system.controller.admin.permission.vo.navnode;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "管理后台 - 分端导航节点列表 Request VO")
@Data
public class NavNodeListReqVO {

    @Schema(description = "端标识，参见 NavTerminalEnum 枚举类", example = "app")
    private String terminal;

    @Schema(description = "节点名称，模糊匹配", example = "用户管理")
    private String name;

    @Schema(description = "节点类型，参见 NavNodeTypeEnum 枚举类", example = "3")
    private Integer nodeType;

    @Schema(description = "状态，参见 CommonStatusEnum 枚举类", example = "0")
    private Integer status;

}
