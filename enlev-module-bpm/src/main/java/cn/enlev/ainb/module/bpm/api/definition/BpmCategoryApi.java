package cn.enlev.ainb.module.bpm.api.definition;

import java.util.List;

/**
 * 流程分类 API 接口
 *
 * <p>为什么要有这个：流程定义在「发起流程」列表里是<b>按分类分组</b>展示的，
 * 而分组只认 {@code bpm_category} 里真实存在且启用中的分类 code —— 一条流程若挂了个
 * 不存在的分类（例如 AI 建流程时默认填的 "1"），部署会成功、但在发起列表里<b>永远看不到</b>，
 * 正是本平台头号缺陷「配置保存成功但不生效且无提示」的翻版。
 * 调用方（AI 建流程工具）需要在落库前把分类对齐到启用中的 code，故暴露此只读接口。
 *
 * @author 走进新时代
 */
public interface BpmCategoryApi {

    /**
     * 返回启用中的流程分类 code 列表（按 sort 升序，保证调用方取「第一个」时结果稳定）
     *
     * @return 分类 code 列表；一个都没配置时返回空列表
     */
    List<String> getEnabledCategoryCodes();

}
