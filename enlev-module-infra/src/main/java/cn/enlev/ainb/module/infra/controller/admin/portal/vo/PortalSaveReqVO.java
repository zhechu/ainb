package cn.enlev.ainb.module.infra.controller.admin.portal.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class PortalSaveReqVO {

    private Long id;

    @NotEmpty(message = "门户名称不能为空")
    @Size(max = 100, message = "门户名称不能超过100个字符")
    private String name;

    @NotEmpty(message = "门户编码不能为空")
    @Pattern(regexp = "^[A-Za-z_][A-Za-z0-9_]*$", message = "门户编码只允许字母、数字、下划线，且以字母或下划线开头")
    @Size(max = 100, message = "门户编码不能超过100个字符")
    private String code;

    @Size(max = 500, message = "门户描述不能超过500个字符")
    private String description;

    /** 门户类型：0-在线设计 1-自定义链接，默认在线设计 */
    private Integer type;

    /** 链接类型：0-页面（内部路由） 1-外链，type=1 时必填 */
    private Integer linkType;

    @Size(max = 1024, message = "链接地址不能超过1024个字符")
    private String linkUrl;

    /** 显示顺序（升序，值越小越靠前） */
    private Integer sort;

    @NotNull(message = "门户配置不能为空")
    private String content;

    private Boolean isDefault;
    private Boolean enabled;

    /** 可见范围：0-所有人 1-指定角色 2-指定用户，默认所有人 */
    private Integer visibleScope;

    @Size(max = 2000, message = "可见对象不能超过2000个字符")
    private String visibleIds;
}
