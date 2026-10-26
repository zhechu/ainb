package cn.enlev.ainb.module.bpm.api.definition;

import cn.enlev.ainb.framework.common.enums.CommonStatusEnum;
import cn.enlev.ainb.module.bpm.dal.dataobject.definition.BpmCategoryDO;
import cn.enlev.ainb.module.bpm.service.definition.BpmCategoryService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

/**
 * 流程分类 API 实现类
 *
 * <p>薄封装，不复制业务逻辑 —— 查询仍在 {@link BpmCategoryService} 里发生。
 *
 * @author 走进新时代
 */
@Service
public class BpmCategoryApiImpl implements BpmCategoryApi {

    @Resource
    private BpmCategoryService categoryService;

    @Override
    public List<String> getEnabledCategoryCodes() {
        return categoryService.getCategoryListByStatus(CommonStatusEnum.ENABLE.getStatus()).stream()
                .sorted(Comparator.comparing(BpmCategoryDO::getSort, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(BpmCategoryDO::getCode)
                .toList();
    }

}
