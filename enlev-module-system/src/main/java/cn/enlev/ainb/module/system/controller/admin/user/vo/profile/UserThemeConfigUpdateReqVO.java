package cn.enlev.ainb.module.system.controller.admin.user.vo.profile;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 当前用户主题配置请求。 */
@Data
public class UserThemeConfigUpdateReqVO {

    /** 主题与布局配置 JSON */
    @NotBlank
    @Size(max = 100000)
    private String themeConfig;

}
