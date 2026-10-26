package cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * DSL 节点（§4 + 公共字段 §4.5）
 *
 * <h3>为什么 config 用宽松的 {@code Map<String,Object>} 而不是每种类型一个强类型子类</h3>
 * 节点类型有十几种、且随版本增加。若每种都建一个 config DTO，反序列化要维护一套
 * 「type → class」的多态映射（Jackson {@code @JsonTypeInfo}），新增类型就要改映射，
 * 漏改的表现是「新节点静默解析成基类、字段全空」——正是本项目反复踩的「配了不生效」。
 * 用 Map 把「按 type 取字段」的判断收口到各 NodeExecutor 里，谁用谁负责解释自己的 config，
 * 反而更不容易错。下面这组 {@code configXxx} 取值器统一处理类型转换与缺省，避免各处裸 cast。
 *
 * @author 走进新时代
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class NodeDTO {

    /**
     * 节点 id，单份 DSL 内唯一，{@code ^[a-z][a-z0-9_]{0,62}$}
     */
    private String id;

    /**
     * 节点类型：llm / tool / condition.rule / condition.llm / hitl.confirm / ...
     */
    private String type;

    private String name;

    /**
     * 类型相关配置，由各 NodeExecutor 解释
     */
    private Map<String, Object> config;

    /**
     * 失败恢复策略，覆盖全局 settings.recovery：forward / compensate
     */
    private String recovery;

    /**
     * 反做配置，仅 recovery=compensate 时用（§4.5）
     */
    private Map<String, Object> compensation;

    /**
     * 重试配置：{ maxAttempts, backoffMs }
     */
    private Map<String, Object> retry;

    /**
     * 节点级超时秒数
     */
    private Integer timeoutSeconds;

    // ==================== config 取值器（统一类型转换与缺省） ====================

    public String configStr(String key) {
        Object v = configGet(key);
        return v == null ? null : String.valueOf(v);
    }

    public String configStr(String key, String def) {
        String v = configStr(key);
        return v == null || v.isEmpty() ? def : v;
    }

    public Long configLong(String key) {
        Object v = configGet(key);
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : Long.parseLong(s);
    }

    public Integer configInt(String key, Integer def) {
        Object v = configGet(key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v != null) {
            String s = String.valueOf(v).trim();
            if (!s.isEmpty()) {
                return Integer.parseInt(s);
            }
        }
        return def;
    }

    public Double configDouble(String key) {
        Object v = configGet(key);
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v != null) {
            String s = String.valueOf(v).trim();
            if (!s.isEmpty()) {
                return Double.parseDouble(s);
            }
        }
        return null;
    }

    public boolean configBool(String key, boolean def) {
        Object v = configGet(key);
        if (v instanceof Boolean b) {
            return b;
        }
        if (v != null) {
            String s = String.valueOf(v).trim();
            if (!s.isEmpty()) {
                return Boolean.parseBoolean(s);
            }
        }
        return def;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> configMap(String key) {
        Object v = configGet(key);
        return v instanceof Map ? (Map<String, Object>) v : null;
    }

    @SuppressWarnings("unchecked")
    public List<Object> configList(String key) {
        Object v = configGet(key);
        return v instanceof List ? (List<Object>) v : List.of();
    }

    private Object configGet(String key) {
        return config == null ? null : config.get(key);
    }

}
