package cn.enlev.ainb.module.infra.api.billtemplate;

import cn.enlev.ainb.module.infra.dal.dataobject.billtemplate.BillTemplateDO;
import cn.enlev.ainb.module.infra.service.billtemplate.BillTemplateService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

/**
 * 单据模板 API 实现类
 *
 * @author 走进新时代
 */
@Service
@Validated
public class BillTemplateApiImpl implements BillTemplateApi {

    @Resource
    private BillTemplateService billTemplateService;

    @Override
    public String generate(String code) {
        return billTemplateService.generate(code);
    }

    @Override
    public String preview(String code) {
        BillTemplateDO rule = billTemplateService.getBillTemplateByCode(code);
        // 范例是保存模板时算好存下来的，这里直接读，不重新试算 ——
        // 试算会走一遍 DateTimeFormatter，而调用方通常只是想在下拉里显示个样子
        return rule != null ? rule.getExample() : null;
    }

}
