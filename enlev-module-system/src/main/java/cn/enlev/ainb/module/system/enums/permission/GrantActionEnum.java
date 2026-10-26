package cn.enlev.ainb.module.system.enums.permission;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.common.core.ArrayValuable;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

/**
 * 授权动作枚举
 *
 * 对应 v9 §7 模型里的 Action 维度。
 * 枚举值直接落库（varchar），不要改名。
 *
 * @author 走进新时代
 */
@Getter
@AllArgsConstructor
public enum GrantActionEnum implements ArrayValuable<String> {

    /**
     * 查看（列表 / 详情）
     */
    VIEW("VIEW"),
    /**
     * 新增
     */
    CREATE("CREATE"),
    /**
     * 修改
     */
    UPDATE("UPDATE"),
    /**
     * 删除
     */
    DELETE("DELETE"),
    /**
     * 导出
     */
    EXPORT("EXPORT"),
    /**
     * 导入
     */
    IMPORT("IMPORT"),
    /**
     * 打印。默认跟随 VIEW（向后兼容），需要单独收紧时才配 DENY
     */
    PRINT("PRINT"),
    /**
     * 发起（流程 / 表单）
     */
    START("START"),
    /**
     * 设计（流程设计器）
     *
     * 与 {@link #MANAGE} 是【真子集】关系，不是同义词：DESIGN 能改流程图、部署、启停，
     * 但删模型与清流程数据仍然只认 MANAGE。判定入口见 BpmModelServiceImpl#validateModelDesigner
     *
     * ⚠️ 接线时不要图省事写成 {@code isManager(...) || canDesign(...)} 塞进原来那个
     * 统一的校验方法 —— 那等于给 DESIGN 顺带发了删除权，是提权而不是细粒度
     */
    DESIGN("DESIGN"),
    /**
     * 管理（流程管理员）
     */
    MANAGE("MANAGE"),
    /**
     * 查看该对象下的【全部】实例（v9 §7.2 的流程四动作之一）
     *
     * 与 {@link #VIEW} 的区别：VIEW 是「能看这个对象本身」，
     * VIEW_ALL 是「能看它产生的所有数据」。流程监控岗要的正是后者 ——
     * 他与那些流程实例没有任何参与关系，却必须看得到
     */
    VIEW_ALL("VIEW_ALL"),
    /**
     * 被引用查询（v9 §2）
     * 只能按主键查单条或按关键字搜，只返回带出字段映射里声明的字段，
     * 不返回列表全量、不能导出、不能翻页浏览。
     * 用于「业务员填订单时，关联控件需要查客户」这类场景
     */
    REFERENCE("REFERENCE"),
    /**
     * 通配符，匹配所有动作
     */
    WILDCARD("*");

    public static final String[] ARRAYS = Arrays.stream(values())
            .map(GrantActionEnum::getCode).toArray(String[]::new);

    private final String code;

    public static GrantActionEnum parse(String code) {
        if (StrUtil.isBlank(code)) {
            return null;
        }
        for (GrantActionEnum action : values()) {
            if (action.code.equals(code)) {
                return action;
            }
        }
        return null;
    }

    /**
     * 是否通配符，即匹配所有动作
     */
    public boolean isWildcard() {
        return this == WILDCARD;
    }

    @Override
    public String[] array() {
        return ARRAYS;
    }

}
