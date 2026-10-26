package cn.enlev.ainb.module.infra.dal.dataobject.formdesign;

import cn.enlev.ainb.framework.mybatis.core.dataobject.BaseDO;
import cn.enlev.ainb.framework.tenant.core.aop.TenantIgnore;
import cn.enlev.ainb.module.infra.enums.formdesign.FormDesignStatusEnum;
import cn.enlev.ainb.module.infra.enums.formdesign.FormTypeEnum;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 表单设计定义 DO
 *
 * 本模块是独立的低代码表单模块，与 bpm_form（流程表单）、infra/build（表单构建）
 * 完全隔离，不复用任何表与服务。
 *
 * 业务数据存储策略：【只绑定已有表】—— 不建表也不改表，表单绑定到已存在的物理表上。
 * 这规避了「发布时自动建表、改字段却不自动 ALTER」导致数据静默丢失的经典缺陷，
 * 代价是表结构由 DBA 掌控，因此发布时必须真去核实元数据（见 FormDesignService#publish）。
 *
 * @author 走进新时代
 */
@TableName("infra_form_design")
@KeySequence("infra_form_design_seq") // 用于 Oracle、PostgreSQL、Kingbase、DB2、H2 数据库的主键自增。如果是 MySQL 等数据库，可不写。
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@TenantIgnore // 公共功能，不分租户。注意：必须与「建表脚本不带 tenant_id 列」同时成立，
              // 仅继承 BaseDO 时 TenantDatabaseInterceptor 仍会拦截
public class FormDesignDO extends BaseDO {

    /**
     * 编号
     */
    @TableId
    private Long id;
    /**
     * 表单名称
     */
    private String name;
    /**
     * 表单编码
     *
     * 运行时与菜单通过它引用本表单，因此加了唯一索引且不允许发布后修改。
     */
    private String code;
    /**
     * 分类
     *
     * 数据字典 {@link cn.enlev.ainb.module.infra.enums.DictTypeConstants#INFRA_FORM_DESIGN_CATEGORY}
     */
    private String category;
    /**
     * 表单类型
     *
     * 枚举 {@link FormTypeEnum}
     */
    private Integer formType;
    /**
     * 状态
     *
     * 枚举 {@link FormDesignStatusEnum}：0-草稿 1-已发布 2-已发布有改动。
     * 「已发布有改动」用于提示用户草稿与线上版本已不一致，需重新发布。
     */
    private Integer status;

    // ========== 表单设计产物 ==========

    /**
     * 表单配置 JSON
     *
     * 沿用项目既有的 form-create 约定，与 bpm_form 一致：存 designer.getOption() 的序列化结果。
     * 前端可直接复用 src/utils/formCreate.ts 的 encodeConf / decodeConf。
     */
    private String formConf;
    /**
     * 表单字段规则 JSON 数组
     *
     * 存 designer.getRule() 逐条序列化后的字符串数组。
     *
     * 注意：字段与物理列的映射【不】写在这里 —— 那会污染第三方结构且升级易冲突，
     * 映射关系统一放在 {@link #tableBinding} 的 fieldMapping 中，以 rule 的 field 作键关联。
     */
    private String formFields;

    // ========== 列表设计产物 ==========

    /**
     * 列表配置 JSON
     *
     * 包含列配置、查询条件、分页、按钮、树形、左侧树、分组汇总、复杂表头、样式脚本。
     * 仅 formType = 表单+列表 时有意义。
     */
    private String listConf;

    // ========== 数据绑定 ==========

    /**
     * 数据源编号
     *
     * 0 表示主库；其余复用 infra_data_source_config 的配置。
     * 跨数据源时无法用同一事务，因此主表与子表不在同一数据源会在发布校验时被拒绝。
     */
    private Long dataSourceConfigId;
    /**
     * 主表名
     */
    private String mainTable;
    /**
     * 主表主键列名
     */
    private String mainPk;
    /**
     * 表绑定配置 JSON
     *
     * 结构见 {@link cn.enlev.ainb.module.infra.framework.formdesign.core.binding.TableBinding}：
     * 字段映射、主键策略、逻辑删除、系统字段填充、从表关联（只读）、子表配置（一对多明细）。
     */
    private String tableBinding;

    /**
     * 表单产物的 Schema 版本号
     *
     * formConf / formFields / listConf / tableBinding 这组 JSON 结构的版本。
     * 结构发生破坏性变更时递增，供迁移器判断某张存量表单该怎么升级。
     * 当前值见 {@link cn.enlev.ainb.module.infra.framework.formdesign.core.schema.FormDesignSchema#CURRENT_VERSION}。
     *
     * 之所以是独立的列而不是塞进 JSON：需要能直接查「还有多少张表单停留在旧版本」
     * —— 那决定了迁移能否一次性做完。另外 formConf 是设计器 getOption() 的产物、
     * formFields 是逐条序列化的规则数组（没有顶层对象可挂），往里塞自定义键并不可靠。
     */
    private Integer schemaVersion;

    // ========== 联动与扩展 ==========

    /**
     * 联动规则 JSON 数组
     *
     * 声明式规则，结构见 {@link cn.enlev.ainb.module.infra.framework.formdesign.core.logic.LogicRule}。
     * 这是默认的联动路径 —— 规则是 JSON，能静态检查、能做版本 diff、能在服务端复算。
     * 提交时服务端会用同一套规则重算「必填/只读」，据此丢弃只读字段的值、拒绝必填空值。
     */
    private String logicRules;

    /**
     * 跨字段校验规则 JSON 数组
     *
     * 结构见 {@link cn.enlev.ainb.module.infra.framework.formdesign.core.validation.CrossFieldRule}。
     * 语义是「when 满足时 must 必须也满足」，用来表达「结束日期 ≥ 开始日期」
     * 「A 填了 B 必填」这类约束。
     *
     * 与 {@link #logicRules} 分开存而不是混进同一个数组：联动规则的动作改变字段状态，
     * 是幂等的、参与不动点迭代；校验规则的结果是「拒绝这次提交」，两者放在一起
     * 会让「规则跑完之后表单变成什么样」不再可推理。
     *
     * 字段【自身】的格式校验（正则）不在这里 —— 它属于单个控件，
     * 存在控件的 props.validateRules 里，随 formFields 一起走。
     */
    private String validateRules;
    /**
     * 自定义按钮 JSON 数组
     *
     * 运行时的按钮此前固定为四个（新增/编辑/删除/导出），业务动作无处可放。
     * 每个按钮：{key,label,icon,position,action,visibleWhen,permission}。
     *
     * 单开一列而不是塞进 listConf：自定义按钮有三个位置，其中「详情」
     * 根本不在列表上，塞进那份「列表怎么显示」的配置里名不副实。
     */
    private String customButtons;
    /**
     * 详情页页签 JSON 数组
     *
     * 详情页此前只有表单本体。每个页签：{key,label,type}。
     * 单开一列的理由同 customButtons：listConf 是「列表怎么显示」，页签不在列表上。
     */
    private String detailTabs;
    /**
     * 打印模板 HTML
     *
     * 一段带 {@code {字段}} 占位符的 HTML，运行时在详情页/列表行点「打印」时
     * 取记录插值后打开打印窗口。
     *
     * 存 HTML 而不是存一个指向模板表的 ID：bpm 那边的打印模板就是这个形态
     * （没有表、没有服务），而「一张表单一套模板」目前够用。真出现「一张表单
     * 多套模板」再拆表，那时这一列是清单里的第一条 —— 是加表，不是改语义。
     *
     * 落库前过一遍 {@code RichContentGuard} 的白名单清洗：模板是设计者写的 HTML，
     * 与富文本字段同一个理由。
     */
    private String printTemplate;

    /**
     * 业务逻辑扩展模式
     *
     * 枚举 {@link cn.enlev.ainb.module.infra.enums.formdesign.FormExtensionModeEnum}：
     * 0-无 1-前端脚本 2-服务端扩展点。三者互斥，同一表单只有一种生效。
     *
     * 新建表单默认「前端脚本」。切换模式时不删除另一模式的既有配置，只是不执行，
     * 这样过渡期可以来回切换做对比验证。
     */
    private Integer extensionMode;

    /**
     * 前端脚本 JSON
     *
     * 仅 extensionMode = 前端脚本 时执行。结构是「钩子名 → 脚本文本」，
     * 含表单级钩子（onLoad / onFieldChange / beforeSubmit / afterSubmit）
     * 与控件级钩子。
     *
     * 脚本在受限沙箱里执行：没有任何后端调用能力、取不到 window 等全局对象。
     * 需要后端参与的逻辑一律走服务端扩展点。
     */
    private String formScript;

    /**
     * beforeSubmit 钩子超时后的动作
     *
     * 枚举 {@link cn.enlev.ainb.module.infra.enums.formdesign.SubmitHookTimeoutActionEnum}：
     * 1-阻断 2-放行。默认阻断 —— 超时意味着校验没跑完，放行等于静默跳过校验。
     */
    private Integer submitHookTimeoutAction;

    // ========== 发布相关 ==========

    /**
     * 当前线上版本号
     *
     * 0 表示从未发布。回滚时此值不变 —— 回滚只改草稿，不改线上版本。
     */
    private Integer publishedVersion;
    /**
     * 发布生成的菜单编号
     *
     * 下线或删除时据此回收 system_menu 记录。用户可能手工删过该菜单，回收时需容错。
     *
     * <p>本列同时是两处判据：<b>能不能直接删</b>（{@code deleteFormDesign} 见它非空就拒绝，
     * 让用户先走下线）与<b>是不是首次发布</b>（{@code publish} 用 {@code menuId == null}
     * 决定要不要让前端建菜单）。所以它悬挂着一个已删菜单的编号时，这张表单既删不掉、
     * 重新发布也建不出菜单来。
     *
     * <p>🔴 要把它置 NULL，<b>必须</b>用 {@code LambdaUpdateWrapper#set} 显式写，
     * {@code setMenuId(null)} + {@code updateById} 是「这一列别动」而不是「清空」——
     * MP 的字段更新策略默认 NOT_NULL，为 null 的列整个不进 SET 子句，且不报任何错。
     * 实测就是这么留下过 12 行悬挂数据的，回归守卫见 {@code FormDesignUnpublishTest}。
     */
    private Long menuId;
    /**
     * 发布生成的 App 端导航节点编号（system_nav_node.id）
     *
     * NULL 表示从未发布到 App 端。下线时据此交还前端去删导航节点，
     * 重新发布时据此判断是「补建」还是「更新已有节点」。
     *
     * <p>🔴 与 {@code menuId} 同一条红线：要把它置 NULL，<b>必须</b>用
     * {@code LambdaUpdateWrapper#set} 显式写，{@code setAppNavNodeId(null)} +
     * {@code updateById} 是「这一列别动」而不是「清空」（MP 字段更新策略默认 NOT_NULL，
     * 为 null 的列整个不进 SET 子句，且不报任何错）。回归守卫见 {@code FormDesignUnpublishTest}。
     */
    private Long appNavNodeId;
    /**
     * 排序
     */
    private Integer sort;
    /**
     * 备注
     */
    private String remark;

    // ========== AI 产物治理（B4-1） ==========

    /**
     * 来源：这行是人建的、AI 建的、还是导入登记进来的
     *
     * 枚举 {@link cn.enlev.ainb.module.infra.enums.artifact.ArtifactSourceEnum}。
     * 本表没有「导入已有表单」这个入口，故 IMPORT 取值目前用不上 ——
     * 刻意与 {@code infra_data_model.source} 共用同一个取值集，理由见那个枚举的类注释。
     *
     * 🔴 <b>不接受调用方传入</b>：{@code FormDesignSaveReqVO} 与 api 包的 DTO 里都没有这个字段，
     *    值由 Service 按「走的哪个入口」写死。
     */
    private String source;
    /**
     * 是否被人工手改过
     *
     * AI 建的表单后来被人在设计器里改动过 ⟹ true。
     *
     * <p>🔴 <b>由服务端按入口判定，不接受任何一方传入</b>：VO 与 api 包的 DTO 里都没有这个字段。
     * 不放给前端传，是因为「忘了传」的表现是这一列恒为假 —— 治理逻辑照旧跑，
     * 只是永远得出「这还是 AI 的原生产物、可以覆盖」的结论，而那是错的。
     * 不放给 AI 传更不用说：让它能给自己盖章，这一列就恒为假。
     *
     * <p>🔴 <b>单调</b>：一旦为真就不再回退。回退的后果是「人改过的 AI 产物」
     * 重新变回「AI 可以随便覆盖的东西」，下一次覆盖掉的就是人工的成果，且没有任何提示。
     */
    private Boolean manualEditedFlag;
    /**
     * 绑定的<b>主表</b>结构指纹（sha256，64 位十六进制）
     *
     * 由 {@link cn.enlev.ainb.module.infra.framework.datamodel.core.SchemaFingerprint} 计算。
     *
     * <p>⚠️ 与 {@link #schemaVersion} <b>完全不是一回事</b>，两个名字长得像，别混：
     * <ul>
     *   <li>{@code schemaVersion} = <b>本行 JSON 产物</b>（formConf/formFields/listConf/tableBinding）
     *       的结构版本，用来判断这张存量表单该怎么迁移</li>
     *   <li>{@code schemaFingerprint} = <b>它绑定的那张物理表</b>的结构摘要</li>
     * </ul>
     *
     * <p>🔴 <b>本轮（迭代七第 09 轮）只加了列，没有写入方。</b>
     * 表单侧刻意<b>不做</b>指纹复核，本列因此恒为 {@code null}，理由写在
     * {@code ErrorCodeConstants} 里「刻意没有 FORM_DESIGN_SCHEMA_DRIFT」那段注释，摘要如下：
     * <ol>
     *   <li>B4-2 的闸是「<b>DDL 执行前</b>复核」，而表单设计只绑定已有表、不建表也不改表，
     *       这条路径上没有 DDL，没有「已经执行了一半、回不去」的代价要防</li>
     *   <li>{@code FormDesignPublishValidator} 已经逐列核过「映射的列必须在主表中存在」，
     *       报出来的是「哪个字段映射的哪一列没了」—— 比「两个哈希不相等」可操作得多</li>
     *   <li>哈希闸会对与本表单<b>无关</b>的结构变更也报警（DBA 把某个没用到的列加宽了），
     *       于是发布被一条无从修起的错误卡住。假阳性 + 无补救动作 = 比不拦更坏</li>
     *   <li>真要写入，得对 MP 代码生成器的 {@code TableField} 再写一份规范化 ——
     *       与 {@code SchemaFingerprint} 吃的 {@code TableColumnModel} 不是同一个类型。
     *       两份指纹定义各自漂移，正是本仓反复吃过的那类亏</li>
     * </ol>
     * 列还是留着：B4-1 要求两张表同构，而且它是<b>事后取证</b>的落点 ——
     * 将来若要填，填的就是「这张表单发布时主表长什么样」。
     */
    private String schemaFingerprint;
    /**
     * 乐观锁版本号
     *
     * <p>⚠️ 与 {@link #publishedVersion} <b>不是一回事</b>：后者是「线上发布到第几版」，
     *    回滚时不变（回滚只改草稿、不改线上版本），下线时归 0；本列是并发控制用的，
     *    每一次 UPDATE 都自增。
     *
     * <p>🔴 三件事必须同时成立，少一件这一列就是装饰：
     * <ol>
     *   <li>{@code @Version} 注解（就是下面这个）</li>
     *   <li>{@code OptimisticLockerInnerInterceptor} 已注册 —— 本仓此前<b>从未注册过</b></li>
     *   <li><b>每条</b> update 路径都带上当前 version、并检查影响行数</li>
     * </ol>
     * 第 3 条是真活儿，本表有 <b>9 条</b>写路径（2 处 insert 不需要，7 处 update 需要），
     * 其中 2 条 MP 的拦截器<b>结构上够不着</b>（entity 为 null ⟹ 拦截器无处读 version）：
     * {@code FormDesignMapper#updateToDelete}（手写 {@code @Update} SQL，版本条件是手写的）
     * 与 {@code FormDesignVersionServiceImpl#rollbackClearableColumns}（{@code update(null, wrapper)}，
     * <b>刻意不带</b>版本条件 —— 它与同事务里前一次带锁的 {@code updateById} 组成
     * 「先 CAS 再补写」，排他锁持锁到提交，中间没有别的事务能改这一行。
     * 那条顺序依赖写在该方法的注释里，挪动调用顺序前必读）。
     *
     * <p>🔴 拦截器在冲突时<b>不抛异常</b>，只是让 UPDATE 命中 0 行 ——
     *    所以每条路径都要自己检查行数并抛 {@code FORM_DESIGN_RECORD_VERSION_CONFLICT}。
     *    不检查的话表现是「接口返回成功、先提交的那次改动被静默丢弃」。
     *
     * <p>⚠️ 别与 {@code FORM_DESIGN_VERSION_CONFLICT}(1_001_204_067) 搞混：
     *    那个说的是<b>表单绑定的业务数据行</b>的乐观锁（{@code TableBinding} 里的 versionColumn），
     *    与本列无关。两者同名的话，用户看到「版本冲突」根本分不清是表单定义撞了还是单据数据撞了。
     */
    @Version
    private Integer version;

}
