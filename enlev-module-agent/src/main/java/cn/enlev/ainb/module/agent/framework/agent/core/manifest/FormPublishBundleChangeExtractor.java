package cn.enlev.ainb.module.agent.framework.agent.core.manifest;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.NodeDTO;
import cn.enlev.ainb.module.agent.tool.impl.form.FormPublishBundleTool;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * {@code form_publish_bundle} → {@link ChangeKind#FORM_PUBLISH}
 *
 * <h3>本抽取器只报「作者写了什么」，菜单与权限交给干跑</h3>
 * 发布这个动作真正会建出什么，取决于表单<b>当前有没有绑过菜单</b>：
 * {@code FormDesignServiceImpl.publish} 里 {@code firstPublish = formDesign.getMenuId() == null}，
 * 首发才建菜单（<b>1 个组件菜单 {@code system_menu.type=2} + 7 个按钮权限 {@code type=3}，
 * 共 8 条菜单记录</b>，不是一个目录），再发布只是更新。
 * 而 {@code FormDesignApi.getFormDesignSimpleList()} 的 DTO <b>没有 menuId</b>，
 * 所以这件事在清单侧算不出来 —— 硬算的结果是再发布时也报「将新建 1 个菜单」，
 * 即清单在撒谎。故 {@code stats.menus} / {@code stats.permissions} 与
 * {@code detail.menu} 一律留给干跑器：只读出口
 * {@code FormDesignApi#previewPublishMenu(id, parentMenuId)} 已经就位，
 * 它复用私有的 {@code buildMenuConfig}，与真发布同一份逻辑、同一行判据。
 *
 * <p>⚠️ 顺带一个容易说错的事实：后端<b>一条菜单都不建</b>。
 * {@code buildMenuConfig} 只组装配置，真正调 {@code /system/menu/create} 的是前端
 * （{@code PublishDialog.vue} 的 {@code createMenus}）。
 * 清单说「会新建 8 条菜单记录」说的是<b>这个动作整体的后果</b>，
 * 不是「infra 会写 system_menu」—— 后者是假的。
 *
 * <h3>{@code roleIds} 为空必须说出来，而不是省略</h3>
 * 不指定角色时菜单<b>仅超级管理员可见</b>（见 {@code FormPublishBundleTool} 的参数说明）。
 * 这是最典型的「配了但不生效」：表单发布成功、菜单也建了，业务用户就是看不到入口，
 * 而从发布结果里完全看不出原因。清单是唯一能在事前说清的地方。
 *
 * @author 走进新时代
 */
@Component
public class FormPublishBundleChangeExtractor implements ToolChangeExtractor {

    @Override
    public String toolId() {
        return FormPublishBundleTool.NAME;
    }

    @Override
    public ChangeItem extract(NodeDTO node, ManifestContext ctx) {
        Map<String, Object> args = ManifestArgs.of(node);
        String formId = ManifestArgs.str(args, FormPublishBundleTool.PARAM_ID);
        String parentMenuName = ManifestArgs.str(args, FormPublishBundleTool.PARAM_PARENT_MENU_NAME);
        String parentMenuId = ManifestArgs.str(args, FormPublishBundleTool.PARAM_PARENT_MENU_ID);
        List<String> roleIds = ManifestArgs.strList(args, FormPublishBundleTool.PARAM_ROLE_IDS);

        ChangeItem item = new ChangeItem();
        item.setKind(ChangeKind.FORM_PUBLISH);
        item.getTarget().put("formId", formId);
        item.getTarget().put("parentMenu", describeParentMenu(parentMenuName, parentMenuId));
        item.getDetail().put("parentMenuName", parentMenuName);
        item.getDetail().put("parentMenuId", parentMenuId);
        item.getDetail().put("roleIds", roleIds);
        item.getDetail().put("roleNote", roleIds.isEmpty()
                ? "未指定角色，发布后仅超级管理员可见"
                : "分配给 " + roleIds.size() + " 个角色");
        // 🔴 编号是占位符时要明说：作者此刻并不知道要发布哪张表单，
        // 审批人看到的也不该是一个「看起来已经填好」的编号
        item.getDetail().put("formIdPending", ManifestArgs.isPending(formId));
        item.setSummary(summary(formId, parentMenuName, parentMenuId, roleIds));
        return item;
    }

    /**
     * 上级菜单：给了名字用名字，只给编号显示编号，都没给就是顶层
     *
     * <p>与 {@code FormPublishBundleTool} 的参数说明同口径：两者都不传表示挂到顶层菜单。
     *
     * <p>⚠️ 这里<b>刻意不带「目录」二字</b>。表单运行时菜单是 {@code type=2}（菜单），
     * 而它的上级既可以是 {@code type=1}（目录）也可以是另一个菜单 ——
     * 要知道是哪种得查 {@code system_menu}，那是干跑器的活。
     * 抽取器随口给它安一个类型，就是清单在替一个自己没查过的东西下断言。
     *
     * @return 干净的标签，供 {@code target.parentMenu} 用（不带动词、不带书名号）
     */
    private String describeParentMenu(String parentMenuName, String parentMenuId) {
        if (StrUtil.isNotBlank(parentMenuName)) {
            return parentMenuName;
        }
        if (StrUtil.isNotBlank(parentMenuId)) {
            return "#" + parentMenuId;
        }
        return "顶层菜单";
    }

    /**
     * summary 里放表单编号的那个槽位
     *
     * <p>留白规则与 {@code FormDataArgs#codeSlot} 同一条：ASCII 两侧留空格，全角括号不留。
     * 写成 {@code "发布表单 " + (blank ? "（编号未填）" : "#" + id)} 的话，
     * 缺编号那条分支会渲染成「发布表单 （编号未填），挂到…」——
     * 一个带着模板洞的句子，而它出现的时机恰恰是模型把参数写错、审批人正在逐字读的时候。
     *
     * <p>🔴 规则的<b>强制点</b>是 {@code SummarySpacingTest}，不是本注释。
     */
    private String formIdSlot(String formId) {
        if (StrUtil.isBlank(formId)) {
            return "（编号未填）";
        }
        // 照字面显示占位符。它确实还没定，编一个值出来才是撒谎
        if (ManifestArgs.isPending(formId)) {
            return "（编号运行期才确定：" + formId + "）";
        }
        return " #" + formId;
    }

    /**
     * summary 里描述挂到哪儿的那半句，连介词一起返回
     *
     * <p>介词放在这里而不是调用方拼，是因为三种情况的搭配不同：
     * 「挂到「人事管理」下」「挂到菜单 #2048 下」都对，但「挂到顶层菜单下」是病句。
     */
    private String parentMenuPhrase(String parentMenuName, String parentMenuId) {
        if (StrUtil.isNotBlank(parentMenuName)) {
            return "挂到「" + parentMenuName + "」下";
        }
        if (StrUtil.isNotBlank(parentMenuId)) {
            return "挂到菜单 #" + parentMenuId + " 下";
        }
        return "挂到顶层";
    }

    private String summary(String formId, String parentMenuName, String parentMenuId, List<String> roleIds) {
        StringBuilder sb = new StringBuilder("发布表单").append(formIdSlot(formId))
                .append("，").append(parentMenuPhrase(parentMenuName, parentMenuId));
        if (roleIds.isEmpty()) {
            sb.append("。⚠️ 未指定角色，发布后仅超级管理员可见，业务用户看不到入口");
        } else {
            sb.append("，分配给 ").append(roleIds.size()).append(" 个角色");
        }
        return sb.toString();
    }

}
