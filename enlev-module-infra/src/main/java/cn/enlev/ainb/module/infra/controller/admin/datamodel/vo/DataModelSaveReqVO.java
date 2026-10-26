package cn.enlev.ainb.module.infra.controller.admin.datamodel.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Schema(description = "管理后台 - 数据模型创建/修改 Request VO")
@Data
public class DataModelSaveReqVO {

    @Schema(description = "编号。修改时传入", example = "1")
    private Long id;

    @Schema(description = "数据源配置的编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "0")
    @NotNull(message = "数据源配置的编号不能为空")
    private Long dataSourceConfigId;

    @Schema(description = "表名", requiredMode = Schema.RequiredMode.REQUIRED, example = "biz_order")
    @NotEmpty(message = "表名不能为空")
    private String tableName;

    /*
     * ⚠️ 下面三个长度限制不是锦上添花，是防孤儿表：
     *
     * createDataModel 的顺序是「先执行 DDL、后写元数据」（DDL 失败就不留脏元数据，这是对的）。
     * 但反方向没兜住：这三个字段此前没有长度约束，超长时物理表已经建出来了，
     * 元数据 insert 才抛 MySQL 原生的 Data too long。结果是表存在、却没登记进 infra_data_model，
     * 而 createDataModel 开头就查 existsTable —— 重试直接报「表已存在」，
     * 用户彻底卡死：表既管不了也删不掉（删除走的是已登记的模型）。
     *
     * 长度取自 infra_data_model 的列定义，两边必须一致。
     */

    @Schema(description = "表描述", requiredMode = Schema.RequiredMode.REQUIRED, example = "订单")
    @NotEmpty(message = "表描述不能为空")
    @Size(max = 500, message = "表描述不能超过 500 个字符")
    private String tableComment;

    @Schema(description = "模型名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "订单模型")
    @NotEmpty(message = "模型名称不能为空")
    @Size(max = 100, message = "模型名称不能超过 100 个字符")
    private String modelName;

    @Schema(description = "备注", example = "订单主表")
    @Size(max = 500, message = "备注不能超过 500 个字符")
    private String remark;

    @Schema(description = "字段列表", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "字段列表不能为空")
    @Size(min = 1, message = "至少需要一个字段")
    @Valid
    private List<DataModelColumnSaveReqVO> columns;

    @Schema(description = "索引列表，可为空")
    @Valid
    private List<DataModelIndexSaveReqVO> indexes;

    @Schema(description = "是否已知晓风险。删除字段等有损操作需要显式确认", example = "false")
    private Boolean riskConfirmed;

    /**
     * 系统列策略，仅<b>建表</b>时生效，改表传了也会被忽略
     *
     * 不传按 TENANT 处理，与改造前行为一致。
     * 之所以不落库：物理表有没有 tenant_id 列本身就是唯一事实来源，
     * 详见 {@link cn.enlev.ainb.module.infra.enums.datamodel.SystemColumnProfileEnum} 类注释。
     */
    @Schema(description = "系统列策略：TENANT（默认，多租户）/ GLOBAL（全局表，无 tenant_id）/ LEGACY（兼容存量样板表）",
            example = "TENANT")
    private String systemColumnProfile;

    /**
     * 乐观锁版本号，<b>改表时必传</b>
     *
     * <p>取自 {@code GET /infra/data-model/structure} 返回的 {@code version} ——
     * 语义是「我这次的修改是基于我看到的那一版做的」。库里那一行的版本已经变了，
     * 说明有人在你读取之后又改过，本次提交会被拒绝（{@code DATA_MODEL_RECORD_VERSION_CONFLICT}）。
     *
     * <p>🔴 不传<b>不是</b>「跳过乐观锁」，而是直接拒绝（{@code DATA_MODEL_RECORD_VERSION_REQUIRED}）。
     *    MyBatis-Plus 的拦截器在 version 为 null 时是<b>静默跳过</b>版本条件的 ——
     *    若不拦，「忘传」的表现就是乐观锁形同没开、并发覆盖照旧发生且<b>不报错</b>，
     *    而这条路径的代价是不可回滚的 DDL。
     *
     * <p>建表（{@code id} 为空）时忽略本字段。
     */
    @Schema(description = "乐观锁版本号，改表时必传；取自「获取表结构」的返回值", example = "0")
    private Integer version;

    /**
     * 本次改表所依据的表结构指纹，取自 {@code GET /structure} 返回的 {@code schemaFingerprint}
     *
     * <p>与 {@link #version} 防的是<b>两件不同的事</b>，两个都要：
     * <ul>
     *   <li>{@code version} 防「<b>登记行</b>被人改过」（模型名称、备注、列的业务元数据…）</li>
     *   <li>{@code schemaFingerprint} 防「<b>物理表结构</b>被人绕过平台改过」（DBA 手工 SQL、迁移脚本）</li>
     * </ul>
     * 后者尤其要紧，因为它<b>不会</b>反映在 version 上：直接对库执行 ALTER 根本不经过本平台，
     * 登记行一个字节都没动。而 diff 是拿实时结构算的，于是 ALTER 照样生成、照样执行成功，
     * 只有 columnMeta / listConfig / 绑在这张表上的表单映射在悄悄失效。
     *
     * <p>不传则退回与库里存的指纹比对；两者都为空（本功能上线前建的存量模型）时放行，
     * 并在 DDL 成功后补记基线 —— 否则存量表会<b>从此改不动</b>。
     */
    @Schema(description = "本次改表所依据的表结构指纹，取自「获取表结构」的返回值", example = "9f2c…")
    @Size(max = 64, message = "表结构指纹长度不合法")
    private String schemaFingerprint;

    /**
     * 确认本次改表要删除字段
     *
     * <p>🔴 与 {@link #riskConfirmed} <b>不是一回事，也不能互相替代</b>：
     * <ul>
     *   <li>{@code riskConfirmed} = 「我看过表里的数据，确认这些数据可以永久丢失」—— 只在<b>表里有数据</b>时要</li>
     *   <li>{@code confirmDropColumns} = 「我确认我就是要删这些列」—— <b>不管表里有没有数据</b>都要</li>
     * </ul>
     * 后者补的是空表上的静默删除：改表是<b>全量同步</b>语义，提交的列表里没有的列一律进 dropColumns，
     * 于是「只想加一个字段」的直觉写法等于把其它业务列全删掉；空表上没有数据可丢，
     * 原先的 {@code validateLossyChange} 直接放行，表结构被清成一列、接口返回成功、DDL 不可回滚。
     *
     * <p>⚠️ AI 走 api 包时<b>不允许</b>把这个标记置真（{@code DataModelUpdateValidator} 会拒），
     *    理由与 {@code riskConfirmed} 完全相同：用户在计划卡片上点的是「同意执行这个计划」，
     *    不是「我确认这些列可以删」。
     */
    @Schema(description = "确认删除字段。删列一律要显式确认，不看表里有没有数据", example = "false")
    private Boolean confirmDropColumns;

}
