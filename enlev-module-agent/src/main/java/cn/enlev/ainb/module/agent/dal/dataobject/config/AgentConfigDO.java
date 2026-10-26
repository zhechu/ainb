package cn.enlev.ainb.module.agent.dal.dataobject.config;

import cn.enlev.ainb.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * AI 助手的租户级配置 DO
 *
 * 🔴 <b>一张表只为一个开关，值得吗？值得。</b>
 *
 * <p>这个开关是「AI 助手对该租户是否可用」的唯一判据，
 * 语义上必须<b>按租户独立</b>：同一套部署里，A 租户开了不等于 B 租户也开。
 * 放在 {@code application.yaml} 里就只能是全局的，做不到这一点。
 *
 * <p>没有配置行 = 未启用。这让「默认关着」成为<b>数据层面的事实</b>，
 * 而不是依赖某个默认值写对 —— 漏配的后果是「用不了」而非「所有人都能用」。
 *
 * <p>⚠️ 继承 {@link TenantBaseDO} 而非 {@code BaseDO}：对比 {@code infra_form_design}
 * 用的是 {@code @TenantIgnore}（表单设计跨租户共享），助手的开关与会话<b>必须分租户</b>，
 * 不能照抄。
 *
 * @author 走进新时代
 */
@TableName("agent_config")
@KeySequence("agent_config_seq")
@Data
@EqualsAndHashCode(callSuper = true)
public class AgentConfigDO extends TenantBaseDO {

    /**
     * 编号
     */
    private Long id;

    /**
     * 是否启用 AI 助手
     *
     * 对标简道云「AI 实验室加入计划」：管理员显式开启，不默认给所有人。
     */
    private Boolean enabled;

    /**
     * 备注，记录开启的缘由与范围
     */
    private String remark;

}
