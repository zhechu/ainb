package cn.enlev.ainb.module.infra.dal.dataobject.datamodel;

import cn.enlev.ainb.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 数据模型 DDL 执行日志 DO
 *
 * DDL 是不可回滚的操作，出问题时需要知道「到底执行了什么」，所以每次执行都留痕，
 * 失败也记录。排查线上表结构异常时，这张表是第一手依据。
 *
 * @author 走进新时代
 */
@TableName("infra_data_model_log")
@KeySequence("infra_data_model_log_seq") // 用于 Oracle、PostgreSQL、Kingbase、DB2、H2 数据库的主键自增。如果是 MySQL 等数据库，可不写。
@Data
@EqualsAndHashCode(callSuper = true)
public class DataModelLogDO extends BaseDO {

    /**
     * 编号
     */
    private Long id;
    /**
     * 数据源配置的编号
     */
    private Long dataSourceConfigId;
    /**
     * 表名称
     */
    private String tableName;
    /**
     * 操作类型
     *
     * 枚举 {@link cn.enlev.ainb.module.infra.enums.datamodel.DataModelOperateTypeEnum}
     */
    private String operateType;
    /**
     * 实际执行的 DDL
     */
    private String executeSql;
    /**
     * 是否执行成功
     */
    private Boolean success;
    /**
     * 失败原因
     */
    private String errorMsg;
    /**
     * 执行耗时，单位：毫秒
     */
    private Integer duration;

}
