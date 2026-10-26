package cn.enlev.ainb.module.ai.framework.type;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.mybatis.core.type.EncryptTypeHandler;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * API Key 的加密 TypeHandler
 *
 * <h3>为什么不直接用 {@link EncryptTypeHandler}</h3>
 * 两点差异，都是 API Key 这个场景特有的：
 * <ol>
 *   <li><b>要放过 {@code ${ENV_VAR}} 占位符</b>。库里存的可能不是密钥本身，
 *       而是一个环境变量引用（{@code AiUtils.resolveSpringPlaceholders} 在用的时候才解析）。
 *       引用不是秘密，加密它只会让运维在库里看不懂自己配了什么</li>
 *   <li><b>解密失败要能降级</b>。存量数据是明文，上线那一刻如果直接抛异常，
 *       所有模型调用会一起挂掉 —— 而这是个安全加固，不该把可用性搭进去。
 *       所以解不开就按明文处理并告警，迁移脚本跑完告警自然消失</li>
 * </ol>
 *
 * <h3>⚠️ 这一层防的是什么，不防什么</h3>
 * 密钥写在 {@code application.yaml} 里、跟着代码走。所以它防的是
 * <b>数据库被单独拖走</b>（备份泄漏、只读账号、SQL 注入读表），
 * <b>不防</b>拿到应用服务器或代码仓库的人。
 * 接口侧的泄漏由 {@code AiApiKeyRespVO} 的脱敏注解挡，两层各管一段。
 *
 * @author 走进新时代
 */
@Slf4j
public class AiApiKeyEncryptTypeHandler extends BaseTypeHandler<String> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, String parameter, JdbcType jdbcType)
            throws SQLException {
        ps.setString(i, isPlaceholder(parameter) ? parameter : EncryptTypeHandler.encrypt(parameter));
    }

    @Override
    public String getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return decrypt(rs.getString(columnName));
    }

    @Override
    public String getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return decrypt(rs.getString(columnIndex));
    }

    @Override
    public String getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return decrypt(cs.getString(columnIndex));
    }

    /**
     * 是否环境变量占位符
     */
    private static boolean isPlaceholder(String value) {
        return StrUtil.isNotBlank(value) && StrUtil.contains(value, "${");
    }

    private static String decrypt(String value) {
        if (StrUtil.isBlank(value) || isPlaceholder(value)) {
            return value;
        }
        try {
            // 剥掉空白再解密：MySQL 的 TO_BASE64 每 76 字符插一个换行，
            // 而 base64 解码不接受换行。用它写迁移脚本是最自然的做法，
            // 踩中了却极难发现 —— 实测那次，因为 ChatModel 被缓存着，
            // 对话照常成功，只有日志里多了几条告警
            return EncryptTypeHandler.decrypt(StrUtil.removeAllLineBreaks(value).trim());
        } catch (Exception ex) {
            // 走到这里只有两种可能：① 是尚未迁移的明文；② 密钥换过了解不开。
            // 两种都不该让调用直接失败 —— 前者会在迁移后消失，
            // 后者原样返回密文，上游厂商会以「密钥无效」拒绝，是个响亮且明确的失败
            log.warn("[decrypt][API Key 解密失败，按明文处理。若非存量数据未迁移，"
                    + "请检查 mybatis-plus.encryptor.password 是否被改过]");
            return value;
        }
    }

}
