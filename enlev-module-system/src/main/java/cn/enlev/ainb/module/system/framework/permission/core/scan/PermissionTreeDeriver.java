package cn.enlev.ainb.module.system.framework.permission.core.scan;

import cn.hutool.core.util.StrUtil;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.*;

/**
 * 按权限串命名约定推导资源树
 *
 * ## 命名约定
 *
 * 权限串格式：module:resource:action 或 module:resource（两层到三层）
 * 例如：system:user:query → 父级 system:user → 父级 system
 *
 * ## 推导结果
 *
 * 一个权限串对应一个叶子资源节点（ACTION 类型）。
 * 推导过程：
 *   1. 把权限串按 ":" 切分，逐段累加
 *   2. 每一段前缀都是一个目录节点（MENU 类型），如果它还没有资源行就创建
 *   3. 叶子节点（完整权限串）是 ACTION 类型
 *
 * @author 走进新时代
 */
public class PermissionTreeDeriver {

    /**
     * 从一批权限串推导出完整的节点列表（含中间目录节点）
     *
     * @param permissions 权限串集合，如 ["system:user:query", "system:dept:create"]
     * @return 推导出的节点，按 path 排序
     */
    public List<DerivedNode> derive(Collection<String> permissions) {
        if (permissions == null || permissions.isEmpty()) {
            return List.of();
        }
        // 用 LinkedHashMap 保证插入顺序，同时按 path 去重
        Map<String, DerivedNode> nodeMap = new LinkedHashMap<>();
        for (String permission : permissions) {
            if (StrUtil.isBlank(permission)) {
                continue;
            }
            String[] segments = permission.split(":");
            if (segments.length < 2) {
                // 只有一段的不符合命名约定，当成一层目录
                nodeMap.putIfAbsent(permission, new DerivedNode(permission, permission, DerivedNodeType.MENU, null));
                continue;
            }
            // 建中间目录节点
            StringBuilder prefix = new StringBuilder();
            for (int i = 0; i < segments.length - 1; i++) {
                if (i > 0) {
                    prefix.append(":");
                }
                prefix.append(segments[i]);
                String prefixStr = prefix.toString();
                nodeMap.putIfAbsent(prefixStr, new DerivedNode(
                        prefixStr, segments[i], DerivedNodeType.MENU,
                        i == 0 ? null : prefixStr.substring(0, prefixStr.lastIndexOf(':'))));
            }
            // 叶子 ACTION 节点
            String parentPermission = permission.substring(0, permission.lastIndexOf(':'));
            nodeMap.putIfAbsent(permission, new DerivedNode(
                    permission, segments[segments.length - 1], DerivedNodeType.ACTION, parentPermission));
        }
        return new ArrayList<>(nodeMap.values());
    }

    /**
     * 推导出的节点
     */
    @Getter
    @AllArgsConstructor
    public static class DerivedNode {

        /**
         * 节点的权限串（也是唯一标识）
         */
        private final String permission;
        /**
         * 节点名称（权限串最后一段）
         */
        private final String name;
        /**
         * 节点类型
         */
        private final DerivedNodeType type;
        /**
         * 父节点的权限串，根节点为 null
         */
        private final String parentPermission;

    }

    public enum DerivedNodeType {
        /** 目录层（中间节点）*/
        MENU,
        /** 操作权限（叶子节点）*/
        ACTION
    }

}
