package cn.enlev.ainb.module.infra.api.formdesign;

import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.module.infra.api.formdesign.dto.FormDataPageReqDTO;
import cn.enlev.ainb.module.infra.api.formdesign.dto.FormDesignMenuPreviewRespDTO;
import cn.enlev.ainb.module.infra.api.formdesign.dto.FormDesignPublishRespDTO;
import cn.enlev.ainb.module.infra.api.formdesign.dto.FormDesignRespDTO;
import cn.enlev.ainb.module.infra.api.formdesign.dto.FormDesignSaveReqDTO;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 表单设计 API 接口
 *
 * 供 BPM 模块对接「在线表单」使用：流程模型与流程节点绑定表单设计的表单，
 * 流程数据落到该表单表绑定的物理表。
 *
 * 设计约定：
 * 1. 一律以 code 而非 id 作为入参 —— code 发布后不可修改，作为跨模块引用更稳定
 * 2. 不涉及版本：始终取表单的最新版本，流程不冻结表单快照
 * 3. 数据读写全部委托 FormDesignRuntimeService，其方法已带事务，调用方在外层事务内调用即可加入
 *
 * @author 走进新时代
 */
public interface FormDesignApi {

    /**
     * 获得可供流程选择的表单列表
     *
     * 返回全部已发布表单，并逐个标注 flowReady 与 unavailableReason，
     * 由调用方决定置灰还是过滤 —— 直接过滤掉会让用户不知道自己的表单为何不出现。
     *
     * @param mainForm 是否作为主表单校验。主表单要求业务表同时具备 process_instance_id 与 process_status 两列；
     *                 节点表单只要求 process_instance_id
     * @return 表单列表
     */
    List<FormDesignRespDTO> getFlowFormList(boolean mainForm);

    /**
     * 获得表单（<b>设计态，不做字段权限裁剪</b>）
     *
     * ⚠️ 只给「配流程」的场景用。流程设计器必须看得到表单的<b>全部</b>字段，
     * 否则设计者按自己的字段权限看到的是一份残缺表单，
     * 在上面配节点字段权限、变量映射时会静默漏配。
     *
     * 渲染给最终用户的场景一律用 {@link #getRuntimeFormDesign(String)}。
     *
     * @param code 表单编码
     * @return 表单，不存在时返回 null
     */
    FormDesignRespDTO getFormDesign(String code);

    /**
     * 获得表单（<b>运行态，已按当前登录人的字段权限裁剪</b>）
     *
     * <h3>为什么必须有这一份</h3>
     *
     * 在线表单自己的渲染接口（{@code /infra/form-runtime/config}）早就裁剪了，
     * 但流程侧拿表单走的是本 API —— <b>同一张表单，从流程页面进就没有字段权限</b>。
     * 表现是 HIDDEN 的字段照样渲染、READONLY 的照样可编辑，
     * 用户填了、提交了，然后值被写路径的 {@code stripNonWritable} 丢掉，全程无提示。
     *
     * 调用方若处于节点上下文中（{@code PermissionContextHolder}），
     * 裁剪会自动带上节点级配置 —— 这正是「节点字段权限」唯一能看见效果的地方。
     *
     * @param code 表单编码
     * @return 表单，不存在时返回 null
     */
    FormDesignRespDTO getRuntimeFormDesign(String code);

    /**
     * 获得表单列表（设计态，不裁剪）
     *
     * @param codes 表单编码数组
     * @return 表单列表
     */
    List<FormDesignRespDTO> getFormDesignList(Collection<String> codes);

    /**
     * 获得表单列表（运行态，已裁剪）
     *
     * @param codes 表单编码数组
     * @return 表单列表
     */
    List<FormDesignRespDTO> getRuntimeFormDesignList(Collection<String> codes);

    /**
     * 校验表单是否可用于流程
     *
     * @param code     表单编码
     * @param mainForm 是否作为主表单校验
     * @return 不可用原因；可用时返回 null
     */
    String validateFlowReady(String code, boolean mainForm);

    // ========== 业务数据读写：委托 FormDesignRuntimeService ==========

    /**
     * 创建业务数据
     *
     * @param code 表单编码
     * @param data 表单数据
     * @return 业务主键
     */
    Object createData(String code, Map<String, Object> data);

    /**
     * 更新业务数据
     *
     * @param code    表单编码
     * @param pkValue 业务主键
     * @param data    表单数据
     */
    void updateData(String code, Object pkValue, Map<String, Object> data);

    /**
     * 获得业务数据
     *
     * @param code    表单编码
     * @param pkValue 业务主键
     * @return 业务数据，不存在时返回 null
     */
    Map<String, Object> getData(String code, Object pkValue);

    /**
     * 删除业务数据
     *
     * @param code    表单编码
     * @param pkValue 业务主键
     */
    void deleteData(String code, Object pkValue);

    /**
     * 回写系统列
     *
     * 入参 key 是物理列名，仅允许 process_instance_id 与 process_status。
     * 不能用 {@link #updateData} 代替 —— 那个方法按表单字段映射全量覆盖，
     * 既写不进系统列（系统列不在 fieldMapping 里会被忽略），
     * 又会把未传入的表单字段清空。
     *
     * @param code         表单编码
     * @param pkValue      业务主键
     * @param columnValues 物理列名 -> 值
     */
    void updateSystemColumns(String code, Object pkValue, Map<String, Object> columnValues);

    /**
     * 分页查询业务数据（<b>只读</b>）
     *
     * <h3>🔴 与上面那批数据方法一样：本 API 不做任何权限校验</h3>
     * 这是 api 层「薄封装、只搬 DTO」的刻意设计 —— 一旦在这里写业务判断，
     * api 路径就会和 Controller 路径分叉。因此<b>调用方必须自己校验</b>数据权限：
     * {@code infra:form-design-run:{code}:query}（与 Web UI 的
     * {@code FormDesignRuntimeController.checkPermission} 逐字一致）。
     * agent 的 {@code form_data_page} 工具在 {@code execute} 首行做这道动态门。
     *
     * <h3>条件的操作符不在此校验</h3>
     * {@link FormDataPageReqDTO.Condition#getOperator()} 原样透传给
     * {@code FormDesignRuntimeService.page}，由其内部的 {@code FormDesignSqlBuilder}
     * 用 {@code OperatorEnum} 白名单兜底 —— 这是防 SQL 注入的唯一收口，
     * 绝不在此拼接操作符字符串。
     *
     * @param code   表单编码
     * @param reqDTO 分页查询条件；conditions/sortField/sortOrder/pageNo/pageSize 均可缺省，
     *               pageNo 缺省 1、pageSize 缺省 10（由 Impl 兜底）
     * @return 分页结果
     */
    PageResult<Map<String, Object>> pageData(String code, FormDataPageReqDTO reqDTO);

    // ==================================================================
    // 以下是<b>设计态</b>方法，供 agent 模块使用（方案 §3.1.1）
    //
    // ⚠️ 它们与上面那批「运行时数据 CRUD」是完全不同的两类能力：
    // 上面改的是<b>业务数据</b>，下面改的是<b>表单本身的定义</b>。
    // 放在同一个接口里是因为它们都属于表单设计这个领域，
    // 但调用方要清楚自己在动哪一层 —— 弄混的后果是「本想改一条记录，
    // 结果改了整张表单的配置」。
    // ==================================================================

    /**
     * 新建表单设计（草稿，不发布）
     *
     * @param reqDTO 全量定义
     * @return 表单编号
     */
    Long createFormDesign(FormDesignSaveReqDTO reqDTO);

    /**
     * 更新表单设计
     *
     * <h3>🔴 这<b>不是</b>增量更新</h3>
     * {@code clearableColumnsWrapper} 对 8 个列做显式清空（见
     * {@link FormDesignSaveReqDTO#CLEARABLE_FIELDS}）：这一次没传值的一律 set null。
     * 提交局部 DTO 会让该表单的<b>联动规则、校验规则、自定义按钮、页签、
     * 打印模板、前端脚本被静默清空</b>，且不报任何错。
     *
     * <p>正确用法：{@link #getFormDesignById} 取回全量 → 局部应用变更 → 整体回写。
     *
     * <h3>🔴 {@code version} 必须原样回传（B4-4 乐观锁）</h3>
     * 取回来的那个 {@code version} 就是本条写路径的版本基线，
     * 服务端拿它做 {@code UPDATE … WHERE version = ?} 并<b>检查影响行数</b>。
     * <ul>
     *   <li>不带 ⟹ {@code FORM_DESIGN_RECORD_VERSION_REQUIRED}：
     *       MyBatis-Plus 遇到 null 版本会<b>静默跳过</b>版本条件，
     *       缺了它乐观锁形同没开，所以宁可拒绝也不放行</li>
     *   <li>带的值已过期 ⟹ {@code FORM_DESIGN_RECORD_VERSION_CONFLICT}：
     *       这条表单在你「读-改-写」期间被人改过，你的改动没有落库。
     *       请重新取回全量、在其上重做，<b>不要</b>拿新版本号原样重提 ——
     *       那等于用你手里的旧内容覆盖掉对方的改动</li>
     * </ul>
     * 「读-改-写」窗口越长，撞上的概率越高，而这正是本方法唯一的使用姿势。
     *
     * <h3>⚠️ 改完还要重新发布才生效</h3>
     * 更新后状态变为「已发布有改动」，不 publish 的话用户看到的仍是旧的。
     * 这本身就是个「配了但没生效」的入口。
     */
    void updateFormDesign(FormDesignSaveReqDTO reqDTO);

    /**
     * 按编号取表单设计（设计态全量）
     *
     * 与 {@link #getFormDesign(String)} 的区别：那个按 code 取、给流程用；
     * 这个按 id 取、返回<b>可以原样回写</b>的全量结构，专供「取回 → 改 → 回写」这条路径。
     *
     * <p>返回的 {@code version} 是回写时的<b>必传</b>字段，见
     * {@link #updateFormDesign(FormDesignSaveReqDTO)}。
     *
     * @param id 表单编号
     * @return 全量定义，不存在返回 null
     */
    FormDesignSaveReqDTO getFormDesignById(Long id);

    /**
     * 按编码取表单设计（设计态全量）
     *
     * 与 {@link #getFormDesignById(Long)} 返回同样的结构，只是入参换成 code。
     * 存在的理由很实际：{@link FormDesignRespDTO} 里<b>没有 id</b>，
     * 所以拿到 code 的调用方（AI、跨模块引用）无法自己换算成 id。
     *
     * @param code 表单编码
     * @return 全量定义，不存在返回 null
     */
    FormDesignSaveReqDTO getFormDesignByCode(String code);

    /**
     * 列出全部表单（简要信息）
     *
     * 与 {@link #getFlowFormList(boolean)} 的区别：那个只返回<b>能用于流程</b>的已发布表单，
     * 这个返回全部（含草稿）。调用方需要的是「库里有哪些表单、编码是什么」——
     * 没有这个入口的话，拿到一个表单名却换不成编码，后面所有按 code 的操作都无从谈起。
     *
     * @return 表单列表
     */
    List<FormDesignSimpleRespDTO> getFormDesignSimpleList();

    /**
     * 表单的简要信息
     */
    @lombok.Data
    class FormDesignSimpleRespDTO {

        private Long id;
        private String code;
        private String name;
        private String category;
        /**
         * 绑定的物理表名
         */
        private String mainTable;
        /**
         * 状态：0 未发布、1 已发布
         *
         * ⚠️ 未发布的表单用户是看不到的，流程也绑不了。
         */
        private Integer status;

    }

    /**
     * 发布表单
     *
     * ⚠️ 发布<b>不会建菜单</b>，只返回菜单配置。见 {@link FormDesignPublishRespDTO}。
     *
     * @param id           表单编号
     * @param parentMenuId 上级菜单编号。<b>首次发布必填</b>（{@code FormDesignServiceImpl.publish}
     *                     对 {@code menuId==null} 的首发场景强制要求非空，否则抛
     *                     {@code FORM_DESIGN_MENU_PARENT_REQUIRED}「请选择上级菜单」）；
     *                     传 {@code 0L} 表示挂到顶层菜单。已绑过菜单的再发布可传 {@code null}
     * @return 发布结果，含版本号与菜单配置
     */
    FormDesignPublishRespDTO publishFormDesign(Long id, Long parentMenuId);

    /**
     * 预演「这次发布会不会建菜单、建成什么样」—— 🔴 <b>只读，一行都不写</b>
     *
     * <h3>为什么不能用 {@link #publishFormDesign} 代替</h3>
     * 那个方法<b>真发布</b>：存版本快照、改 {@code status}、在事务内同步广播
     * 权限资源登记事件。它的注释写「发布不会建菜单」，说的是<b>菜单</b>不建，
     * 不是说它没有副作用 —— 拿它做预演，等于为了看一眼菜单长什么样，
     * 把一条流程真的发布掉、还留下一串版本号。
     *
     * <h3>为什么清单需要它</h3>
     * 建不建菜单的判据是「这张表单当前有没有绑过菜单」，而 DSL 里只有表单 code，
     * 光看 DSL 算不出来。硬算的结果是<b>再发布时也报「将新建 1 个菜单」</b>——
     * 对用户说一件不会发生的事，正是变更清单要消灭的那类错误。
     *
     * <p>⚠️ 会建出来的是 <b>1 个组件菜单（{@code system_menu.type=2}）+ 7 个按钮权限
     * （{@code type=3}）= 8 条菜单记录</b>，不是一个目录；而且后端<b>一条都不建</b>，
     * 真正调 {@code /system/menu/create} 的是前端。详见
     * {@link FormDesignMenuPreviewRespDTO#getMenuConfig()}。
     *
     * @param id           表单编号，不存在抛 {@code FORM_DESIGN_NOT_EXISTS}
     *                     （{@code ServiceException}，调用方按「诊断信息」处理，不要吞成「没问题」）
     * @param parentMenuId 上级菜单编号，{@code 0L} 表示顶层。首发时传 null <b>不抛</b>，
     *                     而是在结果里置 {@code parentMenuMissing=true}
     * @return 预演结果
     */
    FormDesignMenuPreviewRespDTO previewPublishMenu(Long id, Long parentMenuId);

    /**
     * 把表单与菜单绑定
     *
     * @param designId          表单编号
     * @param menuId            主菜单编号
     * @param permissionMenuIds 按钮权限菜单编号
     */
    void bindMenu(Long designId, Long menuId, List<Long> permissionMenuIds);

    /**
     * 表单体检
     *
     * <h3>为什么这个方法对 AI 特别有价值</h3>
     * 它与<b>发布走同一份校验</b>且<b>不改任何数据</b>，
     * 所以是 AI 自修正的<b>天然反馈信号</b>：生成完表单先体检一次，
     * 有问题就把结构化结果回灌给模型自己改，改好了再进 Plan。
     *
     * <p>比「先发布、失败了再改」好得多 —— 后者会在库里留下半成品。
     *
     * @param id 表单编号
     * @return 问题列表，空表示体检通过
     */
    List<String> inspectFormDesign(Long id);

    /**
     * 获得控件契约（<b>裁剪版</b>）
     *
     * <h3>为什么要裁剪，而不是把完整契约给出去</h3>
     * 完整契约含能力位、脱敏、权限键等十几个维度，整份塞进 prompt 要几千 token，
     * 而 AI 生成表单时真正需要判断的只有三件事：
     * <b>这个控件存不存在、还能不能用、能绑什么类型的列</b>。
     *
     * <p>多给的部分不是无害的：它会稀释注意力，也会诱使模型去配它其实
     * 配不对的高级能力。
     *
     * <h3>为什么必须查而不是凭记忆写</h3>
     * 控件 {@code type} 必须在这份白名单内且 {@code supported=true}。
     * 凭记忆编控件名的后果是：表单<b>能保存、能发布，就是渲染不出来</b> ——
     * 又一个「配了但不生效」。
     *
     * @return 控件列表
     */
    List<WidgetSimpleRespDTO> getWidgetContracts();

    /**
     * 获得子表字段的分隔符（控件契约的 {@code structural.subFieldSeparator}）
     *
     * 🔴 返回的是<b>契约 JSON 里那个值</b>（经 {@code WidgetContractRegistry} 运行期解析），
     * 而 Java 侧只有一份常量 {@code FormResourceDescriptorDTO.SUB_FIELD_SEPARATOR}，
     * 两者由 {@code SubFieldSeparatorSingleSourceTest} 在构建期钉在一起；
     * {@code enlev-module-bpm} 的 {@code BpmTaskServiceImpl.SUB_FIELD_SEPARATOR} 已改成引用那一份
     * （台账 #101(b)），<b>不再是「独立副本」</b>—— 本 javadoc 原先那么写，理由（跨模块不依赖内部实现）
     * 与事实不符：bpm 本来就依赖 infra，而那个常量在 api 包里。
     *
     * <p>⚠️ 但 {@code static final String} 会被 javac 内联进各模块的字节码，
     * 只重装 infra 的 jar 仍会让两边不同步，而这种「jar 之间不同步」单测结构上测不到 ——
     * 所以这个出口要留着，让诊断工具能在<b>运行期</b>把两个值读出来比对。
     * 两边对不上的表现是「子表字段配了权限却不生效」，或反过来把有权限的字段裁掉，
     * 从现象几乎不可能反推到分隔符不一致。
     *
     * @return 分隔符
     */
    String getSubFieldSeparator();

    /**
     * 获得表单数据查询的<b>操作符目录</b>（{@code OperatorEnum} 的对外视图）
     *
     * <h3>为什么要开这个出口</h3>
     * {@code OperatorEnum} 在 {@code enums/formdesign/} 下，而 agent 模块受
     * {@code AgentArchitectureTest#testOnlyDependOnApiLayer} 约束、只能依赖本模块的 api 层，
     * 拿不到这个枚举。于是 {@code form_data_page} 工具的 {@code conditions[].operator}
     * 只有两种可能的写法：要么在 agent 侧<b>手抄一份</b>（两份目录必然漂移，漂了不报错、
     * 表现是查询静默返回空），要么让 LLM 凭记忆编（编出来的操作符被 {@code DynamicSqlBuilder}
     * 的白名单兜底丢掉，同样是静默的空结果）。给一个只读出口，两边都读同一份。
     *
     * <h3>🔴 刻意不带 SQL 片段</h3>
     * {@code OperatorEnum.sqlFragment} 是拼接 WHERE 用的实现细节，对展示与对 LLM 都无用，
     * 且没理由送到浏览器。这里只给「机器读的 code + 人读的中文名」。
     * 防注入的收口仍在 {@code DynamicSqlBuilder} 的枚举白名单，与本方法无关。
     *
     * @return 操作符列表，顺序即枚举声明顺序
     */
    List<SearchOperatorRespDTO> getSearchOperatorList();

    /**
     * 查询操作符的对外视图
     */
    @lombok.Data
    class SearchOperatorRespDTO {

        /** 操作符 code，即 {@code conditions[].operator} 该填的值（如 {@code LIKE}） */
        private String operator;

        /** 中文名（如「包含」），展示与提示词用 */
        private String label;

    }

    /**
     * 获得<b>字段格式校验</b>可用的内置正则目录（{@code validation-patterns.json} 的对外视图）
     *
     * <h3>为什么要开这个出口</h3>
     * 与 {@link #getSearchOperatorList()} 同一条理由，而且这里的后果更隐蔽。
     * 正则库在 {@code framework/formdesign/core/validation/} 下，agent 模块受
     * {@code AgentArchitectureTest#testOnlyDependOnApiLayer} 约束只能依赖本模块的 api 层，
     * 拿不到 {@code ValidationPatternRegistry}。于是 {@code form_save_draft} 要教模型写
     * {@code props.validateRules} 的 {@code patternKey} 时只有两条路：在 agent 侧<b>手抄一份键名</b>
     * （两份目录必然漂移，漂了不报错），或者让 LLM 凭记忆编。
     *
     * <h3>🔴 编错键名在<b>运行期</b>是静默放过的</h3>
     * {@code FormValidationGuard#checkBuiltin} 查不到 key 时「记一条 warn 然后返回 true」——
     * 那是有意的可用性取舍（一次正则库调整不该让存量表单集体提交不了）。
     * 副作用是：用户要求「身份证要校验格式」，表单建好了、发布体检也过了
     * （只要没人真去发布，{@code FormDesignPublishValidator#validateValidationRules} 那一关就碰不到），
     * 而任何输入都能存进去。<b>这正是本模块头号缺陷「配了但不生效」的形状。</b>
     * 给一个只读出口，提示词就能把真实键名印出来，模型不必猜。
     *
     * <h3>🔴 刻意不带 {@code pattern}（正则原文）</h3>
     * 正则原文对 LLM 无用（它要的是「身份证 → 哪个 key」），而送出去等于邀请它
     * 改用 {@code type=REGEX} 把正则<b>拷进每一张表单</b>——那就绕开了
     * 「引用 key、库升级后存量表单跟着升级」这个设计意图（见 {@code FieldValidationRule} 类注释）。
     * 前端那份构建期副本另有一致性测试对着 {@code GET /infra/form-design/validation-patterns} 比，
     * 与本方法无关。
     *
     * @return 内置正则目录，顺序即库文件里的声明顺序
     */
    List<ValidationPatternRespDTO> getValidationPatternList();

    /**
     * 内置正则的对外视图
     */
    @lombok.Data
    class ValidationPatternRespDTO {

        /** 正则 key，即 {@code props.validateRules[].patternKey} 该填的值（如 {@code ID_CARD}） */
        private String key;

        /** 中文名（如「身份证」）。提示词靠它把用户的说法对上 key，故不可省 */
        private String label;

        /** 校验不通过时的默认提示语 */
        private String message;

    }

    /**
     * 控件契约的裁剪版
     */
    @lombok.Data
    class WidgetSimpleRespDTO {

        /** 控件类型，生成 schema 时用的就是它 */
        private String type;

        private String label;

        private String group;

        /** 是否可用。false 的绝对不能用 */
        private Boolean supported;

        /** 是否已废弃。可用但不建议，新表单不要选 */
        private Boolean deprecated;

        /**
         * 可绑定的列类型族
         *
         * 控件与目标列的类型不相容时，表单同样是「能保存、跑起来不对」。
         */
        private List<String> columnTypes;

        /** 是否可作为子表控件 */
        private Boolean subTable;

        /**
         * 控件自身的配置项（契约 v2）
         *
         * 🔴 这一块原先是<b>空白</b>，直接后果是 AI 只能用基础控件 ——
         * 字典选择器该填哪个字典、关联表单该绑哪张表单，它无从得知，
         * 于是要么不用这些控件，要么凭空捏一个键名。
         * 而这类配置<b>写错键名不报错</b>，表现是「配了、保存成功了、界面上没生效」。
         */
        private List<WidgetPropDTO> props;
    }

    /**
     * 控件配置项
     */
    @lombok.Data
    class WidgetPropDTO {

        /** 键名，即 rule.props 里的 key。写错不报错，只是安静地不生效 */
        private String name;
        /** string / number / boolean / array / object / any */
        private String type;
        /** 不填则该控件无法正常工作 */
        private Boolean required;
        /** 中文说明，含「配错了会怎样」 */
        private String desc;
        /** 受控取值。非空表示只能从这里面选 */
        private List<String> allowedValues;
        /** 需要引用别处编码时，说明去哪儿取 */
        private String source;
        /** 示例值 */
        private String example;
    }

}
