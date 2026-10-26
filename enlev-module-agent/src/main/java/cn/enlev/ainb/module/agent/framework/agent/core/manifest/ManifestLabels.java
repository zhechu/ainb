package cn.enlev.ainb.module.agent.framework.agent.core.manifest;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.module.infra.api.formdesign.FormDesignApi;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 机器码 → 中文名的查表，一次清单构建里<b>最多各查一遍</b>
 *
 * <h3>为什么不把中文名写进抽取器</h3>
 * DSL 里存的是 {@code widget:"select"}、{@code operator:"LIKE"} 这类机器码。清单要给人看，
 * 就得翻成「下拉选择」「像」。这份对照表在 infra 已经有权威副本
 * （控件契约 JSON、{@code OperatorEnum}），S1 的名称目录也是从这两个只读出口取的。
 * 抽取器自己抄一份就是第三份副本 —— 而抄出来的那份漂了不报错，
 * 表现是「清单把 select 显示成 select」，用户看不出哪里不对。
 *
 * <h3>为什么懒加载而不是构建时一律先查</h3>
 * 只有表单类节点需要控件名，只有查询类参数需要操作符名。一份全是建表节点的 DSL
 * 不该为它用不上的两张表付两次跨模块调用。
 *
 * <h3>🔴 查不到就原样返回机器码，绝不抛异常</h3>
 * 清单是给人看的诊断物，它自己绝不能成为新的失败点（与
 * {@link ToolChangeExtractorRegistry#get} 返回 null 而非抛异常同理）。
 * 显示 {@code select} 比整个清单接口 500 好得多 —— 前者用户还能自己去查，
 * 后者连「将会发生什么」都看不到。
 *
 * <p>⚠️ 非线程安全，且<b>刻意</b>如此：它只活在一次 {@code build()} 调用里，
 * 由 {@link ManifestContext} 持有，不跨线程、不做单例缓存。
 * 真要跨请求缓存，得先回答「控件契约改了怎么失效」，而它现在从 classpath JSON 读、
 * 运行期不变，缓存收益为零。
 *
 * @author 走进新时代
 */
@Slf4j
public class ManifestLabels {

    /**
     * 可为 null：纯单测里不需要真去查 infra，此时所有查表都回落机器码
     */
    private final FormDesignApi formDesignApi;

    private Map<String, String> widgets;
    private Map<String, String> operators;

    public ManifestLabels(FormDesignApi formDesignApi) {
        this.formDesignApi = formDesignApi;
    }

    /**
     * 控件 type → 中文名，查不到返回 type 本身
     */
    public String widgetLabel(String type) {
        if (StrUtil.isBlank(type)) {
            return null;
        }
        return widgets().getOrDefault(type, type);
    }

    /**
     * 查询操作符 code → 中文名，查不到返回 code 本身
     */
    public String operatorLabel(String operator) {
        if (StrUtil.isBlank(operator)) {
            return null;
        }
        return operators().getOrDefault(operator, operator);
    }

    private Map<String, String> widgets() {
        if (widgets == null) {
            widgets = load(() -> {
                Map<String, String> map = new LinkedHashMap<>();
                for (FormDesignApi.WidgetSimpleRespDTO w : formDesignApi.getWidgetContracts()) {
                    if (w != null && StrUtil.isNotBlank(w.getType())) {
                        map.putIfAbsent(w.getType(), StrUtil.blankToDefault(w.getLabel(), w.getType()));
                    }
                }
                return map;
            }, "控件契约");
        }
        return widgets;
    }

    private Map<String, String> operators() {
        if (operators == null) {
            operators = load(() -> {
                Map<String, String> map = new LinkedHashMap<>();
                for (FormDesignApi.SearchOperatorRespDTO op : formDesignApi.getSearchOperatorList()) {
                    if (op != null && StrUtil.isNotBlank(op.getOperator())) {
                        map.putIfAbsent(op.getOperator(), StrUtil.blankToDefault(op.getLabel(), op.getOperator()));
                    }
                }
                return map;
            }, "操作符目录");
        }
        return operators;
    }

    /**
     * 加载失败一律回落空表（于是所有查表都返回机器码），只留一条 warn
     */
    private Map<String, String> load(Loader loader, String what) {
        if (formDesignApi == null) {
            return Map.of();
        }
        try {
            return loader.load();
        } catch (Exception ex) {
            log.warn("[load][读取{}失败，清单里的机器码将不翻译：{}]", what, ex.toString());
            return Map.of();
        }
    }

    @FunctionalInterface
    private interface Loader {
        Map<String, String> load();
    }

}
