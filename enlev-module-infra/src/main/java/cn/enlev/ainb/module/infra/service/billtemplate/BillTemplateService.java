package cn.enlev.ainb.module.infra.service.billtemplate;

import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.module.infra.controller.admin.billtemplate.vo.BillTemplateLogPageReqVO;
import cn.enlev.ainb.module.infra.controller.admin.billtemplate.vo.BillTemplatePageReqVO;
import cn.enlev.ainb.module.infra.controller.admin.billtemplate.vo.BillTemplatePreviewReqVO;
import cn.enlev.ainb.module.infra.controller.admin.billtemplate.vo.BillTemplateSaveReqVO;
import cn.enlev.ainb.module.infra.controller.admin.billtemplate.vo.BillTemplateTestRespVO;
import cn.enlev.ainb.module.infra.dal.dataobject.billtemplate.BillTemplateDO;
import cn.enlev.ainb.module.infra.dal.dataobject.billtemplate.BillTemplateLogDO;
import cn.enlev.ainb.module.infra.framework.billtemplate.core.BillNumberSpec;
import jakarta.validation.Valid;

import java.util.Collection;
import java.util.List;

/**
 * 单据模板 Service
 *
 * @author 走进新时代
 */
public interface BillTemplateService {

    // ==================== 配置 CRUD ====================

    Long createBillTemplate(@Valid BillTemplateSaveReqVO reqVO);

    void updateBillTemplate(@Valid BillTemplateSaveReqVO reqVO);

    void deleteBillTemplate(Long id);

    /**
     * 启用 / 停用
     */
    void updateStatus(Long id, Integer status);

    BillTemplateDO getBillTemplate(Long id);

    BillTemplateDO getBillTemplateByCode(String code);

    List<BillTemplateDO> getBillTemplateListByCodes(Collection<String> codes);

    PageResult<BillTemplateDO> getBillTemplatePage(BillTemplatePageReqVO reqVO);

    /**
     * 获得已启用的模板列表，供下拉选择
     */
    List<BillTemplateDO> getEnabledBillTemplateList();

    // ==================== 号码生成 ====================

    /**
     * 试算范例，【不消耗】流水
     *
     * 供后台配置页实时预览。前端不自己算，就是为了让预览与真实出号走同一段代码。
     *
     * @param reqVO 待试算的配置，可以是尚未保存的
     * @return 范例号码
     */
    String preview(@Valid BillTemplatePreviewReqVO reqVO);

    /**
     * 按模板编码生成单据号，【消耗】流水
     *
     * @param code 模板编码
     * @return 单据号
     */
    String generate(String code);

    /**
     * 按生成规格生成单据号，【消耗】流水
     *
     * 内联模式（规则写在表单字段上）走这个入口 —— 它没有模板编码可用。
     *
     * @param spec 生成规格
     * @return 单据号
     */
    String generateBySpec(BillNumberSpec spec);

    /**
     * 管理页测试取号
     *
     * 模拟模式（real=false）只读干跑：按「当前流水 + 1」试算下一个号，
     * 不消耗流水、不回写统计、不记日志 —— 严格取号场景下测试不能造成跳号。
     * 真实模式（real=true）与业务出号同路径：消耗流水，日志来源记为「测试取号」。
     *
     * @param id   模板编号
     * @param real 是否真实取号
     * @return 测试结果（号码 / 序号 / 周期 / 模式）
     */
    BillTemplateTestRespVO testGenerate(Long id, boolean real);

    // ==================== 号码预占 ====================

    /**
     * 预占一个号码并返回
     *
     * 同一用户对同一作用域重复调用会拿到同一个号，不会烧号。
     *
     * @param spec 生成规格
     * @return 单据号
     */
    String occupy(BillNumberSpec spec);

    /**
     * 按模板编码预占一个号码
     *
     * 与 {@link #occupy(BillNumberSpec)} 的区别是会校验模板存在且已启用 ——
     * 停用的模板不该还能通过预占继续出号。
     *
     * @param code 模板编码
     * @return 单据号
     */
    String occupyByCode(String code);

    /**
     * 核销预占
     *
     * @param scope 计数作用域
     * @param no    提交上来的号码
     * @return 是否真的核销了。false 表示预占已过期或号码不匹配
     */
    boolean releaseOccupied(String scope, String no);

    /**
     * 读取当前用户已预占的号码，没有则返回 null
     */
    String getOccupied(String scope);

    // ==================== 取号日志 ====================

    /**
     * 获得取号日志分页
     *
     * 仅开启「记录取号日志」的模板会产生日志
     */
    PageResult<BillTemplateLogDO> getLogPage(BillTemplateLogPageReqVO reqVO);

}
