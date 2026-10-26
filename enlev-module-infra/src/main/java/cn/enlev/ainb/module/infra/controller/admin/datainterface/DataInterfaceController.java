package cn.enlev.ainb.module.infra.controller.admin.datainterface;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.common.pojo.CommonResult;
import cn.enlev.ainb.framework.common.pojo.PageParam;
import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.framework.common.util.json.JsonUtils;
import cn.enlev.ainb.framework.common.util.object.BeanUtils;
import cn.enlev.ainb.module.infra.controller.admin.datainterface.vo.*;
import cn.enlev.ainb.module.infra.dal.dataobject.datainterface.DataInterfaceDO;
import cn.enlev.ainb.module.infra.dal.dataobject.datainterface.DataInterfaceLogDO;
import cn.enlev.ainb.module.infra.dal.dataobject.datainterface.DataInterfaceVersionDO;
import cn.enlev.ainb.module.infra.enums.datainterface.DataInterfaceTypeEnum;
import cn.enlev.ainb.module.infra.enums.datainterface.SqlStatementTypeEnum;
import cn.enlev.ainb.module.infra.framework.datainterface.core.param.ParamDefinition;
import cn.enlev.ainb.module.infra.service.datainterface.DataInterfaceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static cn.enlev.ainb.framework.common.pojo.CommonResult.success;

/**
 * 接口管理 Controller
 *
 * 权限设计：
 * - 普通配置用 infra:data-interface:create / update
 * - 配置含写操作的 SQL 额外需要 infra:data-interface:write-sql（在 Service 层校验）
 *
 * <p>🔴 菜单名叫「接口管理」（原名「数据接口」），代码、实体与权限串仍叫「数据接口」。
 * 分歧是刻意的，理由见 {@code DataModelDataController} 头上那段：
 * 只统一用户看得见的地方，不动 {@code infra:data-interface:*} 权限串
 *
 * @author 走进新时代
 */
@Tag(name = "管理后台 - 接口管理")
@RestController
@RequestMapping("/infra/data-interface")
@Validated
public class DataInterfaceController {

    @Resource
    private DataInterfaceService dataInterfaceService;

    // ==================== 配置管理 ====================

    @PostMapping("/create")
    @Operation(summary = "创建数据接口")
    @PreAuthorize("@ss.hasPermission('infra:data-interface:create')")
    public CommonResult<Long> createDataInterface(@Valid @RequestBody DataInterfaceSaveReqVO reqVO) {
        return success(dataInterfaceService.createDataInterface(reqVO));
    }

    @PutMapping("/update")
    @Operation(summary = "更新数据接口", description = "更新前会自动存一个版本快照")
    @PreAuthorize("@ss.hasPermission('infra:data-interface:update')")
    public CommonResult<Boolean> updateDataInterface(@Valid @RequestBody DataInterfaceSaveReqVO reqVO) {
        dataInterfaceService.updateDataInterface(reqVO);
        return success(true);
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除数据接口")
    @Parameter(name = "id", description = "编号", required = true, example = "1")
    @PreAuthorize("@ss.hasPermission('infra:data-interface:delete')")
    public CommonResult<Boolean> deleteDataInterface(@RequestParam("id") Long id) {
        dataInterfaceService.deleteDataInterface(id);
        return success(true);
    }

    @PutMapping("/update-status")
    @Operation(summary = "启用/停用接口")
    @Parameter(name = "id", description = "编号", required = true, example = "1")
    @Parameter(name = "status", description = "状态：0-开启 1-停用", required = true, example = "0")
    @PreAuthorize("@ss.hasPermission('infra:data-interface:update')")
    public CommonResult<Boolean> updateStatus(@RequestParam("id") Long id,
                                              @RequestParam("status") Integer status) {
        dataInterfaceService.updateStatus(id, status);
        return success(true);
    }

    @GetMapping("/get")
    @Operation(summary = "获得数据接口")
    @Parameter(name = "id", description = "编号", required = true, example = "1")
    @PreAuthorize("@ss.hasPermission('infra:data-interface:query')")
    public CommonResult<DataInterfaceRespVO> getDataInterface(@RequestParam("id") Long id) {
        DataInterfaceDO entity = dataInterfaceService.getDataInterface(id);
        return success(fillStatementTypeName(BeanUtils.toBean(entity, DataInterfaceRespVO.class)));
    }

    @GetMapping("/page")
    @Operation(summary = "获得数据接口分页")
    @PreAuthorize("@ss.hasPermission('infra:data-interface:query')")
    public CommonResult<PageResult<DataInterfaceRespVO>> getDataInterfacePage(
            @Valid DataInterfacePageReqVO pageReqVO) {
        PageResult<DataInterfaceDO> pageResult = dataInterfaceService.getDataInterfacePage(pageReqVO);
        PageResult<DataInterfaceRespVO> result = BeanUtils.toBean(pageResult, DataInterfaceRespVO.class);
        result.getList().forEach(this::fillStatementTypeName);
        return success(result);
    }

    /**
     * 填充动作类型的中文名
     *
     * 非 SQL 类型的接口没有动作类型，留空即可 —— 列表页会显示为「-」
     */
    private DataInterfaceRespVO fillStatementTypeName(DataInterfaceRespVO respVO) {
        if (respVO != null && StrUtil.isNotBlank(respVO.getSqlStatementType())) {
            respVO.setSqlStatementTypeName(
                    SqlStatementTypeEnum.typeOf(respVO.getSqlStatementType()).getName());
        }
        return respVO;
    }

    @PostMapping("/detect-sql")
    @Operation(summary = "探测 SQL 里的语句类型",
            description = "给配置界面做即时提示用：比对「用户选的动作类型」与「SQL 实际语句」是否一致。"
                    + "只解析不拦截，解析失败也返回 200")
    @PreAuthorize("@ss.hasPermission('infra:data-interface:query')")
    public CommonResult<DataInterfaceSqlDetectRespVO> detectSql(@RequestBody DataInterfaceSqlDetectReqVO reqVO) {
        return success(dataInterfaceService.detectSql(reqVO.getSql()));
    }

    // ==================== 执行 ====================

    @PostMapping("/preview")
    // 前端按钮叫「测试」，这里跟着叫测试。路径与方法名仍是 preview —— 改路径是一次
    // 纯粹为了好看而让所有调用方（前端 api、agent 工具、任何已存的书签）一起断掉的变更
    @Operation(summary = "在线测试", description = "与正式调用同一套执行逻辑，限制返回条数，并回传实际执行的 SQL")
    @Parameter(name = "id", description = "编号", required = true, example = "1")
    @PreAuthorize("@ss.hasPermission('infra:data-interface:query')")
    public CommonResult<Map<String, Object>> preview(@RequestParam("id") Long id,
                                                     @RequestBody(required = false) Map<String, Object> args) {
        return success(dataInterfaceService.preview(id, args));
    }

    @PostMapping("/invoke")
    @Operation(summary = "调用接口", description = "按接口编码调用")
    @Parameter(name = "code", description = "接口编码", required = true, example = "order_list")
    @PreAuthorize("@ss.hasPermission('infra:data-interface:invoke')")
    public CommonResult<Object> invoke(@RequestParam("code") String code,
                                       @RequestBody(required = false) Map<String, Object> args) {
        return success(dataInterfaceService.invoke(code, args));
    }

    // ==================== 运行时取数 ====================
    //
    // 服务于「表单控件的选项从数据接口来」这类运行时场景，与上面的管理端点刻意分开：
    //
    // 1. /simple-list 需要 query 权限（只有能设计表单的人才需要选接口），
    //    但只下发接口的「签名」——名称、编码、入参定义，不下发 SQL / 脚本这些实现细节。
    // 2. /options 只要求登录态。填单人不可能有数据接口的管理权限，
    //    如果取数也要 invoke 权限，表单一上线就会全是空下拉框。
    //
    // 放开权限的安全边界在于：能取到什么数据完全由接口配置者决定 —— 他在注册表里定义好了
    // SQL / URL 和参数签名。调用方只能按定义传参，改不了 SQL、改不了地址、也注入不了脚本。
    // Service 层还会强制只允许读取型接口，杜绝副作用。

    @GetMapping("/simple-list")
    @Operation(summary = "获得可用于运行时取数的接口列表",
            description = "只返回启用中的读取型接口，供表单设计器等选择数据源使用。"
                    + "要含写型接口（自定义按钮的「调数据接口」动作）传 readOnly=false")
    @Parameter(name = "readOnly", description = "是否只要读取型。默认 true")
    @PreAuthorize("@ss.hasPermission('infra:data-interface:query')")
    public CommonResult<List<DataInterfaceSimpleRespVO>> getSimpleList(
            @RequestParam(value = "readOnly", required = false, defaultValue = "true") Boolean readOnly) {
        // 两个清单都要 query 权限（只有能设计表单的人才需要选接口），且都只下发接口的
        // 「签名」—— 名称、编码、入参定义，不下发 SQL / 脚本这些实现细节。
        // ⚠️ 含写型的那份【不能】挪到只需登录态的 /options 那条路上去
        List<DataInterfaceDO> list = Boolean.FALSE.equals(readOnly)
                ? dataInterfaceService.getEnabledSimpleList()
                : dataInterfaceService.getReadOnlySimpleList();
        return success(list.stream().map(DataInterfaceController::toSimpleVO).toList());
    }

    @PostMapping("/options")
    @Operation(summary = "运行时取数", description = "供表单控件等按接口编码取选项数据，仅需登录态，只允许读取型接口")
    @Parameter(name = "code", description = "接口编码", required = true, example = "order_list")
    public CommonResult<Object> options(@RequestParam("code") String code,
                                        @RequestBody(required = false) Map<String, Object> args) {
        return success(dataInterfaceService.invokeReadOnly(code, args));
    }

    /**
     * DO 转精简 VO
     *
     * 不能用 BeanUtils.toBean：DO 的 params 是 JSON 字符串，VO 的 params 是对象列表，
     * 同名不同型，自动拷贝会直接抛转换异常。显式装配顺便起到白名单作用 ——
     * config / script 这些实现细节不会因为将来给 VO 加了同名字段就被意外下发。
     */
    private static DataInterfaceSimpleRespVO toSimpleVO(DataInterfaceDO entity) {
        DataInterfaceSimpleRespVO vo = new DataInterfaceSimpleRespVO();
        vo.setId(entity.getId());
        vo.setName(entity.getName());
        vo.setCode(entity.getCode());
        vo.setCategory(entity.getCategory());
        vo.setType(entity.getType());
        vo.setHasPage(entity.getHasPage());
        vo.setVersion(entity.getVersion());
        vo.setRemark(entity.getRemark());
        vo.setParams(parseParams(entity.getParams()));
        return vo;
    }

    private static List<ParamDefinition> parseParams(String params) {
        if (StrUtil.isBlank(params)) {
            return Collections.emptyList();
        }
        List<ParamDefinition> list = JsonUtils.parseArray(params, ParamDefinition.class);
        return list == null ? Collections.emptyList() : list;
    }

    // ==================== 版本管理 ====================

    @GetMapping("/version-page")
    @Operation(summary = "获得版本历史分页")
    @Parameter(name = "interfaceId", description = "接口编号", required = true, example = "1")
    @PreAuthorize("@ss.hasPermission('infra:data-interface:query')")
    public CommonResult<PageResult<DataInterfaceVersionRespVO>> getVersionPage(
            @RequestParam("interfaceId") Long interfaceId, @Valid PageParam pageParam) {
        PageResult<DataInterfaceVersionDO> pageResult =
                dataInterfaceService.getVersionPage(interfaceId, pageParam);
        return success(BeanUtils.toBean(pageResult, DataInterfaceVersionRespVO.class));
    }

    @GetMapping("/get-version")
    @Operation(summary = "获得指定版本的快照", description = "用于版本比对")
    @Parameter(name = "interfaceId", description = "接口编号", required = true, example = "1")
    @Parameter(name = "version", description = "版本号", required = true, example = "2")
    @PreAuthorize("@ss.hasPermission('infra:data-interface:query')")
    public CommonResult<DataInterfaceVersionRespVO> getVersion(
            @RequestParam("interfaceId") Long interfaceId,
            @RequestParam("version") Integer version) {
        DataInterfaceVersionDO entity = dataInterfaceService.getVersion(interfaceId, version);
        return success(BeanUtils.toBean(entity, DataInterfaceVersionRespVO.class));
    }

    @PutMapping("/rollback")
    @Operation(summary = "回滚到指定版本", description = "回滚本身也会产生新版本，保证可追溯")
    @Parameter(name = "interfaceId", description = "接口编号", required = true, example = "1")
    @Parameter(name = "version", description = "版本号", required = true, example = "2")
    @PreAuthorize("@ss.hasPermission('infra:data-interface:update')")
    public CommonResult<Boolean> rollbackVersion(@RequestParam("interfaceId") Long interfaceId,
                                                  @RequestParam("version") Integer version) {
        dataInterfaceService.rollbackVersion(interfaceId, version);
        return success(true);
    }

    // ==================== 调用日志 ====================

    @GetMapping("/log-page")
    @Operation(summary = "获得调用日志分页")
    @PreAuthorize("@ss.hasPermission('infra:data-interface:query')")
    public CommonResult<PageResult<DataInterfaceLogRespVO>> getLogPage(
            @Valid DataInterfaceLogPageReqVO pageReqVO) {
        PageResult<DataInterfaceLogDO> pageResult = dataInterfaceService.getLogPage(pageReqVO);
        // 只下发 userId，不拼昵称：infra 不能依赖 system 的 service/dal（跨模块只能碰 api 包），
        // 而为此走 AdminUserApi 批量查一遍，等于给每条日志都加一次跨模块调用。
        // 昵称由前端用 /system/user/simple-list 建映射表翻译（useUserLabel），
        // 一次请求覆盖整页。RespVO 上曾经挂着 userNickname / username 两个字段，
        // 因为这里从不赋值而恒为 null —— 前端模板照着它们写了条件判断，
        // 结果「调用人」列一直在显示裸 id。字段已删，免得下一个人再照着 Swagger 信一次
        return success(BeanUtils.toBean(pageResult, DataInterfaceLogRespVO.class));
    }

    // ==================== 元数据 ====================

    @GetMapping("/list-type")
    @Operation(summary = "获得接口类型选项")
    @PreAuthorize("@ss.hasPermission('infra:data-interface:query')")
    public CommonResult<List<Map<String, Object>>> getTypeList() {
        // 用 value / label 命名：前端 el-option 可直接绑定，不必再做一层字段映射
        return success(Arrays.stream(DataInterfaceTypeEnum.values())
                .map(item -> Map.<String, Object>of("value", item.getType(), "label", item.getName()))
                .toList());
    }

    @GetMapping("/list-statement-type")
    @Operation(summary = "获得 SQL 动作类型选项",
            description = "只返回用户可选的四种：查询/新增/修改/删除。DDL 已被策略禁止，OTHER 是解析兜底值，都不开放选择")
    @PreAuthorize("@ss.hasPermission('infra:data-interface:query')")
    public CommonResult<List<Map<String, Object>>> getStatementTypeList() {
        return success(SqlStatementTypeEnum.selectableValues().stream()
                .map(item -> Map.<String, Object>of(
                        "value", item.getType(),
                        "label", item.getName(),
                        "writeOperation", item.getWriteOperation()))
                .toList());
    }

}
