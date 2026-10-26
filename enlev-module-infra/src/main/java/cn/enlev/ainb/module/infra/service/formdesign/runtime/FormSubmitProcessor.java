package cn.enlev.ainb.module.infra.service.formdesign.runtime;

import cn.enlev.ainb.module.infra.dal.dataobject.formdesign.FormDesignDO;
import cn.enlev.ainb.module.infra.framework.formdesign.core.extension.FormDesignExtension;
import cn.enlev.ainb.module.infra.framework.formdesign.core.extension.FormDesignExtensionRegistry;
import cn.enlev.ainb.module.infra.framework.formdesign.core.extension.FormSubmitContext;
import cn.enlev.ainb.module.infra.framework.formdesign.core.file.FileMetaEnricher;
import cn.enlev.ainb.module.infra.framework.formdesign.core.file.RichContentGuard;
import cn.enlev.ainb.module.infra.framework.formdesign.core.formula.FormulaRecalculator;
import cn.enlev.ainb.module.infra.framework.formdesign.core.logic.FormLogicGuard;
import cn.enlev.ainb.module.infra.framework.formdesign.core.permission.FormFieldCropper;
import cn.enlev.ainb.module.infra.framework.formdesign.core.subtable.SubTableGuard;
import cn.enlev.ainb.module.infra.framework.formdesign.core.system.SystemValueGuard;
import cn.enlev.ainb.module.infra.framework.formdesign.core.validation.FormValidationGuard;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.module.infra.enums.ErrorCodeConstants.FORM_DESIGN_LOGIC_CONFLICT;
import static cn.enlev.ainb.module.infra.enums.ErrorCodeConstants.FORM_DESIGN_SUB_TABLE_INVALID;
import static cn.enlev.ainb.module.infra.enums.ErrorCodeConstants.FORM_DESIGN_REQUIRED_MISSING;
import static cn.enlev.ainb.module.infra.enums.ErrorCodeConstants.FORM_DESIGN_EXTENSION_REJECTED;
import static cn.enlev.ainb.module.infra.enums.ErrorCodeConstants.FORM_DESIGN_VALIDATION_FAILED;

/**
 * 表单提交的前置与后置处理
 *
 * 把全部服务端约束收在一处，而不是在 create/update/delete 三个入口各写一遍 ——
 * 那样漏掉一个入口就等于那条路径没有服务端约束，而这种漏洞从功能上完全看不出来。
 *
 * <h3>执行顺序</h3>
 *
 * <ol>
 *   <li>剥离系统值：创建人这类值由后端按登录上下文填</li>
 *   <li>联动规则复校验：丢弃不可写字段的提交值、拒绝必填空值</li>
 *   <li>子表校验：行数区间 + 硬顶 + 行内必填</li>
 *   <li>关联表单带出：重读被关联记录并覆盖</li>
 *   <li>公式权威重算：用服务端结果覆盖前端提交值</li>
 *   <li>富媒体净化：内联 base64 图片转存文件服务、富文本按白名单清洗</li>
 *   <li>字段格式与跨字段校验：校验最终要落库的那份数据</li>
 *   <li>服务端扩展点 before 钩子（仅当表单处于服务端扩展点模式）</li>
 *   <li>—— 调用方写库 ——</li>
 *   <li>服务端扩展点 after 钩子</li>
 * </ol>
 *
 * 顺序不是随意的，每一步的理由写在对应代码处。三条最容易搞反的：
 * 复校验排在扩展点之前（扩展点看到的应当是已净化的数据）、
 * 格式校验排在重算之后（校验的对象应当是最终落库值）、
 * 富媒体净化排在格式校验之前（否则校验的是一串 20–100KB 的 base64，而它本该先变成一个 URL）。
 *
 * @author 走进新时代
 */
@Component
@Slf4j
public class FormSubmitProcessor {

    @Resource
    private FormLogicGuard logicGuard;
    /**
     * 字段权限。前端的 disabled 不是安全边界，提交时必须在服务端再拦一次
     */
    @Resource
    private FormFieldCropper fieldCropper;
    @Resource
    private FormDesignExtensionRegistry extensionRegistry;
    @Resource
    private FormulaRecalculator formulaRecalculator;
    @Resource
    private RelationFormFiller relationFormFiller;
    @Resource
    private SystemValueGuard systemValueGuard;
    @Resource
    private SubTableGuard subTableGuard;
    @Resource
    private FormValidationGuard validationGuard;
    @Resource
    private RichContentGuard richContentGuard;
    /**
     * 上传值的元信息补齐。紧跟在净化之后 —— 它依赖净化产出的 URL
     */
    @Resource
    private FileMetaEnricher fileMetaEnricher;

    /**
     * 提交前处理
     *
     * @param design    表单设计
     * @param operation 操作类型
     * @param pkValue   主键值，CREATE 时为 null
     * @param data      提交数据。本方法不修改入参
     * @param userId    当前用户
     * @return 净化后的数据。调用方应当用这份写库
     */
    public Map<String, Object> before(FormDesignDO design, FormSubmitContext.Operation operation,
                                      Object pkValue, Map<String, Object> data, Long userId) {
        // 1. 剥离系统值字段的提交值。必须排在【最前面】——
        //    创建人这类值由后端按登录上下文填，前端提交什么都不算数。
        //    放在最前是为了让后续每一步（联动、带出、公式、扩展点）看到的都是
        //    已经去掉伪造值的数据，否则某一步可能拿它去做判断
        Map<String, Object> incoming = operation == FormSubmitContext.Operation.DELETE
                ? data
                : systemValueGuard.strip(design.getFormFields(), data);

        // 2. 联动规则复校验。删除操作不校验字段 —— 删除时没有字段数据可言
        Map<String, Object> sanitized = operation == FormSubmitContext.Operation.DELETE
                ? (incoming != null ? new LinkedHashMap<>(incoming) : new LinkedHashMap<>())
                : applyLogicGuard(design, incoming);

        // 3. 子表行数与行内必填校验。
        //    排在联动之后：联动可能给子表字段赋值或清空，先校验会看到还没处理过的数据
        if (operation != FormSubmitContext.Operation.DELETE) {
            SubTableGuard.ValidationResult subTableResult =
                    subTableGuard.validate(design.getFormFields(), sanitized);
            if (!subTableResult.isPassed()) {
                throw exception(FORM_DESIGN_SUB_TABLE_INVALID, subTableResult.toMessage());
            }
        }

        // 4. 关联表单带出字段：按映射表重新读一遍被关联记录并覆盖。
        //    必须排在公式重算【之前】—— 公式很可能引用带出的字段
        //    （税额 = 带出的税率 * 金额），先算公式会拿到前端提交的、我们不信任的带出值
        if (operation != FormSubmitContext.Operation.DELETE) {
            sanitized = relationFormFiller.fill(design, sanitized);
        }

        // 5. 公式字段权威重算，覆盖前端提交的值。
        //    排在联动复校验【之后】：联动可能给公式引用的字段赋值或清空，
        //    先重算会拿到还没被联动处理过的值。
        //    排在扩展点【之前】：扩展点看到的应当是已经算好的合计，
        //    否则它读到的是前端提交的、我们本就不信任的那个值
        if (operation != FormSubmitContext.Operation.DELETE) {
            sanitized = formulaRecalculator.recalculate(design.getFormFields(), sanitized);
        }

        // 6. 富媒体净化：内联 base64 图片转存文件服务、富文本按白名单清洗。
        //    排在格式校验【之前】：一张签名的 base64 是 20–100KB 的字符串，
        //    让它带着这个形态进入校验（尤其是超长值拦截），拦下来的是一个本该先被
        //    转存成 URL 的值，报错内容对用户毫无意义。
        //    排在公式重算【之后】：这是最后一个会改动字段值的步骤，放在它后面
        //    才能保证扫到的是最终要落库的那份数据
        if (operation != FormSubmitContext.Operation.DELETE) {
            sanitized = richContentGuard.purify(design.getFormFields(), sanitized);
        }

        // 6.5 上传值补元信息：URL 数组 → [{name,url,size,type}]（设计方案 §5.9）。
        //     ⚠️ 必须排在富媒体净化【之后】—— 净化把内联 base64 转存成 URL，
        //     转存之后才有 infra_file 记录可查。顺序反过来的话，base64 那一类
        //     补出来是空的，而它本该能补上。
        //     排在格式校验【之前】：校验看到的应当是最终形态
        if (operation != FormSubmitContext.Operation.DELETE) {
            sanitized = fileMetaEnricher.enrich(design.getFormFields(), sanitized);
        }

        // 7. 字段格式与跨字段校验。
        //    必须排在带出与公式重算【之后】—— 校验的对象应当是最终要落库的数据。
        //    放在之前会去校验一个我们本就不信任的前端值：公式字段的值稍后会被服务端
        //    重算覆盖，此时校验它毫无意义；而带出字段此刻还是前端提交的旧值，
        //    「合计必须大于 0」这类约束会对着一个即将被替换的数字下结论
        if (operation != FormSubmitContext.Operation.DELETE) {
            FormValidationGuard.ValidationResult validationResult = validationGuard.validate(
                    design.getFormFields(), design.getValidateRules(), sanitized);
            if (!validationResult.isPassed()) {
                throw exception(FORM_DESIGN_VALIDATION_FAILED, validationResult.toMessage());
            }
        }

        // 8. 服务端扩展点 before 钩子
        if (!extensionRegistry.willInvoke(design.getExtensionMode(), design.getCode())) {
            return sanitized;
        }
        FormSubmitContext ctx = new FormSubmitContext(
                design.getCode(), design.getId(), operation, pkValue, userId, sanitized);
        Consumer<FormDesignExtension> hook = switch (operation) {
            case CREATE -> ext -> ext.beforeCreate(ctx);
            case UPDATE -> ext -> ext.beforeUpdate(ctx);
            case DELETE -> ext -> ext.beforeDelete(ctx);
        };
        extensionRegistry.invoke(design.getExtensionMode(), ctx, hook);

        if (ctx.hasErrors()) {
            // 汇总后一次性抛出，用户一次看到全部问题。
            // 这也是为什么约定扩展点往 addError 里加消息而不是自己抛异常
            throw exception(FORM_DESIGN_EXTENSION_REJECTED, String.join("；", ctx.getErrors()));
        }
        // 扩展点可能改过数据（补全字段），返回它改后的
        return ctx.getData();
    }

    /**
     * 提交后处理
     *
     * after 钩子里抛异常会连带回滚整个提交事务。这通常不是调用方想要的 ——
     * 发通知、写日志这类副作用失败了不该让业务数据一起回滚。
     * 但也【不在这里吞掉异常】：是否容错应当由扩展点作者决定，
     * 框架替他决定会让「我明明抛了异常怎么没回滚」变成一个谜。
     * 接口文档里已写明这一点。
     */
    public void after(FormDesignDO design, FormSubmitContext.Operation operation,
                      Object pkValue, Map<String, Object> data, Long userId) {
        if (!extensionRegistry.willInvoke(design.getExtensionMode(), design.getCode())) {
            return;
        }
        FormSubmitContext ctx = new FormSubmitContext(
                design.getCode(), design.getId(), operation, pkValue, userId, data);
        Consumer<FormDesignExtension> hook = switch (operation) {
            case CREATE -> ext -> ext.afterCreate(ctx);
            case UPDATE -> ext -> ext.afterUpdate(ctx);
            // 删除没有 after 钩子：删完之后数据已经不在了，能做的事很有限，
            // 需要的话在 beforeDelete 里做
            case DELETE -> ext -> {
            };
        };
        extensionRegistry.invoke(design.getExtensionMode(), ctx, hook);
        if (ctx.hasErrors()) {
            throw exception(FORM_DESIGN_EXTENSION_REJECTED, String.join("；", ctx.getErrors()));
        }
    }

    /**
     * 执行联动规则复校验
     */
    private Map<String, Object> applyLogicGuard(FormDesignDO design, Map<String, Object> data) {
        // 字段权限的兜底：HIDDEN / READONLY 字段的提交值在复校验【之前】就丢掉。
        // 放在前面是因为联动规则不该看到用户本来就无权提交的值 ——
        // 让规则据此算出别的字段，等于让越权值间接生效
        FormFieldCropper.StripResult stripped =
                fieldCropper.stripNonWritable(design.getCode(), design.getFormFields(), data);
        if (!stripped.discarded().isEmpty()) {
            log.info("[applyLogicGuard][按字段权限丢弃了 {} 个字段的提交值：{}]",
                    stripped.discarded().size(), stripped.discarded());
        }

        FormLogicGuard.GuardResult result =
                logicGuard.guard(design.getLogicRules(), design.getFormFields(), stripped.data());

        if (!result.getConflictFields().isEmpty()) {
            // 配置矛盾（既必填又不可填）是规则配错了，不是用户的问题。
            // 报出来比让提交永远失败要好
            throw exception(FORM_DESIGN_LOGIC_CONFLICT,
                    String.join("、", result.getConflictFields()));
        }
        if (!result.getMissingRequiredFields().isEmpty()) {
            throw exception(FORM_DESIGN_REQUIRED_MISSING,
                    String.join("、", result.getMissingRequiredFields()));
        }
        return result.getData();
    }

}
