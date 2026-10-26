package cn.enlev.ainb.module.infra.dal.dataobject.datainterface;

import cn.enlev.ainb.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 数据接口版本快照 DO
 *
 * 每次修改前把「修改前的配置」存为一个版本，因此：
 * - 版本 N 的快照 = 第 N 次修改前的状态
 * - 主表当前记录 = 最新状态
 * - 回滚 = 把某版本快照写回主表，并再产生一个新版本（回滚本身也留痕）
 *
 * 存整份快照而非字段级 diff：配置项多且结构会演进，存快照最简单可靠，
 * 比对交给前端做。
 *
 * @author 走进新时代
 */
@TableName("infra_data_interface_version")
@KeySequence("infra_data_interface_version_seq")
@Data
@EqualsAndHashCode(callSuper = true)
public class DataInterfaceVersionDO extends BaseDO {

    /**
     * 编号
     */
    private Long id;
    /**
     * 接口编号
     */
    private Long interfaceId;
    /**
     * 版本号，从 1 递增
     */
    private Integer version;
    /**
     * 该版本的完整配置快照 JSON
     */
    private String snapshot;
    /**
     * 变更说明
     */
    private String changeSummary;

}
