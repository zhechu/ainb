package cn.enlev.ainb.module.infra.controller.admin.datamodel;

import cn.enlev.ainb.framework.common.pojo.CommonResult;
import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.module.infra.controller.admin.datamodel.vo.DataModelDataPageReqVO;
import cn.enlev.ainb.module.infra.framework.datamodel.core.model.TableColumnModel;
import cn.enlev.ainb.module.infra.service.datamodel.DataModelDataService;
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

/**
 * 动态数据 Controller
 *
 * 免代码生成的业务数据增删改查。权限串独立于建模本身（infra:data-model-data:*），
 * 这样「能设计表结构的人」与「能录业务数据的人」可以是不同角色。
 *
 * <p>🔴 菜单名叫「数据查看」（原名「业务数据」），代码与实体仍叫「业务数据 / 动态数据」。
 * 这个分歧是刻意的：菜单名描述的是<b>用户在这里干什么</b>（看数据），
 * 代码名描述的是<b>这张表存的是什么</b>（模型对应的业务数据行）。
 * 把代码跟着改成「数据查看」会造出 {@code DataViewDO} 这种既不像表也不像行为的名字，
 * 而且 {@code infra:data-model-data:*} 权限串一改，已授出的权全部失效。
 * 读到这里的不要「顺手统一」—— 只统一用户看得见的地方（菜单、@Tag、按钮文案）
 *
 * @author 走进新时代
 */
@Tag(name = "管理后台 - 数据查看")
@RestController
@RequestMapping("/infra/data-model-data")
@Validated
public class DataModelDataController {

    @Resource
    private DataModelDataService dataModelDataService;

    @PostMapping("/page")
    @Operation(summary = "获得业务数据分页", description = "支持动态查询条件与排序")
    @PreAuthorize("@ss.hasPermission('infra:data-model-data:query')")
    public CommonResult<PageResult<Map<String, Object>>> getDataPage(
            @Valid @RequestBody DataModelDataPageReqVO pageReqVO) {
        return success(dataModelDataService.getDataPage(pageReqVO));
    }

    @GetMapping("/get")
    @Operation(summary = "获得业务数据")
    @Parameter(name = "modelId", description = "模型编号", required = true, example = "1")
    @Parameter(name = "id", description = "数据主键", required = true, example = "1024")
    @PreAuthorize("@ss.hasPermission('infra:data-model-data:query')")
    public CommonResult<Map<String, Object>> getData(@RequestParam("modelId") Long modelId,
                                                     @RequestParam("id") String id) {
        return success(dataModelDataService.getData(modelId, id));
    }

    @GetMapping("/list-columns")
    @Operation(summary = "获得字段定义", description = "供前端渲染动态列表与表单")
    @Parameter(name = "modelId", description = "模型编号", required = true, example = "1")
    @PreAuthorize("@ss.hasPermission('infra:data-model-data:query')")
    public CommonResult<List<TableColumnModel>> getDataColumns(@RequestParam("modelId") Long modelId) {
        return success(dataModelDataService.getDataColumns(modelId));
    }

//    @PostMapping("/create")
//    @Operation(summary = "创建业务数据")
//    @Parameter(name = "modelId", description = "模型编号", required = true, example = "1")
//    @PreAuthorize("@ss.hasPermission('infra:data-model-data:create')")
//    public CommonResult<Object> createData(@RequestParam("modelId") Long modelId,
//                                           @RequestBody Map<String, Object> data) {
//        return success(dataModelDataService.createData(modelId, data));
//    }

//    @PutMapping("/update")
//    @Operation(summary = "更新业务数据")
//    @Parameter(name = "modelId", description = "模型编号", required = true, example = "1")
//    @Parameter(name = "id", description = "数据主键", required = true, example = "1024")
//    @PreAuthorize("@ss.hasPermission('infra:data-model-data:update')")
//    public CommonResult<Boolean> updateData(@RequestParam("modelId") Long modelId,
//                                            @RequestParam("id") String id,
//                                            @RequestBody Map<String, Object> data) {
//        dataModelDataService.updateData(modelId, id, data);
//        return success(true);
//    }

//    @DeleteMapping("/delete")
//    @Operation(summary = "删除业务数据")
//    @Parameter(name = "modelId", description = "模型编号", required = true, example = "1")
//    @Parameter(name = "id", description = "数据主键", required = true, example = "1024")
//    @PreAuthorize("@ss.hasPermission('infra:data-model-data:delete')")
//    public CommonResult<Boolean> deleteData(@RequestParam("modelId") Long modelId,
//                                            @RequestParam("id") String id) {
//        dataModelDataService.deleteData(modelId, id);
//        return success(true);
//    }

//    @DeleteMapping("/delete-list")
//    @Operation(summary = "批量删除业务数据")
//    @Parameter(name = "modelId", description = "模型编号", required = true, example = "1")
//    @Parameter(name = "ids", description = "数据主键列表", required = true, example = "1,2,3")
//    @PreAuthorize("@ss.hasPermission('infra:data-model-data:delete')")
//    public CommonResult<Boolean> deleteDataList(@RequestParam("modelId") Long modelId,
//                                                 @RequestParam("ids") List<String> ids) {
//        dataModelDataService.deleteDataList(modelId, List.copyOf(ids));
//        return success(true);
//    }

}
