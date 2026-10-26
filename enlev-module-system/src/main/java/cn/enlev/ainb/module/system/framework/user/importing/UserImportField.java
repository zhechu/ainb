package cn.enlev.ainb.module.system.framework.user.importing;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.module.system.enums.DictTypeConstants;
import lombok.Getter;

import java.util.Arrays;

/**
 * 用户导入支持的列
 *
 * <h3>为什么单开一份而不是反射 {@code UserImportExcelVO} 上的注解</h3>
 *
 * 预检要用的信息比 {@code @ExcelProperty} 能表达的多：这一列必不必填、
 * 是不是字典列、字典类型是什么、解析不掉时该说什么。把它们塞进注解要么加一堆自定义注解，
 * 要么在解析处写一串 {@code if (field.equals("sex"))} —— 后者正是「加一列要改三个地方」的由来。
 *
 * ⚠️ 这里的 {@link #label} 必须与 {@code UserImportExcelVO} 上的 {@code @ExcelProperty}
 * 完全一致：模板是拿那个 VO 生成的，两边对不上就会出现「下载了模板、原样填好、
 * 一列都识别不出来」——而用户完全无从判断是自己填错了还是系统坏了。
 *
 * @author 芋道源码
 */
@Getter
public enum UserImportField {

    // ⚠️ 只有登录名是必填的，别的都不是 —— 这与 UserSaveReqVO 上的约束一致。
    // 把「用户名称」也标成必填看着更规整，但那样预检会判 INVALID 的行，
    // 执行时其实能成功：预检说的和真实发生的不一样，比预检不够严格糟得多
    USERNAME("username", "登录名称", true, null),
    NICKNAME("nickname", "用户名称", false, null),
    DEPT_ID("deptId", "部门编号", false, null),
    EMAIL("email", "用户邮箱", false, null),
    MOBILE("mobile", "手机号码", false, null),
    SEX("sex", "用户性别", false, DictTypeConstants.USER_SEX),
    STATUS("status", "账号状态", false, DictTypeConstants.COMMON_STATUS);

    /**
     * 字段名，与 {@code UserImportExcelVO} 的属性名一致
     */
    private final String field;

    /**
     * 列标题，与 {@code UserImportExcelVO} 上的 {@code @ExcelProperty} 一致
     */
    private final String label;

    /**
     * 必填。缺这一列的话整份文件都导不了
     */
    private final boolean required;

    /**
     * 字典类型。非字典列为 null
     */
    private final String dictType;

    UserImportField(String field, String label, boolean required, String dictType) {
        this.field = field;
        this.label = label;
        this.required = required;
        this.dictType = dictType;
    }

    public boolean isDict() {
        return StrUtil.isNotBlank(dictType);
    }

    /**
     * 按表头文本找列
     *
     * <h3>匹配规则：去空白 + 忽略大小写 + 允许带星号</h3>
     *
     * 用户常会在必填列的标题上自己加一个「*」，或者从别处复制过来带了不可见空白。
     * 严格相等的话这些文件一列都识别不出来，而用户看着自己的表头明明是对的。
     *
     * 刻意<b>不</b>做模糊匹配（编辑距离、包含关系那类）：把「手机号码2」匹配到「手机号码」
     * 会把一列备注数据静默写进手机号字段，而这种错没人会去核对。
     */
    public static UserImportField ofHeader(String header) {
        String normalized = normalize(header);
        if (normalized == null) {
            return null;
        }
        return Arrays.stream(values())
                .filter(it -> normalized.equals(normalize(it.label)) || normalized.equals(normalize(it.field)))
                .findFirst().orElse(null);
    }

    private static String normalize(String text) {
        if (StrUtil.isBlank(text)) {
            return null;
        }
        String result = StrUtil.removeAll(StrUtil.trim(text), '*', '＊', ' ', ' ', '\t', '\r', '\n');
        return StrUtil.isBlank(result) ? null : result.toLowerCase();
    }

    /**
     * 列序号 → Excel 的列字母（A、B、…、AA）
     *
     * 报错时给字母而不是序号：用户是拿 Excel 顶上那一排字母去定位的，
     * 跟他说「第 3 列」他还要自己数一遍
     */
    public static String columnLetter(int columnIndex) {
        StringBuilder result = new StringBuilder();
        int index = columnIndex;
        while (index >= 0) {
            result.insert(0, (char) ('A' + index % 26));
            index = index / 26 - 1;
        }
        return result.toString();
    }

}
