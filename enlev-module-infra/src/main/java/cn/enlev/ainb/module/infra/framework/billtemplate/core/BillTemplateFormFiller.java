package cn.enlev.ainb.module.infra.framework.billtemplate.core;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.module.infra.dal.dataobject.formdesign.FormDesignDO;
import cn.enlev.ainb.module.infra.framework.formdesign.core.binding.TableBinding;
import cn.enlev.ainb.module.infra.service.billtemplate.BillTemplateService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 表单保存时的单号填充器
 *
 * 在 {@code FormDesignRuntimeServiceImpl.create()} 里、拼 INSERT 之前调用，
 * 把单据字段的值填进提交数据。之后的字段映射、SQL 拼装完全不需要知道单号的存在。
 *
 * <h3>为什么在后端填而不是前端</h3>
 * 前端提交上来的值不可信，也无法保证并发下唯一。前端只做「预占展示」，
 * 让用户在提交前能看到号；真正的落库值一律以这里为准。
 *
 * <h3>只在新增时填</h3>
 * {@code update()} 不走这里 —— 单号一经生成就不该随编辑变化。
 *
 * <h3>预占的核销</h3>
 * 若提交数据里已带了值，且该值正是当前用户预占的号，就沿用它并核销预占；
 * 否则忽略前端传来的值，重新生成。这样「用户看到的号」与「最终落库的号」一致，
 * 又不给伪造留口子。
 *
 * @author 走进新时代
 */
@Component
@Slf4j
public class BillTemplateFormFiller {

    @Resource
    private FormBillFieldResolver resolver;
    @Resource
    private BillTemplateService billTemplateService;

    /**
     * 为新增数据填充单号
     *
     * @param design  表单设计
     * @param binding 表绑定配置
     * @param data    提交数据，会被就地修改
     * @return 本次是否真的填了号
     *
     * <p>返回值给调用方用来判断「唯一索引冲突要不要重取号重试」：没填号的表单，
     * 冲突一定来自别的唯一索引，重试只会以同样的方式再失败一次。
     */
    public boolean fill(FormDesignDO design, TableBinding binding, Map<String, Object> data) {
        if (data == null) {
            return false;
        }
        List<BillFieldDefinition> fields = resolver.resolve(
                design.getCode(), design.getPublishedVersion(), design.getFormFields(), binding);
        if (fields.isEmpty()) {
            return false;
        }
        boolean filled = false;
        for (BillFieldDefinition field : fields) {
            if (field.isMainTable()) {
                filled |= fillMainField(design, field, data);
            } else {
                filled |= fillSubTableField(design, field, data);
            }
        }
        return filled;
    }

    // ==================== 主表 ====================

    /**
     * @return 是否新生成了号。沿用预占的号也算 —— 那个号同样是这次提交要落库的值，
     *         冲突时同样需要重取
     */
    private boolean fillMainField(FormDesignDO design, BillFieldDefinition field, Map<String, Object> data) {
        // 先判预占再生成：反过来的话，即便沿用了预占的号，也已经白白从计数器里取走一个，
        // 每提交一张单子就跳一个号
        if (acceptOccupied(design, field, data.get(field.getField()))) {
            return true;
        }
        // 主表字段的作用域就是主表数据本身，没有回退一说
        String no = generate(design, field, data, null);
        if (no != null) {
            data.put(field.getField(), no);
            return true;
        }
        return false;
    }

    // ==================== 子表 ====================

    /**
     * 为子表的每一行填号
     *
     * 逐行独立生成 —— 明细行各有各的单号，共用一个号就失去了意义。
     */
    @SuppressWarnings("unchecked")
    private boolean fillSubTableField(FormDesignDO design, BillFieldDefinition field, Map<String, Object> data) {
        Object rowsValue = data.get(field.getSubTableField());
        if (!(rowsValue instanceof List<?> rows) || rows.isEmpty()) {
            return false;
        }
        boolean filled = false;
        for (Object row : rows) {
            if (!(row instanceof Map<?, ?> rowMap)) {
                continue;
            }
            Map<String, Object> rowData = (Map<String, Object>) rowMap;
            // 子表行的字段取值先本行、后主表，因此把主表数据作为回退作用域传进去
            String no = generate(design, field, rowData, data);
            if (no == null) {
                continue;
            }
            // 子表行不参与预占：预占是按「用户 + 作用域」缓存的单个号，
            // 而一次提交可能有 N 行，天然对不上。
            // 用 put 覆盖而不是判空后再写：重试时行数据里可能还留着上一次尝试的号，
            // 那个号已经随事务回滚作废了，必须换成新的
            rowData.put(field.getField(), no);
            filled = true;
        }
        return filled;
    }

    // ==================== 生成 ====================

    /**
     * 按字段配置生成一个号，配置不可用时返回 null
     */
    private String generate(FormDesignDO design, BillFieldDefinition field,
                            Map<String, Object> rowData, Map<String, Object> mainData) {
        if (field.isInlineMode()) {
            return generateInline(design, field, rowData, mainData);
        }
        String templateCode = field.getTemplateCode();
        if (StrUtil.isBlank(templateCode)) {
            // 发布校验会拦住这种配置，运行时再遇到只可能是历史脏数据。
            // 不抛异常：为了一个没配好的单号字段让整张单子存不进去，代价过大
            log.warn("[generate][表单({}) 的单据字段({})未绑定模板，跳过填号]",
                    design.getCode(), field.getField());
            return null;
        }
        return billTemplateService.generate(templateCode);
    }

    private String generateInline(FormDesignDO design, BillFieldDefinition field,
                                  Map<String, Object> rowData, Map<String, Object> mainData) {
        InlineRuleConfig config = InlineRuleEvaluator.parse(field.getRuleConfig());
        if (config == null) {
            log.warn("[generateInline][表单({}) 的单据字段({})内联规则为空，跳过填号]",
                    design.getCode(), field.getField());
            return null;
        }
        BillNumberSpec spec = InlineRuleEvaluator.evaluate(
                design.getCode(), field.getField(), config, rowData, mainData);
        return billTemplateService.generateBySpec(spec);
    }

    /**
     * 提交上来的值是否是本人预占的号
     *
     * 是则核销预占并沿用该值；否则返回 false，由调用方覆盖成新生成的号。
     * 只有引用模板的主表字段才有预占 —— 内联模式的号依赖同表单其它字段的值，
     * 用户填表时那些值还没定，预占没有意义。
     */
    private boolean acceptOccupied(FormDesignDO design, BillFieldDefinition field, Object submitted) {
        if (!field.isTemplateMode() || StrUtil.isBlank(field.getTemplateCode())) {
            return false;
        }
        if (!(submitted instanceof String no) || StrUtil.isBlank(no)) {
            return false;
        }
        if (billTemplateService.releaseOccupied(field.getTemplateCode(), no)) {
            return true;
        }
        log.debug("[acceptOccupied][表单({}) 字段({})提交了未预占的值，将改用新生成的号]",
                design.getCode(), field.getField());
        return false;
    }

}
