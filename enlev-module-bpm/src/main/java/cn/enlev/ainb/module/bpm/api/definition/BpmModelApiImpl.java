package cn.enlev.ainb.module.bpm.api.definition;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.common.util.json.JsonUtils;
import cn.enlev.ainb.module.bpm.api.definition.dto.BpmModelCreateReqDTO;
import cn.enlev.ainb.module.bpm.api.definition.dto.BpmModelRespDTO;
import cn.enlev.ainb.module.bpm.api.definition.dto.BpmProcessConstraintsRespDTO;
import cn.enlev.ainb.module.bpm.controller.admin.definition.vo.model.BpmModelMetaInfoVO;
import cn.enlev.ainb.module.bpm.controller.admin.definition.vo.model.BpmModelSaveReqVO;
import cn.enlev.ainb.module.bpm.controller.admin.definition.vo.model.simple.BpmSimpleModelNodeVO;
import cn.enlev.ainb.module.bpm.convert.definition.BpmModelConvert;
import cn.enlev.ainb.module.bpm.enums.definition.BpmModelTypeEnum;
import cn.enlev.ainb.module.bpm.enums.definition.BpmSimpleModelNodeTypeEnum;
import cn.enlev.ainb.module.bpm.enums.definition.BpmUserTaskApproveMethodEnum;
import cn.enlev.ainb.module.bpm.enums.definition.BpmUserTaskApproveTypeEnum;
import cn.enlev.ainb.module.bpm.framework.flowable.core.enums.BpmTaskCandidateStrategyEnum;
import cn.enlev.ainb.module.bpm.service.definition.BpmModelService;
import cn.enlev.ainb.module.bpm.service.task.BpmTaskServiceImpl;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.flowable.engine.repository.Model;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 流程模型 API 实现类
 *
 * <p>薄封装，不复制业务逻辑 —— 所有校验仍在 {@link BpmModelService} 里发生。
 *
 * @author 走进新时代
 */
@Service
@Validated
@Slf4j
public class BpmModelApiImpl implements BpmModelApi {

    /**
     * 各候选人策略的 candidateParam 格式说明
     *
     * ⚠️ 竖线格式那两条是真的会炸：策略实现里写着 {@code Assert.isTrue(params.length == 2)}，
     * 少给一段直接抛异常。把格式写进规则表，是为了让模型一次写对而不是试三次。
     */
    private static final Map<Integer, String> CANDIDATE_PARAM_FORMATS = Map.ofEntries(
            Map.entry(10, "逗号分隔的角色编号，如 \"1,2\""),
            Map.entry(20, "逗号分隔的部门编号"),
            Map.entry(21, "逗号分隔的部门编号"),
            Map.entry(22, "逗号分隔的岗位编号"),
            Map.entry(23, "\"部门编号们|层级\"，如 \"1,2|2\"，两段都必须给"),
            Map.entry(30, "逗号分隔的用户编号，如 \"1,2,3\""),
            Map.entry(37, "层级数字，必须大于 0，如 \"1\""),
            Map.entry(38, "层级数字，必须大于 0，如 \"1\""),
            Map.entry(40, "逗号分隔的用户组编号"),
            Map.entry(50, "表单里的用户字段名"),
            Map.entry(51, "\"表单字段名|层级\"，两段都必须给"),
            Map.entry(60, "Flowable 表达式，求值结果须为用户编号集合"));

    /**
     * 不需要 candidateParam 的策略（与 {@code BpmTaskCandidateStrategy#isParamRequired} 的覆写一致）
     */
    private static final List<Integer> PARAM_NOT_REQUIRED_STRATEGIES = List.of(34, 35, 36);

    @Resource
    private BpmModelService modelService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    // ⚠️ @Valid 只声明在接口上：Hibernate Validator 禁止重写方法重定义参数约束
    // （HV000151），两边都写会在运行时直接抛 ConstraintDeclarationException
    public String saveAndDeployModel(Long userId, BpmModelCreateReqDTO reqDTO) {
        // 1. 解析节点树。走 bpm 自己的 VO，调用方不必认识它
        BpmSimpleModelNodeVO simpleModel = JsonUtils.parseObject(
                reqDTO.getSimpleModel(), BpmSimpleModelNodeVO.class);

        BpmModelSaveReqVO saveReqVO = new BpmModelSaveReqVO();
        saveReqVO.setKey(reqDTO.getKey());
        saveReqVO.setName(reqDTO.getName());
        saveReqVO.setCategory(reqDTO.getCategory());
        saveReqVO.setSimpleModel(simpleModel);
        saveReqVO.setDescription(reqDTO.getDescription());
        // 恒为仿钉钉设计器：AI 生成的是节点树，不是 BPMN XML。
        // 传 BPMN(10) 的话 saveModel 会去读 bpmnXml 字段，而那个字段是空的，
        // 结果是模型建出来却没有任何流程图
        saveReqVO.setType(BpmModelTypeEnum.SIMPLE.getType());
        saveReqVO.setFormType(reqDTO.getFormType());
        saveReqVO.setFormId(reqDTO.getFormId());
        saveReqVO.setFormDesignCode(reqDTO.getFormDesignCode());
        saveReqVO.setManagerUserIds(reqDTO.getManagerUserIds());
        saveReqVO.setStartUserIds(reqDTO.getStartUserIds());
        // visible 是 @NotNull，且默认值语义应当是「可见」——
        // 建一个谁都看不到的流程没有意义，而漏传会直接被参数校验打回
        saveReqVO.setVisible(Boolean.TRUE);

        // 2. 按 key 决定新建还是更新。
        //    ⚠️ 不能无脑 createModel —— 流程标识全局唯一，撞上已有的会直接被拒，
        //    而「给这张表单加审批流」时，那条流程往往<b>已经存在</b>，
        //    此时用户要的是改它，不是被告知「已存在」然后另起一条重复的流程
        Model existing = modelService.getModelByKey(reqDTO.getKey());
        String modelId;
        if (existing == null) {
            modelId = modelService.createModel(saveReqVO);
            log.info("[saveAndDeployModel][流程模型({})新建成功，标识({})]", modelId, reqDTO.getKey());
        } else {
            modelId = existing.getId();
            saveReqVO.setId(modelId);
            modelService.updateModel(userId, saveReqVO);
            log.info("[saveAndDeployModel][流程模型({})更新成功，标识({})]", modelId, reqDTO.getKey());
        }

        // 3. 立刻部署。
        //    与保存共处一个事务：部署失败则整体回滚，不留下「建了没部署」的半成品 ——
        //    那种模型从未部署过，管理员判定拿不到流程资源行，用户会既改不了也删不掉。
        //    部署同时会产生新的流程定义版本，并把上一版挂起
        modelService.deployModel(userId, modelId);
        log.info("[saveAndDeployModel][流程模型({})部署成功]", modelId);
        return modelId;
    }

    @Override
    public List<BpmModelRespDTO> getModelList(String name) {
        List<Model> models = modelService.getModelList(name);
        List<BpmModelRespDTO> result = new ArrayList<>(models.size());
        for (Model model : models) {
            BpmModelMetaInfoVO metaInfo = BpmModelConvert.INSTANCE.parseMetaInfo(model);
            BpmModelRespDTO dto = new BpmModelRespDTO();
            dto.setId(model.getId());
            dto.setKey(model.getKey());
            dto.setName(model.getName());
            dto.setCategory(model.getCategory());
            dto.setDeployed(StrUtil.isNotEmpty(model.getDeploymentId()));
            if (metaInfo != null) {
                dto.setType(metaInfo.getType());
                dto.setFormType(metaInfo.getFormType());
                dto.setFormDesignCode(metaInfo.getFormDesignCode());
            }
            result.add(dto);
        }
        return result;
    }

    @Override
    public String getModelSimpleJson(String id) {
        BpmSimpleModelNodeVO simpleModel = modelService.getSimpleModel(id);
        return simpleModel == null ? null : JsonUtils.toJsonString(simpleModel);
    }

    @Override
    public BpmProcessConstraintsRespDTO getProcessConstraints() {
        BpmProcessConstraintsRespDTO result = new BpmProcessConstraintsRespDTO();
        result.setRequiredApproveType(BpmUserTaskApproveTypeEnum.USER.getType());

        List<BpmProcessConstraintsRespDTO.NodeType> nodeTypes = new ArrayList<>();
        for (BpmSimpleModelNodeTypeEnum item : BpmSimpleModelNodeTypeEnum.values()) {
            BpmProcessConstraintsRespDTO.NodeType nodeType = new BpmProcessConstraintsRespDTO.NodeType();
            nodeType.setType(item.getType());
            nodeType.setName(item.getName());
            nodeType.setBranch(BpmSimpleModelNodeTypeEnum.isBranchNode(item.getType()));
            nodeTypes.add(nodeType);
        }
        result.setNodeTypes(nodeTypes);

        List<BpmProcessConstraintsRespDTO.CandidateStrategy> strategies = new ArrayList<>();
        for (BpmTaskCandidateStrategyEnum item : BpmTaskCandidateStrategyEnum.values()) {
            BpmProcessConstraintsRespDTO.CandidateStrategy strategy =
                    new BpmProcessConstraintsRespDTO.CandidateStrategy();
            strategy.setStrategy(item.getStrategy());
            strategy.setName(item.getDescription());
            strategy.setParamRequired(!PARAM_NOT_REQUIRED_STRATEGIES.contains(item.getStrategy()));
            strategy.setParamFormat(CANDIDATE_PARAM_FORMATS.get(item.getStrategy()));
            strategies.add(strategy);
        }
        result.setCandidateStrategies(strategies);

        List<BpmProcessConstraintsRespDTO.ApproveMethod> methods = new ArrayList<>();
        for (BpmUserTaskApproveMethodEnum item : BpmUserTaskApproveMethodEnum.values()) {
            BpmProcessConstraintsRespDTO.ApproveMethod method =
                    new BpmProcessConstraintsRespDTO.ApproveMethod();
            method.setMethod(item.getMethod());
            method.setName(item.getName());
            method.setRatioRequired(BpmUserTaskApproveMethodEnum.RATIO.getMethod().equals(item.getMethod()));
            methods.add(method);
        }
        result.setApproveMethods(methods);

        result.setFirstNodeForbiddenStrategies(
                List.of(BpmTaskCandidateStrategyEnum.APPROVE_USER_SELECT.getStrategy()));

        BpmProcessConstraintsRespDTO.ConditionType expression = new BpmProcessConstraintsRespDTO.ConditionType();
        expression.setType(1);
        expression.setName("条件表达式");
        expression.setUsage("直接写 Flowable 表达式，原样生效。"
                + "务必用 ${var:getOrDefault(字段名, null) > 100} 这种写法 —— "
                + "直接写 ${字段名 > 100} 在变量不存在时会抛异常，整个流程卡住");
        BpmProcessConstraintsRespDTO.ConditionType rule = new BpmProcessConstraintsRespDTO.ConditionType();
        rule.setType(2);
        rule.setName("条件规则");
        rule.setUsage("用 conditionGroups 结构化描述，后端拼成表达式。"
                + "嵌套三层且每层都有必填项，生成容易出错，优先用条件表达式");
        result.setConditionTypes(List.of(expression, rule));
        // 直接引用 bpm 自己那份常量，不另抄字面量
        result.setSubFieldSeparator(BpmTaskServiceImpl.SUB_FIELD_SEPARATOR);
        return result;
    }

}
