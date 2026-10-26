package cn.enlev.ainb.module.infra.service.formdesign;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 墓碑编码的单元测试
 *
 * <h3>这里守的是「删掉的表单必须把编码让出来」</h3>
 * {@code infra_form_design.uk_code} 是<b>不含 deleted</b> 的全局唯一索引，
 * 而删除是逻辑删除 —— 只置 {@code deleted=1} 的话，那条谁也看不见的墓碑会一直占着编码。
 * 实测后果：删掉「设备管理」再跑一次建表流程，{@code form_save_draft} 抛出一整段
 * {@code Duplicate entry 'device_management' for key 'uk_code'} 的 MyBatis 异常，
 * 而页面上根本查不到是谁占的（{@code selectByCode} 自带 {@code deleted=0}）。
 *
 * @author 走进新时代
 */
public class FormDesignTombstoneCodeTest {

    /** 与 {@code infra_form_design.code} 的列宽一致 */
    private static final int CODE_MAX_LENGTH = 100;

    @Test
    @DisplayName("墓碑编码带上 id，同一个编码反复建删也不会互相撞")
    public void testTombstoneCarriesId() {
        assertEquals("device_management_del_880469",
                FormDesignServiceImpl.tombstoneCode("device_management", 880469L));
        assertEquals("device_management_del_880470",
                FormDesignServiceImpl.tombstoneCode("device_management", 880470L));
    }

    /**
     * 🔴 超列宽就是删除操作本身撞 Data truncation —— 表单删不掉，
     *    比编码被占住更难查（报错发生在删除路径上，谁也想不到是编码太长）
     */
    @Test
    @DisplayName("🔴 原编码很长时截断，墓碑编码不超列宽")
    public void testTombstoneFitsColumn() {
        String longCode = "f".repeat(CODE_MAX_LENGTH);

        String tombstone = FormDesignServiceImpl.tombstoneCode(longCode, 880469L);

        assertTrue(tombstone.length() <= CODE_MAX_LENGTH,
                "必须 <= 列宽 " + CODE_MAX_LENGTH + "，实际 " + tombstone.length());
        assertTrue(tombstone.endsWith("_del_880469"), "后缀不能被截掉，否则失去唯一性：" + tombstone);
    }

    @Test
    @DisplayName("原编码不长时原样保留前缀，删掉的是哪个表单一眼能认出来")
    public void testTombstoneKeepsReadablePrefix() {
        String tombstone = FormDesignServiceImpl.tombstoneCode("device_management", 1L);

        assertTrue(tombstone.startsWith("device_management"), tombstone);
    }

}
