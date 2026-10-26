package cn.enlev.ainb.module.bpm.controller.admin.definition.vo.form;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Schema(description = "管理后台 - 流程可用的在线表单 Response VO")
@Data
public class BpmOnlineFormRespVO {

    @Schema(description = "表单编码", requiredMode = Schema.RequiredMode.REQUIRED, example = "leave_apply")
    private String code;

    @Schema(description = "表单名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "请假申请")
    private String name;

    @Schema(description = "分类", example = "oa")
    private String category;

    @Schema(description = "主表名", example = "biz_leave")
    private String mainTable;

    @Schema(description = "表单配置 JSON")
    private String formConf;

    @Schema(description = "表单字段规则数组")
    private List<String> formFields;

    @Schema(description = "是否可用于流程", requiredMode = Schema.RequiredMode.REQUIRED, example = "true")
    private Boolean flowReady;

    @Schema(description = "不可用原因。flowReady 为 false 时非空",
            example = "主表(biz_leave)缺少 process_instance_id 列，请补加 varchar(64) 列")
    private String unavailableReason;

}
