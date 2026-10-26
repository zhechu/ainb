package cn.enlev.ainb.module.infra.api.formdesign;

import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.infra.api.formdesign.dto.FormDesignRespDTO;
import cn.enlev.ainb.module.infra.dal.dataobject.formdesign.FormDesignDO;
import cn.enlev.ainb.module.infra.framework.formdesign.core.permission.FormFieldCropper;
import cn.enlev.ainb.module.infra.service.formdesign.FormDesignService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 表单结构的「设计态 / 运行态」之分（TODO #35）
 *
 * <h3>为什么要两份</h3>
 *
 * 在线表单自己的渲染接口早就按字段权限裁过了，但<b>流程侧拿表单走的是这个 API</b>。
 * 一直只有一份不裁剪的结构，于是同一张表单：
 *
 * <pre>
 * 从「表单」菜单进 → HIDDEN 的字段不渲染
 * 从「流程」页面进 → HIDDEN 的字段照常渲染
 * </pre>
 *
 * 而且没有任何提示。用户在那个本不该出现的字段里填了值、提交，
 * 值被写路径的 {@code stripNonWritable} 丢掉 —— 正是本项目最典型的那类缺陷。
 *
 * <h3>为什么不干脆全裁</h3>
 *
 * 流程设计器必须看到<b>全部</b>字段才能在上面配节点字段权限与变量映射。
 * 一律裁剪的话，设计者按自己的权限看到的是一份残缺表单，配置会静默漏配。
 * 所以两份都要有，而且<b>调用方必须显式选</b> —— 这组用例就是钉住这个区别不被"优化"掉。
 *
 * @author 走进新时代
 */
@MockitoSettings(strictness = Strictness.LENIENT)
public class FormDesignApiRuntimeCropTest extends BaseMockitoUnitTest {

    private static final String CODE = "leave_form";
    /**
     * 两条渲染规则，其中 mobile 假设被裁掉
     */
    private static final String FORM_FIELDS =
            "[\"{\\\"field\\\":\\\"reason\\\"}\",\"{\\\"field\\\":\\\"mobile\\\"}\"]";

    @Mock
    private FormDesignService formDesignService;
    @Mock
    private FormFieldCropper fieldCropper;

    @InjectMocks
    private FormDesignApiImpl formDesignApi;

    @BeforeEach
    void setUp() {
        FormDesignDO design = new FormDesignDO();
        design.setCode(CODE);
        design.setName("请假单");
        design.setFormConf("{\"form\":{}}");
        design.setFormFields(FORM_FIELDS);
        when(formDesignService.getFormDesignByCode(CODE)).thenReturn(design);
        // 裁剪器把 mobile 裁掉
        when(fieldCropper.cropFormFields(eq(CODE), any()))
                .thenReturn(List.of("{\"field\":\"reason\"}"));
    }

    @Test
    @DisplayName("⚠️ 设计态不裁剪 —— 流程设计器要看得到全部字段，否则配节点权限时会静默漏配")
    void testDesignTimeNotCropped() {
        FormDesignRespDTO dto = formDesignApi.getFormDesign(CODE);

        assertEquals(2, dto.getFormFields().size());
        verify(fieldCropper, never()).cropFormFields(any(), any());
    }

    @Test
    @DisplayName("🔴 运行态必须裁剪 —— 不裁的话 HIDDEN 字段从流程页面照样渲染出来")
    void testRuntimeCropped() {
        FormDesignRespDTO dto = formDesignApi.getRuntimeFormDesign(CODE);

        assertEquals(1, dto.getFormFields().size());
        assertTrue(dto.getFormFields().get(0).contains("reason"));
        verify(fieldCropper).cropFormFields(eq(CODE), any());
    }

    @Test
    @DisplayName("裁的是 formFields，formConf 原样保留 —— 后者是表单级配置，里面没有字段")
    void testFormConfUntouched() {
        FormDesignRespDTO dto = formDesignApi.getRuntimeFormDesign(CODE);

        assertEquals("{\"form\":{}}", dto.getFormConf());
    }

    @Test
    @DisplayName("表单不存在时两条路径都返回 null，运行态不能因为多一步裁剪就抛异常")
    void testMissingForm() {
        assertNull(formDesignApi.getFormDesign("not_exists"));
        assertNull(formDesignApi.getRuntimeFormDesign("not_exists"));
    }

    @Test
    @DisplayName("列表版本同样是两份：运行态逐个裁，设计态一个都不裁")
    void testListVariants() {
        assertEquals(2, formDesignApi.getFormDesignList(List.of(CODE)).get(0).getFormFields().size());
        verify(fieldCropper, never()).cropFormFields(any(), any());

        assertEquals(1, formDesignApi.getRuntimeFormDesignList(List.of(CODE)).get(0).getFormFields().size());
        verify(fieldCropper).cropFormFields(eq(CODE), any());
    }

}
