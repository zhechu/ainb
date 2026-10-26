package cn.enlev.ainb.module.infra.controller.admin.datainterface.vo;

import cn.enlev.ainb.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "管理后台 - 接口管理 - 分页 Request VO")
@Data
public class DataInterfacePageReqVO extends PageParam {

    @Schema(description = "接口名称，模糊匹配", example = "订单")
    private String name;

    @Schema(description = "接口编码，模糊匹配", example = "order")
    private String code;

    @Schema(description = "来源：internal-内部 external-外部。字典 infra_form_type", example = "internal")
    private String category;

    /**
     * 单个标签，不是标签集合
     *
     * 「筛出同时带 A 和 B 的接口」几乎没人用，而多选的语义（AND 还是 OR）用户猜不准，
     * 猜错的代价是查出来一堆不相干的接口还以为筛选坏了。单选语义唯一：带这个标签的
     */
    @Schema(description = "标签，精确匹配一个", example = "form")
    private String tag;

    @Schema(description = "类型：1-SQL 2-静态JSON 3-HTTP转发", example = "1")
    private Integer type;

    @Schema(description = "状态：0-开启 1-停用", example = "0")
    private Integer status;

    @Schema(description = "是否只看含写操作的接口", example = "true")
    private Boolean hasWriteOperation;

    @Schema(description = "SQL 动作类型：SELECT/INSERT/UPDATE/DELETE", example = "SELECT")
    private String sqlStatementType;

}
