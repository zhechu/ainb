package cn.enlev.ainb.module.infra.api.billtemplate;

/**
 * 单据模板 API 接口
 *
 * 供其它业务模块（erp / mes / wms 等）程序化出号，替代各模块自己写死前缀的
 * {@code XxxNoRedisDAO} —— 规则改动不再需要改代码重新发布。
 *
 * 设计约定：
 * 1. 以 code 而非 id 作为入参 —— code 是稳定的业务引用，id 在环境间不一致
 * 2. 出号会消耗流水，且【不参与调用方事务的回滚】：事务回滚时号已发出，
 *    该号即跳过。归还号码在并发下会导致重号，代价远大于跳号
 *
 * @author 走进新时代
 */
public interface BillTemplateApi {

    /**
     * 生成一个单据号
     *
     * @param code 模板编码
     * @return 单据号
     * @throws cn.enlev.ainb.framework.common.exception.ServiceException 模板不存在或已停用
     */
    String generate(String code);

    /**
     * 试算范例，不消耗流水
     *
     * @param code 模板编码
     * @return 范例号码，模板不存在时返回 null
     */
    String preview(String code);

}
