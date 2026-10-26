package cn.enlev.ainb.module.agent.framework.agent.core.validate;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.module.infra.api.datamodel.DataModelApi;
import cn.enlev.ainb.module.infra.api.datamodel.dto.DataModelSaveReqDTO;
import cn.enlev.ainb.module.infra.api.datamodel.dto.DataModelStructureRespDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 改表参数的生成后校验（方案 §5.4 —— 整套工具里最高危的一个）
 *
 * <h3>🔴 改表危险在哪</h3>
 * {@code updateDataModel} 是<b>全量同步语义</b>：真实表里有、而入参 {@code columns} 里
 * 没出现的列，<b>一律进 dropColumns</b>。也就是说「加一个字段」的直觉写法
 *（只提交那个新字段）等于「把其它所有列都删掉」。
 *
 * <p>后端对系统列有硬保护（B8 修复后，{@code validateSystemColumnsNotDropped}
 * 无论表有无数据都拒绝），对业务列则在 B4-3 之后也要显式确认 ——
 * 但那个确认位（{@code confirmDropColumns}）<b>不许由 AI 设置</b>，
 * 两者相加就是：<b>删列这件事在 AI 路径上不存在</b>，空表也不例外。
 * 见 {@link #validateDrops} 与 {@link #validateNoConfirmDropColumns}。
 *
 * <h3>这个校验器的定位</h3>
 * 它是<b>第二道</b>防线。第一道在工具里：{@code datamodel_update} 不接受完整列集合，
 * 只接受「加/改」的意图，由工具自己取回现有结构再组装 ——
 * 让「漏传某一列」在结构上不可能发生。
 *
 * <p>这里再校验一遍，是因为组装逻辑本身也可能出错，而这一步的代价不可回滚。
 *
 * <h3>🔴 还有一道与结构无关的：乐观锁基线</h3>
 * 改表是「读结构 → 组装 → 写回」，读与写之间隔着好几轮模型调用（几十秒到几分钟）。
 * 这正是并发覆盖最容易发生的形态，所以 {@code version} 必须由工具从取回值里带上
 * （{@link #validateVersionCarried}），后端拿它做 CAS。
 *
 * @author 走进新时代
 */
@Component
@Slf4j
public class DataModelUpdateValidator {

    /**
     * 必须始终存在的系统列
     *
     * ⚠️ 名单从 {@link DataModelApi#getTableConstraints()} 取，这里不写字面量。
     * 少了 {@code tenant_id} → 拼不出租户条件 → 跨租户数据泄漏；
     * 少了 {@code deleted} → 逻辑删除变成物理删除，不可恢复。
     */
    @Resource
    private DataModelApi dataModelApi;

    /**
     * 校验改表参数
     *
     * @param reqDTO  组装好的全量改表参数
     * @param current 改之前的真实结构
     * @return 结果
     */
    public AgentValidateResult validate(DataModelSaveReqDTO reqDTO,
                                        DataModelStructureRespDTO current) {
        AgentValidateResult result = new AgentValidateResult();
        if (reqDTO == null || current == null) {
            result.addError("改表参数或原结构为空");
            return result;
        }
        if (reqDTO.getId() == null) {
            result.addError("改表必须带上模型编号 id，否则会被当成新建");
        }

        Set<String> targetNames = lowerNames(reqDTO.getColumns() == null ? List.of()
                : reqDTO.getColumns().stream().map(DataModelSaveReqDTO.ColumnDTO::getColumnName)
                        .toList());
        Set<String> currentNames = lowerNames(current.getColumns() == null ? List.of()
                : current.getColumns().stream()
                        .map(DataModelStructureRespDTO.ColumnDTO::getColumnName).toList());

        validateTableNameUnchanged(reqDTO, current, result);
        validateSystemColumns(targetNames, currentNames, result);
        validatePrimaryKey(reqDTO, current, result);
        validateFlowWritebackColumns(reqDTO, current, result);
        validateDrops(targetNames, currentNames, result);
        validateNoRiskConfirmed(reqDTO, result);
        validateNoConfirmDropColumns(reqDTO, result);
        validateVersionCarried(reqDTO, result);
        return result;
    }

    // ==================== 表名 ====================

    /**
     * 表名不可改
     *
     * 后端会<b>静默丢弃</b>表名变更（{@code DataModelServiceImpl} 的 update 路径
     * 用的是库里那条记录的表名）。不拦的话，模型以为改了名、实际没改，
     * 而它后续的所有操作都会按新名字来找这张表。
     */
    private void validateTableNameUnchanged(DataModelSaveReqDTO reqDTO,
                                            DataModelStructureRespDTO current,
                                            AgentValidateResult result) {
        if (StrUtil.isBlank(reqDTO.getTableName())) {
            return;
        }
        if (!reqDTO.getTableName().equalsIgnoreCase(current.getTableName())) {
            result.addError("不能改表名（当前「%s」，传入「%s」）。"
                            + "后端会静默丢弃表名变更 —— 改了等于没改，但后续操作会按新名字找表。"
                            + "要改表名请重建表",
                    current.getTableName(), reqDTO.getTableName());
        }
    }

    // ==================== 系统列 ====================

    /**
     * 🔴 现有表里的系统列，一个都不能少
     *
     * <h3>判据是「现有表里有的」，不是「名单里列的」</h3>
     * 系统列名单随 {@code systemColumnProfile} 而变（TENANT / GLOBAL / LEGACY），
     * 而且名单里还有 {@code version} 这类并非每张表都有的列。
     * 拿名单当必填清单的话，一张本来就没有 {@code version} 的表会被判成「缺列」而永远改不了 ——
     * 实测踩过这一条。
     *
     * <p>要防的是<b>删除</b>，所以正确的判据只能是：原来有、现在没了。
     */
    private void validateSystemColumns(Set<String> targetNames, Set<String> currentNames,
                                       AgentValidateResult result) {
        List<String> systemColumns = dataModelApi.getTableConstraints().getSystemColumnNames();
        if (systemColumns == null || systemColumns.isEmpty()) {
            log.warn("[validateSystemColumns][取不到系统列名单，跳过该项校验]");
            return;
        }
        List<String> missing = systemColumns.stream()
                .map(name -> name.toLowerCase(Locale.ROOT))
                // 只看这张表原本就有的系统列
                .filter(currentNames::contains)
                .filter(name -> !targetNames.contains(name))
                .toList();
        if (!missing.isEmpty()) {
            result.addError("提交的列里缺少系统列：%s。"
                            + "改表是【全量同步】语义 —— 没传的列会被当成要删除。"
                            + "删掉 tenant_id 会导致跨租户数据泄漏，删掉 deleted 会让逻辑删除变成物理删除。"
                            + "正确做法是先取回完整结构，在其上做局部修改后整体回传",
                    String.join("、", missing));
        }
    }

    // ==================== 主键 ====================

    private void validatePrimaryKey(DataModelSaveReqDTO reqDTO,
                                    DataModelStructureRespDTO current,
                                    AgentValidateResult result) {
        Set<String> currentPk = current.getColumns() == null ? Set.of()
                : current.getColumns().stream()
                        .filter(item -> Boolean.TRUE.equals(item.getPrimaryKey()))
                        .map(item -> item.getColumnName().toLowerCase(Locale.ROOT))
                        .collect(Collectors.toSet());
        Set<String> targetPk = reqDTO.getColumns() == null ? Set.of()
                : reqDTO.getColumns().stream()
                        .filter(item -> Boolean.TRUE.equals(item.getPrimaryKey()))
                        .map(item -> item.getColumnName().toLowerCase(Locale.ROOT))
                        .collect(Collectors.toSet());
        if (!Objects.equals(currentPk, targetPk)) {
            result.addError("不能改主键（当前 %s，传入 %s）。"
                            + "改主键要重建索引、可能涉及数据迁移，平台不支持，请重建表",
                    currentPk, targetPk);
        }
    }

    // ==================== 删列 ====================

    /**
     * 🔴 删业务列：AI 路径<b>一律拒</b>（B4-3）
     *
     * <p>后端那道闸要的是 {@code confirmDropColumns=true}，而那个标记
     * <b>不许由 AI 设置</b>（见 {@link #validateNoConfirmDropColumns}）——
     * 两者相加，AI 就删不了列了。这是有意的，不是漏配。
     *
     * <h3>为什么在这里就报错，而不是留给后端报</h3>
     * 后端那句 {@code DATA_MODEL_DROP_COLUMN_NEED_CONFIRM} 的补救建议是
     * 「请确认后重试（confirmDropColumns=true）」—— 对<b>人</b>是对的（界面上会弹二次确认），
     * 对<b>模型</b>是个死循环：它照做也没用，工具在组装参数时会把那个标记重新抹成 null，
     * 于是同一个错误再报一遍。模型只会一直重试，或者转头告诉用户「这列删不掉，平台有 bug」。
     * 所以在这一层就说清「谁来做、去哪儿做」。
     *
     * <p>⚠️ 空表也不例外。B4-3 之前这一层只出<b>警告</b>（放行、把决定权交给后端），
     * 而后端那道闸只在<b>有数据</b>时才拦 —— 于是空表上「漏传一列」是<b>静默</b>的：
     * 那一列进 dropColumns，DDL 执行成功，接口返回成功，表结构少了一列。
     * 「表是空的」只说明数据没丢，不说明<b>结构</b>没丢 ——
     * 而结构是这一整套工具真正在保护的东西。
     *
     * <p>系统列的缺失已经在 {@link #validateSystemColumns} 里报过了，这里只说业务列，避免重复刷屏。
     */
    private void validateDrops(Set<String> targetNames, Set<String> currentNames,
                               AgentValidateResult result) {
        List<String> drops = currentNames.stream()
                .filter(name -> !targetNames.contains(name))
                .toList();
        if (drops.isEmpty()) {
            return;
        }
        Set<String> systemColumns = lowerNames(
                dataModelApi.getTableConstraints().getSystemColumnNames());
        List<String> businessDrops = drops.stream()
                .filter(name -> !systemColumns.contains(name))
                .toList();
        if (businessDrops.isEmpty()) {
            return;
        }
        result.addError(String.format(
                "这次改表会删除业务列：%s，而删列不允许由 AI 执行（空表也不行）。"
                        + "删列不可回滚，必须由人在能看到表结构的地方做这个决定 —— "
                        + "请让用户去【基础设施 → 数据管理 → 数据模型 → 该表 → 修改】里删，那边有二次确认弹窗。"
                        + "⚠️ 如果你本意只是加字段，那就是提交的列清单漏了这几列，请补齐后重试",
                String.join("、", businessDrops)));
    }

    // ==================== riskConfirmed ====================

    /**
     * 🔴 AI 永远不能传 riskConfirmed
     *
     * 这个标记的语义是「人已经看过这张表的数据，确认这些列可以丢」。
     * 用户在 Plan 卡片上点的是「同意执行这个计划」，<b>不等于</b>
     * 「我确认这列数据可以永久丢失」—— 两件事不能混为一谈。
     *
     * <p>真要删有数据的列，应当去数据模型界面，在能看到数据的地方做这个决定。
     */
    private void validateNoRiskConfirmed(DataModelSaveReqDTO reqDTO, AgentValidateResult result) {
        if (Boolean.TRUE.equals(reqDTO.getRiskConfirmed())) {
            result.addError("不允许由 AI 设置 riskConfirmed。"
                    + "它的语义是「人已经看过数据、确认这些列可以丢」，"
                    + "而用户在计划卡片上点的是「同意执行这个计划」，不是同一件事。"
                    + "要删除有数据的列，请到数据模型界面操作");
        }
    }

    // ==================== confirmDropColumns ====================

    /**
     * 🔴 AI 永远不能传 confirmDropColumns（B4-3）
     *
     * <p>与 {@link #validateNoRiskConfirmed} 同一条纪律，但它俩<b>不可互换</b>：
     * <ul>
     *   <li>{@code riskConfirmed} 管的是<b>数据</b>——「这些列里的行可以丢」，
     *       只在表里有数据时才用得上；</li>
     *   <li>{@code confirmDropColumns} 管的是<b>结构</b>——「我确实要删这几列」，
     *       与表里有没有数据无关。空表上它才是唯一那道闸。</li>
     * </ul>
     * 两个都得由人来给，因为用户在 Plan 卡片上点的是「同意执行这个计划」，
     * 那既不是「数据可以丢」也不是「这几列该删」。
     */
    private void validateNoConfirmDropColumns(DataModelSaveReqDTO reqDTO,
                                              AgentValidateResult result) {
        if (Boolean.TRUE.equals(reqDTO.getConfirmDropColumns())) {
            result.addError("不允许由 AI 设置 confirmDropColumns。"
                    + "它的语义是「人确实要删掉这几列」，"
                    + "而用户在计划卡片上点的是「同意执行这个计划」，不是同一件事。"
                    + "要删列，请到数据模型界面操作 —— 那边会弹出二次确认");
        }
    }

    // ==================== 乐观锁基线 ====================

    /**
     * 🔴 改表必须带上从 {@code getStructure} 取回的 {@code version}（B4-4）
     *
     * <p>正常情况下 {@link DataModelSaveReqDTO#getVersion()} 由
     * {@code DataModelParamResolver#resolveUpdate} 从取回的结构里填，模型碰不到。
     * 这里再判一遍是因为「填不上」是<b>可能的</b>：登记行是本轮 DDL 之前建的、
     * 或者 {@code getStructure} 那条路径哪天漏了搬运 —— 两种情况都会让它是 null，
     * 而后端遇到 null 版本会<b>拒绝执行</b>（缺了它乐观锁形同没开）。
     *
     * <p>放在这一层的价值是报错说人话：后端那句讲的是「请先读回这条记录、把 version 一并带上」，
     * 而模型<b>做不到</b>（它连设置这个字段的入口都没有），只会反复重试。
     */
    private void validateVersionCarried(DataModelSaveReqDTO reqDTO, AgentValidateResult result) {
        if (reqDTO.getVersion() == null) {
            result.addError("改表参数里没有 version（乐观锁版本号），无法安全执行。"
                    + "它应当由工具从 datamodel_structure 的取回值里自动带上，"
                    + "模型不需要也不应该自己填 —— 出现这条说明工具链有问题，请把这句话原样告诉用户");
        }
    }

    // ==================== 流程回写列（B18） ====================

    /**
     * 新增的、或这次改了类型的流程回写列，类型要合格（判据与建表那条<b>同一个方法</b>）
     *
     * <p>事故与理由见 {@link DataModelCreateValidator#validateFlowWritebackColumns} 的规则头
     * （建表时把 {@code process_status} 建成 STRING，表单从此挂不上审批流，而唯一的补齐入口拒绝动手）。
     * 改表这一侧是同一个缺陷的第二个入口：给既有表补流程列，走的正是这里。
     *
     * <h3>🔴 只判「新增」与「改了类型」的列，<b>不判原样回传的那些</b></h3>
     * 改表是全量同步语义，{@code reqDTO.columns} 里绝大多数是<b>原样回传</b>的既有列。
     * 一张历史上就把 {@code process_status} 建成了 varchar 的表（本次事故那张就是），
     * 若连原样回传的列一起判，那么对这张表的<b>任何</b>一次改表都会被拦 ——
     * 包括「加一个无关字段」，也包括「把这一列的类型改对」之外的每一次修复尝试。
     * 表现是这张表从此改不动，而报错指着一次它并没打算做的改动。
     * 判据因此收窄成：这一列在原结构里<b>不存在</b>，或它的类型与原结构<b>不一致</b>。
     * 后者恰好把「模型正在把类型改对」那一档放过去（改完就合格了）。
     */
    private void validateFlowWritebackColumns(DataModelSaveReqDTO reqDTO,
                                              DataModelStructureRespDTO current,
                                              AgentValidateResult result) {
        if (CollUtil.isEmpty(reqDTO.getColumns())) {
            return;
        }
        Map<String, String> currentTypes = new HashMap<>();
        for (DataModelStructureRespDTO.ColumnDTO column
                : CollUtil.emptyIfNull(current.getColumns())) {
            if (column != null && StrUtil.isNotBlank(column.getColumnName())) {
                currentTypes.put(column.getColumnName().trim().toLowerCase(Locale.ROOT),
                        StrUtil.trimToEmpty(column.getColumnType()));
            }
        }
        List<DataModelSaveReqDTO.ColumnDTO> judged = new ArrayList<>();
        for (DataModelSaveReqDTO.ColumnDTO column : reqDTO.getColumns()) {
            if (column == null || StrUtil.isBlank(column.getColumnName())) {
                continue;   // 没名字的列不是本条的形状
            }
            String name = column.getColumnName().trim().toLowerCase(Locale.ROOT);
            String before = currentTypes.get(name);
            String after = StrUtil.trimToEmpty(column.getColumnType());
            if (before == null || !before.equalsIgnoreCase(after)) {
                judged.add(column);
            }
        }
        DataModelCreateValidator.validateFlowWritebackColumns(judged,
                dataModelApi.getTableConstraints(), result);
    }

    // ==================== 辅助 ====================

    /**
     * 统一转小写去空 —— 列名比对一律忽略大小写
     */
    private Set<String> lowerNames(java.util.Collection<String> names) {
        return names == null ? new LinkedHashSet<>() : names.stream()
                .filter(StrUtil::isNotBlank)
                .map(name -> name.toLowerCase(Locale.ROOT))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

}
