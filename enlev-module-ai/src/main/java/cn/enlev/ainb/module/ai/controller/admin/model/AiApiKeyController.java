package cn.enlev.ainb.module.ai.controller.admin.model;

import cn.enlev.ainb.framework.common.pojo.CommonResult;
import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.framework.common.util.object.BeanUtils;
import cn.enlev.ainb.module.ai.controller.admin.model.vo.apikey.AiApiKeyPageReqVO;
import cn.enlev.ainb.module.ai.controller.admin.model.vo.apikey.AiApiKeyRespVO;
import cn.enlev.ainb.module.ai.controller.admin.model.vo.apikey.AiApiKeySaveReqVO;
import cn.enlev.ainb.module.ai.controller.admin.model.vo.apikey.AiApiKeyUpdateStatusReqVO;
import cn.enlev.ainb.module.ai.controller.admin.model.vo.model.AiModelRespVO;
import cn.enlev.ainb.module.ai.dal.dataobject.model.AiApiKeyDO;
import cn.enlev.ainb.module.ai.service.model.AiApiKeyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static cn.enlev.ainb.framework.common.pojo.CommonResult.success;
import static cn.enlev.ainb.framework.common.util.collection.CollectionUtils.convertList;

@Tag(name = "管理后台 - AI API 密钥")
@RestController
@RequestMapping("/ai/api-key")
@Validated
public class AiApiKeyController {

    @Resource
    private AiApiKeyService apiKeyService;

    @PostMapping("/create")
    @Operation(summary = "创建 API 密钥")
    @PreAuthorize("@ss.hasPermission('ai:api-key:create')")
    public CommonResult<Long> createApiKey(@Valid @RequestBody AiApiKeySaveReqVO createReqVO) {
        return success(apiKeyService.createApiKey(createReqVO));
    }

    @PutMapping("/update")
    @Operation(summary = "更新 API 密钥")
    @PreAuthorize("@ss.hasPermission('ai:api-key:update')")
    public CommonResult<Boolean> updateApiKey(@Valid @RequestBody AiApiKeySaveReqVO updateReqVO) {
        apiKeyService.updateApiKey(updateReqVO);
        return success(true);
    }

    @PutMapping("/update-status")
    @Operation(summary = "启用/停用 API 密钥",
            description = "只改 status 一个字段。🔴 绝不能让前端把整行回传 /update："
                    + "AiApiKeyRespVO.apiKey 带 @SliderDesensitize，列表页拿到的是打码串，"
                    + "整行回传会把掩码当成真密钥加密落库、静默毁掉每一个密钥。"
                    + "副作用：密钥一停用，挂在它下面的模型全部在候选链选择期就被剔除")
    @PreAuthorize("@ss.hasPermission('ai:api-key:update')")
    public CommonResult<Boolean> updateApiKeyStatus(@Valid @RequestBody AiApiKeyUpdateStatusReqVO reqVO) {
        apiKeyService.updateApiKeyStatus(reqVO.getId(), reqVO.getStatus());
        return success(true);
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除 API 密钥")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('ai:api-key:delete')")
    public CommonResult<Boolean> deleteApiKey(@RequestParam("id") Long id) {
        apiKeyService.deleteApiKey(id);
        return success(true);
    }

    @GetMapping("/get")
    @Operation(summary = "获得 API 密钥")
    @Parameter(name = "id", description = "编号", required = true, example = "1024")
    @PreAuthorize("@ss.hasPermission('ai:api-key:query')")
    public CommonResult<AiApiKeyRespVO> getApiKey(@RequestParam("id") Long id) {
        AiApiKeyDO apiKey = apiKeyService.getApiKey(id);
        return success(BeanUtils.toBean(apiKey, AiApiKeyRespVO.class));
    }

    @GetMapping("/page")
    @Operation(summary = "获得 API 密钥分页")
    @PreAuthorize("@ss.hasPermission('ai:api-key:query')")
    public CommonResult<PageResult<AiApiKeyRespVO>> getApiKeyPage(@Valid AiApiKeyPageReqVO pageReqVO) {
        PageResult<AiApiKeyDO> pageResult = apiKeyService.getApiKeyPage(pageReqVO);
        return success(BeanUtils.toBean(pageResult, AiApiKeyRespVO.class));
    }

    @GetMapping("/simple-list")
    @Operation(summary = "获得 API 密钥分页列表")
    public CommonResult<List<AiModelRespVO>> getApiKeySimpleList() {
        List<AiApiKeyDO> list = apiKeyService.getApiKeyList();
        return success(convertList(list, key -> new AiModelRespVO().setId(key.getId()).setName(key.getName())));
    }

}