package cn.enlev.ainb.module.infra.enums.formdesign;

import cn.enlev.ainb.framework.common.core.ArrayValuable;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

/**
 * 表单设计状态枚举
 *
 * @author 走进新时代
 */
@Getter
@AllArgsConstructor
public enum FormDesignStatusEnum implements ArrayValuable<Integer> {

    /**
     * 草稿（从未发布）
     */
    DRAFT(0, "草稿"),

    /**
     * 已发布（草稿与线上一致）
     */
    PUBLISHED(1, "已发布"),

    /**
     * 已发布有改动（草稿已被改动，与线上版本不一致，需重新发布）
     */
    PUBLISHED_MODIFIED(2, "已发布有改动");

    public static final Integer[] ARRAYS = Arrays.stream(values())
            .map(FormDesignStatusEnum::getStatus).toArray(Integer[]::new);

    /**
     * 状态
     */
    private final Integer status;
    /**
     * 描述
     */
    private final String name;

    @Override
    public Integer[] array() {
        return ARRAYS;
    }

    public static FormDesignStatusEnum statusOf(Integer status) {
        return Arrays.stream(values())
                .filter(item -> item.getStatus().equals(status))
                .findFirst().orElse(null);
    }

}
