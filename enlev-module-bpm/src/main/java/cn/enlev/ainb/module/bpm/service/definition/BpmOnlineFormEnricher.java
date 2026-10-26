package cn.enlev.ainb.module.bpm.service.definition;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.module.bpm.controller.admin.definition.vo.process.BpmProcessDefinitionRespVO;
import cn.enlev.ainb.module.bpm.enums.definition.BpmModelFormTypeEnum;
import cn.enlev.ainb.module.infra.api.formdesign.FormDesignApi;
import cn.enlev.ainb.module.infra.api.formdesign.dto.FormDesignRespDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 在线表单的流程定义补齐器
 *
 * 在线表单类型的流程定义**不冗余表单快照**（formConf / formFields 都为空），因为流程始终使用
 * 表单的最新版本。但前端渲染表单需要这两个字段，因此在返回给前端时按 formDesignCode 实时补齐。
 *
 * 这样做的好处：前端拿到的 BpmProcessDefinitionRespVO 无论哪种表单类型，形态都一致，
 * 渲染逻辑不需要按表单类型分支（对应方案的铁律「流程侧对表单类型无感」）。
 *
 * 代价：每次读取多一次表单查询。相比在部署时冻结快照，换来的是表单改动能立刻生效 ——
 * 这是需求方明确要求的「流程只用表单最新版本」。
 *
 * @author 走进新时代
 */
@Component
@Slf4j
public class BpmOnlineFormEnricher {

    @Resource
    private FormDesignApi formDesignApi;

    /**
     * 补齐流程定义的表单配置
     *
     * @param respVO 流程定义 VO。为 null 或非在线表单类型时原样返回
     * @return 补齐后的 VO
     */
    public BpmProcessDefinitionRespVO enrich(BpmProcessDefinitionRespVO respVO) {
        if (respVO == null
                || !Objects.equals(respVO.getFormType(), BpmModelFormTypeEnum.ONLINE.getType())
                || StrUtil.isEmpty(respVO.getFormDesignCode())) {
            return respVO;
        }
        // 已经有值就不重复查（同一个 VO 可能经过多个出口）
        if (StrUtil.isNotEmpty(respVO.getFormConf()) && CollUtil.isNotEmpty(respVO.getFormFields())) {
            return respVO;
        }
        // ⚠️ 取【运行态】那一份 —— 它按当前登录人的字段权限裁过。
        // 用设计态的 getFormDesign 的话，同一张表单从表单菜单进有字段权限、从流程进没有，
        // 而 HIDDEN 的字段会照常渲染出来（TODO #35）
        FormDesignRespDTO formDesign = formDesignApi.getRuntimeFormDesign(respVO.getFormDesignCode());
        if (formDesign == null) {
            // 表单被删了。不抛异常：详情页应当还能打开并展示流程轨迹，只是表单区域为空，
            // 直接失败会让这条流程实例完全无法查看
            log.warn("[enrich][流程定义({}) 引用的在线表单({}) 不存在，表单区域将为空]",
                    respVO.getId(), respVO.getFormDesignCode());
            return respVO;
        }
        respVO.setFormConf(formDesign.getFormConf());
        respVO.setFormFields(formDesign.getFormFields());
        if (StrUtil.isEmpty(respVO.getFormName())) {
            respVO.setFormName(formDesign.getName());
        }
        return respVO;
    }

    /**
     * 批量补齐流程定义的表单配置
     *
     * 相同 code 只查一次 —— 列表里同一个在线表单常被多个流程引用，逐条查会放大成 N 次查询。
     *
     * @param list 流程定义 VO 列表
     * @return 原列表（就地修改）
     */
    public List<BpmProcessDefinitionRespVO> enrichList(List<BpmProcessDefinitionRespVO> list) {
        if (CollUtil.isEmpty(list)) {
            return list;
        }
        Set<String> codes = list.stream()
                .filter(item -> Objects.equals(item.getFormType(), BpmModelFormTypeEnum.ONLINE.getType()))
                .map(BpmProcessDefinitionRespVO::getFormDesignCode)
                .filter(StrUtil::isNotEmpty)
                .collect(Collectors.toSet());
        if (CollUtil.isEmpty(codes)) {
            return list;
        }
        Map<String, FormDesignRespDTO> formMap = formDesignApi.getRuntimeFormDesignList(codes).stream()
                .collect(Collectors.toMap(FormDesignRespDTO::getCode, item -> item, (a, b) -> a));
        for (BpmProcessDefinitionRespVO respVO : list) {
            if (!Objects.equals(respVO.getFormType(), BpmModelFormTypeEnum.ONLINE.getType())
                    || StrUtil.isEmpty(respVO.getFormDesignCode())) {
                continue;
            }
            FormDesignRespDTO formDesign = formMap.get(respVO.getFormDesignCode());
            if (formDesign == null) {
                log.warn("[enrichList][流程定义({}) 引用的在线表单({}) 不存在]",
                        respVO.getId(), respVO.getFormDesignCode());
                continue;
            }
            respVO.setFormConf(formDesign.getFormConf());
            respVO.setFormFields(formDesign.getFormFields());
            if (StrUtil.isEmpty(respVO.getFormName())) {
                respVO.setFormName(formDesign.getName());
            }
        }
        return list;
    }

}
