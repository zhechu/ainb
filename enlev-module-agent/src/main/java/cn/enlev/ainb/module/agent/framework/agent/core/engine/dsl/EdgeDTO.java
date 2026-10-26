package cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.Map;

/**
 * DSL 边（§5）
 *
 * <p>静态边：只有 from/to。条件边：type=conditional，带 cases（路由键→目标节点）+ default。
 * {@code START}/{@code END} 为保留端点，编译时映射为 SAA 常量。
 *
 * @author 走进新时代
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class EdgeDTO {

    /**
     * 源节点 id，或保留字 "START"
     */
    private String from;

    /**
     * 目标节点 id，或保留字 "END"。条件边时为空，走 cases/default
     */
    private String to;

    /**
     * 边类型：为空/静态 → 普通边；"conditional" → 条件边
     */
    private String type;

    /**
     * 条件边路由表：路由键 → 目标节点 id
     */
    private Map<String, String> cases;

    /**
     * 条件边兜底目标节点 id。DSL 里字段名是 "default"（Java 关键字，故用 @JsonProperty 映射）
     */
    @JsonProperty("default")
    private String defaultTarget;

    public boolean isConditional() {
        return "conditional".equalsIgnoreCase(type);
    }

}
