package cn.enlev.ainb.module.infra.framework.formdesign.core.widget;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.common.util.json.JsonUtils;
import cn.enlev.ainb.module.infra.framework.formdesign.core.FormFieldsCodec;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 超链接地址的协议白名单
 *
 * <h3>它防的是什么</h3>
 *
 * {@code javascript:alert(1)} 放进 href，填表人一点就执行。而这个地址是
 * <b>表单设计者</b>配的、<b>填表人</b>点的 —— 两者往往不是同一个人，
 * 也不是同一个信任级别。{@code data:text/html,...} 同理，还能整页替换。
 *
 * <h3>为什么是白名单</h3>
 *
 * 黑名单永远列不全：{@code JaVaScRiPt:}、中间塞制表符、前面塞空格、
 * HTML 实体编码……只放行确定安全的那几个协议才收敛。
 *
 * <h3>与前端的分工</h3>
 *
 * 前端 {@code FormWidgets/linkUrl.ts} 有一份等价实现，管即时反馈与运行时渲染；
 * 这一份在<b>发布</b>时校验，是不可绕过的那道。两处都要有：
 * 只有前端的话，改一改请求就能把 javascript: 存进库；
 * 只有服务端的话，用户要等到点发布才知道地址不合法。
 *
 * @author 走进新时代
 */
public final class LinkUrlGuard {

    /**
     * 允许的协议
     *
     * ⚠️ 必须与前端 {@code ALLOWED_LINK_PROTOCOLS} 一致。
     */
    public static final Set<String> ALLOWED_PROTOCOLS = Set.of("http:", "https:", "mailto:", "tel:");

    /**
     * 超链接控件的类型，与控件契约一致
     */
    private static final String LINK_WIDGET_TYPE = "Link";

    private LinkUrlGuard() {
    }

    /**
     * 地址是否安全
     *
     * @param url 地址模板（可以带 {字段} 占位符）
     * @return 安全返回 true。空地址算安全 —— 那只是没配，不是危险
     */
    public static boolean isSafe(String url) {
        if (StrUtil.isBlank(url)) {
            return true;
        }
        // 先去掉空白与控制字符：制表符版的 java<TAB>script: 在浏览器里
        // 照样会被当成 javascript:，不清掉的话下面的前缀判断会被绕过
        StringBuilder cleaned = new StringBuilder(url.length());
        for (char c : url.toCharArray()) {
            if (c > ' ') {
                cleaned.append(Character.toLowerCase(c));
            }
        }
        String text = cleaned.toString();
        if (text.isEmpty()) {
            return true;
        }
        int colon = text.indexOf(':');
        if (colon < 0) {
            // 没有协议部分：相对地址、锚点、查询串，都安全
            return true;
        }
        // 冒号出现在第一个 / ? # 之后的，不是协议分隔符（如 /a:b）
        int separator = Integer.MAX_VALUE;
        for (char c : new char[]{'/', '?', '#'}) {
            int index = text.indexOf(c);
            if (index >= 0) {
                separator = Math.min(separator, index);
            }
        }
        if (colon > separator) {
            return true;
        }
        return ALLOWED_PROTOCOLS.contains(text.substring(0, colon + 1));
    }

    /**
     * 从表单字段规则里找出地址不安全的超链接控件
     *
     * 要递归：控件可能在布局容器或子表里。
     *
     * @return 不安全的控件清单；全部安全时返回空列表
     */
    public static List<UnsafeLink> findUnsafeLinks(String formFieldsJson) {
        List<UnsafeLink> result = new ArrayList<>();
        for (String rule : FormFieldsCodec.read(formFieldsJson)) {
            Map<String, Object> node = JsonUtils.parseObjectQuietly(rule,
                    new TypeReference<Map<String, Object>>() {});
            if (node != null) {
                collect(node, result);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static void collect(Map<String, Object> node, List<UnsafeLink> result) {
        if (LINK_WIDGET_TYPE.equals(node.get("type")) && node.get("props") instanceof Map<?, ?> propsMap) {
            Map<String, Object> props = (Map<String, Object>) propsMap;
            String url = props.get("url") instanceof String s ? s : null;
            if (!isSafe(url)) {
                UnsafeLink unsafe = new UnsafeLink();
                unsafe.setTitle(node.get("title") instanceof String t ? t : "超链接");
                unsafe.setUrl(url);
                result.add(unsafe);
            }
        }
        if (node.get("props") instanceof Map<?, ?> propsMap
                && ((Map<String, Object>) propsMap).get("rule") instanceof List<?> innerRules) {
            for (Object inner : innerRules) {
                if (inner instanceof Map<?, ?> innerMap) {
                    collect((Map<String, Object>) innerMap, result);
                }
            }
        }
        if (node.get("children") instanceof List<?> children) {
            for (Object child : children) {
                if (child instanceof Map<?, ?> childMap) {
                    collect((Map<String, Object>) childMap, result);
                }
            }
        }
    }

    /**
     * 一个地址不安全的超链接控件
     */
    @Data
    public static class UnsafeLink {

        /**
         * 控件标题，用于报错定位。用标题而不是字段 ID —— 用户看不懂 ID
         */
        private String title;
        private String url;

    }

}
