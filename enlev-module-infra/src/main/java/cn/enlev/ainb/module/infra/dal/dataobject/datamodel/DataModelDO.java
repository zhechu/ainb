package cn.enlev.ainb.module.infra.dal.dataobject.datamodel;

import cn.enlev.ainb.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 数据模型 DO
 *
 * 用于登记「由数据模型模块管理的表」。表结构本身不存在这里，而是实时读取
 * information_schema，这样避免元数据与真实表结构不一致。
 * 本表的核心作用有三个：
 * 1. 作为 SQL 安全校验的白名单来源，见 SqlSafeGuard
 * 2. 承载模型的业务信息（模型名称、备注）
 * 3. 承载 AI 产物的治理信息（来源、是否被人工接管、结构指纹、乐观锁版本）
 *
 * @author 走进新时代
 */
@TableName("infra_data_model")
@KeySequence("infra_data_model_seq") // 用于 Oracle、PostgreSQL、Kingbase、DB2、H2 数据库的主键自增。如果是 MySQL 等数据库，可不写。
@Data
@EqualsAndHashCode(callSuper = true)
public class DataModelDO extends BaseDO {

    /**
     * 编号
     */
    private Long id;
    /**
     * 数据源配置的编号
     *
     * 关联 {@link cn.enlev.ainb.module.infra.dal.dataobject.db.DataSourceConfigDO#getId()}
     */
    private Long dataSourceConfigId;
    /**
     * 表名称
     */
    private String tableName;
    /**
     * 表描述
     */
    private String tableComment;
    /**
     * 模型名称
     */
    private String modelName;
    /**
     * 备注
     */
    private String remark;
    /**
     * 列表页配置 JSON：展示列、查询条件、默认排序
     *
     * 用 JSON 而非独立表：这些配置整体读写、不按字段查询，JSON 列维护成本更低
     */
    private String listConfig;
    /**
     * 表单配置 JSON：字段顺序、控件类型、校验规则
     */
    private String formConfig;
    /**
     * 子表配置 JSON：子表名 + 外键字段
     */
    private String subTableConfig;
    /**
     * 列的业务元数据 JSON：列名 → {dictType, refTable, refColumn}
     *
     * 与上面三个配置同一种做法（整体读写、不按字段查询）。
     * 存在这里而不是物理表：这些是<b>业务语义</b>不是存储结构，且不建物理外键。
     * 详见 {@link cn.enlev.ainb.module.infra.framework.datamodel.core.ColumnMetaStore}
     */
    private String columnMeta;

    // ========== AI 产物治理（B4-1） ==========

    /**
     * 来源：这行是人建的、AI 建的、还是导入存量表登记进来的
     *
     * 枚举 {@link cn.enlev.ainb.module.infra.enums.artifact.ArtifactSourceEnum}。
     *
     * 🔴 <b>不接受调用方传入</b> —— {@code DataModelSaveReqVO} 与 api 包的 DTO 里都没有这个字段，
     *    值由 Service 按「走的哪个入口」写死。理由见那个枚举的类注释。
     */
    private String source;
    /**
     * 是否被人工手改过
     *
     * AI 建的模型后来被人在界面上改动过 ⟹ true。治理动作（批量清理、重新生成）
     * 只应作用于 {@code source = AI} 且本列为 false 的行，见
     * {@link cn.enlev.ainb.module.infra.enums.artifact.ArtifactSourceEnum#isUntouchedAiArtifact}。
     *
     * <p>🔴 <b>由服务端按入口判定，不接受任何一方传入</b>：VO 与 api 包的 DTO 里都没有这个字段。
     * 不放给前端传，是因为「忘了传」的表现是这一列恒为假 —— 治理逻辑照旧跑，
     * 只是永远得出「这还是 AI 的原生产物、可以覆盖」的结论，而那是错的。
     * 不放给 AI 传更不用说：让它能给自己盖章，这一列就恒为假。
     *
     * <p>🔴 <b>单调</b>：一旦为真就不再回退，理由同 {@code FormDesignDO} 上那一列。
     */
    private Boolean manualEditedFlag;
    /**
     * 上次 DDL 成功时的表结构指纹（sha256，64 位十六进制）
     *
     * 由 {@link cn.enlev.ainb.module.infra.framework.datamodel.core.SchemaFingerprint} 计算。
     * 改表在<b>执行 DDL 之前</b>用它复核真实结构，见
     * {@code DataModelServiceImpl#validateSchemaNotDrifted}。
     *
     * <p>🔴 {@code null} 的语义是「尚未记录基线」，与「记录过」必须能区分 ——
     *    所以这一列可空且不设默认值。存量模型第一次改表时它是 null，
     *    那种情况要放行并顺手记下基线；若把 null 当成「结构对不上」，
     *    加这一列之前建的<b>每一张表都会从此改不动</b>，而报错说的还是「结构被人改过」。
     */
    private String schemaFingerprint;
    /**
     * 乐观锁版本号
     *
     * <p>🔴 三件事必须同时成立，少一件这一列就是装饰：
     * <ol>
     *   <li>{@code @Version} 注解（就是下面这个）</li>
     *   <li>{@code OptimisticLockerInnerInterceptor} 已注册 —— 本仓此前<b>从未注册过</b>，
     *       见 {@code EnlevMybatisAutoConfiguration}</li>
     *   <li><b>每条</b> update 路径都把当前 version 带上、并检查影响行数</li>
     * </ol>
     * 第 3 条是真活儿：MP 的拦截器在版本冲突时<b>不抛异常</b>，只是让 UPDATE 命中 0 行。
     * 不检查行数的话，「并发覆盖」的表现是接口返回成功、而先提交的那次改动被静默丢弃 ——
     * 与本模块最常见的缺陷同型（配置保存成功但不生效且无提示）。
     *
     * <p>⚠️ 拦截器只对 {@code updateById(entity)} 与 {@code update(entity, wrapper)}
     *    且 entity 非 null 的路径生效（3.5.16 的 {@code doOptimisticLocker}：
     *    取不到 {@code et} 时只在 {@code wrapperMode} 下才动手，而默认构造器把它置为 false）。
     *    本表的 update 路径只有 {@code DataModelServiceImpl#updateDataModel} 一处；
     *    表单那边共 9 条写路径（7 处 update），其中 2 处拦截器结构上够不着，是手写的版本条件。
     *
     * <p>⚠️ 与 {@code SystemColumnProfileEnum.LEGACY} 给<b>业务表</b>补的那个 version 列
     *    <b>不是一回事</b>：业务表的增删改查走 {@code DynamicSqlExecutor} 手拼 SQL，
     *    完全绕过 MyBatis 拦截器，所以那个列上 {@code @Version} 根本不生效 ——
     *    那条注释是对的，别去「修」它。本列所在的 infra_data_model 走的是 MyBatis Mapper。
     */
    @Version
    private Integer version;

}
