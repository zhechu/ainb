package cn.enlev.ainb.module.agent.service.prompttemplate;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.common.enums.CommonStatusEnum;
import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.framework.common.util.object.BeanUtils;
import cn.enlev.ainb.module.agent.controller.admin.prompttemplate.vo.AgentPromptTemplatePageReqVO;
import cn.enlev.ainb.module.agent.controller.admin.prompttemplate.vo.AgentPromptTemplateSaveReqVO;
import cn.enlev.ainb.module.agent.dal.dataobject.prompttemplate.AgentPromptTemplateDO;
import cn.enlev.ainb.module.agent.dal.mysql.prompttemplate.AgentPromptTemplateCaseMapper;
import cn.enlev.ainb.module.agent.dal.mysql.prompttemplate.AgentPromptTemplateMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.util.List;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.AGENT_PROMPT_TEMPLATE_NOT_EXISTS;

/**
 * 提示词模板 Service 实现（需求7）
 *
 * <h3>🔴 {@code @Valid} 只声明在接口上</h3>
 * 接口与实现同时标 {@code @Valid} 会让 Hibernate Validator 抛 {@code ConstraintDeclarationException}
 * (HV000151)，表现是该端点<b>永远</b>返回「请求参数不正确」，与传什么无关——极难定位。
 *
 * @author 走进新时代
 */
@Service
@Validated
@Slf4j
public class AgentPromptTemplateServiceImpl implements AgentPromptTemplateService {

    @Resource
    private AgentPromptTemplateMapper promptTemplateMapper;
    /**
     * 只用来在删模板时连带删用例。刻意注入 Mapper 而不是 {@code AgentPromptTemplateCaseService}：
     * 那个 Service 反过来要读本模块的模板表校验 templateId，两边互相注入成环。
     */
    @Resource
    private AgentPromptTemplateCaseMapper promptTemplateCaseMapper;

    @Override
    public Long createPromptTemplate(AgentPromptTemplateSaveReqVO createReqVO) {
        AgentPromptTemplateDO template = BeanUtils.toBean(createReqVO, AgentPromptTemplateDO.class);
        applyDefaults(template);
        promptTemplateMapper.insert(template);
        return template.getId();
    }

    @Override
    public void updatePromptTemplate(AgentPromptTemplateSaveReqVO updateReqVO) {
        // selectById 走租户隔离：碰不到他租户的行，跨租户改表现为「不存在」
        validateExists(updateReqVO.getId());
        AgentPromptTemplateDO updateObj = BeanUtils.toBean(updateReqVO, AgentPromptTemplateDO.class);
        applyDefaults(updateObj);
        promptTemplateMapper.updateById(updateObj);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deletePromptTemplate(Long id) {
        validateExists(id);
        promptTemplateMapper.deleteById(id);
        // 🔴 连带删这条模板下的用例，且必须与上面同一个事务。
        //    用例的 template_id 是 NOT NULL 的逻辑外键，留着就是一批指向已删模板的行：
        //    管理页的用例子表按 templateId 查不到它们、聊天面板分组时找不到模板名也不显示，
        //    但分页接口照样数得出来 —— 表现为「库里明明有 45 条，页面上只找得到 30 条」，
        //    而且全程没有任何报错。软删（BaseDO#deleted 上有 @TableLogic），数据没真丢。
        int cases = promptTemplateCaseMapper.deleteByTemplateId(id);
        if (cases > 0) {
            log.info("[deletePromptTemplate][模板({}) 连带删除 {} 条用例]", id, cases);
        }
    }

    @Override
    public AgentPromptTemplateDO getPromptTemplate(Long id) {
        AgentPromptTemplateDO template = promptTemplateMapper.selectById(id);
        if (template == null) {
            throw exception(AGENT_PROMPT_TEMPLATE_NOT_EXISTS, id);
        }
        return template;
    }

    @Override
    public PageResult<AgentPromptTemplateDO> getPromptTemplatePage(AgentPromptTemplatePageReqVO pageReqVO) {
        return promptTemplateMapper.selectPage(pageReqVO);
    }

    @Override
    public List<AgentPromptTemplateDO> getEnabledPromptTemplateList() {
        return promptTemplateMapper.selectEnabledList();
    }

    @Override
    public List<String> getCategoryList() {
        // 只取分类列再 distinct：库里同名分类可能有几十条模板，去重后通常只有个位数。
        // 🔴 用 StrUtil.trim（null 进 null 出）而不是 String::trim：mapper 那边虽然已经滤掉了
        //    isNotNull，但把「非空」的保证寄托在另一层的查询条件上太脆——哪天有人换个 wrapper 复用
        //    这个流，这里就 NPE 成 500，而表现只是「分类下拉空白」。
        return promptTemplateMapper.selectListForCategories().stream()
                .map(AgentPromptTemplateDO::getCategory)
                .map(StrUtil::trim)
                .filter(StrUtil::isNotBlank)
                .distinct()
                .toList();
    }

    /**
     * 补默认值：状态缺省为「开启」、排序缺省为 0、文本列去首尾空白
     *
     * <p>🔴 status 必须兜底。列虽然 {@code NOT NULL DEFAULT 0}，但 MyBatis-Plus 的 insert 会
     * 把 null 字段一起写进 SQL（非 selective 策略由全局配置决定），落库成 NULL 就直接违反非空约束；
     * 更要紧的是：即便侥幸落库，聊天面板按 {@code status=0} 过滤，NULL 那条永远查不出来——
     * 「新建成功了却在助手里找不到」，正是本平台头号缺陷的形状。
     */
    private void applyDefaults(AgentPromptTemplateDO template) {
        if (template.getStatus() == null) {
            template.setStatus(CommonStatusEnum.ENABLE.getStatus());
        }
        if (template.getSort() == null) {
            template.setSort(0);
        }
        template.setName(StrUtil.trim(template.getName()));
        template.setCategory(StrUtil.trimToNull(template.getCategory()));
        template.setDescription(StrUtil.trimToNull(template.getDescription()));
    }

    private void validateExists(Long id) {
        if (id == null || promptTemplateMapper.selectById(id) == null) {
            throw exception(AGENT_PROMPT_TEMPLATE_NOT_EXISTS, id);
        }
    }

}
