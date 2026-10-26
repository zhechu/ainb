package cn.enlev.ainb.module.system.controller.admin.auth.vo;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.common.validation.InEnum;
import cn.enlev.ainb.module.system.enums.social.SocialTypeEnum;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.validator.constraints.Length;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;

@Schema(description = "管理后台 - 账号密码登录 Request VO，如果登录并绑定社交用户，需要传递 social 开头的参数")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuthLoginReqVO extends CaptchaVerificationReqVO {

    @Schema(description = "账号", requiredMode = Schema.RequiredMode.REQUIRED, example = "enlevyuanma")
    @NotEmpty(message = "登录账号不能为空")
    @Length(min = 4, max = 30, message = "账号长度为 4-30 位")
    @Pattern(regexp = "^[a-zA-Z0-9]{4,30}$", message = "账号格式为数字以及字母")
    private String username;

    @Schema(description = "密码", requiredMode = Schema.RequiredMode.REQUIRED, example = "buzhidao")
    @NotEmpty(message = "密码不能为空")
    @Length(min = 4, max = 16, message = "密码长度为 4-16 位")
    private String password;

    // ========== 绑定社交登录时，需要传递如下参数 ==========

    @Schema(description = "社交平台的类型，参见 SocialTypeEnum 枚举值", requiredMode = Schema.RequiredMode.REQUIRED, example = "10")
    @InEnum(SocialTypeEnum.class)
    private Integer socialType;

    @Schema(description = "授权码", requiredMode = Schema.RequiredMode.REQUIRED, example = "1024")
    private String socialCode;

    @Schema(description = "state", requiredMode = Schema.RequiredMode.REQUIRED, example = "9b2ffbc1-7425-4155-9894-9d5c08541d62")
    private String socialState;

    // 🔴 这两条 @AssertTrue 是「绑社交用户时 socialCode / socialState 必填」的唯一判据：
    // 上面三个字段的 @Schema 都写着 requiredMode = REQUIRED，但那只是文档口径，
    // 普通账密登录压根不传它们 —— 真正决定「传了 socialType 才必填」的是这两条。
    //
    // ⚠️ 只有 isSocialCodeValid 需要 @JsonIgnore：springdoc 按 JavaBean 属性枚举请求体字段，
    // 不加就会在登录接口的文档里多出一个 socialCodeValid 布尔请求字段，照着文档填的人会以为要传。
    // isSocialState 刻意【不加】—— 它的属性名与上面那个 String 字段 socialState 撞车，
    // 而 Jackson 的 @JsonIgnore 是按「属性」生效的：标在这个 boolean getter 上，
    // 有可能把整个 socialState 属性一起忽略掉，那就真的收不到 state 了。
    // 撞车的结果是字段侧胜出（文档里 socialState 仍是 string），既没多出没标错，就别动它
    @AssertTrue(message = "授权码不能为空")
    @JsonIgnore
    public boolean isSocialCodeValid() {
        return socialType == null || StrUtil.isNotEmpty(socialCode);
    }

    @AssertTrue(message = "授权 state 不能为空")
    public boolean isSocialState() {
        return socialType == null || StrUtil.isNotEmpty(socialState);
    }

}