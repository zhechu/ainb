package cn.enlev.ainb.module.infra.api.formdesign.dto;

import lombok.Data;

import java.util.List;

/**
 * 表单数据分页查询 Request DTO
 *
 * 供 agent 等其它模块按 code 分页查询「表单已提交的业务数据行」使用。
 * 形状与 infra 自己的 {@code FormDataPageReqVO} 一致，只是换成 api 级类型 ——
 * 🔴 <b>绝不</b>把 service 内部的 {@code FormDesignSqlBuilder.QueryCondition} 泄漏进 api 签名，
 * 转换由 {@code FormDesignApiImpl} 承担（镜像 Controller 的 convertConditions）。
 *
 * <p>⚠️ 不 extends {@code PageParam}：这是模块间调用的 DTO，不走 MVC 绑定，
 * pageNo/pageSize 用 {@link Integer} 允许为 null，由实现层兜默认（1 / 10）。
 *
 * @author 走进新时代
 */
@Data
public class FormDataPageReqDTO {

    /**
     * 查询条件列表
     *
     * field 必须能在表绑定的 fieldMapping 里解析出物理列，否则该条件被服务端忽略
     * （这层过滤在服务端做，不依赖调用方自律）。operator 走 infra 的 OperatorEnum 白名单，
     * 非法操作符不会被拼进 SQL —— 调用方无需在此重复校验。
     */
    private List<Condition> conditions;

    /**
     * 排序字段（form-create 的 field，需在配置中声明过）
     */
    private String sortField;

    /**
     * 排序方向，只允许 ASC / DESC
     */
    private String sortOrder;

    /**
     * 页码，从 1 开始。null 时实现层兜默认 1
     */
    private Integer pageNo;

    /**
     * 每页条数。null 时实现层兜默认 10；上限由 infra 运行时约束
     */
    private Integer pageSize;

    /**
     * 查询条件
     */
    @Data
    public static class Condition {

        /**
         * 字段名（form-create 的 field）
         */
        private String field;

        /**
         * 操作符：EQ/NE/GT/GE/LT/LE/LIKE/IN/BETWEEN/IS_NULL 等，见 infra OperatorEnum
         */
        private String operator;

        /**
         * 值
         */
        private Object value;

        /**
         * 第二个值，BETWEEN 时使用
         */
        private Object value2;

    }

}
