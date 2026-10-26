package cn.enlev.ainb.module.agent.service.prompttemplate;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.common.enums.CommonStatusEnum;
import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.framework.common.util.object.BeanUtils;
import cn.enlev.ainb.module.agent.controller.admin.prompttemplate.vo.AgentPromptTemplateCasePageReqVO;
import cn.enlev.ainb.module.agent.controller.admin.prompttemplate.vo.AgentPromptTemplateCaseSaveReqVO;
import cn.enlev.ainb.module.agent.dal.dataobject.prompttemplate.AgentPromptTemplateCaseDO;
import cn.enlev.ainb.module.agent.dal.dataobject.prompttemplate.AgentPromptTemplateDO;
import cn.enlev.ainb.module.agent.dal.mysql.prompttemplate.AgentPromptTemplateCaseMapper;
import cn.enlev.ainb.module.agent.dal.mysql.prompttemplate.AgentPromptTemplateMapper;
import cn.enlev.ainb.module.agent.enums.AgentIntentEnum;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.AGENT_PROMPT_TEMPLATE_CASE_INTENT_INVALID;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.AGENT_PROMPT_TEMPLATE_CASE_NOT_EXISTS;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.AGENT_PROMPT_TEMPLATE_CASE_TEMPLATE_NOT_EXISTS;

/**
 * 提示词模板用例 Service 实现（S7 / L5）
 *
 * <h3>🔴 为什么写入前一定要校验模板存在</h3>
 * {@code template_id} 是逻辑外键、库里没有 {@code FOREIGN KEY} 兜着。放过一个不存在的 id，
 * 这条用例就会进入一种<b>哪儿都看不见但确实存在</b>的状态：创建接口返回成功、分页接口数得出它、
 * 管理页的用例子表（按 templateId 查）里却没有它，聊天面板也不显示 —— 因为分组时找不到模板名。
 * 「保存成功但不生效且无提示」正是本平台的头号缺陷形态，只能在写入口拦。
 *
 * <p>校验用 {@code selectById}，它自带租户隔离，故「挂到他租户的模板上」与「模板不存在」
 * 同样被拒，不泄漏该 id 在别的租户存在。
 *
 * @author 走进新时代
 */
@Service
@Validated
@Slf4j
public class AgentPromptTemplateCaseServiceImpl implements AgentPromptTemplateCaseService {

    @Resource
    private AgentPromptTemplateCaseMapper caseMapper;
    @Resource
    private AgentPromptTemplateMapper promptTemplateMapper;

    @Override
    public Long createCase(AgentPromptTemplateCaseSaveReqVO createReqVO) {
        validateTemplateExists(createReqVO.getTemplateId());
        AgentPromptTemplateCaseDO entity = BeanUtils.toBean(createReqVO, AgentPromptTemplateCaseDO.class);
        applyDefaults(entity);
        caseMapper.insert(entity);
        return entity.getId();
    }

    @Override
    public void updateCase(AgentPromptTemplateCaseSaveReqVO updateReqVO) {
        validateExists(updateReqVO.getId());
        // 改挂到别的模板上也得验：否则「编辑时手滑改了 templateId」同样能造出孤儿用例
        validateTemplateExists(updateReqVO.getTemplateId());
        AgentPromptTemplateCaseDO updateObj = BeanUtils.toBean(updateReqVO, AgentPromptTemplateCaseDO.class);
        applyDefaults(updateObj);
        caseMapper.updateById(updateObj);
    }

    @Override
    public void deleteCase(Long id) {
        validateExists(id);
        caseMapper.deleteById(id);
    }

    @Override
    public AgentPromptTemplateCaseDO getCase(Long id) {
        AgentPromptTemplateCaseDO entity = caseMapper.selectById(id);
        if (entity == null) {
            throw exception(AGENT_PROMPT_TEMPLATE_CASE_NOT_EXISTS, id);
        }
        return entity;
    }

    @Override
    public PageResult<AgentPromptTemplateCaseDO> getCasePage(AgentPromptTemplateCasePageReqVO pageReqVO) {
        return caseMapper.selectPage(pageReqVO);
    }

    @Override
    public List<AgentPromptTemplateCaseDO> getCaseListByTemplateId(Long templateId) {
        return caseMapper.selectListByTemplateId(templateId);
    }

    @Override
    public List<AgentPromptTemplateCaseDO> getEnabledCaseList() {
        // 两次小查询而不是一次 JOIN：本模块其余读列表也都是「查出来再在内存里过一遍」的写法，
        // 且这两张表都是人工维护的短文本，量级只有几十条，JOIN 省不下什么，
        // 却会让「用例可见性同时取决于用例自己和它模板的开关」这条规则藏进 SQL 里。
        Set<Long> enabledTemplateIds = promptTemplateMapper.selectEnabledList().stream()
                .map(AgentPromptTemplateDO::getId)
                .collect(Collectors.toSet());
        return caseMapper.selectEnabledList().stream()
                .filter(c -> enabledTemplateIds.contains(c.getTemplateId()))
                .toList();
    }

    /**
     * 补默认值 + 归一化：状态缺省「开启」、排序缺省 0、文本去首尾空白、期望分型统一成大写枚举名
     */
    private void applyDefaults(AgentPromptTemplateCaseDO entity) {
        if (entity.getStatus() == null) {
            entity.setStatus(CommonStatusEnum.ENABLE.getStatus());
        }
        if (entity.getSort() == null) {
            entity.setSort(0);
        }
        entity.setTitle(StrUtil.trim(entity.getTitle()));
        entity.setExpectHint(StrUtil.trimToNull(entity.getExpectHint()));
        entity.setExpectIntent(normalizeExpectIntent(entity.getExpectIntent()));
    }

    /**
     * 期望分型归一化：空白 → {@code null}（有意义的第三态，见 DO 注释）；
     * 其余统一成大写后必须命中 {@link AgentIntentEnum}
     *
     * <p>容忍大小写（{@code build} → {@code BUILD}）与 {@code AgentIntentClassifier#parse} 同款，
     * 为一个大小写把用户填的东西丢进报错分支不值得。但<b>不</b>容忍中文别名或缩写：
     * 验收脚本按这一列挑用例真发一次，一个认不出的值会让它静默跳过这条用例，
     * 看起来「全部通过」，实际一条都没验。
     */
    private String normalizeExpectIntent(String raw) {
        String text = StrUtil.trimToEmpty(raw).toUpperCase(Locale.ROOT);
        if (text.isEmpty()) {
            return null;
        }
        for (AgentIntentEnum intent : AgentIntentEnum.values()) {
            if (intent.getIntent().equals(text)) {
                return text;
            }
        }
        // 报用户实际填的那个值，不是归一化后的，否则他看不出自己错在哪
        throw exception(AGENT_PROMPT_TEMPLATE_CASE_INTENT_INVALID, raw);
    }

    private void validateTemplateExists(Long templateId) {
        if (templateId == null || promptTemplateMapper.selectById(templateId) == null) {
            throw exception(AGENT_PROMPT_TEMPLATE_CASE_TEMPLATE_NOT_EXISTS, templateId);
        }
    }

    private void validateExists(Long id) {
        if (id == null || caseMapper.selectById(id) == null) {
            throw exception(AGENT_PROMPT_TEMPLATE_CASE_NOT_EXISTS, id);
        }
    }

}
