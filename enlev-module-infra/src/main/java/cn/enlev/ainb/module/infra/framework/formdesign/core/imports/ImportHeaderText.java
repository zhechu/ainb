package cn.enlev.ainb.module.infra.framework.formdesign.core.imports;

import cn.hutool.core.util.StrUtil;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 表头名字归一化
 *
 * <h3>为什么需要它</h3>
 *
 * 导入时表头靠**名字**匹配字段，但同一个字段在下面几个地方的名字可以都不一样：
 *
 * <ul>
 *   <li>表单里的 {@code title}（项目编号）</li>
 *   <li>字段的 {@code field}（project_code）</li>
 *   <li>列表列的 {@code label}（编号（含年份））</li>
 * </ul>
 *
 * 而用户在 Excel 里手打表头时还会带上各种装饰与全半角差异：
 * 「项目编号 *」「项目编号（必填）」「 项目编号 」「项目编号:」「项目编号：」。
 *
 * 如果直接字符串相等，上面这些**全部匹配不上**，用户看到的是
 * 「一列都没识别出来」或者更糟——识别出来但串到别的字段上去了。
 *
 * <h3>为什么归一化要大写化与去括号</h3>
 *
 * 英文表头的大小写不敏感是常识（CODE / code / Code 都是同一个）。
 * 中文里跟在括号里的通常是限定语（「金额（元）」「金额(元)」），
 * 用户从别处复制表头过来，括号里的字常常与字段定义不一致。
 * 去掉尾部的括号段能显著提高命中率，代价是同名前缀的字段会冲突 ——
 * 那种情况由调用方的冲突检测兜住，不会静默选错。
 *
 * @author 走进新时代
 */
public final class ImportHeaderText {

    private ImportHeaderText() {
    }

    /**
     * 归一化一个表头
     *
     * @param raw 原始表头文本
     * @return 归一化结果，空串表示这个表头没有内容
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.trim();

        // 1. 全角转半角。Excel 里手打的表头经常是中文输入法下带出来的全角字符，
        //    「：」「（」「＊」这些与半角不是同一个字符
        text = toHalfWidth(text);

        // 2. 去掉表示「必填」的装饰。设计器与模板都在必填列上打星号，
        //    用户从模板复制出来再改的时候星号常常还在
        text = text.replace("*", "").replace("＊", "");

        // 3. 去掉尾部的括号段，反复去 —— 「金额（元）（含税）」要一起去掉。
        //    只去尾部，不从中间去：「单价（元）/数量」这种复合表头不该被压成「单价/数量」——
        //    它本来就匹配不上任何单个字段，压了反而可能撞上别的字段
        text = stripTrailingBrackets(text);

        // 4. 去掉尾部的冒号
        while (text.endsWith(":") || text.endsWith("：")) {
            text = text.substring(0, text.length() - 1).trim();
        }

        // 5. 去掉所有空白（含全角空格）。「项 目 编 号」是排版时误加的，
        //    按内容算它就是「项目编号」
        text = text.replaceAll("[\\s\\u3000]+", "");
        text = text.replace("_", "").replace("-", "");

        return text.toLowerCase();
    }

    /**
     * 去掉尾部的成对括号段
     *
     * 「项目编号（必填）」→「项目编号」；「项目编号（必填」（括号没闭合）也去 ——
     * 用户手打的表头经常只打半边括号
     */
    private static String stripTrailingBrackets(String text) {
        String result = text.trim();
        while (true) {
            String next = stripOneTrailingBracket(result);
            if (next.equals(result)) {
                return result;
            }
            result = next;
        }
    }

    private static String stripOneTrailingBracket(String text) {
        int end = text.length();
        if (end == 0) {
            return text;
        }
        char last = text.charAt(end - 1);
        char close = switch (last) {
            case '）' -> '（';
            case ')' -> '(';
            case '】' -> '【';
            case ']' -> '[';
            default -> 0;
        };
        if (close == 0) {
            return text;
        }
        // 从尾部往前找配对的左括号。找不到就说明只有半边，把尾部这一段全砍掉
        for (int i = end - 2; i >= 0; i--) {
            if (text.charAt(i) == close) {
                return text.substring(0, i).trim();
            }
        }
        return text.substring(0, end - 1).trim();
    }

    /**
     * 全角转半角
     *
     * 只转 ASCII 可见字符区间（全角 0xFF01~0xFF5E ↔ 半角 0x21~0x7E），
     * 中文全角标点（，。：；）不在这个区间里，由上面的去冒号逻辑单独处理。
     * 它们各自的半角形态在中文语境里是另一个意思，不该一律转换。
     */
    private static String toHalfWidth(String text) {
        char[] chars = text.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            if (chars[i] == '　') {
                chars[i] = ' ';
            } else if (chars[i] >= '！' && chars[i] <= '～') {
                chars[i] = (char) (chars[i] - 0xFEE0);
            }
        }
        return new String(chars);
    }

    /**
     * 按分隔符切分多选值
     *
     * 支持中英文逗号、顿号、分号、竖线、空格 —— 用户在 Excel 里填多选值时
     * 用什么分隔的都有，只认逗号的话其它几种会被当成一个整体值存进去，
     * 表现为「选项匹配不上但在库里又看得见」。
     *
     * @param raw 原始文本
     * @return 去空去重后的值列表，输入为空时返回空列表
     */
    public static List<String> splitMultiValue(String raw) {
        if (StrUtil.isBlank(raw)) {
            return Collections.emptyList();
        }
        Set<String> values = new LinkedHashSet<>();
        for (String part : raw.split("[,，、;；|\\s]+")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                values.add(trimmed);
            }
        }
        return List.copyOf(values);
    }

}
