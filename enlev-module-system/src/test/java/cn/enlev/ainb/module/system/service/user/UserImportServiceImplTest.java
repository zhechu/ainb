package cn.enlev.ainb.module.system.service.user;

import cn.idev.excel.FastExcelFactory;
import cn.enlev.ainb.framework.common.biz.system.dict.DictDataCommonApi;
import cn.enlev.ainb.framework.common.biz.system.dict.dto.DictDataRespDTO;
import cn.enlev.ainb.framework.dict.core.DictFrameworkUtils;
import cn.enlev.ainb.framework.test.core.ut.BaseDbUnitTest;
import cn.enlev.ainb.module.system.controller.admin.user.vo.user.*;
import cn.enlev.ainb.module.system.dal.dataobject.dept.DeptDO;
import cn.enlev.ainb.module.system.dal.dataobject.user.AdminUserDO;
import cn.enlev.ainb.module.system.dal.mysql.user.AdminUserMapper;
import cn.enlev.ainb.module.system.service.dept.DeptService;
import jakarta.annotation.Resource;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.util.*;

import static cn.enlev.ainb.framework.test.core.util.AssertUtils.assertServiceException;
import static cn.enlev.ainb.module.system.enums.ErrorCodeConstants.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.*;

/**
 * {@link UserImportServiceImpl} 的单元测试
 *
 * 这里重点钉的是<b>预检的判定</b>与<b>执行时的过滤</b>：这两处出错都不会报错，
 * 只会让用户看到一个「看起来成功了」的结果 —— 比如选了「跳过」却把线上账号全覆盖了，
 * 或者预检说会新增 98 条、实际只进去 90 条。
 *
 * @author 芋道源码
 */
@Import(value = {UserImportServiceImpl.class, UserImportServiceImplTest.ValidatorConfig.class})
public class UserImportServiceImplTest extends BaseDbUnitTest {

    /**
     * {@link BaseDbUnitTest} 只装配 DB 相关的东西，容器里没有 Validator，
     * 而预检要拿它把 {@code UserSaveReqVO} 上的约束落到具体的列
     */
    @TestConfiguration
    static class ValidatorConfig {

        @Bean
        public Validator validator() {
            return Validation.buildDefaultValidatorFactory().getValidator();
        }
    }

    @Resource
    private UserImportServiceImpl userImportService;

    @Resource
    private AdminUserMapper userMapper;

    @MockitoBean
    private AdminUserService adminUserService;
    @MockitoBean
    private DeptService deptService;

    @BeforeEach
    public void setUp() {
        // 字典是别的模块的数据，这里 Mock 掉。要有真值，否则「用户性别」一列永远解析不掉
        DictDataCommonApi dictDataApi = Mockito.mock(DictDataCommonApi.class);
        when(dictDataApi.getDictDataList("system_user_sex")).thenReturn(Arrays.asList(
                dictData("男", "1"), dictData("女", "2")));
        when(dictDataApi.getDictDataList("common_status")).thenReturn(Arrays.asList(
                dictData("开启", "0"), dictData("关闭", "1")));
        DictFrameworkUtils.init(dictDataApi);
        DictFrameworkUtils.clearCache();

        // 部门 100 存在，别的都不存在
        when(deptService.getDeptMap(any())).thenAnswer(invocation -> {
            Collection<Long> ids = invocation.getArgument(0);
            Map<Long, DeptDO> map = new HashMap<>();
            if (ids != null && ids.contains(100L)) {
                map.put(100L, new DeptDO().setId(100L).setName("研发部"));
            }
            return map;
        });
        when(deptService.getDept(100L)).thenReturn(new DeptDO().setId(100L).setName("研发部"));
    }

    // ==================== 预检 ====================

    @Test
    @DisplayName("表头匹配：认出来的进 mappings，认不出的单独列出来而不是静默忽略")
    void testPreviewHeaderMapping() throws Exception {
        UserImportPreviewRespVO respVO = preview(
                Arrays.asList("登录名称", "用户名称", "部门编号", "内部备注"),
                Arrays.asList("zhangsan", "张三", "100", "随便写的"));

        assertEquals(3, respVO.getMappings().size());
        assertEquals("A", respVO.getMappings().get(0).getColumnLetter());
        assertEquals("username", respVO.getMappings().get(0).getField());
        assertTrue(respVO.getMappings().get(0).getRequired());

        assertEquals(1, respVO.getUnmatchedColumns().size());
        assertEquals("D", respVO.getUnmatchedColumns().get(0).getColumnLetter());
        assertEquals("内部备注", respVO.getUnmatchedColumns().get(0).getHeader());
        assertTrue(respVO.getMissingRequiredFields().isEmpty());
    }

    @Test
    @DisplayName("同一个字段被两列匹配到时，第二列忽略并说清是被哪一列占了")
    void testPreviewDuplicateColumn() throws Exception {
        UserImportPreviewRespVO respVO = preview(
                Arrays.asList("登录名称", "username"),
                Arrays.asList("zhangsan", "zhangsan"));

        assertEquals(1, respVO.getMappings().size());
        assertEquals(1, respVO.getDuplicateColumns().size());
        assertEquals("A", respVO.getDuplicateColumns().get(0).getFirstColumnLetter());
    }

    @Test
    @DisplayName("缺必填列：整表说一次，不逐行刷屏")
    void testPreviewMissingRequiredColumn() throws Exception {
        UserImportPreviewRespVO respVO = preview(
                Arrays.asList("用户名称", "部门编号"),
                Arrays.asList("张三", "100"));

        assertEquals(Collections.singletonList("登录名称"), respVO.getMissingRequiredFields());
        assertEquals(1, respVO.getTableErrors().size());
        // 缺列时不再逐行解析 —— 每行都会报同一个错，几万条一样的错误只会把真问题埋掉
        assertTrue(respVO.getRows().isEmpty());
        assertEquals(0, respVO.getSummary().getTotalRows());
    }

    @Test
    @DisplayName("四种行状态：NEW / EXISTS / DUPLICATE_IN_FILE / INVALID")
    void testPreviewRowStatus() throws Exception {
        userMapper.insert(new AdminUserDO().setUsername("exists").setNickname("已存在")
                .setPassword("x").setStatus(0));

        UserImportPreviewRespVO respVO = preview(
                Arrays.asList("登录名称", "用户名称", "部门编号"),
                Arrays.asList("newuser", "新用户", "100"),
                Arrays.asList("exists", "已存在", "100"),
                Arrays.asList("newuser", "又一个新用户", "100"),
                Arrays.asList("baduser", "部门不对", "999"));

        assertEquals(Arrays.asList("NEW", "EXISTS", "DUPLICATE_IN_FILE", "INVALID"),
                respVO.getRows().stream().map(UserImportPreviewRespVO.PreviewRow::getStatus).toList());

        UserImportPreviewRespVO.Summary summary = respVO.getSummary();
        assertEquals(4, summary.getTotalRows());
        assertEquals(1, summary.getNewCount());
        assertEquals(1, summary.getExistsCount());
        assertEquals(1, summary.getDuplicateCount());
        assertEquals(1, summary.getInvalidCount());
        // 四个数加起来要等于总行数 —— 对不上说明判定逻辑漏了分支
        assertEquals(summary.getTotalRows(), summary.getNewCount() + summary.getExistsCount()
                + summary.getDuplicateCount() + summary.getInvalidCount());
    }

    @Test
    @DisplayName("部门不存在要落到【那一列】上，用户才知道该改哪格")
    void testPreviewDeptErrorCarriesColumn() throws Exception {
        UserImportPreviewRespVO respVO = preview(
                Arrays.asList("登录名称", "用户名称", "部门编号"),
                Arrays.asList("baduser", "部门不对", "999"));

        UserImportPreviewRespVO.PreviewError error = respVO.getRows().get(0).getErrors().get(0);
        assertEquals("C", error.getColumnLetter());
        assertEquals("部门编号", error.getFieldLabel());
        assertEquals("部门不存在", error.getMessage());
        assertEquals("999", error.getRawValue());
        assertEquals(2, error.getRowNo());
    }

    @Test
    @DisplayName("字典列填中文，反查成编码；解析不掉时要把可选值一并说出来")
    void testPreviewDictColumn() throws Exception {
        UserImportPreviewRespVO respVO = preview(
                Arrays.asList("登录名称", "用户名称", "用户性别"),
                Arrays.asList("okuser", "正常", "男"),
                Arrays.asList("baduser", "不正常", "雄"));

        assertEquals("NEW", respVO.getRows().get(0).getStatus());
        // 预览里显示的是反查【之后】的结果，用户才能确认标签反查真的生效了
        assertEquals("男", respVO.getRows().get(0).getData().get("用户性别"));

        assertEquals("INVALID", respVO.getRows().get(1).getStatus());
        String message = respVO.getRows().get(1).getErrors().get(0).getMessage();
        assertTrue(message.contains("男"), message);
        assertTrue(message.contains("女"), message);
    }

    @Test
    @DisplayName("手机号被【别人】占了要拦下，被【自己】占着不算冲突")
    void testPreviewMobileOccupied() throws Exception {
        userMapper.insert(new AdminUserDO().setUsername("other").setNickname("别人")
                .setMobile("15601691300").setPassword("x").setStatus(0));
        userMapper.insert(new AdminUserDO().setUsername("self").setNickname("自己")
                .setMobile("15601701300").setPassword("x").setStatus(0));

        UserImportPreviewRespVO respVO = preview(
                Arrays.asList("登录名称", "用户名称", "手机号码"),
                Arrays.asList("newone", "新来的", "15601691300"),
                // 同一个人用自己原来的手机号，走的是「覆盖更新」，不该判成冲突 ——
                // 判成冲突的话，用户永远没法用导入去更新一个已有账号
                Arrays.asList("self", "自己", "15601701300"));

        assertEquals("INVALID", respVO.getRows().get(0).getStatus());
        assertEquals("手机号已被其他账号占用", respVO.getRows().get(0).getErrors().get(0).getMessage());
        assertEquals("EXISTS", respVO.getRows().get(1).getStatus());
    }

    @Test
    @DisplayName("没填手机号的行不该被拿去跟库里那些也没填手机号的账号比")
    void testPreviewBlankMobileIsNotAConflict() throws Exception {
        userMapper.insert(new AdminUserDO().setUsername("other").setNickname("别人")
                .setPassword("x").setStatus(0));

        UserImportPreviewRespVO respVO = preview(
                Arrays.asList("登录名称", "用户名称", "手机号码"),
                Arrays.asList("newone", "新来的", ""));

        assertEquals("NEW", respVO.getRows().get(0).getStatus());
    }

    @Test
    @DisplayName("字段格式不合规时，违规要落到对应的列，而不是糊成一条消息")
    void testPreviewConstraintMapsToColumn() throws Exception {
        UserImportPreviewRespVO respVO = preview(
                Arrays.asList("登录名称", "用户名称", "手机号码"),
                Arrays.asList("zhangsan", "张三", "123"));

        assertEquals("INVALID", respVO.getRows().get(0).getStatus());
        UserImportPreviewRespVO.PreviewError error = respVO.getRows().get(0).getErrors().get(0);
        assertEquals("mobile", error.getField());
        assertEquals("C", error.getColumnLetter());
    }

    @Test
    @DisplayName("超过行数上限要明说，而且级别是 WARN —— 前 N 行照样能导，不该摆成「请先处理」")
    void testPreviewTruncated() throws Exception {
        List<List<Object>> rows = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            rows.add(Arrays.asList("user" + i, "用户" + i));
        }
        UserImportPreviewRespVO respVO = userImportService.preview(
                excel(Arrays.asList("登录名称", "用户名称"), rows), 2, 100);

        assertTrue(respVO.getSummary().getTruncated());
        assertEquals(2, respVO.getSummary().getMaxRows());
        assertEquals(2, respVO.getSummary().getTotalRows());
        assertEquals(1, respVO.getTableErrors().size());
        assertTrue(respVO.getTableErrors().get(0).getMessage().contains("2"));
        assertEquals("WARN", respVO.getTableErrors().get(0).getLevel());
        // ⚠️ 截断不该顺带把行解析也停掉：读到的那 2 行仍要逐行判定，
        // 否则用户拿到的是一份「什么都没说」的预检
        assertEquals(2, respVO.getRows().size());
        assertEquals(2, respVO.getSummary().getNewCount());
    }

    @Test
    @DisplayName("缺必填列是阻断性的，级别要留在 ERROR")
    void testMissingRequiredColumnIsError() throws Exception {
        UserImportPreviewRespVO respVO = preview(
                Arrays.asList("用户名称"), Arrays.asList("张三"));

        assertEquals(1, respVO.getTableErrors().size());
        assertEquals("ERROR", respVO.getTableErrors().get(0).getLevel());
    }

    @Test
    @DisplayName("明细只回前 N 行，但统计仍然是全量的")
    void testPreviewLimitsDetailRowsNotSummary() throws Exception {
        List<List<Object>> rows = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            rows.add(Arrays.asList("user" + i, "用户" + i));
        }
        UserImportPreviewRespVO respVO = userImportService.preview(
                excel(Arrays.asList("登录名称", "用户名称"), rows), 1000, 3);

        assertEquals(3, respVO.getRows().size());
        assertEquals(3, respVO.getSummary().getPreviewRowCount());
        assertEquals(10, respVO.getSummary().getTotalRows());
        assertEquals(10, respVO.getSummary().getNewCount());
    }

    @Test
    @DisplayName("表头都读不到时直接报错，而不是回一份「零行数据」让用户以为文件是空的")
    void testPreviewNoHeader() {
        assertServiceException(
                () -> userImportService.preview(
                        new MockMultipartFile("file", "empty.xlsx", null, new byte[0]), 100, 100),
                USER_IMPORT_HEADER_MISSING);
    }

    // ==================== 执行 ====================

    @Test
    @DisplayName("token 过期 / 用过了都要拦住 —— 手抖点两下确认不该导两遍")
    void testExecuteTokenGuard() throws Exception {
        stubImportUserList();
        UserImportPreviewRespVO preview = preview(
                Arrays.asList("登录名称", "用户名称"), Arrays.asList("zhangsan", "张三"));

        assertServiceException(() -> userImportService.execute(
                new UserImportExecuteReqVO().setImportToken("not-exists")), USER_IMPORT_TOKEN_EXPIRED);

        userImportService.execute(new UserImportExecuteReqVO().setImportToken(preview.getImportToken()));
        assertServiceException(() -> userImportService.execute(
                        new UserImportExecuteReqVO().setImportToken(preview.getImportToken())),
                USER_IMPORT_TOKEN_CONSUMED);
    }

    @Test
    @DisplayName("策略 SKIP：库里已有的算跳过，不送去写库，也不算失败")
    void testExecuteSkipStrategy() throws Exception {
        userMapper.insert(new AdminUserDO().setUsername("exists").setNickname("已存在")
                .setPassword("x").setStatus(0));
        stubImportUserList();
        UserImportPreviewRespVO preview = preview(
                Arrays.asList("登录名称", "用户名称"),
                Arrays.asList("newuser", "新用户"),
                Arrays.asList("exists", "已存在"));

        UserImportResultRespVO result = userImportService.execute(new UserImportExecuteReqVO()
                .setImportToken(preview.getImportToken()).setDuplicateStrategy("SKIP"));

        assertEquals(1, result.getSkipCount());
        assertEquals(0, result.getFailCount());
        // ⚠️ 只有 newuser 被送进去。上游是把已存在的也送进去再收一条
        // 「用户账号已经存在」，于是用户选了「跳过」却看到一屏红色
        verify(adminUserService).importUserList(
                argThat(list -> list.size() == 1 && "newuser".equals(list.get(0).getUsername())),
                eq(false));
    }

    @Test
    @DisplayName("策略 UPDATE：库里已有的照样送进去，并且带上 updateSupport=true")
    void testExecuteUpdateStrategy() throws Exception {
        userMapper.insert(new AdminUserDO().setUsername("exists").setNickname("已存在")
                .setPassword("x").setStatus(0));
        stubImportUserList();
        UserImportPreviewRespVO preview = preview(
                Arrays.asList("登录名称", "用户名称"),
                Arrays.asList("newuser", "新用户"),
                Arrays.asList("exists", "已存在"));

        userImportService.execute(new UserImportExecuteReqVO()
                .setImportToken(preview.getImportToken()).setDuplicateStrategy("UPDATE"));

        verify(adminUserService).importUserList(argThat(list -> list.size() == 2), eq(true));
    }

    @Test
    @DisplayName("用户在预检表里剔掉的行不导入")
    void testExecuteRemovedRows() throws Exception {
        stubImportUserList();
        UserImportPreviewRespVO preview = preview(
                Arrays.asList("登录名称", "用户名称"),
                Arrays.asList("keep", "留下"),
                Arrays.asList("drop", "剔掉"));

        UserImportResultRespVO result = userImportService.execute(new UserImportExecuteReqVO()
                .setImportToken(preview.getImportToken()).setRemovedRows(Collections.singletonList(3)));

        assertEquals(1, result.getSkipCount());
        verify(adminUserService).importUserList(
                argThat(list -> list.size() == 1 && "keep".equals(list.get(0).getUsername())),
                anyBoolean());
    }

    @Test
    @DisplayName("预检就不合格的行不送去写库，直接算失败并带上行号")
    void testExecuteInvalidRowsBecomeFailures() throws Exception {
        stubImportUserList();
        UserImportPreviewRespVO preview = preview(
                Arrays.asList("登录名称", "用户名称", "部门编号"),
                Arrays.asList("gooduser", "正常", "100"),
                Arrays.asList("baduser", "部门不对", "999"));

        UserImportResultRespVO result = userImportService.execute(new UserImportExecuteReqVO()
                .setImportToken(preview.getImportToken()));

        assertEquals(1, result.getFailCount());
        assertEquals(3, result.getFailures().get(0).getRowNo());
        assertEquals("baduser", result.getFailures().get(0).getUsername());
        assertEquals("部门不存在", result.getFailures().get(0).getMessage());
        verify(adminUserService).importUserList(argThat(list -> list.size() == 1), anyBoolean());
    }

    @Test
    @DisplayName("「全对才导」：还剩不合格的行就一条都不写")
    void testExecuteAllOrNothing() throws Exception {
        stubImportUserList();
        UserImportPreviewRespVO preview = preview(
                Arrays.asList("登录名称", "用户名称", "部门编号"),
                Arrays.asList("gooduser", "正常", "100"),
                Arrays.asList("baduser", "部门不对", "999"));

        UserImportResultRespVO result = userImportService.execute(new UserImportExecuteReqVO()
                .setImportToken(preview.getImportToken()).setAllOrNothing(true));

        assertEquals(0, result.getCreateCount());
        assertEquals(1, result.getTableErrors().size());
        assertEquals(1, result.getFailCount());
        verify(adminUserService, never()).importUserList(any(), anyBoolean());
    }

    @Test
    @DisplayName("「全对才导」看的是【剔除之后】还剩多少 —— 用户一行行删掉问题行本来就是为了让这批能过")
    void testAllOrNothingCountsAfterRemoval() throws Exception {
        stubImportUserList();
        UserImportPreviewRespVO preview = preview(
                Arrays.asList("登录名称", "用户名称", "部门编号"),
                Arrays.asList("gooduser", "正常", "100"),
                Arrays.asList("baduser", "部门不对", "999"));

        UserImportResultRespVO result = userImportService.execute(new UserImportExecuteReqVO()
                .setImportToken(preview.getImportToken()).setAllOrNothing(true)
                .setRemovedRows(Collections.singletonList(3)));

        assertTrue(result.getTableErrors().isEmpty());
        verify(adminUserService).importUserList(argThat(list -> list.size() == 1), anyBoolean());
    }

    @Test
    @DisplayName("写库时才失败的行要回填行号 —— 上游只给用户名，用户还要回 Excel 里搜一遍")
    void testExecuteFailureCarriesRowNo() throws Exception {
        when(adminUserService.importUserList(any(), anyBoolean())).thenReturn(
                UserImportRespVO.builder().createUsernames(new ArrayList<>())
                        .updateUsernames(new ArrayList<>())
                        .failureUsernames(Collections.singletonMap("zhangsan", "手机号已经存在")).build());
        UserImportPreviewRespVO preview = preview(
                Arrays.asList("登录名称", "用户名称"), Arrays.asList("zhangsan", "张三"));

        UserImportResultRespVO result = userImportService.execute(
                new UserImportExecuteReqVO().setImportToken(preview.getImportToken()));

        assertEquals(1, result.getFailCount());
        assertEquals(2, result.getFailures().get(0).getRowNo());
        assertEquals("手机号已经存在", result.getFailures().get(0).getMessage());
    }

    @Test
    @DisplayName("一行都不剩时不去调写库 —— 那条路径遇到空列表会抛「导入数据不能为空」")
    void testExecuteNothingToDo() throws Exception {
        stubImportUserList();
        UserImportPreviewRespVO preview = preview(
                Arrays.asList("登录名称", "用户名称"), Arrays.asList("zhangsan", "张三"));

        UserImportResultRespVO result = userImportService.execute(new UserImportExecuteReqVO()
                .setImportToken(preview.getImportToken()).setRemovedRows(Collections.singletonList(2)));

        assertEquals(1, result.getSkipCount());
        assertEquals(0, result.getCreateCount());
        verify(adminUserService, never()).importUserList(any(), anyBoolean());
    }

    // ==================== 造数据 ====================

    private void stubImportUserList() {
        when(adminUserService.importUserList(any(), anyBoolean())).thenAnswer(invocation -> {
            List<UserImportExcelVO> list = invocation.getArgument(0);
            return UserImportRespVO.builder()
                    .createUsernames(list.stream().map(UserImportExcelVO::getUsername).toList())
                    .updateUsernames(new ArrayList<>())
                    .failureUsernames(new LinkedHashMap<>()).build();
        });
    }

    private UserImportPreviewRespVO preview(List<String> header, List<?>... rows) throws Exception {
        List<List<Object>> data = new ArrayList<>();
        for (List<?> row : rows) {
            data.add(new ArrayList<>(row));
        }
        return userImportService.preview(excel(header, data), 1000, 1000);
    }

    /**
     * 造一份真的 xlsx
     *
     * 不 mock 掉解析：表头匹配、行号、字典反查这些恰恰全在解析那一段上，
     * 喂一个手工拼的 RawRow 进去，测的就只剩判定逻辑了
     */
    private MultipartFile excel(List<String> header, List<List<Object>> data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<List<String>> head = new ArrayList<>();
        header.forEach(it -> head.add(Collections.singletonList(it)));
        FastExcelFactory.write(out).head(head).sheet("用户列表").doWrite(data);
        return new MockMultipartFile("file", "users.xlsx", null, out.toByteArray());
    }

    private DictDataRespDTO dictData(String label, String value) {
        DictDataRespDTO dto = new DictDataRespDTO();
        dto.setLabel(label);
        dto.setValue(value);
        return dto;
    }

}
