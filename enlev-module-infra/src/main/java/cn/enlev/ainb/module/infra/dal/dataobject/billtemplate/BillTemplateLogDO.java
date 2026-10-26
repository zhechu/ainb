package cn.enlev.ainb.module.infra.dal.dataobject.billtemplate;

import cn.enlev.ainb.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 单据模板取号日志 DO
 *
 * <h3>租户隔离</h3>
 * 模板配置与取号都按租户隔离（{@link BillTemplateDO} 继承 TenantBaseDO，
 * Redis 计数 key 中含 tenantId）。日志要回答「哪个租户的谁取了哪个号」，
 * 因此本表是普通租户表：插入时由租户拦截器自动补 tenant_id，查询自动过滤。
 *
 * <h3>快照字段</h3>
 * genType / period / seq 记录的是【取号当时】的规则与进度。模板配置日后被改，
 * 日志仍能还原当时的情况；按 seq 排序即可肉眼核对连号与跳号。
 *
 * @author 走进新时代
 */
@TableName("infra_bill_template_log")
@KeySequence("infra_bill_template_log_seq")
@Data
@EqualsAndHashCode(callSuper = true)
public class BillTemplateLogDO extends BaseDO {

    /**
     * 编号
     */
    @TableId
    private Long id;
    /**
     * 模板编号
     */
    private Long templateId;
    /**
     * 模板编码，冗余便于查询（模板改名/删除后仍可追溯）
     */
    private String templateCode;
    /**
     * 生成的单据号；取号失败时为空
     */
    private String billNo;
    /**
     * 生成方式快照：1-时间流水 2-随机数 3-UUID
     *
     * 枚举 {@link cn.enlev.ainb.module.infra.enums.billtemplate.BillGenTypeEnum}
     */
    private Integer genType;
    /**
     * 本次取号的周期标记快照，如 20260920
     */
    private String period;
    /**
     * 本次消耗的流水序号（时间流水方式）；随机/UUID 为 null
     */
    private Long seq;
    /**
     * 取号来源：1-业务出号 2-表单预占
     *
     * 预占【复用】已有号不记日志 —— 那次没有消耗计数，记了反而干扰连号核对
     */
    private Integer source;
    /**
     * 是否成功
     */
    private Boolean success;
    /**
     * 失败原因摘要，最长 500 字符
     */
    private String errorMsg;
    /**
     * 取号人编号
     */
    private Long userId;

}
