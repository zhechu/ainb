package cn.enlev.ainb.module.ai.util;

import cn.enlev.ainb.framework.common.exception.ErrorCode;
import cn.enlev.ainb.framework.common.exception.ServiceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.module.ai.enums.ErrorCodeConstants.API_CONFIG_PLACEHOLDER_NOT_RESOLVED;
import static cn.enlev.ainb.module.ai.enums.ErrorCodeConstants.API_KEY_BLANK;
import static cn.enlev.ainb.module.ai.enums.ErrorCodeConstants.API_KEY_DISABLE;
import static cn.enlev.ainb.module.ai.enums.ErrorCodeConstants.API_KEY_NOT_EXISTS;
import static cn.enlev.ainb.module.ai.enums.ErrorCodeConstants.MODEL_ALL_UNAVAILABLE;
import static cn.enlev.ainb.module.ai.enums.ErrorCodeConstants.MODEL_QUOTA_EXHAUSTED;
import static cn.enlev.ainb.module.ai.enums.ErrorCodeConstants.MODEL_TASK_PINNED_UNAVAILABLE;
import static cn.enlev.ainb.module.ai.enums.ErrorCodeConstants.WEB_SEARCH_FAIL;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AiUtils#validateApiKey(String)} 的成因与文案单测（台账 D4-29）
 *
 * <h3>它守的失效形态是「一条报错串对应多个成因」</h3>
 * 这个方法原先抛 {@code new IllegalStateException("apiKey 不能为空")} —— 没有码、只有一句内部术语。
 * 消费方拿不到成因，就只能自己<b>猜</b>一个：agent 侧的 {@code toReadableMessage} 曾把所有
 * {@code IllegalStateException} 一律回成「没有可用的聊天模型，请联系管理员启用一个」。
 * 于是「密钥没填」这个成因在界面上<b>顶着另一个成因的名字</b>出现，
 * 管理员照着去反复启停一个本来就启用着的模型（红线 §10.2 第 7 条：不替 error 编病因）。
 *
 * <p>🔴 所以本类的判据不是「改好了」，而是<b>成因枚举</b>：聊天路径上每个成因各造一次、
 * 各一条断言说清它指的是哪件事，最后再断一次「这些串两两互不相同」。
 * 只要有两句话说的是同一句，那条误标签的路就还在。
 *
 * <p>⚠️ 这类断言的失效方式很安静：给某个新成因复用一句现成文案，编译通过、行为「看着也对」，
 * 只有这里的两两比对会红。
 *
 * @author 走进新时代
 */
class AiUtilsValidateApiKeyTest {

    // ==================== ① 两个成因都得抛，且都是带码的 ServiceException ====================

    @Test
    @DisplayName("密钥为空（null / 空串 / 纯空白）→ 带码 ServiceException，不再是 IllegalStateException")
    void validateApiKey_blank_throwsCodedServiceException() {
        for (String blank : new String[]{null, "", "   "}) {
            ServiceException ex = assertThrows(ServiceException.class,
                    () -> AiUtils.validateApiKey(blank), "空值该被拦下：" + blank);
            assertEquals(API_KEY_BLANK.getCode(), ex.getCode(), "成因要靠码认，不能靠异常类型猜：" + blank);
        }
    }

    @Test
    @DisplayName("密钥仍是 SDK 示例占位值 → 与「空」同档拦下（发出去只会拿到 401，看不出根因）")
    void validateApiKey_placeholder_throwsCodedServiceException() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> AiUtils.validateApiKey(AiUtils.API_KEY_PLACEHOLDER));
        assertEquals(API_KEY_BLANK.getCode(), ex.getCode());
        assertEquals("sk-xxxx", AiUtils.API_KEY_PLACEHOLDER,
                "占位值改了要一起改：各平台 SDK 文档里的示例密钥就是这个串，改了它就不叫占位值了");
    }

    @Test
    @DisplayName("填了真值就放行 —— 正向对照，否则上面两条「把方法改成一律抛」也全绿")
    void validateApiKey_realKey_passes() {
        assertDoesNotThrow(() -> AiUtils.validateApiKey("sk-real-key-0123456789"));
    }

    // ==================== ② 成因枚举：每个成因一句话，两两互不相同 ====================

    /**
     * 聊天路径上会被用户看见的全部成因，各造一次。
     *
     * <p>🔴 key 是<b>成因</b>、value 是<b>用户会看见的那句话</b>。带占位符的一律用生产侧同款参数填过 ——
     * 比对模板原文没有意义：{@code MODEL_ALL_UNAVAILABLE} 的模板是「所有 AI 模型均不可用，最后错误：{}」，
     * 而用户实际看到的是 {@code AiModelApiImpl#buildChatCandidates} 填进去的那句可操作指引。
     */
    private static Map<String, String> causeToMessage() {
        Map<String, String> causes = new LinkedHashMap<>();
        causes.put("密钥没填（或仍是占位值）", messageOf(API_KEY_BLANK));
        causes.put("一个能用的聊天模型都没有",
                exception(MODEL_ALL_UNAVAILABLE,
                        "没有可用的聊天模型，请在「AI 模型」里启用一个，并确认它关联的密钥也是启用状态").getMessage());
        causes.put("任务已钉住的模型现在不可用", exception(MODEL_TASK_PINNED_UNAVAILABLE, 7L).getMessage());
        causes.put("模型额度耗尽", exception(MODEL_QUOTA_EXHAUSTED, "qwen3.5-plus", "403 quota exhausted").getMessage());
        causes.put("密钥行不存在", messageOf(API_KEY_NOT_EXISTS));
        causes.put("密钥被禁用", messageOf(API_KEY_DISABLE));
        causes.put("密钥里的 ${ENV} 占位符解析不了",
                exception(API_CONFIG_PLACEHOLDER_NOT_RESOLVED, "${OPENAI_API_KEY}").getMessage());
        causes.put("联网搜索这一步失败",
                exception(WEB_SEARCH_FAIL, "HTTP 401，厂商响应：{\"code\":\"invalid_api_key\"}").getMessage());
        return causes;
    }

    private static String messageOf(ErrorCode code) {
        return exception(code).getMessage();
    }

    @Test
    @DisplayName("🔴 每个成因各一条断言：它那句话得说清自己指的是哪件事")
    void eachCauseNamesItself() {
        Map<String, String> causes = causeToMessage();
        // 域自检：成因少了一个，下面那两两比对会「更绿」（要比的串少了一条），故先把条数钉死
        assertEquals(8, causes.size(), "成因清单被改动了？请逐条确认新增/删除的那条文案没跟别人撞车");

        String blank = causes.get("密钥没填（或仍是占位值）");
        assertTrue(blank.contains("密钥"), "该指向密钥：" + blank);
        assertTrue(blank.contains("AI 大模型密钥"), "该给出能照做的出路（去哪个菜单填）：" + blank);
        assertFalse(blank.contains("没有可用的聊天模型"),
                "🔴 D4-29 的要害：这句不许再顶着「没有可用的聊天模型」的名义出现：" + blank);

        String none = causes.get("一个能用的聊天模型都没有");
        assertTrue(none.contains("没有可用的聊天模型"), "该指向模型没启用：" + none);
        assertTrue(none.contains("启用一个"), "该给出能照做的出路：" + none);

        assertTrue(causes.get("任务已钉住的模型现在不可用").contains("钉住"),
                "该说清是「任务钉住了」而不是「环境没配好」：" + causes.get("任务已钉住的模型现在不可用"));
        assertTrue(causes.get("模型额度耗尽").contains("额度耗尽"),
                "该说清是额度：" + causes.get("模型额度耗尽"));
        assertTrue(causes.get("密钥行不存在").contains("不存在"),
                "该说清是密钥行没了：" + causes.get("密钥行不存在"));
        assertTrue(causes.get("密钥被禁用").contains("禁用"),
                "该说清是密钥被禁用（≠ 没填）：" + causes.get("密钥被禁用"));
        assertTrue(causes.get("密钥里的 ${ENV} 占位符解析不了").contains("无法解析"),
                "该说清是占位符解析不了（≠ 没填）：" + causes.get("密钥里的 ${ENV} 占位符解析不了"));

        String search = causes.get("联网搜索这一步失败");
        assertTrue(search.contains("联网搜索"), "该说清是搜索这一步（用户勾了「联网搜索」才知道跟自己有关）：" + search);
        assertTrue(search.contains("invalid_api_key"),
                "🔴 该逐字带上厂商原文。它原先抛没有码的 IllegalStateException，被兜底成「系统异常」，"
                        + "厂商说的话只留在服务端日志里：" + search);
        assertFalse(search.contains("系统异常"),
                "不许退回那句什么都不指认的通用话：" + search);
    }

    @Test
    @DisplayName("🔴 报错串两两互不相同 —— 「同一条报错串不再对应多个成因」")
    void noTwoCausesShareOneMessage() {
        Map<String, String> causes = causeToMessage();
        for (Map.Entry<String, String> a : causes.entrySet()) {
            for (Map.Entry<String, String> b : causes.entrySet()) {
                if (a.getKey().compareTo(b.getKey()) >= 0) {
                    continue; // 每对比一次
                }
                assertFalse(a.getValue().equals(b.getValue()),
                        "两个成因共用了同一句话，用户/管理员无从分辨：\n  成因甲「" + a.getKey()
                                + "」\n  成因乙「" + b.getKey() + "」\n  共用文案：" + a.getValue());
            }
        }
    }

    /**
     * 🔴 变异探针：把 {@code API_KEY_BLANK} 的文案换成 {@code MODEL_ALL_UNAVAILABLE} 那个成因
     * <b>用户实际看见的整句</b>，上面两条必须<b>一起</b>变红。
     *
     * <p>本条不是测试，是把「怎么验证这套断言真的在守东西」写下来，免得后人以为它们恒绿。
     * 已按下面三步实测过一遍（2026-10-07，证据 {@code logs/43}、{@code logs/44}、{@code logs/45}）：
     * <pre>
     *   1) 把 ErrorCodeConstants 里 API_KEY_BLANK 的 msg 逐字改成：
     *      所有 AI 模型均不可用，最后错误：没有可用的聊天模型，请在「AI 模型」里启用一个，并确认它关联的密钥也是启用状态
     *      🔴 必须是<b>整句</b>（含「所有 AI 模型均不可用，最后错误：」这个前缀）。
     *      只换成后半句的话 noTwoCausesShareOneMessage <b>不会</b>红 —— 它比的是「逐字相等」，
     *      差一个前缀就不相等了；那样只能验到 eachCauseNamesItself 一条，验不到本类真正的要害。
     *   2) mvn -o -pl enlev-module-ai test -Dtest=AiUtilsValidateApiKeyTest → EXIT_CODE=1
     *      实测 Tests run: 6, Failures: 2 —— 恰好 eachCauseNamesItself 与 noTwoCausesShareOneMessage 各红一条。
     *   3) 改回来，复跑 → EXIT_CODE=0
     * </pre>
     *
     * <p>⚠️ 另外 5 条<b>本来就该保持绿</b>，别看到它们绿就以为探针没生效：
     * 三条 {@code validateApiKey_*} 与 {@code isApiKeyBlankIsTheSingleAuthority} 断的是
     * 「有没有抛、码对不对」，与文案无关；
     * 而本条（{@code assertionsAreAboutTheWordsNotJustTheThrow}）比的是
     * {@code API_KEY_BLANK.getMsg()} 与 {@code ex.getMessage()} —— 变异让<b>两边一起</b>变，
     * 所以它照样绿。它守的是另一件事：抛点没有另拼一份可漂的副本。
     */
    @Test
    @DisplayName("反向自检：这套断言比的是文案本身，不是「有没有抛异常」")
    void assertionsAreAboutTheWordsNotJustTheThrow() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> AiUtils.validateApiKey(AiUtils.API_KEY_PLACEHOLDER));
        assertEquals(API_KEY_BLANK.getMsg(), ex.getMessage(),
                "本方法的文案必须逐字来自 API_KEY_BLANK —— 在抛点另拼一句话，就等于又开了一份可漂的副本");
    }

    /**
     * 🔴 「什么算没填」只有一把判据：{@link AiUtils#isApiKeyBlank(String)}
     *
     * <p>抽出谓词是为了让「{@code validateApiKey} 抛不抛」与「候选链要不要剔掉这个模型」
     * 用同一句话（后者在 {@code AiModelFailoverServiceImpl} 第 5 步与 {@code buildPinnedChain}）。
     * 两处各写一遍 {@code isBlank || placeholder} 的话，哪天多加一种占位值就只改得掉一处 ——
     * 那时的形态正是「单测全绿、线上照旧把密钥没填误报成没有可用模型」。
     * 本条把两者绑死：谓词说没填的必须抛带码的，谓词放行的必须不抛。
     */
    @Test
    @DisplayName("🔴 isApiKeyBlank 与 validateApiKey 是同一把判据（不许各写一遍）")
    void isApiKeyBlankIsTheSingleAuthority() {
        for (String each : new String[]{null, "", "   ", AiUtils.API_KEY_PLACEHOLDER, "sk-xxxxx",
                "sk-real-key-0123456789", "${OPENAI_API_KEY}"}) {
            if (AiUtils.isApiKeyBlank(each)) {
                assertEquals(API_KEY_BLANK.getCode(), assertThrows(ServiceException.class,
                        () -> AiUtils.validateApiKey(each), "谓词说没填就该拦下：" + each).getCode(),
                        "成因要靠码认：" + each);
            } else {
                assertDoesNotThrow(() -> AiUtils.validateApiKey(each), "谓词放行的不许抛：" + each);
            }
        }
        // ${} 占位符刻意<b>不算</b>「没填」：它由 resolveSpringPlaceholders 报
        // API_CONFIG_PLACEHOLDER_NOT_RESOLVED。两个成因合并的话，管理员会去「把密钥重填一遍」，
        // 而真正的问题是那个环境变量没配 —— 重填多少次都一样
        assertFalse(AiUtils.isApiKeyBlank("${OPENAI_API_KEY}"), "「填了但解析不出来」≠「没填」，各有自己的码");
        assertFalse(AiUtils.isApiKeyBlank("sk-xxxxx"),
                "占位值按<b>逐字相等</b>判，不是前缀匹配：真密钥里出现 sk-xxxx 开头是合法的");
    }

}
