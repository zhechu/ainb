package cn.enlev.ainb.module.system.dal.mysql.permission;

import cn.enlev.ainb.framework.mybatis.core.mapper.BaseMapperX;
import cn.enlev.ainb.module.system.dal.dataobject.permission.SlotMappingDO;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface SlotMappingMapper extends BaseMapperX<SlotMappingDO> {

    default List<SlotMappingDO> selectListByResourceId(Long resourceId) {
        return selectList(SlotMappingDO::getResourceId, resourceId);
    }

    default SlotMappingDO selectByResourceIdAndSlot(Long resourceId, String slot) {
        return selectOne(SlotMappingDO::getResourceId, resourceId,
                SlotMappingDO::getSlot, slot);
    }

    /**
     * 物理删除槽位映射（绕过 @TableLogic 软删除）
     *
     * <h3>为什么必须物理删除</h3>
     *
     * 唯一索引 {@code uk_resource_slot(tenant_id, resource_id, slot)} <b>不含 deleted 字段</b>，
     * 而 {@code BaseDO} 的软删除只把 deleted 置 1、行仍留在表里。两者叠加的后果是：
     * <ol>
     *   <li>用户删掉一个槽位（软删，行还在）</li>
     *   <li>想用同样的 slot 名重建 —— {@code selectByResourceIdAndSlot} 被 @TableLogic
     *       自动加了 {@code deleted=0}，查不到那条软删行，于是判定「不重复」放行</li>
     *   <li>INSERT 撞上唯一索引 → 500，报错信息还与「重复」无关</li>
     * </ol>
     * 表现就是<b>删掉的槽位名永远被占着，且报错看不出原因</b>。
     *
     * 槽位映射是纯配置数据，量小、无审计价值，物理删除是正确取舍。
     */
    @Delete("DELETE FROM sys_slot_mapping WHERE id = #{id}")
    int deleteByIdPhysically(@Param("id") Long id);

}
