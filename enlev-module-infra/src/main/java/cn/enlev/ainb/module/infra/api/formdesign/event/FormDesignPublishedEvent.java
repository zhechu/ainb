package cn.enlev.ainb.module.infra.api.formdesign.event;

import cn.enlev.ainb.module.infra.api.formdesign.dto.FormResourceDescriptorDTO;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.ToString;

/**
 * 表单发布事件
 *
 * <h3>为什么是事件，而不是 infra 调 system 的 API</h3>
 *
 * 模块依赖是<b>单向</b>的：{@code enlev-module-system} 依赖 {@code enlev-module-infra}，
 * 反过来不成立。所以「发布时登记权限资源」这件事，infra 不能主动调权限模块 ——
 * 那会造成模块循环依赖。
 *
 * 用事件之后，infra 只管广播「我发布了，这是我的可授权对象清单」，
 * 谁关心谁监听。权限模块（system）的监听器 import 得到这个事件类，方向合法。
 *
 * <h3>同步 + 同事务，不是异步</h3>
 *
 * 监听方用普通 {@code @EventListener}（不是 {@code @TransactionalEventListener}），
 * 因此它跑在发布的事务里：资源登记失败 → 整个发布回滚。
 *
 * 这是刻意的。本项目最贵的缺陷形态是「保存成功了但没生效，且没有任何提示」
 * （见 CLAUDE.md）。「表单已发布、但它的数据集资源没登记」正是这种形态：
 * 页面一切正常，只有行级/字段级权限悄悄地全都不生效。宁可发布失败得明明白白。
 *
 * @author 走进新时代
 */
@Getter
@ToString
@RequiredArgsConstructor
public class FormDesignPublishedEvent {

    /**
     * 本次发布的表单的可授权对象描述
     */
    private final FormResourceDescriptorDTO descriptor;

}
