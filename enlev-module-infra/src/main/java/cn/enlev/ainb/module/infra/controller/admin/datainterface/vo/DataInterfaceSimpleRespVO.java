package cn.enlev.ainb.module.infra.controller.admin.datainterface.vo;

import cn.enlev.ainb.module.infra.framework.datainterface.core.param.ParamDefinition;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 数据接口精简信息
 *
 * 给「选一个接口」的场景用（表单控件的选项数据源、门户组件等）。
 * 不下发 config / script —— 那是接口的实现细节，选接口的人不需要、也不该看到。
 * params 必须下发，调用方要知道有哪些入参才能配置参数绑定。
 */
@Schema(description = "管理后台 - 接口管理 - 精简 Response VO")
@Data
public class DataInterfaceSimpleRespVO {

    @Schema(description = "编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Long id;

    @Schema(description = "接口名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "订单列表查询")
    private String name;

    @Schema(description = "接口编码，运行时按它调用", requiredMode = Schema.RequiredMode.REQUIRED, example = "order_list")
    private String code;

    @Schema(description = "来源：internal-内部 external-外部。字典 infra_form_type", example = "internal")
    private String category;

    @Schema(description = "类型：1-SQL 2-静态JSON 3-HTTP转发", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Integer type;

    @Schema(description = "是否分页：0-否 1-是", example = "0")
    private Integer hasPage;

    @Schema(description = "当前版本号，前端据此让选项缓存自动失效", example = "3")
    private Integer version;

    @Schema(description = "入参定义")
    private List<ParamDefinition> params;

    @Schema(description = "备注", example = "供报表使用")
    private String remark;

}
