package cn.enlev.ainb.module.infra.service.formdesign;

import cn.enlev.ainb.framework.common.exception.ServiceException;
import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.infra.dal.dataobject.formdesign.FormDesignDO;
import cn.enlev.ainb.module.infra.dal.mysql.formdesign.FormDesignMapper;
import cn.enlev.ainb.module.infra.framework.formdesign.core.FormFieldsCodec;
import cn.enlev.ainb.module.infra.framework.formdesign.core.logic.FormLogicGuard;
import cn.enlev.ainb.module.infra.framework.formdesign.core.relation.RelationFormResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * 关联表单的<b>间接</b>循环引用检测
 *
 * <h3>为什么只拦自引用不够</h3>
 *
 * 发布校验原先只拦 {@code A → A}。而 {@code A → B → A} 拦不住：
 * 发布 A 的时候 B 还没关联 A，发布 B 的时候 A 已经发布过了 —— 两次都能过。
 * 环一旦形成，「选一条记录带出它的字段」在两张表单之间来回指，
 * 设计器里看不出任何问题，运行时表现为带出值莫名其妙。
 *
 * @author 走进新时代
 */
@MockitoSettings(strictness = Strictness.LENIENT)
public class FormDesignRelationCycleTest extends BaseMockitoUnitTest {

    @InjectMocks
    private FormDesignPublishValidator validator;

    @Mock
    private FormDesignMapper formDesignMapper;

    @Spy
    private RelationFormResolver relationFormResolver = new RelationFormResolver();

    /**
     * 本类只测环检测。字段映射的存在性校验由另一条分支负责，
     * 这里让它返回空集合即可 —— 测试用的表单都没有配带出映射
     */
    @Mock
    private FormLogicGuard logicGuard;

    /** 造一张「有一个关联表单控件指向 targetCode」的表单 */
    private FormDesignDO form(String code, String targetCode) {
        FormDesignDO design = new FormDesignDO();
        design.setCode(code);
        design.setName(code);
        design.setPublishedVersion(1);
        if (targetCode == null) {
            design.setFormFields(FormFieldsCodec.write(List.of()));
            return design;
        }
        String rule = String.format(
                "{\"type\":\"%s\",\"field\":\"rel_%s\",\"title\":\"关联%s\","
                        + "\"props\":{\"targetFormCode\":\"%s\",\"fieldMappings\":[]}}",
                RelationFormResolver.RELATION_WIDGET_TYPE, targetCode, targetCode, targetCode);
        design.setFormFields(FormFieldsCodec.write(List.of(rule)));
        return design;
    }

    private void validate(FormDesignDO design) {
        when(logicGuard.parseFieldMetas(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(Collections.emptyMap());
        ReflectionTestUtils.invokeMethod(validator, "validateRelationFields", design);
    }

    @Test
    @DisplayName("⚠️ A → B → A 必须拦住 —— 这是本次补的缺口")
    void testIndirectCycleRejected() {
        FormDesignDO a = form("form_a", "form_b");
        when(formDesignMapper.selectByCode("form_b")).thenReturn(form("form_b", "form_a"));
        when(formDesignMapper.selectByCode("form_a")).thenReturn(a);

        ServiceException ex = assertThrows(ServiceException.class, () -> validate(a));
        assertTrue(ex.getMessage().contains("循环关联"), ex.getMessage());
    }

    @Test
    @DisplayName("A → B → C → A 三级环也要拦住")
    void testThreeLevelCycleRejected() {
        FormDesignDO a = form("form_a", "form_b");
        when(formDesignMapper.selectByCode("form_b")).thenReturn(form("form_b", "form_c"));
        when(formDesignMapper.selectByCode("form_c")).thenReturn(form("form_c", "form_a"));
        when(formDesignMapper.selectByCode("form_a")).thenReturn(a);

        assertThrows(ServiceException.class, () -> validate(a));
    }

    @Test
    @DisplayName("A → B → C 不成环，放行")
    void testChainWithoutCycleAllowed() {
        FormDesignDO a = form("form_a", "form_b");
        when(formDesignMapper.selectByCode("form_b")).thenReturn(form("form_b", "form_c"));
        when(formDesignMapper.selectByCode("form_c")).thenReturn(form("form_c", null));

        assertDoesNotThrow(() -> validate(a));
    }

    @Test
    @DisplayName("B → B 自环不应把 A 的发布卡住 —— A 只是路过，不在环上")
    void testUnrelatedSelfLoopDoesNotBlock() {
        FormDesignDO a = form("form_a", "form_b");
        // form_b 指向自己：它自己发布时会被自引用校验拦，但不该连累 form_a
        when(formDesignMapper.selectByCode("form_b")).thenReturn(form("form_b", "form_b"));

        assertDoesNotThrow(() -> validate(a));
    }

    @Test
    @DisplayName("关联链上的表单不存在时不报环 —— 那是另一条校验负责的事")
    void testMissingTargetInChain() {
        FormDesignDO a = form("form_a", "form_b");
        when(formDesignMapper.selectByCode("form_b")).thenReturn(form("form_b", "form_gone"));
        when(formDesignMapper.selectByCode("form_gone")).thenReturn(null);

        assertDoesNotThrow(() -> validate(a));
    }

    @Test
    @DisplayName("没有关联控件时直接返回，不查库")
    void testNoRelationField() {
        assertDoesNotThrow(() -> validate(form("form_a", null)));
    }

}
