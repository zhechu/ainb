package cn.enlev.ainb.module.system.controller.admin.dept.vo.dept;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "管理后台 - 组织列表 Request VO")
@Data
public class DeptListReqVO {

    @Schema(description = "组织名称，模糊匹配", example = "启凌智搭")
    private String name;

    @Schema(description = "展示状态，参见 CommonStatusEnum 枚举类", example = "1")
    private Integer status;

}
