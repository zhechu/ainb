package cn.enlev.ainb.module.infra.controller.admin.formdesign;

import cn.enlev.ainb.framework.common.pojo.CommonResult;
import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.framework.common.util.collection.CollectionUtils;
import cn.enlev.ainb.framework.common.util.object.BeanUtils;
import cn.enlev.ainb.framework.mybatis.core.util.SystemTablePolicy;
import cn.enlev.ainb.module.infra.controller.admin.formdesign.vo.FormDesignDbColumnRespVO;
import cn.enlev.ainb.module.infra.controller.admin.formdesign.vo.FormDesignDbTableRespVO;
import cn.enlev.ainb.module.infra.controller.admin.formdesign.vo.FormDesignPageReqVO;
import cn.enlev.ainb.module.infra.controller.admin.formdesign.vo.FormDesignPublishReqVO;
import cn.enlev.ainb.module.infra.controller.admin.formdesign.vo.FormDesignPublishRespVO;
import cn.enlev.ainb.module.infra.controller.admin.formdesign.vo.FormDesignRespVO;
import cn.enlev.ainb.module.infra.controller.admin.formdesign.vo.FormDesignSaveReqVO;
import cn.enlev.ainb.module.infra.controller.admin.formdesign.vo.FormDesignSimpleRespVO;
import cn.enlev.ainb.module.infra.controller.admin.formdesign.vo.FormDesignUnpublishRespVO;
import cn.enlev.ainb.module.infra.controller.admin.formdesign.vo.FormDesignVersionRespVO;
import cn.enlev.ainb.module.infra.controller.admin.formdesign.vo.FormWidgetContractRespVO;
import cn.enlev.ainb.module.infra.dal.dataobject.db.DataSourceConfigDO;
import cn.enlev.ainb.module.infra.dal.dataobject.formdesign.FormDesignDO;
import cn.enlev.ainb.module.infra.dal.dataobject.formdesign.FormDesignVersionDO;
import cn.enlev.ainb.module.infra.framework.formdesign.core.FormFieldsCodec;
import cn.enlev.ainb.module.infra.framework.formdesign.core.cellformat.CellFormatContractRegistry;
import cn.enlev.ainb.module.infra.framework.formdesign.core.contract.WidgetContractRegistry;
import cn.enlev.ainb.module.infra.framework.formdesign.core.extension.FormDesignExtensionRegistry;
import cn.enlev.ainb.module.infra.framework.formdesign.core.validation.ValidationPatternRegistry;
import cn.enlev.ainb.module.infra.service.db.DataSourceConfigService;
import cn.enlev.ainb.module.infra.service.db.DatabaseTableService;
import cn.enlev.ainb.module.infra.service.formdesign.FormDesignService;
import cn.enlev.ainb.module.infra.service.formdesign.FormDesignVersionService;
import com.baomidou.mybatisplus.generator.config.po.TableField;
import com.baomidou.mybatisplus.generator.config.po.TableInfo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static cn.enlev.ainb.framework.common.pojo.CommonResult.success;

/**
 * 表单设计 Controller
 *
 * 本模块是独立的低代码表单模块，与「表单构建」（infra/build，仅产出代码不落库）
 * 和「流程表单」（bpm_form，仅供工作流使用）完全隔离。
 *
 * 注意：字典标签与枚举文案由前端渲染（DictTag / getStrDictOptions），后端只返回原始值，
 * 这与项目既有惯例一致。
 *
 * @author 走进新时代
 */
@Tag(name = "管理后台 - 表单设计")
@RestController
@RequestMapping("/infra/form-design")
@Validated
public class FormDesignController {

    @Resource
    private FormDesignService formDesignService;
    @Resource
    private FormDesignVersionService versionService;
    @Resource
    private WidgetContractRegistry widgetContractRegistry;
    @Resource
    private CellFormatContractRegistry cellFormatRegistry;
    @Resource
    private ValidationPatternRegistry patternRegistry;
    @Resource
    private FormDesignExtensionRegistry extensionRegistry;
    @Resource
    private DataSourceConfigService dataSourceConfigService;
    @Resource
    private DatabaseTableService databaseTableService;

    @PostMapping("/create")
    @Operation(summary = "创建表单设计")
    @PreAuthorize("@ss.hasPermission('infra:form-design:create')")
    public CommonResult<Long> createFormDesign(@Valid @RequestBody FormDesignSaveReqVO reqVO) {
        return success(formDesignService.createFormDesign(reqVO));
    }

    @PutMapping("/update")
    @Operation(summary = "更新表单设计",
            description = "已发布的表单被修改后，状态会变为「已发布有改动」，需重新发布才生效")
    @PreAuthorize("@ss.hasPermission('infra:form-design:update')")
    public CommonResult<Boolean> updateFormDesign(@Valid @RequestBody FormDesignSaveReqVO reqVO) {
        formDesignService.updateFormDesign(reqVO);
        return success(true);
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除表单设计")
    @Parameter(name = "id", description = "编号", required = true, example = "1")
    @PreAuthorize("@ss.hasPermission('infra:form-design:delete')")
    public CommonResult<Boolean> deleteFormDesign(@RequestParam("id") Long id) {
        formDesignService.deleteFormDesign(id);
        return success(true);
    }

    @GetMapping("/get")
    @Operation(summary = "获得表单设计")
    @Parameter(name = "id", description = "编号", required = true, example = "1")
    @PreAuthorize("@ss.hasPermission('infra:form-design:query')")
    public CommonResult<FormDesignRespVO> getFormDesign(@RequestParam("id") Long id) {
        FormDesignDO formDesign = formDesignService.getFormDesign(id);
        FormDesignRespVO respVO = convertToRespVO(formDesign);
        // 第二道模式错配告警：把「该表单是否存在服务端扩展点实现」一并下发，
        // 设计器据此在属性面板提示「有实现但模式不匹配，这些扩展点不会执行」并给一键切换。
        // 放在详情接口而不是单开一个接口：设计器打开表单时本来就要调它，省一次往返
        respVO.setHasServerExtension(extensionRegistry.hasExtension(respVO.getCode()));
        respVO.setServerExtensionNames(extensionRegistry.extensionNames(respVO.getCode()));
        return success(respVO);
    }

    @GetMapping("/page")
    @Operation(summary = "获得表单设计分页")
    @PreAuthorize("@ss.hasPermission('infra:form-design:query')")
    public CommonResult<PageResult<FormDesignRespVO>> getFormDesignPage(@Valid FormDesignPageReqVO pageReqVO) {
        PageResult<FormDesignDO> pageResult = formDesignService.getFormDesignPage(pageReqVO);
        PageResult<FormDesignRespVO> result = BeanUtils.toBean(pageResult, FormDesignRespVO.class);
        // 列表页不需要返回体积可能很大的设计产物，置空以减小响应
        if (result.getList() != null) {
            result.getList().forEach(item -> {
                item.setFormConf(null);
                item.setFormFields(null);
                item.setListConf(null);
                item.setTableBinding(null);
            });
        }
        return success(result);
    }

    @GetMapping("/inspect")
    @Operation(summary = "设计期体检", description = "把这张表单的问题一次列全，不改动任何数据。"
            + "与发布走同一份校验 —— 体检通过就一定发得出去")
    @Parameter(name = "id", description = "表单编号", required = true, example = "1")
    @PreAuthorize("@ss.hasPermission('infra:form-design:query')")
    public CommonResult<List<String>> inspect(@RequestParam("id") Long id) {
        return success(formDesignService.inspect(id));
    }

    @GetMapping("/simple-list")
    @Operation(summary = "获得表单设计精简列表", description = "供其它功能引用表单时使用")
    @PreAuthorize("@ss.hasPermission('infra:form-design:query')")
    public CommonResult<List<FormDesignSimpleRespVO>> getFormDesignSimpleList() {
        List<FormDesignDO> list = formDesignService.getFormDesignSimpleList();
        return success(BeanUtils.toBean(list, FormDesignSimpleRespVO.class));
    }

    @GetMapping("/cell-format-contracts")
    @Operation(summary = "获得列格式化契约",
            description = "列表怎么显示、导出怎么导，两端共用这一份声明。"
                    + "前端另有一份同名文件作为构建期副本，两份是否一致由一致性测试守着 —— "
                    + "各写各的话，前端改了某种格式化、导出那边不知道，"
                    + "表现是「列表显示正常、导出是另一套」")
    @PreAuthorize("@ss.hasPermission('infra:form-design:query')")
    public CommonResult<Map<String, Object>> getCellFormatContracts() {
        // 直接把契约文件原样回出去。刻意不逐字段搬进一个 VO ——
        // 搬一遍就多一处要跟着改的地方，而这个接口的唯一用途就是给
        // 一致性测试拿去与前端副本逐字段比对
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("contractVersion", cellFormatRegistry.getContractVersion());
        result.put("emptyCell", cellFormatRegistry.getEmptyCell());
        result.put("numberFormat", cellFormatRegistry.getNumberFormat());
        result.put("booleanText", cellFormatRegistry.getBooleanText());
        result.put("rowKinds", cellFormatRegistry.getRowKinds());
        result.put("formatters", cellFormatRegistry.listAll());
        return success(result);
    }

    @GetMapping("/widget-contracts")
    @Operation(summary = "获得表单控件契约",
            description = "控件的值类型、落库形态、可映射列类型与能力位。前端设计器与列表/查询/导出的能力判断都读这份契约，"
                    + "不再各自硬编码。前端另有一份同名文件作为构建期副本，两份是否一致由一致性测试守着")
    @PreAuthorize("@ss.hasPermission('infra:form-design:query')")
    public CommonResult<FormWidgetContractRespVO> getWidgetContracts() {
        FormWidgetContractRespVO vo = new FormWidgetContractRespVO();
        vo.setContractVersion(widgetContractRegistry.getContractVersion());
        vo.setSubTableWidgetType(widgetContractRegistry.getSubTableWidgetType());
        vo.setSubFieldSeparator(widgetContractRegistry.getSubFieldSeparator());
        vo.setNestedRulePath(widgetContractRegistry.getNestedRulePath());
        vo.setLayoutContainerTypes(widgetContractRegistry.getLayoutContainerTypes());
        vo.setRichTextWidgetTypes(widgetContractRegistry.getRichTextWidgetTypes());
        vo.setDelimitedSeparator(widgetContractRegistry.getDelimitedSeparator());
        vo.setWidgets(widgetContractRegistry.listAll());
        return success(vo);
    }

    @GetMapping("/validation-patterns")
    @Operation(summary = "获得内置正则库",
            description = "字段格式校验可选的内置正则。前端另有一份同名文件作为构建期副本 —— "
                    + "校验必须在前端即时反馈、又必须在服务端复校验，两侧的正则一旦漂移，"
                    + "就会出现「界面上填好了但提交失败」。本接口是一致性测试的比对基准")
    @PreAuthorize("@ss.hasPermission('infra:form-design:query')")
    public CommonResult<Map<String, Object>> getValidationPatterns() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("libraryVersion", patternRegistry.getLibraryVersion());
        result.put("patterns", patternRegistry.listAll());
        return success(result);
    }

    @PostMapping("/copy")
    @Operation(summary = "复制表单设计", description = "副本为草稿状态，不继承菜单与版本历史")
    @Parameter(name = "id", description = "被复制的编号", required = true, example = "1")
    @PreAuthorize("@ss.hasPermission('infra:form-design:create')")
    public CommonResult<Long> copyFormDesign(@RequestParam("id") Long id) {
        return success(formDesignService.copyFormDesign(id));
    }

    @GetMapping("/check-code")
    @Operation(summary = "校验表单编码是否可用", description = "可用返回 true，已被占用返回 false")
    @Parameter(name = "code", description = "表单编码", required = true, example = "customer")
    @Parameter(name = "id", description = "编号，更新时传入以排除自身", example = "1")
    @PreAuthorize("@ss.hasPermission('infra:form-design:query')")
    public CommonResult<Boolean> checkCode(@RequestParam("code") String code,
                                           @RequestParam(value = "id", required = false) Long id) {
        return success(formDesignService.isCodeAvailable(id, code));
    }

    /**
     * DO 转 RespVO
     *
     * formFields 在 DO 里是整份 JSON 字符串，在 VO 里是 form-create 的规则列表，需显式转换。
     */
    private FormDesignRespVO convertToRespVO(FormDesignDO formDesign) {
        if (formDesign == null) {
            return null;
        }
        FormDesignRespVO respVO = BeanUtils.toBean(formDesign, FormDesignRespVO.class);
        respVO.setFormFields(FormFieldsCodec.read(formDesign.getFormFields()));
        return respVO;
    }

    // ==================== 发布与版本 ====================

    @PostMapping("/publish")
    @Operation(summary = "发布表单",
            description = "后端只做校验+存版本+更新状态，并返回建菜单所需配置；"
                    + "菜单由前端再调 /system/menu/create 创建（infra 不能反向依赖 system）")
    @PreAuthorize("@ss.hasPermission('infra:form-design:publish')")
    public CommonResult<FormDesignPublishRespVO> publish(@Valid @RequestBody FormDesignPublishReqVO reqVO) {
        return success(formDesignService.publish(reqVO));
    }

    @PostMapping("/bind-menu")
    @Operation(summary = "登记前端已创建的菜单", description = "前端建完菜单后回调，供下线时精确回收")
    @Parameter(name = "designId", description = "表单设计编号", required = true, example = "1")
    @Parameter(name = "menuId", description = "菜单编号", required = true, example = "2540")
    @PreAuthorize("@ss.hasPermission('infra:form-design:publish')")
    public CommonResult<Boolean> bindMenu(@RequestParam("designId") Long designId,
                                          @RequestParam("menuId") Long menuId,
                                          @RequestParam(value = "permissionMenuIds", required = false) List<Long> permissionMenuIds) {
        formDesignService.bindMenu(designId, menuId, permissionMenuIds);
        return success(true);
    }

    @PostMapping("/bind-app-nav")
    @Operation(summary = "登记前端已创建的 App 导航节点", description = "前端建完导航节点后回调，供下线时精确回收")
    @Parameter(name = "designId", description = "表单设计编号", required = true, example = "1")
    @Parameter(name = "navNodeId", description = "导航节点编号", required = true, example = "356")
    @PreAuthorize("@ss.hasPermission('infra:form-design:publish')")
    public CommonResult<Boolean> bindAppNav(@RequestParam("designId") Long designId,
                                            @RequestParam("navNodeId") Long navNodeId) {
        formDesignService.bindAppNav(designId, navNodeId);
        return success(true);
    }

    @PostMapping("/unpublish")
    @Operation(summary = "下线表单", description = "返回需要前端删除的菜单编号列表与 App 导航节点编号")
    @Parameter(name = "id", description = "编号", required = true, example = "1")
    @PreAuthorize("@ss.hasPermission('infra:form-design:publish')")
    public CommonResult<FormDesignUnpublishRespVO> unpublish(@RequestParam("id") Long id) {
        return success(formDesignService.unpublish(id));
    }

    @GetMapping("/version-list")
    @Operation(summary = "获得版本列表", description = "按版本号倒序，最多保留最新 100 个")
    @Parameter(name = "designId", description = "表单设计编号", required = true, example = "1")
    @PreAuthorize("@ss.hasPermission('infra:form-design:query')")
    public CommonResult<List<FormDesignVersionRespVO>> getVersionList(@RequestParam("designId") Long designId) {
        List<FormDesignVersionDO> list = versionService.getVersionList(designId);
        return success(BeanUtils.toBean(list, FormDesignVersionRespVO.class));
    }

    @GetMapping("/get-version")
    @Operation(summary = "获得指定版本的快照")
    @Parameter(name = "designId", description = "表单设计编号", required = true, example = "1")
    @Parameter(name = "version", description = "版本号", required = true, example = "2")
    @PreAuthorize("@ss.hasPermission('infra:form-design:query')")
    public CommonResult<FormDesignVersionRespVO> getVersion(@RequestParam("designId") Long designId,
                                                             @RequestParam("version") Integer version) {
        FormDesignVersionDO versionDO = versionService.getVersion(designId, version);
        return success(BeanUtils.toBean(versionDO, FormDesignVersionRespVO.class));
    }

    @PostMapping("/rollback")
    @Operation(summary = "回滚到指定版本",
            description = "只把快照写回草稿，线上版本不变，需再点发布才生效")
    @Parameter(name = "designId", description = "表单设计编号", required = true, example = "1")
    @Parameter(name = "version", description = "目标版本号", required = true, example = "2")
    @PreAuthorize("@ss.hasPermission('infra:form-design:rollback')")
    public CommonResult<Boolean> rollback(@RequestParam("designId") Long designId,
                                          @RequestParam("version") Integer version) {
        versionService.rollback(designId, version);
        return success(true);
    }

    // ==================== 数据库元数据：供前端选表选列 ====================

    @GetMapping("/data-sources")
    @Operation(summary = "获得可用数据源列表", description = "供选择数据源下拉框使用")
    @PreAuthorize("@ss.hasPermission('infra:form-design:query')")
    public CommonResult<List<DataSourceConfigDO>> getDataSourceList() {
        return success(dataSourceConfigService.getDataSourceConfigList());
    }

    @GetMapping("/db-tables")
    @Operation(summary = "获得数据源的表列表", description = "供选择主表/从表/子表使用")
    @Parameter(name = "dataSourceConfigId", description = "数据源编号，0 为主库", required = true, example = "0")
    @Parameter(name = "nameLike", description = "表名模糊匹配", example = "customer")
    @PreAuthorize("@ss.hasPermission('infra:form-design:query')")
    public CommonResult<List<FormDesignDbTableRespVO>> getDbTables(
            @RequestParam("dataSourceConfigId") Long dataSourceConfigId,
            @RequestParam(value = "nameLike", required = false) String nameLike) {
        List<TableInfo> tableList = databaseTableService.getTableList(dataSourceConfigId, nameLike, null);
        // 过滤：只返回有 tenant_id 列的表，因为表单设计模块必须支持多租户
        List<FormDesignDbTableRespVO> result = tableList.stream()
                .filter(table -> hasTenantIdColumn(dataSourceConfigId, table.getName()))
                .map(table -> {
                    FormDesignDbTableRespVO vo = new FormDesignDbTableRespVO();
                    vo.setName(table.getName());
                    vo.setComment(table.getComment());
                    vo.setFrameworkTable(isFrameworkTable(table.getName()));
                    return vo;
                })
                .collect(Collectors.toList());
        return success(result);
    }

    @GetMapping("/table-columns")
    @Operation(summary = "获得表的列信息", description = "供配置字段映射使用")
    @Parameter(name = "dataSourceConfigId", description = "数据源编号，0 为主库", required = true, example = "0")
    @Parameter(name = "tableName", description = "表名", required = true, example = "biz_customer")
    @PreAuthorize("@ss.hasPermission('infra:form-design:query')")
    public CommonResult<List<FormDesignDbColumnRespVO>> getTableColumns(
            @RequestParam("dataSourceConfigId") Long dataSourceConfigId,
            @RequestParam("tableName") String tableName) {
        TableInfo table = databaseTableService.getTable(dataSourceConfigId, tableName);
        if (table == null || table.getFields() == null) {
            return success(List.of());
        }
        // 过滤掉系统自动维护的字段（这些字段不应出现在手动映射的下拉框里）
        // 但保留主键，因为某些场景需要显式映射主键（如关联查询）
        List<FormDesignDbColumnRespVO> result = table.getFields().stream()
                .filter(field -> !isAutoManagedColumn(field.getName()) || field.isKeyFlag())
                .map(this::convertColumn)
                .collect(Collectors.toList());
        return success(result);
    }

    /**
     * 系统自动管理的列名（框架自动填充，不需要用户手动映射）
     */
    private static final String[] AUTO_MANAGED_COLUMNS = {
            "creator", "create_time", "updater", "update_time", "deleted", "tenant_id"
    };

    /**
     * 判断是否是系统自动维护的字段
     */
    private boolean isAutoManagedColumn(String columnName) {
        if (columnName == null) return false;
        for (String col : AUTO_MANAGED_COLUMNS) {
            if (col.equals(columnName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断是否为系统内置表（不允许作为可写主表）
     *
     * 口径与 SqlIdentifierGuard / FormDesignPublishValidator 统一走 SystemTablePolicy，
     * 不再各自硬编码前缀 —— 此前三处副本都只有 system_/infra_/bpm_，漏了 sys_/act_/ai_/qrtz_。
     */
    private boolean isFrameworkTable(String tableName) {
        return SystemTablePolicy.isSystemTable(tableName);
    }

    /**
     * 检查表是否有 tenant_id 列
     *
     * 表单设计模块要求所有表必须支持多租户隔离，因此只能选择有 tenant_id 列的表。
     * 系统内置表（如 system_users、infra_config 等）通常不分租户，不应出现在可选列表里。
     */
    private boolean hasTenantIdColumn(Long dataSourceConfigId, String tableName) {
        try {
            TableInfo table = databaseTableService.getTable(dataSourceConfigId, tableName);
            if (table == null || table.getFields() == null) {
                return false;
            }
            return table.getFields().stream()
                    .anyMatch(field -> "tenant_id".equals(field.getName()));
        } catch (Exception e) {
            // 查表失败时返回 false，该表不会出现在可选列表里
            return false;
        }
    }

    /**
     * TableField 转 VO
     */
    private FormDesignDbColumnRespVO convertColumn(TableField field) {
        FormDesignDbColumnRespVO vo = new FormDesignDbColumnRespVO();
        vo.setColumnName(field.getName());
        vo.setColumnComment(field.getComment());
        vo.setJdbcType(resolveColumnJdbcTypeName(field));
        vo.setJavaType(field.getColumnType() != null ? field.getColumnType().getType() : null);
        vo.setPrimaryKey(field.isKeyFlag());
        vo.setNullable(field.getMetaInfo() != null && field.getMetaInfo().isNullable());
        return vo;
    }

    /**
     * 一列的类型名，给前端原样回填进 {@code tableBinding.fieldMapping.jdbcType}
     *
     * <p>🔴 两个候选名里挑契约认得的那个，理由与后果都写在
     * {@link WidgetContractRegistry#resolveColumnTypeName}：一句话是 MySQL 的 json 列
     * 在 {@code JdbcType} 枚举里叫 {@code LONGVARCHAR}，与 text 列同名，
     * 「这列是 json」只有驱动报的类型名知道 —— 而下游要靠它决定多选存 JSON 串还是拼逗号串。
     */
    private String resolveColumnJdbcTypeName(TableField field) {
        if (field.getMetaInfo() == null) {
            return null;
        }
        return widgetContractRegistry.resolveColumnTypeName(
                field.getMetaInfo().getTypeName(),
                field.getMetaInfo().getJdbcType() != null
                        ? field.getMetaInfo().getJdbcType().name() : null);
    }

}
