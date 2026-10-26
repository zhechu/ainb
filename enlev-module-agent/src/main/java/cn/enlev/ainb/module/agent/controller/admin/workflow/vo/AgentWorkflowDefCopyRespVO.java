package cn.enlev.ainb.module.agent.controller.admin.workflow.vo;

import cn.enlev.ainb.module.agent.framework.agent.core.copy.WorkflowCopyRewriter;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 管理后台 - Workflow 定义 复制 Response VO（S5-b / 方案 §3-L4 4.1）
 *
 * <h3>🔴 {@link #rewritten} 不是可选的锦上添花，是这一步<b>唯一</b>的用户可见信号</h3>
 * {@code insertNewVersion} 全方法不跑 {@code validateDsl}（S4b 的发布闸门在<b>发布期</b>，复制在<b>建草稿期</b>）
 * ⟹ 复制可以产出一份<b>必然发不出去</b>的草稿而不报任何错。前端必须在复制成功后把这个清单弹出来，
 * 不能只 toast 一句「复制成功」—— 那等于把本仓 §6「8 条静默改写」的教训重演一遍。
 *
 * <p>清单里有两类行，靠 {@code from.equals(to)} 区分（见 {@link WorkflowCopyRewriter.Rewrite}）：
 * <ul>
 *   <li>{@code from != to}：<b>服务端替你改了</b>。要显示成什么改成了什么。</li>
 *   <li>{@code from == to}：<b>刻意没改，但你要知道</b>。如「审批流未复制，仍指向 X」、
 *       「验签密钥沿用了源流程」、「建表名加完后缀不合规所以没改」。</li>
 * </ul>
 *
 * <p>⚠️ {@code rewritten} 为空列表是<b>正常的</b>：那表示这份 DSL 里没有任何会撞车的配置
 * （比如一条纯 LLM 问答流程）。它与「清单不可用」是两回事 —— 复制永远给得出清单，
 * 因为改写器 parse 不出 DSL 时是<b>抛</b>而不是给空表（见 {@code WorkflowCopyRewriter#rewrite}）。
 *
 * @author 走进新时代
 */
@Schema(description = "管理后台 - Workflow 定义 复制 Response VO")
@Data
public class AgentWorkflowDefCopyRespVO {

    @Schema(description = "新草稿的编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "301")
    private Long id;

    @Schema(description = "新业务编码", requiredMode = Schema.RequiredMode.REQUIRED, example = "wf_plan_customer_copy")
    private String code;

    @Schema(description = "新名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "客户跟进流程（副本）")
    private String name;

    @Schema(description = "版本号；新编码下恒为 1", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Integer version;

    @Schema(description = "状态；复制品一律是 DRAFT，即使源是 PUBLISHED", example = "DRAFT")
    private String status;

    @Schema(description = "改写清单：服务端替你改了哪些冲突配置、哪些刻意没改（空列表=这份 DSL 没有会撞车的配置）")
    private List<WorkflowCopyRewriter.Rewrite> rewritten;

}
