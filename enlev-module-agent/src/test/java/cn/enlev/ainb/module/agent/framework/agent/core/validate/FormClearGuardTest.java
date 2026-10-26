package cn.enlev.ainb.module.agent.framework.agent.core.validate;

import cn.enlev.ainb.module.infra.api.formdesign.dto.FormDesignSaveReqDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 表单静默清空防线的单元测试
 *
 * <h3>这个类守的缺陷，用户要过好几天才会发现</h3>
 * AI 改了个表单标题，顺手把用户配了半天的联动规则、校验规则、自定义按钮、
 * 打印模板<b>全清了</b>。接口返回成功，界面上看不出任何异常，
 * 直到某天有人问「为什么这个字段不联动了」。
 *
 * <p>那时也没得救：版本快照只在 publish 时产生，草稿期的改动无快照可回滚。
 *
 * @author 走进新时代
 */
public class FormClearGuardTest {

    private final FormClearGuard guard = new FormClearGuard();

    /** 一份配置齐全的表单 */
    private FormDesignSaveReqDTO fullForm() {
        FormDesignSaveReqDTO dto = new FormDesignSaveReqDTO();
        dto.setId(1L);
        dto.setName("采购申请");
        dto.setCode("purchase_request");
        dto.setFormConf("{\"rule\":[]}");
        dto.setListConf("{\"columns\":[]}");
        dto.setLogicRules("[{\"when\":\"amount>1000\"}]");
        dto.setValidateRules("[{\"field\":\"amount\"}]");
        dto.setCustomButtons("[{\"name\":\"导出\"}]");
        dto.setDetailTabs("[{\"title\":\"明细\"}]");
        dto.setPrintTemplate("<html></html>");
        dto.setFormScript("console.log(1)");
        dto.setRemark("备注");
        return dto;
    }

    /** 模拟 AI 按「我只改标题」的直觉提交的局部 DTO */
    private FormDesignSaveReqDTO partialUpdate() {
        FormDesignSaveReqDTO dto = new FormDesignSaveReqDTO();
        dto.setId(1L);
        dto.setName("采购申请单");   // 只改了这个
        dto.setCode("purchase_request");
        dto.setFormConf("{\"rule\":[]}");
        return dto;
    }

    /**
     * 🔴 这是本类的核心：把「只改标题」的后果显式地摆出来
     */
    @Test
    @DisplayName("🔴 只改标题的局部提交，会让 8 个配置项全部被静默清空")
    public void testPartialUpdateClearsEverything() {
        FormClearGuard.ClearDiff diff = guard.diff(fullForm(), partialUpdate());

        assertFalse(diff.isEmpty());
        assertEquals(8, diff.getCleared().size(),
                "8 个可清空字段本来都有值，局部提交后全会没：" + diff.getCleared());
        assertTrue(diff.getCleared().containsAll(FormDesignSaveReqDTO.CLEARABLE_FIELDS));
    }

    @Test
    @DisplayName("补回之后，没动过的配置原样保留，改过的字段不受影响")
    public void testRestoreKeepsUntouchedAndRespectsChange() {
        FormDesignSaveReqDTO original = fullForm();
        FormDesignSaveReqDTO updated = partialUpdate();

        List<String> restored = guard.restoreCleared(original, updated);

        assertEquals(8, restored.size());
        assertEquals("[{\"when\":\"amount>1000\"}]", updated.getLogicRules());
        assertEquals("<html></html>", updated.getPrintTemplate());
        // 真正想改的那个字段不能被覆盖回去
        assertEquals("采购申请单", updated.getName());
    }

    @Test
    @DisplayName("原本就是空的不算被清空 —— 不能凭空报一堆假警告")
    public void testEmptyOriginalNotReported() {
        FormDesignSaveReqDTO original = new FormDesignSaveReqDTO();
        original.setId(1L);
        original.setLogicRules("");        // 空串
        original.setPrintTemplate(null);   // null

        FormDesignSaveReqDTO updated = new FormDesignSaveReqDTO();
        updated.setId(1L);

        assertTrue(guard.diff(original, updated).isEmpty());
    }

    @Test
    @DisplayName("新增配置（原本没有 → 现在有了）不算清空")
    public void testAddingConfigNotReported() {
        FormDesignSaveReqDTO original = new FormDesignSaveReqDTO();
        original.setId(1L);

        FormDesignSaveReqDTO updated = new FormDesignSaveReqDTO();
        updated.setId(1L);
        updated.setLogicRules("[{\"when\":\"x\"}]");

        assertTrue(guard.diff(original, updated).isEmpty());
    }

    @Test
    @DisplayName("只清空其中一项时，只报那一项")
    public void testPartialClear() {
        FormDesignSaveReqDTO original = fullForm();
        FormDesignSaveReqDTO updated = fullForm();
        updated.setPrintTemplate(null);

        FormClearGuard.ClearDiff diff = guard.diff(original, updated);

        assertEquals(List.of("printTemplate"), diff.getCleared());
    }

    /**
     * 原值要带出来：只说「printTemplate 会被清空」，用户无从判断该不该同意
     */
    @Test
    @DisplayName("差异里要带上原值，Plan 卡片才能显示「本来是什么」")
    public void testOriginalValueCarried() {
        FormClearGuard.ClearDiff diff = guard.diff(fullForm(), partialUpdate());

        assertEquals("<html></html>", diff.getOriginalValues().get("printTemplate"));
        assertEquals("[{\"when\":\"amount>1000\"}]", diff.getOriginalValues().get("logicRules"));
    }

    @Test
    @DisplayName("字段名要能翻译成人话 —— 用户不知道 logicRules 是什么")
    public void testDescribe() {
        assertEquals("联动规则", FormClearGuard.describe("logicRules"));
        assertEquals("打印模板", FormClearGuard.describe("printTemplate"));
        assertEquals("前端脚本", FormClearGuard.describe("formScript"));
        // 没覆盖到的字段原样返回，不能返回 null 把卡片搞成空白
        assertEquals("unknownField", FormClearGuard.describe("unknownField"));
    }

    /**
     * 🔴 防漂移：CLEARABLE_FIELDS 与 DTO 的 getter 必须对得上
     *
     * 这份名单是照着 {@code clearableColumnsWrapper} 抄的。
     * 哪天后端加了第 9 个可清空列而这里没跟上，防线就在那一列上失效了 ——
     * 而失效的表现是「偏偏那一项配置总是丢」。
     */
    @Test
    @DisplayName("🔴 CLEARABLE_FIELDS 里的每个字段都要在 DTO 上真实存在")
    public void testClearableFieldsMatchDto() {
        for (String field : FormDesignSaveReqDTO.CLEARABLE_FIELDS) {
            assertDoesNotThrow(() -> {
                FormDesignSaveReqDTO.class.getMethod(
                        "get" + Character.toUpperCase(field.charAt(0)) + field.substring(1));
                FormDesignSaveReqDTO.class.getMethod(
                        "set" + Character.toUpperCase(field.charAt(0)) + field.substring(1),
                        String.class);
            }, "字段 " + field + " 在 DTO 上不存在或类型不是 String");
        }
    }

}
