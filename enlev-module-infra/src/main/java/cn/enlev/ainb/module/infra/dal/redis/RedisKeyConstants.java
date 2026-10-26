package cn.enlev.ainb.module.infra.dal.redis;

/**
 * Infra Redis Key 常量
 *
 * @author 走进新时代
 */
public interface RedisKeyConstants {

    /**
     * 单据号的流水计数
     *
     * KEY 格式：infra:bill_template:no:{tenantId}:{scope}:{group}:{period}
     * VALUE 格式：自增整数
     *
     * <ul>
     *   <li>tenantId：租户编号，无租户上下文时为 0。租户隔离靠它，因此 infra_bill_template 表不带 tenant_id 列</li>
     *   <li>scope：计数作用域。模板模式为模板编码，内联模式为 {@code inline:{表单编码}:{字段}}</li>
     *   <li>group：分组段。不分组为 {@code _}，按前缀分组时为前缀的哈希</li>
     *   <li>period：周期段。不重置为 {@code _}，否则为 {@code 20260906} 这类周期标记 ——
     *       周期切换时 key 天然变成新的，计数从 1 开始，无需定时任务</li>
     * </ul>
     */
    String BILL_TEMPLATE_NO = "infra:bill_template:no:";

    /**
     * 单据号的预占
     *
     * KEY 格式：infra:bill_template:occupy:{tenantId}:{userId}:{scope}
     * VALUE 格式：已预占的号码
     *
     * 用户打开新增表单时预占一个号并展示出来，提交成功后核销。
     * 同一用户反复打开表单会命中同一个预占，不会烧号；
     * 放弃填写则等 TTL 到期作废（跳号，属于有意的取舍）。
     */
    String BILL_TEMPLATE_OCCUPY = "infra:bill_template:occupy:";

}
