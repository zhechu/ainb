package cn.enlev.ainb.module.ai.controller.admin.model;

import cn.enlev.ainb.framework.common.enums.CommonStatusEnum;
import cn.enlev.ainb.framework.common.pojo.CommonResult;
import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.framework.common.util.object.BeanUtils;
import cn.enlev.ainb.module.ai.controller.admin.model.vo.model.AiModelPageReqVO;
import cn.enlev.ainb.module.ai.controller.admin.model.vo.model.AiModelRespVO;
import cn.enlev.ainb.module.ai.controller.admin.model.vo.model.AiModelSaveReqVO;
import cn.enlev.ainb.module.ai.controller.admin.model.vo.model.AiModelTestRespVO;
import cn.enlev.ainb.module.ai.controller.admin.model.vo.model.AiModelUpdateStatusReqVO;
import cn.enlev.ainb.module.ai.dal.dataobject.model.AiApiKeyDO;
import cn.enlev.ainb.module.ai.dal.dataobject.model.AiModelDO;
import cn.enlev.ainb.module.ai.service.model.AiApiKeyService;
import cn.enlev.ainb.module.ai.service.model.AiModelService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

import static cn.enlev.ainb.framework.common.pojo.CommonResult.success;
import static cn.enlev.ainb.framework.common.util.collection.CollectionUtils.convertList;
import static cn.enlev.ainb.framework.common.util.collection.CollectionUtils.convertMap;

@Tag(name = "管理后台 - AI 模型")
@RestController
@RequestMapping("/ai/model")
@Validated
public class AiModelController {

    @Resource
    private AiModelService modelService;
    @Resource
    private AiApiKeyService apiKeyService;

    @PostMapping("/create")
    @Operation(summary = "创建模型")
    @PreAuthorize("@ss.hasPermission('ai:model:create')")
    public CommonResult<Long> createModel(@Valid @RequestBody AiModelSaveReqVO createReqVO) {
        return success(modelService.createModel(createReqVO));
    }

    @PutMapping("/update")
    @Operation(summary = "更新模型")
    @PreAuthorize("@ss.hasPermission('ai:model:update')")
    public CommonResult<Boolean> updateModel(@Valid @RequestBody AiModelSaveReqVO updateReqVO) {
        modelService.updateModel(updateReqVO);
        return success(true);
    }

    @PutMapping("/update-status")
    @Operation(summary = "启用/停用模型",
            description = "只改 status 一个字段。走 /update 需要把整行重新提交一遍（每个字段都是必填），"
                    + "在故障风暴里逐个禁用模型时那是五遍完整表单。"
                    + "副作用：停用后所有 AI 调用的候选链立即少一个；"
                    + "已经钉住该模型的任务不会切换，而是直接报「任务钉住的模型不可用」")
    @PreAuthorize("@ss.hasPermission('ai:model:update')")
    public CommonResult<Boolean> updateModelStatus(@Valid @RequestBody AiModelUpdateStatusReqVO reqVO) {
        modelService.updateModelStatus(reqVO.getId(), reqVO.getStatus());
        return success(true);
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除模型")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('ai:model:delete')")
    public CommonResult<Boolean> deleteModel(@RequestParam("id") Long id) {
        modelService.deleteModel(id);
        return success(true);
    }

    @GetMapping("/get")
    @Operation(summary = "获得模型")
    @Parameter(name = "id", description = "编号", required = true, example = "1024")
    @PreAuthorize("@ss.hasPermission('ai:model:query')")
    public CommonResult<AiModelRespVO> getModel(@RequestParam("id") Long id) {
        AiModelDO model = modelService.getModel(id);
        return success(BeanUtils.toBean(model, AiModelRespVO.class));
    }

    @GetMapping("/page")
    @Operation(summary = "获得模型分页")
    @PreAuthorize("@ss.hasPermission('ai:model:query')")
    public CommonResult<PageResult<AiModelRespVO>> getModelPage(@Valid AiModelPageReqVO pageReqVO) {
        PageResult<AiModelDO> pageResult = modelService.getModelPage(pageReqVO);
        return success(BeanUtils.toBean(pageResult, AiModelRespVO.class));
    }

    @GetMapping("/simple-list")
    @Operation(summary = "获得模型列表")
    @Parameter(name = "type", description = "类型", required = true, example = "1")
    @Parameter(name = "platform", description = "平台", example = "midjourney")
    public CommonResult<List<AiModelRespVO>> getModelSimpleList(
            @RequestParam("type") Integer type,
            @RequestParam(value = "platform", required = false) String platform) {
        List<AiModelDO> list = modelService.getModelListByStatusAndType(
                CommonStatusEnum.ENABLE.getStatus(), type, platform);
        // 密钥也必须启用：模型启用但密钥禁用时，选中它要到运行时才报错，
        // 下拉里出现「选了必炸」的选项就是坑（与故障切换候选链同一口径）
        Map<Long, AiApiKeyDO> keyMap = convertMap(apiKeyService.getApiKeyList(), AiApiKeyDO::getId);
        list = list.stream().filter(model -> {
            AiApiKeyDO apiKey = keyMap.get(model.getKeyId());
            return apiKey != null && CommonStatusEnum.isEnable(apiKey.getStatus());
        }).toList();
        return success(convertList(list, model -> new AiModelRespVO().setId(model.getId())
                .setName(model.getName()).setModel(model.getModel()).setPlatform(model.getPlatform())));
    }

    @PostMapping("/test")
    @Operation(summary = "测试模型是否可用",
            description = "对单个模型发一次真实的最小对话请求。⚠️ 会真实消耗厂商 token；"
                    + "只测这一个模型、绝不切换到别的候选（切换会让「测试通过」变成别的模型替它通过）。"
                    + "已禁用的模型也照样能测——「管理员想不想用它」与「它到底能不能用」是两件事。"
                    + "失败不抛异常，而是回 success=false + 厂商原文 + 路由事实")
    @Parameter(name = "id", description = "编号", required = true, example = "1024")
    @Parameter(name = "timeoutSeconds", description = "硬超时，单位：秒。服务端夹到 [5, 120]", example = "30")
    @PreAuthorize("@ss.hasPermission('ai:model:query')")
    public CommonResult<AiModelTestRespVO> testModel(
            @RequestParam("id") Long id,
            @RequestParam(value = "timeoutSeconds", required = false, defaultValue = "30") Integer timeoutSeconds) {
        return success(modelService.testModel(id, timeoutSeconds));
    }

}