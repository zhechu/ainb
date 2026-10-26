package cn.enlev.ainb.module.system.framework.permission.core.dataset;

import cn.hutool.core.util.StrUtil;

/**
 * BPM 流程与分类的资源命名
 *
 * <h3>为什么流程资源要挂在分类底下</h3>
 *
 * v9 §7.2 要求「分类继承」：给一个分类授权，其下所有流程自动继承。
 * 这件事<b>不需要单独实现</b> —— 把分类建成流程的<b>父资源</b>即可，
 * {@code GrantDecisionService} 本来就会沿资源祖先链找授权（物化路径，不递归）。
 *
 * 换句话说，「分类继承」是资源树的自然结果，不是一条额外规则。
 * 自己写一套继承逻辑的话，它迟早和资源树的祖先链算法漂移，
 * 而漂移的表现是「在分类上配的权限对某些流程不生效」，极难反查。
 *
 * <h3>命名</h3>
 *
 * <pre>
 * 分类：bpm:category:&lt;categoryCode&gt;
 * 流程：bpm:process:&lt;processDefinitionKey&gt;
 * </pre>
 *
 * ⚠️ 流程用的是 <b>key</b> 而不是 definitionId。definitionId 每次部署都会变，
 * 拿它当资源标识的话，流程一改一部署，之前配的授权就全指向了一个没人用的旧版本 ——
 * 界面上权限还在，实际已经失效。
 *
 * @author 走进新时代
 */
public final class ProcessResourceNaming {

    /**
     * 分类资源的权限串前缀
     */
    public static final String CATEGORY_PREFIX = "bpm:category:";
    /**
     * 流程资源的权限串前缀
     */
    public static final String PROCESS_PREFIX = "bpm:process:";

    private ProcessResourceNaming() {
    }

    public static String category(String categoryCode) {
        return StrUtil.isBlank(categoryCode) ? null : CATEGORY_PREFIX + categoryCode;
    }

    public static String process(String processDefinitionKey) {
        return StrUtil.isBlank(processDefinitionKey) ? null : PROCESS_PREFIX + processDefinitionKey;
    }

    /**
     * 从权限串反解流程定义 key
     *
     * @return 不是流程资源时返回 null
     */
    public static String parseProcessKey(String permission) {
        if (StrUtil.isBlank(permission) || !permission.startsWith(PROCESS_PREFIX)) {
            return null;
        }
        return StrUtil.blankToDefault(permission.substring(PROCESS_PREFIX.length()), null);
    }

}
