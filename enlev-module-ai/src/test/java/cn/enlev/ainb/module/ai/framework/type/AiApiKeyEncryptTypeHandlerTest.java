package cn.enlev.ainb.module.ai.framework.type;

import cn.enlev.ainb.framework.mybatis.core.type.EncryptTypeHandler;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AiApiKeyEncryptTypeHandler} 的单元测试
 *
 * <p>用真实的 AES 而不是 mock 加解密：这个类的价值全在「什么时候<b>不</b>加密」
 * 和「解不开时怎么办」，mock 掉加解密就只剩分支判断，测不出真东西。
 * 密钥通过系统属性注入，与 {@code application.yaml} 里的配置项同名。
 *
 * @author 走进新时代
 */
public class AiApiKeyEncryptTypeHandlerTest {

    private static final String PASSWORD_KEY = "mybatis-plus.encryptor.password";
    /** 与 application.yaml 一致的 16 字节密钥 */
    private static final String PASSWORD = "XDV71a+xqStEA3WH";

    private final AiApiKeyEncryptTypeHandler handler = new AiApiKeyEncryptTypeHandler();

    @BeforeAll
    public static void setUpAll() {
        System.setProperty(PASSWORD_KEY, PASSWORD);
    }

    /**
     * {@code EncryptTypeHandler} 从 Spring 上下文取密钥，这里不起 Spring，
     * 所以把 {@code SpringUtil.getProperty} 挡掉，返回同一把密钥
     */
    private <T> T withPassword(java.util.function.Supplier<T> action) {
        try (MockedStatic<cn.hutool.extra.spring.SpringUtil> springUtil =
                     Mockito.mockStatic(cn.hutool.extra.spring.SpringUtil.class)) {
            springUtil.when(() -> cn.hutool.extra.spring.SpringUtil.getProperty(eq(PASSWORD_KEY)))
                    .thenReturn(PASSWORD);
            return action.get();
        }
    }

    @Test
    @DisplayName("普通密钥：写入时加密，读取时能原样解回")
    public void testRoundTrip() throws SQLException {
        String raw = "sk-abcdefghijklmnopqrstuvwxyz0123456789";
        String stored = withPassword(() -> EncryptTypeHandler.encrypt(raw));

        // 密文不能等于原文，否则等于没加密
        org.junit.jupiter.api.Assertions.assertNotEquals(raw, stored);

        ResultSet rs = mock(ResultSet.class);
        when(rs.getString(eq("api_key"))).thenReturn(stored);
        String actual = withPassword(() -> {
            try {
                return handler.getNullableResult(rs, "api_key");
            } catch (SQLException ex) {
                throw new IllegalStateException(ex);
            }
        });
        assertEquals(raw, actual);
    }

    @Test
    @DisplayName("🔴 占位符原样存：它是环境变量引用，不是密钥")
    public void testPlaceholderNotEncrypted() throws SQLException {
        String placeholder = "${OPENAI_API_KEY}";
        PreparedStatement ps = mock(PreparedStatement.class);

        withPassword(() -> {
            try {
                handler.setNonNullParameter(ps, 1, placeholder, null);
            } catch (SQLException ex) {
                throw new IllegalStateException(ex);
            }
            return null;
        });

        // 必须原样写入 —— 加密了的话，运维在库里看不懂自己配了什么
        verify(ps).setString(anyInt(), eq(placeholder));
    }

    @Test
    @DisplayName("🔴 占位符原样读：不能尝试解密")
    public void testPlaceholderNotDecrypted() throws SQLException {
        String placeholder = "${AZURE_KEY}";
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString(eq("api_key"))).thenReturn(placeholder);

        String actual = withPassword(() -> {
            try {
                return handler.getNullableResult(rs, "api_key");
            } catch (SQLException ex) {
                throw new IllegalStateException(ex);
            }
        });
        assertEquals(placeholder, actual);
    }

    @Test
    @DisplayName("🔴 存量明文解不开时降级返回原值，不抛异常")
    public void testLegacyPlaintextFallback() throws SQLException {
        // 迁移脚本跑之前，库里就是这个样子
        String legacy = "sk-this-is-plaintext-not-encrypted";
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString(eq("api_key"))).thenReturn(legacy);

        String actual = withPassword(() -> {
            try {
                return handler.getNullableResult(rs, "api_key");
            } catch (SQLException ex) {
                throw new IllegalStateException(ex);
            }
        });
        // 抛异常的话，上线那一刻所有模型调用一起挂 —— 安全加固不该把可用性搭进去
        assertEquals(legacy, actual);
    }

    @Test
    @DisplayName("🔴 密文里带换行也要能解开 —— MySQL 的 TO_BASE64 每 76 字符插一个 \\n")
    public void testCiphertextWithLineBreaks() throws SQLException {
        String raw = "sk-a-very-long-api-key-that-encrypts-to-more-than-76-base64-chars-0123456789";
        String stored = withPassword(() -> EncryptTypeHandler.encrypt(raw));
        // 模拟 TO_BASE64 的换行行为
        String wrapped = stored.replaceAll("(.{76})", "$1\n");
        org.junit.jupiter.api.Assertions.assertTrue(wrapped.contains("\n"),
                "样本本身要带换行，否则这条测试是空跑的");

        ResultSet rs = mock(ResultSet.class);
        when(rs.getString(eq("api_key"))).thenReturn(wrapped);
        String actual = withPassword(() -> {
            try {
                return handler.getNullableResult(rs, "api_key");
            } catch (SQLException ex) {
                throw new IllegalStateException(ex);
            }
        });
        assertEquals(raw, actual);
    }

    @Test
    @DisplayName("空值不处理")
    public void testBlankValue() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString(anyString())).thenReturn(null);
        assertEquals(null, handler.getNullableResult(rs, "api_key"));
    }

}
