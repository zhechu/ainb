package cn.enlev.ainb.module.system.api.tenant;

import cn.enlev.ainb.module.system.dal.dataobject.tenant.TenantDO;
import cn.enlev.ainb.module.system.service.tenant.TenantService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

/**
 * 租户联系人 API 实现类
 *
 * @author 走进新时代
 */
@Service
public class TenantContactApiImpl implements TenantContactApi {

    @Resource
    private TenantService tenantService;

    @Override
    public Long getContactUserId(Long tenantId) {
        if (tenantId == null) {
            return null;
        }
        TenantDO tenant = tenantService.getTenant(tenantId);
        return tenant == null ? null : tenant.getContactUserId();
    }

}
