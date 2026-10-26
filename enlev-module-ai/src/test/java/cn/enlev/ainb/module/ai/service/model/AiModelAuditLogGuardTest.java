package cn.enlev.ainb.module.ai.service.model;

import cn.enlev.ainb.module.ai.controller.admin.model.vo.apikey.AiApiKeySaveReqVO;
import cn.enlev.ainb.module.ai.controller.admin.model.vo.model.AiModelSaveReqVO;
import cn.enlev.ainb.module.ai.enums.LogRecordConstants;
import com.mzt.logapi.starter.annotation.DiffLogField;
import com.mzt.logapi.starter.annotation.LogRecord;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AI 模型 / 密钥「变更审计」的两条守卫。
 *
 * <p>为什么要有：这两张表是共享配置，改一下就会换掉所有调用方实际用的模型，而 2026-10-08 之前
 * 管理端改它们在 {@code system_operate_log} 里留 0 行痕迹 —— 那一轮 T1 跑到一半模型被停用、
 * 27 分钟真跑整轮作废，事后连「谁改的」都问不出来。补上审计之后，这两个失效模式必须被钉住：
 *
 * <ol>
 *   <li><b>密钥泄进日志</b>：{@code apiKey} 一旦被加上 {@code @DiffLogField}，厂商密钥原文就会明文落库，
 *       进而进备份、进每一次日志导出。{@link #apiKeyMustNeverBeDiffLogged()} 看守。</li>
 *   <li><b>审计悄悄失效</b>：{@code @DiffLogField} 少了、或模板里变量名打错一个字母，
 *       都不会让任何测试变红 —— 日志照写，只是内容空了 / 运行期抛 SpEL 解析失败。
 *       {@link #modelAuditMustCoverTheFieldsThatBurnedUs()} 与
 *       {@link #logRecordTemplatesMustOnlyReferenceKnownVariables()} 看守。</li>
 * </ol>
 *
 * <p>纯反射，不起 Spring、不连库、不打厂商接口。
 */
class AiModelAuditLogGuardTest {

    /** 模板里允许出现的变量根名。多一个字母都会让运行期 SpEL 解析炸掉，所以在编译期就钉住 */
    private static final Set<String> ALLOWED_ROOTS = Set.of("model", "apiKey", "updateReqVO", "id");

    /**
     * 必须挂审计的写入方法全集：模型 / 密钥 各「增、改、删、改状态」。
     *
     * <p>🔴 这里刻意用【方法名集合】而不是裸计数。计数只能发现「多了/少了」，
     * 集合还能发现「审计挂到了错的方法上」—— 严格更强。
     *
     * <p>为什么是 8 个而不是原来的 6 个：列表页的启用/停用按钮走独立的
     * {@code update-status} 端点（只写 status 一个字段），因为 {@code AiApiKeyRespVO.apiKey}
     * 带 {@code @SliderDesensitize}，把整行回传 {@code /update} 会将打码串当成真密钥落库。
     * 而 {@code status} 恰恰是 2026-10-08 那轮「T1 跑到一半模型被停用、27 分钟作废、
     * 事后问不出谁改的」的字段 —— 新增这两个写入方法必须一并挂上审计，这是合法增长，不是守卫失效。
     */
    private static final Set<String> AUDITED_METHODS = Set.of(
            "createModel", "updateModel", "deleteModel", "updateModelStatus",
            "createApiKey", "updateApiKey", "deleteApiKey", "updateApiKeyStatus");

    /** 匹配 {@code {{#root.xxx}}} 与 {@code {_DIFF{#root}}} 里的变量根名 */
    private static final Pattern VARIABLE_ROOT = Pattern.compile("#([A-Za-z_][A-Za-z0-9_]*)");

    @Test
    void apiKeyMustNeverBeDiffLogged() throws NoSuchFieldException {
        Field apiKey = AiApiKeySaveReqVO.class.getDeclaredField("apiKey");
        DiffLogField diffLogField = apiKey.getAnnotation(DiffLogField.class);
        // 🔴 这不是风格问题：操作日志明文落库，给它打注解 = 把厂商密钥永久写进 system_operate_log
        assertTrue(diffLogField == null,
                "apiKey 字段【绝不能】带 @DiffLogField —— 那会让厂商密钥明文落进 system_operate_log");

        // 反向确认这套审计真的在干活（否则上面那条会因为「一个注解都没有」而假绿）
        assertNotNull(AiApiKeySaveReqVO.class.getDeclaredField("url").getAnnotation(DiffLogField.class),
                "url 必须可审计：链首模型的确定性失败原文就是「url error」，而它改没改过之前一个字都查不到");
        assertNotNull(AiApiKeySaveReqVO.class.getDeclaredField("status").getAnnotation(DiffLogField.class),
                "status 必须可审计");
    }

    @Test
    void noTemplateMayReferenceTheSecretField() throws IllegalAccessException {
        for (Field field : LogRecordConstants.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || field.getType() != String.class) {
                continue;
            }
            String template = (String) field.get(null);
            // 变量名本身就叫 apiKey，所以不能简单判「含 apiKey」；要判的是【取它的 apiKey 属性】
            assertFalse(template.contains("#apiKey.apiKey"),
                    "模板 " + field.getName() + " 取用了密钥原文：" + template);
        }
    }

    @Test
    void modelAuditMustCoverTheFieldsThatBurnedUs() throws NoSuchFieldException {
        // status / sort 是候选链链首的唯一决定因素（status=0 启用 + ORDER BY sort ASC, id ASC），
        // 也正是那一轮被静默改掉、导致整轮作废的两个字段
        DiffLogField status = AiModelSaveReqVO.class.getDeclaredField("status").getAnnotation(DiffLogField.class);
        assertNotNull(status, "status 必须可审计");
        // 0=启用 / 1=停用 是反直觉的，本轮已经有人读反过一次、整份「启用模型清单」为此作废，
        // 所以极性必须写在字段名里，让读日志的人不可能再读反
        assertTrue(status.name().contains("0=启用") && status.name().contains("1=停用"),
                "status 的 @DiffLogField.name 必须写明极性，实际是：" + status.name());
        assertNotNull(AiModelSaveReqVO.class.getDeclaredField("sort").getAnnotation(DiffLogField.class),
                "sort 必须可审计");
        assertNotNull(AiModelSaveReqVO.class.getDeclaredField("keyId").getAnnotation(DiffLogField.class),
                "keyId 必须可审计：密钥被禁用会让模型在候选链选择期就被剔除");
    }

    @Test
    void logRecordTemplatesMustOnlyReferenceKnownVariables() {
        List<String> offenders = new ArrayList<>();
        Set<String> audited = new HashSet<>();
        for (Class<?> service : List.of(AiModelServiceImpl.class, AiApiKeyServiceImpl.class)) {
            for (Method method : service.getDeclaredMethods()) {
                LogRecord logRecord = method.getAnnotation(LogRecord.class);
                if (logRecord == null) {
                    continue;
                }
                audited.add(method.getName());
                for (String template : List.of(logRecord.bizNo(), logRecord.success())) {
                    Matcher matcher = VARIABLE_ROOT.matcher(template);
                    while (matcher.find()) {
                        String root = matcher.group(1);
                        if (!ALLOWED_ROOTS.contains(root)) {
                            offenders.add(service.getSimpleName() + "#" + method.getName()
                                    + " 的模板引用了未登记的变量「" + root + "」：" + template);
                        }
                    }
                }
            }
        }
        // 每一个写入方法都必须挂上审计：少一个就是有一类改动查不到，多一个则可能是审计挂错了方法
        Set<String> missing = new HashSet<>(AUDITED_METHODS);
        missing.removeAll(audited);
        Set<String> unexpected = new HashSet<>(audited);
        unexpected.removeAll(AUDITED_METHODS);
        assertTrue(missing.isEmpty() && unexpected.isEmpty(),
                "带 @LogRecord 的方法与登记的全集不符。缺审计：" + missing + "，未登记的：" + unexpected);
        assertTrue(offenders.isEmpty(), () -> String.join("\n", offenders));
    }

}
