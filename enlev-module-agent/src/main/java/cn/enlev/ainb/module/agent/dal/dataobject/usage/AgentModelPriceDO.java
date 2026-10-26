package cn.enlev.ainb.module.agent.dal.dataobject.usage;

import cn.enlev.ainb.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * AI 助手模型单价 DO
 *
 * <h3>⚠️ 继承 {@link BaseDO} 而不是 TenantBaseDO</h3>
 * 单价是平台与厂商之间的事实，与租户无关 —— 同一个模型不会因为换个租户就换个进价。
 * 做成分租户的话，新开一个租户就得把全部单价复制一遍，
 * 漏了的表现是「这个租户的账单金额全是 0」。
 *
 * <h3>🔴 改价用新增，不要 UPDATE</h3>
 * 按 {@code effective_from} 存多行。UPDATE 旧行会让历史账单跟着变，
 * 而账单的第一属性是可复算。
 *
 * @author 走进新时代
 */
@TableName("agent_model_price")
@Data
@EqualsAndHashCode(callSuper = true)
public class AgentModelPriceDO extends BaseDO {

    @TableId
    private Long id;

    /**
     * 平台，如 TongYi
     */
    private String platform;

    /**
     * 模型标识，如 qwen3.7-plus
     */
    private String modelName;

    /**
     * 输入单价（元 / 1000 token）
     *
     * 单位刻意是「每千 token」而不是「每 token」：厂商报价普遍是这个口径，
     * 换算成每 token 会得到 0.000004 这种数，人核对时极易数错位数。
     */
    private BigDecimal promptPrice;

    /**
     * 输出单价（元 / 1000 token）
     */
    private BigDecimal completionPrice;

    /**
     * 币种
     */
    private String currency;

    /**
     * 生效时间（含）
     */
    private LocalDateTime effectiveFrom;

    /**
     * 备注
     */
    private String remark;

}
