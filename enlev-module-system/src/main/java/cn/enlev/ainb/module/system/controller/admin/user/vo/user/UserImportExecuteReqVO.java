package cn.enlev.ainb.module.system.controller.admin.user.vo.user;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 用户导入执行 Request VO
 *
 * @author 芋道源码
 */
@Schema(description = "管理后台 - 用户导入执行 Request VO")
@Data
public class UserImportExecuteReqVO {

    /**
     * 库里已有同名账号时怎么办
     *
     * <h3>为什么没有表单设计那边的 APPEND（一律新增）</h3>
     *
     * 登录名在用户表上是唯一的，「不管有没有都再来一条」在这里必然全部失败。
     * 把它摆出来只会让用户选了之后收到一屏报错。
     */
    public enum DuplicateStrategy {
        /**
         * 跳过，并在结果里列出来。不改任何已有数据
         */
        SKIP,
        /**
         * 按登录名覆盖更新
         */
        UPDATE
    }

    @Schema(description = "预检令牌。上一次预检返回的 importToken，只能用一次",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String importToken;

    @Schema(description = "用户在预检表里剔掉的行号。这些行不导入", example = "[5, 9]")
    private List<Integer> removedRows;

    @Schema(description = "库中已存在时的策略：SKIP-跳过并列出 / UPDATE-按登录名覆盖更新。默认 SKIP",
            example = "SKIP")
    private String duplicateStrategy;

    @Schema(description = "是否「全对才导」。开启时只要还剩一行校验不过，整批都不导",
            example = "false")
    private Boolean allOrNothing;

    /**
     * 解析策略
     *
     * 认不出的值按 SKIP 处理，不抛异常 —— SKIP 是这两个里唯一不修改已有数据的，
     * 拿不准的时候该落在不会造成损失的那一边
     */
    public DuplicateStrategy resolveStrategy() {
        if (duplicateStrategy == null || duplicateStrategy.isBlank()) {
            return DuplicateStrategy.SKIP;
        }
        try {
            return DuplicateStrategy.valueOf(duplicateStrategy.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return DuplicateStrategy.SKIP;
        }
    }

    public boolean isUpdateSupport() {
        return resolveStrategy() == DuplicateStrategy.UPDATE;
    }

}
