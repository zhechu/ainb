package cn.enlev.ainb.module.infra.dal.dataobject.portal;

import cn.enlev.ainb.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/** Published portal snapshot. */
@TableName("infra_portal_version")
@KeySequence("infra_portal_version_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class PortalVersionDO extends TenantBaseDO {

    @TableId
    private Long id;
    private Long portalId;
    private Integer version;
    private String content;
    private Integer schemaVersion;
    private String publishRemark;
}
