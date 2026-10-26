package cn.enlev.ainb.framework.mybatis.core.util;

import cn.hutool.core.util.StrUtil;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 系统表访问策略
 *
 * 统一「哪些表禁止被某类操作碰」这件事。抽出来的原因：这条规则此前在后端有 5 份副本、
 * 三套口径 —— 数据建模 24 个前缀、表单设计 3 个前缀（漏了 sys_/act_/ai_/qrtz_）、
 * JdbcUtils 只认 sys_。结果是同一个 sys_grant，数据建模不让建、表单设计却能绑定并写入。
 *
 * <h3>为什么按操作分级，而不是一个 isFrameworkTable</h3>
 *
 * 两份名单不一致不是手误，是两个不同的策略被写成了同一个名字：
 * <ul>
 *     <li>数据建模拦的是【改表结构】—— 连 crm_/erp_ 这类别的模块的表也不许动</li>
 *     <li>表单设计拦的是【写数据】—— 但 crm_/erp_ 是合法业务表，该放开</li>
 *     <li>两边都【放行读】—— 做报表需要读系统表，不要改坏这条</li>
 * </ul>
 * 合成单一判断会逼着二选一：要么误伤合法业务表，要么放宽系统表。所以按 {@link TableOperation} 分级。
 *
 * <h3>为什么不提供 check() 抛异常</h3>
 *
 * 各模块有自己的错误码（FORM_DESIGN_FRAMEWORK_TABLE_DENY / DATA_MODEL_TABLE_IS_FRAMEWORK /
 * DATA_INTERFACE_SQL_FRAMEWORK_TABLE_DENY），框架层不该反向依赖业务模块的枚举。
 * 本类只给判断，抛什么错由调用方决定 —— 这样现有错误码、测试与前端提示都不受影响。
 *
 * @author 走进新时代
 */
public final class SystemTablePolicy {

    private SystemTablePolicy() {
    }

    /**
     * 系统核心表前缀：写操作一律禁止
     *
     * qrtz_ 必须在内：Quartz 调度表在库里是【大写】的（QRTZ_TRIGGERS 等 11 张），
     * 写坏会直接搞坏定时任务 —— 这也是 {@link #normalize} 必须转小写的原因。
     * flw_ 必须在内：Flowable 6.x 的新前缀，与 act_ 同属工作流引擎表，
     * 只禁 act_ 不禁 flw_ 等于只锁了半扇门。它由 Flowable 运行时用 Liquibase 自建，
     * 不在建库脚本里，所以搜代码搜不到，但不代表不存在。
     */
    private static final Set<String> SYSTEM_CORE = Set.of(
            "act_", "ai_", "bpm_", "flw_", "infra_", "qrtz_", "sys_", "system_");

    /**
     * 预留模块前缀：建表与登记时额外禁止
     *
     * 这些模块当前大多没启用（根 pom.xml 里被注释），但表名前缀要占住 ——
     * 否则用户建了 crm_customer，将来启用 CRM 模块就撞名。
     * 只管 DDL/REGISTER，不管 WRITE：已存在的业务模块表是可以被表单绑定并写入的。
     */
    private static final Set<String> RESERVED_MODULE = Stream.concat(
            SYSTEM_CORE.stream(),
            Stream.of("member_", "pay_", "mp_", "mall_", "trade_", "promotion_", "product_",
                    "crm_", "erp_", "hrm_", "fms_", "mes_", "wms_", "im_", "iot_", "report_")
    ).collect(Collectors.toUnmodifiableSet());

    /**
     * 归一化表名
     *
     * 三件事都不能省：
     * 1. 转小写 —— QRTZ_* 在库里是大写的，不转直接失配
     * 2. 剥反引号 —— SQL 解析器可能返回 `sys_grant` 这种带引号的形态
     * 3. 剥库名限定 —— mydb.sys_grant 取最后一段
     */
    static String normalize(String tableName) {
        if (StrUtil.isBlank(tableName)) {
            return "";
        }
        String result = tableName.trim().replace("`", "").replace("\"", "");
        int dot = result.lastIndexOf('.');
        if (dot >= 0 && dot < result.length() - 1) {
            result = result.substring(dot + 1);
        }
        return result.toLowerCase();
    }

    /**
     * 是否系统表（最宽口径，给界面打标用）
     *
     * 注意这里用的是 SYSTEM_CORE 而非 RESERVED_MODULE：
     * 界面上把 crm_/erp_ 标成「系统表」会误导用户，它们是可以正常绑定的业务表。
     */
    public static boolean isSystemTable(String tableName) {
        String lower = normalize(tableName);
        return !lower.isEmpty() && SYSTEM_CORE.stream().anyMatch(lower::startsWith);
    }

    /**
     * 判断某表是否允许执行某类操作
     *
     * @param tableName 表名，可带反引号或库名限定
     * @param operation 操作类型
     * @return 允许则 true
     */
    public static boolean isAllowed(String tableName, TableOperation operation) {
        if (operation == TableOperation.READ) {
            // 读一律放行 —— 做报表经常需要读系统表，这是刻意的
            return true;
        }
        String lower = normalize(tableName);
        if (lower.isEmpty()) {
            // 拿不到表名时按更危险的处理，与数据接口 SqlPolicyValidator 对 OTHER 语句的态度一致
            return false;
        }
        return prefixes(operation).stream().noneMatch(lower::startsWith);
    }

    /**
     * 取某类操作的禁止前缀集合，用于下发前端做置灰与提示
     *
     * @param operation 操作类型
     * @return 禁止的前缀；READ 返回空集
     */
    public static Set<String> prefixes(TableOperation operation) {
        return switch (operation) {
            case READ -> Set.of();
            case WRITE -> SYSTEM_CORE;
            case DDL, REGISTER -> RESERVED_MODULE;
        };
    }

}
