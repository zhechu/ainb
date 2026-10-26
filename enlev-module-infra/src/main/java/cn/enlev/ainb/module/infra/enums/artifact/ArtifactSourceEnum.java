package cn.enlev.ainb.module.infra.enums.artifact;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * AI 产物的来源（{@code infra_data_model.source} / {@code infra_form_design.source}）：
 * 这行是「人建的」、「AI 建的」、还是「导入存量登记进来的」
 *
 * <h3>🔴 为什么需要这一列</h3>
 * 数据模型与表单设计都有<b>两类写入方</b>：人在界面上点「新增」，以及 Agent 工具
 * （{@code datamodel_create} / {@code form_save_draft}）在对话里悄悄落库。
 * 两者产出的行在库里长得一模一样，于是「清理 AI 试出来的那批表」这件事
 * 根本没有安全的判据 —— 只能靠表名猜，猜错就是删掉人建的东西，而 DDL 不可回滚。
 * 有了这一列，治理动作可以只作用于 {@link #AI} 且 {@code manual_edited_flag = 0} 的行。
 *
 * <h3>🔴 为什么取值由服务端写死、不接受调用方传入</h3>
 * {@code DataModelSaveReqVO} / {@code FormDesignSaveReqVO} 与 api 包的 DTO 里
 * <b>都没有</b> source 字段，值由 Service 按「走的哪个入口」决定：
 * <ul>
 *   <li>Controller（人在界面上操作）→ {@link #MANUAL}</li>
 *   <li>{@code DataModelApiImpl} / {@code FormDesignApiImpl}（Agent 工具走的是 api 包）→ {@link #AI}</li>
 *   <li>{@code importTables}（把库里已有的表登记进来）→ {@link #IMPORT}</li>
 * </ul>
 * 若允许客户端指定，AI 建的行就能被伪装成手动 —— 而伪装的动机是现成的：
 * 治理动作专挑 AI 的行下手。这一列的全部价值就在于<b>可信</b>，
 * 一旦可被写入方自己决定，它就退化成一个没人敢信的装饰。
 *
 * <p>与 {@code agent_workflow_def.source}（{@code WorkflowSourceEnum}）是同一条纪律。
 * 那边只有 MANUAL/AUTO 两个值，因为它的两条自动路径无从细分；
 * 这边多一个 {@link #IMPORT}，因为「导入已有表」是一个<b>独立且可判定</b>的入口 ——
 * 它既不是人从零建的、也不是 AI 建的，混进 MANUAL 会让「人工建的表」这个统计虚高。
 *
 * <h3>⚠️ 两张表共用同一个取值集，即便 IMPORT 目前只有数据模型用得上</h3>
 * 表单设计没有「导入已有表单」这个入口。刻意<b>不</b>给它裁掉 IMPORT：
 * 两列共用一个枚举，「查所有 AI 产物」才是一条条件；取值集一旦分叉，
 * 就得写两套，而漏一套的表现是清单少一半、且<b>不报错</b>。
 *
 * @author 走进新时代
 */
@Getter
@AllArgsConstructor
public enum ArtifactSourceEnum {

    /** 人工创建：管理员在数据模型 / 表单设计界面上点「新增」建出来的 */
    MANUAL("MANUAL", "人工创建"),

    /** AI 创建：Agent 工具在对话或流程执行中落库的，管理员没有主动建过 */
    AI("AI", "AI 创建"),

    /** 导入登记：库里本来就有的表，通过「导入已有表」纳入受管范围 */
    IMPORT("IMPORT", "导入登记"),
    ;

    private final String source;
    private final String description;

    /**
     * 按取值反查枚举，认不出来的一律按 {@link #MANUAL} 处理
     *
     * <p>不返回 null、也不抛异常：这一列是 {@code NOT NULL DEFAULT 'MANUAL'}，
     * 库里不会有 NULL；但内存里新 new 出来、还没落库的 DO 会有，
     * 而「没设来源」被误判成 AI 的后果是<b>治理动作把这行当成 AI 产物清掉</b> ——
     * 宁可把它算成人建的（治理动作会跳过），也不能反过来。
     */
    public static ArtifactSourceEnum of(String source) {
        for (ArtifactSourceEnum item : values()) {
            if (item.source.equals(source)) {
                return item;
            }
        }
        return MANUAL;
    }

    /**
     * 是不是 AI 建出来、且尚未被人工接管过的行 —— 治理动作唯一安全的作用范围
     *
     * <p>把「来源」与「有没有被人改过」两件事合成一个判据，是因为<b>单独用任何一个都不够</b>：
     * 只看 source=AI，会把「AI 建了、人后来精修过」的行也算进去，删掉的是人的劳动；
     * 只看 flag=0，会把人从零建的行也算进去，而那种行根本不该被当成 AI 产物清理。
     *
     * @param source           {@code source} 列的值，null 按 MANUAL 处理
     * @param manualEditedFlag {@code manual_edited_flag} 列的值，null 按 false（未被手改）处理
     */
    public static boolean isUntouchedAiArtifact(String source, Boolean manualEditedFlag) {
        return AI.source.equals(source) && !Boolean.TRUE.equals(manualEditedFlag);
    }

}
