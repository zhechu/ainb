package cn.enlev.ainb.module.infra.api.datainterface.dto;

import lombok.Data;

import java.util.List;

/**
 * 数据接口精简信息 DTO（跨模块 api 层）
 *
 * 给「选一个接口 / 调一个接口」的场景用（agent 数据接口工具的目录列表）。
 * 与 infra 内部的 DataInterfaceSimpleRespVO 同一形状思路：<b>不下发 config / script</b> ——
 * 那是接口的实现细节（SQL 文本、HTTP 地址、后处理脚本），调用方不需要、也不该看到；
 * params 必须下发，调用方要知道有哪些入参才能正确传参。
 *
 * 🔴 只用 api 安全类型：writeOperation 以 service 的只读判定为权威
 * （DO 的 hasWriteOperation 列只对 SQL 类型可靠，HTTP POST 接口该列恒为 false，
 * 直接搬列会把「能写」的 HTTP 接口标成只读，误导 AI 选错工具）。
 *
 * @author 走进新时代
 */
@Data
public class DataInterfaceSimpleRespDTO {

    /**
     * 接口编码，运行时按它调用
     */
    private String code;
    /**
     * 接口名称
     */
    private String name;
    /**
     * 来源：internal-内部 external-外部，字典 infra_form_type
     */
    private String category;
    /**
     * 类型：1-SQL 2-静态JSON 3-HTTP转发
     *
     * 枚举 {@link cn.enlev.ainb.module.infra.enums.datainterface.DataInterfaceTypeEnum}
     * （此处刻意用 Integer 而非枚举类型，保持 api 层零内部依赖）
     */
    private Integer type;
    /**
     * 类型中文名（SQL 查询 / 静态 JSON / HTTP 转发），给 AI 直接读，省得它再猜数字含义
     */
    private String typeName;
    /**
     * 是否含写操作（能否经 invokeData 产生副作用）
     *
     * 以 service 的只读判定为权威：SQL 看动作类型、HTTP 看 method、静态 JSON 恒只读
     */
    private Boolean writeOperation;
    /**
     * 是否分页（仅 SQL 查询型可能为 true）
     */
    private Boolean hasPage;
    /**
     * 备注
     */
    private String remark;
    /**
     * 入参定义
     */
    private List<Param> params;

    /**
     * 入参定义（镜像 infra 内部 ParamDefinition 的字段，api 层独立副本）
     */
    @Data
    public static class Param {

        /**
         * 参数名，调用时 requestArgs 的键
         */
        private String name;
        /**
         * 显示名称
         */
        private String label;
        /**
         * 类型：STRING/INT/LONG/DECIMAL/BOOLEAN/DATE/DATETIME
         */
        private String type;
        /**
         * 是否必填
         */
        private Boolean required;
        /**
         * 默认值（必填项未传时也会用它兜底）
         */
        private String defaultValue;
        /**
         * 说明
         */
        private String remark;

    }

}
