package cn.enlev.ainb.module.infra.controller.admin.billtemplate;

import cn.enlev.ainb.framework.common.pojo.CommonResult;
import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.framework.common.util.object.BeanUtils;
import cn.enlev.ainb.module.infra.controller.admin.billtemplate.vo.*;
import cn.enlev.ainb.module.infra.dal.dataobject.billtemplate.BillTemplateDO;
import cn.enlev.ainb.module.infra.enums.billtemplate.ResetCycleEnum;
import cn.enlev.ainb.module.infra.service.billtemplate.BillTemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.framework.common.pojo.CommonResult.success;
import static cn.enlev.ainb.module.infra.enums.ErrorCodeConstants.BILL_TEMPLATE_NOT_EXISTS;

/**
 * 单据模板 Controller
 *
 * <h3>为什么没有「直接生成单号」的接口</h3>
 * 生成会消耗流水。开放一个前端可随意调用的 generate 接口，等于把流水号交给
 * 任何一个能打开浏览器控制台的人去刷。前端要拿号只有 {@link #occupy} 一条路 ——
 * 它对同一用户同一作用域是幂等的，反复调用不会烧号。
 * 跨模块的程序化出号走 {@code BillTemplateApi}，不经过 HTTP。
 * 唯一的例外是 {@link #testGenerate}：管理页的测试取号，默认模拟不烧号，
 * 真实模式有确认门槛且日志来源单独记为「测试取号」，便于审计区分。
 *
 * <p>🔴 菜单名叫「取号管理」（原名「单据模板」），代码与实体仍叫「单据模板」。
 * 分歧是刻意的，理由与 {@code DataModelDataController} 头上那段相同：
 * 菜单名说用户在这里干什么（配取号规则、看取号日志），
 * 代码名说这张表是什么（一张单据的编号模板）。
 * {@code infra:bill-template:*} 权限串一改，已授出的权全部失效，所以不动
 *
 * @author 走进新时代
 */
@Tag(name = "管理后台 - 取号管理")
@RestController
@RequestMapping("/infra/bill-template")
@Validated
public class BillTemplateController {

    @Resource
    private BillTemplateService billTemplateService;

    // ==================== 配置管理 ====================

    @PostMapping("/create")
    @Operation(summary = "创建单据模板")
    @PreAuthorize("@ss.hasPermission('infra:bill-template:create')")
    public CommonResult<Long> createBillTemplate(@Valid @RequestBody BillTemplateSaveReqVO reqVO) {
        return success(billTemplateService.createBillTemplate(reqVO));
    }

    @PutMapping("/update")
    @Operation(summary = "更新单据模板")
    @PreAuthorize("@ss.hasPermission('infra:bill-template:update')")
    public CommonResult<Boolean> updateBillTemplate(@Valid @RequestBody BillTemplateSaveReqVO reqVO) {
        billTemplateService.updateBillTemplate(reqVO);
        return success(true);
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除单据模板", description = "已生成过单号的模板不允许删除")
    @Parameter(name = "id", description = "编号", required = true, example = "1")
    @PreAuthorize("@ss.hasPermission('infra:bill-template:delete')")
    public CommonResult<Boolean> deleteBillTemplate(@RequestParam("id") Long id) {
        billTemplateService.deleteBillTemplate(id);
        return success(true);
    }

    @PutMapping("/update-status")
    @Operation(summary = "启用/停用单据模板")
    @Parameter(name = "id", description = "编号", required = true, example = "1")
    @Parameter(name = "status", description = "状态：0-开启 1-停用", required = true, example = "0")
    @PreAuthorize("@ss.hasPermission('infra:bill-template:update')")
    public CommonResult<Boolean> updateStatus(@RequestParam("id") Long id,
                                              @RequestParam("status") Integer status) {
        billTemplateService.updateStatus(id, status);
        return success(true);
    }

    @GetMapping("/get")
    @Operation(summary = "获得单据模板")
    @Parameter(name = "id", description = "编号", required = true, example = "1")
    @PreAuthorize("@ss.hasPermission('infra:bill-template:query')")
    public CommonResult<BillTemplateRespVO> getBillTemplate(@RequestParam("id") Long id) {
        BillTemplateDO entity = billTemplateService.getBillTemplate(id);
        if (entity == null) {
            throw exception(BILL_TEMPLATE_NOT_EXISTS);
        }
        return success(toRespVO(entity));
    }

    @GetMapping("/page")
    @Operation(summary = "获得单据模板分页")
    @PreAuthorize("@ss.hasPermission('infra:bill-template:query')")
    public CommonResult<PageResult<BillTemplateRespVO>> getBillTemplatePage(@Valid BillTemplatePageReqVO reqVO) {
        PageResult<BillTemplateDO> pageResult = billTemplateService.getBillTemplatePage(reqVO);
        PageResult<BillTemplateRespVO> result = BeanUtils.toBean(pageResult, BillTemplateRespVO.class);
        // 逐条补上 AUTO 解析后的实际周期
        for (int i = 0; i < result.getList().size(); i++) {
            result.getList().get(i).setResetCycleActual(actualResetCycle(pageResult.getList().get(i)));
        }
        return success(result);
    }

    /**
     * DO 转 VO，并补上 BeanUtils 拷不过来的派生字段
     */
    private BillTemplateRespVO toRespVO(BillTemplateDO entity) {
        BillTemplateRespVO vo = BeanUtils.toBean(entity, BillTemplateRespVO.class);
        vo.setResetCycleActual(actualResetCycle(entity));
        return vo;
    }

    /**
     * 解析实际重置周期
     *
     * 前端只做展示，解析规则留在后端一处 —— 两边各实现一遍，规则调整时必然对不上。
     */
    private String actualResetCycle(BillTemplateDO entity) {
        return ResetCycleEnum.codeOf(entity.getResetCycle())
                .resolve(entity.getDateFormat()).getCode();
    }

    /**
     * 不加 {@code @PreAuthorize}：表单设计器里的单据组件要靠它填下拉。
     * 设计表单的人未必被授予「取号查询」（权限串 infra:bill-template:query）权限，
     * 卡在这里会让表单设计器无法配置。
     * 返回的是精简信息，不含流水进度等敏感字段。
     */
    @GetMapping("/simple-list")
    @Operation(summary = "获得单据模板精简列表", description = "只返回已启用的，供表单设计器等处下拉选择")
    public CommonResult<List<BillTemplateSimpleRespVO>> getSimpleBillTemplateList() {
        List<BillTemplateDO> list = billTemplateService.getEnabledBillTemplateList();
        return success(BeanUtils.toBean(list, BillTemplateSimpleRespVO.class));
    }

    // ==================== 号码 ====================

    @PostMapping("/preview")
    @Operation(summary = "试算范例", description = "不消耗流水，供配置页实时预览")
    @PreAuthorize("@ss.hasPermission('infra:bill-template:query')")
    public CommonResult<String> preview(@Valid @RequestBody BillTemplatePreviewReqVO reqVO) {
        return success(billTemplateService.preview(reqVO));
    }

    @PostMapping("/test")
    @Operation(summary = "测试取号",
            description = "模拟模式（默认）不消耗流水、不写日志；真实模式与业务出号同路径，会消耗流水，日志来源记为「测试取号」")
    @Parameter(name = "id", description = "模板编号", required = true, example = "1")
    @Parameter(name = "real", description = "是否真实取号，默认 false（模拟）", example = "false")
    @PreAuthorize("@ss.hasPermission('infra:bill-template:query')")
    public CommonResult<BillTemplateTestRespVO> testGenerate(
            @RequestParam("id") Long id,
            @RequestParam(value = "real", required = false, defaultValue = "false") Boolean real) {
        return success(billTemplateService.testGenerate(id, real));
    }

    @PostMapping("/occupy")
    @Operation(summary = "预占一个单号",
            description = "打开新增表单时调用。同一用户对同一模板重复调用返回同一个号，不会烧号；"
                    + "放弃填写则等预占过期作废")
    @Parameter(name = "code", description = "模板编码", required = true, example = "purchase_order")
    public CommonResult<String> occupy(@RequestParam("code") String code) {
        return success(billTemplateService.occupyByCode(code));
    }

    // ==================== 取号日志 ====================

    @GetMapping("/log-page")
    @Operation(summary = "获得单据模板取号日志分页", description = "仅开启「记录取号日志」的模板会产生日志")
    @PreAuthorize("@ss.hasPermission('infra:bill-template:query')")
    public CommonResult<PageResult<BillTemplateLogRespVO>> getLogPage(@Valid BillTemplateLogPageReqVO reqVO) {
        return success(BeanUtils.toBean(billTemplateService.getLogPage(reqVO), BillTemplateLogRespVO.class));
    }

}
