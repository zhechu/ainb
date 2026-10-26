package cn.enlev.ainb.module.agent.framework.agent.core.validate;

import java.util.Map;

/**
 * 工具参数的生成后校验（方案 §5.5）
 *
 * <h3>为什么要在「进 Plan 之前」校验，而不是执行时才校验</h3>
 * 执行时校验只能保证不出错，但那时用户<b>已经点过确认了</b>。
 * 让用户对着一张写着「创建采购申请表」的卡片点了确认，
 * 然后告诉他「表名不合规」—— 他会觉得这个功能不可靠，而且他确实没做错什么。
 *
 * <p>所以校验要前移到<b>生成计划的那一刻</b>：不通过就把结构化错误回灌给 LLM 自修正，
 * 改好了再把卡片给用户看。用户看到的每一张卡片都应该是<b>能执行成功</b>的。
 *
 * <p>⚠️ 前移不等于取消 —— 执行时仍要再校验一次，因为 Plan 从生成到确认之间
 * 库里可能已经变了。两处都要有。
 *
 * @author 走进新时代
 */
public interface AgentToolParamValidator {

    /**
     * 是否负责校验这个工具
     */
    boolean supports(String toolName);

    /**
     * 校验参数
     *
     * @param params 工具入参
     * @return 结果，{@code errors} 非空即不通过
     */
    AgentValidateResult validate(Map<String, Object> params);

}
