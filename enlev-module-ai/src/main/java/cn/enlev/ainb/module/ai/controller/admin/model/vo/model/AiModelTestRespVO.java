package cn.enlev.ainb.module.ai.controller.admin.model.vo.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * AI 模型可用性测试 Response VO
 *
 * <p>与 {@code BillTemplateTestRespVO} 同形态：扁平字段、不落库、点一次测一次。
 *
 * <p>🔴 失败<b>不抛异常</b>，而是回 {@code success=false} + 明细 —— 本端点的价值恰恰在失败明细里，
 * 抛异常会把它压成一句 message。因此本 VO 里同时存在「成功字段」与「失败字段」，
 * 前端按 {@link #success} 分流展示。
 *
 * @author 走进新时代
 */
@Schema(description = "管理后台 - AI 模型可用性测试 Response VO")
@Data
public class AiModelTestRespVO {

    @Schema(description = "是否调通", requiredMode = Schema.RequiredMode.REQUIRED, example = "true")
    private Boolean success;

    @Schema(description = "是否因超时结束（与「厂商报错」区分开）", requiredMode = Schema.RequiredMode.REQUIRED, example = "false")
    private Boolean timedOut;

    @Schema(description = "耗时，单位：毫秒", example = "1832")
    private Long durationMs;

    @Schema(description = "实际发给厂商的平台", example = "TongYi")
    private String platform;

    @Schema(description = "实际发给厂商的模型标识", example = "qwen3.7-max")
    private String model;

    @Schema(description = "路由到多模态端点还是文本端点；非通义平台为 null", example = "false")
    private Boolean multiModel;

    /**
     * 🔴 A3 之后必须回显这一格，否则「改了 {@code enlev.ai.tongyi.thinking-only-models} 到底生效没有」
     * 仍然只能靠猜：思考模式由<b>三档</b>决定（调用方显式表态 > 限死必须开 > 按端点），
     * 而本端点走的是「不表态」那一档，命中的是后两档 —— 光看 {@code success} 分不出是哪档起的作用。
     */
    @Schema(description = "实际发给厂商的思考模式开关；非通义平台为 null", example = "true")
    private Boolean enableThinking;

    @Schema(description = "模型当前启用状态（事实回显，测已禁用模型时尤其重要）", example = "0")
    private Integer modelStatus;

    @Schema(description = "密钥当前启用状态（事实回显）", example = "0")
    private Integer keyStatus;

    @Schema(description = "成功时的回复片段，截断 200 字；向量模型这一格放的是维度等可核对的事实", example = "pong")
    private String reply;

    /**
     * 🔴 只对向量模型（{@code type=5}）有值。回显它不是为了好看：
     * {@code SimpleVectorStore} 的向量文件按 <b>Java 类名</b>而不是模型名命名，
     * 所有通义 embedding 模型共用同一个 {@code simple_DashScopeEmbeddingModel.json}。
     * 换模型前先比这一格 —— 维度不一致就意味着切换会把两个不兼容的向量空间混进同一个文件，
     * 而检索不会报错，只会静默地返回不相关的段落。
     */
    @Schema(description = "向量维度；非向量模型为 null", example = "1024")
    private Integer embeddingDimensions;

    @Schema(description = "输入 token 用量；厂商未返回时为 null", example = "12")
    private Integer promptTokens;

    @Schema(description = "输出 token 用量；厂商未返回时为 null", example = "3")
    private Integer completionTokens;

    @Schema(description = "失败时的厂商原文，逐字带回、截断 500 字。不替它编病因", example = "400 - {\"code\":\"InvalidParameter\",\"message\":\"url error, please check url！\"}")
    private String errorMsg;

    @Schema(description = "失败分型：QUOTA-额度类 / CONFIG-配置类 / TRANSIENT-瞬时类；成功或本地拦截（如硬超时）时为 null", example = "CONFIG")
    private String failureReason;

}
