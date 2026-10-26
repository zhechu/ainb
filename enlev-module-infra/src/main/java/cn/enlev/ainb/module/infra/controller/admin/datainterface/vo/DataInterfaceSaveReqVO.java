package cn.enlev.ainb.module.infra.controller.admin.datainterface.vo;

import cn.enlev.ainb.module.infra.framework.datainterface.core.param.ParamDefinition;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Schema(description = "管理后台 - 接口管理 - 创建/修改 Request VO")
@Data
public class DataInterfaceSaveReqVO {

    @Schema(description = "编号。修改时传入", example = "1")
    private Long id;

    @Schema(description = "接口名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "订单列表查询")
    @NotEmpty(message = "接口名称不能为空")
    private String name;

    @Schema(description = "责任人，手填字符串", example = "张三")
    @Size(max = 100, message = "责任人长度不能超过 100 个字符")
    private String owner;

    @Schema(description = "接口编码，调用时使用", requiredMode = Schema.RequiredMode.REQUIRED, example = "order_list")
    @NotEmpty(message = "接口编码不能为空")
    @Pattern(regexp = "^[A-Za-z_][A-Za-z0-9_]*$",
            message = "接口编码只允许字母、数字、下划线，且以字母或下划线开头")
    private String code;

    @Schema(description = "来源：internal-内部 external-外部。字典 infra_form_type", example = "internal")
    private String category;

    /**
     * 标签必填是刻意的：接口目录会越长越大，没有标签就没法按用途筛，
     * 后来的人只能靠名字猜这个接口是干什么的。允许为空的话，绝大多数接口会一直空着
     *
     * <p>个数上限对着列宽来的：{@code tags} 是 varchar(500)，存的是逗号串
     * （见 {@code StringListTypeHandler}）。字典里的标签值都是十几字符以内，
     * 20 个连逗号算不到 400 —— 留出余量给自建标签，同时保证永远不会撑爆列宽。
     * 不拦的话 MySQL 严格模式报一个用户看不懂的 Data too long，非严格模式静默截断，
     * 截断点落在某个标签中间，读回来就多出一个不存在的标签值
     */
    @Schema(description = "标签，可多个。字典 infra_data_interface_tag",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "[\"form\",\"report\"]")
    @NotEmpty(message = "标签不能为空")
    @Size(max = 20, message = "标签最多 20 个")
    private List<String> tags;

    @Schema(description = "类型：1-SQL 2-静态JSON 3-HTTP转发", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "类型不能为空")
    private Integer type;

    @Schema(description = "状态：0-开启 1-停用", example = "0")
    private Integer status;

    @Schema(description = "数据源配置编号，SQL 类型使用", example = "0")
    private Long dataSourceConfigId;

    @Schema(description = "数据源配置。SQL 类型为 SQL 文本；HTTP 类型为配置 JSON；静态类型为 JSON 数据",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty(message = "配置不能为空")
    private String config;

    /**
     * 动作类型由用户显式选择而非程序推断
     *
     * 一个接口可以配多条 SQL，程序无法可靠判断这组语句整体算什么动作，
     * 交给用户选更精确。解析器的结果只用于兜底校验（见 Service 层）。
     */
    @Schema(description = "SQL 动作类型：SELECT-查询 INSERT-新增 UPDATE-修改 DELETE-删除。SQL 类型必填",
            example = "SELECT")
    private String sqlStatementType;

    @Schema(description = "入参定义")
    @Valid
    private List<ParamDefinition> params;

    @Schema(description = "JS 后处理脚本")
    private String script;

    @Schema(description = "备注", example = "供报表使用")
    private String remark;

    @Schema(description = "是否分页：0-否 1-是（仅 SQL 查询类型有效）", example = "0")
    private Integer hasPage;

    @Schema(description = "分页时的 COUNT SQL（可选，为空则自动改写）", example = "SELECT COUNT(*) FROM biz_order WHERE status = 1")
    private String countSql;

    @Schema(description = "HTTP 转发基础 URL（可选）", example = "http://api.example.com")
    private String baseUrl;

    @Schema(description = "HTTP 转发：是否允许内网地址 0-否 1-是", example = "0")
    private Integer allowInternalUrl;

    @Schema(description = "是否记录调用日志：0-否 1-是。为空默认开启", example = "1")
    private Integer logEnabled;

    @Schema(description = "变更说明。修改时填写，会记入版本历史", example = "增加状态筛选")
    private String changeSummary;

    @Schema(description = "是否已知晓风险。SQL 含写操作时必须为 true", example = "false")
    private Boolean riskConfirmed;

}
