package cn.enlev.ainb.module.infra.controller.admin.formdesign.vo;

import cn.enlev.ainb.module.infra.framework.formdesign.core.contract.WidgetContract;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 管理后台 - 表单控件契约 Response VO
 *
 * 直接把注册表里的契约原样下发。之所以不做字段裁剪：契约的意义就在于前后端看到的是同一份事实，
 * 中间加一层转换就又多了一处可能漂移的地方。
 *
 * @author 走进新时代
 */
@Schema(description = "管理后台 - 表单控件契约 Response VO")
@Data
public class FormWidgetContractRespVO {

    @Schema(description = "契约版本号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Integer contractVersion;

    @Schema(description = "子表控件的类型", requiredMode = Schema.RequiredMode.REQUIRED, example = "group")
    private String subTableWidgetType;

    @Schema(description = "子表内部字段拼层级 key 时的分隔符", requiredMode = Schema.RequiredMode.REQUIRED, example = ".")
    private String subFieldSeparator;

    @Schema(description = "子表控件内部规则所在的属性路径", requiredMode = Schema.RequiredMode.REQUIRED, example = "props.rule")
    private String nestedRulePath;

    @Schema(description = "布局容器类型")
    private List<String> layoutContainerTypes;

    @Schema(description = "值是 HTML 的控件类型")
    private List<String> richTextWidgetTypes;

    @Schema(description = "多值控件落库时用的分隔符", example = ",")
    private String delimitedSeparator;

    @Schema(description = "全部控件契约")
    private List<WidgetContract> widgets;

}
