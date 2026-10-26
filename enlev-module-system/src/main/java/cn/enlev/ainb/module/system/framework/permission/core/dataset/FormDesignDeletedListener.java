package cn.enlev.ainb.module.system.framework.permission.core.dataset;

import cn.enlev.ainb.module.infra.api.formdesign.event.FormDesignDeletedEvent;
import cn.enlev.ainb.module.system.service.permission.DatasetResourceService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 表单删除 → 权限资源注销
 *
 * <p>{@link FormDesignPublishedListener} 的反向。为什么监听事件、为什么是
 * {@code @EventListener} 而不是 {@code AFTER_COMMIT}，理由与那边逐字相同：
 * 模块依赖单向，且「表单删了、权限体系里还挂着它的数据集锚点」同样属于
 * 本项目最贵的缺陷形态 —— 页面上完全看不出来。
 *
 * <h3>锚点不存在时<b>不</b>告警</h3>
 *
 * 与发布那边刻意不同：{@code onPublished} 拿不到资源 id 意味着「发布成功了但权限没生效」，
 * 是必须喊出来的异常；而删除一张从未发布过的草稿表单，压根就没有锚点可注销，
 * 这是最常见的正常路径。喊出来的话日志里全是噪音，真正该看的那条反而被淹掉。
 *
 * @author 走进新时代
 */
@Component
@Slf4j
public class FormDesignDeletedListener {

    @Resource
    private DatasetResourceService datasetResourceService;

    @EventListener
    public void onDeleted(FormDesignDeletedEvent event) {
        DatasetResourceService.UnregisterResult result =
                datasetResourceService.unregister(event.getFormCode());
        log.info("[onDeleted][表单({})注销数据集资源：资源 {} 行，授权 {} 行，槽位 {} 行]",
                event.getFormCode(), result.removedResourceCount(),
                result.removedGrantCount(), result.removedSlotCount());
    }

}
