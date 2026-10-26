package cn.enlev.ainb.module.infra.service.formdesign.runtime;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.common.util.json.JsonUtils;
import cn.enlev.ainb.module.infra.controller.admin.formdesign.vo.runtime.FormImportPreviewReqVO;
import cn.enlev.ainb.module.infra.controller.admin.formdesign.vo.runtime.FormImportPreviewRespVO;
import cn.enlev.ainb.module.infra.controller.admin.formdesign.vo.runtime.FormImportReqVO;
import cn.enlev.ainb.module.infra.controller.admin.formdesign.vo.runtime.FormImportRespVO;
import cn.enlev.ainb.module.infra.dal.dataobject.formdesign.FormDesignDO;
import cn.enlev.ainb.module.infra.framework.formdesign.core.FormFieldsCodec;
import cn.enlev.ainb.module.infra.framework.formdesign.core.binding.SubTableBinding;
import cn.enlev.ainb.module.infra.framework.formdesign.core.binding.TableBinding;
import cn.enlev.ainb.module.infra.framework.formdesign.core.imports.ImportDataResolver;
import cn.enlev.ainb.module.infra.framework.formdesign.core.imports.ImportDryRunValidator;
import cn.enlev.ainb.module.infra.framework.formdesign.core.imports.ImportHeaderMapper;
import cn.enlev.ainb.module.infra.framework.formdesign.core.imports.ImportModels;
import cn.enlev.ainb.module.infra.framework.formdesign.core.imports.ImportModels.RowError;
import cn.enlev.ainb.module.infra.framework.formdesign.core.imports.ImportModels.RowStatus;
import cn.enlev.ainb.module.infra.framework.formdesign.core.imports.ImportRowGrouper;
import cn.enlev.ainb.module.infra.framework.formdesign.core.imports.ImportTemplateWriter;
import cn.enlev.ainb.module.infra.framework.formdesign.core.permission.FormFieldCropper;
import cn.enlev.ainb.module.infra.framework.formdesign.core.imports.ImportWidget;
import cn.enlev.ainb.module.infra.framework.formdesign.core.imports.ImportWidgetRegistry;
import cn.enlev.ainb.module.infra.framework.formdesign.core.sql.FormDesignSqlBuilder;
import cn.enlev.ainb.module.infra.service.formdesign.FormDesignService;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.module.infra.enums.ErrorCodeConstants.FORM_DESIGN_IMPORT_FILE_INVALID;
import static cn.enlev.ainb.module.infra.enums.ErrorCodeConstants.FORM_DESIGN_IMPORT_TOKEN_INVALID;
import static cn.enlev.ainb.module.infra.enums.ErrorCodeConstants.FORM_DESIGN_IMPORT_TOO_MANY_ROWS;
import static cn.enlev.ainb.module.infra.enums.ErrorCodeConstants.FORM_DESIGN_NOT_EXISTS;
import static cn.enlev.ainb.module.infra.enums.ErrorCodeConstants.FORM_DESIGN_NOT_PUBLISHED;
import static cn.enlev.ainb.module.infra.enums.ErrorCodeConstants.FORM_DESIGN_MAIN_TABLE_REQUIRED;

/**
 * 表单数据导入 Service 实现
 *
 * @author 走进新时代
 */
@Slf4j
@Service
public class FormImportServiceImpl implements FormImportService {

    /**
     * 预览令牌的有效期
     *
     * 5 分钟。再短的话用户改几行数据就过期了（会得到「预览已失效，请重新上传」），
     * 再长的话服务端要一直存着那份解析结果。
     */
    private static final Duration TOKEN_TTL = Duration.ofMinutes(5);

    /**
     * 一次最多保留多少个未消费的预览
     *
     * 用户点了预览又不执行是很常见的。不设上限的话，
     * 反复上传大文件会把服务端内存堆满。
     */
    private static final int MAX_PENDING_PREVIEWS = 50;

    /**
     * 预览返回给前端的最大行数
     *
     * 全量发给前端的话，一份 5000 行的表会产生几 MB 的 JSON，
     * 浏览器渲染这张预览表会卡住 —— 而用户真正要看的只是「有没有错、错在哪」。
     */
    private static final int MAX_PREVIEW_ROWS = 200;

    @Resource
    private FormDesignService formDesignService;

    @Resource
    private FormDesignSqlBuilder sqlBuilder;

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Resource
    private ImportWidgetRegistry widgetRegistry;

    @Resource
    private ImportDataResolver dataResolver;

    @Resource
    private ImportDryRunValidator dryRunValidator;

    @Resource
    private ImportTemplateWriter templateWriter;

    /**
     * 字段级权限裁剪器
     *
     * 只在生成导入模板时用：模板里不该出现用户填了也会被丢弃的列。
     * 导入的**写入**路径不需要它 —— 那条路走 runtimeService.create/update，
     * FormSubmitProcessor 已经按字段权限丢弃过 HIDDEN / READONLY 的值了，
     * 在这里再拦一次等于两份口径，迟早漂
     */
    @Resource
    private FormFieldCropper fieldCropper;

    /**
     * 执行导入走的是运行时的 create/update
     *
     * ⚠️ 用 @Resource 注入接口而不是直接写 SQL —— 这是本方案最核心的一条决定：
     * 导入的数据必须与手工录入的数据是同一种东西。
     */
    @Resource
    private FormDesignRuntimeService runtimeService;

    /**
     * 未消费的预览
     *
     * key 是 importToken。用 Guava 的 Cache 而不是自己拿 Map 管 ——
     * 过期清理、容量上限这两件事手写很容易漏掉其中一个。
     */
    private Cache<String, PendingPreview> pendingPreviews;

    @PostConstruct
    public void init() {
        pendingPreviews = CacheBuilder.newBuilder()
                .expireAfterWrite(TOKEN_TTL)
                .maximumSize(MAX_PENDING_PREVIEWS)
                .build();
    }

    /**
     * 一份已经解析、校验过、等着被执行的预览
     */
    @Data
    private static class PendingPreview {
        private String code;
        private Map<String, ImportWidget> widgets;
        private List<ImportRowGrouper.Group> groups;
        /**
         * 已经解析出来的状态与错误，执行时直接用 ——
         * 不重算一遍的理由是「用户看到什么就导什么」：
         * 重算有可能算出不一样的结果（库里的数据变了），
         * 那样用户会对着预览里明明没问题的行收到一条失败
         */
        private List<PreviewEntry> entries;
        /**
         * 更新键的字段名
         */
        private List<String> updateKeyFields;
        /**
         * 已经执行过了。用于「同一 token 只能用一次」
         */
        private boolean consumed;
    }

    /**
     * 预览里的一行及其判定结果
     */
    @Data
    private static class PreviewEntry {
        private int rowNo;
        private int endRowNo;
        private RowStatus status;
        private Map<String, Object> data;
        private Map<String, List<Map<String, Object>>> subData;
        private List<RowError> errors = new ArrayList<>();
        private List<RowError> warnings = new ArrayList<>();
        /**
         * EXISTS 状态下命中的库里记录主键
         */
        private Object existingPk;
        /**
         * EXISTS 状态下命中的那一行（含乐观锁版本号）
         *
         * 留着整行而不是只留主键：更新时要把版本号带回去，
         * 而版本号不是表单字段，从文件里取不到。
         *
         * ⚠️ 版本号取的是**预览那一刻**的值。执行时若有人已经改过这条记录，
         *    更新会因版本对不上而失败 —— 这是对的：用户看到的预览已经过时了，
         *    该让他重新看一遍，而不是拿旧数据把别人的修改覆盖掉。
         */
        private Map<String, Object> existingRow;
    }

    // ==================== 预览 ====================

    @Override
    public FormImportPreviewRespVO preview(String code, InputStream input,
                                           FormImportPreviewReqVO reqVO) throws IOException {
        FormDesignDO design = loadPublishedDesign(code);
        TableBinding binding = parseBinding(design);
        Long dataSourceId = 0L;

        // 可导入字段先算出来 —— 下面推断「父键」要用它
        Map<String, ImportWidget> widgets =
                widgetRegistry.parse(design.getFormFields(), design.getListConf());

        boolean hasSubTable = binding.getSubTables() != null && !binding.getSubTables().isEmpty();
        List<String> updateKeyFields = resolveUpdateKeyFields(binding, widgets,
                reqVO.getUpdateKeyFields());

        // ⚠️ 父键是「哪几个字段相同就算同一条主记录」，不是子表控件的名字。
        //
        //    传成子表控件名（contacts / banks）的话，每一行都归不了组，
        //    报出来是「这一行没有填 contacts + banks」—— 一句谁也看不懂的话，
        //    而正确的表现应当是 4 行明细合并成 2 条主记录。
        //
        //    只有带子表的表单才需要归组：单表表单一行就是一条记录。
        List<String> parentFields = hasSubTable ? updateKeyFields : List.of();

        // 完整解析
        ImportDataResolver.Parsed parsed = dataResolver.resolve(
                input, design.getFormFields(), design.getListConf(), parentFields);

        FormImportPreviewRespVO resp = new FormImportPreviewRespVO();

        // 带子表却没有父键：明细行无法归组。说清楚比让它跑出一堆「每行各成一条」
        // 的结果强 —— 后者会把 3 行明细导成 3 条主记录，而用户看不出哪里不对
        if (hasSubTable && parentFields.isEmpty()) {
            RowError error = new RowError();
            error.setMessage("这张表单有明细表，但没有配唯一性校验字段或业务主键，"
                    + "无法判断多行明细属于同一条主记录。"
                    + "请先在表单上给主键字段勾上「唯一性校验」");
            resp.getTableErrors().add(toVo(error));
        }

        // 1. 表头映射
        resp.setMappings(buildMappings(parsed));
        resp.setMissingRequiredFields(parsed.getMapping().getMissingRequiredFields());
        for (ImportHeaderMapper.UnmatchedColumn col : parsed.getMapping().getUnmatchedColumns()) {
            FormImportPreviewRespVO.UnmatchedColumn vo = new FormImportPreviewRespVO.UnmatchedColumn();
            vo.setColumnLetter(col.getColumnLetter());
            vo.setHeader(col.getHeader());
            vo.setReason("这一列没有对应的表单字段，数据会被忽略");
            resp.getUnmatchedColumns().add(vo);
        }
        for (ImportHeaderMapper.DuplicateColumn col : parsed.getMapping().getDuplicateColumns()) {
            FormImportPreviewRespVO.UnmatchedColumn vo = new FormImportPreviewRespVO.UnmatchedColumn();
            vo.setColumnLetter(col.getColumnLetter());
            vo.setHeader(col.getHeader());
            vo.setFirstColumnLetter(col.getFirstColumnLetter());
            vo.setReason(String.format("「%s」已经在 %s 列用过了，这一列会被忽略",
                    ImportDryRunValidator.labelOf(parsed.getWidgets(), col.getField()),
                    col.getFirstColumnLetter()));
            resp.getDuplicateColumns().add(vo);
        }

        // 2. 整表级错误：解析阶段产生的 + 分组阶段产生的
        for (RowError error : parsed.getTableErrors()) {
            resp.getTableErrors().add(toVo(error));
        }
        for (RowError error : parsed.getGroups().getTableErrors()) {
            resp.getTableErrors().add(toVo(error));
        }

        // 3. 更新键。前面已经算过（归组要用它），这里只回给前端
        resp.setUpdateKeyFields(updateKeyFields);

        // 4. 逐条干跑 + 查库判 EXISTS
        Map<String, Integer> keyFirstRow = new LinkedHashMap<>();
        List<PreviewEntry> entries = new ArrayList<>();
        for (ImportRowGrouper.Group group : parsed.getGroups().getGroups()) {
            entries.add(buildEntry(group, design, dataSourceId, binding, parsed.getWidgets(),
                    updateKeyFields, keyFirstRow));
        }

        // 5. 汇总统计
        FormImportPreviewRespVO.Summary summary = new FormImportPreviewRespVO.Summary();
        summary.setTotalRows(parsed.getDataRowCount());
        summary.setTotalGroups(entries.size());
        int newCount = 0;
        int existsCount = 0;
        int dupCount = 0;
        int invalidCount = 0;
        for (PreviewEntry entry : entries) {
            switch (entry.getStatus()) {
                case NEW -> newCount++;
                case EXISTS -> existsCount++;
                case DUPLICATE_IN_FILE -> dupCount++;
                case INVALID -> invalidCount++;
            }
        }
        summary.setNewCount(newCount);
        summary.setExistsCount(existsCount);
        summary.setDuplicateCount(dupCount);
        summary.setInvalidCount(invalidCount);

        // 6. 预览行（只回前若干条）
        int limit = Math.min(entries.size(), MAX_PREVIEW_ROWS);
        for (int i = 0; i < limit; i++) {
            resp.getRows().add(toPreviewRow(entries.get(i), parsed.getWidgets()));
        }
        summary.setPreviewRowCount(limit);
        resp.setSummary(summary);
        resp.setGroupCount(entries.size());

        // 脱敏列填的是掩码时要说出来。悄悄跳过的话，
        // 用户会以为那一列导进去了 —— 而新增的记录上那一列其实是空的
        if (!parsed.getMaskedSkippedFields().isEmpty()) {
            RowError notice = new RowError();
            notice.setLevel(ImportModels.ErrorLevel.WARN);
            notice.setMessage(String.format(
                    "「%s」填的是脱敏后的掩码，已按「不改这一列」处理："
                            + "覆盖更新时保留库里的原值，新增的记录这一列为空。"
                            + "要真的改它，请填完整值",
                    String.join("、", parsed.getMaskedSkippedFields())));
            resp.getTableErrors().add(toVo(notice));
        }

        // 跳过的合计行 / 分组行要说出来。不说的话用户会发现
        // 「文件里 101 行、只导了 100 条」，而那一条差额他找不到原因
        if (parsed.getSkippedViewRows() > 0) {
            RowError notice = new RowError();
            notice.setLevel(ImportModels.ErrorLevel.WARN);
            notice.setMessage(String.format(
                    "已跳过 %d 行合计行 / 分组行 —— 它们是导出时跟着列表带出来的，不是数据",
                    parsed.getSkippedViewRows()));
            resp.getTableErrors().add(toVo(notice));
        }

        if (parsed.isTruncated()) {
            // 截断必须说出来。不说的话用户以为整份文件都导进去了。
            //
            // ⚠️ 级别是 WARN 不是 ERROR：前 MAX_ROWS 行照样能导，用户可以先导这一批
            // 再拆下一批。标成 ERROR 的话界面会把它放进「这份文件有以下问题，请先处理」
            // 那条红色提示里 —— 而那句话是在说「不先改就导不了」，与事实相反
            RowError notice = new RowError();
            notice.setLevel(ImportModels.ErrorLevel.WARN);
            notice.setMessage(String.format(
                    "文件超过 %d 行，只读取了前 %d 行。剩下的请拆分成另一个文件分次导入",
                    ImportDataResolver.MAX_ROWS, ImportDataResolver.MAX_ROWS));
            resp.getTableErrors().add(toVo(notice));
        }

        // 7. 存起来，等执行
        String token = UUID.randomUUID().toString().replace("-", "");
        PendingPreview pending = new PendingPreview();
        pending.setCode(code);
        pending.setWidgets(parsed.getWidgets());
        pending.setGroups(parsed.getGroups().getGroups());
        pending.setEntries(entries);
        pending.setUpdateKeyFields(updateKeyFields);
        pendingPreviews.put(token, pending);
        resp.setImportToken(token);
        return resp;
    }

    /**
     * 判定一条主记录的状态
     */
    private PreviewEntry buildEntry(ImportRowGrouper.Group group, FormDesignDO design,
                                    Long dataSourceId, TableBinding binding,
                                    Map<String, ImportWidget> widgets,
                                    List<String> updateKeyFields,
                                    Map<String, Integer> keyFirstRow) {
        PreviewEntry entry = new PreviewEntry();
        entry.setRowNo(group.firstRowNo());
        entry.setEndRowNo(group.lastRowNo());
        entry.setData(group.getMainData());
        entry.setSubData(group.getSubData());
        entry.getErrors().addAll(group.getErrors());
        entry.getWarnings().addAll(group.getWarnings());

        // 组内已经有错误（比如缺必填列、同组主表值不一致）时不往下走 ——
        // 那些错误已经说明这一条进不去，再叠一层「必填项没填」只会让报告变长
        if (group.hasError()) {
            entry.setStatus(RowStatus.INVALID);
            return entry;
        }

        // 组装成运行时认识的数据形态：主表字段 + 子表数组
        Map<String, Object> submitData = new LinkedHashMap<>(group.getMainData());
        group.getSubData().forEach((subField, rows) -> {
            if (!rows.isEmpty()) {
                submitData.put(subField, rows);
            }
        });

        // 干跑校验：与运行时同一套规则
        ImportDryRunValidator.Result dryRun = dryRunValidator.validate(
                design.getFormFields(), design.getValidateRules(), design.getLogicRules(),
                submitData, group.firstRowNo(), widgets);
        if (!dryRun.isPassed()) {
            entry.getErrors().addAll(dryRun.getErrors());
            markLocations(entry.getErrors(), group);
            entry.setStatus(RowStatus.INVALID);
            return entry;
        }
        // 用净化后的数据 —— 联动规则可能丢弃或补写了字段
        entry.setData(dryRun.getData());
        entry.getWarnings().addAll(dryRun.getWarnings());
        markLocations(entry.getWarnings(), group);

        // 文件内自重复。参考实现完全没有这一层，同一份文件里重复是常态
        if (!updateKeyFields.isEmpty()) {
            String key = buildBusinessKey(group.getMainData(), updateKeyFields);
            if (key != null) {
                Integer first = keyFirstRow.get(key);
                if (first != null) {
                    RowError error = new RowError();
                    error.setRowNo(group.firstRowNo());
                    error.setMessage(String.format(
                            "与第 %d 行的主键重复（%s）。同一个文件里同一条数据只能出现一次",
                            first, key));
                    entry.getErrors().add(error);
                    entry.setStatus(RowStatus.DUPLICATE_IN_FILE);
                    return entry;
                }
                keyFirstRow.put(key, group.firstRowNo());
            }
        }

        // 查库判「已存在」
        if (!updateKeyFields.isEmpty()) {
            Map<String, Object> existingRow = findExistingRow(dataSourceId, binding,
                    group.getMainData(), updateKeyFields);
            if (existingRow != null) {
                entry.setExistingRow(existingRow);
                entry.setExistingPk(existingRow.get(binding.getMainPk()));
                entry.setStatus(RowStatus.EXISTS);
                return entry;
            }
        }
        entry.setStatus(RowStatus.NEW);
        return entry;
    }

    /**
     * 把「报告里的错误」补上列字母与原始文本
     *
     * 解析阶段产生的错误本来带着列字母（那时还知道是哪个单元格）；
     * 干跑阶段产生的只知道字段名。用户需要的是
     * 「第 7 行 I 列 预算金额：必须是数字」，缺一不可 ——
     * 少了列字母他得自己在哪一列上找。
     */
    private void markLocations(List<RowError> errors, ImportRowGrouper.Group group) {
        for (RowError error : errors) {
            if (error.getColumnLetter() != null || error.getField() == null) {
                continue;
            }
            String raw = group.getRawTexts().get(error.getField());
            if (StrUtil.isNotBlank(raw)) {
                error.setRawValue(raw);
            }
        }
    }

    // ==================== 执行 ====================

    @Override
    public FormImportRespVO execute(String code, FormImportReqVO reqVO) {
        PendingPreview pending = pendingPreviews.getIfPresent(reqVO.getImportToken());
        if (pending == null) {
            throw exception(FORM_DESIGN_IMPORT_TOKEN_INVALID,
                    "预览已经过期或不存在，请重新上传文件");
        }
        // 同一 token 只能消费一次。防的是「用户不确定有没有成功，又点了一次」——
        // 那会导致整份文件被导两遍，而唯一键上没有索引的表单完全不会报错
        synchronized (pending) {
            if (pending.isConsumed()) {
                throw exception(FORM_DESIGN_IMPORT_TOKEN_INVALID,
                        "这份预览已经导入过了。如需再导一次，请重新上传文件");
            }
            pending.setConsumed(true);
        }
        // 用掉就删，不给第二次机会
        pendingPreviews.invalidate(reqVO.getImportToken());

        if (!code.equals(pending.getCode())) {
            throw exception(FORM_DESIGN_IMPORT_TOKEN_INVALID, "预览与当前表单不匹配");
        }

        // 重新读一次表绑定：预览与执行之间表单可能被改过发布过。
        // 缓存一份在 PendingPreview 里反而会用上过期的配置
        TableBinding binding = parseBinding(loadPublishedDesign(code));

        FormImportRespVO resp = new FormImportRespVO();
        ImportModels.DuplicateStrategy strategy = parseStrategy(reqVO.getDuplicateStrategy());

        // 用户删掉的行与改过的行
        Set<String> removed = reqVO.getRemovedRows() == null ? Set.of()
                : new HashSet<>(reqVO.getRemovedRows());
        Map<String, Map<String, Object>> edited = reqVO.getEditedRows() == null ? Map.of()
                : reqVO.getEditedRows();

        // 待执行的条目：先过一遍「删掉的行」与「用户改过的行」，
        // 并把 INVALID / DUPLICATE_IN_FILE 直接计入失败
        List<PreviewEntry> toImport = new ArrayList<>();
        for (PreviewEntry entry : pending.getEntries()) {
            String rowKey = String.valueOf(entry.getRowNo());
            if (removed.contains(rowKey)) {
                continue;
            }
            // 用户就地改过的值覆盖解析结果
            Map<String, Object> override = edited.get(rowKey);
            if (override != null && !override.isEmpty()) {
                Map<String, Object> merged = new LinkedHashMap<>(entry.getData());
                merged.putAll(override);
                entry.setData(merged);
                // 改过之后原来的判定不再可信，状态重新交给执行时的服务端校验。
                // 这里**不重跑干跑**是刻意的：就地改一处的场景下，
                // 用户要的是「改了就能过」，而不是再看一遍完整的校验报告。
                // 真正的兜底在 runtimeService.create/update —— 它一定会拦
                if (entry.getStatus() == RowStatus.INVALID) {
                    entry.setStatus(RowStatus.NEW);
                }
            }

            if (entry.getStatus() == RowStatus.INVALID) {
                resp.setFailCount(resp.getFailCount() + 1);
                resp.getFailures().add(toFailure(entry, pending.getUpdateKeyFields()));
                continue;
            }
            if (entry.getStatus() == RowStatus.DUPLICATE_IN_FILE) {
                resp.setFailCount(resp.getFailCount() + 1);
                resp.getFailures().add(toFailure(entry, pending.getUpdateKeyFields()));
                continue;
            }
            toImport.add(entry);
        }

        // 「全对才导」：有失败就整批不导
        if (Boolean.TRUE.equals(reqVO.getAllOrNothing()) && !resp.getFailures().isEmpty()) {
            rowFailuresIntoTableErrors(resp);
            resp.setFailCount(0);
            return resp;
        }

        // 逐条执行。
        // ⚠️ 逐条一个事务，不做整体大事务：1000 行里 3 行有问题，
        //    不该让那 997 行白导一遍。这与校验示例里「服务端权威」的思路一致 ——
        //    能导的导进去，导不进的逐条说清楚
        for (PreviewEntry entry : toImport) {
            try {
                if (entry.getStatus() == RowStatus.EXISTS) {
                    switch (strategy) {
                        case SKIP -> {
                            resp.setSkipCount(resp.getSkipCount() + 1);
                        }
                        case UPDATE -> {
                            runtimeService.update(code, entry.getExistingPk(),
                                    withOptimisticVersion(entry, binding));
                            resp.setUpdateCount(resp.getUpdateCount() + 1);
                        }
                        case APPEND -> {
                            runtimeService.create(code, entry.getData());
                            resp.setCreateCount(resp.getCreateCount() + 1);
                        }
                    }
                    continue;
                }
                // NEW
                runtimeService.create(code, entry.getData());
                resp.setCreateCount(resp.getCreateCount() + 1);
            } catch (Exception e) {
                resp.setFailCount(resp.getFailCount() + 1);
                FormImportRespVO.Failure failure = new FormImportRespVO.Failure();
                failure.setRowNo(entry.getRowNo());
                failure.setMessage(rootMessage(e));
                failure.setBusinessKey(buildBusinessKey(entry.getData(),
                        pending.getUpdateKeyFields()));
                resp.getFailures().add(failure);
            }
        }
        return resp;
    }

    /**
     * 更新时把乐观锁的版本号补进提交数据
     *
     * <h3>为什么必须补</h3>
     *
     * 运行时的 update 要求提交数据里带版本号，缺了直接报
     * 「本次提交缺少版本号(version)」——而版本号**不是表单字段**，
     * Excel 里没有、也不该有（让用户手填版本号是荒谬的）。
     *
     * <h3>为什么用预览那一刻的版本号，而不是执行时再查一次</h3>
     *
     * 执行时重查等于「无条件覆盖」：乐观锁对导入这条路径就自动失效了，
     * 而这正是本模块反复在避免的「配了但不生效」。
     *
     * 用预览时的版本号，语义才是对的：用户是**看着那份预览**按的确认。
     * 如果这中间有人改过那条记录，更新会因版本对不上而失败，
     * 这一条会出现在失败清单里 —— 该让他重新看一遍，
     * 而不是拿一份已经过时的文件把别人的修改冲掉。
     */
    private Map<String, Object> withOptimisticVersion(PreviewEntry entry, TableBinding binding) {
        if (binding.getOptimisticLock() == null || !binding.getOptimisticLock().isUsable()) {
            return entry.getData();
        }
        String versionColumn = binding.getOptimisticLock().getColumn();
        Map<String, Object> existing = entry.getExistingRow();
        if (existing == null || !existing.containsKey(versionColumn)) {
            // 查不到版本号时原样提交，让运行时报「缺少版本号」——
            // 这里静默跳过版本条件的话，乐观锁就被导入这条路径绕过去了
            return entry.getData();
        }
        Map<String, Object> merged = new LinkedHashMap<>(entry.getData());
        merged.put(versionColumn, existing.get(versionColumn));
        return merged;
    }

    /**
     * 取异常链上最有用的一条消息
     *
     * 顶层往往是 {@code RuntimeException: xxx} 这种包装。用户要看的是
     * 「必须是数字」那句话，而不是哪个类抛的。
     */
    private String rootMessage(Throwable e) {
        Throwable current = e;
        String message = null;
        int depth = 0;
        while (current != null && depth < 10) {
            String candidate = current.getMessage();
            if (StrUtil.isNotBlank(candidate)) {
                message = candidate;
            }
            current = current.getCause();
            depth++;
        }
        return StrUtil.isNotBlank(message) ? message : "导入失败";
    }

    private ImportModels.DuplicateStrategy parseStrategy(String raw) {
        if (StrUtil.isBlank(raw)) {
            // 默认跳过。另外两个都会改数据，而「改数据」这件事
            // 用户应当在看清有多少条命中之后再做决定
            return ImportModels.DuplicateStrategy.SKIP;
        }
        try {
            return ImportModels.DuplicateStrategy.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return ImportModels.DuplicateStrategy.SKIP;
        }
    }

    // ==================== 模板 ====================

    @Override
    public void downloadTemplate(String code, HttpServletResponse response) throws IOException {
        FormDesignDO design = loadPublishedDesign(code);
        // ⚠️ 模板必须按字段权限裁过再生成，否则用户会对着一个「填了也不会生效」的列发呆：
        // 导入的写入走的是 runtimeService.create/update，而那条路上
        // FormSubmitProcessor 会按字段权限把 HIDDEN / READONLY 的值丢掉 ——
        // 模板里却还留着这一列，就又成了「填了、导了、没报错、但没进去」。
        //
        // 这里只需要喂裁过的 formFields，剩下的事模板生成器本来就会做：
        //   * HIDDEN  → cropFormFields 整条剔除，自然不进模板
        //   * READONLY → cropFormFields 注入 props.disabled = true，
        //     而 ImportTemplateWriter 本来就过滤 isDisabled（它对公式字段
        //     和只读字段是同一个理由：不该让用户以为自己该填）
        String croppedFields = FormFieldsCodec.write(
                fieldCropper.cropFormFields(code, FormFieldsCodec.read(design.getFormFields())));
        templateWriter.write(response, design.getName(), croppedFields, design.getListConf());
    }

    // ==================== 查库 ====================

    /**
     * 按更新键查库里已有的记录
     *
     * 返回**整行**而不是只返回主键：覆盖更新时要把乐观锁的版本号带回去，
     * 而版本号不是表单字段，从文件里取不到。
     *
     * @return 命中的那一行，没有则返回 null
     */
    private Map<String, Object> findExistingRow(Long dataSourceId, TableBinding binding,
                                                Map<String, Object> data,
                                                List<String> updateKeyFields) {
        List<FormDesignSqlBuilder.QueryCondition> conditions = new ArrayList<>();
        for (String field : updateKeyFields) {
            Object value = data.get(field);
            if (value == null || (value instanceof CharSequence cs && cs.isEmpty())) {
                // 参与判重的字段有空值就不判 —— 否则「两条都还没填某个字段」的记录
                // 会互相冲突，而它们其实是两条正常的草稿
                return null;
            }
            FormDesignSqlBuilder.QueryCondition condition =
                    new FormDesignSqlBuilder.QueryCondition();
            condition.setField(field);
            condition.setOperator("EQ");
            condition.setValue(value);
            conditions.add(condition);
        }
        if (conditions.isEmpty()) {
            return null;
        }
        try {
            // 复用分页查询取一条：它会把逻辑删除、从表关联这些条件都带上，
            // 而自己拼一条 SELECT 出来很容易漏掉逻辑删除 ——
            // 漏掉的后果是「已删除的记录被判成已存在」，覆盖导入时更新不到任何东西
            FormDesignSqlBuilder.SqlWithParams select = sqlBuilder.buildPage(
                    dataSourceId, binding, conditions, null, null, 1, 1);
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    select.getSql(), select.getParams().toArray());
            return rows.isEmpty() ? null : rows.get(0);
        } catch (Exception e) {
            // 查库失败不阻断预览 —— 判不出 EXISTS 的后果是把它当 NEW 处理，
            // 执行时运行时的唯一性校验还会再拦一次。这里让整份预览失败
            // 反而是把一个可以降级的问题放大成了阻断
            log.warn("[findExistingRow][表单({})按更新键查库失败，本条按新增处理]",
                    binding.getMainTable(), e);
            return null;
        }
    }

    /**
     * 决定更新键
     *
     * 优先级：
     * <ol>
     *   <li>调用方显式指定（前端弹窗里选的）</li>
     *   <li>表绑定上的业务主键配置 —— 它本来就是为「判重」设计的</li>
     *   <li>配了唯一性校验的字段</li>
     * </ol>
     *
     * 都没有时返回空列表，前端据此把「覆盖」策略置灰 ——
     * 没有键就无法判断「哪一条是已存在的」。
     */
    private List<String> resolveUpdateKeyFields(TableBinding binding,
                                                Map<String, ImportWidget> widgets,
                                                List<String> requested) {
        if (requested != null && !requested.isEmpty()) {
            // 用户指定的字段必须真的在表单里 —— 否则会是「指定了一个永远匹配不到的东西」，
            // 表现为所有行都判成新增，且看不出原因
            List<String> valid = requested.stream()
                    .filter(f -> widgets.containsKey(f))
                    .toList();
            if (!valid.isEmpty()) {
                return valid;
            }
        }
        if (binding.getBusinessKey() != null && binding.getBusinessKey().isEnabled()) {
            return binding.getBusinessKey().getFields();
        }
        List<String> uniqueFields = widgets.values().stream()
                .filter(ImportWidget::isUniqueCheck)
                .map(ImportWidget::getField)
                .toList();
        return uniqueFields;
    }

    /**
     * 按更新键拼出业务键的值，用于报告里让用户定位到那一条
     */
    private String buildBusinessKey(Map<String, Object> data, List<String> updateKeyFields) {
        if (updateKeyFields == null || updateKeyFields.isEmpty() || data == null) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        for (String field : updateKeyFields) {
            Object value = data.get(field);
            if (value != null) {
                parts.add(String.valueOf(value));
            }
        }
        return parts.isEmpty() ? null : String.join(" / ", parts);
    }

    // ==================== 组装 VO ====================

    private List<FormImportPreviewRespVO.ColumnMapping> buildMappings(
            ImportDataResolver.Parsed parsed) {
        List<FormImportPreviewRespVO.ColumnMapping> mappings = new ArrayList<>();
        for (Map.Entry<Integer, String> entry : parsed.getMapping().getColumnToField().entrySet()) {
            int column = entry.getKey();
            String field = entry.getValue();
            ImportWidget widget = parsed.getWidgets().get(field);

            FormImportPreviewRespVO.ColumnMapping vo = new FormImportPreviewRespVO.ColumnMapping();
            vo.setColumnLetter(ImportHeaderMapper.columnLetter(column));
            vo.setHeader(column < parsed.getHeaders().size() ? parsed.getHeaders().get(column) : null);
            vo.setField(field);
            vo.setFieldLabel(widget != null ? widget.label() : field);
            vo.setSubTitle(widget != null ? widget.getSubTitle() : null);
            vo.setRequired(widget != null && widget.isRequired());
            vo.setFormula(widget != null && widget.isFormula());
            mappings.add(vo);
        }
        return mappings;
    }

    private FormImportPreviewRespVO.PreviewRow toPreviewRow(PreviewEntry entry,
                                                            Map<String, ImportWidget> widgets) {
        FormImportPreviewRespVO.PreviewRow vo = new FormImportPreviewRespVO.PreviewRow();
        vo.setRowNo(entry.getRowNo());
        vo.setEndRowNo(entry.getEndRowNo());
        vo.setStatus(entry.getStatus().name());
        vo.setStatusText(statusText(entry.getStatus()));
        vo.setData(entry.getData());
        vo.setSubData(entry.getSubData());
        for (RowError error : entry.getErrors()) {
            vo.getErrors().add(toVo(error));
        }
        for (RowError error : entry.getWarnings()) {
            vo.getWarnings().add(toVo(error));
        }
        return vo;
    }

    private String statusText(RowStatus status) {
        return switch (status) {
            case NEW -> "将新增";
            case EXISTS -> "库中已存在";
            case DUPLICATE_IN_FILE -> "文件内重复";
            case INVALID -> "校验未通过";
        };
    }

    private FormImportPreviewRespVO.PreviewError toVo(RowError error) {
        FormImportPreviewRespVO.PreviewError vo = new FormImportPreviewRespVO.PreviewError();
        vo.setRowNo(error.getRowNo() == 0 ? null : error.getRowNo());
        vo.setColumnLetter(error.getColumnLetter());
        vo.setField(error.getField());
        vo.setFieldLabel(error.getFieldLabel());
        vo.setMessage(error.getMessage());
        vo.setRawValue(error.getRawValue());
        vo.setLevel(error.getLevel().name());
        return vo;
    }

    private FormImportRespVO.Failure toFailure(PreviewEntry entry, List<String> updateKeyFields) {
        FormImportRespVO.Failure failure = new FormImportRespVO.Failure();
        failure.setRowNo(entry.getRowNo());
        failure.setBusinessKey(buildBusinessKey(entry.getData(), updateKeyFields));
        // 一条记录可能有多条错误，拼起来给用户 —— 一次看到全部比看到一条强
        if (entry.getErrors().isEmpty()) {
            failure.setMessage("这一行没有通过校验");
            return failure;
        }
        RowError first = entry.getErrors().get(0);
        failure.setColumnLetter(first.getColumnLetter());
        failure.setField(first.getField());
        failure.setFieldLabel(first.getFieldLabel());
        failure.setRawValue(first.getRawValue());
        if (entry.getErrors().size() == 1) {
            failure.setMessage(first.getMessage());
        } else {
            List<String> messages = new ArrayList<>();
            for (RowError error : entry.getErrors()) {
                messages.add(error.getMessage());
            }
            failure.setMessage(String.join("；", messages));
        }
        return failure;
    }

    /**
     * 把行级失败搬进整表错误里
     *
     * 「全对才导」触发时导入了 0 条，用户需要知道为什么 ——
     * 此时失败明细在 resp.failures 里，但 failCount 被清成 0 了，
     * 前端如果按 failCount 判断就不会展示它们。所以搬一份到 tableErrors。
     */
    private void rowFailuresIntoTableErrors(FormImportRespVO resp) {
        for (FormImportRespVO.Failure failure : resp.getFailures()) {
            FormImportPreviewRespVO.PreviewError error =
                    new FormImportPreviewRespVO.PreviewError();
            error.setRowNo(failure.getRowNo());
            error.setColumnLetter(failure.getColumnLetter());
            error.setField(failure.getField());
            error.setFieldLabel(failure.getFieldLabel());
            error.setRawValue(failure.getRawValue());
            error.setFieldLabel(failure.getFieldLabel());
            error.setMessage(String.format("第 %s 行：%s",
                    failure.getRowNo() == null ? "?" : failure.getRowNo(),
                    failure.getMessage()));
            error.setLevel("ERROR");
            resp.getTableErrors().add(error);
        }
        resp.getTableErrors().add(0, tableError(String.format(
                "选择了「全对才导」，但这一批有 %d 条不通过，因此一条都没有导入",
                resp.getFailures().size())));
    }

    private FormImportPreviewRespVO.PreviewError tableError(String message) {
        FormImportPreviewRespVO.PreviewError error =
                new FormImportPreviewRespVO.PreviewError();
        error.setMessage(message);
        error.setLevel("ERROR");
        return error;
    }

    // ==================== 与运行时一致的前置校验 ====================

    private FormDesignDO loadPublishedDesign(String code) {
        FormDesignDO design = formDesignService.getFormDesignByCode(code);
        if (design == null) {
            throw exception(FORM_DESIGN_NOT_EXISTS);
        }
        if (design.getPublishedVersion() == null || design.getPublishedVersion() == 0) {
            throw exception(FORM_DESIGN_NOT_PUBLISHED);
        }
        return design;
    }

    private TableBinding parseBinding(FormDesignDO design) {
        if (StrUtil.isBlank(design.getTableBinding())) {
            throw exception(FORM_DESIGN_MAIN_TABLE_REQUIRED);
        }
        TableBinding binding = JsonUtils.parseObject(design.getTableBinding(), TableBinding.class);
        if (binding == null || StrUtil.isBlank(binding.getMainTable())) {
            throw exception(FORM_DESIGN_MAIN_TABLE_REQUIRED);
        }
        if (StrUtil.isBlank(binding.getMainPk())) {
            binding.setMainPk("id");
        }
        return binding;
    }

}
