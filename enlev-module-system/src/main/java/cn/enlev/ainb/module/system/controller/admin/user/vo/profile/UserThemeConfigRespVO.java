package cn.enlev.ainb.module.system.controller.admin.user.vo.profile;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 当前用户主题配置响应。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserThemeConfigRespVO {

    /** 主题与布局配置 JSON */
    private String themeConfig;

}
