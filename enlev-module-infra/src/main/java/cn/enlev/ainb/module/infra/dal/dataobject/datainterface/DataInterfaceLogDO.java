package cn.enlev.ainb.module.infra.dal.dataobject.datainterface;

import cn.enlev.ainb.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 数据接口调用日志 DO
 *
 * execute_sql 记录**注入租户条件后的最终 SQL** —— 排查「为什么查不到数据」时，
 * 这是最直接的证据。
 *
 * @author 走进新时代
 */
@TableName("infra_data_interface_log")
@KeySequence("infra_data_interface_log_seq")
@Data
@EqualsAndHashCode(callSuper = true)
public class DataInterfaceLogDO extends BaseDO {

    /**
     * 编号
     */
    private Long id;
    /**
     * 接口编号
     */
    private Long interfaceId;
    /**
     * 接口编码，冗余便于查询
     */
    private String interfaceCode;
    /**
     * 调用方式：1-在线预览 2-接口调用
     */
    private Integer invokeType;
    /**
     * 请求参数 JSON
     */
    private String requestParams;
    /**
     * 实际执行的 SQL，含自动注入的租户条件
     */
    private String executeSql;
    /**
     * 是否成功
     */
    private Boolean success;
    /**
     * 失败原因摘要，列表页展示，最长 500 字符
     */
    private String errorMsg;
    /**
     * 失败原因完整内容，展开行显示，最长 4000 字符
     */
    private String errorDetail;
    /**
     * 返回记录数
     */
    private Integer resultCount;
    /**
     * 耗时，单位：毫秒
     */
    private Integer duration;
    /**
     * 调用人编号
     */
    private Long userId;
    /**
     * 调用 IP
     */
    private String userIp;

}
