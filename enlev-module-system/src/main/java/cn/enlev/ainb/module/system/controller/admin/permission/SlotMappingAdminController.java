package cn.enlev.ainb.module.system.controller.admin.permission;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.common.pojo.CommonResult;
import cn.enlev.ainb.framework.common.util.object.BeanUtils;
import cn.enlev.ainb.module.system.controller.admin.permission.vo.slotmapping.SlotMappingOptionsRespVO;
import cn.enlev.ainb.module.system.controller.admin.permission.vo.slotmapping.SlotMappingRespVO;
import cn.enlev.ainb.module.system.controller.admin.permission.vo.slotmapping.SlotMappingSaveReqVO;
import cn.enlev.ainb.module.system.dal.dataobject.permission.ResourceDO;
import cn.enlev.ainb.module.system.dal.dataobject.permission.SlotMappingDO;
import cn.enlev.ainb.module.system.dal.mysql.permission.ResourceMapper;
import cn.enlev.ainb.module.system.dal.mysql.permission.SlotMappingMapper;
import cn.enlev.ainb.module.system.enums.permission.PermissionSlotEnum;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.framework.common.pojo.CommonResult.success;
import static cn.enlev.ainb.module.system.enums.ErrorCodeConstants.SLOT_MAPPING_COLUMN_NOT_EXISTS;
import static cn.enlev.ainb.module.system.enums.ErrorCodeConstants.SLOT_MAPPING_DUPLICATE;
import static cn.enlev.ainb.module.system.enums.ErrorCodeConstants.SLOT_MAPPING_TABLE_NOT_IN_DB;
import static cn.enlev.ainb.module.system.enums.ErrorCodeConstants.SLOT_MAPPING_UPDATE_ID_REQUIRED;

/**
 * 管理后台 - 语义槽位映射（S8b）
 *
 * 槽位是行过滤规则的参数锚点：规则里写 {owner}，行过滤引擎在这里查「owner → create_user_id」。
 * 这是字段权限能不能生效的前提；没有槽位映射的数据集，任何行级规则的条件都求不出来，
 * 结果是恒假（一行数据都看不到，且不报错）。
 *
 * <h3>🔴 正因为失效是静默的，写入口必须核实这一列真的存在</h3>
 *
 * 「配错了」和「没配」在这里长得一模一样：都是条件求不出来、都是恒假、都不报错。
 * 所以 {@link #validateColumnExists} 在保存的那一刻查 information_schema，
 * 表不在或列不在都直接拒，错误消息里带上表名与列名 —— 拦在写入口，
 * 比让管理员配完再去猜「为什么权限不生效」要好。
 *
 * <h3>存量行同样要核，因为写入口只拦得住今后</h3>
 *
 * 库里已经有早期自动推断写进去的行（那时不核实列存在）。它们求不出来，
 * 而界面上看着和一条好配置没有区别。所以 {@link #listByResource} 会逐行回填
 * {@code columnExists}，让前端能把这些行标出来 —— 详见 {@link #markColumnExists}。
 */
@Tag(name = "管理后台 - 语义槽位映射")
@RestController
@RequestMapping("/system/slot-mapping")
@Validated
public class SlotMappingAdminController {

    @Resource
    private SlotMappingMapper slotMappingMapper;
    /** 只为 {@link #options} 取数据集的主表名，让前端能把「所属表」默认选上 */
    @Resource
    private ResourceMapper resourceMapper;
    /**
     * 只为编辑器候选项用：表名与列名是<b>库结构的事实</b>，
     * 读 {@code information_schema} 比从子资源反推准（子资源只登记了被拿去配过字段权限的列），
     * 也比调 infra 的数据模型接口轻（那边按数据模型 id 取列，还要 {@code infra:data-model:query} 权限）
     */
    @Resource
    private JdbcTemplate jdbcTemplate;

    /**
     * 全量槽位映射。
     *
     * 存在的唯一理由是让批量授权能在**提交之前**算出覆盖度：勾选一批资源、选一个行过滤规则，
     * 前端要回答「这批资源里哪些具备该规则要求的槽位」。逐资源调 /list-by-resource 是 N 次请求，
     * 而批量授权的勾选量级是几十到上百个资源。
     *
     * 不带 slots 过滤参数：前端一次拉全量缓存住，结果与「当前选了哪个规则」无关 ——
     * 加了参数它就是个 no-op 过滤，却白搭一份前后端要对齐的契约（逗号拼接、空列表分支、@Size）。
     *
     * 量级：本库 206 行，比 /system/resource/list 一次拉的 1192 行资源小一个数量级。
     * 租户：{@link SlotMappingDO} 是 @TenantIgnore 全局表（「这张物理表的哪一列存创建人」是表结构
     * 的事实，不分租户），所以全量返回不扩大暴露面，与既有 /list-by-resource 同口径。
     */
    @GetMapping("/list")
    @Operation(summary = "查询全部槽位映射",
            description = "批量授权前核对「已勾资源里哪些具备某规则要求的槽位」用。"
                    + "缺槽位的数据集绑上规则后条件求不出来，结果是恒假（一行都看不到，且不报错）")
    @PreAuthorize("@ss.hasPermission('system:slot-mapping:query')")
    public CommonResult<List<SlotMappingRespVO>> list() {
        return success(BeanUtils.toBean(slotMappingMapper.selectList(), SlotMappingRespVO.class));
    }

    /**
     * 某资源的全部槽位映射，并逐行核实「这一列在所绑的表上真的存在」
     *
     * <p>🔴 为什么要核实存量行：{@link #validateColumnExists} 只拦得住<b>今后</b>的写入，
     * 而库里已经有早期自动推断写进去的行 —— 那时候不核实列存在，于是留下了
     * 「配了但求不出来」的映射。这种行的后果不是报错，而是引用它的规则恒假
     * （fail-closed，一行都看不到），界面上却和一条好配置长得一模一样。
     * 实测本库 46 行里有 15 行是这种：6 个资源的主表还没建出来（表单未发布），
     * 外加 {@code test_leave} 的 status 槽位指着一个不存在的列。
     *
     * <p>「看不到数据」会有人来报，「不知道为什么看不到」才是真正耗人的那种 ——
     * 所以这里宁可多查一轮库结构，也要把它标出来。
     */
    @GetMapping("/list-by-resource")
    @Operation(summary = "查询某资源的全部槽位映射",
            description = "每行额外带回 columnExists：该列在所绑的表上是否真实存在。"
                    + "为 false 说明这是一条求不出来的映射，引用它的规则会恒假（一行都看不到且不报错）")
    @Parameter(name = "resourceId", description = "资源编号（DATASET 类型）", required = true)
    @PreAuthorize("@ss.hasPermission('system:slot-mapping:query')")
    public CommonResult<List<SlotMappingRespVO>> listByResource(@RequestParam("resourceId") Long resourceId) {
        List<SlotMappingRespVO> rows = BeanUtils.toBean(
                slotMappingMapper.selectListByResourceId(resourceId), SlotMappingRespVO.class);
        markColumnExists(rows);
        return success(rows);
    }

    /**
     * 批量核实并回填 {@link SlotMappingRespVO#getColumnExists()}
     *
     * <p>一次 {@code IN} 查询把这几行涉及的表的列全捞回来，不逐行查 ——
     * 一个资源的槽位通常就三四行、涉及一到两张表，逐行查是白发的往返。
     *
     * <p>表名一律按小写比对：写入时 {@link #normalize} 已经把表名转小写了，
     * 而存量行是回填脚本写的、大小写随 {@code sys_resource.dataset_table} 原样。
     * 求值那侧同样是拿小写当 key，所以两边口径一致才算核对了同一件事。
     *
     * <p>⚠️ 表名为空的行<b>不标</b>（留 null）：空是「主表」的存量语义
     * （见 {@code SlotMappingDO#tableName}），而主表叫什么这里无从得知 ——
     * 判成 false 等于对着一条其实没坏的配置喊坏
     */
    private void markColumnExists(List<SlotMappingRespVO> rows) {
        Set<String> tables = rows.stream()
                .map(SlotMappingRespVO::getTableName)
                .filter(StrUtil::isNotBlank)
                .map(name -> name.trim().toLowerCase())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (tables.isEmpty()) {
            return;
        }
        String placeholders = tables.stream().map(t -> "?").collect(Collectors.joining(", "));
        List<Map<String, Object>> columns = jdbcTemplate.queryForList(
                "SELECT LOWER(table_name) AS tableName, LOWER(column_name) AS columnName "
                        + "FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name IN (" + placeholders + ")",
                tables.toArray());
        Set<String> existing = new HashSet<>(columns.size() * 2);
        for (Map<String, Object> column : columns) {
            existing.add(meta(column, "tableName", "TABLENAME") + "."
                    + meta(column, "columnName", "COLUMNNAME"));
        }
        for (SlotMappingRespVO row : rows) {
            if (StrUtil.isBlank(row.getTableName()) || StrUtil.isBlank(row.getColumnName())) {
                continue;
            }
            row.setColumnExists(existing.contains(row.getTableName().trim().toLowerCase()
                    + "." + row.getColumnName().trim().toLowerCase()));
        }
    }

    @PostMapping("/create")
    @Operation(summary = "新增槽位映射")
    @PreAuthorize("@ss.hasPermission('system:slot-mapping:create')")
    public CommonResult<Long> createSlotMapping(@Valid @RequestBody SlotMappingSaveReqVO reqVO) {
        // 同一个资源+槽位不能重复。这道校验同时是 uk_resource_slot 的人话版：
        // 少了它，重复配置会以 DuplicateKeyException 的形式撞唯一索引，
        // 用户读到的是「系统异常」，而真正该知道的是「这一行已经有了，去改它」
        SlotMappingDO exist = slotMappingMapper.selectByResourceIdAndSlot(
                reqVO.getResourceId(), reqVO.getSlot());
        if (exist != null) {
            throw exception(SLOT_MAPPING_DUPLICATE, reqVO.getSlot());
        }
        SlotMappingDO slot = BeanUtils.toBean(normalize(reqVO), SlotMappingDO.class);
        slot.setId(null);
        slotMappingMapper.insert(slot);
        return success(slot.getId());
    }

    @PutMapping("/update")
    @Operation(summary = "修改槽位映射（通常只改 tableName / columnName / description）")
    @PreAuthorize("@ss.hasPermission('system:slot-mapping:update')")
    public CommonResult<Boolean> updateSlotMapping(@Valid @RequestBody SlotMappingSaveReqVO reqVO) {
        if (reqVO.getId() == null) {
            throw exception(SLOT_MAPPING_UPDATE_ID_REQUIRED);
        }
        SlotMappingDO slot = BeanUtils.toBean(normalize(reqVO), SlotMappingDO.class);
        // 🔴 不能走 updateById：MyBatis-Plus 默认字段策略是 NOT_NULL，实体上为 null 的列
        //    会被【整个从 SET 子句里省掉】—— 那是「别动这列」，不是「清成 null」。
        //    description 是本 VO 上唯一可空的字段，于是「把说明清空」这个操作
        //    会返回成功、而库里旧说明原样留着。
        //    ⚠️ 前端过去恰好发的是空串（不是 null），空串不是 null 所以能写进去，
        //    这个坑一直没露出来；行内编辑改造之后清空走的是 undefined ⟹ null，正好踩上。
        //    别指望调用方永远发空串 —— 契约要在这里说清楚
        slotMappingMapper.update(slot, new LambdaUpdateWrapper<SlotMappingDO>()
                .eq(SlotMappingDO::getId, slot.getId())
                .set(slot.getDescription() == null, SlotMappingDO::getDescription, null));
        return success(true);
    }

    /**
     * 归一化 + 核实这一列在库里真的存在
     *
     * <p>表名一律转小写：求值时是拿它当 key 去 {@code tableQualifiers} 里查的，
     * 而那张 map 的 key 是 {@code tableName.toLowerCase()}。大小写不统一的话，
     * 一条配置正确的映射会因为 key 对不上而求不出来 —— 又是静默失效。
     *
     * <p>列的存在性核实是本类唯一一处「宁可不给配」的地方，理由见类注释：
     * 配错与没配的表现完全相同，且都不报错。
     *
     * <p>⚠️ 这里<b>只</b>归一表名与列名，槽位名一概不动 —— 不是漏了：
     * 槽位名是程序里那张「槽位 → 列」map 的 key，严格区分大小写，而内置槽位里有
     * {@code bizKey} 这个驼峰名。顺手给它也来一次 {@code toLowerCase()}，
     * 会得到一条被"归一化"亲手弄死的映射（{@code bizkey} 谁也匹配不上）。
     * 表列名能转小写是因为它们是 SQL 标识符，MySQL 里大小写不敏感。
     * 槽位名的合法性由 {@code SlotMappingSaveReqVO} 上的 {@code @Pattern} 在入口拦住
     *
     * @return 就地改过的同一个 reqVO（便于 {@code BeanUtils.toBean(normalize(reqVO), ...)} 直接串起来）
     */
    private SlotMappingSaveReqVO normalize(SlotMappingSaveReqVO reqVO) {
        String tableName = reqVO.getTableName().trim().toLowerCase();
        String columnName = reqVO.getColumnName().trim().toLowerCase();
        reqVO.setTableName(tableName);
        reqVO.setColumnName(columnName);
        validateColumnExists(tableName, columnName);
        return reqVO;
    }

    /**
     * 核实 {@code tableName.columnName} 在当前库里真实存在，不存在就拒
     *
     * <p>表名与列名都走 {@code ?} 绑定，不拼进 SQL。
     *
     * <p>刻意分两次查、给两条不同的错误消息：「表不在库里」和「表在、列不在」
     * 对管理员是两件完全不同的事 —— 前者多半是选错了数据集，后者是列名记错了。
     * 合成一句「表或列不存在」等于让人自己去猜。
     *
     * <p>🔴 两条都必须是 {@code ServiceException}（错误码常量）而不是
     * {@code IllegalArgumentException}：全局异常处理器没有后者的 handler，
     * 它会落到兜底分支，把消息换成「系统异常」并往 {@code infra_api_error_log}
     * 插一条带完整堆栈的记录 —— 一次选错列名的配置失误，在运维眼里就成了一次线上故障。
     * 而这两条消息恰恰是<b>必须</b>让人读懂的：槽位映射配错的后果是引用它的规则恒假
     * （一行都看不到、且不报错），读不懂拒绝原因的人只会以为自己已经配好了
     *
     * <p>⚠️ 只认主库（本模块自己的 {@link JdbcTemplate}）。表在别的数据源上时这里会拒，
     * 但那不是误伤：{@code RecordOwnerResolver} 与行过滤求值同样只在主库上跑，
     * 配了也用不上。真要支持，得先让求值侧支持多数据源，而不是先让配置侧放行
     */
    private void validateColumnExists(String tableName, String columnName) {
        Integer tables = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_schema = DATABASE() AND table_name = ? AND table_type = 'BASE TABLE'",
                Integer.class, tableName);
        if (tables == null || tables == 0) {
            throw exception(SLOT_MAPPING_TABLE_NOT_IN_DB, tableName);
        }
        Integer columns = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?",
                Integer.class, tableName, columnName);
        if (columns == null || columns == 0) {
            throw exception(SLOT_MAPPING_COLUMN_NOT_EXISTS, tableName, columnName);
        }
    }

    /**
     * 槽位映射编辑器的候选项：槽位词表 + 库里的物理表名 + 本数据集的主表名
     *
     * <p>三个输入框各自的口径（槽位名可填、表名与列名只能选）与理由，
     * 见 {@link SlotMappingOptionsRespVO} 的类注释。
     *
     * <p>列名不在这里返回：全库 217 张表 / 3367 列一次性摊开是几十万字节，
     * 而用户一次只配一行。选了表再由 {@link #tableColumns} 单独取。
     *
     * <p>权限点用 {@code system:slot-mapping:query}，与 {@link #listByResource} 同口径：
     * 能看这个资源的槽位，就能看它的候选项。
     */
    @GetMapping("/options")
    @Operation(summary = "查询槽位映射编辑器的候选项",
            description = "槽位名取自 PermissionSlotEnum 全集（含说明与候选列名），表名取自当前库，"
                    + "另带回本数据集的主表名供前端默认选中")
    @Parameter(name = "resourceId", description = "资源编号（DATASET 类型）", required = true)
    @PreAuthorize("@ss.hasPermission('system:slot-mapping:query')")
    public CommonResult<SlotMappingOptionsRespVO> options(@RequestParam("resourceId") Long resourceId) {
        List<SlotMappingDO> existing = slotMappingMapper.selectListByResourceId(resourceId);
        Set<String> mappedSlots = existing.stream()
                .map(SlotMappingDO::getSlot).filter(StrUtil::isNotBlank).collect(Collectors.toSet());

        List<SlotMappingOptionsRespVO.SlotOption> slots = new ArrayList<>();
        for (PermissionSlotEnum value : PermissionSlotEnum.values()) {
            SlotMappingOptionsRespVO.SlotOption option = new SlotMappingOptionsRespVO.SlotOption();
            option.setSlot(value.getSlot());
            option.setDescription(value.getDescription());
            option.setCandidates(value.getCandidates());
            option.setMapped(mappedSlots.contains(value.getSlot()));
            slots.add(option);
        }

        SlotMappingOptionsRespVO respVO = new SlotMappingOptionsRespVO();
        respVO.setSlots(slots);
        respVO.setTables(listTables());
        ResourceDO resource = resourceMapper.selectById(resourceId);
        respVO.setMainTable(resource == null ? null : resource.getDatasetTable());
        return success(respVO);
    }

    /**
     * 某张表的列，供「先选表、再选字段」的第二级下拉
     *
     * <p>表名走 {@code ?} 绑定，不拼进 SQL。表不存在时返回空列表而不是报错 ——
     * 前端把列下拉禁用掉即可；真要保存也会被 {@link #validateColumnExists} 拦下，
     * 不会写进一条求不出来的映射
     */
    @GetMapping("/table-columns")
    @Operation(summary = "查询某张物理表的列", description = "槽位映射编辑器「先选表、再选字段」的第二级")
    @Parameter(name = "tableName", description = "物理表名", required = true, example = "biz_book_borrow")
    @PreAuthorize("@ss.hasPermission('system:slot-mapping:query')")
    public CommonResult<List<SlotMappingOptionsRespVO.ColumnOption>> tableColumns(
            @RequestParam("tableName") String tableName) {
        if (StrUtil.isBlank(tableName)) {
            return success(List.of());
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT column_name AS columnName, column_comment AS columnComment "
                        + "FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = ? "
                        + "ORDER BY ordinal_position",
                tableName.trim());
        List<SlotMappingOptionsRespVO.ColumnOption> columns = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            SlotMappingOptionsRespVO.ColumnOption column = new SlotMappingOptionsRespVO.ColumnOption();
            column.setColumnName(meta(row, "columnName", "COLUMNNAME"));
            column.setColumnComment(meta(row, "columnComment", "COLUMNCOMMENT"));
            columns.add(column);
        }
        return success(columns);
    }

    /**
     * 当前库的物理表（表名 + 注释）
     *
     * <p>用的是本模块自己的 {@link JdbcTemplate}，也就是主库 ——
     * 与 {@code RecordOwnerResolver} 读归属人时同一个数据源。
     * 刻意不去调 infra 的数据源配置接口：那条路要 {@code infra:data-source-config:query} 权限
     * 外加一个数据源编号，而 system 模块本来也不该反向依赖 infra 的内部实现。
     *
     * <p>⚠️ 于是这里只列得出主库的表。在线表单是可以绑到别的数据源上的
     * （{@code infra_form_design.data_source_config_id}），那种表单的表在下拉里选不到、
     * 保存也会被 {@link #validateColumnExists} 拒。实测本库 55 张在线表单全部绑在主库
     * （data_source_config_id 为空或 0），所以这条限制对现有数据零影响。
     * 真要支持非主库表单，得让求值侧（行过滤、{@code RecordOwnerResolver}）先能按表单的
     * 数据源切换 —— 那是求值侧的改造，配置侧放宽校验只会把「配了但不生效」重新放回来
     */
    private List<SlotMappingOptionsRespVO.TableOption> listTables() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT table_name AS tableName, table_comment AS tableComment "
                        + "FROM information_schema.tables "
                        + "WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE' "
                        + "ORDER BY table_name");
        List<SlotMappingOptionsRespVO.TableOption> tables = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            SlotMappingOptionsRespVO.TableOption table = new SlotMappingOptionsRespVO.TableOption();
            table.setTableName(meta(row, "tableName", "TABLENAME"));
            table.setTableComment(meta(row, "tableComment", "TABLECOMMENT"));
            tables.add(table);
        }
        return tables;
    }

    /**
     * 从 information_schema 的一行里取字段
     *
     * <p>底层按别名回传，大小写随驱动而变，两种都取一次 —— 与
     * {@code DataModelApiImpl} 里同名方法一个写法
     */
    private static String meta(Map<String, Object> row, String camelKey, String upperKey) {
        Object value = row.getOrDefault(camelKey, row.get(upperKey));
        return value == null ? null : value.toString();
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除槽位映射",
            description = "⚠️ 删掉某个槽位后，引用该槽位的规则条件求值时会返回恒假（一行都看不到）")
    @Parameter(name = "id", description = "映射编号", required = true)
    @PreAuthorize("@ss.hasPermission('system:slot-mapping:delete')")
    public CommonResult<Boolean> deleteSlotMapping(@RequestParam("id") Long id) {
        // ⚠️ 物理删除，不能用 deleteById 的软删除 ——
        // 唯一索引 uk_resource_slot 不含 deleted 字段，软删除会让这个 slot 名被永久占用，
        // 之后用同名重建必然撞唯一键报 500（详见 SlotMappingMapper#deleteByIdPhysically）
        slotMappingMapper.deleteByIdPhysically(id);
        return success(true);
    }

}
