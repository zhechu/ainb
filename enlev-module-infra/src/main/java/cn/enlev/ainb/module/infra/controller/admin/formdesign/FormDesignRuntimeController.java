package cn.enlev.ainb.module.infra.controller.admin.formdesign;

import cn.enlev.ainb.framework.common.pojo.CommonResult;
import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.framework.security.core.service.SecurityFrameworkService;
import cn.enlev.ainb.framework.common.util.object.BeanUtils;
import cn.enlev.ainb.module.infra.controller.admin.formdesign.vo.runtime.FormDataChangeLogRespVO;
import cn.enlev.ainb.module.infra.controller.admin.formdesign.vo.runtime.FormDataPageReqVO;
import cn.enlev.ainb.module.infra.controller.admin.formdesign.vo.runtime.FormExportReqVO;
import cn.enlev.ainb.module.infra.controller.admin.formdesign.vo.runtime.FormImportPreviewReqVO;
import cn.enlev.ainb.module.infra.controller.admin.formdesign.vo.runtime.FormImportPreviewRespVO;
import cn.enlev.ainb.module.infra.controller.admin.formdesign.vo.runtime.FormImportReqVO;
import cn.enlev.ainb.module.infra.controller.admin.formdesign.vo.runtime.FormImportRespVO;
import cn.enlev.ainb.module.infra.controller.admin.formdesign.vo.runtime.FormRuntimeConfigRespVO;
import cn.enlev.ainb.module.infra.framework.formdesign.core.export.FormExportOptions;
import cn.enlev.ainb.module.infra.framework.formdesign.core.sql.FormDesignSqlBuilder;
import cn.enlev.ainb.module.infra.service.formdesign.runtime.FormDesignRuntimeService;
import cn.enlev.ainb.module.infra.service.formdesign.runtime.FormImportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.multipart.MultipartFile;
import java.io.InputStream;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.framework.common.pojo.CommonResult.success;
import static cn.enlev.ainb.module.infra.enums.ErrorCodeConstants.FORM_DESIGN_IMPORT_FILE_INVALID;
import static cn.enlev.ainb.module.infra.enums.ErrorCodeConstants.FORM_DESIGN_RUNTIME_EXECUTE_FAIL;

/**
 * 表单设计运行时 Controller
 *
 * 已发布表单的数据 CRUD 入口。前端渲染页通过 code 调用，
 * 后端按发布时的 tableBinding 配置动态生成 SQL。
 *
 * 权限设计：本模块的运行时权限是「按表单」的，权限串形如
 * infra:form-design-run:{code}:{action}，发布时随菜单一起创建。
 * 由于权限串含动态的 code，无法用 @PreAuthorize 的静态字符串表达，
 * 因此改为在方法内用 SecurityFrameworkService 编程式校验。
 *
 * 注意：细粒度的列级权限与行级数据权限第一期不做（已与需求方确认），
 * 但运行时接口本身【必须】校验菜单权限 —— 否则任何登录用户都能读写任意绑定表。
 *
 * @author 走进新时代
 */
@Tag(name = "管理后台 - 表单设计运行时")
@RestController
@RequestMapping("/infra/form-design/runtime")
@Validated
public class FormDesignRuntimeController {

    @Resource
    private FormDesignRuntimeService runtimeService;
    @Resource
    private FormImportService importService;
    @Resource
    private SecurityFrameworkService securityFrameworkService;

    @GetMapping("/{code}/config")
    @Operation(summary = "获得运行时渲染配置", description = "含 form-create 配置与列表配置，不含物理表结构")
    @Parameter(name = "code", description = "表单编码", required = true, example = "customer")
    public CommonResult<FormRuntimeConfigRespVO> getConfig(@PathVariable("code") String code) {
        checkPermission(code, "query");
        return success(runtimeService.getRenderConfig(code));
    }

    @GetMapping("/{code}/check-unique")
    @Operation(summary = "校验字段值是否可用",
            description = "给前端失焦即时校验用。与提交时的唯一性校验查同一条 SQL —— "
                    + "否则会出现「输入时说可以、提交时说重复」。"
                    + "只对配了唯一性校验的字段生效，其余字段一律返回 true")
    @Parameter(name = "code", description = "表单编码", required = true, example = "customer")
    @Parameter(name = "field", description = "字段名", required = true, example = "mobile")
    @Parameter(name = "value", description = "待校验的值", required = true, example = "13812345678")
    @Parameter(name = "id", description = "编辑时传自己的主键以排除自身", example = "1")
    public CommonResult<Boolean> checkUnique(@PathVariable("code") String code,
                                             @RequestParam("field") String field,
                                             @RequestParam("value") String value,
                                             @RequestParam(value = "id", required = false) String id) {
        // 用 query 权限而不是 create/update：它做的是一次查询。
        // 而且新增与编辑两种场景都要用它，绑到其中任一个动作上，另一个场景就会 403
        checkPermission(code, "query");
        return success(runtimeService.checkFieldUnique(code, field, value, id));
    }

    @GetMapping("/{code}/data-logs")
    @Operation(summary = "查询一条数据的变更时间线",
            description = "只有表单启用了变更日志才会有记录。未启用时返回空列表而不是报错 —— "
                    + "前端据此决定要不要展示时间线入口")
    @Parameter(name = "code", description = "表单编码", required = true, example = "customer")
    @Parameter(name = "id", description = "数据主键值", required = true, example = "1")
    @Parameter(name = "limit", description = "最多返回多少条", example = "50")
    public CommonResult<List<FormDataChangeLogRespVO>> getDataChangeLogs(
            @PathVariable("code") String code,
            @RequestParam("id") String id,
            @RequestParam(value = "limit", defaultValue = "50") Integer limit) {
        // 用 query 权限：能看这张表单的数据，才能看它的变更历史。
        // 绑到 update 上的话，只有编辑权限的人才能看历史，而看历史本身是只读的
        checkPermission(code, "query");
        // limit 收在 1..200：前端传 0 会得到空列表（看起来像没有日志），
        // 传一个很大的数会把整段历史拉进内存
        int size = limit == null ? 50 : Math.min(Math.max(limit, 1), 200);
        return success(BeanUtils.toBean(
                runtimeService.getDataChangeLogs(code, id, size), FormDataChangeLogRespVO.class));
    }

    @PostMapping("/{code}/create")
    @Operation(summary = "新增表单数据")    @Parameter(name = "code", description = "表单编码", required = true, example = "customer")
    public CommonResult<Object> create(@PathVariable("code") String code,
                                       @RequestBody Map<String, Object> data) {
        checkPermission(code, "create");
        return success(runtimeService.create(code, data));
    }

    @PutMapping("/{code}/update")
    @Operation(summary = "修改表单数据")
    @Parameter(name = "code", description = "表单编码", required = true, example = "customer")
    @Parameter(name = "id", description = "主键值", required = true, example = "1")
    public CommonResult<Boolean> update(@PathVariable("code") String code,
                                        @RequestParam("id") String id,
                                        @RequestBody Map<String, Object> data) {
        checkPermission(code, "update");
        runtimeService.update(code, id, data);
        return success(true);
    }

    @DeleteMapping("/{code}/delete")
    @Operation(summary = "删除表单数据")
    @Parameter(name = "code", description = "表单编码", required = true, example = "customer")
    @Parameter(name = "id", description = "主键值", required = true, example = "1")
    public CommonResult<Boolean> delete(@PathVariable("code") String code,
                                        @RequestParam("id") String id) {
        checkPermission(code, "delete");
        runtimeService.delete(code, id);
        return success(true);
    }

    @PostMapping("/{code}/delete-batch")
    @Operation(summary = "批量删除表单数据")
    @Parameter(name = "code", description = "表单编码", required = true, example = "customer")
    public CommonResult<Boolean> deleteBatch(@PathVariable("code") String code,
                                             @RequestBody List<Object> ids) {
        // 权限与单条删除同一个：批量删除不是一件比单删更强的事，
        // 另立一个权限点只会让「能删一条却不能删两条」这种配置成为可能
        checkPermission(code, "delete");
        runtimeService.deleteBatch(code, ids);
        return success(true);
    }

    @GetMapping("/{code}/get")
    @Operation(summary = "获得表单数据详情", description = "含子表明细")
    @Parameter(name = "code", description = "表单编码", required = true, example = "customer")
    @Parameter(name = "id", description = "主键值", required = true, example = "1")
    public CommonResult<Map<String, Object>> get(@PathVariable("code") String code,
                                                  @RequestParam("id") String id) {
        checkPermission(code, "query");
        return success(runtimeService.get(code, id));
    }

    @GetMapping("/{code}/plain-field")
    @Operation(summary = "查看脱敏字段的明文",
            description = "详情接口对脱敏字段返回掩码，用户改不了也核对不了原值。"
                    + "本接口一次只还原【一条数据的一个字段】，"
                    + "且只对确实配了脱敏的字段放行 —— 否则它就成了任意行任意列的明文读取口")
    @Parameter(name = "code", description = "表单编码", required = true, example = "customer")
    @Parameter(name = "id", description = "主键值", required = true, example = "1")
    @Parameter(name = "field", description = "字段名，必须是配了脱敏的字段", required = true, example = "mobile")
    public CommonResult<Object> getPlainField(@PathVariable("code") String code,
                                              @RequestParam("id") String id,
                                              @RequestParam("field") String field) {
        // ⚠️ 两道门，缺一不可，而且顺序是有意的：
        //
        // 1) update —— 列表页只要 query 就能看（看到的是掩码），而看明文是为了修改，
        //    门槛应当与「能改这条数据」对齐；
        // 2) view-plain —— 单独的授权动作。只有 update 的话，「能改」与「能看明文」
        //    被焊死成一件事，而真实组织里它们往往不是同一批人
        //    （客服能改联系方式，明文号码只给风控主管）。
        //
        // 第 2 道是纯收紧：它不给任何人新增能力，只把原来隐含在 update 里的能力拆出来。
        // 超管走 *:*:* 通配，两道都自动通过。
        //
        // 🔴 view-plain 是 BUSINESS_VERB 档、不塌缩，所以「资源行不存在」在这里
        //    仍然等于拒绝（不像塌缩后的串会回落到身份行的 default_effect）。
        //    本次改动之前首次发布的表单没有这一行，它们的「查看明文」会被前端隐藏 ——
        //    这是有意的 fail-closed，不是回归。详见 FormDesignServiceImpl#buildMenuConfig
        checkPermission(code, "update");
        checkPermission(code, "view-plain");
        return success(runtimeService.getPlainFieldValue(code, id, field));
    }

    @PostMapping("/{code}/page")
    @Operation(summary = "分页查询表单数据")
    @Parameter(name = "code", description = "表单编码", required = true, example = "customer")
    public CommonResult<PageResult<Map<String, Object>>> page(@PathVariable("code") String code,
                                                              @Valid @RequestBody FormDataPageReqVO reqVO) {
        checkPermission(code, "query");
        List<FormDesignSqlBuilder.QueryCondition> conditions = convertConditions(reqVO);
        PageResult<Map<String, Object>> result = runtimeService.page(code, conditions,
                reqVO.getSortField(), reqVO.getSortOrder(), reqVO.getPageNo(), reqVO.getPageSize());
        return success(result);
    }

    @GetMapping("/{code}/distinct-values")
    @Operation(summary = "查询某字段的去重值",
            description = "供列表页左侧树筛选用。字段必须在表绑定的字段映射里声明过，否则拒绝")
    @Parameter(name = "code", description = "表单编码", required = true, example = "customer")
    @Parameter(name = "field", description = "form-create 字段名", required = true, example = "customerLevel")
    public CommonResult<List<Object>> distinctValues(@PathVariable("code") String code,
                                                     @RequestParam("field") String field) {
        // 与列表同一个权限 —— 它读的是列表数据的一个投影，不该更宽松
        checkPermission(code, "query");
        return success(runtimeService.distinctValues(code, field));
    }

    @PostMapping("/{code}/export")
    @Operation(summary = "导出表单数据", description = "按当前查询条件导出 Excel，性能优化：流式写入，不全量加载到内存")
    @Parameter(name = "code", description = "表单编码", required = true, example = "customer")
    public void export(@PathVariable("code") String code,
                       @RequestBody FormExportReqVO reqVO,
                       HttpServletResponse response) throws IOException {
        checkPermission(code, "export");

        List<FormDesignSqlBuilder.QueryCondition> conditions =
                toQueryConditions(reqVO.getConditions());
        // ⚠️ 带上 visibleFields —— 用户在列设置里隐藏的列不该被导出。
        //    不传的话「所见」与「所得」对不上，而用户隐藏某一列往往正是因为不想要它
        FormExportOptions options = new FormExportOptions();
        options.setScope(FormExportOptions.parseScope(reqVO.getScope()));
        options.setPageNo(reqVO.getPageNo());
        options.setPageSize(reqVO.getPageSize());
        options.setIncludeSummary(reqVO.getIncludeSummary());
        options.setVisibleFields(reqVO.getVisibleFields());
        options.setSortField(reqVO.getSortField());
        options.setSortOrder(reqVO.getSortOrder());
        runtimeService.exportExcel(code, conditions, options, response);
    }

    @GetMapping("/{code}/reference-options")
    @Operation(summary = "关联表单控件的候选数据",
            description = "给关联控件选记录用的弱查询。与 page 的区别不只是字段少，"
                    + "而是**另一套权限语义**：校验目标表单的 reference 或 query，"
                    + "为的是让「能填订单但不该浏览客户列表」的人也选得到客户。"
                    + "⚠️ 目标表单与可见字段都从源表单的已发布配置里读，不接受调用方传")
    @Parameter(name = "code", description = "正在填写的表单编码（引用方）", required = true, example = "order")
    @Parameter(name = "field", description = "该表单上关联控件的字段名", required = true, example = "customer_id")
    @Parameter(name = "keyword", description = "按显示字段模糊搜，可空")
    public CommonResult<List<Map<String, Object>>> referenceOptions(
            @PathVariable("code") String code,
            @RequestParam("field") String field,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "limit", required = false) Integer limit) {
        // ⚠️ 两道权限，缺一不可，且顺序有讲究：
        //
        // 1. 源表单的 query —— 不校验的话，任何人都能拿【别人那张表单】的关联控件配置
        //    当模板来读目标表单。同一张目标表单，不同引用方声明的带出字段并不一样，
        //    挑一个带出字段最多的源表单就能多读出几列
        // 2. 目标表单的 reference 或 query —— 这才是数据本身的那道门
        checkPermission(code, "query");
        checkReferencePermission(runtimeService.resolveReferenceTargetCode(code, field));
        return success(runtimeService.referenceOptions(code, field, keyword, limit));
    }

    @PostMapping("/{code}/import/preview")
    @Operation(summary = "预览表格导入",
            description = "解析上传的 Excel 并逐行校验，返回表头映射、逐行状态与统计，**不写库**。"
                    + "返回的 importToken 在执行时必须原样带回，且只能用一次。"
                    + "⚠️ 权限用 import 而不是 query：预览会把整份文件的内容回显在响应里，"
                    + "只读权限的人不该借此读到任意数据")
    @Parameter(name = "code", description = "表单编码", required = true, example = "supplier")
    public CommonResult<FormImportPreviewRespVO> importPreview(
            @PathVariable("code") String code,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "updateKeyFields", required = false)
            List<String> updateKeyFields) throws IOException {
        checkPermission(code, "import");
        if (file == null || file.isEmpty()) {
            throw exception(FORM_DESIGN_IMPORT_FILE_INVALID, "没有收到文件");
        }
        // ⚠️ 文件名后缀不在这里判：xls 与 xlsx 都要支持，而有些浏览器上传时
        //    带上的是 application/octet-stream。真正的判据是 FastExcel 能不能解析 ——
        //    它解析不了会走到下面那个 catch，报出来的话比「后缀名不对」更准
        FormImportPreviewReqVO reqVO = new FormImportPreviewReqVO();
        reqVO.setUpdateKeyFields(updateKeyFields);
        try (InputStream input = file.getInputStream()) {
            return success(importService.preview(code, input, reqVO));
        } catch (IOException e) {
            // 解析失败要给出「文件读不了」这个信息 —— 只抛一个英文的
            // POI 异常，用户不知道是自己文件的问题还是系统的问题
            throw exception(FORM_DESIGN_IMPORT_FILE_INVALID, e.getMessage());
        }
    }

    @PostMapping("/{code}/import/execute")
    @Operation(summary = "执行表格导入",
            description = "按预览结果逐条写入。逐条一个事务 —— 一条失败不影响其余。"
                    + "写库走的是运行时的 create/update，因此手工录入能拦住的，导入也一定拦得住")
    @Parameter(name = "code", description = "表单编码", required = true, example = "supplier")
    public CommonResult<FormImportRespVO> importExecute(@PathVariable("code") String code,
                                                        @RequestBody FormImportReqVO reqVO) {
        checkPermission(code, "import");
        return success(importService.execute(code, reqVO));
    }

    @GetMapping("/{code}/import/template")
    @Operation(summary = "下载导入模板",
            description = "按当前表单的字段定义动态生成 —— 表头就是字段标题，"
                    + "字典列带下拉，第二行是填写说明。不是一份通用的静态文件")
    @Parameter(name = "code", description = "表单编码", required = true, example = "supplier")
    public void importTemplate(@PathVariable("code") String code,
                               HttpServletResponse response) throws IOException {
        checkPermission(code, "import");
        importService.downloadTemplate(code, response);
    }

    /**
     * 把前端传来的查询条件转成 SQL 构造器的条件
     *
     * 抽出来是因为导出与导入判重两处都要用，各写一遍迟早会漂移
     */
    private List<FormDesignSqlBuilder.QueryCondition> toQueryConditions(
            List<FormDataPageReqVO.Condition> items) {
        List<FormDesignSqlBuilder.QueryCondition> conditions = new ArrayList<>();
        if (items == null) {
            return conditions;
        }
        for (FormDataPageReqVO.Condition item : items) {
            if (item == null) {
                continue;
            }
            FormDesignSqlBuilder.QueryCondition condition = new FormDesignSqlBuilder.QueryCondition();
            condition.setField(item.getField());
            condition.setOperator(item.getOperator());
            condition.setValue(item.getValue());
            condition.setValue2(item.getValue2());
            conditions.add(condition);
        }
        return conditions;
    }

    @PostMapping("/{code}/invoke-button")
    @Operation(summary = "执行自定义按钮的「调数据接口」动作",
            description = "⚠️ 只接受【按钮标识】，不接受接口编码 —— 接口编码由服务端从已发布的表单配置里读出来。"
                    + "允许写型接口（作废单据这类动作正是它存在的理由），这一点与 /data-interface/options 不同。"
                    + "⚠️ 鉴权是【临时状态】：沿用表单自己的运行时 update 权限，"
                    + "没有针对数据接口的独立权限模型，见交接文档第十五节与台账第 8 项")
    @Parameter(name = "code", description = "表单编码", required = true, example = "customer")
    @Parameter(name = "buttonKey", description = "按钮标识", required = true, example = "voidOrder")
    @Parameter(name = "id", description = "当前记录主键。工具栏按钮没有记录，可不传")
    public CommonResult<Object> invokeButton(@PathVariable("code") String code,
                                             @RequestParam("buttonKey") String buttonKey,
                                             @RequestParam(value = "id", required = false) String id) {
        // 用 update 而不是新造一个 action：新造的话发布时要跟着建权限点，
        // 而那批权限点已经随菜单建好了。⚠️ 代价是「能改这张表单的人」等于
        // 「能点这张表单上任何一个接口按钮的人」，粒度就到这儿 —— 临时状态
        checkPermission(code, "update");
        return success(runtimeService.invokeButtonInterface(code, buttonKey, id));
    }

    /**
     * 校验当前用户是否有该表单的对应操作权限
     *
     * 权限串在发布时随菜单一并创建，格式固定为 infra:form-design-run:{code}:{action}。
     */
    private void checkPermission(String code, String action) {
        String permission = String.format("infra:form-design-run:%s:%s", code, action);
        if (!securityFrameworkService.hasPermission(permission)) {
            throw exception(FORM_DESIGN_RUNTIME_EXECUTE_FAIL, "没有权限：" + permission);
        }
    }

    /**
     * 关联取数的权限：目标表单的 {@code reference} 或 {@code query}，有其一即可
     *
     * <h3>为什么是「或」而不是只认 reference</h3>
     *
     * {@code GrantDecisionService.hasPermission} 对<b>资源行不存在</b>直接返回 false。
     *
     * <p>⚠️ 上一句描述的是<b>权限资源塌缩之前</b>的语义，现在已不成立：
     * 塌缩开启后 {@code GrantDecisionService#lookupOf} 会把
     * {@code infra:form-design-run:{code}:{action}} 翻译成（身份行, 动作）去查，
     * 于是「动词行不存在」不再等于拒绝 —— 身份行是 DATASET 时
     * {@code default_effect} 出厂为 ALLOW，链上又没有授权就会<b>回落成放行</b>。
     * 本方法下面那个「取或」的论证是在旧语义下做的，重读时别把这句当前提。
     * 现状与实测口径以 {@code GrantDecisionService#lookupOf} 里那段 ⚠️ 注释为准。
     * 只认 reference 的话，所有<b>本次改动之前发布的表单</b>都没有这一行资源，
     * 于是它们的关联控件会在升级的那一刻集体变空 —— 而 S8 之前还没有授权界面，
     * 管理员连补配的地方都没有。那不是「收紧权限」，那是功能下线。
     *
     * 取「或」之后三种人各得其所：
     * <ul>
     *   <li>原本有 query 的人 —— 行为与升级前完全一致，无感</li>
     *   <li>被单独授予 reference 的人 —— 拿到了本动作存在的意义：
     *       能选客户，但不能浏览客户列表、不能导出</li>
     *   <li>两者都没有的人 —— 照旧拒绝</li>
     * </ul>
     *
     * 这也与 {@code GrantActionEnum} 里 PRINT 的既定做法一致
     * （「默认跟随 VIEW（向后兼容），需要单独收紧时才配 DENY」）。
     *
     * ⚠️ 因此 <b>reference 目前只能放开、不能收紧</b>：给某人配了
     * {@code reference} 的 DENY，他若还有 query 就仍然选得到。
     * 要表达「能查列表但不许被别处引用」得先把 query 收掉。
     * 这个限制是「不破坏存量」换来的，记在这里以免以后被当成缺陷。
     */
    private void checkReferencePermission(String targetCode) {
        String reference = String.format("infra:form-design-run:%s:reference", targetCode);
        String query = String.format("infra:form-design-run:%s:query", targetCode);
        if (securityFrameworkService.hasPermission(reference)
                || securityFrameworkService.hasPermission(query)) {
            return;
        }
        throw exception(FORM_DESIGN_RUNTIME_EXECUTE_FAIL, "没有权限：" + reference);
    }

    /**
     * VO 的查询条件转成 SqlBuilder 认识的结构
     */
    private List<FormDesignSqlBuilder.QueryCondition> convertConditions(FormDataPageReqVO reqVO) {
        List<FormDesignSqlBuilder.QueryCondition> conditions = new ArrayList<>();
        if (reqVO.getConditions() == null) {
            return conditions;
        }
        for (FormDataPageReqVO.Condition item : reqVO.getConditions()) {
            if (item == null) {
                continue;
            }
            FormDesignSqlBuilder.QueryCondition condition = new FormDesignSqlBuilder.QueryCondition();
            condition.setField(item.getField());
            condition.setOperator(item.getOperator());
            condition.setValue(item.getValue());
            condition.setValue2(item.getValue2());
            conditions.add(condition);
        }
        return conditions;
    }

}
