package cn.enlev.ainb.module.system.controller.admin.user;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.apilog.core.annotation.ApiAccessLog;
import cn.enlev.ainb.framework.common.enums.CommonStatusEnum;
import java.util.LinkedHashSet;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import cn.enlev.ainb.framework.common.pojo.CommonResult;
import cn.enlev.ainb.framework.common.pojo.PageParam;
import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.framework.excel.core.util.ExcelUtils;
import cn.enlev.ainb.module.system.controller.admin.user.vo.user.*;
import cn.enlev.ainb.module.system.convert.user.UserConvert;
import cn.enlev.ainb.module.system.dal.dataobject.dept.DeptDO;
import cn.enlev.ainb.module.system.dal.dataobject.user.AdminUserDO;
import cn.enlev.ainb.module.system.enums.common.SexEnum;
import cn.enlev.ainb.module.system.service.dept.DeptService;
import cn.enlev.ainb.module.system.service.user.AdminUserService;
import cn.enlev.ainb.module.system.service.user.UserImportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
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
import java.util.Collections;
import java.util.List;
import java.util.Map;

import cn.enlev.ainb.framework.datapermission.core.field.PermissionFieldCrop;
import cn.enlev.ainb.framework.datapermission.core.field.PermissionFieldHelper;

import static cn.enlev.ainb.framework.apilog.core.enums.OperateTypeEnum.EXPORT;
import static cn.enlev.ainb.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId;
import static cn.enlev.ainb.framework.common.pojo.CommonResult.success;
import static cn.enlev.ainb.framework.common.util.collection.CollectionUtils.convertList;
import static cn.enlev.ainb.framework.common.util.collection.CollectionUtils.convertSet;

@Tag(name = "管理后台 - 用户")
@Slf4j
@RestController
@RequestMapping("/system/user")
@Validated
public class UserController {

    /**
     * 字段级权限 helper（S7a）
     *
     * 只在切面覆盖不到的出口上手动调 —— 目前是导出（方法返回 void，
     * 数据直接写进 response 流，切面拿不到它）
     */
    @Resource
    private PermissionFieldHelper permissionFieldHelper;

    /**
     * 一次导出的行数上限
     *
     * <h3>为什么必须有</h3>
     *
     * 改造前这里用的是 {@code PageParam.PAGE_SIZE_NONE}（-1，不分页）——
     * 也就是<b>把整张用户表读进内存</b>，再逐条转成 VO，再写 Excel。
     * 用户表几万行时，这一个请求就能把堆吃掉；而它还带着部门名的批量回查。
     *
     * 2 万与表单设计运行时的导出上限对齐，两处是同一个量级的判断：
     * 导出是「全量读进内存再写出去」的操作，行数上去之后内存与响应时间都不线性。
     * 真要几十万行的场景，该用的是异步导出而不是把这个数字调大。
     */
    public static final int MAX_EXPORT_ROWS = 20000;

    /**
     * 一次导入的行数上限
     *
     * 与导出上限同一个量级：两侧都是「整批进内存」的操作。
     * 读满之后预检会在 {@code summary.truncated} 与整表级问题里明确说出来，
     * 不是静默丢掉 —— 那正是改造前的缺口。
     */
    public static final int MAX_IMPORT_ROWS = 20000;

    /**
     * 导入文件的大小上限（MB）
     *
     * 行数上限拦不住「一万行但每行塞了几十 KB 富文本」这种文件，所以还要有一道体积闸
     */
    public static final int MAX_IMPORT_FILE_SIZE_MB = 10;

    /**
     * 预检最多回多少行明细给前端
     *
     * 两万行全发过去，光 JSON 就是几十 MB，浏览器渲染那张表也会卡死。
     * 统计数字是全量算的，只有<b>明细</b>截到这个数 —— 而用户在这一屏要做的判断
     * （表头对不对、错在哪类字段上）几百行足够了
     */
    private static final int MAX_PREVIEW_ROWS = 500;

    @Resource
    private AdminUserService userService;
    @Resource
    private DeptService deptService;
    @Resource
    private UserImportService userImportService;

    @PostMapping("/create")
    @Operation(summary = "新增用户")
    @PreAuthorize("@ss.hasPermission('system:user:create')")
    public CommonResult<Long> createUser(@Valid @RequestBody UserSaveReqVO reqVO) {
        stripNonWritableUserFields(reqVO, "create");
        Long id = userService.createUser(reqVO);
        return success(id);
    }

    @PutMapping("update")
    @Operation(summary = "修改用户")
    @PreAuthorize("@ss.hasPermission('system:user:update')")
    public CommonResult<Boolean> updateUser(@Valid @RequestBody UserSaveReqVO reqVO) {
        stripNonWritableUserFields(reqVO, "update");
        userService.updateUser(reqVO);
        return success(true);
    }

    /**
     * 写路径守卫：把 HIDDEN / READONLY 的字段从提交值里剔掉
     *
     * <h3>为什么读裁了还要单独守写</h3>
     *
     * 读路径的 {@link PermissionFieldCrop} 只保证「他看不到」，
     * 而前端的 disabled 不是安全边界 —— 抓包重放照样能把 mobile 提交上来。
     * 不守写的话，一个被配成「看不到手机号」的人反而能<b>改掉</b>别人的手机号。
     *
     * <h3>⚠️ 置空为什么不会抹掉原值</h3>
     *
     * {@code stripNonWritable} 的语义是<b>置空</b>，而 {@code updateUser} 走的是
     * {@code userMapper.updateById}，MyBatis Plus 默认 {@code NOT_NULL} 策略
     * （本项目没有覆盖 {@code update-strategy}）—— null 字段根本不进 UPDATE 语句，
     * 于是「不可写」正好落成「不更新该列」，原值保留。
     *
     * 如果哪天把全局策略改成 {@code ALWAYS} / {@code IGNORED}，这里就会变成
     * <b>把别人的手机号刷成空</b>，且全程 200 无日志（TODO #23 在流程回写上踩过同一个坑）。
     * 改那个配置前必须先回来看这段。
     */
    private void stripNonWritableUserFields(UserSaveReqVO reqVO, String scene) {
        // ⚠️ 这个清单必须与读路径 @PermissionFieldCrop 的字段级范围保持一致。
        // 原来只写了 mobile/email —— 读路径扩大到 11 个字段后这里没跟上，
        // 后果是「详情里配了 READONLY，提交时照样能改」：
        // 界面上看着是只读，接口层却没有任何拦截，属于典型的「配了不生效」
        Set<String> stripped = permissionFieldHelper.stripNonWritable(
                reqVO, "system:user",
                List.of("mobile", "email", "deptName", "postIds", "sex", "avatar",
                        "status", "remark", "loginIp", "loginDate", "createTime",
                        "nickname"));
        if (!stripped.isEmpty()) {
            // 只记字段名，绝不记值 —— 把被拒的明文抄进日志是二次泄露（S4c §6 的口径）
            log.info("[stripNonWritableUserFields][{}] 用户({}) 的字段 {} 不可写，已丢弃提交值",
                    scene, getLoginUserId(), stripped);
        }
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除用户")
    @Parameter(name = "id", description = "编号", required = true, example = "1024")
    @PreAuthorize("@ss.hasPermission('system:user:delete')")
    public CommonResult<Boolean> deleteUser(@RequestParam("id") Long id) {
        userService.deleteUser(id);
        return success(true);
    }

    @DeleteMapping("/delete-list")
    @Parameter(name = "ids", description = "编号列表", required = true)
    @Operation(summary = "批量删除用户")
    @PreAuthorize("@ss.hasPermission('system:user:delete')")
    public CommonResult<Boolean> deleteUserList(@RequestParam("ids") List<Long> ids) {
        userService.deleteUserList(ids);
        return success(true);
    }

    @PutMapping("/update-password")
    @Operation(summary = "重置用户密码")
    @PreAuthorize("@ss.hasPermission('system:user:update-password')")
    public CommonResult<Boolean> updateUserPassword(@Valid @RequestBody UserUpdatePasswordReqVO reqVO) {
        userService.updateUserPassword(reqVO.getId(), reqVO.getPassword());
        return success(true);
    }

    @PutMapping("/update-status")
    @Operation(summary = "修改用户状态")
    @PreAuthorize("@ss.hasPermission('system:user:update')")
    public CommonResult<Boolean> updateUserStatus(@Valid @RequestBody UserUpdateStatusReqVO reqVO) {
        userService.updateUserStatus(reqVO.getId(), reqVO.getStatus());
        return success(true);
    }

    @GetMapping("/page")
    @Operation(summary = "获得用户分页列表")
    @PreAuthorize("@ss.hasPermission('system:user:query')")
    @PermissionFieldCrop(value = "system:user", column = true, fields = {"username", "nickname",
            "mobile", "email", "deptName", "postIds", "sex", "avatar", "status", "remark",
            "loginIp", "loginDate", "createTime"})
    public CommonResult<PageResult<UserRespVO>> getUserPage(@Valid UserPageReqVO pageReqVO) {
        // 获得用户分页列表
        PageResult<AdminUserDO> pageResult = userService.getUserPage(pageReqVO);
        if (CollUtil.isEmpty(pageResult.getList())) {
            return success(new PageResult<>(pageResult.getTotal()));
        }
        // 拼接数据
        Map<Long, DeptDO> deptMap = deptService.getDeptMap(
                convertList(pageResult.getList(), AdminUserDO::getDeptId));
        return success(new PageResult<>(UserConvert.INSTANCE.convertList(pageResult.getList(), deptMap),
                pageResult.getTotal()));
    }

    @GetMapping("/list")
    @Operation(summary = "获得用户详情列表")
    @Parameter(name = "ids", description = "编号列表", required = true, example = "[1024]")
    @PreAuthorize("@ss.hasPermission('system:user:query')")
    @PermissionFieldCrop(value = "system:user", column = true, fields = {"username", "nickname",
            "mobile", "email", "deptName", "postIds", "sex", "avatar", "status", "remark",
            "loginIp", "loginDate", "createTime"})
    public CommonResult<List<UserRespVO>> getUserList(@RequestParam("ids") List<Long> ids) {
        List<AdminUserDO> list = userService.getUserList(ids);
        if (CollUtil.isEmpty(list)) {
            return success(Collections.emptyList());
        }
        // 拼接数据
        Map<Long, DeptDO> deptMap = deptService.getDeptMap(convertSet(list, AdminUserDO::getDeptId));
        return success(UserConvert.INSTANCE.convertList(list, deptMap));
    }

    @GetMapping({"/list-all-simple", "/simple-list"})
    @Operation(summary = "获取用户精简信息列表", description = "只包含被开启的用户，主要用于前端的下拉选项")
    public CommonResult<List<UserSimpleRespVO>> getSimpleUserList(
            @RequestParam(value = "deptId", required = false) Long deptId) {
        List<AdminUserDO> list = userService.getUserListByStatus(
                CommonStatusEnum.ENABLE.getStatus(), deptId);

        // 拼接数据
        Map<Long, DeptDO> deptMap = deptService.getDeptMap(
                convertList(list, AdminUserDO::getDeptId));
        return success(UserConvert.INSTANCE.convertSimpleList(list, deptMap));
    }

    @GetMapping("/get")
    @Operation(summary = "获得用户详情")
    @Parameter(name = "id", description = "编号", required = true, example = "1024")
    @PreAuthorize("@ss.hasPermission('system:user:query')")
    @PermissionFieldCrop(value = "system:user", fields = {"nickname", "mobile", "email", "deptName",
            "postIds", "sex", "avatar", "status", "remark", "loginIp", "loginDate", "createTime"})
    public CommonResult<UserRespVO> getUser(@RequestParam("id") Long id) {
        AdminUserDO user = userService.getUser(id);
        if (user == null) {
            return success(null);
        }
        // 拼接数据
        DeptDO dept = deptService.getDept(user.getDeptId());
        return success(UserConvert.INSTANCE.convert(user, dept));
    }

    @GetMapping("/export-excel")
    @Operation(summary = "导出用户",
            description = "支持导当前页或全部。全部最多 " + MAX_EXPORT_ROWS + " 条 —— "
                    + "超了会在文件名与响应头上明确标出来，而不是静默截断")
    @PreAuthorize("@ss.hasPermission('system:user:export')")
    @ApiAccessLog(operateType = EXPORT)
    public void exportUserList(@Validated UserExportReqVO exportReqVO,
                               HttpServletResponse response) throws IOException {
        // 导当前页时用他正看着的那一页 —— 这才是「所见」。
        // 导全部时受上限约束
        boolean currentPage = exportReqVO.isCurrentPage();
        if (currentPage) {
            // 页大小也收在上限内：有人把每页设得很大时，
            // 「当前页」与「全部」就没区别了
            int pageSize = exportReqVO.getPageSize() != null && exportReqVO.getPageSize() > 0
                    ? exportReqVO.getPageSize() : 10;
            exportReqVO.setPageSize(Math.min(pageSize, MAX_EXPORT_ROWS));
        } else {
            exportReqVO.setPageNo(1);
            exportReqVO.setPageSize(MAX_EXPORT_ROWS);
        }

        PageResult<AdminUserDO> pageResult = userService.getUserPage(exportReqVO);
        List<AdminUserDO> list = pageResult.getList();

        // ⚠️ 截断要说出来，不能静默
        //
        // 静默截断的话用户以为导全了，而少掉的那部分他永远不会去找 ——
        // 这类「看起来成功了其实不完整」的结果比直接报错危险得多。
        //
        // 响应头在 ExcelUtils.write 【之前】设，所以发得出去；
        // 文件名里也标一份，因为前端多半只认自己拼的文件名（Content-Disposition
        // 随 write 写完流才设，发不出来）
        boolean truncated = !currentPage && pageResult.getTotal() != null
                && pageResult.getTotal() > MAX_EXPORT_ROWS;
        if (truncated) {
            response.setHeader("X-Export-Truncated", "true");
            response.setHeader("X-Export-Max-Rows", String.valueOf(MAX_EXPORT_ROWS));
            response.setHeader("X-Export-Total", String.valueOf(pageResult.getTotal()));
        }

        // 输出 Excel
        Map<Long, DeptDO> deptMap = deptService.getDeptMap(
                convertList(list, AdminUserDO::getDeptId));
        String fileName = "用户数据"
                + (currentPage ? "(第" + exportReqVO.getPageNo() + "页)" : "")
                + (truncated ? "(前" + MAX_EXPORT_ROWS + "条)" : "")
                + ".xlsx";
        // ⚠️ 导出必须和 page/get 裁同一份字段。
        //
        // 导出是本项目栽过的地方（TODO #12）：只裁了列表与详情，导出照样把明文写进
        // Excel —— 而且比抓包更省事，点一下按钮就拿到全量。
        // 这里走不了切面（方法返回 void，数据直接写进 response 流），所以显式调 helper
        List<UserRespVO> exportList = UserConvert.INSTANCE.convertList(list, deptMap);
        permissionFieldHelper.crop(exportList, "system:user", List.of("mobile", "email"));
        ExcelUtils.write(response, fileName, "数据", UserRespVO.class, exportList);
    }

    @GetMapping("/get-import-template")
    @Operation(summary = "获得导入用户模板")
    public void importTemplate(HttpServletResponse response) throws IOException {
        // 手动创建导出 demo
        List<UserImportExcelVO> list = Arrays.asList(
                UserImportExcelVO.builder().username("zhechu").deptId(1L).email("ling-yu-wang@qq.com").mobile("15219890524")
                        .nickname("走进新时代").status(CommonStatusEnum.ENABLE.getStatus()).sex(SexEnum.MALE.getSex()).build()
        );
        // 输出
        ExcelUtils.write(response, "用户导入模板.xls", "用户列表", UserImportExcelVO.class, list);
    }

    @GetMapping("/import-export-config")
    @Operation(summary = "获得导入导出的限制",
            description = "导出弹窗要在用户点「导出」之前就说清上限，而上限是服务端常量。"
                    + "前端硬编码的话，后端哪天调了之后界面上写的数就成了假的 —— "
                    + "而用户会照着那个数规划自己的分批")
    @PreAuthorize("@ss.hasAnyPermissions('system:user:export', 'system:user:import')")
    public CommonResult<UserImportExportConfigRespVO> getImportExportConfig() {
        return success(new UserImportExportConfigRespVO(
                MAX_EXPORT_ROWS, MAX_IMPORT_ROWS, MAX_IMPORT_FILE_SIZE_MB));
    }

    @PostMapping("/import-preview")
    @Operation(summary = "预检用户导入",
            description = "只解析与校验，不写库。返回逐行判定结果与一个 importToken，"
                    + "确认后拿它调 import-execute")
    @Parameter(name = "file", description = "Excel 文件", required = true)
    @PreAuthorize("@ss.hasPermission('system:user:import')")
    public CommonResult<UserImportPreviewRespVO> importPreview(
            @RequestParam("file") MultipartFile file) throws IOException {
        return success(userImportService.preview(file, MAX_IMPORT_ROWS, MAX_PREVIEW_ROWS));
    }

    @PostMapping("/import-execute")
    @Operation(summary = "执行用户导入",
            description = "把上一次预检的结果真正写进库。同一个 importToken 只能用一次")
    @PreAuthorize("@ss.hasPermission('system:user:import')")
    public CommonResult<UserImportResultRespVO> importExecute(
            @Valid @RequestBody UserImportExecuteReqVO reqVO) {
        return success(userImportService.execute(reqVO));
    }

    /**
     * @deprecated 改用 import-preview + import-execute 两步。
     *
     * 这个接口的问题不在实现，而在形态：用户在点「确定」的那一刻并不知道会发生什么，
     * 而写入是不可撤销的。一份 500 行的文件里混着 3 行错的，等结果出来时
     * 497 行已经进库了，他只能拿着一串用户名去猜哪几行要补。
     *
     * 保留是因为可能还有脚本或别的客户端在调它 —— 管理后台已经切走了。
     */
    @Deprecated
    @PostMapping("/import")
    @Operation(summary = "导入用户（已废弃）",
            description = "请改用 import-preview + import-execute：先看清每一行的判定结果再落库")
    @Parameters({
            @Parameter(name = "file", description = "Excel 文件", required = true),
            @Parameter(name = "updateSupport", description = "是否支持更新，默认为 false", example = "true")
    })
    @PreAuthorize("@ss.hasPermission('system:user:import')")
    public CommonResult<UserImportRespVO> importExcel(@RequestParam("file") MultipartFile file,
                                                      @RequestParam(value = "updateSupport", required = false, defaultValue = "false") Boolean updateSupport) throws Exception {
        // ⚠️ 带上行数上限
        //
        // 改造前是 read(file, Class) —— 一次性把整份文件读进内存，且不限行数。
        // 一份几十万行的 Excel（不管是误传还是恶意）就能把堆吃掉，
        // 而导入还要逐条走校验与写库，时间上也撑不住。
        //
        // ⚠️ 这条路径上「超出上限的部分被静默丢掉」的缺口仍然在：ExcelUtils.read
        // 只回一个 List，看不出它是读完的还是读一半停的。两步式那条路径没有这个问题 ——
        // UserImportSheetReader 自己知道有没有读满，会在预检结果里明说
        List<UserImportExcelVO> list = ExcelUtils.read(file, UserImportExcelVO.class, MAX_IMPORT_ROWS);
        // ⚠️ 导入是【批量写路径】，同样要守。漏掉它等于把 create/update 上的守卫绕过去：
        //    改不了单个用户的手机号，传个 Excel 就能批量改
        stripNonWritableImportRows(list);
        return success(userService.importUserList(list, updateSupport));
    }

    /**
     * 导入行的写路径守卫。逐行剔掉 HIDDEN / READONLY 字段的值
     *
     * ⚠️ 只在这里记一次汇总日志，不逐行记 —— 一份上万行的表会把日志刷爆。
     * 同样只记字段名不记值。
     */
    private void stripNonWritableImportRows(List<UserImportExcelVO> rows) {
        if (CollUtil.isEmpty(rows)) {
            return;
        }
        Set<String> stripped = new LinkedHashSet<>();
        for (UserImportExcelVO row : rows) {
            stripped.addAll(permissionFieldHelper.stripNonWritable(
                    row, "system:user", List.of("mobile", "email")));
        }
        if (!stripped.isEmpty()) {
            log.info("[stripNonWritableImportRows][用户({}) 导入 {} 行，字段 {} 不可写，已丢弃提交值]",
                    getLoginUserId(), rows.size(), stripped);
        }
    }

    // ==================== 免鉴权接口（用于 IM 点头像弹名片、加好友搜索等场景） ====================

    @GetMapping("/get-simple")
    @Operation(summary = "获得用户精简信息", description = "用于点头像弹名片等场景；免鉴权")
    @Parameter(name = "id", description = "用户编号", required = true, example = "1024")
    public CommonResult<UserSimpleRespVO> getSimpleUser(@RequestParam("id") Long id) {
        AdminUserDO user = userService.getUser(id);
        if (user == null) {
            return success(null);
        }
        // 拼接数据
        DeptDO dept = user.getDeptId() != null ? deptService.getDept(user.getDeptId()) : null;
        Map<Long, DeptDO> deptMap = dept != null ? Collections.singletonMap(dept.getId(), dept) : Collections.emptyMap();
        return success(CollUtil.getFirst(UserConvert.INSTANCE.convertSimpleList(
                Collections.singletonList(user), deptMap)));
    }

    @GetMapping("/list-by-nickname")
    @Operation(summary = "按昵称模糊搜索用户精简信息", description = "用于加好友等场景；免鉴权；当前仅按昵称匹配")
    @Parameter(name = "nickname", description = "昵称关键词", required = true, example = "启凌智搭")
    public CommonResult<List<UserSimpleRespVO>> getSimpleUserListByNickname(@RequestParam("nickname") String nickname) {
        if (StrUtil.isBlank(nickname)) {
            return success(Collections.emptyList());
        }
        // 拼接数据
        List<AdminUserDO> list = userService.getUserListByNickname(nickname.trim());
        Map<Long, DeptDO> deptMap = deptService.getDeptMap(convertList(list, AdminUserDO::getDeptId));
        return success(UserConvert.INSTANCE.convertSimpleList(list, deptMap));
    }

}
