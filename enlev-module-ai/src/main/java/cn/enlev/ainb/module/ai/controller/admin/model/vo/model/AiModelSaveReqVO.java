package cn.enlev.ainb.module.ai.controller.admin.model.vo.model;

import cn.enlev.ainb.module.ai.enums.model.AiModelTypeEnum;
import cn.enlev.ainb.module.ai.enums.model.AiPlatformEnum;
import cn.enlev.ainb.framework.common.enums.CommonStatusEnum;
import cn.enlev.ainb.framework.common.validation.InEnum;
import com.mzt.logapi.starter.annotation.DiffLogField;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Schema(description = "管理后台 - API 模型新增/修改 Request VO")
@Data
public class AiModelSaveReqVO {

    // 🔴 下面这些 @DiffLogField 是「模型被谁改过」的唯一凭据：_DIFF 只会输出**打了这个注解**的字段，
    //    没打的一律不进操作日志 —— 所以「哪些字段可审计」在这里是**显式白名单**，不是默认全开。
    //    加它们的实测代价：2026-10-08 轮10 里模型 76 在 T1 跑到一半被停用，一个任务里出现 3 个模型、
    //    27 分钟真跑整轮作废，而 system_operate_log 在 21:40 之后 0 行 —— 事后连「谁改的」都问不出来。
    //    status 那条刻意把极性写进字段名：0=启用 / 1=停用 是反直觉的，本轮已经有人（我）读反过一次，
    //    整份「启用模型清单」为此作废。

    @Schema(description = "编号", example = "2630")
    private Long id;

    @Schema(description = "API 秘钥编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "22042")
    @NotNull(message = "API 秘钥编号不能为空")
    @DiffLogField(name = "API 秘钥编号")
    private Long keyId;

    @Schema(description = "模型名字", requiredMode = Schema.RequiredMode.REQUIRED, example = "张三")
    @NotEmpty(message = "模型名字不能为空")
    @DiffLogField(name = "模型名字")
    private String name;

    @Schema(description = "模型标识", requiredMode = Schema.RequiredMode.REQUIRED, example = "gpt-3.5-turbo-0125")
    @NotEmpty(message = "模型标识不能为空")
    @DiffLogField(name = "模型标识")
    private String model;

    @Schema(description = "模型平台", requiredMode = Schema.RequiredMode.REQUIRED, example = "OpenAI")
    @NotEmpty(message = "模型平台不能为空")
    @InEnum(AiPlatformEnum.class)
    @DiffLogField(name = "模型平台")
    private String platform;

    @Schema(description = "模型类型", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "模型类型不能为空")
    @InEnum(AiModelTypeEnum.class)
    @DiffLogField(name = "模型类型")
    private Integer type;

    @Schema(description = "排序", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "排序不能为空")
    @DiffLogField(name = "排序（候选链链首由 sort ASC, id ASC 决定）")
    private Integer sort;

    @Schema(description = "状态", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @InEnum(CommonStatusEnum.class)
    @NotNull(message = "状态不能为空")
    @DiffLogField(name = "状态（0=启用 / 1=停用）")
    private Integer status;

    @Schema(description = "温度参数", example = "1")
    @DiffLogField(name = "温度参数")
    private Double temperature;

    @Schema(description = "单条回复的最大 Token 数量", example = "4096")
    @DiffLogField(name = "单条回复的最大 Token 数量")
    private Integer maxTokens;

    @Schema(description = "上下文的最大 Message 数量", example = "8192")
    @DiffLogField(name = "上下文的最大 Message 数量")
    private Integer maxContexts;

}