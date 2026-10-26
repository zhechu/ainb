package cn.enlev.ainb.module.infra.framework.formdesign.core.schema;

/**
 * 表单产物的 Schema 版本
 *
 * 表单设计的产物（formConf / formFields / listConf / tableBinding）是一组随迭代演进的
 * JSON 结构。版本号的用途是：结构发生破坏性变更时，迁移器据此判断某张存量表单
 * 是用哪一版结构写的、该怎么升级。
 *
 * <h3>本阶段只有版本号，没有迁移器</h3>
 *
 * 产品尚未上线，无存量表单需要迁移，重构期可以自由改结构。因此现在只把版本号记下来，
 * 不建迁移器链。
 *
 * <p><b>上线前必须补齐</b>：迁移器链 + 迁移单测 + 真实表单 JSON 夹具。
 * 已列入设计方案的延后事项台账第 1 项。跳过它的后果是：上线后第一次改 Schema 就没有退路，
 * 旧表单可能直接打不开，且没有补救手段。
 *
 * <h3>什么时候该递增</h3>
 *
 * 只在【破坏性】变更时递增 —— 即「按旧结构写的数据，用新代码读会出错或语义变了」。
 * 纯新增可选字段不算破坏性，不要递增；否则版本号会因为无意义的变更快速膨胀，
 * 迁移器里堆满什么都不做的空迁移。
 *
 * @author 走进新时代
 */
public final class FormDesignSchema {

    /**
     * 当前 Schema 版本号
     *
     * 新建与更新表单时都会写入这个值 —— 保存动作产出的就是当前结构的数据。
     *
     * <h3>⚠️ 把它改成 2 之前，请先读交接文档第二十一节</h3>
     *
     * 版本回滚（{@code FormDesignVersionServiceImpl#rollback}）已经在回滚这个版本号了，
     * 那段代码的注释写着「否则<b>迁移器</b>会以为它已经是当前结构而跳过它」——
     * 而<b>那个迁移器并不存在</b>。
     *
     * 所以这个常量一旦变成 2，「回滚到改结构之前的快照」就会把旧结构的数据塞回主表，
     * 而运行时按新结构去读它。<b>今天不出问题，只是因为它还是 1。</b>
     * 这跟上不上线无关 —— 改它的人就是打开那个洞的人。
     */
    public static final int CURRENT_VERSION = 1;

    /**
     * 版本号在版本快照 JSON 里的键名
     *
     * 快照（infra_form_design_version.snapshot）是本模块自己的结构，版本号写在里面即可。
     * 回滚时需要知道那份快照是按哪一版结构写的，所以快照必须带上它 ——
     * 少了它，回滚一份旧结构的快照会把旧数据原样塞回主表，而主表已经按新结构在读了。
     */
    public static final String SNAPSHOT_KEY = "schemaVersion";

    private FormDesignSchema() {
    }

}
