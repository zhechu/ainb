package cn.enlev.ainb.module.system.service.permission;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.common.biz.system.permission.dto.DataRowFilterRespDTO;
import cn.enlev.ainb.framework.datapermission.core.annotation.DataPermission;
import cn.enlev.ainb.module.system.dal.dataobject.permission.ResourceDO;
import cn.enlev.ainb.module.system.dal.dataobject.permission.SlotMappingDO;
import cn.enlev.ainb.module.system.dal.mysql.permission.SlotMappingMapper;
import cn.enlev.ainb.module.system.enums.permission.PermissionSlotEnum;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 读某条业务记录的归属人（S7b-2 补：记录共享的发起权判定要用）
 *
 * <h3>为什么要直接查业务表</h3>
 *
 * 判定「他是不是这条记录的创建人」必须读到那条记录。而 {@code enlev-module-system}
 * 没有、也不该有「读任意数据集的一行」这种通用能力（那等于把 infra 的运行时抄一份）。
 *
 * 但需要的三样东西<b>已经都登记在权限表里了</b>：
 *
 * <pre>
 * sys_resource.dataset_table                  物理表名
 * sys_slot_mapping  slot=bizKey → 列名        主键列
 * sys_slot_mapping  slot=owner  → 列名        归属人列
 * </pre>
 *
 * 所以这里只拼一条最小的 {@code SELECT owner FROM table WHERE pk = ?}。
 *
 * <h3>⚠️ 拼表名列名的安全边界</h3>
 *
 * 表名与列名不能参数化，所以它们<b>只来自权限表已登记的值</b>，
 * 且再过一道标识符正则 —— 与 {@code SqlIdentifierGuard} 的第三重校验同一个路数。
 * 值（主键）走 {@code ?} 绑定。
 *
 * <h3>⚠️ 读不到就是读不到，不要猜</h3>
 *
 * 没配 owner 槽位、表不在主库、记录不存在、SQL 报错 —— 一律返回 null，
 * 由调用方按「判不出归属人 → 拒绝分享」处理。
 * 在这里猜一个「大概是他吧」会让共享变成一个谁都能发起的动作。
 *
 * <h3>已知边界：只支持主库</h3>
 *
 * 用的是默认 {@link JdbcTemplate}。绑定到别的数据源的表单在这里读不到归属人，
 * 于是它们的 {@code OWNER_ONLY} 分享会一律被拒 —— 这是<b>看得见</b>的失败
 * （前端会收到明确报错），比悄悄放开好。要支持多数据源得引 dynamic-datasource 的路由，
 * 那会让 system 反向依赖 infra 的数据源配置。
 *
 * @author 走进新时代
 */
@Component
@Slf4j
public class RecordOwnerResolver {

    /**
     * 标识符兜底正则。与 {@code SqlIdentifierGuard} 保持一致
     */
    private static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,63}$");

    @Resource
    private JdbcTemplate jdbcTemplate;
    @Resource
    private SlotMappingMapper slotMappingMapper;
    @Resource
    private DataRowFilterResolver dataRowFilterResolver;

    /**
     * 读某条记录的归属人编号
     *
     * @param dataset  数据集资源（要带 {@code datasetTable}）
     * @param recordId 业务主键
     * @return 归属人的用户编号；判不出来时返回 null（<b>调用方必须按「拒绝」处理</b>）
     */
    @DataPermission(enable = false) // 判权限这件事本身不该再被行级权限过滤一遍
    public Long resolveOwnerUserId(ResourceDO dataset, String recordId) {
        if (dataset == null || StrUtil.isBlank(dataset.getDatasetTable()) || StrUtil.isBlank(recordId)) {
            return null;
        }
        String table = dataset.getDatasetTable();
        String pkColumn = slotColumn(dataset.getId(), PermissionSlotEnum.BIZ_KEY, table);
        String ownerColumn = slotColumn(dataset.getId(), PermissionSlotEnum.OWNER, table);
        if (!isSafe(table) || !isSafe(pkColumn) || !isSafe(ownerColumn)) {
            log.warn("[resolveOwnerUserId][数据集({})的表/主键列/归属人列不齐或不合法"
                            + "（table={}, pk={}, owner={}），判不出归属人]",
                    dataset.getPermission(), table, pkColumn, ownerColumn);
            return null;
        }
        String sql = String.format("SELECT `%s` FROM `%s` WHERE `%s` = ? LIMIT 1",
                ownerColumn, table, pkColumn);
        try {
            List<Object> values = jdbcTemplate.queryForList(sql, Object.class, recordId);
            if (values.isEmpty() || values.get(0) == null) {
                return null;
            }
            return toUserId(values.get(0));
        } catch (Exception ex) {
            // 表在别的数据源、列被 DBA 改了、记录不存在……都会走到这里。
            // 一律当成「判不出来」，由调用方拒绝
            log.warn("[resolveOwnerUserId][读数据集({})的记录({})归属人失败，判不出归属人]",
                    dataset.getPermission(), recordId, ex);
            return null;
        }
    }

    /**
     * 这个人看得到这条记录吗
     *
     * <h3>为什么要有它：{@code ANY_VIEWER} 档的名字必须是真的</h3>
     *
     * 那一档的语义是「<b>看得到</b>这条记录的人都能分享」。
     * 不校验可见性的话，它实际是「任何人都能分享」—— 名字与行为不符，
     * 而配置的人只会读名字。本项目里这种不符最后都变成了「配了不生效」的另一种形态：
     * 配的人以为收着，实际敞着。
     *
     * <h3>做法：把行级过滤条件拼进存在性查询</h3>
     *
     * {@code SELECT 1 FROM <table> WHERE <pk> = ? AND (<行过滤条件>)}，
     * 行过滤条件由 {@link DataRowFilterResolver} 产出 —— 与业务列表查询<b>同一份判定</b>，
     * 不另写一套（另写必然漂移，而漂移的表现是「列表里看得到的单子分享不了」）。
     *
     * @return 看得到返回 true；看不到、判不出来、查询出错一律 false
     */
    @DataPermission(enable = false)
    public boolean canSeeRecord(Long userId, ResourceDO dataset, String recordId) {
        if (userId == null || dataset == null || StrUtil.isBlank(dataset.getDatasetTable())
                || StrUtil.isBlank(recordId)) {
            return false;
        }
        String table = dataset.getDatasetTable();
        String pkColumn = slotColumn(dataset.getId(), PermissionSlotEnum.BIZ_KEY, table);
        if (!isSafe(table) || !isSafe(pkColumn)) {
            log.warn("[canSeeRecord][数据集({})的表或主键列不合法（table={}, pk={}），按看不到处理]",
                    dataset.getPermission(), table, pkColumn);
            return false;
        }
        String prefix = "`" + table + "`.";
        // 单表查询，限定符表就主表一条。⚠️ 必须显式给：绑到别的表上的槽位
        // 要按「求不出来」失效，而不是悄悄用主表前缀去凑（理由见 RuleDataPermissionRule）
        DataRowFilterRespDTO filter = dataRowFilterResolver.resolve(userId, table, prefix,
                Map.of(table.toLowerCase(), prefix));
        if (filter.isDenyAll()) {
            return false;
        }
        StringBuilder sql = new StringBuilder(String.format(
                "SELECT 1 FROM `%s` WHERE `%s`.`%s` = ?", table, table, pkColumn));
        List<Object> params = new java.util.ArrayList<>();
        params.add(recordId);
        if (filter.isFiltered() && StrUtil.isNotBlank(filter.getSql())) {
            sql.append(" AND (").append(filter.getSql()).append(")");
            params.addAll(filter.getParams());
        }
        sql.append(" LIMIT 1");
        try {
            return !jdbcTemplate.queryForList(sql.toString(), params.toArray()).isEmpty();
        } catch (Exception ex) {
            log.warn("[canSeeRecord][判定数据集({})的记录({})可见性失败，按看不到处理]",
                    dataset.getPermission(), recordId, ex);
            return false;
        }
    }

    /**
     * 归属人列可能存的是数字，也可能是字符串形态的用户编号
     * （芋道的审计列 {@code creator} 是 varchar，存的就是 userId 的字符串）
     */
    private Long toUserId(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(value.toString().trim());
        } catch (NumberFormatException e) {
            // 归属人列存的不是用户编号（比如存了用户名）。这不是异常，是配错了槽位，
            // 但后果一样：判不出归属人
            log.warn("[toUserId][归属人列的值({})不是用户编号，判不出归属人]", value);
            return null;
        }
    }

    private String slotColumn(Long resourceId, PermissionSlotEnum slot, String expectTable) {
        SlotMappingDO mapping = slotMappingMapper.selectByResourceIdAndSlot(resourceId, slot.getSlot());
        if (mapping == null) {
            return null;
        }
        // 槽位可以绑到关联表上（SlotMappingDO#tableName），而本类只查主表 ——
        // 绑在别处的列在这里取不到。返回 null 让调用方按「判不出来 → 拒绝」处理，
        // 比拿一个主表上没有的列名去拼 SQL、然后在 catch 里静默失败要说得清
        String bound = StrUtil.trimToNull(mapping.getTableName());
        if (bound != null && !bound.equalsIgnoreCase(expectTable)) {
            log.warn("[slotColumn][数据集资源({})的槽位 '{}' 绑在表 '{}' 上，"
                            + "而记录归属/可见性判定只查主表 '{}'，取不到这一列]",
                    resourceId, slot.getSlot(), bound, expectTable);
            return null;
        }
        return mapping.getColumnName();
    }

    private boolean isSafe(String identifier) {
        return identifier != null && IDENTIFIER.matcher(identifier).matches();
    }

}
