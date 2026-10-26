package cn.enlev.ainb.module.agent.framework.agent.core.validate;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.mybatis.core.util.SystemTablePolicy;
import cn.enlev.ainb.framework.mybatis.core.util.TableOperation;
import cn.enlev.ainb.module.infra.api.datamodel.DataModelApi;
import cn.enlev.ainb.module.infra.api.datamodel.dto.DataModelSaveReqDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 建表参数的生成后校验（方案 §5.5）
 *
 * <h3>为什么 agent 侧还要再校验一遍，后端明明也会拦</h3>
 * 因为两者的<b>目的不同</b>：
 * <ul>
 *   <li>后端校验是为了<b>保护数据库</b>，拦住就行，错误信息给谁看不重要</li>
 *   <li>这里校验是为了<b>让模型能自己改对</b> —— 所以错误信息必须说清楚「应该改成什么」，
 *       而且要<b>一次说完</b>。一次只报一个错，模型改完再撞下一个，
 *       三轮上限很快烧完，每一轮都是真金白银</li>
 * </ul>
 *
 * <p>更要紧的是：后端的部分规则<b>根本不报错</b>（§5.5 的 8 条静默改写）——
 * 不传 STRING 长度会悄悄变成 varchar(255)、TEXT 带默认值被静默丢弃、
 * indexType 写成小写 unique 就按普通索引建。这些后端永远不会拦，
 * 只能在这里说出来。而建表是<b>不可逆</b>的，发现得晚就只能改表。
 *
 * <h3>规则的来源</h3>
 * 表前缀、标识符正则、长度上限、系统列名一律从 {@code DataModelApi.getTableConstraints()} 取，
 * 框架表前缀走 {@code SystemTablePolicy} —— <b>这里一个字面量都不抄</b>。
 * 抄了就会漂移，而漂移的两个方向都很糟：放行了后端会拒的（白烧一轮），
 * 或拦住了界面上能建的（AI 显得比人笨）。
 *
 * @author 走进新时代
 */
@Component
@Slf4j
public class DataModelCreateValidator {

    /**
     * 表描述的最小长度
     */
    private static final int MIN_COMMENT_LENGTH = 2;

    @Resource
    private DataModelApi dataModelApi;

    /**
     * 校验建表参数
     *
     * @param reqDTO 建表参数
     * @return 结果，{@code errors} 非空即不通过
     */
    public AgentValidateResult validate(DataModelSaveReqDTO reqDTO) {
        AgentValidateResult result = new AgentValidateResult();
        if (reqDTO == null) {
            result.addError("建表参数为空");
            return result;
        }
        DataModelApi.TableConstraintsRespDTO constraints = dataModelApi.getTableConstraints();
        Set<String> allowedTypes = dataModelApi.getColumnTypeList().stream()
                .map(DataModelApi.ColumnTypeRespDTO::getType)
                .collect(java.util.stream.Collectors.toCollection(HashSet::new));

        validateTableName(reqDTO.getTableName(), constraints, result);
        validateTableComment(reqDTO, result);
        validateColumns(reqDTO, constraints, allowedTypes, result);
        validateIndexes(reqDTO, result);
        return result;
    }

    /**
     * <b>只</b>校验一个表名（S5-b 复制改写用）：业务表前缀 + 命名规范正则 + 框架保留前缀，三查
     *
     * <h3>🔴 为什么要单开这个口子，而不是让调用方去凑一个 {@link DataModelSaveReqDTO}</h3>
     * 复制一份流程时改写器要给 {@code datamodel_create} 的表名加后缀，加完必须当场知道「新名字合不合法」：
     * 不合法就得放弃改写并把原因回显给用户，否则复制会产出一份<b>必然建不了表</b>的草稿 ——
     * 而 {@code insertNewVersion} 全方法不跑 {@code validateDsl}，用户在那一刻看不到任何错。
     *
     * <p>判据只在 {@link #validate} 里，但那个入口还要求表描述 ≥2 字、至少一个业务字段、索引合规；
     * 改写器手上<b>只有一个表名字符串</b>，凑不出能通过其余三查的 DTO（硬凑等于替它编一份假字段清单）。
     * 故把三查抽成本方法，{@link #validate} 走的是<b>同一份实现</b>（方案 §2-D7：一个语义一个实现）——
     * 若在此另写一套，改写器就会与建表校验各说各话，而「复制时说合法、建表时被拒」正是本项目头号缺陷。
     *
     * <p>⚠️ 三查里的「长度上限」不是一个可取的数：{@code TableConstraintsRespDTO} 没有标识符长度字段，
     * 64 只编码在 {@code identifierPattern}（{@code ^[A-Za-z_][A-Za-z0-9_]{0,63}$}）里，
     * infra 侧的唯一具名副本是 {@code SqlSafeGuard.IDENTIFIER_MAX_LENGTH} 且无 api 出口（台账 #108）。
     * 所以调用方<b>拿不到「最多还能加几个字」</b>，只能试完再问本方法 —— 这也是它必须 public 的第二个理由。
     *
     * @param tableName 待校验的表名（内部会 trim；空白按「必须提供表名」记 error）
     * @return 结果，{@link AgentValidateResult#isPassed()} 为假即这个表名不可用，
     *         {@link AgentValidateResult#getErrors()} 是可直接展示给用户的原因
     */
    public AgentValidateResult validateTableName(String tableName) {
        AgentValidateResult result = new AgentValidateResult();
        validateTableName(tableName, dataModelApi.getTableConstraints(), result);
        return result;
    }

    // ==================== 表名 ====================

    private void validateTableName(String rawTableName,
                                   DataModelApi.TableConstraintsRespDTO constraints,
                                   AgentValidateResult result) {
        String tableName = StrUtil.trimToEmpty(rawTableName);
        if (tableName.isEmpty()) {
            result.addError("必须提供表名");
            return;
        }
        String prefix = constraints.getTablePrefix();
        if (!tableName.startsWith(prefix)) {
            // 🔴 给的建议必须<b>本身是合法的</b>。
            //    直接拼成 biz_PurchaseOrder 看着像帮了忙，实则驼峰仍然不合规 ——
            //    模型照做一遍，撞第二次错误，又是一轮真实的计费。
            //    「一次说完、一轮改对」是这个校验器存在的全部意义，
            //    给一条会再错一次的建议等于白给
            result.addError("表名 %s 缺少业务表前缀，应改为 %s", tableName,
                    prefix + toSnakeCase(tableName));
        }
        if (!Pattern.matches(constraints.getIdentifierPattern(), tableName)) {
            result.addError("表名 %s 不符合命名规范（要求：全小写 snake_case，字母开头，"
                    + "字母或数字结尾，长度 2~64，不含 $ 与中文）", tableName);
        }
        // 🔴 走 SystemTablePolicy，不自己攒前缀名单。
        //    这份名单在 S0 之前有 5 份副本、三套口径，其中表单设计那 3 份只认
        //    system_/infra_/bpm_ —— 于是 sys_grant 能被绑定为可写主表，绕过整个权限判定链
        if (!SystemTablePolicy.isAllowed(tableName, TableOperation.DDL)) {
            result.addError("表名 %s 撞上了框架保留前缀，禁止建表。换一个以 %s 开头的业务表名",
                    tableName, prefix);
        }
    }

    private void validateTableComment(DataModelSaveReqDTO reqDTO, AgentValidateResult result) {
        String comment = StrUtil.trimToEmpty(reqDTO.getTableComment());
        if (comment.length() < MIN_COMMENT_LENGTH) {
            result.addError("必须提供表描述（至少 %d 个字），用中文说明这张表存什么",
                    MIN_COMMENT_LENGTH);
            return;
        }
        if (comment.equalsIgnoreCase(StrUtil.trimToEmpty(reqDTO.getTableName()))) {
            result.addError("表描述不能与表名相同，要用中文说明这张表存什么");
        }
    }

    // ==================== 列 ====================

    private void validateColumns(DataModelSaveReqDTO reqDTO,
                                 DataModelApi.TableConstraintsRespDTO constraints,
                                 Set<String> allowedTypes,
                                 AgentValidateResult result) {
        List<DataModelSaveReqDTO.ColumnDTO> columns = reqDTO.getColumns();
        if (CollUtil.isEmpty(columns)) {
            result.addError("必须至少提供一个业务字段");
            return;
        }
        Set<String> seen = new HashSet<>();
        Set<String> systemColumns = new HashSet<>();
        CollUtil.emptyIfNull(constraints.getSystemColumnNames())
                .forEach(name -> systemColumns.add(name.toLowerCase(Locale.ROOT)));

        for (DataModelSaveReqDTO.ColumnDTO column : columns) {
            String name = StrUtil.trimToEmpty(column.getColumnName());
            if (name.isEmpty()) {
                result.addError("存在没有字段名的列");
                continue;
            }
            String lower = name.toLowerCase(Locale.ROOT);

            if (!seen.add(lower)) {
                result.addError("字段 %s 重复定义", name);
            }
            if (!Pattern.matches(constraints.getIdentifierPattern(), name)) {
                result.addError("字段名 %s 不符合命名规范（全小写 snake_case，字母开头，"
                        + "字母或数字结尾，长度 2~64）", name);
            }

            // 🔴 §5.5 规则 1：主键二选一。默认路径下业务字段一律非主键，
            //    注入器会自动补 id 作主键。这里设了主键就会变成「2 个主键」，
            //    而报错信息来自 MysqlDdlBuilder，模型看不出该怎么改
            if (Boolean.TRUE.equals(column.getPrimaryKey())) {
                result.addError("字段 %s 不能设为主键。主键由系统自动生成（id），"
                        + "业务字段一律 primaryKey=false", name);
            }

            // 🔴 §5.5 静默改写第 8 条：自定义与系统列同名的列，
            //    注入器让位、不覆盖也不报错，后续动态 CRUD 的租户拼接行为异常。
            //    这条必须硬拦 —— 它的后果是跨租户数据泄漏，而表现是「一切正常」
            if (systemColumns.contains(lower)) {
                result.addError("字段 %s 与系统列同名，禁止使用。系统列（%s）由平台自动维护，"
                        + "业务字段请换个名字", name,
                        String.join("、", constraints.getSystemColumnNames()));
            }

            // §5.5 规则 3：业务字段的注释必填（VO 上无 @NotEmpty，Service 层才强制）
            if (StrUtil.isBlank(column.getColumnComment())) {
                result.addError("字段 %s 缺少中文注释。注释是必填的，且要说清这个字段存什么", name);
            }

            validateColumnType(column, name, constraints, allowedTypes, result);
            validateFlowWritebackColumn(column, name, constraints, result);
            validateSilentRewrites(column, name, result);
        }
    }

    private void validateColumnType(DataModelSaveReqDTO.ColumnDTO column, String name,
                                    DataModelApi.TableConstraintsRespDTO constraints,
                                    Set<String> allowedTypes, AgentValidateResult result) {
        String type = StrUtil.trimToEmpty(column.getColumnType()).toUpperCase(Locale.ROOT);
        if (type.isEmpty()) {
            result.addError("字段 %s 缺少类型。可选：%s", name, String.join("/", allowedTypes));
            return;
        }
        if (!allowedTypes.contains(type)) {
            result.addError("字段 %s 的类型 %s 不在受支持的列表里。可选：%s",
                    name, column.getColumnType(), String.join("/", allowedTypes));
            return;
        }

        if ("STRING".equals(type)) {
            Integer length = column.getLength();
            if (length != null && (length <= 0 || length > constraints.getStringMaxLength())) {
                result.addError("字段 %s 的长度 %d 超出范围，STRING 长度须在 1~%d 之间",
                        name, length, constraints.getStringMaxLength());
            }
        }

        // 🔴 §5.5 规则 4：DECIMAL 须 scale < length 严格成立
        if ("DECIMAL".equals(type)) {
            Integer length = column.getLength();
            Integer scale = column.getScale();
            if (length != null && scale != null && scale >= length) {
                result.addError("字段 %s 的 DECIMAL 精度不合法：scale(%d) 必须小于 length(%d)。"
                        + "金额一般用 length=12、scale=2", name, scale, length);
            }
        }
    }

    // ==================== 流程回写列的类型（B18） ====================

    /**
     * 一批列里的流程回写列，类型必须与「平台往里写什么」对得上
     *
     * <p>改表校验器复用本方法（只喂它<b>新增与改了类型</b>的那些列，理由写在那边），
     * 故这里是 static、且不自己取约束 —— 两个调用方各自已经取过一份，不该取两次。
     */
    public static void validateFlowWritebackColumns(List<DataModelSaveReqDTO.ColumnDTO> columns,
                                                    DataModelApi.TableConstraintsRespDTO constraints,
                                                    AgentValidateResult result) {
        if (CollUtil.isEmpty(columns)) {
            return;
        }
        for (DataModelSaveReqDTO.ColumnDTO column : columns) {
            if (column == null) {
                continue;
            }
            validateFlowWritebackColumn(column, StrUtil.trimToEmpty(column.getColumnName()),
                    constraints, result);
        }
    }

    /**
     * 平台保留的那两列（{@code process_instance_id} / {@code process_status}），类型与长度都要合格
     *
     * <h3>🔴 复现的事故（第 6 轮实录，2026-10-18，方案 §10.13）</h3>
     * 用户说「这张表后面要挂审批流并按样品类型分流」，模型建表时<b>自己</b>补了两列：
     * {@code process_instance_id STRING(64)}（对）与 {@code process_status STRING(32)}（错 ——
     * 平台往里写的是 {@code BpmProcessInstanceStatusEnum} 的状态码，整数）。
     * 建表成功、表单发布成功、列表能查能录，一路绿灯；到要挂审批流那一刻，
     * BPM 的「在线表单」下拉把这张表单标成<b>不可用</b>，理由是
     * 「主表(biz_lab_sample_reg)的 process_status 列类型为 VARCHAR，需改为整数型」。
     * 而唯一的补齐入口（数据模型页「快速添加 → 流程字段」）只按<b>列名</b>判存在，
     * 报一句「相关字段已存在，无需重复添加」就收手 —— 用户被留在死路上，除了手写 ALTER 没有出路。
     *
     * <h3>为什么拦在建表参数这一侧，而不是等挂流程时再说</h3>
     * 类型错本身不违反任何 DDL 规则，后端建表校验<b>永远不会</b>拦它；它毁掉的是<b>之后</b>的一步，
     * 而那一步的报错离根因隔了整个「建表 → 存草稿 → 发布 → 建流程模型」四段路。
     * 建表又是不可逆的（DDL 回滚不掉），发现得晚就只能改表。
     * 本校验器的定位正是「后端不报错、但建出来的不是你要的」那一类（见类注释），
     * 这条是同类里代价最大的一个：它不报错，只是让一个功能<b>永久</b>不可用。
     *
     * <h3>⚠️ 判据一律从 {@code constraints.getFlowColumns()} 读，这里一个字面量都不写</h3>
     * 列名、合格类型、最小长度三样都在 infra 那一份里（{@code DataModelApiImpl#getTableConstraints}），
     * 而它又是从就绪校验（{@code FormDesignApiImpl#resolveUnavailableReason} 第 6/7 条）的常量推出来的。
     * 在这里抄一份的后果不是编译错误，是两边各自漂移 —— B17 记的就是两份类型词表漂移的代价。
     * 名单读不到（null / 空）时<b>整条沉默</b>：判据缺了一半还去报 error，
     * 报出来的是「类型不合格」却给不出「该改成什么」，模型改不动，白烧一轮。
     *
     * <h3>⚠️ 三处沉默条件</h3>
     * <ul>
     *   <li><b>列名空</b>：归 {@link #validateColumns} 那条「存在没有字段名的列」说，本条不叠。</li>
     *   <li><b>类型空</b>：归 {@link #validateColumnType} 的「缺少类型」说，同上。</li>
     *   <li><b>STRING 没写长度</b>：后端会静默补 {@code stringDefaultLength}（255），
     *       比 64 大，故不报 —— 与 infra 就绪校验里 {@code resolveColumnLength} 拿不到长度就放行同口径。
     *       那条静默改写本身由 {@link #validateSilentRewrites} 负责说出来。</li>
     * </ul>
     */
    private static void validateFlowWritebackColumn(DataModelSaveReqDTO.ColumnDTO column, String name,
                                                    DataModelApi.TableConstraintsRespDTO constraints,
                                                    AgentValidateResult result) {
        List<DataModelApi.FlowColumnConstraint> flowColumns =
                constraints == null ? null : constraints.getFlowColumns();
        if (CollUtil.isEmpty(flowColumns) || name.isEmpty()) {
            return;
        }
        DataModelApi.FlowColumnConstraint constraint = flowColumns.stream()
                .filter(item -> item != null && name.equalsIgnoreCase(StrUtil.trimToEmpty(item.getColumnName())))
                .findFirst().orElse(null);
        if (constraint == null || CollUtil.isEmpty(constraint.getAllowedTypes())) {
            return;
        }
        String type = StrUtil.trimToEmpty(column.getColumnType()).toUpperCase(Locale.ROOT);
        if (type.isEmpty()) {
            return;
        }
        Integer minLength = constraint.getMinLength();
        boolean typeOk = constraint.getAllowedTypes().stream().anyMatch(type::equalsIgnoreCase);
        if (!typeOk) {
            result.addError("字段 %s 是平台保留的流程回写列，类型 %s 不合格：只能是 %s%s。"
                            + "🔴 类型不对<b>不会报错</b> —— 建表、存表单草稿、发布全都会成功，"
                            + "直到要把这张表挂上审批流时才被拒（【流程模型】的表单设计那一步"
                            + "会把这张表单标成「不可用」），而建表不可逆，事后只能手写 ALTER 改类型",
                    name, column.getColumnType(), String.join("/", constraint.getAllowedTypes()),
                    minLength == null ? "" : "，且长度不小于 " + minLength);
            return;
        }
        // 类型对了才谈长度：类型本身就错时再说一句长度，等于同一个根因报两遍
        Integer length = column.getLength();
        if (minLength != null && length != null && length < minLength) {
            result.addError("字段 %s 是平台保留的流程回写列，长度 %d 不够：须不小于 %d，"
                            + "否则平台写入的值会被截断",
                    name, length, minLength);
        }
    }

    /**
     * §5.5 的 8 条「静默改写」
     *
     * 后端<b>不报错、保存成功、但建出来的不是你要的</b>。既然后端永远不会拦，
     * 就只能在这里说出来 —— 而且建表不可逆，发现得晚就只能改表。
     *
     * <p>这些是 warning 不是 error：它们不会让建表失败，
     * 强行拦下来反而会挡住一些合理的写法。让用户在 Plan 卡片上看到就够了。
     */
    private void validateSilentRewrites(DataModelSaveReqDTO.ColumnDTO column, String name,
                                        AgentValidateResult result) {
        String type = StrUtil.trimToEmpty(column.getColumnType()).toUpperCase(Locale.ROOT);

        // 不传 STRING 长度 → 静默用 255
        if ("STRING".equals(type) && column.getLength() == null) {
            result.addWarning("字段 %s 没写长度，会建成 varchar(255)", name);
        }
        // 不传 DECIMAL 长度/精度 → 静默用 (10,2)
        if ("DECIMAL".equals(type) && (column.getLength() == null || column.getScale() == null)) {
            result.addWarning("字段 %s 没写完整的长度与精度，会建成 decimal(10,2)", name);
        }
        // TEXT / LONG_TEXT / JSON 带默认值 → 静默丢弃
        // 🔴 JSON 是「不支持**字面量**默认值」：MySQL 8.0.13 起它只收 DEFAULT ('{}') 这种表达式形式，
        //    而 MysqlDdlBuilder 刻意不发表达式形式（理由写在那里的 appendDefaultValue 上）。
        //    所以三类的表现一致：填了、保存成功、建出来的列没有默认值。
        if (("TEXT".equals(type) || "LONG_TEXT".equals(type) || "JSON".equals(type))
                && StrUtil.isNotBlank(column.getDefaultValue())) {
            result.addWarning("字段 %s 是 %s，它的默认值会被静默丢弃（MySQL 不支持）", name, type);
        }
        // defaultValue = "" → 不生成 DEFAULT 子句（空串不等于「默认空串」）
        if (column.getDefaultValue() != null && column.getDefaultValue().isEmpty()) {
            result.addWarning("字段 %s 的默认值是空串，后端会当作「没设默认值」处理，"
                    + "不会生成 DEFAULT ''", name);
        }
        // 非主键字段 autoIncrement=true → 静默忽略
        if (Boolean.TRUE.equals(column.getAutoIncrement())
                && !Boolean.TRUE.equals(column.getPrimaryKey())) {
            result.addWarning("字段 %s 不是主键，autoIncrement 会被静默忽略", name);
        }
    }

    // ==================== 建议值的规整 ====================

    /**
     * 转成 snake_case，供「应改为 xxx」的建议使用
     *
     * 只处理最常见的一种不合规写法（驼峰）。处理不了的（含中文、含 $）
     * 由命名规范那条错误单独报 —— 那时给不出确定的建议，说清规则更诚实。
     */
    private static String toSnakeCase(String name) {
        StringBuilder sb = new StringBuilder(name.length() + 8);
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0 && sb.length() > 0 && sb.charAt(sb.length() - 1) != '_') {
                    sb.append('_');
                }
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 去掉索引名上已有的前缀/后缀，避免建议出 {@code uk_uk_xxx} 或 {@code idx_order_idx}
     */
    private static String stripIndexPrefix(String indexName) {
        String name = indexName;
        for (String prefix : List.of("uk_", "idx_", "index_")) {
            if (name.startsWith(prefix)) {
                name = name.substring(prefix.length());
            }
        }
        for (String suffix : List.of("_index", "_idx", "_uk")) {
            if (name.endsWith(suffix)) {
                name = name.substring(0, name.length() - suffix.length());
            }
        }
        return toSnakeCase(name);
    }

    // ==================== 索引 ====================

    private void validateIndexes(DataModelSaveReqDTO reqDTO, AgentValidateResult result) {
        // 🔴 建 name → type 的映射，只为下面那条「索引列不可建键」的检查。
        //    名字统一转小写：MySQL 字段名大小写不敏感，模型常把索引列写成与定义不同的大小写
        Map<String, String> columnTypes = new HashMap<>();
        for (DataModelSaveReqDTO.ColumnDTO column : CollUtil.emptyIfNull(reqDTO.getColumns())) {
            columnTypes.put(StrUtil.trimToEmpty(column.getColumnName()).toLowerCase(Locale.ROOT),
                    StrUtil.trimToEmpty(column.getColumnType()).toUpperCase(Locale.ROOT));
        }

        for (DataModelSaveReqDTO.IndexDTO index : CollUtil.emptyIfNull(reqDTO.getIndexes())) {
            String indexName = StrUtil.trimToEmpty(index.getIndexName());
            String indexType = StrUtil.trimToEmpty(index.getIndexType());
            if (indexName.isEmpty()) {
                result.addError("存在没有名字的索引");
                continue;
            }
            // 🔴 §5.5 静默改写第 5 条：indexType 只认精确大写 "UNIQUE"，
            //    写成 UNIQE / unique / FULLTEXT 一律按普通索引建，且不报错。
            //    唯一约束没建上，而界面显示得好好的 —— 直到某天出现重复数据
            if (!indexType.isEmpty() && !"UNIQUE".equals(indexType) && !"NORMAL".equals(indexType)) {
                if ("UNIQUE".equalsIgnoreCase(indexType)) {
                    result.addError("索引 %s 的类型写成了 %s，必须是精确大写的 UNIQUE，"
                            + "否则会被当作普通索引建出来且不报错", indexName, indexType);
                } else {
                    result.addError("索引 %s 的类型 %s 不受支持，只能是 UNIQUE 或 NORMAL",
                            indexName, indexType);
                }
            }
            // 命名建议要按用户<b>想建什么</b>来给，而不是按「写错了所以当普通索引」来给。
            // 把 indexType=unique（小写）的索引建议改成 idx_ 开头，等于顺着他的笔误
            // 把唯一约束彻底弄没了 —— 而唯一约束没建上的表现是「一切正常」，
            // 直到某天出现重复数据
            boolean unique = "UNIQUE".equalsIgnoreCase(indexType);
            String expectedPrefix = unique ? "uk_" : "idx_";
            if (!indexName.startsWith(expectedPrefix)) {
                result.addError("索引 %s 命名不合规，%s索引须以 %s 开头，应改为 %s",
                        indexName, unique ? "唯一" : "普通", expectedPrefix,
                        expectedPrefix + stripIndexPrefix(indexName));
            }
            if (CollUtil.isEmpty(index.getColumnNames())) {
                result.addError("索引 %s 没有指定字段", indexName);
            }
            // 🔴 TEXT / LONG_TEXT / JSON 不能做索引列，但两者报的错不一样（真库实测，见 infra 的
            //    DataModelMysqlIntegrationTest#testJsonColumnEndToEnd）：TEXT/LONG_TEXT 是 1170
            //    「缺前缀长度」，JSON 是 3152「只能靠生成列 + JSON path」，前缀长度对 JSON 无效。
            //    拦在这里而不是让 MysqlDdlBuilder 抛，理由与本文件 §5.5 规则 1 那条相同：
            //    DdlExecutor 只取 ex.getMessage()，真放过去模型拿到的是 Spring 那句
            //    「bad SQL grammar [SQL]」—— 连类型名都不含，而这一条直接告诉它改法。
            //    ⚠️ 措辞里刻意**不提**「用生成列」：数据模型框架压根没有生成列
            //    （TableColumnModel 无表达式字段、MysqlDdlBuilder 从不生成 GENERATED ALWAYS AS），
            //    提了只会把模型引向一个平台建不出来的设计。
            //    列名在 columnTypes 里查不到时不报错 —— 「索引列不存在」由 infra 侧
            //    DATA_MODEL_INDEX_COLUMN_INVALID 负责，这里不重复判、也不抢它的措辞
            for (String columnName : CollUtil.emptyIfNull(index.getColumnNames())) {
                String type = columnTypes.get(StrUtil.trimToEmpty(columnName).toLowerCase(Locale.ROOT));
                if ("TEXT".equals(type) || "LONG_TEXT".equals(type) || "JSON".equals(type)) {
                    result.addError("索引 %s 的列 %s 是 %s 类型，不能建索引（MySQL 错误 %s）。"
                                    + "要按它查就另建一个 STRING/INT/LONG/DATETIME 的列，把要检索的值抽到那一列上，"
                                    + "再对那一列建索引",
                            indexName, columnName, type, "JSON".equals(type) ? "3152" : "1170");
                }
            }
        }
    }

}
