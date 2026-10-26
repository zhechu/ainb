package cn.enlev.ainb.module.infra.controller.admin.datamodel;

import cn.enlev.ainb.framework.common.pojo.CommonResult;
import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.framework.common.util.object.BeanUtils;
import cn.enlev.ainb.framework.mybatis.core.util.SystemTablePolicy;
import cn.enlev.ainb.framework.mybatis.core.util.TableOperation;
import cn.enlev.ainb.module.infra.controller.admin.datamodel.vo.*;
import cn.enlev.ainb.module.infra.dal.dataobject.datamodel.DataModelDO;
import cn.enlev.ainb.module.infra.enums.datamodel.DataModelColumnTypeEnum;
import cn.enlev.ainb.module.infra.framework.datamodel.core.model.TableColumnModel;
import cn.enlev.ainb.module.infra.service.datamodel.DataModelService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static cn.enlev.ainb.framework.common.pojo.CommonResult.success;

/**
 * 数据模型 Controller
 *
 * <p>菜单名与代码名现在一致了：都叫「数据模型」（菜单原名「数据建模」）。
 * 表 {@code infra_data_model}、类 {@code DataModelDO} 从来就是这个叫法，
 * 只有菜单是当初另起的名，本次把菜单对齐过来
 */
@Tag(name = "管理后台 - 数据模型")
@RestController
@RequestMapping("/infra/data-model")
@Validated
public class DataModelController {

    @Resource
    private DataModelService dataModelService;

    @PostMapping("/create")
    @Operation(summary = "创建数据模型", description = "会在数据库中真实执行 CREATE TABLE")
    @PreAuthorize("@ss.hasPermission('infra:data-model:create')")
    public CommonResult<Long> createDataModel(@Valid @RequestBody DataModelSaveReqVO createReqVO) {
        return success(dataModelService.createDataModel(createReqVO));
    }

    @PutMapping("/update")
    @Operation(summary = "更新数据模型", description = "会在数据库中真实执行 ALTER TABLE，且无法回滚")
    @PreAuthorize("@ss.hasPermission('infra:data-model:update')")
    public CommonResult<Boolean> updateDataModel(@Valid @RequestBody DataModelSaveReqVO updateReqVO) {
        dataModelService.updateDataModel(updateReqVO);
        return success(true);
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除数据模型")
    @Parameter(name = "id", description = "编号", required = true, example = "1")
    @Parameter(name = "dropTable", description = "是否同时删除真实表", example = "false")
    @Parameter(name = "riskConfirmed", description = "是否已确认风险", example = "false")
    @PreAuthorize("@ss.hasPermission('infra:data-model:delete')")
    public CommonResult<Boolean> deleteDataModel(@RequestParam("id") Long id,
                                                 @RequestParam(value = "dropTable", defaultValue = "false") Boolean dropTable,
                                                 @RequestParam(value = "riskConfirmed", defaultValue = "false") Boolean riskConfirmed) {
        dataModelService.deleteDataModel(id, dropTable, riskConfirmed);
        return success(true);
    }

    @PostMapping("/import")
    @Operation(summary = "导入已有表", description = "仅登记模型，不执行 DDL")
    @PreAuthorize("@ss.hasPermission('infra:data-model:create')")
    public CommonResult<Boolean> importTables(@RequestParam("dataSourceConfigId") Long dataSourceConfigId,
                                              @RequestParam("tableNames") List<String> tableNames) {
        dataModelService.importTables(dataSourceConfigId, tableNames);
        return success(true);
    }

    @GetMapping("/get")
    @Operation(summary = "获得数据模型")
    @Parameter(name = "id", description = "编号", required = true, example = "1")
    @PreAuthorize("@ss.hasPermission('infra:data-model:query')")
    public CommonResult<DataModelRespVO> getDataModel(@RequestParam("id") Long id) {
        DataModelDO dataModel = dataModelService.getDataModel(id);
        return success(BeanUtils.toBean(dataModel, DataModelRespVO.class));
    }

    @GetMapping("/page")
    @Operation(summary = "获得数据模型分页")
    @PreAuthorize("@ss.hasPermission('infra:data-model:query')")
    public CommonResult<PageResult<DataModelRespVO>> getDataModelPage(@Valid DataModelPageReqVO pageReqVO) {
        PageResult<DataModelDO> pageResult = dataModelService.getDataModelPage(pageReqVO);
        return success(BeanUtils.toBean(pageResult, DataModelRespVO.class));
    }

    @GetMapping("/list-columns")
    @Operation(summary = "获得模型的字段列表", description = "实时读取真实表结构")
    @Parameter(name = "id", description = "编号", required = true, example = "1")
    @PreAuthorize("@ss.hasPermission('infra:data-model:query')")
    public CommonResult<List<TableColumnModel>> getDataModelColumns(@RequestParam("id") Long id) {
        return success(dataModelService.getDataModelColumns(id));
    }

    @GetMapping("/structure")
    @Operation(summary = "获得模型的完整结构", description = "字段 + 索引，实时读取真实表结构")
    @Parameter(name = "id", description = "编号", required = true, example = "1")
    @PreAuthorize("@ss.hasPermission('infra:data-model:query')")
    public CommonResult<DataModelStructureRespVO> getDataModelStructure(@RequestParam("id") Long id) {
        return success(dataModelService.getDataModelStructure(id));
    }

    @GetMapping("/list-system-column")
    @Operation(summary = "获得系统内置字段列表", description = "点「新建」时以只读方式展示")
    @Parameter(name = "profile", description = "系统列策略：TENANT（默认）/ GLOBAL / LEGACY", example = "TENANT")
    @PreAuthorize("@ss.hasPermission('infra:data-model:query')")
    public CommonResult<List<TableColumnModel>> getSystemColumns(
            @RequestParam(value = "profile", required = false) String profile) {
        // 带上 profile，界面展示的系统列才与实际建出来的表一致。
        // 少了这个参数，选了 GLOBAL 的用户界面上仍会看到 tenant_id，而新建时界面字段是要一并提交的
        // —— 注入器发现已存在同名列就「让位」，结果表里照样有 tenant_id，profile 形同虚设。
        return success(dataModelService.getSystemColumns(profile));
    }

    /**
     * 下发系统表前缀给前端
     *
     * 前端此前硬编码了一份同样的名单（用于禁用删除按钮），与后端两处维护必然漂移。
     * 这是运行期策略而非构建期结构性事实，页面初始化拉一次就够，不必像控件契约那样硬同步。
     *
     * 口径取 DDL —— 前端用它判断「这张表能不能通过数据模型删」，与后端 checkNotFrameworkTable 一致。
     */
    @GetMapping("/list-system-table-prefix")
    @Operation(summary = "获得系统表前缀列表", description = "前端用于禁用系统表的删除按钮")
    @PreAuthorize("@ss.hasPermission('infra:data-model:query')")
    public CommonResult<List<String>> getSystemTablePrefixes() {
        return success(SystemTablePolicy.prefixes(TableOperation.DDL).stream().sorted().toList());
    }

    @PostMapping("/preview-ddl")
    @Operation(summary = "预览待执行的 DDL", description = "不实际执行，用于改表前确认")
    @PreAuthorize("@ss.hasPermission('infra:data-model:query')")
    public CommonResult<List<String>> previewDdl(@Valid @RequestBody DataModelSaveReqVO saveReqVO) {
        return success(dataModelService.previewDdl(saveReqVO));
    }

    @GetMapping("/list-database-table")
    @Operation(summary = "获得数据库的表列表", description = "用于导入已有表")
    @PreAuthorize("@ss.hasPermission('infra:data-model:query')")
    public CommonResult<List<Map<String, Object>>> getDatabaseTableList(
            @RequestParam(value = "dataSourceConfigId", defaultValue = "0") Long dataSourceConfigId,
            @RequestParam(value = "tableName", required = false) String tableName,
            @RequestParam(value = "tableComment", required = false) String tableComment) {
        return success(dataModelService.getDatabaseTableList(dataSourceConfigId, tableName, tableComment));
    }

    @GetMapping("/table-data-page")
    @Operation(summary = "预览表数据")
    @Parameter(name = "id", description = "编号", required = true, example = "1")
    @PreAuthorize("@ss.hasPermission('infra:data-model:query')")
    public CommonResult<PageResult<Map<String, Object>>> getTableDataPage(
            @RequestParam("id") Long id,
            @RequestParam(value = "pageNo", defaultValue = "1") Integer pageNo,
            @RequestParam(value = "pageSize", defaultValue = "10") Integer pageSize) {
        return success(dataModelService.getTableDataPage(id, pageNo, pageSize));
    }

    @GetMapping("/log-page")
    @Operation(summary = "获得 DDL 执行日志分页")
    @PreAuthorize("@ss.hasPermission('infra:data-model:query')")
    public CommonResult<PageResult<DataModelLogRespVO>> getDataModelLogPage(@Valid DataModelLogPageReqVO pageReqVO) {
        return success(BeanUtils.toBean(dataModelService.getDataModelLogPage(pageReqVO), DataModelLogRespVO.class));
    }

    @GetMapping("/list-column-type")
    @Operation(summary = "获得支持的字段类型列表")
    @PreAuthorize("@ss.hasPermission('infra:data-model:query')")
    public CommonResult<List<Map<String, Object>>> getColumnTypeList() {
        List<Map<String, Object>> list = Arrays.stream(DataModelColumnTypeEnum.values())
                .map(item -> Map.of(
                        "type", (Object) item.getType(),
                        "mysqlType", item.getMysqlType(),
                        "javaType", item.getJavaType(),
                        "needLength", item.getNeedLength(),
                        "needScale", item.getNeedScale(),
                        "description", item.getDescription()))
                .toList();
        return success(list);
    }

}
