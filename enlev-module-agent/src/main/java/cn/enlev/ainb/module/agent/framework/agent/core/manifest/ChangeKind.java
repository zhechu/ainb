package cn.enlev.ainb.module.agent.framework.agent.core.manifest;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 变更类型：变更清单里每一项「将会做什么」的分类
 *
 * <h3>为什么每个常量都自带一句话模板</h3>
 * 清单顶部那句 {@code headline}（「将新建 1 张表、1 张表单……」）是四个渲染点唯一必读的东西 ——
 * 审批人可能只看这一行就点同意。若把它写成 builder 里的一个 {@code switch(kind)}，
 * 新增一种变更类型时编译器不会提醒补分支，结果是「新类型的变更做了、但那句话里没提」——
 * 正是本模块最恨的失败模式（静默少一块）。模板放进构造参数，加常量就必须给文案，漏不了。
 *
 * <p>量词也在模板里（「1 张表」而不是「1 个表」）：中文量词按名词走，
 * 交给 builder 统一拼会退化成「1 个 XXX」。
 *
 * @author 走进新时代
 */
@Getter
@AllArgsConstructor
public enum ChangeKind {

    /** 建新物理表（{@code datamodel_create}） */
    TABLE_CREATE("新建数据表", "新建 %d 张数据表"),

    /** 改已有表结构（{@code datamodel_update}）：加列/改列/删列 */
    TABLE_ALTER("修改表结构", "修改 %d 张表的结构"),

    /** 把已存在的物理表登记进平台（{@code datamodel_import}）：不产生任何 DDL */
    TABLE_REGISTER("登记已有表", "登记 %d 张已有表"),

    /** 存表单草稿（{@code form_save_draft}） */
    FORM_CREATE("新建表单", "新建 %d 张表单"),

    /** 改表单设计（{@code form_update}） */
    FORM_UPDATE("修改表单", "修改 %d 个表单"),

    /** 发布表单（{@code form_publish_bundle}）：连带建菜单、建按钮权限、给角色授权 */
    FORM_PUBLISH("发布表单", "发布 %d 个表单"),

    /** 建并部署审批流（{@code process_create_and_deploy}） */
    PROCESS_DEPLOY("部署审批流", "部署 %d 条审批流"),

    /** 写业务数据（{@code form_data_create} / {@code form_data_update} / {@code form_data_delete}） */
    DATA_WRITE("写业务数据", "改动 %d 处业务数据"),

    /** 调外部数据接口（{@code data_interface_invoke}）：副作用取决于接口本身，平台无从判断 */
    DATA_INTERFACE("调用数据接口", "调用 %d 个数据接口"),

    /**
     * 兜底：工具认识、但没有专用抽取器
     *
     * <p>🔴 这一档存在的唯一理由是「新增写工具时忘了配抽取器」不能表现为清单里少一项。
     * 它至少说出「调了哪个工具、传了什么参数」，并明确标注解读不完整。
     */
    GENERIC_TOOL("调用工具", "调用 %d 个工具"),
    ;

    /**
     * 短标签：清单每一项前面的中文分类名（渲染成 chip）
     */
    private final String label;

    /**
     * headline 片段模板，恰好一个 {@code %d}（该类型的变更项数）
     *
     * <p>⚠️ <b>不要以「将」开头</b>：builder 把各片段用「、」连起来后统一前置一个「将」，
     * 模板里再写一遍就成了「将将新建 1 张数据表」。
     */
    private final String headlineTemplate;

    /**
     * 按本类型的项数渲染 headline 片段
     */
    public String headline(int count) {
        return String.format(headlineTemplate, count);
    }

}
