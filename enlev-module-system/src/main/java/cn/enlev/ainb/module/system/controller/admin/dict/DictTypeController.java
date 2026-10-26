package cn.enlev.ainb.module.system.controller.admin.dict;

import cn.enlev.ainb.framework.apilog.core.annotation.ApiAccessLog;
import cn.enlev.ainb.framework.common.pojo.CommonResult;
import cn.enlev.ainb.framework.common.pojo.PageParam;
import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.framework.common.util.http.HttpUtils;
import cn.enlev.ainb.framework.common.util.json.JsonUtils;
import cn.enlev.ainb.framework.common.util.object.BeanUtils;
import cn.enlev.ainb.framework.excel.core.util.ExcelUtils;
import cn.enlev.ainb.module.system.controller.admin.dict.vo.transfer.DictImportRespVO;
import cn.enlev.ainb.module.system.controller.admin.dict.vo.transfer.DictTransferVO;
import cn.enlev.ainb.module.system.controller.admin.dict.vo.type.DictTypeMoveReqVO;
import cn.enlev.ainb.module.system.controller.admin.dict.vo.type.DictTypePageReqVO;
import cn.enlev.ainb.module.system.controller.admin.dict.vo.type.DictTypeRespVO;
import cn.enlev.ainb.module.system.controller.admin.dict.vo.type.DictTypeSaveReqVO;
import cn.enlev.ainb.module.system.controller.admin.dict.vo.type.DictTypeSimpleRespVO;
import cn.enlev.ainb.module.system.dal.dataobject.dict.DictTypeDO;
import cn.enlev.ainb.module.system.service.dict.DictTypeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static cn.enlev.ainb.framework.apilog.core.enums.OperateTypeEnum.EXPORT;
import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.framework.common.pojo.CommonResult.success;
import static cn.enlev.ainb.module.system.enums.ErrorCodeConstants.DICT_TRANSFER_PARSE_FAILED;

@Tag(name = "管理后台 - 字典类型")
@RestController
@RequestMapping("/system/dict-type")
@Validated
public class DictTypeController {

    @Resource
    private DictTypeService dictTypeService;

    @PostMapping("/create")
    @Operation(summary = "创建字典类型")
    @PreAuthorize("@ss.hasPermission('system:dict:create')")
    public CommonResult<Long> createDictType(@Valid @RequestBody DictTypeSaveReqVO createReqVO) {
        Long dictTypeId = dictTypeService.createDictType(createReqVO);
        return success(dictTypeId);
    }

    @PutMapping("/update")
    @Operation(summary = "修改字典类型")
    @PreAuthorize("@ss.hasPermission('system:dict:update')")
    public CommonResult<Boolean> updateDictType(@Valid @RequestBody DictTypeSaveReqVO updateReqVO) {
        dictTypeService.updateDictType(updateReqVO);
        return success(true);
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除字典类型")
    @Parameter(name = "id", description = "编号", required = true, example = "1024")
    @PreAuthorize("@ss.hasPermission('system:dict:delete')")
    public CommonResult<Boolean> deleteDictType(@RequestParam("id") Long id) {
        dictTypeService.deleteDictType(id);
        return success(true);
    }

    @DeleteMapping("/delete-list")
    @Operation(summary = "批量删除字典类型")
    @Parameter(name = "ids", description = "编号列表", required = true)
    @PreAuthorize("@ss.hasPermission('system:dict:delete')")
    public CommonResult<Boolean> deleteDictTypeList(@RequestParam("ids") List<Long> ids) {
        dictTypeService.deleteDictTypeList(ids);
        return success(true);
    }

    @GetMapping("/page")
    @Operation(summary = "获得字典类型的分页列表")
    @PreAuthorize("@ss.hasPermission('system:dict:query')")
    public CommonResult<PageResult<DictTypeRespVO>> pageDictTypes(@Valid DictTypePageReqVO pageReqVO) {
        PageResult<DictTypeDO> pageResult = dictTypeService.getDictTypePage(pageReqVO);
        return success(BeanUtils.toBean(pageResult, DictTypeRespVO.class));
    }

    @Operation(summary = "/查询字典类型详细")
    @Parameter(name = "id", description = "编号", required = true, example = "1024")
    @GetMapping(value = "/get")
    @PreAuthorize("@ss.hasPermission('system:dict:query')")
    public CommonResult<DictTypeRespVO> getDictType(@RequestParam("id") Long id) {
        DictTypeDO dictType = dictTypeService.getDictType(id);
        return success(BeanUtils.toBean(dictType, DictTypeRespVO.class));
    }

    @GetMapping(value = {"/list-all-simple", "simple-list"})
    @Operation(summary = "获得全部字典类型列表", description = "包括开启 + 禁用的字典类型，主要用于前端的下拉选项")
    // 无需添加权限认证，因为前端全局都需要
    public CommonResult<List<DictTypeSimpleRespVO>> getSimpleDictTypeList() {
        List<DictTypeDO> list = dictTypeService.getDictTypeList();
        return success(BeanUtils.toBean(list, DictTypeSimpleRespVO.class));
    }

    @GetMapping("/list")
    @Operation(summary = "获得字典分类列表", description = "扁平列表，由前端 handleTree 组装成树；keyword 非空时连同祖先一并返回")
    @PreAuthorize("@ss.hasPermission('system:dict:query')")
    public CommonResult<List<DictTypeRespVO>> getDictTypeList(
            @RequestParam(value = "keyword", required = false) String keyword) {
        List<DictTypeDO> list = dictTypeService.getDictTypeList(keyword);
        return success(BeanUtils.toBean(list, DictTypeRespVO.class));
    }

    @PutMapping("/move")
    @Operation(summary = "移动字典分类")
    @PreAuthorize("@ss.hasPermission('system:dict:update')")
    public CommonResult<Boolean> moveDictType(@Valid @RequestBody DictTypeMoveReqVO reqVO) {
        dictTypeService.moveDictType(reqVO.getId(), reqVO.getParentId(), reqVO.getSort());
        return success(true);
    }

    @Operation(summary = "导出数据类型")
    @GetMapping("/export-excel")
    @PreAuthorize("@ss.hasPermission('system:dict:query')")
    @ApiAccessLog(operateType = EXPORT)
    public void export(HttpServletResponse response, @Valid DictTypePageReqVO exportReqVO) throws IOException {
        exportReqVO.setPageSize(PageParam.PAGE_SIZE_NONE);
        List<DictTypeDO> list = dictTypeService.getDictTypePage(exportReqVO).getList();
        // 导出
        ExcelUtils.write(response, "字典类型.xls", "数据", DictTypeRespVO.class,
                BeanUtils.toBean(list, DictTypeRespVO.class));
    }

    @Operation(summary = "导出字典迁移包",
            description = "导出该分类及其全部子分类，连同这些字典下的数据，产出一个 JSON 文件")
    @Parameter(name = "id", description = "分类编号", required = true, example = "1024")
    @GetMapping("/export-json")
    @PreAuthorize("@ss.hasPermission('system:dict:export')")
    @ApiAccessLog(operateType = EXPORT)
    public void exportJson(HttpServletResponse response, @RequestParam("id") Long id) throws IOException {
        DictTransferVO transfer = dictTypeService.exportDictTypes(id);
        DictTypeDO dictType = dictTypeService.getDictType(id);
        // 用缩进格式而不是压缩成一行：这个文件是要被人打开看、甚至手工改几行再导回去的，
        // 放进 Git 里做版本比对时，逐行的 diff 也才有意义
        byte[] content = JsonUtils.toJsonPrettyString(transfer).getBytes(StandardCharsets.UTF_8);
        String filename = (dictType != null ? dictType.getName() : "字典") + ".json";
        // 与 ExcelUtils 一致，header 放在写出之后设置，避免出错时 contentType 已被改掉
        response.getOutputStream().write(content);
        response.addHeader("Content-Disposition", "attachment;filename=" + HttpUtils.encodeUtf8(filename));
        // 内容确实是 JSON，但这里必须报 octet-stream：
        // 前端的响应拦截器约定「二进制接口返回了 application/json 就说明这次调用失败了」，
        // 会把响应体当成错误信封去解析。文件名后缀已经足够说明它是什么了
        response.setContentType("application/octet-stream");
    }

    @Operation(summary = "导入字典迁移包")
    @Parameter(name = "policy", description = "冲突处理策略：1-跳过 2-覆盖 3-副本，默认跳过")
    @Parameter(name = "parentId", description = "导入到哪个分组下，不传表示挂到分类树的根")
    @PostMapping("/import-json")
    @PreAuthorize("@ss.hasPermission('system:dict:import')")
    public CommonResult<DictImportRespVO> importJson(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "policy", required = false) Integer policy,
            @RequestParam(value = "parentId", required = false) Long parentId) throws IOException {
        DictTransferVO transfer;
        try {
            transfer = JsonUtils.parseObject(file.getBytes(), DictTransferVO.class);
        } catch (Exception ex) {
            // 用户传错文件（比如把 Excel 传到了这里）是最常见的情况，
            // 直接抛 Jackson 的原始异常，界面上会显示一串看不懂的解析错误
            throw exception(DICT_TRANSFER_PARSE_FAILED);
        }
        return success(dictTypeService.importDictTypes(transfer, policy, parentId));
    }

}
