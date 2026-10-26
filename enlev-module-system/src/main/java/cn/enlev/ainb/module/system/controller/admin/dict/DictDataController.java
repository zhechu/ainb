package cn.enlev.ainb.module.system.controller.admin.dict;

import cn.enlev.ainb.framework.apilog.core.annotation.ApiAccessLog;
import cn.enlev.ainb.framework.common.enums.CommonStatusEnum;
import cn.enlev.ainb.framework.common.pojo.CommonResult;
import cn.enlev.ainb.framework.common.pojo.PageParam;
import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.framework.common.util.object.BeanUtils;
import cn.enlev.ainb.framework.excel.core.util.ExcelUtils;
import cn.enlev.ainb.module.system.controller.admin.dict.vo.data.DictDataExcelVO;
import cn.enlev.ainb.module.system.controller.admin.dict.vo.data.DictDataImportRespVO;
import cn.enlev.ainb.module.system.controller.admin.dict.vo.data.DictDataMoveReqVO;
import cn.enlev.ainb.module.system.controller.admin.dict.vo.data.DictDataPageReqVO;
import cn.enlev.ainb.module.system.controller.admin.dict.vo.data.DictDataRespVO;
import cn.enlev.ainb.module.system.controller.admin.dict.vo.data.DictDataSaveReqVO;
import cn.enlev.ainb.module.system.controller.admin.dict.vo.data.DictDataSimpleRespVO;
import cn.enlev.ainb.module.system.controller.admin.dict.vo.data.DictDataSyncRespVO;
import cn.enlev.ainb.module.system.dal.dataobject.dict.DictDataDO;
import cn.enlev.ainb.module.system.dal.redis.dict.DictDataCache;
import cn.enlev.ainb.module.system.service.dict.DictDataService;
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
import java.util.Arrays;
import java.util.List;

import static cn.enlev.ainb.framework.apilog.core.enums.OperateTypeEnum.EXPORT;
import static cn.enlev.ainb.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 字典数据")
@RestController
@RequestMapping("/system/dict-data")
@Validated
public class DictDataController {

    @Resource
    private DictDataService dictDataService;

    @PostMapping("/create")
    @Operation(summary = "新增字典数据")
    @PreAuthorize("@ss.hasPermission('system:dict:create')")
    public CommonResult<Long> createDictData(@Valid @RequestBody DictDataSaveReqVO createReqVO) {
        Long dictDataId = dictDataService.createDictData(createReqVO);
        return success(dictDataId);
    }

    @PutMapping("/update")
    @Operation(summary = "修改字典数据")
    @PreAuthorize("@ss.hasPermission('system:dict:update')")
    public CommonResult<Boolean> updateDictData(@Valid @RequestBody DictDataSaveReqVO updateReqVO) {
        dictDataService.updateDictData(updateReqVO);
        return success(true);
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除字典数据")
    @Parameter(name = "id", description = "编号", required = true, example = "1024")
    @Parameter(name = "cascade", description = "是否级联删除子节点，默认否")
    @PreAuthorize("@ss.hasPermission('system:dict:delete')")
    public CommonResult<Boolean> deleteDictData(@RequestParam("id") Long id,
                                                @RequestParam(value = "cascade", required = false,
                                                        defaultValue = "false") boolean cascade) {
        dictDataService.deleteDictData(id, cascade);
        return success(true);
    }

    @GetMapping("/tree-list")
    @Operation(summary = "获得某个字典的数据列表", description = "扁平列表，由前端 handleTree 组装成树；keyword 非空时连同祖先一并返回")
    @PreAuthorize("@ss.hasPermission('system:dict:query')")
    public CommonResult<List<DictDataRespVO>> getDictDataTreeList(
            @RequestParam("dictType") String dictType,
            @RequestParam(value = "keyword", required = false) String keyword) {
        List<DictDataDO> list = dictDataService.getDictDataTreeList(dictType, keyword);
        return success(BeanUtils.toBean(list, DictDataRespVO.class));
    }

    @GetMapping("/children")
    @Operation(summary = "获得某个节点的直接子级", description = "供懒加载字典按需展开")
    @PreAuthorize("@ss.hasPermission('system:dict:query')")
    public CommonResult<List<DictDataRespVO>> getDictDataChildren(
            @RequestParam("dictType") String dictType,
            @RequestParam(value = "parentId", required = false) Long parentId) {
        List<DictDataDO> list = dictDataService.getDictDataChildren(dictType, parentId);
        return success(BeanUtils.toBean(list, DictDataRespVO.class));
    }

    @PutMapping("/move")
    @Operation(summary = "移动字典数据节点")
    @PreAuthorize("@ss.hasPermission('system:dict:update')")
    public CommonResult<Boolean> moveDictData(@Valid @RequestBody DictDataMoveReqVO reqVO) {
        dictDataService.moveDictData(reqVO.getId(), reqVO.getParentId(), reqVO.getSort());
        return success(true);
    }

    @DeleteMapping("/delete-list")
    @Operation(summary = "批量删除字典数据")
    @Parameter(name = "ids", description = "编号列表", required = true)
    @PreAuthorize("@ss.hasPermission('system:dict:delete')")
    public CommonResult<Boolean> deleteDictDataList(@RequestParam("ids") List<Long> ids) {
        dictDataService.deleteDictDataList(ids);
        return success(true);
    }

    @GetMapping(value = {"/list-all-simple", "simple-list"})
    @Operation(summary = "获得全部字典数据列表", description = "一般用于管理后台缓存字典数据在本地")
    // 无需添加权限认证，因为前端全局都需要
    public CommonResult<List<DictDataSimpleRespVO>> getSimpleDictDataList() {
        List<DictDataDO> list = dictDataService.getSimpleDictDataList();
        return success(BeanUtils.toBean(list, DictDataSimpleRespVO.class));
    }

    @GetMapping("/sync")
    @Operation(summary = "获得字典同步数据", description = "版本号 + 全量数据，两者来自同一次读取，供前端整体缓存")
    // 无需添加权限认证，理由同 simple-list：前端全局都需要
    public CommonResult<DictDataSyncRespVO> syncDictData() {
        DictDataCache cache = dictDataService.getDictDataCache();
        return success(new DictDataSyncRespVO(cache.getVersion(),
                BeanUtils.toBean(cache.getList(), DictDataSimpleRespVO.class)));
    }

    @GetMapping("/version")
    @Operation(summary = "获得字典版本号", description = "前端用它判断本地缓存是否还新鲜，一致则跳过全量拉取")
    // 无需添加权限认证，理由同 simple-list：前端全局都需要，且只返回一个数字
    public CommonResult<Long> getDictVersion() {
        return success(dictDataService.getDictVersion());
    }

    @GetMapping("/page")
    @Operation(summary = "获得字典类型的分页")
    @PreAuthorize("@ss.hasPermission('system:dict:query')")
    public CommonResult<PageResult<DictDataRespVO>> getDictTypePage(@Valid DictDataPageReqVO pageReqVO) {
        PageResult<DictDataDO> pageResult = dictDataService.getDictDataPage(pageReqVO);
        return success(BeanUtils.toBean(pageResult, DictDataRespVO.class));
    }

    @GetMapping(value = "/get")
    @Operation(summary = "/查询字典数据详细")
    @Parameter(name = "id", description = "编号", required = true, example = "1024")
    @PreAuthorize("@ss.hasPermission('system:dict:query')")
    public CommonResult<DictDataRespVO> getDictData(@RequestParam("id") Long id) {
        DictDataDO dictData = dictDataService.getDictData(id);
        return success(BeanUtils.toBean(dictData, DictDataRespVO.class));
    }

    @GetMapping("/export-excel")
    @Operation(summary = "导出字典数据", description = "父子关系用「上级键值」表达，导出的文件可以直接改完再导回来")
    @PreAuthorize("@ss.hasPermission('system:dict:export')")
    @ApiAccessLog(operateType = EXPORT)
    public void export(HttpServletResponse response, @RequestParam("dictType") String dictType) throws IOException {
        ExcelUtils.write(response, "字典数据.xls", "数据", DictDataExcelVO.class,
                dictDataService.exportDictDataList(dictType));
    }

    @GetMapping("/get-import-template")
    @Operation(summary = "获得字典数据导入模板")
    public void importTemplate(HttpServletResponse response) throws IOException {
        // 模板里给两行样例，第二行演示怎么用「上级键值」挂到第一行下面——
        // 光给一排表头，用户多半猜不出层级该怎么填
        List<DictDataExcelVO> list = Arrays.asList(
                DictDataExcelVO.builder().label("电子产品").value("elec").parentValue(null)
                        .sort(0).status(CommonStatusEnum.ENABLE.getStatus())
                        .colorType("primary").cssClass("").remark("顶层节点，上级键值留空").build(),
                DictDataExcelVO.builder().label("手机").value("phone").parentValue("elec")
                        .sort(1).status(CommonStatusEnum.ENABLE.getStatus())
                        .colorType("").cssClass("").remark("挂在 elec 下面").build()
        );
        ExcelUtils.write(response, "字典数据导入模板.xls", "数据", DictDataExcelVO.class, list);
    }

    @PostMapping("/import-excel")
    @Operation(summary = "导入字典数据")
    @Parameter(name = "dictType", description = "导入到哪个字典", required = true)
    @Parameter(name = "updateSupport", description = "键值已存在时是否更新，默认为 false")
    @PreAuthorize("@ss.hasPermission('system:dict:import')")
    public CommonResult<DictDataImportRespVO> importExcel(
            @RequestParam("file") MultipartFile file,
            @RequestParam("dictType") String dictType,
            @RequestParam(value = "updateSupport", required = false, defaultValue = "false") Boolean updateSupport)
            throws Exception {
        List<DictDataExcelVO> list = ExcelUtils.read(file, DictDataExcelVO.class);
        return success(dictDataService.importDictDataList(dictType, list, updateSupport));
    }

}
