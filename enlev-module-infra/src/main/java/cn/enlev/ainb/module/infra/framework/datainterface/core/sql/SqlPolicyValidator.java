package cn.enlev.ainb.module.infra.framework.datainterface.core.sql;

import cn.enlev.ainb.framework.mybatis.core.util.SystemTablePolicy;
import cn.enlev.ainb.framework.mybatis.core.util.TableOperation;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.module.infra.enums.ErrorCodeConstants.*;
import static cn.enlev.ainb.module.infra.enums.datainterface.SqlStatementTypeEnum.DDL;

/**
 * SQL 配置的合法性校验
 *
 * 保存与执行两处共用同一套校验 —— 保存时拦住能给用户即时反馈，
 * 执行时兜底防止「配置绕过界面直接写库」或「历史数据不合规」。
 *
 * @author 走进新时代
 */
public class SqlPolicyValidator {

    private SqlPolicyValidator() {
    }

    /**
     * 校验分析结果是否符合策略
     *
     * @param analyze 语句分析结果
     */
    public static void validate(SqlStatementAnalyzer.AnalyzeResult analyze) {
        if (!analyze.isSuccess()) {
            throw exception(DATA_INTERFACE_SQL_PARSE_FAIL, analyze.getErrorMsg());
        }
        for (SqlStatementAnalyzer.StatementInfo info : analyze.getStatements()) {
            validateNoDdl(info);
            validateFrameworkTableWrite(info);
        }
    }

    /**
     * 禁止 DDL
     *
     * 两个理由：
     * 1. 数据接口的定位是「数据的增删改查」，改表结构应该走数据模型，职责分离
     * 2. MySQL 的 DDL 无法回滚 —— 多语句里混一句 DDL，整个接口的事务保证就失效了
     *
     * 无法识别类型的语句（OTHER）同样拒绝：拿不准时按更危险的处理。
     */
    private static void validateNoDdl(SqlStatementAnalyzer.StatementInfo info) {
        if (DDL.getType().equals(info.getStatementType())) {
            throw exception(DATA_INTERFACE_SQL_DDL_DENY, info.getStatementType());
        }
        if ("OTHER".equals(info.getStatementType())) {
            throw exception(DATA_INTERFACE_SQL_STATEMENT_NOT_SUPPORTED, info.getSql());
        }
    }

    /**
     * 系统表写保护
     *
     * 删自己的业务表是用户的自由，但对 sys_ / system_ / infra_ / bpm_ / act_ / flw_ /
     * ai_ / qrtz_ 这些系统表做写操作会让系统直接不可用，甚至越权（写 sys_grant 等于自助提权）。
     * 查询这些表是允许的。
     *
     * ⚠️ 口径是 {@link TableOperation#WRITE}，比数据模型的 DDL 口径宽 ——
     * crm_/erp_ 等业务模块表可以正常增删改，只是不允许通过数据模型改它们的表结构。
     */
    private static void validateFrameworkTableWrite(SqlStatementAnalyzer.StatementInfo info) {
        if (!info.isWriteOperation()) {
            return;
        }
        for (String table : info.getTableNames()) {
            if (!SystemTablePolicy.isAllowed(table, TableOperation.WRITE)) {
                throw exception(DATA_INTERFACE_SQL_FRAMEWORK_TABLE_DENY, table);
            }
        }
    }

}
