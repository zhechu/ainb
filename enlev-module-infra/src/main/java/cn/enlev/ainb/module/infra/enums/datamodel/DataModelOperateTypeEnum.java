package cn.enlev.ainb.module.infra.enums.datamodel;

import cn.enlev.ainb.framework.common.core.ArrayValuable;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

/**
 * 数据模型的 DDL 操作类型枚举
 *
 * @author 走进新时代
 */
@Getter
@AllArgsConstructor
public enum DataModelOperateTypeEnum implements ArrayValuable<String> {

    CREATE("CREATE", "建表"),
    ALTER("ALTER", "改表"),
    DROP("DROP", "删表");

    public static final String[] ARRAYS = Arrays.stream(values())
            .map(DataModelOperateTypeEnum::getType).toArray(String[]::new);

    /**
     * 类型
     */
    private final String type;
    /**
     * 描述
     */
    private final String name;

    @Override
    public String[] array() {
        return ARRAYS;
    }

}
