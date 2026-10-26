package cn.enlev.ainb.module.infra.dal.dataobject.datainterface;

import cn.enlev.ainb.framework.mybatis.core.dataobject.BaseDO;
import cn.enlev.ainb.framework.mybatis.core.type.StringListTypeHandler;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 数据接口定义 DO
 *
 * @author 走进新时代
 */
// autoResultMap 是 tags 能被读回来的前提：没有它 MyBatis-Plus 不会生成带 typeHandler 的 ResultMap，
// 写进去是逗号串、读出来也是逗号串（而不是 List），且不会报错
@TableName(value = "infra_data_interface", autoResultMap = true)
@KeySequence("infra_data_interface_seq")
@Data
@EqualsAndHashCode(callSuper = true)
public class DataInterfaceDO extends BaseDO {

    /**
     * 编号
     */
    private Long id;
    /**
     * 接口名称
     */
    private String name;
    /**
     * 责任人，手填字符串
     */
    private String owner;
    /**
     * 接口编码，调用时使用
     */
    private String code;
    /**
     * 来源：这个接口的数据从哪来
     *
     * 字典 {@code infra_form_type}，当前只有「内部 / 外部」两个值。
     * 内部＝取本系统自己的库表或接口，外部＝转发到第三方系统。
     * 界面上叫「来源」，代码里仍叫 category —— 它已经是对外契约的一部分
     * （分页查询参数、SaveReqVO、agent 工具参数都叫 category），改名收益抵不上连带改动
     *
     * <p>⚠️ 不要把这个字段理解成「谁来消费这个接口」。消费方是<b>标签</b>
     * （字典 {@code infra_data_interface_tag}，种子就是 表单取数 / 门户组件 / AI 工具 …）
     * 在表达的维度。本字段的注释曾经写的正是「内部＝本系统自己消费，外部＝给别的系统调」，
     * 方向与字段名相反，也和标签重复了一遍
     */
    private String category;
    /**
     * 标签，多值
     *
     * 字典 {@code infra_data_interface_tag}。库里存逗号分隔串（{@link StringListTypeHandler}），
     * 查询走 FIND_IN_SET，见 {@code DataInterfaceMapper#selectPage}
     */
    @TableField(typeHandler = StringListTypeHandler.class)
    private List<String> tags;
    /**
     * 类型
     *
     * 枚举 {@link cn.enlev.ainb.module.infra.enums.datainterface.DataInterfaceTypeEnum}
     */
    private Integer type;
    /**
     * 状态：0-开启 1-停用
     */
    private Integer status;
    /**
     * 数据源配置编号，SQL 类型使用
     */
    private Long dataSourceConfigId;
    /**
     * 数据源配置 JSON
     *
     * 三种类型的配置结构不同，用一个 JSON 列承载：
     * SQL 类型存 SQL 文本；HTTP 类型存 URL/method/headers/超时；静态类型存 JSON 数据
     */
    private String config;
    /**
     * 入参定义 JSON
     *
     * 结构见 {@link cn.enlev.ainb.module.infra.framework.datainterface.core.param.ParamDefinition}
     */
    private String params;
    /**
     * JS 后处理脚本
     */
    private String script;
    /**
     * SQL 动作类型
     *
     * 枚举 {@link cn.enlev.ainb.module.infra.enums.datainterface.SqlStatementTypeEnum}。
     *
     * ⚠️ **由用户在配置界面显式选择**，不是解析 SQL 推断出来的 —— 一个接口可以配多条
     * SQL，程序无法可靠判断这组语句整体算什么动作。SqlStatementAnalyzer 的解析结果
     * 只用于兜底校验（选「查询」但 SQL 含写语句时拒绝保存）。
     *
     * 决定三件事：返回格式（查询返结果集 / 其它返影响行数）、能否开分页、列表页标注。
     */
    private String sqlStatementType;
    /**
     * 是否含写操作
     *
     * 由 sqlStatementType 直接推导（SELECT 为 false，其余为 true），
     * 冗余一列是为了列表页筛选与标注不必解析字符串。
     */
    private Boolean hasWriteOperation;
    /**
     * 是否分页：0-否 1-是
     *
     * 仅 type=SQL 且 sqlStatementType=SELECT 时有效，
     * 保存时会校验：非查询动作强制置 0。
     * 开启后要求 SQL 中使用 #{pageSize} / #{offsetSize}。
     */
    private Integer hasPage;
    /**
     * 分页时的 COUNT SQL（可选）
     *
     * 为空时自动改写：把 SELECT xxx FROM ... 改成 SELECT COUNT(*) FROM ...
     */
    private String countSql;
    /**
     * HTTP 转发基础 URL（可选）
     *
     * 配置后可在 config 里使用相对路径，如 /api/user
     */
    private String baseUrl;
    /**
     * HTTP 转发：是否允许内网地址 0-否 1-是
     *
     * 开启后可访问 localhost / 192.168.x 等内网地址。
     * 默认禁用以防 SSRF 攻击。
     */
    private Integer allowInternalUrl;
    /**
     * 当前版本号
     */
    private Integer version;
    /**
     * 是否记录调用日志：0-否 1-是
     *
     * 默认开启。高频/高性能接口可关闭以减少写日志开销。
     */
    private Integer logEnabled;
    /**
     * 备注
     */
    private String remark;

}
