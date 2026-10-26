package cn.enlev.ainb.module.system.framework.user.importing;

import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link UserImportField} 的单元测试
 *
 * 表头匹配这件事的两头都要钉住：太松会把「手机号码2」这样的备注列静默写进手机号字段，
 * 太紧则用户自己在标题上加了个星号就一列都识别不出来。两种错用户都发现不了 ——
 * 前者根本不报错，后者报的是「缺少必填列」，看起来像模板的问题。
 *
 * @author 芋道源码
 */
public class UserImportFieldTest extends BaseMockitoUnitTest {

    @Test
    @DisplayName("按模板上的列标题匹配")
    void testMatchByLabel() {
        assertEquals(UserImportField.USERNAME, UserImportField.ofHeader("登录名称"));
        assertEquals(UserImportField.DEPT_ID, UserImportField.ofHeader("部门编号"));
        assertEquals(UserImportField.STATUS, UserImportField.ofHeader("账号状态"));
    }

    @Test
    @DisplayName("也认字段名 —— 从接口文档抄表头的人不少")
    void testMatchByFieldName() {
        assertEquals(UserImportField.USERNAME, UserImportField.ofHeader("username"));
        assertEquals(UserImportField.DEPT_ID, UserImportField.ofHeader("DeptId"));
    }

    @Test
    @DisplayName("容忍用户自己加的星号、前后空白与全角空格")
    void testTolerateNoise() {
        assertEquals(UserImportField.USERNAME, UserImportField.ofHeader("  登录名称 "));
        assertEquals(UserImportField.USERNAME, UserImportField.ofHeader("*登录名称"));
        assertEquals(UserImportField.USERNAME, UserImportField.ofHeader("登录名称＊"));
        assertEquals(UserImportField.MOBILE, UserImportField.ofHeader("手机 号码"));
    }

    @Test
    @DisplayName("不做模糊匹配 —— 把备注列匹配进手机号是没人会去核对的错")
    void testNoFuzzyMatch() {
        assertNull(UserImportField.ofHeader("手机号码2"));
        assertNull(UserImportField.ofHeader("手机"));
        assertNull(UserImportField.ofHeader("备注"));
        assertNull(UserImportField.ofHeader(""));
        assertNull(UserImportField.ofHeader(null));
    }

    @Test
    @DisplayName("只有登录名是必填 —— 与 UserSaveReqVO 上的约束保持一致")
    void testRequired() {
        assertEquals(true, UserImportField.USERNAME.isRequired());
        assertEquals(false, UserImportField.NICKNAME.isRequired());
        assertEquals(false, UserImportField.DEPT_ID.isRequired());
    }

    @Test
    @DisplayName("列字母：用户是拿 Excel 顶上那排字母定位的，不是序号")
    void testColumnLetter() {
        assertEquals("A", UserImportField.columnLetter(0));
        assertEquals("G", UserImportField.columnLetter(6));
        assertEquals("Z", UserImportField.columnLetter(25));
        assertEquals("AA", UserImportField.columnLetter(26));
        assertEquals("AB", UserImportField.columnLetter(27));
        assertEquals("BA", UserImportField.columnLetter(52));
    }

}
