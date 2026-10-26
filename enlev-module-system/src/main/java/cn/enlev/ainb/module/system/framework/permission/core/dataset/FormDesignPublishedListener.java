package cn.enlev.ainb.module.system.framework.permission.core.dataset;

import cn.enlev.ainb.module.infra.api.formdesign.event.FormDesignPublishedEvent;
import cn.enlev.ainb.module.system.service.permission.DatasetResourceService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 表单发布 → 权限资源登记
 *
 * <h3>为什么监听事件，而不是由 infra 调过来</h3>
 *
 * 模块依赖单向：{@code enlev-module-system} 依赖 {@code enlev-module-infra}。
 * 所以本模块可以 import infra 的事件类，反过来 infra 不能 import 本模块的任何东西。
 *
 * <h3>为什么是 @EventListener 而不是 @TransactionalEventListener</h3>
 *
 * 普通监听器跑在发布的事务里：这里抛异常 → 发布整体回滚。
 *
 * 换成 {@code AFTER_COMMIT} 的话，失败时表单已经发布出去了，
 * 而它的数据集资源、敏感字段资源、语义槽位一个都没有 ——
 * 页面一切正常，只有行级/字段级权限悄悄地全都不生效。
 * 这正是本项目最贵的缺陷形态（见 CLAUDE.md）：配了、成功了、没生效、没提示。
 *
 * 代价是「权限模块出问题会挡住表单发布」。这个代价是明说的、看得见的，
 * 比静默失效好。
 *
 * @author 走进新时代
 */
@Component
@Slf4j
public class FormDesignPublishedListener {

    @Resource
    private DatasetResourceService datasetResourceService;

    @EventListener
    public void onPublished(FormDesignPublishedEvent event) {
        DatasetResourceService.RegisterResult result =
                datasetResourceService.register(event.getDescriptor());
        if (result.datasetResourceId() == null) {
            log.warn("[onPublished][表单({})没有登记出数据集资源]",
                    event.getDescriptor() != null ? event.getDescriptor().getFormCode() : null);
        }
    }

}
