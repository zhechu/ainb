package cn.enlev.ainb.module.agent.controller.admin.def.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 管理后台 - Agent 定义 复制 Request VO（需求11）
 *
 * <h3>🔴 为什么复制要单独一个 VO，而不是复用 {@link AgentDefSaveReqVO}</h3>
 * 复制的入参只有「源 id + 新编码 + 新名称」三项，其余字段（persona / modelId / knowledgeId / toolKeys /
 * description）一律<b>从源定义整份搬过来</b>——这正是「复制」的意义：不用重填一遍就能得到一个可改的新智能体。
 * 复用 SaveReqVO 就得让前端把源的 persona 等读回来再原样提交，一来一回既浪费负载，
 * 又给了「前端漏传一个字段 → 复制出来的 Agent 少了配置且没人发现」的机会。
 *
 * <h3>编码校验与 SaveReqVO 逐字一致</h3>
 * 同一个正则写两份是刻意的：Bean Validation 注解没法跨类复用，而两处不一致的表现是
 * 「新增能建出来的编码，复制却说非法」，比抄一遍更难排查。
 *
 * @author 走进新时代
 */
@Schema(description = "管理后台 - Agent 定义 复制 Request VO")
@Data
public class AgentDefCopyReqVO {

    @Schema(description = "源定义编号（任意状态都可作为复制源）", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "1")
    // 🔴 必须 @NotNull：Schema 已经声明 REQUIRED，少了校验注解就成了「文档说必填、实际能传 null」，
    //    而 null 传下去查到的是 AGENT_DEF_NOT_EXISTS —— 用户看到的是「智能体不存在」，
    //    真正的问题却是自己漏传了 id。
    @NotNull(message = "源定义编号不能为空")
    private Long id;

    @Schema(description = "新业务编码，必须未被占用", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "order_assistant_v2")
    @NotBlank(message = "业务编码不能为空")
    @Pattern(regexp = "^[a-z][a-z0-9_]{1,63}$",
            message = "业务编码必须以小写字母开头，仅含小写字母/数字/下划线，长度 2~64")
    private String code;

    @Schema(description = "新展示名", requiredMode = Schema.RequiredMode.REQUIRED, example = "订单客服（副本）")
    @NotBlank(message = "名称不能为空")
    @Size(max = 128, message = "名称长度不能超过 128")
    private String name;

}
