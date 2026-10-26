package cn.enlev.ainb.module.ai.controller.admin.model.vo.apikey;

import com.mzt.logapi.starter.annotation.DiffLogField;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import java.util.*;
import jakarta.validation.constraints.*;

@Schema(description = "管理后台 - AI API 密钥新增/修改 Request VO")
@Data
public class AiApiKeySaveReqVO {

    @Schema(description = "编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "23538")
    private Long id;

    @Schema(description = "名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "文心一言")
    @NotEmpty(message = "名称不能为空")
    @DiffLogField(name = "名称")
    private String name;

    // 🔴 这里【刻意不加】@DiffLogField：操作日志是明文落库的（system_operate_log.action），
    //    一旦给 apiKey 打上注解，厂商密钥就永久进了库、进了备份、进了任何一次日志导出 ——
    //    与「厂商 API 密钥绝不落盘」同一条纪律。_DIFF 只输出打了注解的字段，所以不打就是**结构性**排除，
    //    不依赖谁记得去脱敏。AiApiKeyServiceImpl 里还额外把进日志上下文的旧对象 apiKey 置空，双保险。
    @Schema(description = "密钥", requiredMode = Schema.RequiredMode.REQUIRED, example = "ABC")
    @NotEmpty(message = "密钥不能为空")
    private String apiKey;

    @Schema(description = "平台", requiredMode = Schema.RequiredMode.REQUIRED, example = "OpenAI")
    @NotEmpty(message = "平台不能为空")
    @DiffLogField(name = "平台")
    private String platform;

    // url 要审计：本轮链首 77 的确定性失败原文就是「url error, please check url！」，
    // 而它改没改过、什么时候改的，之前一个字都查不到
    @Schema(description = "自定义 API 地址", example = "https://aip.baidubce.com")
    @DiffLogField(name = "自定义 API 地址")
    private String url;

    @Schema(description = "状态", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "状态不能为空")
    @DiffLogField(name = "状态（0=启用 / 1=停用）")
    private Integer status;

}