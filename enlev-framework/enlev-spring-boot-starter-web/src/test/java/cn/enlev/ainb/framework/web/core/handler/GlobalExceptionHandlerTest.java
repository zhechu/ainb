package cn.enlev.ainb.framework.web.core.handler;

import cn.enlev.ainb.framework.common.biz.infra.logger.ApiErrorLogCommonApi;
import cn.enlev.ainb.framework.common.pojo.CommonResult;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;

import static cn.enlev.ainb.framework.common.exception.enums.GlobalErrorCodeConstants.BAD_REQUEST;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@link GlobalExceptionHandler} 的单元测试
 *
 * 重点守住「请求体读不出来」这一类异常的归类：它们是客户端错误，必须返回 400，
 * 且不能写 API 错误日志——否则调用方的脏请求会被记成本服务的故障。
 */
@ExtendWith(MockitoExtension.class)
public class GlobalExceptionHandlerTest {

    @Mock
    private ApiErrorLogCommonApi apiErrorLogApi;

    private GlobalExceptionHandler buildHandler() {
        return new GlobalExceptionHandler("enlev-server", apiErrorLogApi);
    }

    private static MockHttpInputMessage inputMessage() {
        return new MockHttpInputMessage(new byte[0]);
    }

    @Test
    public void testUnreadableBody_illegalEncoding_returns400AndNoErrorLog() {
        // 准备参数：真实场景是请求体不是合法 UTF-8，Jackson 抛 "Invalid UTF-8 start byte"
        HttpMessageNotReadableException ex = new HttpMessageNotReadableException(
                "JSON parse error", new RuntimeException("Invalid UTF-8 start byte 0xb1"), inputMessage());

        // 调用
        CommonResult<?> result = buildHandler().methodArgumentTypeInvalidFormatExceptionHandler(ex);

        // 断言：400 + 带上最内层原因，而不是 500「系统异常」
        assertEquals(BAD_REQUEST.getCode(), result.getCode());
        assertTrue(result.getMsg().startsWith("请求参数格式错误:"), result.getMsg());
        assertTrue(result.getMsg().contains("Invalid UTF-8 start byte"), result.getMsg());
        // 关键：客户端错误不得进 API 错误日志
        verifyNoInteractions(apiErrorLogApi);
    }

    @Test
    public void testUnreadableBody_malformedJson_returns400() {
        HttpMessageNotReadableException ex = new HttpMessageNotReadableException(
                "JSON parse error: Unexpected character", new RuntimeException("Unexpected character ('}' (code 125))"),
                inputMessage());

        CommonResult<?> result = buildHandler().methodArgumentTypeInvalidFormatExceptionHandler(ex);

        assertEquals(BAD_REQUEST.getCode(), result.getCode());
        assertTrue(result.getMsg().startsWith("请求参数格式错误:"), result.getMsg());
        verifyNoInteractions(apiErrorLogApi);
    }

    @Test
    public void testUnreadableBody_missingBody_keepsExistingMessage() {
        HttpMessageNotReadableException ex = new HttpMessageNotReadableException(
                "Required request body is missing", inputMessage());

        CommonResult<?> result = buildHandler().methodArgumentTypeInvalidFormatExceptionHandler(ex);

        assertEquals(BAD_REQUEST.getCode(), result.getCode());
        assertEquals("请求参数类型错误: request body 缺失", result.getMsg());
        verifyNoInteractions(apiErrorLogApi);
    }

    @Test
    public void testUnreadableBody_invalidFormat_keepsExistingMessage() {
        InvalidFormatException cause = InvalidFormatException.from(null, "Cannot deserialize value",
                "abc", Integer.class);
        HttpMessageNotReadableException ex = new HttpMessageNotReadableException(
                "JSON parse error", cause, inputMessage());

        CommonResult<?> result = buildHandler().methodArgumentTypeInvalidFormatExceptionHandler(ex);

        assertEquals(BAD_REQUEST.getCode(), result.getCode());
        assertEquals("请求参数类型错误:abc", result.getMsg());
        verifyNoInteractions(apiErrorLogApi);
    }

    @Test
    public void testUnreadableBody_longReason_isTruncated() {
        String longReason = "x".repeat(500);
        HttpMessageNotReadableException ex = new HttpMessageNotReadableException(
                "JSON parse error", new RuntimeException(longReason), inputMessage());

        CommonResult<?> result = buildHandler().methodArgumentTypeInvalidFormatExceptionHandler(ex);

        assertEquals(BAD_REQUEST.getCode(), result.getCode());
        // 前缀 9 个字符 + 截断后的 200 字符 + Hutool 追加的省略号，远小于原始的 500
        assertTrue(result.getMsg().length() < 250, String.valueOf(result.getMsg().length()));
    }

}
