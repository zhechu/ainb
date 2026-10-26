package cn.enlev.ainb.module.system.service.user;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.idev.excel.FastExcelFactory;
import cn.idev.excel.enums.ReadDefaultReturnEnum;
import cn.enlev.ainb.framework.dict.core.DictFrameworkUtils;
import cn.enlev.ainb.module.system.controller.admin.user.vo.user.*;
import cn.enlev.ainb.module.system.dal.dataobject.dept.DeptDO;
import cn.enlev.ainb.module.system.dal.dataobject.user.AdminUserDO;
import cn.enlev.ainb.module.system.dal.mysql.user.AdminUserMapper;
import cn.enlev.ainb.module.system.framework.user.importing.UserImportField;
import cn.enlev.ainb.module.system.framework.user.importing.UserImportSheetReader;
import cn.enlev.ainb.module.system.service.dept.DeptService;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.module.system.enums.ErrorCodeConstants.*;

/**
 * 用户导入 Service 实现
 *
 * @author 芋道源码
 */
@Slf4j
@Service
public class UserImportServiceImpl implements UserImportService {

    /**
     * 预检结果留多久
     *
     * 用户要在第二步看完一屏逐行结果再决定，太短会让他看到一半就失效；
     * 太长则意味着执行时用的是很久以前的库状态 —— 那份「哪些账号已存在」的判断早就过时了
     */
    private static final Duration TOKEN_TTL = Duration.ofMinutes(10);

    /**
     * 最多同时挂多少份未执行的预检
     *
     * 每一份都揣着整个文件解析后的数据。不设上限的话，一群人各传一份大文件又都不点确定，
     * 内存就回不来了
     */
    private static final int MAX_PENDING = 64;

    @Resource
    private AdminUserService adminUserService;
    @Resource
    private AdminUserMapper userMapper;
    @Resource
    private DeptService deptService;

    /**
     * 用容器里的 Validator，而不是 {@code ValidationUtils.validate}
     *
     * 后者把所有违规裹成一个 {@code ConstraintViolationException}，消息是拼起来的一串 ——
     * 而预检要做的恰恰是把每条违规<b>落到具体哪一列</b>。拿到 {@code ConstraintViolation}
     * 才有 {@code propertyPath}，也才能把它翻译成用户看得懂的「C 列 部门编号」
     */
    @Resource
    private Validator validator;

    /**
     * 还没被执行的预检，key 是 importToken
     *
     * 用 Guava 的 Cache 而不是自己拿 Map 管：过期清理与容量上限这两件事手写很容易漏掉一个
     */
    private Cache<String, PendingImport> pendingImports;

    @PostConstruct
    public void init() {
        pendingImports = CacheBuilder.newBuilder()
                .expireAfterWrite(TOKEN_TTL)
                .maximumSize(MAX_PENDING)
                .build();
    }

    /**
     * 一份等着被执行的预检
     */
    @Data
    private static class PendingImport {
        private List<PreviewEntry> entries;
        /**
         * 已经执行过了。用于「同一个 token 只能用一次」—— 用户手抖点两下确认，
         * 第二次不该再导一遍
         */
        private boolean consumed;
    }

    /**
     * 预检里的一行及其判定结果
     */
    @Data
    private static class PreviewEntry {
        private int rowNo;
        private RowStatus status;
        private UserImportExcelVO user;
        /**
         * 这一行解析出来的值，回给前端展示用
         */
        private Map<String, Object> data = new LinkedHashMap<>();
        private List<UserImportPreviewRespVO.PreviewError> errors = new ArrayList<>();
        private List<UserImportPreviewRespVO.PreviewError> warnings = new ArrayList<>();
    }

    private enum RowStatus {

        NEW("将新增"),
        EXISTS("库中已存在"),
        DUPLICATE_IN_FILE("文件内重复"),
        INVALID("校验未通过");

        private final String text;

        RowStatus(String text) {
            this.text = text;
        }
    }

    // ==================== 预检 ====================

    @Override
    public UserImportPreviewRespVO preview(MultipartFile file, int maxRows, int previewRows) throws IOException {
        UserImportSheetReader reader = readSheet(file, maxRows);
        UserImportPreviewRespVO respVO = new UserImportPreviewRespVO();
        respVO.setUpdateKeyFields(Collections.singletonList(UserImportField.USERNAME.getLabel()));

        // 1. 表头 → 字段
        Map<UserImportField, Integer> columnOf = resolveHeader(reader.getHeaders(), respVO);
        if (!respVO.getMissingRequiredFields().isEmpty()) {
            // 缺必填列时不再逐行解析：每一行都会报同一个错，几万条一模一样的错误
            // 只会把真正的问题埋掉。整表级说一次就够了
            respVO.getTableErrors().add(tableError(StrUtil.format(
                    "文件里缺少必填列：{}。请用模板里的列名，或下载模板重新填写",
                    String.join("、", respVO.getMissingRequiredFields()))));
        }

        // 2. 逐行解析与校验
        List<PreviewEntry> entries = respVO.getTableErrors().isEmpty()
                ? resolveRows(reader.getRows(), columnOf)
                : new ArrayList<>();

        // 3. 被行数上限截断要说出来
        //
        // ⚠️ 这是改造前的一个真实缺口：读满上限就停，但调用方拿到的只是一个普通的 List，
        // 用户导一份 3 万行的文件只会看到 2 万条的结果，而他以为那就是全部。
        //
        // 级别是 WARN 不是 ERROR：前 maxRows 行照样能导，用户可以先导这一批再拆下一批。
        // 标成 ERROR 的话界面会把它放进「请先处理」那条红色提示里，而那句话与事实相反
        if (reader.isTruncated()) {
            respVO.getTableErrors().add(tableWarning(StrUtil.format(
                    "文件超过一次导入的上限 {} 行，只读取了前 {} 行。"
                            + "剩下的请拆分成另一个文件分次导入", maxRows, maxRows)));
        }

        // 4. 统计 + 明细
        respVO.setSummary(summarize(entries, reader.isTruncated(), maxRows));
        respVO.setRows(entries.stream().limit(Math.max(previewRows, 0))
                .map(this::toPreviewRow).collect(Collectors.toList()));
        respVO.getSummary().setPreviewRowCount(respVO.getRows().size());

        // 5. 存起来等执行
        String token = IdUtil.fastSimpleUUID();
        PendingImport pending = new PendingImport();
        pending.setEntries(entries);
        pendingImports.put(token, pending);
        respVO.setImportToken(token);
        return respVO;
    }

    private UserImportSheetReader readSheet(MultipartFile file, int maxRows) throws IOException {
        UserImportSheetReader reader = new UserImportSheetReader(maxRows);
        if (file == null || file.isEmpty()) {
            return reader;
        }
        // 参考 ExcelUtils 的做法：包一层 try 兼容 windows 下的流关闭
        try (InputStream inputStream = file.getInputStream()) {
            // ⚠️ readDefaultReturn(READ_CELL_DATA) 不能省
            //
            // 不加的话 FastExcel 默认把每个单元格都当 String 交给监听器，于是监听器里
            // 声明的 Map<Integer, ReadCellData<?>> 在运行期是一堆 String，第一次取值就
            // ClassCastException —— 泛型擦除之后编译期完全看不出来
            FastExcelFactory.read(inputStream, reader)
                    .readDefaultReturn(ReadDefaultReturnEnum.READ_CELL_DATA)
                    .autoCloseStream(false)
                    .headRowNumber(1)
                    .sheet()
                    .doRead();
        }
        return reader;
    }

    /**
     * 表头 → 字段
     *
     * 顺带把三类「用户最容易困惑」的情况填进响应：没识别出来的列、被两列同时匹配到的字段、
     * 缺掉的必填列。不摆出来的话，用户只会看到一堆行报错，却不知道是表头没对上
     */
    private Map<UserImportField, Integer> resolveHeader(List<String> headers, UserImportPreviewRespVO respVO) {
        Map<UserImportField, Integer> columnOf = new LinkedHashMap<>();
        if (headers == null || headers.isEmpty()) {
            throw exception(USER_IMPORT_HEADER_MISSING);
        }
        for (int i = 0; i < headers.size(); i++) {
            String header = headers.get(i);
            if (StrUtil.isBlank(header)) {
                continue;
            }
            String letter = UserImportField.columnLetter(i);
            UserImportField field = UserImportField.ofHeader(header);
            if (field == null) {
                respVO.getUnmatchedColumns().add(unmatched(letter, header,
                        "这一列没有对应的用户字段，里面的数据会被忽略", null));
                continue;
            }
            Integer exists = columnOf.get(field);
            if (exists != null) {
                respVO.getDuplicateColumns().add(unmatched(letter, header,
                        StrUtil.format("「{}」在 {} 列已经出现过，这一列会被忽略",
                                field.getLabel(), UserImportField.columnLetter(exists)),
                        UserImportField.columnLetter(exists)));
                continue;
            }
            columnOf.put(field, i);

            UserImportPreviewRespVO.ColumnMapping mapping = new UserImportPreviewRespVO.ColumnMapping();
            mapping.setColumnLetter(letter);
            mapping.setHeader(header);
            mapping.setField(field.getField());
            mapping.setFieldLabel(field.getLabel());
            mapping.setRequired(field.isRequired());
            mapping.setDict(field.isDict());
            respVO.getMappings().add(mapping);
        }

        Arrays.stream(UserImportField.values())
                .filter(UserImportField::isRequired)
                .filter(field -> !columnOf.containsKey(field))
                .forEach(field -> respVO.getMissingRequiredFields().add(field.getLabel()));
        return columnOf;
    }

    /**
     * 一批预检要用到的、库里已有的数据
     *
     * <h3>为什么非批量不可</h3>
     *
     * 判定一行要问库三件事：这个登录名在不在、这个手机号被谁占了、这个邮箱被谁占了。
     * 逐行去问的话，一份两万行的文件就是<b>六万次查询</b> —— 预检会跑到用户以为页面挂了。
     * 而预检本该是「点一下、几秒钟看到结果」的操作，慢到那个程度等于这一步白加了。
     */
    private record ExistingUsers(Map<String, AdminUserDO> byUsername,
                                 Map<String, AdminUserDO> byMobile,
                                 Map<String, AdminUserDO> byEmail) {
    }

    /**
     * IN 查询一次带多少个值
     *
     * 不分批直接塞两万个值进 IN，多数数据库会因为 SQL 过长或占位符过多直接报错 ——
     * 而报出来的是一条数据库层的异常，看不出跟「文件太大」有什么关系
     */
    private static final int BATCH_SIZE = 500;

    /**
     * 逐行解析与判定
     */
    private List<PreviewEntry> resolveRows(List<UserImportSheetReader.RawRow> rows,
                                           Map<UserImportField, Integer> columnOf) {
        List<PreviewEntry> entries = new ArrayList<>(rows.size());
        // 文件内已经出现过的登录名 → 第一次出现在第几行
        Map<String, Integer> seenUsernames = new HashMap<>();

        // 部门一次性批量查出来。逐行 getDept 的话，一份两万行的文件就是两万次查询
        Set<Long> deptIds = rows.stream()
                .map(row -> parseLong(row.cell(columnOf.get(UserImportField.DEPT_ID))))
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, DeptDO> deptMap = deptIds.isEmpty()
                ? Collections.emptyMap() : deptService.getDeptMap(deptIds);
        ExistingUsers existing = loadExistingUsers(rows, columnOf);

        for (UserImportSheetReader.RawRow row : rows) {
            entries.add(resolveRow(row, columnOf, deptMap, existing, seenUsernames));
        }
        return entries;
    }

    /**
     * 把这份文件里出现的登录名、手机号、邮箱一次性查回来
     */
    private ExistingUsers loadExistingUsers(List<UserImportSheetReader.RawRow> rows,
                                            Map<UserImportField, Integer> columnOf) {
        return new ExistingUsers(
                loadBy(rows, columnOf, UserImportField.USERNAME, AdminUserDO::getUsername),
                loadBy(rows, columnOf, UserImportField.MOBILE, AdminUserDO::getMobile),
                loadBy(rows, columnOf, UserImportField.EMAIL, AdminUserDO::getEmail));
    }

    private Map<String, AdminUserDO> loadBy(List<UserImportSheetReader.RawRow> rows,
                                            Map<UserImportField, Integer> columnOf,
                                            UserImportField field,
                                            SFunction<AdminUserDO, String> column) {
        Integer columnIndex = columnOf.get(field);
        if (columnIndex == null) {
            return Collections.emptyMap();
        }
        List<String> values = rows.stream()
                .map(row -> StrUtil.trimToNull(row.cell(columnIndex)))
                .filter(Objects::nonNull).distinct().toList();
        Map<String, AdminUserDO> result = new HashMap<>();
        for (int from = 0; from < values.size(); from += BATCH_SIZE) {
            List<String> batch = values.subList(from, Math.min(from + BATCH_SIZE, values.size()));
            userMapper.selectList(new LambdaQueryWrapper<AdminUserDO>().in(column, batch))
                    .forEach(user -> result.put(column.apply(user), user));
        }
        return result;
    }

    private PreviewEntry resolveRow(UserImportSheetReader.RawRow row,
                                    Map<UserImportField, Integer> columnOf,
                                    Map<Long, DeptDO> deptMap,
                                    ExistingUsers existing,
                                    Map<String, Integer> seenUsernames) {
        PreviewEntry entry = new PreviewEntry();
        entry.setRowNo(row.getRowNo());

        UserImportExcelVO user = new UserImportExcelVO();
        user.setUsername(StrUtil.trimToNull(row.cell(columnOf.get(UserImportField.USERNAME))));
        user.setNickname(StrUtil.trimToNull(row.cell(columnOf.get(UserImportField.NICKNAME))));
        user.setEmail(StrUtil.trimToNull(row.cell(columnOf.get(UserImportField.EMAIL))));
        user.setMobile(StrUtil.trimToNull(row.cell(columnOf.get(UserImportField.MOBILE))));
        entry.setUser(user);

        // 1. 部门编号：必须是数字，且部门要真的存在
        String rawDept = StrUtil.trimToNull(row.cell(columnOf.get(UserImportField.DEPT_ID)));
        if (rawDept != null) {
            Long deptId = parseLong(rawDept);
            if (deptId == null) {
                entry.getErrors().add(cellError(row, columnOf, UserImportField.DEPT_ID,
                        "部门编号必须是数字", rawDept));
            } else if (!deptMap.containsKey(deptId)) {
                // 这一条上游是在写库时才报的。放到预检里报的价值在于：
                // 部门编号填错是导入最高频的错误，而用户要的是「导之前就知道」
                entry.getErrors().add(cellError(row, columnOf, UserImportField.DEPT_ID,
                        "部门不存在", rawDept));
            } else {
                user.setDeptId(deptId);
            }
        }

        // 2. 字典列：填的是中文标签，要反查成编码
        user.setSex(resolveDict(entry, row, columnOf, UserImportField.SEX));
        user.setStatus(resolveDict(entry, row, columnOf, UserImportField.STATUS));

        // 3. 字段约束。走的是 UserSaveReqVO 上那套注解 —— 与真正写库时用的是同一份规则，
        //    自己再写一遍格式校验必然会和它漂移
        validateConstraints(entry, row, columnOf, user);

        // 4. 库里 / 文件里有没有重名
        entry.getData().putAll(toData(user, rawDept, deptMap, row, columnOf));
        judgeStatus(entry, row, columnOf, user, existing, seenUsernames);
        return entry;
    }

    private Integer resolveDict(PreviewEntry entry, UserImportSheetReader.RawRow row,
                                Map<UserImportField, Integer> columnOf, UserImportField field) {
        String raw = StrUtil.trimToNull(row.cell(columnOf.get(field)));
        if (raw == null) {
            return null;
        }
        String value = DictFrameworkUtils.parseDictDataValue(field.getDictType(), raw);
        if (value == null) {
            // 把可选值一并说出来。只说「无法识别」的话，用户要去翻字典管理才知道该填什么
            List<String> options = DictFrameworkUtils.getDictDataLabelList(field.getDictType());
            entry.getErrors().add(cellError(row, columnOf, field,
                    StrUtil.format("无法识别，可填：{}", String.join(" / ", options)), raw));
            return null;
        }
        return Integer.valueOf(value);
    }

    /**
     * 跑一遍 {@link UserSaveReqVO} 上的注解校验，并把每条违规落到具体的列
     *
     * 密码给一个占位值：那一条约束是给「手工新增」用的，导入的密码来自系统配置里的初始密码，
     * 不该让用户在 Excel 里填。不给占位的话每一行都会多报一条「密码不能为空」。
     *
     * ⚠️ 占位值本身也要过得了 {@code @Length(min = 4, max = 16)} —— 给一个太长的字符串，
     * 结果是<b>每一行都被判成不合格</b>，而报出来的原因是「密码长度为 4-16 位」，
     * 用户对着自己那份根本没有密码列的 Excel 完全无从下手
     */
    private static final String PASSWORD_PLACEHOLDER = "Aa123456";

    private void validateConstraints(PreviewEntry entry, UserImportSheetReader.RawRow row,
                                     Map<UserImportField, Integer> columnOf, UserImportExcelVO user) {
        UserSaveReqVO saveReqVO = new UserSaveReqVO()
                .setUsername(user.getUsername()).setNickname(user.getNickname())
                .setDeptId(user.getDeptId()).setEmail(user.getEmail())
                .setMobile(user.getMobile()).setSex(user.getSex())
                .setPassword(PASSWORD_PLACEHOLDER);
        for (ConstraintViolation<UserSaveReqVO> violation : validator.validate(saveReqVO)) {
            String property = violation.getPropertyPath().toString();
            UserImportField field = Arrays.stream(UserImportField.values())
                    .filter(it -> it.getField().equals(property)).findFirst().orElse(null);
            if (field == null) {
                entry.getErrors().add(rowError(row.getRowNo(), violation.getMessage()));
                continue;
            }
            entry.getErrors().add(cellError(row, columnOf, field, violation.getMessage(),
                    row.cell(columnOf.get(field))));
        }
    }

    /**
     * 判定这一行的状态
     *
     * 顺序是有讲究的：先看有没有错（错的行连「是不是已存在」都不用判），
     * 再看文件内有没有重名，最后才查库
     */
    private void judgeStatus(PreviewEntry entry, UserImportSheetReader.RawRow row,
                             Map<UserImportField, Integer> columnOf, UserImportExcelVO user,
                             ExistingUsers existing, Map<String, Integer> seenUsernames) {
        if (!entry.getErrors().isEmpty()) {
            entry.setStatus(RowStatus.INVALID);
            return;
        }
        Integer firstRowNo = seenUsernames.put(user.getUsername(), row.getRowNo());
        if (firstRowNo != null) {
            entry.getErrors().add(cellError(row, columnOf, UserImportField.USERNAME,
                    StrUtil.format("登录名在第 {} 行已经出现过，这一行不会导入", firstRowNo),
                    user.getUsername()));
            entry.setStatus(RowStatus.DUPLICATE_IN_FILE);
            return;
        }

        AdminUserDO existUser = existing.byUsername().get(user.getUsername());
        // 手机号与邮箱在库里是唯一的。占用者不是这一行要更新的那个人时，写库必然失败 ——
        // 让它在预检里就现形，而不是等到一半数据已经进库了才报
        if (isOccupied(existing.byMobile().get(user.getMobile()), existUser)) {
            entry.getErrors().add(cellError(row, columnOf, UserImportField.MOBILE,
                    "手机号已被其他账号占用", user.getMobile()));
        }
        if (isOccupied(existing.byEmail().get(user.getEmail()), existUser)) {
            entry.getErrors().add(cellError(row, columnOf, UserImportField.EMAIL,
                    "邮箱已被其他账号占用", user.getEmail()));
        }
        if (!entry.getErrors().isEmpty()) {
            entry.setStatus(RowStatus.INVALID);
            return;
        }
        entry.setStatus(existUser == null ? RowStatus.NEW : RowStatus.EXISTS);
    }

    private boolean isOccupied(AdminUserDO occupier, AdminUserDO self) {
        return occupier != null && (self == null || !occupier.getId().equals(self.getId()));
    }

    // ==================== 执行 ====================

    @Override
    public UserImportResultRespVO execute(UserImportExecuteReqVO reqVO) {
        PendingImport pending = pendingImports.getIfPresent(reqVO.getImportToken());
        if (pending == null) {
            throw exception(USER_IMPORT_TOKEN_EXPIRED);
        }
        if (pending.isConsumed()) {
            throw exception(USER_IMPORT_TOKEN_CONSUMED);
        }
        // 先置上再做事：中途失败时这份预检也不该能再用一次 —— 有可能已经写进去一部分了
        pending.setConsumed(true);

        UserImportResultRespVO result = new UserImportResultRespVO();
        Set<Integer> removed = reqVO.getRemovedRows() == null
                ? Collections.emptySet() : new HashSet<>(reqVO.getRemovedRows());
        boolean updateSupport = reqVO.isUpdateSupport();

        List<PreviewEntry> kept = pending.getEntries().stream()
                .filter(entry -> !removed.contains(entry.getRowNo())).collect(Collectors.toList());
        result.setSkipCount(pending.getEntries().size() - kept.size());

        // 「全对才导」：还剩不合格的行就整批不导。
        // ⚠️ 判据是【剔除之后】还剩多少 —— 用户把有问题的行一行行删掉，本来就是为了让这一批能过
        List<PreviewEntry> bad = kept.stream()
                .filter(entry -> entry.getStatus() == RowStatus.INVALID
                        || entry.getStatus() == RowStatus.DUPLICATE_IN_FILE)
                .collect(Collectors.toList());
        if (Boolean.TRUE.equals(reqVO.getAllOrNothing()) && !bad.isEmpty()) {
            result.setSkipCount(0);
            result.getTableErrors().add(tableError(StrUtil.format(
                    USER_IMPORT_ALL_OR_NOTHING_ABORTED.getMsg(), bad.size())));
            result.setFailCount(bad.size());
            result.setFailures(bad.stream().map(this::toFailure).collect(Collectors.toList()));
            return result;
        }

        // 预检里就不合格的行不再送去写库 —— 送过去也只是换一条措辞更差的错误消息回来
        bad.forEach(entry -> result.getFailures().add(toFailure(entry)));
        List<PreviewEntry> todo = kept.stream()
                .filter(entry -> entry.getStatus() == RowStatus.NEW
                        || entry.getStatus() == RowStatus.EXISTS)
                .collect(Collectors.toList());
        if (!updateSupport) {
            // 策略是「跳过」：库里已有的直接不送，算跳过而不是失败。
            // 上游是把它们送进去再收一条「用户账号已经存在」，于是用户选了跳过却看到一屏红色
            List<PreviewEntry> exists = todo.stream()
                    .filter(entry -> entry.getStatus() == RowStatus.EXISTS).collect(Collectors.toList());
            result.setSkipCount(result.getSkipCount() + exists.size());
            todo = todo.stream().filter(entry -> entry.getStatus() == RowStatus.NEW)
                    .collect(Collectors.toList());
        }

        if (!todo.isEmpty()) {
            applyImport(todo, updateSupport, result);
        }
        result.setFailCount(result.getFailures().size());
        return result;
    }

    /**
     * 真正写库
     *
     * ⚠️ 调用 {@link AdminUserService#importUserList} 而不是自己写 mapper
     *
     * 这是整件事里最要紧的一条决定：<b>导进去的用户必须和手工新增的是同一种东西</b> ——
     * 初始密码怎么取、怎么加密、岗位给什么默认值、租户配额算不算，这些都在那条路径上。
     * 为了「预检已经校验过了」就绕开它、直接 insert，是这类功能日后长期跑偏的起点。
     *
     * 代价是校验会跑第二遍。这没关系，反而是对的：预检到执行之间库可能已经变了，
     * 第二遍用的是写库那一刻的真实状态
     */
    private void applyImport(List<PreviewEntry> todo, boolean updateSupport, UserImportResultRespVO result) {
        Map<String, PreviewEntry> entryOf = todo.stream().collect(Collectors.toMap(
                entry -> entry.getUser().getUsername(), entry -> entry, (a, b) -> a));
        UserImportRespVO respVO = adminUserService.importUserList(
                todo.stream().map(PreviewEntry::getUser).collect(Collectors.toList()), updateSupport);

        result.setCreateCount(respVO.getCreateUsernames().size());
        result.setUpdateCount(respVO.getUpdateUsernames().size());
        respVO.getFailureUsernames().forEach((username, message) -> {
            UserImportResultRespVO.Failure failure = new UserImportResultRespVO.Failure();
            failure.setUsername(username);
            // 回填行号。上游只给用户名，用户拿着它还要回 Excel 里搜一遍 ——
            // 而这个映射我们在预检时就建好了
            PreviewEntry entry = entryOf.get(username);
            failure.setRowNo(entry != null ? entry.getRowNo() : null);
            failure.setMessage(message);
            result.getFailures().add(failure);
        });
    }

    private UserImportResultRespVO.Failure toFailure(PreviewEntry entry) {
        UserImportResultRespVO.Failure failure = new UserImportResultRespVO.Failure();
        failure.setRowNo(entry.getRowNo());
        failure.setUsername(entry.getUser() == null ? null : entry.getUser().getUsername());
        UserImportPreviewRespVO.PreviewError first = entry.getErrors().isEmpty()
                ? null : entry.getErrors().get(0);
        if (first != null) {
            failure.setFieldLabel(first.getFieldLabel());
            failure.setMessage(first.getMessage());
            failure.setRawValue(first.getRawValue());
        } else {
            failure.setMessage(entry.getStatus().text);
        }
        return failure;
    }

    // ==================== 小工具 ====================

    private UserImportPreviewRespVO.Summary summarize(List<PreviewEntry> entries,
                                                     boolean truncated, int maxRows) {
        UserImportPreviewRespVO.Summary summary = new UserImportPreviewRespVO.Summary();
        summary.setTotalRows(entries.size());
        summary.setNewCount((int) entries.stream().filter(it -> it.getStatus() == RowStatus.NEW).count());
        summary.setExistsCount((int) entries.stream().filter(it -> it.getStatus() == RowStatus.EXISTS).count());
        summary.setDuplicateCount((int) entries.stream()
                .filter(it -> it.getStatus() == RowStatus.DUPLICATE_IN_FILE).count());
        summary.setInvalidCount((int) entries.stream().filter(it -> it.getStatus() == RowStatus.INVALID).count());
        summary.setTruncated(truncated);
        summary.setMaxRows(maxRows);
        return summary;
    }

    private UserImportPreviewRespVO.PreviewRow toPreviewRow(PreviewEntry entry) {
        UserImportPreviewRespVO.PreviewRow row = new UserImportPreviewRespVO.PreviewRow();
        row.setRowNo(entry.getRowNo());
        row.setStatus(entry.getStatus().name());
        row.setStatusText(entry.getStatus().text);
        row.setData(entry.getData());
        row.setErrors(entry.getErrors());
        row.setWarnings(entry.getWarnings());
        return row;
    }

    /**
     * 这一行给用户看什么
     *
     * ⚠️ 字典列显示的是<b>反查之后</b>的结果（「男」而不是原文），部门显示的是部门名。
     * 显示原文看起来更忠实，但那样用户没法确认「标签反查真的生效了」——
     * 而「看不出来」正是导入功能最需要避免的事
     */
    private Map<String, Object> toData(UserImportExcelVO user, String rawDept,
                                       Map<Long, DeptDO> deptMap,
                                       UserImportSheetReader.RawRow row,
                                       Map<UserImportField, Integer> columnOf) {
        Map<String, Object> data = new LinkedHashMap<>();
        putIfPresent(data, UserImportField.USERNAME, user.getUsername());
        putIfPresent(data, UserImportField.NICKNAME, user.getNickname());
        if (user.getDeptId() != null) {
            // 部门名从上面批量查好的 map 里取 —— 这里再 getDept 一次的话，
            // 两万行就是两万次查询，而这一列只是给用户看的
            DeptDO dept = deptMap.get(user.getDeptId());
            putIfPresent(data, UserImportField.DEPT_ID,
                    dept != null ? dept.getName() : user.getDeptId());
        } else {
            putIfPresent(data, UserImportField.DEPT_ID, rawDept);
        }
        putIfPresent(data, UserImportField.EMAIL, user.getEmail());
        putIfPresent(data, UserImportField.MOBILE, user.getMobile());
        putIfPresent(data, UserImportField.SEX, user.getSex() == null ? null
                : DictFrameworkUtils.parseDictDataLabel(UserImportField.SEX.getDictType(), user.getSex()));
        putIfPresent(data, UserImportField.STATUS, user.getStatus() == null ? null
                : DictFrameworkUtils.parseDictDataLabel(UserImportField.STATUS.getDictType(), user.getStatus()));
        // 解析不掉的字典值上面会是 null，此时退回原文，免得预览里那一格凭空消失
        columnOf.forEach((field, column) -> {
            if (field.isDict() && !data.containsKey(field.getLabel())) {
                putIfPresent(data, field, row.cell(column));
            }
        });
        return data;
    }

    private void putIfPresent(Map<String, Object> data, UserImportField field, Object value) {
        if (value != null && !"".equals(value)) {
            data.put(field.getLabel(), value);
        }
    }

    private UserImportPreviewRespVO.PreviewError cellError(UserImportSheetReader.RawRow row,
                                                          Map<UserImportField, Integer> columnOf,
                                                          UserImportField field, String message,
                                                          String rawValue) {
        UserImportPreviewRespVO.PreviewError error = new UserImportPreviewRespVO.PreviewError();
        error.setRowNo(row.getRowNo());
        Integer column = columnOf.get(field);
        error.setColumnLetter(column == null ? null : UserImportField.columnLetter(column));
        error.setField(field.getField());
        error.setFieldLabel(field.getLabel());
        error.setMessage(message);
        error.setRawValue(rawValue);
        error.setLevel("ERROR");
        return error;
    }

    private UserImportPreviewRespVO.PreviewError rowError(Integer rowNo, String message) {
        UserImportPreviewRespVO.PreviewError error = new UserImportPreviewRespVO.PreviewError();
        error.setRowNo(rowNo);
        error.setMessage(message);
        error.setLevel("ERROR");
        return error;
    }

    /**
     * 整表级的<b>阻断性</b>问题：不先改就一条都导不了
     */
    private UserImportPreviewRespVO.PreviewError tableError(String message) {
        return rowError(null, message);
    }

    /**
     * 整表级的<b>提醒</b>：照样能导，但用户得知道
     */
    private UserImportPreviewRespVO.PreviewError tableWarning(String message) {
        UserImportPreviewRespVO.PreviewError warning = rowError(null, message);
        warning.setLevel("WARN");
        return warning;
    }

    private UserImportPreviewRespVO.UnmatchedColumn unmatched(String letter, String header,
                                                             String reason, String firstLetter) {
        UserImportPreviewRespVO.UnmatchedColumn column = new UserImportPreviewRespVO.UnmatchedColumn();
        column.setColumnLetter(letter);
        column.setHeader(header);
        column.setReason(reason);
        column.setFirstColumnLetter(firstLetter);
        return column;
    }

    private Long parseLong(String text) {
        if (StrUtil.isBlank(text)) {
            return null;
        }
        try {
            return Long.parseLong(StrUtil.trim(text));
        } catch (NumberFormatException e) {
            return null;
        }
    }

}
