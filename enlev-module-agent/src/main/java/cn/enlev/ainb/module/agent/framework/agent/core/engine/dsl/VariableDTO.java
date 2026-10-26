package cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * DSL 变量声明（§3.1）
 *
 * <p>{@link #merge} 1:1 映射 SAA {@code KeyStrategy}，由 KeyStrategyFactoryGenerator 翻译。
 *
 * @author 走进新时代
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class VariableDTO {

    /**
     * 变量键（顶层），命名 {@code ^[a-z][a-z0-9_]{0,62}$}
     */
    private String key;

    /**
     * JSON 原生类型：string / number / boolean / object / array
     */
    private String type;

    /**
     * 合并策略：replace / append / merge（默认 replace）
     */
    private String merge;

    /**
     * 是否必填（入参校验用）
     */
    private Boolean required;

}
