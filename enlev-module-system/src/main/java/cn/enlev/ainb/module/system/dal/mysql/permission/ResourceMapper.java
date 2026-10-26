package cn.enlev.ainb.module.system.dal.mysql.permission;

import cn.enlev.ainb.framework.common.enums.CommonStatusEnum;
import cn.enlev.ainb.framework.mybatis.core.mapper.BaseMapperX;
import cn.enlev.ainb.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.enlev.ainb.module.system.dal.dataobject.permission.ResourceDO;
import java.util.Collection;
import cn.enlev.ainb.module.system.enums.permission.ResourceTypeEnum;
import org.apache.ibatis.annotations.Mapper;

import java.util.Collection;
import java.util.List;

@Mapper
public interface ResourceMapper extends BaseMapperX<ResourceDO> {

    default List<ResourceDO> selectListByPermission(String permission) {
        return selectList(new LambdaQueryWrapperX<ResourceDO>()
                .eq(ResourceDO::getPermission, permission)
                .eq(ResourceDO::getStatus, 0)); // 只取启用的
    }

    /**
     * 按一批权限标识查资源
     *
     * 给字段裁剪用：一张表单几十个字段，逐个查会把列表页拖垮，
     * 所以一次查回来再在内存里分配。同样只取启用的，与单个版本保持一致。
     */
    default List<ResourceDO> selectListByPermissions(Collection<String> permissions) {
        if (permissions == null || permissions.isEmpty()) {
            return List.of();
        }
        return selectList(new LambdaQueryWrapperX<ResourceDO>()
                .in(ResourceDO::getPermission, permissions)
                .eq(ResourceDO::getStatus, 0));
    }

    default ResourceDO selectByMenuId(Long menuId) {
        return selectOne(ResourceDO::getMenuId, menuId);
    }

    /**
     * 按一批菜单编号查资源
     *
     * 给租户套餐「菜单集合 → 资源集合」的换算用。逐个 selectByMenuId 的话，
     * 一个套餐几百个菜单就是几百次 round-trip，而套餐重算会遍历租户下每个角色
     */
    default List<ResourceDO> selectListByMenuIds(Collection<Long> menuIds) {
        if (menuIds == null || menuIds.isEmpty()) {
            return List.of();
        }
        return selectList(new LambdaQueryWrapperX<ResourceDO>()
                .in(ResourceDO::getMenuId, menuIds));
    }

    default List<ResourceDO> selectListByParentId(Long parentId) {
        return selectList(ResourceDO::getParentId, parentId);
    }

    /**
     * 查某路径下的全部后代（含自身），用于物化路径的级联更新
     *
     * SQL 等价于：WHERE path LIKE CONCAT(:pathPrefix, '%')
     */
    default List<ResourceDO> selectDescendantsByPathPrefix(String pathPrefix) {
        return selectList(new LambdaQueryWrapperX<ResourceDO>()
                .likeRight(ResourceDO::getPath, pathPrefix));
    }

    /**
     * 按物化路径精确查一行
     *
     * 给上溯祖先用。用它而不是 {@link #selectDescendantsByPathPrefix} 再过滤：
     * 后者对浅层路径（如 {@code /root/infra/}）会把整棵子树拉回来，
     * 而我们只要那一个节点。
     *
     * 返回 List 而不是单行：物化路径理论上唯一，但真出现重复时
     * {@code selectOne} 会直接抛异常 —— 把一次数据异常升级成「鉴权整个不可用」，
     * 代价比取第一行大得多。
     */
    default List<ResourceDO> selectListByPath(String path) {
        return selectList(ResourceDO::getPath, path);
    }

    /**
     * 按路径集合批量查资源（S9 性能优化）
     *
     * 用于一次查出祖先链的全部节点，把 N 次 round-trip 压缩成 1 次。
     * 返回的行无序，调用方自行按深度排序
     */
    default List<ResourceDO> selectListByPaths(Collection<String> paths) {
        if (paths == null || paths.isEmpty()) {
            return java.util.List.of();
        }
        return selectList(new cn.enlev.ainb.framework.mybatis.core.query.LambdaQueryWrapperX<ResourceDO>()
                .inIfPresent(ResourceDO::getPath, paths));
    }

    default Long selectCountByParentId(Long parentId) {
        return selectCount(ResourceDO::getParentId, parentId);
    }

    /**
     * 按权限标识查单个资源（含停用的）
     *
     * 与 {@link #selectListByPermission} 的区别是<b>不过滤 status</b>：
     * 登记数据集资源时要找的是「这个权限串有没有被占用」，
     * 停用的行同样占位 —— 漏掉它会插出第二行同权限串的资源，
     * 而鉴权时两行谁生效就说不准了
     */
    default ResourceDO selectByPermissionIncludeDisabled(String permission) {
        return selectOne(new LambdaQueryWrapperX<ResourceDO>()
                .eq(ResourceDO::getPermission, permission)
                .last("limit 1"));
    }

    /**
     * 按物理表名查数据集资源
     *
     * 行级规则拦到一张表时的反查入口。一张表可能被多张表单绑定，所以返回列表
     */
    default List<ResourceDO> selectListByDatasetTable(String tableName) {
        return selectList(new LambdaQueryWrapperX<ResourceDO>()
                .eq(ResourceDO::getType, ResourceTypeEnum.DATASET.getCode())
                .eq(ResourceDO::getDatasetTable, tableName)
                .eq(ResourceDO::getStatus, CommonStatusEnum.ENABLE.getStatus()));
    }

    /**
     * 查全部数据集资源（启动时重建表名注册表用）
     */
    default List<ResourceDO> selectListByTypeDataset() {
        return selectList(new LambdaQueryWrapperX<ResourceDO>()
                .eq(ResourceDO::getType, ResourceTypeEnum.DATASET.getCode())
                .isNotNull(ResourceDO::getDatasetTable));
    }

    /**
     * 查某个父资源下指定类型的子资源
     *
     * 登记列/字段时的对账依据：拿它和「本次该有的清单」比对，多退少补
     */
    default List<ResourceDO> selectListByParentIdAndTypes(Long parentId, Collection<String> types) {
        return selectList(new LambdaQueryWrapperX<ResourceDO>()
                .eq(ResourceDO::getParentId, parentId)
                .in(ResourceDO::getType, types));
    }

}
