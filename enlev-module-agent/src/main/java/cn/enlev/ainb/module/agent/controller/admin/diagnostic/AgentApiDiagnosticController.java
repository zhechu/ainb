package cn.enlev.ainb.module.agent.controller.admin.diagnostic;

import cn.enlev.ainb.framework.common.pojo.CommonResult;
import cn.enlev.ainb.module.infra.api.datamodel.DataModelApi;
import cn.enlev.ainb.module.infra.api.datamodel.dto.DataModelStructureRespDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.Data;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static cn.enlev.ainb.framework.common.pojo.CommonResult.success;

/**
 * 跨模块 api 调用诊断
 *
 * 验证 agent → infra.api 这条依赖真的通 —— 包括 Spring 能注入到 api 实现、
 * DTO 能正确跨模块传递。编译通过不代表运行期装配得起来。
 *
 * @author 走进新时代
 */
@Tag(name = "管理后台 - AI 助手 API 层诊断")
@RestController
@RequestMapping("/agent/diagnostic")
@Validated
public class AgentApiDiagnosticController {

    @Resource
    private DataModelApi dataModelApi;
    @Resource
    private cn.enlev.ainb.module.ai.api.model.AiModelApi aiModelApi;
    @Resource
    private cn.enlev.ainb.module.agent.service.knowledge.AgentDocSyncService docSyncService;

    @GetMapping("/api-enabled-models")
    @Operation(summary = "经 api 层取可用模型（模型与密钥都启用的才算）")
    @PreAuthorize("@ss.hasPermission('agent:diagnostic:query')")
    public CommonResult<List<cn.enlev.ainb.module.ai.api.model.AiModelApi.AiModelSimpleRespDTO>> enabledModels() {
        return success(aiModelApi.getEnabledModelList(null));
    }

    @Resource
    private cn.enlev.ainb.module.ai.service.model.AiModelService rawModelService;
    @Resource
    private cn.enlev.ainb.module.ai.service.model.AiApiKeyService rawApiKeyService;

    @Resource
    private cn.enlev.ainb.module.ai.api.rag.AiRagApi ragApi;

    @PostMapping("/doc-sync")
    @Operation(summary = "执行文档灌库（只处理新增与变更的）")
    @PreAuthorize("@ss.hasPermission('agent:diagnostic:query')")
    public CommonResult<cn.enlev.ainb.module.agent.service.knowledge.AgentDocSyncService.DocSyncResult> docSync() {
        return success(docSyncService.doSync());
    }

    @GetMapping("/doc-search")
    @Operation(summary = "在内置知识库里检索，验证召回效果")
    @Parameter(name = "q", description = "查询内容", required = true)
    @PreAuthorize("@ss.hasPermission('agent:diagnostic:query')")
    public CommonResult<List<cn.enlev.ainb.module.ai.api.rag.dto.AiRagSegmentRespDTO>> docSearch(
            @RequestParam("knowledgeId") Long knowledgeId,
            @RequestParam("q") String q,
            @RequestParam(value = "topK", required = false) Integer topK) {
        return success(ragApi.retrieve(knowledgeId, q, topK, null));
    }

    @GetMapping("/model-debug")
    @Operation(summary = "逐步输出模型筛选的中间结果，定位是哪一步滤掉的")
    @PreAuthorize("@ss.hasPermission('agent:diagnostic:query')")
    public CommonResult<List<String>> modelDebug() {
        List<String> lines = new java.util.ArrayList<>();
        for (Integer type : List.of(1, 5)) {
            var models = rawModelService.getModelListByStatusAndType(0, type, null);
            lines.add("type=" + type + " status=0 查到 " + models.size() + " 个");
            for (var m : models) {
                var key = m.getKeyId() == null ? null : rawApiKeyService.getApiKey(m.getKeyId());
                lines.add(String.format("   id=%d model=%s keyId=%s keyStatus=%s",
                        m.getId(), m.getModel(), m.getKeyId(),
                        key == null ? "KEY_NOT_FOUND" : key.getStatus()));
            }
        }
        return success(lines);
    }

    @GetMapping("/model-chat")
    @Operation(summary = "真实调一次模型，验证密钥可用与 usage 能否取到")
    @Parameter(name = "prompt", description = "提问内容")
    @PreAuthorize("@ss.hasPermission('agent:diagnostic:query')")
    public CommonResult<cn.enlev.ainb.module.ai.api.model.dto.AiChatRespDTO> modelChat(
            @RequestParam(value = "modelId", required = false) Long modelId,
            @RequestParam(value = "prompt", defaultValue = "用一句话说明你是谁") String prompt) {
        cn.enlev.ainb.module.ai.api.model.dto.AiChatReqDTO reqDTO =
                new cn.enlev.ainb.module.ai.api.model.dto.AiChatReqDTO();
        reqDTO.setModelId(modelId);
        reqDTO.setSystemPrompt("你是低代码平台的助手，回答简短准确。");
        reqDTO.setMessages(java.util.List.of(
                cn.enlev.ainb.module.ai.api.model.dto.AiChatReqDTO.Message.of("user", prompt)));
        return success(aiModelApi.chat(reqDTO));
    }

    @GetMapping("/doc-sync-plan")
    @Operation(summary = "扫描文档语料，返回同步计划（不实际写入）")
    @PreAuthorize("@ss.hasPermission('agent:diagnostic:query')")
    public CommonResult<cn.enlev.ainb.module.agent.service.knowledge.AgentDocSyncService.DocSyncPlan> docSyncPlan() {
        return success(docSyncService.planSync());
    }

    @GetMapping("/doc-sync-status")
    @Operation(summary = "上次同步的情况 + 库里现有文档数/段落数/已向量化段落数（现查）")
    @PreAuthorize("@ss.hasPermission('agent:diagnostic:query')")
    public CommonResult<cn.enlev.ainb.module.agent.service.knowledge.AgentDocSyncService.DocSyncStatus> docSyncStatus() {
        return success(docSyncService.getSyncStatus());
    }

    @GetMapping("/doc-sync-verify")
    @Operation(summary = "用几条固定问句实召回一次，验证灌进去的东西真能被查出来且带来源")
    @PreAuthorize("@ss.hasPermission('agent:diagnostic:query')")
    public CommonResult<cn.enlev.ainb.module.agent.service.knowledge.AgentDocSyncService.DocSyncVerify> docSyncVerify() {
        return success(docSyncService.verify());
    }

    @Data
    public static class StructureSummary {
        private String tableName;
        private String tableComment;
        private int columnCount;
        /** 被标记为系统列的列名 —— 改表时这些必须原样回传 */
        private List<String> systemColumns;
        /** 业务列名 */
        private List<String> businessColumns;
        private List<String> indexNames;
    }

    @GetMapping("/api-datamodel-list")
    @Operation(summary = "经 api 层取数据模型列表")
    @PreAuthorize("@ss.hasPermission('agent:diagnostic:query')")
    public CommonResult<List<DataModelApi.DataModelSimpleRespDTO>> dataModelList() {
        return success(dataModelApi.getDataModelList());
    }

    @GetMapping("/api-datamodel-structure")
    @Operation(summary = "经 api 层取表结构，并按系统列/业务列分组")
    @Parameter(name = "id", description = "模型编号", required = true)
    @PreAuthorize("@ss.hasPermission('agent:diagnostic:query')")
    public CommonResult<StructureSummary> structure(@RequestParam("id") Long id) {
        DataModelStructureRespDTO dto = dataModelApi.getStructure(id);
        StructureSummary summary = new StructureSummary();
        summary.setTableName(dto.getTableName());
        summary.setTableComment(dto.getTableComment());
        summary.setColumnCount(dto.getColumns() == null ? 0 : dto.getColumns().size());
        summary.setSystemColumns(dto.getColumns().stream()
                .filter(c -> Boolean.TRUE.equals(c.getSystemColumn()))
                .map(DataModelStructureRespDTO.ColumnDTO::getColumnName).toList());
        summary.setBusinessColumns(dto.getColumns().stream()
                .filter(c -> !Boolean.TRUE.equals(c.getSystemColumn()))
                .map(DataModelStructureRespDTO.ColumnDTO::getColumnName).toList());
        summary.setIndexNames(dto.getIndexes() == null ? List.of()
                : dto.getIndexes().stream()
                        .map(DataModelStructureRespDTO.IndexDTO::getIndexName).toList());
        return success(summary);
    }

    @GetMapping("/api-column-types")
    @Operation(summary = "经 api 层取受支持的列类型")
    @PreAuthorize("@ss.hasPermission('agent:diagnostic:query')")
    public CommonResult<List<DataModelApi.ColumnTypeRespDTO>> columnTypes() {
        return success(dataModelApi.getColumnTypeList());
    }

}
