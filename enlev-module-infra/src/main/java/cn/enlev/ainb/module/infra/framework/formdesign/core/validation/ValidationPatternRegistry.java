package cn.enlev.ainb.module.infra.framework.formdesign.core.validation;

import cn.hutool.core.io.resource.ResourceUtil;
import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.common.util.json.JsonUtils;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 内置正则库
 *
 * 启动时从 classpath 加载 form-design/validation-patterns.json，之后按 key 查询已编译的正则。
 *
 * <h3>为什么正则库要单独成文件而不是写成 Java 枚举</h3>
 *
 * 前端也要用同一批正则 —— 校验必须在前端即时反馈（失焦就提示，不能等提交），
 * 同时又必须在服务端复校验（构造请求能绕过前端）。两侧各写一份正则是必然漂移的：
 * 手机号在前端放过、在后端拦下，表现为「界面上填好了但提交失败」，
 * 而用户完全看不出哪里不对。
 *
 * 所以正则的唯一事实来源是这份 JSON，前端持一份构建期副本，一致性由测试兜住 ——
 * 与控件契约完全相同的机制，见 resources/form-design/SYNC.md。
 *
 * <h3>加载失败的处理与契约不同</h3>
 *
 * 控件契约加载失败会让应用起不来（它是整个表单模块的地基）。正则库不一样：
 * 它只影响「配了格式校验的字段」，缺了它其余功能照常。所以这里降级为
 * 空库 + error 日志，而不是拒绝启动 —— 让一份正则库的手误把整个系统拖下线，
 * 代价与收益不匹配。
 *
 * 但单条正则编译失败必须在启动时暴露（见 {@link #compile}）：那是我们自己写错了，
 * 不是用户输入，而且它在运行时的表现是「该字段的校验静默失效」。
 *
 * @author 走进新时代
 */
@Component
@Slf4j
public class ValidationPatternRegistry {

    /**
     * 正则库文件在 classpath 下的位置
     */
    private static final String PATTERN_RESOURCE = "form-design/validation-patterns.json";

    /**
     * key → 内置正则。用 LinkedHashMap 保持 JSON 里的声明顺序，
     * 便于接口返回给前端时下拉框顺序稳定
     */
    private Map<String, BuiltinPattern> patternByKey = Collections.emptyMap();

    /**
     * 正则库版本号
     */
    private Integer libraryVersion;

    /**
     * 用户自定义正则的编译缓存
     *
     * 自定义正则来自表单配置，每次提交都重新编译一遍很浪费 —— 一张表单可能有
     * 十几个字段各带正则，而正则编译不是免费的。
     *
     * 用有界的做法：超过上限就不再往里放（而不是清空重建）。
     * 无界缓存 + 来自配置的 key 意味着「改一次正则就多一条永不释放的条目」，
     * 长期运行会慢慢吃掉堆。
     */
    private final Map<String, Pattern> customPatternCache = new ConcurrentHashMap<>();

    /**
     * 自定义正则缓存的条目上限
     *
     * 取 500：正常一个租户的全部表单加起来也用不到这么多不同的正则。
     * 到顶之后新正则改为每次编译 —— 慢一点，但不会无限增长。
     */
    private static final int CUSTOM_CACHE_LIMIT = 500;

    @PostConstruct
    public void init() {
        String json = ResourceUtil.readUtf8Str(PATTERN_RESOURCE);
        if (StrUtil.isBlank(json)) {
            log.error("[init][内置正则库文件为空({})，配了内置格式校验的字段将不做校验]", PATTERN_RESOURCE);
            return;
        }
        PatternFile file = JsonUtils.parseObjectQuietly(json, PatternFile.class);
        if (file == null || file.getPatterns() == null || file.getPatterns().isEmpty()) {
            log.error("[init][内置正则库解析失败或为空({})]", PATTERN_RESOURCE);
            return;
        }

        Map<String, BuiltinPattern> map = new LinkedHashMap<>();
        Set<String> duplicated = new HashSet<>();
        for (BuiltinPattern item : file.getPatterns()) {
            if (StrUtil.isBlank(item.getKey()) || StrUtil.isBlank(item.getPattern())) {
                log.error("[init][内置正则库存在缺 key 或缺 pattern 的条目，已跳过：{}]", item.getKey());
                continue;
            }
            // 启动时就编译：正则写错在运行时的表现是「这个字段的校验静默失效」，
            // 没有任何报错，而配置的人以为它在生效。启动日志里报出来才有机会被发现
            item.setCompiled(compile(item.getKey(), item.getPattern()));
            if (item.getCompiled() == null) {
                continue;
            }
            if (map.put(item.getKey().toUpperCase(), item) != null) {
                duplicated.add(item.getKey());
            }
        }
        if (!duplicated.isEmpty()) {
            // 重复 key 会让后放入的悄悄覆盖前一条。不拒绝启动，但必须报出来
            log.error("[init][内置正则库存在重复的 key，后者已覆盖前者：{}]", duplicated);
        }

        this.patternByKey = Collections.unmodifiableMap(map);
        this.libraryVersion = file.getLibraryVersion();
        log.info("[init][内置正则库加载完成，版本 {}，共 {} 条]", libraryVersion, patternByKey.size());
    }

    /**
     * 按 key 取内置正则
     *
     * @param key 正则 key，大小写不敏感
     * @return 内置正则；不存在返回 null
     */
    public BuiltinPattern get(String key) {
        return StrUtil.isBlank(key) ? null : patternByKey.get(key.toUpperCase());
    }

    /**
     * 全部内置正则，按声明顺序
     */
    public List<BuiltinPattern> listAll() {
        return List.copyOf(patternByKey.values());
    }

    /**
     * 正则库版本号
     */
    public Integer getLibraryVersion() {
        return libraryVersion;
    }

    /**
     * 编译用户自定义正则
     *
     * @param regex 正则字符串
     * @return 编译结果；语法错误返回 null，调用方按「该条校验不生效」处理
     */
    public Pattern compileCustom(String regex) {
        if (StrUtil.isBlank(regex)) {
            return null;
        }
        Pattern cached = customPatternCache.get(regex);
        if (cached != null) {
            return cached;
        }
        Pattern compiled = compile("custom", regex);
        if (compiled != null && customPatternCache.size() < CUSTOM_CACHE_LIMIT) {
            customPatternCache.put(regex, compiled);
        }
        return compiled;
    }

    /**
     * 编译一条正则
     *
     * 语法错误返回 null 而不是抛异常：
     * <ul>
     *   <li>自定义正则来自表单配置，一个写错的正则不该让整张表单提交不了 ——
     *       那会把「校验配错」升级成「表单不可用」</li>
     *   <li>但必须记 error 日志。静默跳过等于悄悄放弃了这条约束，
     *       而配置的人以为它在生效</li>
     * </ul>
     */
    private Pattern compile(String key, String regex) {
        try {
            return Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            log.error("[compile][正则({})语法错误，该条校验不会生效：{}]", key, regex, e);
            return null;
        }
    }

    // ==================== 正则库文件的反序列化结构 ====================

    /**
     * 正则库文件根结构
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PatternFile {

        private Integer libraryVersion;
        private List<BuiltinPattern> patterns;

    }

    /**
     * 一条内置正则
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BuiltinPattern {

        /**
         * 唯一 key，字段配置里引用它
         */
        private String key;
        /**
         * 展示名，设计器下拉框里显示
         */
        private String label;
        /**
         * 正则字符串。必须落在 Java 与 JS 的公共子集内，见正则库文件的说明
         */
        private String pattern;
        /**
         * 兜底错误提示。字段上配了自定义提示时用字段的
         */
        private String message;

        /**
         * 编译后的正则，启动时填充
         *
         * {@code @JsonIgnore} 是必需的，不是洁癖：本类会被
         * {@code GET /validation-patterns} 直接序列化下发，而 {@code transient}
         * 只对 Java 原生序列化有效、Jackson 不认它 —— 少了这个注解，
         * 接口返回里会多出一个与 pattern 内容重复的 compiled 字段，
         * 而前端副本里没有它。多余字段一旦发出去就可能被人依赖上。
         */
        @JsonIgnore
        private transient Pattern compiled;

        /**
         * 值是否匹配
         *
         * 正则没编译成功时返回 true（放过）—— 我们自己的正则写错了，
         * 不该表现为用户的输入被拒绝。
         */
        public boolean matches(String value) {
            return compiled == null || compiled.matcher(value).matches();
        }

    }

}
