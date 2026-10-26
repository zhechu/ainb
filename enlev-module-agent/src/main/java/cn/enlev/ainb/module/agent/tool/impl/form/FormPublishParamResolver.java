package cn.enlev.ainb.module.agent.tool.impl.form;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.module.system.api.menu.MenuApi;
import cn.enlev.ainb.module.system.api.menu.dto.MenuSimpleRespDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.AGENT_TOOL_PARAM_INVALID;

/**
 * {@code form_publish_bundle} 的入参解析
 *
 * <h3>为什么从工具里抽出来</h3>
 * 因为变更清单的干跑器（{@code ChangeManifestDryRunner}）要回答
 * 「这次发布会挂到哪个菜单下、会不会因为找不到目录而被拒」，
 * 而答案取决于<b>按名字反查目录</b>这一步 —— 那是查询，光看 DSL 算不出来。
 * 干跑器若自己再写一遍反查，就得到第二份口径：漂了不报错，
 * 表现是「清单说挂到人事管理下、实际发布时因为找不到目录整条流程失败」，
 * 而这个工具是 HIGH 风险的五连写，失败会留下半截。
 *
 * <p>抽出来之后，工具与干跑器读的是同一份解析，
 * 与 {@code DataModelParamResolver} / {@code FormDataParamResolver} 同一路数。
 *
 * <h3>🔴 三个方法都<b>会抛</b> {@code ServiceException}</h3>
 * 工具那边直接冒出去变成一次工具调用失败；干跑器那边接住、落成
 * {@code DryRunResult.Outcome.REJECTED}。两边要的都是同一句话，
 * 所以文案在这里只有一份 —— 它是给模型看的修改指引，也是给审批人看的拒绝理由。
 *
 * @author 走进新时代
 */
@Component
@Slf4j
public class FormPublishParamResolver {

    @Resource
    private MenuApi menuApi;

    /**
     * 解析表单编号
     *
     * @param params 工具入参
     * @return 表单编号
     */
    public Long resolveFormId(Map<String, Object> params) {
        Object val = params.get(FormPublishBundleTool.PARAM_ID);
        if (val == null) {
            throw exception(AGENT_TOOL_PARAM_INVALID, FormPublishBundleTool.NAME,
                    "缺少 " + FormPublishBundleTool.PARAM_ID + "（表单编号）");
        }
        try {
            return Long.parseLong(val.toString());
        } catch (NumberFormatException ex) {
            throw exception(AGENT_TOOL_PARAM_INVALID, FormPublishBundleTool.NAME,
                    FormPublishBundleTool.PARAM_ID + " 必须是数字，实际收到：" + val);
        }
    }

    /**
     * 解析上级菜单编号：优先按「名字」找目录，其次用显式 parentMenuId，都没有才挂顶层
     *
     * <p>🔴 这是「指定发布到在线示例菜单却挂到了顶层」的修复点。用户说的是菜单<b>名字</b>
     * （如「在线示例」），而 infra 建菜单要的是 parentId 数字。此前工具只认 parentMenuId，
     * 名字被丢掉、{@code parseParentMenuId} 落回 0（顶层）—— 表现为「指定了却没做到」。
     *
     * <p>必须在<b>发布之前</b>解析：首次发布时 {@code FormDesignServiceImpl.publish} 强制
     * 要求 parentMenuId 非空且正确，晚一步就会把菜单建错地方。
     *
     * <p>名字对不上时<b>不静默回落顶层</b>，而是抛出可选目录清单让模型改对 ——
     * 静默挂错地方正是本平台头号缺陷「配置保存成功但不生效且无提示」的翻版。
     *
     * <p>⚠️ 返回值<b>永不为 null</b>（不传或传 0 一律 {@code 0L} 表示顶层）。
     * 这一点对干跑器很要紧：{@code publish} 的拒绝判据是
     * {@code firstPublish && parentMenuId == null}，走本工具的路径永远凑不出 null，
     * 所以 {@code previewPublishMenu} 的 {@code parentMenuMissing} 在这条链上恒为 false。
     * 干跑器若自己传 null 进去，就会报一件本工具根本不会犯的错。
     *
     * @param params 工具入参
     * @return 上级菜单编号，{@code 0L} 表示顶层
     */
    public Long resolveParentMenuId(Map<String, Object> params) {
        String name = params.get(FormPublishBundleTool.PARAM_PARENT_MENU_NAME) == null
                ? null : params.get(FormPublishBundleTool.PARAM_PARENT_MENU_NAME).toString().trim();
        if (StrUtil.isNotBlank(name)) {
            List<MenuSimpleRespDTO> dirs = menuApi.getEnabledDirectoryList();
            for (MenuSimpleRespDTO dir : dirs) {
                if (name.equals(dir.getName())) {
                    log.info("[resolveParentMenuId][按名字「{}」匹配到目录({})]", name, dir.getId());
                    return dir.getId();
                }
            }
            // 对不上：把可选目录名报回去，别静默挂顶层
            String candidates = dirs.stream().map(MenuSimpleRespDTO::getName)
                    .collect(Collectors.joining("、"));
            throw exception(AGENT_TOOL_PARAM_INVALID, FormPublishBundleTool.NAME,
                    "找不到名为「" + name + "」的启用目录，无法发布到该菜单下。"
                            + "可选目录有：" + (StrUtil.isBlank(candidates) ? "（无）" : candidates)
                            + "。请改用正确的 parentMenuName，或改传 parentMenuId");
        }
        Object val = params.get(FormPublishBundleTool.PARAM_PARENT_MENU_ID);
        if (val == null || StrUtil.isBlank(val.toString()) || "0".equals(val.toString().trim())) {
            return 0L;
        }
        try {
            return Long.parseLong(val.toString().trim());
        } catch (NumberFormatException ex) {
            throw exception(AGENT_TOOL_PARAM_INVALID, FormPublishBundleTool.NAME,
                    FormPublishBundleTool.PARAM_PARENT_MENU_ID
                            + " 必须是数字（不传或传 0 表示挂到顶层），实际收到：" + val);
        }
    }

    /**
     * 解析要分配的角色编号
     *
     * <p>角色可省略：不分配时菜单只对超级管理员可见，总比发布整条失败好；
     * 工具会在结果里明确说明「未分配角色」，不静默。
     *
     * @param params 工具入参
     * @return 角色编号列表，可为空
     */
    public List<Long> resolveRoleIds(Map<String, Object> params) {
        Object val = params.get(FormPublishBundleTool.PARAM_ROLE_IDS);
        if (val == null || (val instanceof List<?> list && list.isEmpty())) {
            return List.of();
        }
        try {
            List<?> raw = (List<?>) val;
            return raw.stream().map(item -> Long.parseLong(item.toString())).toList();
        } catch (Exception ex) {
            throw exception(AGENT_TOOL_PARAM_INVALID, FormPublishBundleTool.NAME,
                    "roleIds 必须是数字数组，实际收到：" + val);
        }
    }

}
