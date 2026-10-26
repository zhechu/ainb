package cn.enlev.ainb.module.system.service.tenant;

import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.module.system.controller.admin.tenant.vo.packages.TenantPackagePageReqVO;
import cn.enlev.ainb.module.system.controller.admin.tenant.vo.packages.TenantPackageSaveReqVO;
import cn.enlev.ainb.module.system.dal.dataobject.tenant.TenantPackageDO;
import jakarta.validation.Valid;

import java.util.List;

/**
 * 租户套餐 Service 接口
 *
 * @author 芋道源码
 */
public interface TenantPackageService {

    /**
     * 创建租户套餐
     *
     * @param createReqVO 创建信息
     * @return 编号
     */
    Long createTenantPackage(@Valid TenantPackageSaveReqVO createReqVO);

    /**
     * 更新租户套餐
     *
     * @param updateReqVO 更新信息
     */
    void updateTenantPackage(@Valid TenantPackageSaveReqVO updateReqVO);

    /**
     * 更新租户套餐状态。只改 status 一个字段，因此不会比对 menuIds、
     * 也不会向该套餐下的所有租户传播角色菜单 —— 那是 {@link #updateTenantPackage(TenantPackageSaveReqVO)} 的副作用
     *
     * @param id     套餐编号
     * @param status 状态，见 CommonStatusEnum
     */
    void updateTenantPackageStatus(Long id, Integer status);

    /**
     * 删除租户套餐
     *
     * @param id 编号
     */
    void deleteTenantPackage(Long id);

    /**
     * 批量删除租户套餐
     *
     * @param ids 编号数组
     */
    void deleteTenantPackageList(List<Long> ids);

    /**
     * 获得租户套餐
     *
     * @param id 编号
     * @return 租户套餐
     */
    TenantPackageDO getTenantPackage(Long id);

    /**
     * 获得租户套餐分页
     *
     * @param pageReqVO 分页查询
     * @return 租户套餐分页
     */
    PageResult<TenantPackageDO> getTenantPackagePage(TenantPackagePageReqVO pageReqVO);

    /**
     * 校验租户套餐
     *
     * @param id 编号
     * @return 租户套餐
     */
    TenantPackageDO validTenantPackage(Long id);

    /**
     * 获得指定状态的租户套餐列表
     *
     * @param status 状态
     * @return 租户套餐
     */
    List<TenantPackageDO> getTenantPackageListByStatus(Integer status);

}
