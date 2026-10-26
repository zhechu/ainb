package cn.enlev.ainb.module.infra.dal.dataobject.portal;

import cn.enlev.ainb.framework.tenant.core.db.TenantBaseDO;
import cn.enlev.ainb.module.infra.enums.portal.PortalTypeEnum;
import cn.enlev.ainb.module.infra.enums.portal.PortalVisibleScopeEnum;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/** Portal definition. */
@TableName("infra_portal")
@KeySequence("infra_portal_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class PortalDO extends TenantBaseDO {

    @TableId
    private Long id;
    private String name;
    private String code;
    private String description;
    /** 门户类型：0-在线设计 1-自定义链接，见 {@link PortalTypeEnum} */
    private Integer type;
    /** 链接类型：0-页面（内部路由） 1-外链，仅 type=1 时有值 */
    private Integer linkType;
    /** 链接地址，仅 type=1 时有值 */
    private String linkUrl;
    /** 显示顺序（升序，值越小越靠前） */
    private Integer sort;
    private Integer status;
    private String draftContent;
    private Integer publishedVersion;
    private Boolean isDefault;
    private Boolean enabled;
    /** 可见范围：0-所有人 1-指定角色 2-指定用户，见 {@link PortalVisibleScopeEnum} */
    private Integer visibleScope;
    /** 可见对象：角色存 code、用户存 id，逗号分隔 */
    private String visibleIds;
    private Integer schemaVersion;
}
