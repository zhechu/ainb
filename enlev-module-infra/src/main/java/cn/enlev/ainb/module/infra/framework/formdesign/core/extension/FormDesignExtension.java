package cn.enlev.ainb.module.infra.framework.formdesign.core.extension;

/**
 * 表单提交的服务端扩展点
 *
 * 三层扩展机制里的第二层之一（另一个是前端脚本，两者互斥）。
 * 实现本接口并注册为 Spring Bean，按 {@link #formCode()} 路由到对应表单。
 *
 * <h3>与前端脚本的分工</h3>
 *
 * 前端脚本管交互（显隐、联动提示、二次确认），沙箱内【没有任何后端调用能力】；
 * 服务端扩展点管业务（查库、调外部系统、跨表校验）。凡需后端参与的逻辑一律走这里。
 *
 * <h3>使用约定</h3>
 *
 * <ul>
 *   <li>校验失败请往 {@link FormSubmitContext#addError} 里加消息，不要自己抛异常 ——
 *       加进来的会被汇总后一次性抛出，用户一次看到全部问题</li>
 *   <li>要补全字段直接改 {@link FormSubmitContext#set}</li>
 *   <li>同一个 formCode 有多个实现时按 {@code @Order} 串行执行</li>
 *   <li>after 钩子里抛异常会连带回滚整个提交事务，这通常不是你想要的 ——
 *       发通知、写日志这类副作用请自己 try/catch</li>
 * </ul>
 *
 * <h3>⚠️ 表单必须切到「服务端扩展点」模式，否则本接口不会被调用</h3>
 *
 * 新建表单的默认模式是「前端脚本」。实现了本接口但没切模式，钩子一次都不会执行、
 * 也没有报错。启动时会打 WARN，设计器里也会提示 —— 见 {@link FormDesignExtensionRegistry}。
 *
 * @author 走进新时代
 */
public interface FormDesignExtension {

    /**
     * 本扩展点作用于哪张表单
     *
     * @return 表单编码，与 infra_form_design.code 对应
     */
    String formCode();

    /**
     * 新增前
     */
    default void beforeCreate(FormSubmitContext ctx) {
    }

    /**
     * 新增后。此时 ctx.primaryKey 已有值
     */
    default void afterCreate(FormSubmitContext ctx) {
    }

    /**
     * 更新前
     */
    default void beforeUpdate(FormSubmitContext ctx) {
    }

    /**
     * 更新后
     */
    default void afterUpdate(FormSubmitContext ctx) {
    }

    /**
     * 删除前
     */
    default void beforeDelete(FormSubmitContext ctx) {
    }

}
