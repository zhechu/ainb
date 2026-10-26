package cn.enlev.ainb.module.infra.api.datamodel;

import cn.enlev.ainb.module.infra.api.datamodel.dto.DataModelStructureRespDTO;
import cn.enlev.ainb.module.infra.api.datamodel.dto.DataModelSaveReqDTO;

import java.util.List;

/**
 * 数据模型 API 接口
 *
 * 供 agent 模块（AI 助手）调用。
 *
 * <h3>为什么是「薄封装」而不是另起一套</h3>
 * 本接口的每个方法都直接委托给 {@code DataModelService} 的同名方法，
 * <b>不复制任何业务逻辑</b>。否则会出现「界面走 Controller、AI 走 api，两条路径行为不一致」——
 * 而这类不一致正是本项目最难反查的缺陷形态：两边都「能用」，只是结果不同。
 *
 * <h3>🔴 调用方必须知道的三件事</h3>
 * <ol>
 *   <li><b>建表即刻执行 DDL，MySQL 的 DDL 无法回滚。</b>
 *       没有草稿态，调 {@link #createDataModel} 就是真的 CREATE TABLE。
 *       所以它必须经过用户确认，不能放进自动循环</li>
 *   <li><b>改表是全量同步语义。</b>{@link #updateDataModel} 提交的列表里没有的列
 *       一律被判为删除。正确姿势固定为：先 {@link #getStructure} 取回全量列
 *       → 在其上增删改 → 整体回传。漏传系统列会被硬拒绝</li>
 *   <li><b>建表规范只作用于新建。</b>命名、前缀、注释质量、配额都只在建表时校验，
 *       改表只管本次新增的部分 —— 否则存量表（无 biz_ 前缀、驼峰列名）会立刻改不动</li>
 * </ol>
 *
 * 完整规范见 {@code docs/数据建模规范.md}，它同时是 AI 建表 prompt 的来源。
 *
 * @author 走进新时代
 */
public interface DataModelApi {

    /**
     * 建表
     *
     * ⚠️ <b>立刻执行 CREATE TABLE，不可回滚。</b>
     *
     * @param reqDTO 建表请求。只传业务列，系统列由注入器按 systemColumnProfile 补齐
     * @return 模型编号
     */
    Long createDataModel(DataModelSaveReqDTO reqDTO);

    /**
     * 改表
     *
     * ⚠️ <b>全量同步语义</b>：必须传回全量列（含系统列），否则缺失的列会被删除。
     * 多条 ALTER 顺序执行、失败即停，已执行的部分不会回滚。
     *
     * <p>🔴 <b>{@code version} 与 {@code schemaFingerprint} 必传</b>，两个都取自
     * {@link #getStructure} 的返回值、<b>原样回传</b>：
     * <ul>
     *   <li>version 缺失 → {@code DATA_MODEL_RECORD_VERSION_REQUIRED}（不是「跳过加锁」，是硬拒绝）；
     *       对不上 → {@code DATA_MODEL_RECORD_VERSION_CONFLICT}</li>
     *   <li>schemaFingerprint 对不上 → {@code DATA_MODEL_SCHEMA_DRIFT}，<b>不执行任何 DDL</b></li>
     * </ul>
     * 两道闸都排在 DDL 之前 —— 一旦开始执行就拦不住了。
     *
     * <p>🔴 <b>{@code confirmDropColumns} 不许由 AI 设置</b>，
     * {@code DataModelUpdateValidator} 会拦。理由与 {@code riskConfirmed} 同一条：
     * 用户在计划卡片上点的是「同意执行这个计划」，不是「同意删这些列」。
     *
     * @param reqDTO 改表请求，id 必传
     */
    void updateDataModel(DataModelSaveReqDTO reqDTO);

    /**
     * 获得表结构（字段 + 索引），实时读取真实表
     *
     * <b>改表前必须先调它</b>。返回的列带 {@code systemColumn} 标记，
     * 调用方据此知道哪些列必须原样回传。
     *
     * <p>🔴 同时返回改表所需的<b>两个基线</b>：{@code version}（登记行的乐观锁版本）与
     * {@code schemaFingerprint}（<b>实时</b>结构指纹）。本接口没有「按 id 读登记行」的孪生方法，
     * 所以这两个值只能从这里拿。两者都要在改表时原样回传。
     *
     * @param id 模型编号
     * @return 表结构
     */
    DataModelStructureRespDTO getStructure(Long id);

    /**
     * 预览待执行的 DDL，<b>不实际执行</b>
     *
     * 与 create/update 走同一套校验 —— 预览通过就等于保存能过。
     * AI 可以拿它当可行性判据。
     *
     * @param reqDTO 与建表/改表相同的请求；id 为空走 CREATE 预览，否则走 ALTER 预览
     * @return DDL 语句列表，按执行顺序排列
     */
    List<String> previewDdl(DataModelSaveReqDTO reqDTO);

    /**
     * 获得已登记的数据模型列表
     *
     * 🔴 AI 接到建表单需求时<b>必须先调它查有没有可复用的表</b>。
     * 表单设计只绑已存在的表、不建表，所以「用现成的表」这条路径全程无 DDL、完全可逆，
     * 与「建新表」的风险差一个数量级。
     *
     * @return 模型列表
     */
    List<DataModelSimpleRespDTO> getDataModelList();

    /**
     * 获得数据库里的物理表（含尚未登记进数据模型的）
     *
     * <h3>为什么需要它</h3>
     * {@link #getDataModelList()} 只返回<b>已登记</b>的模型。
     * 而库里往往有一批物理表存在、却没登记过 —— 对它们，改表工具用不了、
     * 结构也读不到。要让「能不建表就不建表」这条路走得通，
     * 先得能看见这些表。
     *
     * <h3>🔴 keyword 匹配表名<b>或</b>表注释</h3>
     * 两个条件是 OR，同一张表被两边都命中时只返回一次。
     *
     * <p>为什么必须是 OR：调用方是 LLM，它拿到的需求是中文的，
     * 于是按中文语义词搜表（「设备」「巡检」）。而表名是拉丁的（{@code biz_device_manage}），
     * 中文词只可能落在注释里。只按表名匹配 ⟹ 搜「设备」返回 0 条 ⟹
     * 模型判定「库里没有可复用的表」⟹ 去建新表（不可逆）。
     * <b>那正是本方法存在的理由所要避免的结局</b>，所以这个 OR 不是宽松，是必需。
     *
     * <p>⚠️ 与 Controller 侧的孪生出口 {@code /infra/data-model/list-database-table} <b>口径不同</b>：
     * 那边把 tableName / tableComment 分成两个参数、底层是 AND（导入表的对话框有两个独立输入框）。
     * 这里是单参数 OR。两者服务的调用方形态不同，不要相互套用。
     *
     * @param keyword 表名或表注释的模糊匹配，可为 null（null = 全库）
     * @return 物理表列表，每项含 tableName / tableComment / registered
     */
    List<PhysicalTableRespDTO> getPhysicalTableList(String keyword);

    /**
     * 获得<b>悬挂登记</b>：{@code infra_data_model} 里登记着、物理表却已不在
     *
     * <h3>🔴 为什么必须单独一个方法，而不是让 {@link #getPhysicalTableList} 顺带报</h3>
     * 那个方法遍历的论域是<b>物理表</b>：先读 INFORMATION_SCHEMA，
     * 再拿「已登记表名」当查找表给每行打 {@code registered} 标。
     * 悬挂登记<b>压根不在这个论域里</b> —— 它的物理表不存在，所以永远不会被遍历到。
     * 这不是漏写了一个分支，是<b>方向反了</b>：要看见它，
     * 必须遍历<b>登记</b>、拿物理表名当查找表。
     *
     * <p>{@code getPhysicalTableList} 的返回类型也塞不下第二个方向的结论，
     * 而改它的返回类型会动到 4 个调用点里的 3 个 ——
     * 那 3 个只问「这张表在不在」，对悬挂方向毫无兴趣。
     *
     * <h3>为什么不带 keyword 参数</h3>
     * 差集只有在<b>两侧同一个论域</b>上才算得对。带关键字就得把登记侧也按同一规则过滤，
     * 而登记侧有 modelName 这个物理侧没有的字段 —— 按它匹配会凭空造出一批假悬挂。
     * 所以这里恒做<b>全库对账</b>，展示层的裁剪交给调用方。
     *
     * <p>实测口径（{@code _evidence/probe107.txt}）：全库 248 张物理表、297 条登记，
     * 悬挂 80 条，全部是 {@code hrm_}(50) / {@code fms_}(30) ——
     * 属根 pom 里注释掉、不参与编译的模块，本来就没表，是<b>基线噪声不是缺陷</b>，
     * 删掉反而会在那模块启用时坏事。⚠️ 所以「本方法返回空」<b>不等于</b>「没有悬挂」，
     * 只等于「没有低代码自家表族的悬挂」；调用方必须把滤掉的数量如实报出来。
     *
     * @return 悬挂登记列表，每项含 id / tableName / modelName / tableComment
     */
    List<DanglingRegistrationRespDTO> getDanglingRegistrationList();

    /**
     * 悬挂登记
     */
    @lombok.Data
    class DanglingRegistrationRespDTO {

        /**
         * 登记行的 id
         *
         * 清理要走 {@code DELETE /infra/data-model/delete?id=&dropTable=false}，
         * 所以这个 id 是给出去让人动手的，不能省。
         */
        private Long id;
        private String tableName;
        private String modelName;
        private String tableComment;

    }

    /**
     * 把已存在的物理表登记进数据模型
     *
     * ⚠️ 这个动作<b>不执行任何 DDL</b>，只是把表的元数据读进 {@code infra_data_model}。
     * 表本身一个字节都不会变。
     *
     * @param tableNames 表名列表
     */
    void importTables(List<String> tableNames);

    /**
     * 物理表
     */
    @lombok.Data
    class PhysicalTableRespDTO {

        private String tableName;
        private String tableComment;
        /**
         * 是否已登记进数据模型
         *
         * 没登记的表：改表工具改不了它，{@code datamodel_structure} 也读不到。
         */
        private Boolean registered;

    }

    /**
     * 获得受支持的列类型
     *
     * 类型是受控枚举而非自由填写，AI 只能从这个集合里选。
     *
     * @return 类型列表
     */
    List<ColumnTypeRespDTO> getColumnTypeList();

    /**
     * 获得建表约束
     *
     * <h3>为什么要把这些暴露出来，而不是让调用方自己写一份</h3>
     * agent 的生成后校验器（方案 §5.5）要在<b>提交之前</b>拦住不合规的建表参数 ——
     * 拦不住的后果不是报错，而是模型撞一次错误、改一次、再撞，
     * 每一轮都是一次真实的计费。
     *
     * <p>但校验器若自己抄一份「表名要 biz_ 开头、STRING 最长 16383」，
     * 就会和这边的实现<b>各自漂移</b>：配置项改了 agent 不知道，
     * 表现为「界面上能建的表，AI 说不合法」或者反过来 ——
     * 后者更糟，它意味着校验器放行了后端会拒绝的东西，白烧一轮 token。
     *
     * <p>所以规则只有一份，在这边；agent 读过去用。
     *
     * @return 约束
     */
    TableConstraintsRespDTO getTableConstraints();

    /**
     * 数据模型的简要信息
     */
    @lombok.Data
    class DataModelSimpleRespDTO {
        private Long id;
        private String tableName;
        private String tableComment;
        private String modelName;
    }

    /**
     * 列类型选项
     */
    @lombok.Data
    class ColumnTypeRespDTO {
        private String type;
        private String mysqlType;
        private String javaType;
        private Boolean needLength;
        private Boolean needScale;
        private String description;
    }

    /**
     * 建表约束
     */
    @lombok.Data
    class TableConstraintsRespDTO {

        /**
         * 业务表名必须带的前缀，默认 {@code biz_}
         *
         * ⚠️ 可配（{@code enlev.data-model.table-prefix}），所以不能写死在调用方。
         */
        private String tablePrefix;

        /**
         * 标识符（表名、列名、索引名）的合法形态
         */
        private String identifierPattern;

        /**
         * STRING 的长度上限
         */
        private Integer stringMaxLength;

        /**
         * STRING 不传长度时的默认值
         *
         * 这是一条「静默改写」：不传不会报错，会悄悄用这个值。
         * 暴露出来是为了让 AI 能主动规避，而不是建完表才发现列宽不对。
         */
        private Integer stringDefaultLength;

        /**
         * 系统列名，建表时<b>不应</b>由业务列占用
         *
         * 自定义与系统列同名的列时，注入器会<b>让位、不覆盖也不报错</b>，
         * 后续动态 CRUD 的租户拼接行为异常 —— 典型的静默失效。
         */
        private List<String> systemColumnNames;

        /**
         * 流程回写列的类型要求（名字被平台保留的那两列）
         *
         * <h3>为什么这两列的<b>类型</b>也要交出去，而不只是列名</h3>
         * 它们是「建表时看着完全合法、之后才发现废了」的那一类：列名对、类型错，
         * DDL 照建、表单照发，一路绿灯，直到有人要把这张表挂上审批流 ——
         * 那一刻 {@code FormDesignApiImpl#resolveUnavailableReason} 的第 6/7 条才开口
         * （「process_status 列类型为 VARCHAR，需改为整数型」），而唯一的补齐入口
         * （数据模型页的「快速添加 → 流程字段」）只按<b>列名</b>判存在，
         * 报一句「相关字段已存在，无需重复添加」就收手。用户被留在死路上：
         * 界面上没有任何一处能把它改对，除了手写 ALTER。
         *
         * <p>所以判据放在<b>建表参数</b>这一侧：agent 的生成后校验器读到这两列就照这里判，
         * 干跑把它折成阻断信号，规划期就报，模型自己改得对。
         */
        private List<FlowColumnConstraint> flowColumns;
    }

    /**
     * 一条流程回写列的类型要求
     *
     * <p>🔴 {@code allowedTypes} 里放的是<b>受控类型名</b>（{@code DataModelColumnTypeEnum#getType}），
     * 不是 MySQL 类型、也不是 JDBC 枚举名 —— 调用方（agent 的建表校验器）手里的
     * {@code columnType} 就是这一套，换成别的口径它就得再抄一份映射表，
     * 而 B17 记的正是「两份类型词表各自漂移」的代价。
     */
    @lombok.Data
    class FlowColumnConstraint {

        /**
         * 列名，如 {@code process_status}
         */
        private String columnName;

        /**
         * 这一列合格的受控类型名；空 = 任何类型都收（本方法不会给出这种约束）
         */
        private List<String> allowedTypes;

        /**
         * STRING 时的最小长度；null = 不限长度
         */
        private Integer minLength;
    }

}
