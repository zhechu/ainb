package cn.enlev.ainb.module.system.api.tenant;

/**
 * 租户联系人 API
 *
 * <h3>为什么单开一个接口只放一个方法</h3>
 * 框架里的 {@code TenantCommonApi} 是<b>租户隔离机制</b>本身要用的
 *（列出租户编号、校验租户有效），被 TenantFrameworkService 之类的底层组件依赖。
 * 往里加业务查询会让那条依赖链变粗，而它是启动期就要用的。
 *
 * <p>这里要的是另一件事：<b>「出了事该通知谁」</b>。
 * 平台没有专门的「租户管理员」概念，联系人是最接近的那个。
 *
 * @author 走进新时代
 */
public interface TenantContactApi {

    /**
     * 获得租户的联系人用户编号
     *
     * @param tenantId 租户编号
     * @return 联系人用户编号；租户不存在或未设联系人时返回 null
     */
    Long getContactUserId(Long tenantId);

}
