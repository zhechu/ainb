package cn.enlev.ainb.module.infra.dal.dataobject.billtemplate;

import cn.enlev.ainb.framework.tenant.core.db.TenantBaseDO;
import cn.enlev.ainb.module.infra.enums.billtemplate.BillGenTypeEnum;
import cn.enlev.ainb.module.infra.enums.billtemplate.ResetCycleEnum;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 单据模板 DO
 *
 * 描述「一类单号长什么样」：前缀 + 主体（日期流水 / 随机数 / UUID）+ 后缀。
 * 表单设计里的单据组件、以及其它业务模块，都通过 {@link #code} 引用本模板。
 *
 * <h3>模板按租户隔离</h3>
 * 每个租户拥有自己独立的单据模板：表带 tenant_id 列（见
 * sql/mysql/xpy-2026-09-20-bill-template-tenant.sql），由 TenantDatabaseInterceptor
 * 自动过滤与填充；{@link #code} 是租户内唯一（uk_tenant_code），不同租户可以重码。
 * Redis 计数 key 本就含 tenantId，各租户流水天然互不干扰。
 *
 * <h3>权威计数在 Redis，不在本表</h3>
 * {@link #currentNumber} 只是给管理员在列表页看的展示值，真正的自增在
 * {@code BillTemplateRedisDAO} 的 INCR 上。把权威计数放在本表某一列上、靠 UPDATE 递增，
 * 会让同一模板的所有并发请求争抢同一行的行锁；而出号处在业务保存的必经路径上，
 * 这里排队会直接拖慢整个表单提交。
 *
 * <h3>周期重置不依赖本表字段</h3>
 * {@link #lastPeriod} 同样只是展示与排查用。周期切换靠 Redis 计数 key 里的 period 段
 * 天然隔离：跨天/跨月时 key 变成新的，计数自然从 1 开始。判定过程完全不接触号码的
 * 字符串形态，因此前后缀怎么改都不会影响重置行为。
 *
 * @author 走进新时代
 */
@TableName("infra_bill_template")
@KeySequence("infra_bill_template_seq") // 用于 Oracle、PostgreSQL、Kingbase、DB2、H2 数据库的主键自增。如果是 MySQL 等数据库，可不写。
@Data
@EqualsAndHashCode(callSuper = true)
public class BillTemplateDO extends TenantBaseDO {

    /**
     * 编号
     */
    @TableId
    private Long id;
    /**
     * 模板名称
     */
    private String name;
    /**
     * 模板编码
     *
     * 表单与其它模块通过它引用本模板，因此加了唯一索引。
     */
    private String code;
    /**
     * 分类
     *
     * 数据字典 {@link cn.enlev.ainb.module.infra.enums.DictTypeConstants#INFRA_BILL_TEMPLATE_CATEGORY}
     */
    private String category;
    /**
     * 生成方式
     *
     * 枚举 {@link BillGenTypeEnum}
     */
    private Integer genType;
    /**
     * 前缀
     */
    private String prefix;
    /**
     * 后缀
     */
    private String suffix;
    /**
     * 日期格式
     *
     * 取值见 {@link cn.enlev.ainb.module.infra.enums.billtemplate.BillDateFormats}，
     * {@code NONE} 表示号码里不含日期段。仅 {@link BillGenTypeEnum#TIME} 使用。
     */
    private String dateFormat;
    /**
     * 流水位数，1-9
     *
     * 流水值位数超过它时【不截断】，直接输出更长的号码 —— 截断必然产生重复号。
     * 溢出时会打 WARN 日志提示管理员调大位数。
     */
    private Integer digit;
    /**
     * 流水起始值
     */
    private Integer startNumber;
    /**
     * 流水步长
     */
    private Integer step;
    /**
     * 重置周期
     *
     * 枚举 {@link ResetCycleEnum}
     */
    private String resetCycle;
    /**
     * 流水是否按前缀分组重置
     *
     * 模板模式默认 false：前缀是固定字符串，分组与否生成的号码完全一样，
     * 多一个计数器只是徒增排查成本。
     * 内联模式默认 true —— 前缀取自「客户编号」这类字段时，
     * 期望的是每个客户各自从 1 开始，而不是全局一个计数器。
     */
    private Boolean groupByPrefix;
    /**
     * 随机位数：16 / 32
     *
     * 仅 {@link BillGenTypeEnum#RANDOM} 使用。
     */
    private Integer randomDigit;
    /**
     * 随机类型：1-纯数字 2-字母+数字
     *
     * 仅 {@link BillGenTypeEnum#RANDOM} 使用。
     */
    private Integer randomType;
    /**
     * 是否记录取号日志
     *
     * 默认关闭。严格追溯场景开启后，每次取号（业务出号 / 表单预占）都会在
     * {@link BillTemplateLogDO} 落一条日志，日志写在独立事务里，
     * 调用方业务回滚导致的跳号同样留痕。
     */
    private Boolean logEnabled;
    /**
     * 范例
     *
     * 保存时按当前配置算一个样例存下来，供列表页直接展示，避免列表页 N 次试算。
     */
    private String example;
    /**
     * 最近一次生成的号码，展示与排查用
     */
    private String lastNo;
    /**
     * 最近一次的周期标记，展示与排查用
     */
    private String lastPeriod;
    /**
     * 当前流水值
     *
     * 展示用。权威值在 Redis，本字段由生成后的异步回写更新，回写失败不影响出号。
     */
    private Long currentNumber;
    /**
     * 状态：0-开启 1-停用
     *
     * 枚举 {@link cn.enlev.ainb.framework.common.enums.CommonStatusEnum}
     */
    private Integer status;
    /**
     * 排序
     */
    private Integer sort;
    /**
     * 备注
     */
    private String remark;

}
