package cn.enlev.ainb.module.infra.controller.admin.datainterface.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "管理后台 - 接口管理 Response VO")
@Data
public class DataInterfaceRespVO {

    @Schema(description = "编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Long id;

    @Schema(description = "接口名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "订单列表查询")
    private String name;

    @Schema(description = "责任人，手填字符串", example = "张三")
    private String owner;

    @Schema(description = "接口编码", requiredMode = Schema.RequiredMode.REQUIRED, example = "order_list")
    private String code;

    @Schema(description = "来源：internal-内部 external-外部。字典 infra_form_type", example = "internal")
    private String category;

    @Schema(description = "标签，可多个。字典 infra_data_interface_tag", example = "[\"form\",\"report\"]")
    private List<String> tags;

    @Schema(description = "类型：1-SQL 2-静态JSON 3-HTTP转发", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Integer type;

    @Schema(description = "状态：0-开启 1-停用", requiredMode = Schema.RequiredMode.REQUIRED, example = "0")
    private Integer status;

    @Schema(description = "数据源配置编号", example = "0")
    private Long dataSourceConfigId;

    @Schema(description = "数据源配置")
    private String config;

    @Schema(description = "入参定义 JSON")
    private String params;

    @Schema(description = "JS 后处理脚本")
    private String script;

    @Schema(description = "SQL 动作类型：SELECT-查询 INSERT-新增 UPDATE-修改 DELETE-删除。由用户配置时选择",
            example = "SELECT")
    private String sqlStatementType;

    @Schema(description = "SQL 动作类型的中文名，列表页直接展示，避免前端再维护一份映射", example = "查询")
    private String sqlStatementTypeName;

    @Schema(description = "是否含写操作。列表页据此醒目标注", example = "false")
    private Boolean hasWriteOperation;

    @Schema(description = "是否分页：0-否 1-是", example = "0")
    private Integer hasPage;

    @Schema(description = "分页时的 COUNT SQL", example = "SELECT COUNT(*) FROM biz_order")
    private String countSql;

    @Schema(description = "HTTP 转发基础 URL", example = "http://api.example.com")
    private String baseUrl;

    @Schema(description = "HTTP 转发：是否允许内网地址", example = "0")
    private Integer allowInternalUrl;

    @Schema(description = "当前版本号", example = "3")
    private Integer version;

    @Schema(description = "是否记录调用日志：0-否 1-是", example = "1")
    private Integer logEnabled;

    @Schema(description = "备注", example = "供报表使用")
    private String remark;

    @Schema(description = "创建者", example = "admin")
    private String creator;

    @Schema(description = "创建时间", requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalDateTime createTime;

    @Schema(description = "更新者", example = "admin")
    private String updater;

    @Schema(description = "更新时间")
    private LocalDateTime updateTime;

}
