package cn.enlev.ainb.framework.common.biz.system.permission;

/**
 * 权限上下文持有者（v9 §7.1 第 2 步的 context）
 *
 * <h3>它解决什么</h3>
 *
 * 字段模式的三层合并（v9 §7.1 第 7 步）里，「上下文」指的是<b>审批节点</b>：
 *
 * <pre>
 * ceiling = min(全局 subject 命中)        ← 角色红线，不能被节点突破
 * value   = 上下文 subject max ?? 上下文基线 ?? 全局 subject max ?? default
 * 最终    = min(ceiling, value)
 * </pre>
 *
 * 这套逻辑 S2b 就实现了，三条校验也有单测。但 {@code contextId} 一直<b>恒传 null</b>
 * —— 也就是说「节点上下文」这一层在线上<b>从未被触发过</b>：
 * 无论在哪个审批节点上打开表单，看到的字段模式都是全局的那一套。
 *
 * 表现是：在流程设计器里给某个节点配的字段权限，<b>配了等于没配</b>。
 * 不报错、界面正常 —— 正是本项目最贵的那类缺陷。
 *
 * <h3>为什么用 ThreadLocal 而不是方法参数</h3>
 *
 * 从「BPM 在某个任务下打开表单」到「字段模式求值」之间隔着
 * {@code FormDesignApi → FormDesignRuntimeService → FormFieldCropper → PermissionCommonApi}
 * 四层，且中间每一层都有多个重载与分支。逐层传参的话，
 * <b>中间漏传一层就是一个静默的「节点权限不生效」</b>，而它测不出来
 * （接口照常返回、字段照常显示，只是用的是全局模式）。
 *
 * 与 {@code FormRowFilter.IGNORE} 是同一个理由。
 *
 * @author 走进新时代
 */
public final class PermissionContextHolder {

    /**
     * 当前的上下文资源权限标识，如 {@code bpm:node:leave:Activity_xxx}
     */
    private static final ThreadLocal<String> CONTEXT = new ThreadLocal<>();

    private PermissionContextHolder() {
    }

    public static String get() {
        return CONTEXT.get();
    }

    /**
     * 在指定上下文里执行
     *
     * <p>用 try-finally 恢复<b>原值</b>而不是直接清空：调用可能嵌套
     * （比如审批节点里触发了另一个表单的读取），内层结束时把外层的上下文也清掉的话，
     * 外层剩下的语句会突然退回全局判定 —— 而那同样是静默的。</p>
     */
    public static <V> V execute(String contextPermission, java.util.function.Supplier<V> supplier) {
        String origin = CONTEXT.get();
        CONTEXT.set(contextPermission);
        try {
            return supplier.get();
        } finally {
            if (origin == null) {
                CONTEXT.remove();
            } else {
                CONTEXT.set(origin);
            }
        }
    }

    public static void execute(String contextPermission, Runnable runnable) {
        execute(contextPermission, () -> {
            runnable.run();
            return null;
        });
    }

}
