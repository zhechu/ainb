package cn.enlev.ainb.module.agent.framework.agent.core.validate;

import cn.enlev.ainb.framework.common.biz.system.dict.dto.DictDataRespDTO;
import cn.enlev.ainb.framework.common.biz.system.dict.dto.DictTypeRespDTO;
import cn.enlev.ainb.framework.common.enums.CommonStatusEnum;
import cn.enlev.ainb.framework.common.exception.ServiceException;
import cn.enlev.ainb.module.agent.dal.dataobject.workflow.AgentWorkflowDefDO;
import cn.enlev.ainb.module.agent.dal.mysql.def.AgentDefMapper;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.compiler.DslParser;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.context.WorkflowContext;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.NodeDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.dsl.WorkflowDefDTO;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.NodeExecutor;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.NodeExecutorRegistry;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.impl.HitlConfirmNodeExecutor;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.executor.impl.ToolNodeExecutor;
import cn.enlev.ainb.module.agent.framework.agent.core.manifest.ChangeItem;
import cn.enlev.ainb.module.agent.framework.agent.core.manifest.ChangeKind;
import cn.enlev.ainb.module.agent.framework.agent.core.manifest.ChangeManifest;
import cn.enlev.ainb.module.agent.framework.agent.core.manifest.DryRunResult;
import cn.enlev.ainb.module.agent.framework.agent.core.manifest.ManifestArgs;
import cn.enlev.ainb.module.agent.framework.agent.core.manifest.UntrustedInputTracer;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentToolRegistry;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.RiskLevel;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.ToolResultShapes;
import cn.enlev.ainb.module.agent.service.tooldef.AgentToolDefService;
import cn.enlev.ainb.module.agent.service.workflow.AgentWorkflowDefService;
import cn.enlev.ainb.module.agent.tool.impl.datamodel.DataModelListTool;
import cn.enlev.ainb.module.agent.tool.impl.datamodel.DataModelParamResolver;
import cn.enlev.ainb.module.agent.tool.impl.datamodel.DataModelStructureTool;
import cn.enlev.ainb.module.agent.tool.impl.form.FormFieldFactory;
import cn.enlev.ainb.module.ai.api.model.AiModelApi;
import cn.enlev.ainb.module.bpm.api.definition.BpmModelApi;
import cn.enlev.ainb.module.bpm.api.definition.dto.BpmModelRespDTO;
import cn.enlev.ainb.module.infra.api.formdesign.FormDesignApi;
import cn.enlev.ainb.module.infra.api.datamodel.DataModelApi;
import cn.enlev.ainb.module.infra.api.datamodel.dto.DataModelStructureRespDTO;
import cn.enlev.ainb.module.system.api.dict.DictDataApi;
import cn.enlev.ainb.module.system.api.menu.MenuApi;
import cn.enlev.ainb.module.system.api.menu.dto.MenuSimpleRespDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link WorkflowDslValidator} 单测（增量7a，T1）
 *
 * <p>钉两件事：① 合法 DSL 通过；② 各类基础结构错<b>一次收集多条</b>、且文案写「怎么改」
 * （回灌给 LLM 自修正用，见 {@link AgentValidateResult} 类注释）。纯内存、0 token、不建图。
 *
 * <p>⑥⑦⑧⑨⑩⑪⑫⑬ 八节考的是另外八条规则族，仍然纯内存：⑥ 工具引用三查（V1/V2/V3）走桩表；
 * ⑦ 干跑结论折叠（V4/V5/V12）吃的是一份<b>调用方已经建好</b>的清单 ——
 * {@link WorkflowDslValidator#foldDryRunRejections} 自己不发起任何探测，
 * 「探出 REJECTED」那一半由 {@code ChangeManifestDryRunnerTest} 负责；
 * ⑧ 平台引用四查（V6 列 / V7 流程 / V8 模型 / V10 子流程）走桩的跨模块 api，
 * 与运行期查的是同一份数据、同一套口径（各自的口径差异写在用例注释里）；
 * ⑨ 触发器 × 人工闸口（V11 分型矩阵 / V13 无头禁 confirm / V14 wait(event) 唤醒方）——
 * 这一节考的是<b>判定矩阵本身</b>：每一格（含「不该报」的那些格）都有一条用例，
 * 因为矩阵写错一格的表现是「无头建表流程全发不出去」或「定时任务静悄悄卡死」，两者都查不出原因；
 * ⑩ 入站面 / 不可达 / 写而未声明（V15 匿名 webhook / V16 污点入写工具 / V18 孤立节点 / V19 键丢在 resume）——
 * 这四条各自钉一个「体检全绿、跑起来才出事」的形状，而它们的<b>沉默条件</b>与命中条件同样要紧：
 * 沉默条件写错一格，代价是每一张画布都刷一屏假警报，作者从此不看红字；
 * ⑪ 探测<b>本身</b>故障时的作用域与文案（D-new8）—— 考的不是规则判得对不对，
 * 而是「查不动的时候老实交代漏了多少」：范围谎报成小，比不查更坏；
 * ⑫ 会话腿没人写回复键（V20）—— 这一节大半在钉<b>沉默条件</b>：def 416 那种「只给一条腿配了总结节点」
 * 要报，而「两条腿都写了」「两条腿都没写」「无头触发」「循环体里写了」四种都不报 ——
 * 它是<b>警告</b>，假警报一多作者就不再读黄字；
 * ⑬ tool 节点的 args 不是对象（V21）—— 钉的是「体检全绿、存成草稿、真跑才炸」那一类形状里
 * 最确定的一个：执行期 {@code configMap} 认不出类型就返回 null，工具以<b>空参数表</b>被调用。
 * 这一节有一条用例专门钉<b>文案同源</b>（V21 与变更清单那一格红标必须逐字同一句），
 * 因为它是 V 系列里唯一一条从 manifest 包借文案的规则，借来的东西最容易在某一侧被改字；
 * ⑭ script 节点里调沙箱不存在的 {@code tools.*}（V22）—— 比 ⑬ 更坏一档：⑬ 是「工具被空调用」，
 * ⑭ 是「一次建表单的写操作<b>对变更清单完全隐形</b>」（清单只从 tool 节点提取，
 * {@code WorkflowChangeManifestBuilder:190} 把 script 归进不产变更项那一类），
 * 于是审批人看到的是一张 {@code kinds=[]}、{@code riskLevel=READ_ONLY} 的「只读」卡。
 * 这一节大半在钉<b>免报条件</b>：它是纯文本形状判据，误报一格就等于让自修正回路
 * 去改一个没病的脚本，而每一轮都是一次真实计费。
 *
 * @author 走进新时代
 */
class WorkflowDslValidatorTest {

    private WorkflowDslValidator validator;
    private DataModelApi dataModelApi;
    /** 控件契约的出口。留成字段是为了让「探测本身故障」那几条用例能把它改成抛异常 */
    private FormDesignApi formDesignApi;
    private AgentToolDefService toolDefService;
    private BpmModelApi bpmModelApi;
    private AiModelApi aiModelApi;
    /** 启用目录清单的出口。留成字段是为了让「读目录失败」那几条用例能把它改成抛异常 */
    private MenuApi menuApi;
    private AgentWorkflowDefService defService;
    /** Agent 编码存在性的出口（V28）。留成字段是为了让「查不到」与「查库失败」那几条用例各自桩 */
    private AgentDefMapper agentDefMapper;
    /**
     * 字典存在性 / 字典数据的出口（V29 借 {@link FormSchemaValidator} 读它）。
     * 留成字段是为了让「字典是编的」「字典没数据」「查字典抛异常」三种形状各自桩
     */
    private DictDataApi dictDataApi;
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 内置工具桩表：键 = toolId。用例可在自己的方法里再 put 一条覆盖默认声明
     * （见 {@link #stubBuiltinTool(String, RiskLevel, RiskLevel, boolean)}）。
     */
    private final Map<String, AgentToolRegistry.ToolDefinition> toolStubs = new LinkedHashMap<>();

    /** 桩执行器：只声明 type，validate 不执行图，execute 永不被调 */
    private static class StubExecutor implements NodeExecutor {
        private final String type;

        StubExecutor(String type) {
            this.type = type;
        }

        @Override
        public String getType() {
            return type;
        }

        @Override
        public Map<String, Object> execute(NodeDTO node, WorkflowContext ctx) {
            return Map.of();
        }
    }

    @BeforeEach
    void setUp() {
        NodeExecutorRegistry registry = new NodeExecutorRegistry();
        registry.setExecutors(List.of(
                new StubExecutor("tool"),
                new StubExecutor("llm"),
                new StubExecutor("condition.rule"),
                new StubExecutor("condition.llm"),
                new StubExecutor("hitl.confirm"),
                new StubExecutor("hitl.bpm_approval"),
                new StubExecutor("knowledge_retrieval"),
                new StubExecutor("parallel"),
                // 🔴 script 不在 GRAPH_BUILDER_TYPES（那三种是「节点动作即子图」）里，
                //    不登记就会被「type 是否已知」那一关误报成「执行器不存在」，⑭ 节整节全红
                new StubExecutor("script"),
                new StubExecutor("wait")));
        validator = new WorkflowDslValidator();
        ReflectionTestUtils.setField(validator, "nodeExecutorRegistry", registry);

        // form_save_draft 的控件白名单校验依赖控件契约；桩一份够用的清单
        formDesignApi = mock(FormDesignApi.class);
        when(formDesignApi.getWidgetContracts()).thenReturn(contractWidgets());
        FormSchemaValidator formSchemaValidator = new FormSchemaValidator();
        ReflectionTestUtils.setField(formSchemaValidator, "formDesignApi", formDesignApi);
        // V29 借 FormSchemaValidator 判「字典在不在」，那一路读的是 DictDataApi。
        // 🔴 默认不桩 = 任何 dictType 都「不存在」（Mockito 对未桩方法返回 null），
        //    正好是「模型凭记忆编了一个字典名」那个形状；要用字典的用例各自桩
        dictDataApi = mock(DictDataApi.class);
        ReflectionTestUtils.setField(formSchemaValidator, "dictDataApi", dictDataApi);
        ReflectionTestUtils.setField(validator, "formSchemaValidator", formSchemaValidator);
        // V29 用运行期那一份 applyOptionSource 把字段描述摊成 rule 再判（判据不许有第二份）。
        // 真建实例而不是 mock：mock 掉它就等于把「规划期与运行期同口径」这句结构性保证测没了
        ReflectionTestUtils.setField(validator, "fieldFactory", new FormFieldFactory());

        // 主表存在性校验依赖物理表清单；默认空 = 任何表都「不存在」，用例各自桩
        dataModelApi = mock(DataModelApi.class);
        when(dataModelApi.getPhysicalTableList(anyString())).thenReturn(List.of());
        // V30 的系统列名单取自这一次全局读。默认桩一份<b>真的</b>名单而不是留空：
        // 🔴 留空（Mockito 对未桩方法返回 null）会被 systemColumnsOrAbort 判成「探不到」，
        //    于是每条「本流程自己建表」的用例都多顶一句黄标 —— 而它们要测的是列对不对得上，
        //    绿了红了都说明不了 V30 判得准不准（正是 contractWidgets() 那条注释记的同一种「测 A 却因为 B 变红」）
        when(dataModelApi.getTableConstraints()).thenReturn(tableConstraints(SYSTEM_COLUMN_NAMES));
        ReflectionTestUtils.setField(validator, "dataModelApi", dataModelApi);
        // V30 读「同一份 DSL 里 datamodel_create 的建表参数」借的是运行期那个解析器（判据不许有第二份）。
        // 真建实例而不是 mock：mock 掉它就等于把「规划期与运行期同口径」这句结构性保证测没了。
        // resolveCreate 是纯计算（不查库），故它的 dataModelApi 只需挂上、不需要额外桩
        DataModelParamResolver dataModelParamResolver = new DataModelParamResolver();
        ReflectionTestUtils.setField(dataModelParamResolver, "dataModelApi", dataModelApi);
        ReflectionTestUtils.setField(validator, "dataModelParamResolver", dataModelParamResolver);

        // 工具引用校验（V1/V2/V3）依赖「内置注册表 + 自定义工具目录」的合流。
        // 🔴 注册表默认「任何键都认不出」会让下面每一条用存量工具键的用例都多报一条 V1
        //    （那正是「测 A 却因为 B 变红」），故把用例里出现过的工具都登记进来
        stubBuiltinTool("data_interface_list", RiskLevel.READ_ONLY);
        stubBuiltinTool("x", RiskLevel.READ_ONLY);
        stubBuiltinTool("form_save_draft", RiskLevel.LOW);
        stubBuiltinTool("datamodel_create", RiskLevel.IRREVERSIBLE);
        // 只为 V30 那条「登记既有表」的用例存在：datamodel_import 认不出列，必须落回查库那一路
        stubBuiltinTool("datamodel_import", RiskLevel.LOW);
        // 只为「相近候选」那条用例存在：datamodel_lst 打错时要能推出 datamodel_list
        stubBuiltinTool("datamodel_list", RiskLevel.READ_ONLY);
        stubBuiltinTool("form_publish_bundle", RiskLevel.HIGH);
        stubBuiltinTool("tool_create_record", RiskLevel.LOW);
        stubBuiltinTool("tool_delete_record", RiskLevel.HIGH);
        // V6/V7 的「本流程自己会建/会部署」那两条出路要用到
        stubBuiltinTool("datamodel_update", RiskLevel.HIGH);
        stubBuiltinTool("process_create_and_deploy", RiskLevel.HIGH);
        AgentToolRegistry toolRegistry = mock(AgentToolRegistry.class);
        when(toolRegistry.get(anyString())).thenAnswer(inv -> toolStubs.get(inv.<String>getArgument(0)));
        when(toolRegistry.list()).thenAnswer(inv -> toolStubs.values());
        ReflectionTestUtils.setField(validator, "toolRegistry", toolRegistry);

        // 自定义工具目录：默认「当前租户一个自定义工具都没有」，用例各自桩 resolveForGateway
        toolDefService = mock(AgentToolDefService.class);
        when(toolDefService.getEnabledToolDefList()).thenReturn(List.of());
        ReflectionTestUtils.setField(validator, "toolDefService", toolDefService);

        // V7：默认「BPM 里一个流程模型都没有」。
        // 🔴 桩的是 getModelList(null) 而不是 anyString()：校验路径永远传 null
        //    （那个入参过滤的是流程<b>名称</b>，而这里要匹配的是流程<b>标识</b>），
        //    用 anyString() 反而匹配不上 null，会把「桩没生效」伪装成「流程查不到」
        bpmModelApi = mock(BpmModelApi.class);
        when(bpmModelApi.getModelList(isNull())).thenReturn(List.of());
        ReflectionTestUtils.setField(validator, "bpmModelApi", bpmModelApi);

        // V8：默认「一个可用聊天模型都没有」。没有 llm 节点钉 modelId 时压根不会查它
        aiModelApi = mock(AiModelApi.class);
        when(aiModelApi.getEnabledModelList(1)).thenReturn(List.of());
        ReflectionTestUtils.setField(validator, "aiModelApi", aiModelApi);

        // ⑱：form_publish_bundle 的上级菜单校验读的就是这份清单。
        // 🔴 桩<b>两条</b>而不是把真实库里的目录名抄一份进来：生产代码一份清单都不持有
        //    （现读 {@code MenuApi#getEnabledDirectoryList()}），测试里抄全量会让人误以为
        //    「清单在这儿维护」，而它一漂正是这条规则要拦的那个静默失败
        menuApi = mock(MenuApi.class);
        when(menuApi.getEnabledDirectoryList())
                .thenReturn(List.of(directory("在线示例"), directory("系统管理")));
        ReflectionTestUtils.setField(validator, "menuApi", menuApi);

        // V10：默认返回一个可用的定义（不抛 = 查到了）。要考「查不到」的用例各自桩成抛 ServiceException
        defService = mock(AgentWorkflowDefService.class);
        when(defService.getSubWorkflowDef(anyString(), any())).thenReturn(new AgentWorkflowDefDO());
        ReflectionTestUtils.setField(validator, "defService", defService);

        // V28：chat 触发器绑的 Agent 编码存不存在。默认「填什么都在」—— 与上面 defService 那条同一个安排：
        // 正常路径不必逐例桩，要考「查不到」的用例自己桩成 false。
        // ⚠️ 桩的是 mapper 的 default 方法，故 existsByCode 自己那句 selectCount <b>不在单测射程里</b>
        //    （本模块纯 Mockito、不碰真库），它由端到端那一趟覆盖。
        agentDefMapper = mock(AgentDefMapper.class);
        when(agentDefMapper.existsByCode(anyString())).thenReturn(true);
        ReflectionTestUtils.setField(validator, "agentDefMapper", agentDefMapper);

        // 🔴 outputMap 的取值表达式按「父 ∪ 子」判（E3-2）：子名字表要把子 def 的 dsl <b>真</b>解析一遍，
        //    故这里给一个真的 DslParser 而不是 mock —— mock 掉 parse 就等于把本条要考的那份口径也 mock 掉了。
        //    ⚠️ 上面那个默认桩返回的是<b>空</b> DO（dsl=null）⟹ parse 抛 WORKFLOW_DSL_INVALID
        //    ⟹ 走「子 def 取不到就整格不判」的降级路。要考并集的用例各自调 stubChildDef 桩一份带 dsl 的子 DO。
        DslParser dslParser = new DslParser();
        ReflectionTestUtils.setField(dslParser, "objectMapper", mapper);
        ReflectionTestUtils.setField(validator, "dslParser", dslParser);
    }

    /**
     * 桩一份「子流程 code={@code child_wf}、里面有个 capture 产出 {@code childOut}」的可执行子 def
     *
     * <p>只被 {@code validatePlaceholders} 那一支读（V10 只看「抛不抛」），故 DSL 内容只要
     * <b>能被解析</b>且<b>名字表长得对</b>即可，不必是一份能通过体检的流程。
     *
     * @param extraVars 额外声明进子 def {@code variables[]} 的键（考「子流程声明的变量也算解析得到」那一档）
     */
    private void stubChildDef(String... extraVars) {
        StringBuilder vars = new StringBuilder("[{\"key\":\"childInput\",\"type\":\"object\"}");
        for (String key : extraVars) {
            vars.append(",{\"key\":\"").append(key).append("\",\"type\":\"object\"}");
        }
        vars.append("]");
        AgentWorkflowDefDO child = new AgentWorkflowDefDO();
        child.setCode("child_wf");
        child.setVersion(1);
        child.setDsl("""
                {"id":"child_wf","variables":%s,
                 "nodes":[{"id":"cap","type":"tool","config":{"toolId":"x","outputVar":"childOut"}}],
                 "edges":[{"from":"START","to":"cap"},{"from":"cap","to":"END"}]}
                """.formatted(vars));
        when(defService.getSubWorkflowDef(anyString(), any())).thenReturn(child);
    }

    /** 登记一个内置工具桩；声明风险级 = 生效风险级（即「该租户没有覆盖行」的常态） */
    private void stubBuiltinTool(String name, RiskLevel risk) {
        stubBuiltinTool(name, risk, risk, true);
    }

    /**
     * 登记一个内置工具桩，<b>声明风险级与生效风险级可以不同</b>
     *
     * <p>两者不同 = 模拟一行 {@code agent_tool_override} 把这个工具的风险改写了（D11）。
     * 校验路径必须只认声明值，否则那行数据就能让 V3/V11 看不见危险工具。
     */
    private void stubBuiltinTool(String name, RiskLevel declaredRisk, RiskLevel effectiveRisk, boolean enabled) {
        AgentToolRegistry.ToolDefinition def = mock(AgentToolRegistry.ToolDefinition.class);
        when(def.getName()).thenReturn(name);
        when(def.getDisplayName()).thenReturn(name);
        when(def.getDeclaredRisk()).thenReturn(declaredRisk);
        when(def.getRisk()).thenReturn(effectiveRisk);
        when(def.isRequireConfirm()).thenReturn(effectiveRisk.isRequireConfirm());
        when(def.isEnabled()).thenReturn(enabled);
        toolStubs.put(name, def);
    }

    /** 桩一个当前租户的自定义工具（走网关那条解析，故看得到停用行） */
    private void stubCustomTool(String toolKey, boolean enabled, RiskLevel risk) {
        when(toolDefService.resolveForGateway(toolKey))
                .thenReturn(new AgentToolDefService.GatewayView(enabled, List.of(), risk));
    }

    private static FormDesignApi.WidgetSimpleRespDTO widget(String type) {
        FormDesignApi.WidgetSimpleRespDTO dto = new FormDesignApi.WidgetSimpleRespDTO();
        dto.setType(type);
        dto.setSupported(true);
        return dto;
    }

    /**
     * 契约桩清单。抽成方法是为了让「前几次读得好好的、第 N 次开始抛」那种用例能复用同一份清单
     * （{@code validate_optionSourceProbeFailure_warnsWithWholeScope}）
     *
     * <p>🔴 选项族那三个必须在里面：真实契约一直有它们，桩里没有的话
     * 「select 没配选项来源」会先被白名单判成「控件类型 select 不存在」，
     * 于是 ⑳ 节整节测的是另一个根因，绿了红了都说明不了 V29 判得对不对
     */
    private static List<FormDesignApi.WidgetSimpleRespDTO> contractWidgets() {
        return List.of(
                widget("input"), widget("textarea"), widget("datePicker"),
                widget("UploadImg"), widget("UploadImgs"), widget("UploadFile"),
                widget("select"), widget("radio"), widget("checkbox"));
    }

    private static DataModelApi.PhysicalTableRespDTO physicalTable(String tableName) {
        DataModelApi.PhysicalTableRespDTO dto = new DataModelApi.PhysicalTableRespDTO();
        dto.setTableName(tableName);
        dto.setRegistered(true);
        return dto;
    }

    /**
     * V30 的系统列名单桩
     *
     * <p>🔴 刻意<b>不</b>把 infra 那份 {@code SystemColumnInjector#SYSTEM_COLUMNS} 抄进来：
     * 生产代码是<b>现读</b> {@code DataModelApi#getTableConstraints()} 的，抄一份进测试
     * 就等于宣称「名单在这儿维护」，而它一漂正是 V30 要拦的那类静默失败。
     * 这里只桩够用的几个（含 {@code create_time}：V30 有一条用例专测「表单字段映射系统列不该被冤枉」）。
     */
    private static final List<String> SYSTEM_COLUMN_NAMES =
            List.of("id", "creator", "create_time", "updater", "update_time", "deleted", "tenant_id");

    private static DataModelApi.TableConstraintsRespDTO tableConstraints(List<String> systemColumnNames) {
        DataModelApi.TableConstraintsRespDTO dto = new DataModelApi.TableConstraintsRespDTO();
        dto.setSystemColumnNames(systemColumnNames);
        return dto;
    }

    /** 一个启用的目录。校验只读 name，故 id/type 不填 —— 填了反而会让人以为判定看它们 */
    private static MenuSimpleRespDTO directory(String name) {
        MenuSimpleRespDTO dto = new MenuSimpleRespDTO();
        dto.setName(name);
        return dto;
    }

    /**
     * 桩一张<b>已登记进数据模型</b>的表：物理表清单里有它（V6 的前置守卫放行），
     * 且 {@code getDataModelList} + {@code getStructure} 能读出它的列（V6 真正比对的东西）
     *
     * <p>「表在库里、但没登记进数据模型」是另一种合法状态，用 {@link #stubPhysicalTableOnly} 桩。
     */
    private void stubRegisteredTable(String tableName, String... columns) {
        stubPhysicalTableOnly(tableName);
        DataModelApi.DataModelSimpleRespDTO model = new DataModelApi.DataModelSimpleRespDTO();
        model.setId(9001L);
        model.setTableName(tableName);
        when(dataModelApi.getDataModelList()).thenReturn(List.of(model));
        DataModelStructureRespDTO structure = new DataModelStructureRespDTO();
        structure.setTableName(tableName);
        List<DataModelStructureRespDTO.ColumnDTO> list = new ArrayList<>();
        for (String columnName : columns) {
            DataModelStructureRespDTO.ColumnDTO column = new DataModelStructureRespDTO.ColumnDTO();
            column.setColumnName(columnName);
            list.add(column);
        }
        structure.setColumns(list);
        when(dataModelApi.getStructure(9001L)).thenReturn(structure);
    }

    /** 只桩「物理表在库里」，不桩登记信息 —— 即「从没登记进数据模型」的那批表 */
    private void stubPhysicalTableOnly(String tableName) {
        when(dataModelApi.getPhysicalTableList(tableName)).thenReturn(List.of(physicalTable(tableName)));
    }

    private static BpmModelRespDTO bpmModel(String key, String name, boolean deployed) {
        BpmModelRespDTO dto = new BpmModelRespDTO();
        dto.setKey(key);
        dto.setName(name);
        dto.setDeployed(deployed);
        return dto;
    }

    private void stubBpmModels(BpmModelRespDTO... models) {
        when(bpmModelApi.getModelList(isNull())).thenReturn(List.of(models));
    }

    private void stubChatModels(Long... ids) {
        List<AiModelApi.AiModelSimpleRespDTO> models = new ArrayList<>();
        for (Long id : ids) {
            AiModelApi.AiModelSimpleRespDTO dto = new AiModelApi.AiModelSimpleRespDTO();
            dto.setId(id);
            models.add(dto);
        }
        when(aiModelApi.getEnabledModelList(1)).thenReturn(models);
    }

    private AgentValidateResult validate(String dsl) throws Exception {
        WorkflowDefDTO def = mapper.readValue(dsl, WorkflowDefDTO.class);
        return validator.validate(def);
    }

    // ==================== ① 合法 DSL 通过 ====================

    @Test
    void validate_startToolEnd_passed() throws Exception {
        AgentValidateResult r = validate("""
                {"dslVersion":"1.0","id":"wf","name":"n",
                 "variables":[{"key":"input","type":"object"},{"key":"out","type":"object"}],
                 "nodes":[{"id":"list","type":"tool","config":{"toolId":"data_interface_list","outputVar":"out"}}],
                 "edges":[{"from":"START","to":"list"},{"from":"list","to":"END"}]}
                """);
        assertTrue(r.isPassed(), "合法 START→tool→END 应通过：" + r.toFeedback());
    }

    /**
     * 🔴 复现 def-152：save_draft 的参数是规划期冻死的，运行期不会再问模型。
     *    控件名写错（那次的 {@code image}、更早的 {@code upload}）唯一暴露时机是
     *    用户点完确认之后的执行失败 —— 所以必须在这里拦，让自修正回路还够得着。
     */
    @Test
    void validate_saveDraftFrozenUnknownWidget_rejected() throws Exception {
        AgentValidateResult r = validate("""
                {"id":"wf",
                 "variables":[{"key":"input","type":"object"},{"key":"draft","type":"object"}],
                 "nodes":[{"id":"save_draft","type":"tool","config":{
                     "toolId":"form_save_draft","outputVar":"draft",
                     "args":{"name":"设备管理","code":"device_management","mainTable":"biz_device",
                             "fields":[{"field":"device_image","widget":"imgUpload","title":"设备图片"}]}}}],
                 "edges":[{"from":"START","to":"save_draft"},{"from":"save_draft","to":"END"}]}
                """);
        assertFalse(r.isPassed(), "冻进 DSL 的不存在控件名必须规划期拦下：" + r.toFeedback());
        assertTrue(r.toFeedback().contains("imgUpload"), "错误要点名写错的控件名：" + r.toFeedback());
    }

    @Test
    void validate_saveDraftAliasWidget_passed() throws Exception {
        // 本用例只考控件归一：把主表桩成存在，避免主表守卫插进来报错
        when(dataModelApi.getPhysicalTableList("biz_device")).thenReturn(List.of(physicalTable("biz_device")));
        // image 是直觉名：与运行期同口径归一到 UploadImg 后应放行，不该逼模型多烧一轮自修正
        AgentValidateResult r = validate("""
                {"id":"wf",
                 "variables":[{"key":"input","type":"object"},{"key":"draft","type":"object"}],
                 "nodes":[{"id":"save_draft","type":"tool","config":{
                     "toolId":"form_save_draft","outputVar":"draft",
                     "args":{"name":"设备管理","code":"device_management","mainTable":"biz_device",
                             "fields":[{"field":"device_image","widget":"image","title":"设备图片"},
                                      {"field":"production_date","widget":"date","title":"生产日期"}]}}}],
                 "edges":[{"from":"START","to":"save_draft"},{"from":"save_draft","to":"END"}]}
                """);
        assertTrue(r.isPassed(), "上传族/日期直觉名归一后应通过：" + r.toFeedback());
    }

    /**
     * 🔴 复现「绑幽灵表还显示成功」：analyze 自己写着物理表 total=0、需新建 biz_device，
     *    产出的 DSL 却没有建表节点。规划期必须拦，逼自修正回路补 datamodel_create。
     */
    @Test
    void validate_saveDraftGhostMainTable_rejected() throws Exception {
        AgentValidateResult r = validate("""
                {"id":"wf",
                 "variables":[{"key":"input","type":"object"},{"key":"draft","type":"object"}],
                 "nodes":[{"id":"save_draft","type":"tool","config":{
                     "toolId":"form_save_draft","outputVar":"draft",
                     "args":{"name":"设备管理","code":"device_management","mainTable":"biz_device",
                             "fields":[{"field":"device_name","widget":"input","title":"设备名称"}]}}}],
                 "edges":[{"from":"START","to":"save_draft"},{"from":"save_draft","to":"END"}]}
                """);
        assertFalse(r.isPassed(), "主表不存在且无建表节点必须规划期拦下：" + r.toFeedback());
        assertTrue(r.toFeedback().contains("biz_device"), "要点名幽灵表：" + r.toFeedback());
        assertTrue(r.toFeedback().contains("datamodel_create"), "要给出路：" + r.toFeedback());
    }

    @Test
    void validate_saveDraftMainTableCreatedEarlier_passed() throws Exception {
        // 流程里 datamodel_create 在 save_draft 之前：表到运行期才存在，规划期不该误拦。
        // ⚠️ 这个建表节点<b>没有 args</b> ⟹ V30 认不出它声明了哪些列，会留一句黄标认领
        //    （那一档由 validate_saveDraftInDslCreatorUnreadable_… 专测）。本用例只钉「不误拦」这半
        AgentValidateResult r = validate("""
                {"id":"wf",
                 "variables":[{"key":"input","type":"object"},{"key":"draft","type":"object"}],
                 "nodes":[
                   {"id":"make_table","type":"tool","config":{"toolId":"datamodel_create","outputVar":"tbl"}},
                   {"id":"save_draft","type":"tool","config":{
                       "toolId":"form_save_draft","outputVar":"draft",
                       "args":{"name":"设备管理","code":"device_management","mainTable":"biz_device",
                               "fields":[{"field":"device_name","widget":"input","title":"设备名称"}]}}}],
                 "edges":[{"from":"START","to":"make_table"},{"from":"make_table","to":"save_draft"},
                          {"from":"save_draft","to":"END"}]}
                """);
        assertTrue(r.isPassed(), "有前置建表节点时应放行：" + r.toFeedback());
    }

    /**
     * 🔴 S2/D-new7 修掉的假阴性：建表节点与 save_draft 之间只有「条件边上那个多余的 to」相连时，
     *    旧实现认为「建表节点能走到 save_draft」，于是跳过主表存在性校验 —— 幽灵表被静默放过。
     *
     * <p>下面这份 DSL 里 {@code make_table} 的条件边真正通向的是 {@code approve} 与 {@code END}，
     * 运行期<b>永远走不到</b> {@code save_draft}（编译器对条件边从不读 {@code to}，
     * {@code DslToStateGraphCompiler:372-382}）。而 {@code EdgeDTO#to} 的 javadoc 只说「条件边时为空」、
     * 没有任何强制点，DSL 又是 LLM 产的，所以这个形状真的会出现。
     *
     * <p>本用例与 {@code GraphReachabilityTest#conditionalEdgeWithStrayToIsIgnoredLikeTheCompiler} 分工：
     * 那条钉「遍历原语新旧确实不同、且新的与编译器一致」，这条钉「修好之后校验真的报出来了」。
     */
    @Test
    void validate_saveDraftMainTableBehindStrayConditionalTo_rejected() throws Exception {
        AgentValidateResult r = validate("""
                {"id":"wf",
                 "variables":[{"key":"input","type":"object"},{"key":"draft","type":"object"}],
                 "nodes":[
                   {"id":"make_table","type":"tool","config":{"toolId":"datamodel_create","outputVar":"tbl"}},
                   {"id":"approve","type":"tool","config":{"toolId":"form_publish_bundle","outputVar":"pub"}},
                   {"id":"save_draft","type":"tool","config":{
                       "toolId":"form_save_draft","outputVar":"draft",
                       "args":{"name":"设备管理","code":"device_management","mainTable":"biz_device",
                               "fields":[{"field":"device_name","widget":"input","title":"设备名称"}]}}}],
                 "edges":[{"from":"START","to":"make_table"},
                          {"from":"make_table","type":"conditional","to":"save_draft",
                           "cases":{"ok":"approve"},"default":"END"},
                          {"from":"approve","to":"END"},
                          {"from":"save_draft","to":"END"}]}
                """);
        assertFalse(r.isPassed(), "条件边的多余 to 不是通路，主表守卫不能被它绕过：" + r.toFeedback());
        assertTrue(r.toFeedback().contains("biz_device"), "要点名那张幽灵表：" + r.toFeedback());
    }

    /** 反向对照：把上面那条多余的 {@code to} 去掉，判定不变（说明报出的错来自「真不可达」而非「形状怪异」）。 */
    @Test
    void validate_saveDraftMainTableBehindConditionalWithoutStrayTo_rejected() throws Exception {
        AgentValidateResult r = validate("""
                {"id":"wf",
                 "variables":[{"key":"input","type":"object"},{"key":"draft","type":"object"}],
                 "nodes":[
                   {"id":"make_table","type":"tool","config":{"toolId":"datamodel_create","outputVar":"tbl"}},
                   {"id":"approve","type":"tool","config":{"toolId":"form_publish_bundle","outputVar":"pub"}},
                   {"id":"save_draft","type":"tool","config":{
                       "toolId":"form_save_draft","outputVar":"draft",
                       "args":{"name":"设备管理","code":"device_management","mainTable":"biz_device",
                               "fields":[{"field":"device_name","widget":"input","title":"设备名称"}]}}}],
                 "edges":[{"from":"START","to":"make_table"},
                          {"from":"make_table","type":"conditional",
                           "cases":{"ok":"approve"},"default":"END"},
                          {"from":"approve","to":"END"},
                          {"from":"save_draft","to":"END"}]}
                """);
        assertFalse(r.isPassed(), "同样走不到 save_draft，判定必须一致：" + r.toFeedback());
        assertTrue(r.toFeedback().contains("biz_device"), "要点名那张幽灵表：" + r.toFeedback());
    }

    @Test
    void validate_withLlmNode_passed() throws Exception {
        AgentValidateResult r = validate("""
                {"id":"wf",
                 "variables":[{"key":"input","type":"object"},{"key":"txt","type":"string"}],
                 "nodes":[
                   {"id":"gen","type":"llm","config":{"prompt":"hi","outputVar":"txt"}}
                 ],
                 "edges":[{"from":"START","to":"gen"},{"from":"gen","to":"END"}]}
                """);
        assertTrue(r.isPassed(), "含 llm 节点应通过：" + r.toFeedback());
    }

    @Test
    void validate_agentLoopType_isKnown() throws Exception {
        // agent_loop 无执行器 bean，但属「子图类型」，基础校验应放行（深校验由 dryRunValidate 兜）
        AgentValidateResult r = validate("""
                {"id":"wf",
                 "variables":[{"key":"input","type":"object"},{"key":"ans","type":"string"}],
                 "nodes":[{"id":"loop","type":"agent_loop","config":{"tools":["data_interface_list"],"outputVar":"ans"}}],
                 "edges":[{"from":"START","to":"loop"},{"from":"loop","to":"END"}]}
                """);
        assertTrue(r.isPassed(), "agent_loop 是已知子图类型，基础校验应通过：" + r.toFeedback());
    }

    @Test
    void validate_conditionalEdge_passed() throws Exception {
        AgentValidateResult r = validate("""
                {"id":"wf",
                 "variables":[{"key":"input","type":"object"}],
                 "nodes":[
                   {"id":"c","type":"condition.rule","config":{"branches":[{"target":"yes","conditions":[]}],"default":"no"}},
                   {"id":"a","type":"tool","config":{"toolId":"data_interface_list"}},
                   {"id":"b","type":"tool","config":{"toolId":"data_interface_list"}}
                 ],
                 "edges":[
                   {"from":"START","to":"c"},
                   {"from":"c","type":"conditional","cases":{"yes":"a","no":"b"},"default":"b"},
                   {"from":"a","to":"END"},
                   {"from":"b","to":"END"}
                 ]}
                """);
        assertTrue(r.isPassed(), "合法条件边应通过：" + r.toFeedback());
    }

    // ==================== ② 各类基础错（文案写「怎么改」） ====================

    @Test
    void validate_emptyNodes_failed() throws Exception {
        AgentValidateResult r = validate("{\"id\":\"wf\",\"nodes\":[],\"edges\":[]}");
        assertFalse(r.isPassed());
        assertTrue(r.toFeedback().contains("nodes 为空"), r.toFeedback());
    }

    @Test
    void validate_illegalNodeId_failedWithSuggestion() throws Exception {
        AgentValidateResult r = validate("""
                {"id":"wf",
                 "nodes":[{"id":"OrderInfo","type":"tool","config":{"toolId":"x"}}],
                 "edges":[{"from":"START","to":"OrderInfo"},{"from":"OrderInfo","to":"END"}]}
                """);
        assertFalse(r.isPassed());
        // 文案要给出「怎么改」：建议一个小写合法的 id
        assertTrue(r.toFeedback().contains("order_info"), r.toFeedback());
    }

    @Test
    void validate_duplicateNodeId_failed() throws Exception {
        AgentValidateResult r = validate("""
                {"id":"wf",
                 "nodes":[{"id":"a","type":"tool","config":{"toolId":"x"}},{"id":"a","type":"llm","config":{"prompt":"p"}}],
                 "edges":[{"from":"START","to":"a"},{"from":"a","to":"END"}]}
                """);
        assertFalse(r.isPassed());
        assertTrue(r.toFeedback().contains("重复"), r.toFeedback());
    }

    @Test
    void validate_unknownType_failed() throws Exception {
        AgentValidateResult r = validate("""
                {"id":"wf",
                 "nodes":[{"id":"a","type":"magic_node","config":{}}],
                 "edges":[{"from":"START","to":"a"},{"from":"a","to":"END"}]}
                """);
        assertFalse(r.isPassed());
        assertTrue(r.toFeedback().contains("magic_node"), r.toFeedback());
    }

    @Test
    void validate_edgeEndpointMissing_failed() throws Exception {
        AgentValidateResult r = validate("""
                {"id":"wf",
                 "nodes":[{"id":"a","type":"tool","config":{"toolId":"x"}}],
                 "edges":[{"from":"START","to":"ghost"},{"from":"a","to":"END"}]}
                """);
        assertFalse(r.isPassed());
        assertTrue(r.toFeedback().contains("ghost"), r.toFeedback());
    }

    @Test
    void validate_conditionalWithoutCasesOrDefault_failed() throws Exception {
        AgentValidateResult r = validate("""
                {"id":"wf",
                 "nodes":[
                   {"id":"c","type":"condition.rule","config":{"branches":[]}},
                   {"id":"a","type":"tool","config":{"toolId":"x"}}
                 ],
                 "edges":[{"from":"START","to":"c"},{"from":"c","type":"conditional"},{"from":"a","to":"END"}]}
                """);
        assertFalse(r.isPassed());
        assertTrue(r.toFeedback().contains("条件边"), r.toFeedback());
    }

    @Test
    void validate_noStartEdge_failed() throws Exception {
        AgentValidateResult r = validate("""
                {"id":"wf",
                 "nodes":[{"id":"a","type":"tool","config":{"toolId":"x"}}],
                 "edges":[{"from":"a","to":"END"}]}
                """);
        assertFalse(r.isPassed());
        assertTrue(r.toFeedback().contains("START"), r.toFeedback());
    }

    // ==================== ③ 收集式：一次列多条 + 警告不阻断 ====================

    @Test
    void validate_collectsMultipleErrorsAtOnce() throws Exception {
        // 同时踩：type 未知 + 边端点不存在 + 无 END 入口 → 应一次收集 ≥2 条，而非遇第一个就停
        AgentValidateResult r = validate("""
                {"id":"wf",
                 "nodes":[{"id":"a","type":"magic","config":{}}],
                 "edges":[{"from":"START","to":"ghost"}]}
                """);
        assertFalse(r.isPassed());
        assertTrue(r.getErrors().size() >= 2,
                "收集式校验应一次列出多条错误，实际：" + r.getErrors());
    }

    // ==================== ④ Saga 可补偿性（待办 #5 / 设计方案「一之三」第 3 条） ====================

    @Test
    void validate_llmNodeDeclaresCompensate_warnsNonCompensable() throws Exception {
        // llm 节点 token 已耗、无法语义撤销，声明 recovery:compensate 不会生效 → 必须点破（警告不阻断）
        AgentValidateResult r = validate("""
                {"id":"wf",
                 "variables":[{"key":"input","type":"object"},{"key":"txt","type":"string"}],
                 "nodes":[{"id":"gen","type":"llm","recovery":"compensate",
                           "config":{"prompt":"hi","outputVar":"txt"}}],
                 "edges":[{"from":"START","to":"gen"},{"from":"gen","to":"END"}]}
                """);
        assertTrue(r.isPassed(), "可补偿性只警告、不阻断：" + r.toFeedback());
        assertTrue(r.getWarnings().stream().anyMatch(w -> w.contains("不可补偿")),
                "应警告 llm 节点不可补偿：" + r.getWarnings());
    }

    @Test
    void validate_knowledgeRetrievalWithCompensationBlock_warns() throws Exception {
        // 只带 compensation 块（没写 recovery）也算声明了反做，同样要点破
        AgentValidateResult r = validate("""
                {"id":"wf",
                 "variables":[{"key":"input","type":"object"},{"key":"docs","type":"object"}],
                 "nodes":[{"id":"kb","type":"knowledge_retrieval",
                           "compensation":{"compensate_ref":"tool_x"},
                           "config":{"outputVar":"docs"}}],
                 "edges":[{"from":"START","to":"kb"},{"from":"kb","to":"END"}]}
                """);
        assertTrue(r.isPassed(), "可补偿性只警告、不阻断：" + r.toFeedback());
        assertTrue(r.getWarnings().stream().anyMatch(w -> w.contains("不可补偿")),
                "应警告 knowledge_retrieval 节点不可补偿：" + r.getWarnings());
    }

    @Test
    void validate_toolNodeWithCompensate_noNonCompensableWarning() throws Exception {
        // tool 节点是否可补偿取决于具体工具，静态判不了 → 不该误报「不可补偿」
        AgentValidateResult r = validate("""
                {"id":"wf",
                 "variables":[{"key":"input","type":"object"},{"key":"out","type":"object"}],
                 "nodes":[{"id":"create","type":"tool","recovery":"compensate",
                           "compensation":{"compensate_ref":"tool_delete_record"},
                           "config":{"toolId":"tool_create_record","outputVar":"out"}}],
                 "edges":[{"from":"START","to":"create"},{"from":"create","to":"END"}]}
                """);
        assertTrue(r.isPassed(), "tool 声明 compensate 合法：" + r.toFeedback());
        assertFalse(r.getWarnings().stream().anyMatch(w -> w.contains("不可补偿")),
                "tool 节点不应被误判为不可补偿：" + r.getWarnings());
    }

    // ==================== ⑤ 引用可达性（🔴 警告不阻断 / Req6 向后兼容） ====================

    /** 引用可达性提示的独有片段，用于把该警告与其他警告（如 outputVar 未声明）区分开断言 */
    private static final String REACH_HINT = "不在它的上游";

    @Test
    void validate_referenceToDownstreamProducer_warnsButPasses() throws Exception {
        // first 引用 {{$.out}}，但 out 由其下游 second 产出 → 运行到 first 时还没有值：警告，但不阻断
        AgentValidateResult r = validate("""
                {"id":"wf",
                 "variables":[{"key":"input","type":"object"},{"key":"out","type":"object"},{"key":"txt","type":"string"}],
                 "nodes":[
                   {"id":"first","type":"llm","config":{"prompt":"用 {{$.out}} 拼一下","outputVar":"txt"}},
                   {"id":"second","type":"tool","config":{"toolId":"data_interface_list","outputVar":"out"}}
                 ],
                 "edges":[{"from":"START","to":"first"},{"from":"first","to":"second"},{"from":"second","to":"END"}]}
                """);
        assertTrue(r.isPassed(), "可达性只是警告，不应阻断：" + r.toFeedback());
        assertTrue(r.getWarnings().stream().anyMatch(w -> w.contains(REACH_HINT) && w.contains("out")),
                "应警告引用了下游产出的变量：" + r.getWarnings());
    }

    @Test
    void validate_referenceToUpstreamProducer_noWarning() throws Exception {
        // second 引用 {{$.out}}，out 由其上游 first 产出 → 正常，不该报可达性警告
        AgentValidateResult r = validate("""
                {"id":"wf",
                 "variables":[{"key":"input","type":"object"},{"key":"out","type":"object"},{"key":"txt","type":"string"}],
                 "nodes":[
                   {"id":"first","type":"tool","config":{"toolId":"data_interface_list","outputVar":"out"}},
                   {"id":"second","type":"llm","config":{"prompt":"用 {{$.out}}","outputVar":"txt"}}
                 ],
                 "edges":[{"from":"START","to":"first"},{"from":"first","to":"second"},{"from":"second","to":"END"}]}
                """);
        assertTrue(r.isPassed(), r.toFeedback());
        assertFalse(r.getWarnings().stream().anyMatch(w -> w.contains(REACH_HINT)),
                "引用上游产出属正常，不应报可达性警告：" + r.getWarnings());
    }

    @Test
    void validate_parallelJoinReferenceBothBranches_noWarning() throws Exception {
        // 汇合节点 j 同时引用两条并行分支 a/b 的产出；a、b 都是 j 的祖先 → 可达，不误报
        AgentValidateResult r = validate("""
                {"id":"wf",
                 "variables":[{"key":"input","type":"object"},{"key":"ra","type":"object"},{"key":"rb","type":"object"},{"key":"txt","type":"string"}],
                 "nodes":[
                   {"id":"p","type":"parallel","config":{"join":"all"}},
                   {"id":"a","type":"tool","config":{"toolId":"data_interface_list","outputVar":"ra"}},
                   {"id":"b","type":"tool","config":{"toolId":"data_interface_list","outputVar":"rb"}},
                   {"id":"j","type":"llm","config":{"prompt":"{{$.ra}} 与 {{$.rb}}","outputVar":"txt"}}
                 ],
                 "edges":[
                   {"from":"START","to":"p"},
                   {"from":"p","type":"conditional","cases":{"b1":"a","b2":"b"}},
                   {"from":"a","to":"j"},
                   {"from":"b","to":"j"},
                   {"from":"j","to":"END"}
                 ]}
                """);
        assertTrue(r.isPassed(), r.toFeedback());
        assertFalse(r.getWarnings().stream().anyMatch(w -> w.contains(REACH_HINT)),
                "并行汇合引用两分支产出属可达，不应误报：" + r.getWarnings());
    }

    @Test
    void validate_referenceDeclaredVariable_noReachabilityWarning() throws Exception {
        // 引用的 {{$.input}} 是声明变量（非节点产出）：可达性判定不适用，不该报警告
        AgentValidateResult r = validate("""
                {"id":"wf",
                 "variables":[{"key":"input","type":"object"}],
                 "nodes":[{"id":"a","type":"llm","config":{"prompt":"处理 {{$.input}}","outputVar":"input"}}],
                 "edges":[{"from":"START","to":"a"},{"from":"a","to":"END"}]}
                """);
        assertTrue(r.isPassed(), r.toFeedback());
        assertFalse(r.getWarnings().stream().anyMatch(w -> w.contains(REACH_HINT)),
                "引用声明变量不应触发可达性警告：" + r.getWarnings());
    }

    // ==================== ⑥ 工具引用（V1 存在 / V2 启用 / V3 agent_loop 只读） ====================

    /** 把 toolId 包进一份最小合法 DSL，好让每条用例只考工具这一件事 */
    private static String dslWithTool(String toolId) {
        return """
                {"id":"wf",
                 "variables":[{"key":"input","type":"object"},{"key":"out","type":"object"}],
                 "nodes":[{"id":"step","type":"tool","config":{"toolId":"%s","outputVar":"out"}}],
                 "edges":[{"from":"START","to":"step"},{"from":"step","to":"END"}]}
                """.formatted(toolId);
    }

    private static String dslWithAgentLoop(String toolsJson) {
        return """
                {"id":"wf",
                 "variables":[{"key":"input","type":"object"},{"key":"ans","type":"string"}],
                 "nodes":[{"id":"loop","type":"agent_loop",
                           "config":{"tools":%s,"outputVar":"ans"}}],
                 "edges":[{"from":"START","to":"loop"},{"from":"loop","to":"END"}]}
                """.formatted(toolsJson);
    }

    /**
     * V1：键打错是 LLM 产 DSL 的头号成因，而工具键在运行期照抄、不会再问模型 ——
     * 唯一暴露时机是用户点完确认后的那次执行失败。故必须在校验期报，且要给相近候选，
     * 否则模型只会换个别的错法（{@code AgentValidateResult} 类注释：说清怎么改）。
     */
    @Test
    void validate_unknownToolId_rejectedWithSimilarCandidate() throws Exception {
        AgentValidateResult r = validate(dslWithTool("datamodel_lst"));
        assertFalse(r.isPassed(), "认不出的工具键必须报错：" + r.toFeedback());
        assertTrue(r.toFeedback().contains("datamodel_lst"), "要点名写错的键：" + r.toFeedback());
        assertTrue(r.toFeedback().contains("datamodel_list"), "要给出相近候选：" + r.toFeedback());
    }

    /**
     * 🔴 钉住「没有相近候选时」那句兜底文案 —— 它<b>故意</b>与变更清单 {@code unresolved[]} 里那句不同。
     *
     * <p>清单按 {@code getEnabledToolDefList()} 认工具，只看得到启用行，所以说「不是启用中的自定义工具」准确；
     * 本处按 {@code resolveForGateway} 认，停用行也看得到，能走到这句就说明「已停用」已被 V2 报过、
     * 这个键压根没登记。照抄清单那句（「不是当前租户启用中的…」）会把模型引去「启用一下」，
     * 而它真正该做的是登记一个新工具 —— 与 D7「同一句话多处副本各自漂移」同病，故在此设强制点。
     */
    @Test
    void validate_unknownToolIdWithoutCandidate_saysNeverRegistered() throws Exception {
        AgentValidateResult r = validate(dslWithTool("zzz_no_such_tool_qqq"));
        assertFalse(r.isPassed(), r.toFeedback());
        assertTrue(r.toFeedback().contains("没登记过它"),
                "无候选时要说「压根没登记」，不能沿用清单那句「不是启用中的」：" + r.toFeedback());
        assertFalse(r.toFeedback().contains("启用中的自定义工具"),
                "那句会把模型引去「启用」而不是「登记」：" + r.toFeedback());
    }

    /** V2：停用的内置工具运行期会被网关拒，流程走到那一步必失败 */
    @Test
    void validate_disabledBuiltinTool_rejected() throws Exception {
        stubBuiltinTool("data_interface_list", RiskLevel.READ_ONLY, RiskLevel.READ_ONLY, false);
        AgentValidateResult r = validate(dslWithTool("data_interface_list"));
        assertFalse(r.isPassed(), "停用的工具必须报错：" + r.toFeedback());
        assertTrue(r.toFeedback().contains("已停用"), "要说清是「停用」而不是「不存在」：" + r.toFeedback());
    }

    /**
     * 🔴 V2 之所以要问 {@code resolveForGateway} 而不是 {@code getEnabledToolDefList}：
     * 后者只返回启用行，于是「已停用的自定义工具」与「压根没这个键」长得一模一样，
     * V2 永远报不出来，用户会被 V1 的文案引去「登记一个已存在的东西」。
     */
    @Test
    void validate_disabledCustomTool_rejectedAsDisabledNotMissing() throws Exception {
        stubCustomTool("crm_query_customer", false, RiskLevel.READ_ONLY);
        AgentValidateResult r = validate(dslWithTool("crm_query_customer"));
        assertFalse(r.isPassed(), r.toFeedback());
        assertTrue(r.toFeedback().contains("已停用"),
                "停用的自定义工具要报「已停用」，不能报「不存在」：" + r.toFeedback());
    }

    @Test
    void validate_enabledCustomTool_passed() throws Exception {
        stubCustomTool("crm_query_customer", true, RiskLevel.READ_ONLY);
        AgentValidateResult r = validate(dslWithTool("crm_query_customer"));
        assertTrue(r.isPassed(), "当前租户启用中的自定义工具应放行：" + r.toFeedback());
    }

    /**
     * 自定义工具表读不到时：既不能判「存在」也不能判「不存在」。
     * 判不存在 = 把本来能跑的流程挡在发布外；判存在 = 放过一个运行期必被网关拒的键。
     * 两者都比「说清这项没校验成」更坏 —— 故降为 warning 且写明跳过的范围（D-new8 的纪律）。
     */
    @Test
    void validate_customToolCatalogUnavailable_warnsNotError() throws Exception {
        when(toolDefService.resolveForGateway("crm_query_customer"))
                .thenThrow(new RuntimeException("db down"));
        AgentValidateResult r = validate(dslWithTool("crm_query_customer"));
        assertTrue(r.isPassed(), "依赖故障不该把整份 DSL 判死：" + r.toFeedback());
        assertTrue(r.getWarnings().stream().anyMatch(w -> w.contains("只比对了内置注册表")),
                "必须留下一条「这项没校验成」的警告，不能静默：" + r.getWarnings());
    }

    @Test
    void validate_blankToolId_rejected() throws Exception {
        AgentValidateResult r = validate("""
                {"id":"wf",
                 "variables":[{"key":"input","type":"object"}],
                 "nodes":[{"id":"step","type":"tool","config":{}}],
                 "edges":[{"from":"START","to":"step"},{"from":"step","to":"END"}]}
                """);
        assertFalse(r.isPassed(), r.toFeedback());
        assertTrue(r.toFeedback().contains("缺少 toolId"), "要直接说缺的是 toolId：" + r.toFeedback());
    }

    /**
     * V3：自主循环里的模型调用<b>不逐次过 Plan 确认</b>，放一个写工具进去
     * 等于让模型无人值守地改数据（违背平台铁律「用户确认前不执行任何写操作」）。
     */
    @Test
    void validate_agentLoopWithWriteTool_rejected() throws Exception {
        AgentValidateResult r = validate(dslWithAgentLoop("[\"data_interface_list\",\"datamodel_create\"]"));
        assertFalse(r.isPassed(), "agent_loop 里的写工具必须拦下：" + r.toFeedback());
        assertTrue(r.toFeedback().contains("只允许只读工具"), r.toFeedback());
        assertTrue(r.toFeedback().contains("datamodel_create"), "要点名那个写工具：" + r.toFeedback());
    }

    /**
     * 🔴 D11 的双态用例：一行 {@code agent_tool_override} 把 IRREVERSIBLE 改写成 READ_ONLY 之后，
     * 生效值说它只读、声明值说它不可逆。V3 必须仍然拦住 —— 否则整个只读约束
     * 会被一行租户数据静默推倒，而界面上什么都看不出来。
     *
     * <p>这条也是变异探针的锚点：把 {@code declaredRiskOf} 改回读 {@code getRisk()}，本用例必须变红。
     */
    @Test
    void validate_agentLoopWithWriteToolDowngradedByOverride_rejected() throws Exception {
        stubBuiltinTool("datamodel_create", RiskLevel.IRREVERSIBLE, RiskLevel.READ_ONLY, true);
        AgentValidateResult r = validate(dslWithAgentLoop("[\"datamodel_create\"]"));
        assertFalse(r.isPassed(), "被租户覆盖成只读的写工具，仍必须按声明风险拦住：" + r.toFeedback());
        assertTrue(r.toFeedback().contains("不可逆"),
                "文案要报出声明风险级，而不是被改写后的那个：" + r.toFeedback());
    }

    @Test
    void validate_agentLoopWithCustomWriteTool_rejected() throws Exception {
        stubCustomTool("crm_delete_customer", true, RiskLevel.HIGH);
        AgentValidateResult r = validate(dslWithAgentLoop("[\"crm_delete_customer\"]"));
        assertFalse(r.isPassed(), r.toFeedback());
        assertTrue(r.toFeedback().contains("只允许只读工具"), r.toFeedback());
    }

    @Test
    void validate_agentLoopWithCustomReadOnlyTool_passed() throws Exception {
        stubCustomTool("crm_query_customer", true, RiskLevel.READ_ONLY);
        AgentValidateResult r = validate(dslWithAgentLoop("[\"crm_query_customer\"]"));
        assertTrue(r.isPassed(), "只读的自定义工具进 agent_loop 应放行：" + r.toFeedback());
    }

    /** 反向对照：V3 只管 agent_loop。普通 tool 节点走 Plan 确认，放写工具是合法的 */
    @Test
    void validate_toolNodeWithIrreversibleTool_passed() throws Exception {
        AgentValidateResult r = validate(dslWithTool("datamodel_create"));
        assertTrue(r.isPassed(), "tool 节点允许写工具（由确认闸门兜）：" + r.toFeedback());
    }

    @Test
    void validate_agentLoopBlankToolEntry_rejected() throws Exception {
        AgentValidateResult r = validate(dslWithAgentLoop("[\"data_interface_list\",\"\"]"));
        assertFalse(r.isPassed(), r.toFeedback());
        assertTrue(r.toFeedback().contains("tools[] 里有一项是空的"), r.toFeedback());
    }

    // ==================== ⑦ 干跑结论折叠（V4/V5/V12） ====================

    private static ChangeItem changeItem(ChangeKind kind, String nodeName, DryRunResult dryRun) {
        ChangeItem item = new ChangeItem();
        item.setKind(kind);
        item.setNodeId("n1");
        item.setNodeName(nodeName);
        item.setDryRun(dryRun);
        return item;
    }

    private static ChangeManifest manifestOf(ChangeItem... items) {
        ChangeManifest manifest = new ChangeManifest();
        manifest.getChanges().addAll(List.of(items));
        return manifest;
    }

    private AgentValidateResult fold(ChangeManifest manifest) {
        AgentValidateResult result = new AgentValidateResult();
        validator.foldDryRunRejections(manifest, result);
        return result;
    }

    /**
     * 🔴 折叠前，这一份 DSL 在 {@code /validate} 上返回的是 {@code passed=true} + 一个带红标的清单 ——
     * 界面上同时写着「校验通过」和「这么改跑不通」。V4/V5/V12 修的就是这个自相矛盾。
     */
    @Test
    void fold_rejectedItem_blocksPassAndCarriesTheProbesOwnWords() {
        String reason = "表 biz_employee 已经在库里、且已登记进数据模型。请换一个表名，或直接改用那张已有的表";
        AgentValidateResult r = fold(manifestOf(
                changeItem(ChangeKind.TABLE_CREATE, "建员工表", DryRunResult.rejected(reason))));

        assertFalse(r.isPassed(), "被平台拒绝的变更必须阻断发布，否则绿章与红标同屏");
        assertEquals(1, r.getErrors().size(), r.toFeedback());
        String msg = r.getErrors().get(0);
        assertTrue(msg.contains("建员工表"), "要指名是哪个节点，「n1」等于没说：" + msg);
        assertTrue(msg.contains("新建数据表"), "要说出是哪一类变更：" + msg);
        assertTrue(msg.contains(reason), "探测拿回来的原话必须原样透传，不许改写：" + msg);
    }

    /**
     * 🔴 方案 §6.1 的分级：「<b>探测得出『不合法』才是 error，探测失败不是</b>」。
     *
     * <p>{@code NOT_PROBED} 今天混着「infra 挂了」「这类变更还没有干跑接口」「值要到运行期才定」三种，
     * 一律升 error 会让一份用了通用写工具的 DSL 一条都发不出去；一律降 warning 则会顶出一堆
     * 「本次没校验」，教会用户「这个黄标不用看」（{@code ChangeManifestDryRunner} 自己写下过的失败模式）。
     * 分开它们要给 {@code DryRunResult} 加判别位，属 S4b 的发布闸门 —— 本刀<b>一条都不折</b>，
     * 这条用例就是钉住「一条都不折」，将来加 warning 时必须显式改掉它。
     */
    @Test
    void fold_notProbedItems_neverBlockAndNeverWarn() {
        AgentValidateResult r = fold(manifestOf(
                changeItem(ChangeKind.TABLE_CREATE, "建表",
                        DryRunResult.notProbed("建表干跑没探成（infra 无响应），本次没校验它能不能跑通")),
                changeItem(ChangeKind.GENERIC_TOOL, "调外部接口",
                        DryRunResult.notProbed("平台无从预演这项变更：它改什么取决于运行期的参数与被调用方自己")),
                changeItem(ChangeKind.FORM_PUBLISH, "发布表单",
                        DryRunResult.notProbed("表单编号要到运行期才确定（{{$.save.id}}），无从干跑"))));

        assertTrue(r.isPassed(), "没探成不等于不合法：" + r.toFeedback());
        assertTrue(r.getErrors().isEmpty(), r.toFeedback());
        assertTrue(r.getWarnings().isEmpty(),
                "本刀刻意不给 NOT_PROBED 落 warning（理由见方法注释）：" + r.getWarnings());
    }

    /** 反向对照：探通了就是探通了，折叠不许把它变成任何一种阻断信号 */
    @Test
    void fold_passedItem_doesNotBlock() {
        AgentValidateResult r = fold(manifestOf(
                changeItem(ChangeKind.TABLE_CREATE, "建表",
                        DryRunResult.passed("预演通过：可以建这张表；这些改动尚未执行"))));

        assertTrue(r.isPassed(), r.toFeedback());
        assertTrue(r.getErrors().isEmpty(), r.toFeedback());
    }

    /** 收集式纪律在这里同样成立：一份 DSL 里三处跑不通，要一次说完，不能让人点三次「校验」 */
    @Test
    void fold_multipleRejectedItems_collectsAll() {
        AgentValidateResult r = fold(manifestOf(
                changeItem(ChangeKind.TABLE_CREATE, "建员工表", DryRunResult.rejected("表名已被占用，请换一个")),
                changeItem(ChangeKind.FORM_CREATE, "存表单草稿", DryRunResult.rejected("主表 biz_x 不存在")),
                changeItem(ChangeKind.PROCESS_DEPLOY, "部署审批流", DryRunResult.rejected("根节点的 type 必须是 10"))));

        assertFalse(r.isPassed(), r.toFeedback());
        assertEquals(3, r.getErrors().size(), "三项被拒就要三条 error：" + r.toFeedback());
    }

    /** 没有清单 = 纯静态校验（生成器那条自修正回路走的就是一档），不是「没有一项被拒」 */
    @Test
    void fold_nullManifest_isNoOp() {
        AgentValidateResult r = fold(null);

        assertTrue(r.isPassed(), r.toFeedback());
        assertTrue(r.getErrors().isEmpty(), r.toFeedback());
    }

    /**
     * 🔴 与 {@code validateSaveDraftMainTable} 里那个 {@code Set.of().contains(null)} 同一类教训：
     * 体检端点 500 = 用户既看不到错、也发不出去，比漏报更坏。
     * builder 产出的清单 {@code dryRun} 必不为 null，但本方法是 public 的，
     * 调用方可能自己拼一份没 enrich 的清单进来。
     */
    @Test
    void fold_nullDryRun_skippedInsteadOfThrowing() {
        AgentValidateResult r = fold(manifestOf(
                changeItem(ChangeKind.TABLE_CREATE, "建表", null),
                changeItem(ChangeKind.FORM_CREATE, "存表单草稿", DryRunResult.rejected("主表不存在"))));

        assertEquals(1, r.getErrors().size(), "跳过没结论的那项，另一项照常报：" + r.toFeedback());
        assertTrue(r.getErrors().get(0).contains("主表不存在"), r.toFeedback());
    }

    /**
     * 探测原话里带 {@code %} 不能把折叠自己炸掉。
     *
     * <p>{@code AgentValidateResult#addError} 是格式化入口，而 {@code REJECTED} 的 reason
     * 常常是 infra/bpm 抛回来的原话 —— 列注释里写「完成率%」在这套业务里是常事。
     * 原话必须走<b>参数</b>而不是拼进格式串，否则一条 {@code UnknownFormatConversionException}
     * 会把整个体检端点打成 500。
     */
    @Test
    void fold_reasonContainingPercentSign_survivesFormatting() {
        String reason = "列「完成率%」的名称不合法：只能包含小写字母、数字与下划线，请改成 completion_rate";
        AgentValidateResult r = fold(manifestOf(
                changeItem(ChangeKind.TABLE_ALTER, "改表", DryRunResult.rejected(reason))));

        assertFalse(r.isPassed(), r.toFeedback());
        assertTrue(r.getErrors().get(0).contains(reason), "原话要一字不改地传出去：" + r.getErrors().get(0));
    }

    /**
     * {@code unresolved[]} 里的「工具认不出 / 没填 toolId」<b>不折</b>：V1 已经独立报过同一件事，
     * 再折一遍就是同一个错在 errors 里出现两次（而 {@code toFeedback} 是给 LLM 看的，
     * 重复条目会白白吃掉它的注意力）。子流程那一档由 V10 负责。
     */
    @Test
    void fold_unresolvedItems_notFolded() {
        ChangeManifest manifest = new ChangeManifest();
        ChangeManifest.UnresolvedItem unresolved = new ChangeManifest.UnresolvedItem();
        unresolved.setType(ChangeManifest.UnresolvedItem.TYPE_TOOL_UNKNOWN);
        unresolved.setNodeName("查数据");
        unresolved.setTool("datamodel_lst");
        unresolved.setReason("不存在，相近的有 datamodel_list");
        manifest.getUnresolved().add(unresolved);

        AgentValidateResult r = fold(manifest);

        assertTrue(r.isPassed(), "unresolved 归 V1/V10 报，折叠不许重复：" + r.toFeedback());
        assertTrue(r.getErrors().isEmpty(), r.toFeedback());
    }

    // ==================== ⑦-b 已落地的撞车降一档（台账 #81 = S0 裁定① 的 A 方案） ====================

    private AgentValidateResult fold(ChangeManifest manifest, long executedCount) {
        AgentValidateResult result = new AgentValidateResult();
        validator.foldDryRunRejections(manifest, result, executedCount);
        return result;
    }

    /** 拼一项「被拒 + 撞上已有东西」（V5 那一类）；{@code conflictType} 取 {@code ChangeItem.Conflict.TYPE_*} */
    private static ChangeItem conflictedItem(ChangeKind kind, String nodeName, String reason,
                                             String conflictType, String conflictMessage) {
        ChangeItem item = changeItem(kind, nodeName, DryRunResult.rejected(reason));
        ChangeItem.Conflict conflict = new ChangeItem.Conflict();
        conflict.setType(conflictType);
        conflict.setMessage(conflictMessage);
        item.setConflict(conflict);
        return item;
    }

    private static ChangeItem tableTaken(String nodeName) {
        return conflictedItem(ChangeKind.TABLE_CREATE, nodeName,
                "表 biz_device 已存在，建表这一步会在运行期失败",
                ChangeItem.Conflict.TYPE_TABLE_ALREADY_EXISTS,
                "表 biz_device 已经在库里、且已登记进数据模型。请换一个表名，或直接改用那张已有的表");
    }

    /**
     * 🔴 撞车 + 跑过 N 次 ⟹ 降成 warning，发布放行（#81 的正题）。
     *
     * <p>降档前，一条早已上线、真跑过 3 次的建表流程<b>永远无法重新发布</b>：探到的「表名已被占用」
     * 占用的正是它自己当初建的那张表。而回滚会造出同 code 的新草稿，那个草稿同样发不出去 ——
     * 于是「回滚」这个功能对这类流程等于不存在（S0 存量盘点里 10 行 PUBLISHED/fail 的成因）。
     *
     * <p>🔴 文案必须<b>同时</b>说清四件事，少一件就会被读成「没问题了」：
     * 判据（被拉起过几次）、<b>这个数是尝试次数而不是成功次数</b>（E7）、
     * 放行不等于重跑能过（真执行到那一节点仍会被拒）、
     * 以及那东西若是<b>别人的</b>该怎么办（改名）。
     */
    @Test
    void fold_conflictWithExecutionHistory_downgradesToWarningAndSaysWhy() {
        AgentValidateResult r = fold(manifestOf(tableTaken("建设备表")), 3L);

        assertTrue(r.isPassed(), "已落地的撞车不该拦发布：" + r.toFeedback());
        assertTrue(r.getErrors().isEmpty(), r.toFeedback());
        assertEquals(1, r.getWarnings().size(), r.toFeedback());
        String msg = r.getWarnings().get(0);
        assertTrue(msg.contains("建设备表"), "要指名是哪个节点：" + msg);
        assertTrue(msg.contains("3 次"), "判据必须是量出来的执行次数，不是「我觉得应该没事」：" + msg);
        // 🔴 E7：取数口是 COUNT(*) 不带 status 过滤 ⟹ 这 3 次里可能全是 FAILED / INTERRUPTED。
        //    不说出来，读的人就把它当成「成功跑通过 3 次」，而降档的推理恰恰要靠它当证据。
        //    刻意不改数法只改说法：改成只数 SUCCEEDED 会让「建完表却在后面节点炸掉」的行归零，
        //    重新犯台账 #81 那个病（有执行史却被判从没跑过而拦下）。
        assertTrue(msg.contains("含失败与中断的尝试"), "必须写明这是尝试次数，不是成功次数：" + msg);
        assertTrue(msg.contains("已经在库里"), "撞车的出路要照着说，别让人再去猜：" + msg);
        assertTrue(msg.contains("仍会被拒"), "🔴 放行不等于修好了 —— 少了这句就会被读成「没问题了」：" + msg);
        assertTrue(msg.contains("改个名字"), "那东西若属于另一个流程，出路是改名而不是继续复用：" + msg);
    }

    /**
     * 🔴 没有执行史就<b>一档都不降</b>：同一种撞车，跑过 0 次仍然是 error。
     *
     * <p>降档是放行方向，故证据缺席时落在 fail-close 那一边。这条用例与上一条只差 {@code executedCount}，
     * 两条一起才排除了「把降档写成无条件」的变异 —— 那会让「新建一条与别人重名的流程」也直接发出去。
     */
    @Test
    void fold_conflictWithoutExecutionHistory_stillBlocks() {
        AgentValidateResult r = fold(manifestOf(tableTaken("建设备表")), 0L);

        assertFalse(r.isPassed(), r.toFeedback());
        assertEquals(1, r.getErrors().size(), r.toFeedback());
        assertTrue(r.getWarnings().isEmpty(), "没跑过就没有降档的证据：" + r.getWarnings());
    }

    /**
     * 🔴 只降「撞车」，V4 / V12 的 REJECTED 跑过多少次都<b>还是 error</b>。
     *
     * <p>这两类的 {@code conflict} 是 null：V4 是「这份 DDL 本身写错了」、V12 是「在线表单主表缺
     * {@code process_instance_id}」，都与「东西已经在平台上」无关 —— 跑过 99 次也不改变参数是错的，
     * 降档就是放一条真缺陷过去（而 V4/V12 防的恰恰是 IRREVERSIBLE 的运行期失败：表建了一半）。
     *
     * <p>这条是整节里<b>最该守住</b>的一条：把判据从 {@code conflict != null} 「简化」成
     * {@code outcome == REJECTED} 的代码看起来更整齐，上面两条用例照样全绿。
     */
    @Test
    void fold_nonConflictRejections_neverDowngradeEvenWithHistory() {
        AgentValidateResult r = fold(manifestOf(
                changeItem(ChangeKind.TABLE_ALTER, "改表",
                        DryRunResult.rejected("列「完成率%」的名称不合法：只能包含小写字母、数字与下划线")),
                changeItem(ChangeKind.FORM_CREATE, "存表单草稿",
                        DryRunResult.rejected("在线表单的主表缺 process_instance_id 列，发布后回写会失败"))),
                99L);

        assertFalse(r.isPassed(), "V4/V12 与「东西已存在」无关，执行史再多也不降：" + r.toFeedback());
        assertEquals(2, r.getErrors().size(), r.toFeedback());
        assertTrue(r.getWarnings().isEmpty(), r.toFeedback());
    }

    /** 一份清单里两类都有：撞车那条降、V4 那条不降 —— 分档是<b>逐项</b>判的，不是整份清单一刀切 */
    @Test
    void fold_mixedItems_downgradesPerItemNotPerManifest() {
        AgentValidateResult r = fold(manifestOf(
                tableTaken("建设备表"),
                changeItem(ChangeKind.TABLE_ALTER, "改表", DryRunResult.rejected("列名不合法"))),
                3L);

        assertFalse(r.isPassed(), "还剩一条 V4，整份仍不通过：" + r.toFeedback());
        assertEquals(1, r.getErrors().size(), r.toFeedback());
        assertTrue(r.getErrors().get(0).contains("列名不合法"), r.toFeedback());
        assertEquals(1, r.getWarnings().size(), r.toFeedback());
        assertTrue(r.getWarnings().get(0).contains("建设备表"), r.toFeedback());
    }

    /**
     * 🔴 两参重载（生成器那条自修正回路走的一档）必须<b>永不降档</b>。
     *
     * <p>它委托的是 {@code executedCount = 0}。若哪天有人把它改成「顺手查一下库」，
     * 生成器就会拿到一份「表名撞车只是 warning」的反馈，于是它认为自己修好了、停止自修正 ——
     * 而那份 DSL 落库后照样在运行期炸。这条用例钉住委托的常量。
     */
    @Test
    void fold_twoArgOverload_neverDowngrades() {
        AgentValidateResult r = fold(manifestOf(tableTaken("建设备表")));

        assertFalse(r.isPassed(), r.toFeedback());
        assertEquals(1, r.getErrors().size(), r.toFeedback());
    }

    /** 负数与 0 同效：调用方算错了也不该变成「放行」 */
    @Test
    void fold_negativeExecutionCount_treatedAsNoHistory() {
        AgentValidateResult r = fold(manifestOf(tableTaken("建设备表")), -5L);

        assertFalse(r.isPassed(), r.toFeedback());
        assertEquals(1, r.getErrors().size(), r.toFeedback());
    }

    // ==================== ⑧ 平台引用四查（V6 列 / V7 流程 / V8 模型 / V10 子流程） ====================

    /** 拼一个只含 form_save_draft 的 DSL；{@code fieldsJson} 是 fields 数组的 JSON 片段 */
    private static String saveDraftDsl(String mainTable, String fieldsJson) {
        return saveDraftDsl(mainTable, fieldsJson, "");
    }

    /**
     * 同上，但额外声明几个变量：{@code extraVarsJson} 是形如
     * {@code ,{"key":"col","type":"string"}} 的 JSON 片段（自带前导逗号，空串即不额外声明）。
     *
     * <p>为什么需要它：{@link WorkflowDslValidator} 早就有一条<b>引用可达性</b>规则
     * （{@code validatePlaceholders}，源码 :1959），它的 {@code collectUnresolvableRoots}
     * <b>会递归进 Map 与 List</b> ⟹ 嵌在 {@code args.fields[]} 里的 {@code {{$.col}}}
     * 只要 {@code col} 没声明，那条也会报 error。C6 那几条用例要证的是「<b>列名这一格本身</b>不许是
     * 占位符」，就得把 {@code col} 声明掉、把无关的那条 error 摘干净，否则绿的原因分不清是哪个。
     */
    private static String saveDraftDsl(String mainTable, String fieldsJson, String extraVarsJson) {
        return """
                {"id":"wf",
                 "variables":[{"key":"input","type":"object"},{"key":"draft","type":"object"}%s],
                 "nodes":[{"id":"save_draft","type":"tool","config":{
                     "toolId":"form_save_draft","outputVar":"draft",
                     "args":{"name":"设备管理","code":"device_management","mainTable":"%s",
                             "fields":%s}}}],
                 "edges":[{"from":"START","to":"save_draft"},{"from":"save_draft","to":"END"}]}
                """.formatted(extraVarsJson, mainTable, fieldsJson);
    }

    /** 拼一个只含 hitl.bpm_approval 的 DSL；{@code configJson} 是 config 对象的内容 */
    private static String bpmApprovalDsl(String configJson) {
        return """
                {"id":"wf",
                 "variables":[{"key":"input","type":"object"}],
                 "nodes":[{"id":"approve","type":"hitl.bpm_approval","config":{%s}}],
                 "edges":[{"from":"START","to":"approve"},{"from":"approve","to":"END"}]}
                """.formatted(configJson);
    }

    /** 拼一个只含 sub_workflow 的 DSL；{@code configJson} 是 config 对象的内容 */
    private static String subWorkflowDsl(String configJson) {
        return """
                {"id":"wf",
                 "variables":[{"key":"input","type":"object"}],
                 "nodes":[{"id":"sub","type":"sub_workflow","config":{%s}}],
                 "edges":[{"from":"START","to":"sub"},{"from":"sub","to":"END"}]}
                """.formatted(configJson);
    }

    // -------------------- V6：字段映射的列必须真存在 --------------------

    @Test
    void validate_saveDraftUnknownColumn_rejected() throws Exception {
        stubRegisteredTable("biz_device", "id", "device_name", "tenant_id");

        AgentValidateResult r = validate(saveDraftDsl("biz_device",
                "[{\"field\":\"设备名称\",\"column\":\"device_nmae\",\"widget\":\"input\",\"title\":\"设备名称\"}]"));

        assertFalse(r.isPassed(), "映射到不存在的列必须规划期拦下：" + r.toFeedback());
        assertTrue(r.toFeedback().contains("device_nmae"), "要点名写错的那个列：" + r.toFeedback());
        assertTrue(r.toFeedback().contains("device_name"), "要把现有列念出来，否则模型只能再猜一轮：" + r.toFeedback());
        assertTrue(r.toFeedback().contains("Unknown column"), "要说清不拦的后果：" + r.toFeedback());
    }

    /**
     * 🔴 口径必须与工具逐字一致：{@code FormSaveDraftTool:211} 是
     * {@code blankToDefault(field.column, field.field)} —— <b>column 缺省时用 field 顶</b>。
     *
     * <p>实测模型省略 {@code column} 的字段是多数，只读 {@code column} 的实现会把它们一个都校验不到，
     * 而它们恰恰是最容易写错的一批（field 是给表单看的中文名/驼峰名，column 是库里的 snake_case）。
     */
    @Test
    void validate_saveDraftColumnOmitted_fallsBackToFieldName() throws Exception {
        stubRegisteredTable("biz_device", "id", "tenant_id");

        AgentValidateResult r = validate(saveDraftDsl("biz_device",
                "[{\"field\":\"device_name\",\"widget\":\"input\",\"title\":\"设备名称\"}]"));

        assertFalse(r.isPassed(), "没写 column 时用 field 当列名，一样要校验：" + r.toFeedback());
        assertTrue(r.toFeedback().contains("device_name"), r.toFeedback());
    }

    @Test
    void validate_saveDraftAllColumnsExist_passed() throws Exception {
        stubRegisteredTable("biz_device", "id", "device_name", "production_date", "tenant_id");

        AgentValidateResult r = validate(saveDraftDsl("biz_device", """
                [{"field":"device_name","widget":"input","title":"设备名称"},
                 {"field":"production_date","column":"production_date","widget":"date","title":"生产日期"}]"""));

        assertTrue(r.isPassed(), r.toFeedback());
        assertTrue(r.getWarnings().isEmpty(), "列都对得上时不该留任何提示：" + r.getWarnings());
    }

    /** MySQL 的列名本身大小写不敏感，校验不该比数据库更严 */
    @Test
    void validate_saveDraftColumnCaseInsensitive_passed() throws Exception {
        stubRegisteredTable("biz_device", "id", "device_name");

        AgentValidateResult r = validate(saveDraftDsl("biz_device",
                "[{\"field\":\"设备名称\",\"column\":\"Device_Name\",\"widget\":\"input\",\"title\":\"设备名称\"}]"));

        assertTrue(r.isPassed(), r.toFeedback());
    }

    /**
     * 表在库里、但<b>从没登记进数据模型</b>：读不到列结构 ⟹ 只能提示，不能拦。
     *
     * <p>按方案 §6.1「探测失败不是 error」。而且这是个合法且常见的状态 ——
     * {@code getPhysicalTableList} 存在的理由就是「库里有一批物理表从没登记过」。
     */
    @Test
    void validate_saveDraftUnregisteredMainTable_columnCheckWarnsOnly() throws Exception {
        stubPhysicalTableOnly("biz_device");

        AgentValidateResult r = validate(saveDraftDsl("biz_device",
                "[{\"field\":\"whatever\",\"widget\":\"input\",\"title\":\"随便\"}]"));

        assertTrue(r.isPassed(), "没登记不等于列写错了：" + r.toFeedback());
        assertEquals(1, r.getWarnings().size(), r.toFeedback());
        String warning = r.getWarnings().get(0);
        assertTrue(warning.contains("没登记进数据模型"), warning);
        assertTrue(warning.contains("datamodel_import"), "要给出路：" + warning);
    }

    /**
     * 🔴 C6：列名写成占位符必须报 <b>error</b>，不是「静态无从判定，跳过」。
     *
     * <p>本用例原先叫 {@code validate_saveDraftPlaceholderColumn_skipped}，
     * 断言的<b>正是错误语义</b>，注释还写着「列名是占位符 = 值要到运行期才定，静态无从判定」。
     * 它把缺陷<b>钉死</b>了：谁想修都会先撞红这一条，于是「跳过」这个假理由被当成契约保护起来。
     * 与 D4-28 那次回归是同一条教训 —— <b>口径没写清时，测试会把错误口径钉死</b>。
     *
     * <p>实测的事实（读源码，非推断）：{@code ToolNodeExecutor:64-69} 解析 args 时
     * <b>只替换顶层字符串值</b>（{@code v instanceof String s ? resolve(s, ctx) : v}），
     * {@code fields} 是<b>数组</b> ⟹ 嵌在里面的 {@code column} <b>永远不会被替换</b>；
     * {@code FormSaveDraftTool} 自己也不做任何解析（全文没有 {@code StateTemplateResolver}）。
     * 所以 {@code {{$.col}}} 不是「运行期才有值」，而是一个<b>字面的垃圾列名</b>。
     */
    @Test
    void validate_saveDraftPlaceholderColumn_rejected() throws Exception {
        stubRegisteredTable("biz_device", "id");

        AgentValidateResult r = validate("""
                {"id":"wf",
                 "variables":[{"key":"input","type":"object"},{"key":"draft","type":"object"},
                              {"key":"col","type":"string"}],
                 "nodes":[{"id":"save_draft","type":"tool","config":{
                     "toolId":"form_save_draft","outputVar":"draft",
                     "args":{"name":"设备管理","code":"device_management","mainTable":"biz_device",
                             "fields":[{"field":"x","column":"{{$.col}}","widget":"input","title":"动态列"}]}}}],
                 "edges":[{"from":"START","to":"save_draft"},{"from":"save_draft","to":"END"}]}
                """);

        assertFalse(r.isPassed(), "这一格运行期不替换，占位符列名就是字面垃圾列名：" + r.toFeedback());
        assertEquals(1, r.getErrors().size(), "只该报一条（别再叠一条「列不存在」把同一根因说两遍）：" + r.toFeedback());
        String msg = r.getErrors().get(0);
        assertTrue(msg.contains("{{$.col}}"), "要把写错的那一格原样念出来：" + msg);
        assertTrue(msg.contains("变量替换") && msg.contains("原样透传"),
                "🔴 判据必须说清「这一格压根不替换」—— 否则会被读成「运行期能算出来、只是静态不知道」：" + msg);
        assertTrue(msg.contains("Unknown column"), "要说清不拦的后果（与「列不存在」那条同一个下场）：" + msg);
        assertTrue(msg.contains("请直接写表里真实存在的列名"), "要给出路：" + msg);
        assertTrue(msg.contains("做不到"), "🔴 还得说清「列名运行期才定」这个需求本身现在实现不了，别让人以为改个写法就行：" + msg);
    }

    /**
     * 🔴 C6 的落点比方案里写的更靠前一条：这条校验必须在<b>那两道 guard 之前</b>跑。
     *
     * <p>方案 §6.1 C6 的落点是「{@code validateSaveDraftColumns} 那一格换谓词 + 出 error」，
     * 但那个方法<b>只在 {@code mainTableExists} 为真时才被调用</b>，而更早还有一道 guard：
     * 本流程若有 {@code datamodel_create} 能走到 save_draft，<b>两项校验一起跳过</b>。
     *
     * <p>而「先建表、再存草稿」恰恰是<b>最常见</b>的形状（照原方案改，这条校验在最需要它的流程上
     * <b>永远不开口</b>）。占位符列名的判定<b>不需要读库</b> —— 与「表在不在」「表是不是自己建的」全无关，
     * 故它被提到 guard 之前独立成 {@code validateSaveDraftColumnPlaceholders}。
     *
     * <p>本用例不 stub 任何表：既证明这一格<b>不查库也能判</b>，也证明前置建表节点挡不住它。
     *
     * <p>🟡 {@code variables[]} 里<b>刻意声明了 {@code col}</b>：不是为了「让 DSL 合法」，
     * 而是为了把另一条无关的 error 摘掉 —— 引用可达性那条（:1959）会递归进 {@code args.fields[]}，
     * {@code col} 没声明时它也报一条，于是 {@code assertEquals(1, …)} 会红在<b>错误的</b>原因上
     * （本轮实测就是这么红的：{@code expected: <1> but was: <2>}）。
     * 声明掉之后剩下的唯一一条<b>只能</b>来自 C6，这条用例才真的钉住了「提到 guard 之前」这个落点。
     */
    @Test
    void validate_saveDraftPlaceholderColumn_rejectedEvenWhenTableCreatedInFlow() throws Exception {
        AgentValidateResult r = validate("""
                {"id":"wf",
                 "variables":[{"key":"input","type":"object"},{"key":"draft","type":"object"},
                              {"key":"col","type":"string"}],
                 "nodes":[
                   {"id":"make_table","type":"tool","config":{"toolId":"datamodel_create","outputVar":"tbl"}},
                   {"id":"save_draft","type":"tool","config":{
                       "toolId":"form_save_draft","outputVar":"draft",
                       "args":{"name":"设备管理","code":"device_management","mainTable":"biz_device",
                               "fields":[{"field":"x","column":"{{$.col}}","widget":"input","title":"动态列"}]}}}],
                 "edges":[{"from":"START","to":"make_table"},{"from":"make_table","to":"save_draft"},
                          {"from":"save_draft","to":"END"}]}
                """);

        assertFalse(r.isPassed(), "前置建表节点跳过的是「表/列此刻还不存在」那两项，不是这一项：" + r.toFeedback());
        assertEquals(1, r.getErrors().size(), "col 已声明 ⟹ 引用可达性那条不该报，剩下的只能是 C6 这一条：" + r.toFeedback());
        assertTrue(r.getErrors().get(0).contains("变量替换"), r.toFeedback());
    }

    /**
     * 🔴 谓词刻意<b>不</b>换成同源的 {@code StateTemplateResolver.containsPlaceholder}（方案原落点写的是换）。
     *
     * <p>判据要跟着「这一格运行期到底替不替换」走：{@code mainTable} <b>会</b>替换 ⟹ 那边必须与真做替换的
     * 正则同源；本格<b>不会</b>替换 ⟹ 用宽松的 {@code isPlaceholder}，与 {@code bpmProcessDefKey} /
     * {@code workflowId} 那两个端口同类。
     *
     * <p>畸形形状 `{{unclosed` 是两者的<b>唯一</b>分岔点：宽松谓词认出它「像占位符」⟹ 报本条 error；
     * 同源谓词认不出 ⟹ 它会掉进列存在性检查，被报成「列不存在」，而那条文案的出路是
     * 「加一个 datamodel_update 把缺的列补上」—— <b>照着做就会去建一个名叫 `{{unclosed` 的列</b>。
     * 与 {@code mainTableExists} 注释里点名的「教作者把垃圾表名建出来」是同一个坑，只是换到了列上。
     */
    @Test
    void validate_saveDraftMalformedPlaceholderColumn_rejectedAsPlaceholderNotAsMissingColumn() throws Exception {
        stubRegisteredTable("biz_device", "id", "device_name");

        AgentValidateResult r = validate(saveDraftDsl("biz_device",
                "[{\"field\":\"x\",\"column\":\"{{unclosed\",\"widget\":\"input\",\"title\":\"畸形\"}]"));

        assertFalse(r.isPassed(), r.toFeedback());
        assertEquals(1, r.getErrors().size(), r.toFeedback());
        String msg = r.getErrors().get(0);
        assertTrue(msg.contains("变量替换"), "要按「这一格不替换」报，而不是按「列不存在」报：" + msg);
        assertTrue(!msg.contains("不存在的列") && !msg.contains("datamodel_update"),
                "🔴 一旦掉进列存在性那条，出路就变成「去把这个垃圾列名建出来」：" + msg);
    }

    /**
     * 占位符写在 {@code field} 而<b>省略 {@code column}</b> 时一样要拦住。
     *
     * <p>{@code FormSaveDraftTool:211} 是 {@code blankToDefault(field.column, field.field)} ——
     * column 缺省时<b>用 field 顶</b>，所以那个占位符照样会变成列名。
     * 这条钉的是「两个消费者共用 {@code columnNameOf} 一份口径」：若占位符那半只读 {@code column}，
     * 实测模型省略 column 的那些字段（多数）一个都拦不到。
     */
    @Test
    void validate_saveDraftPlaceholderInFieldNameOnly_rejected() throws Exception {
        stubRegisteredTable("biz_device", "id");

        AgentValidateResult r = validate(saveDraftDsl("biz_device",
                "[{\"field\":\"{{$.col}}\",\"widget\":\"input\",\"title\":\"动态列\"}]",
                ",{\"key\":\"col\",\"type\":\"string\"}"));

        assertFalse(r.isPassed(), "column 缺省时用 field 顶，占位符照样落到列名上：" + r.toFeedback());
        assertEquals(1, r.getErrors().size(), "col 已声明 ⟹ 只剩 C6 这一条：" + r.toFeedback());
        assertTrue(r.toFeedback().contains("变量替换"), r.toFeedback());
    }

    // -------------------- #80：主表名运行期才定时，静态层必须与干跑层同口径地说「无从判定」 --------------------

    /**
     * 🔴 #80：主表名要到运行期才确定时，不许拿字面串去查库。
     *
     * <p>{@code mainTable} 是 {@code tool.args} 的<b>顶层字符串值</b>，运行期
     * {@code ToolNodeExecutor:68-69} 会对它调 {@code resolve} ⟹「运行期才算出表名」是个真能跑通的形状。
     * 存量 id=134 就这么跑了 <b>13 次全 SUCCEEDED</b>，而体检把它报成「数据库里没有这张表」。
     *
     * <p>🔴 那条假 error 的坏处不止是红一个字：它的文案还教作者「在 save_draft 之前加一个
     * datamodel_create 把 {{$.tbl}} 建出来」—— 照着做就把数据建进了一张名字是占位符字面量的垃圾表。
     * <b>假阳性给出了一个不可能成立的修法，这比单纯报错更坏。</b>
     */
    @Test
    void validate_saveDraftPlaceholderMainTable_skipped() throws Exception {
        AgentValidateResult r = validate("""
                {"id":"wf",
                 "variables":[{"key":"input","type":"object"},{"key":"draft","type":"object"},
                              {"key":"tbl","type":"string"}],
                 "nodes":[{"id":"save_draft","type":"tool","config":{
                     "toolId":"form_save_draft","outputVar":"draft",
                     "args":{"name":"设备管理","code":"device_management","mainTable":"{{$.tbl}}",
                             "fields":[{"field":"device_name","widget":"input","title":"设备名称"}]}}}],
                 "edges":[{"from":"START","to":"save_draft"},{"from":"save_draft","to":"END"}]}
                """);

        assertTrue(r.isPassed(), "运行期才定的表名不等于「表不存在」：" + r.toFeedback());
        assertEquals(1, r.getWarnings().size(), r.toFeedback());
        String warning = r.getWarnings().get(0);
        assertTrue(warning.contains("{{$.tbl}}"), "要念出那个占位符，作者才知道说的是哪一格：" + warning);
        assertTrue(warning.contains("存不存在"), warning);
        // 🔴 D-new8：warning 必须认领<b>真实</b>的跳过范围。这里跳过的不只主表存在性 ——
        //    mainTableExists 返回 false ⟹ validateSaveDraftColumns 压根没被调，列校验也一起跳了。
        //    只说「主表未校验」会让作者以为字段映射是被检查过的（那正是「假绿」）
        assertTrue(warning.contains("列"), "必须一并认领列校验也跳了：" + warning);
        // 肯定式对照（§5.3）：证明是「压根没查」而不是「查了、恰好查到」
        verify(dataModelApi, never()).getPhysicalTableList("{{$.tbl}}");
    }

    /**
     * 肯定式对照：字面表名查不到照样要拦。
     *
     * <p>没有这一条，上一条绿着也说明不了什么 —— 把 {@code mainTableExists} 整个删掉它一样绿
     * （§5.3「否定式断言必须配同 fixture 的肯定式对照」）。
     */
    @Test
    void validate_saveDraftLiteralMissingMainTable_stillRejected() throws Exception {
        AgentValidateResult r = validate(saveDraftDsl("biz_ghost",
                "[{\"field\":\"device_name\",\"widget\":\"input\",\"title\":\"设备名称\"}]"));

        assertFalse(r.isPassed(), "字面表名查不到就该拦：" + r.toFeedback());
        assertTrue(r.toFeedback().contains("biz_ghost"), r.toFeedback());
        assertTrue(r.getWarnings().isEmpty(), "这条路径不该退化成 warning：" + r.getWarnings());
    }

    /**
     * 🔴 钉住谓词选择的一半：「只有左半花括号、没有闭合」的畸形串<b>不算</b>占位符，必须照样拦。
     *
     * <p>本类自己那个 {@code isPlaceholder} 是 {@code contains("{{")}，用它这里就会静默跳过 ——
     * 而运行期 {@code StateTemplateResolver.PLACEHOLDER} 要求花括号成对闭合，
     * 匹配不上就<b>原样把字面串传下去</b>，工具那边一定拦。静态说「无从判定」、运行期说「不存在」
     * 就是两层口径分岔，而它从界面上完全看不出来。
     */
    @Test
    void validate_saveDraftMalformedPlaceholderMainTable_stillRejected() throws Exception {
        AgentValidateResult r = validate(saveDraftDsl("{{unclosed",
                "[{\"field\":\"device_name\",\"widget\":\"input\",\"title\":\"设备名称\"}]"));

        assertFalse(r.isPassed(), "运行期原样传下去的畸形串不是「无从判定」，是真的错：" + r.toFeedback());
        assertTrue(r.toFeedback().contains("{{unclosed"), r.toFeedback());
    }

    /**
     * 🔴 钉住谓词选择的另一半：裸键 {@code {{tbl}}} 运行期<b>真的会</b>被替换，必须跳过。
     *
     * <p>{@code StatePathNavigator} 明确支持「裸键即从根取一层」（{@code $.a} 与 {@code a} 等价），
     * 而本类的 {@code PLACEHOLDER_ROOT} 要求 {@code $.} 前缀、看不见裸键
     * （{@code StateTemplateResolver#referencedRootKeys} 的注释把这条差异写成「有意不同」）。
     * 这里要问的是「运行期会不会替换」，所以必须用与 {@code resolve} 同源的那个谓词。
     */
    @Test
    void validate_saveDraftBareKeyPlaceholderMainTable_skipped() throws Exception {
        AgentValidateResult r = validate(saveDraftDsl("{{tbl}}",
                "[{\"field\":\"device_name\",\"widget\":\"input\",\"title\":\"设备名称\"}]"));

        assertTrue(r.isPassed(), "裸键占位符运行期照样替换，静态无从判定：" + r.toFeedback());
        assertEquals(1, r.getWarnings().size(), r.toFeedback());
        verify(dataModelApi, never()).getPhysicalTableList("{{tbl}}");
    }

    /**
     * 🔴 钉住 D-new8 的主表那一半：查表故障只跳过<b>本节点</b>，不许把后面所有节点一起静默跳过。
     *
     * <p>改之前这里是 {@code return}：一次数据源抖动会让第二个节点绑的幽灵表照样放行，
     * 而 warning 文案写的是「biz_a 未校验」—— 用户按文案理解范围，实际少校验的却更多。
     */
    @Test
    void validate_mainTableQueryFailure_skipsOnlyThatNode() throws Exception {
        when(dataModelApi.getPhysicalTableList("biz_a")).thenThrow(new RuntimeException("数据源抖动"));
        // biz_b 用 setUp 的默认桩：空清单 = 表不存在

        AgentValidateResult r = validate("""
                {"id":"wf",
                 "variables":[{"key":"input","type":"object"},{"key":"d1","type":"object"},{"key":"d2","type":"object"}],
                 "nodes":[
                   {"id":"first","type":"tool","config":{"toolId":"form_save_draft","outputVar":"d1",
                       "args":{"mainTable":"biz_a","fields":[{"field":"a","widget":"input","title":"a"}]}}},
                   {"id":"second","type":"tool","config":{"toolId":"form_save_draft","outputVar":"d2",
                       "args":{"mainTable":"biz_b","fields":[{"field":"b","widget":"input","title":"b"}]}}}],
                 "edges":[{"from":"START","to":"first"},{"from":"first","to":"second"},{"from":"second","to":"END"}]}
                """);

        assertFalse(r.isPassed(), "第二个节点的幽灵表必须照样报出来：" + r.toFeedback());
        assertEquals(1, r.getErrors().size(), r.toFeedback());
        assertTrue(r.getErrors().get(0).contains("biz_b"), r.getErrors().get(0));
        assertEquals(1, r.getWarnings().size(), r.toFeedback());
        assertTrue(r.getWarnings().get(0).contains("biz_a"), r.getWarnings().get(0));
    }

    /** 读列结构这一步故障（登记信息在、结构读不到）同样只能提示 */
    @Test
    void validate_saveDraftStructureQueryFailure_warnsOnly() throws Exception {
        stubPhysicalTableOnly("biz_device");
        DataModelApi.DataModelSimpleRespDTO model = new DataModelApi.DataModelSimpleRespDTO();
        model.setId(9001L);
        model.setTableName("biz_device");
        when(dataModelApi.getDataModelList()).thenReturn(List.of(model));
        when(dataModelApi.getStructure(9001L)).thenThrow(new RuntimeException("infra 无响应"));

        AgentValidateResult r = validate(saveDraftDsl("biz_device",
                "[{\"field\":\"whatever\",\"widget\":\"input\",\"title\":\"随便\"}]"));

        assertTrue(r.isPassed(), "探测失败不是不合法：" + r.toFeedback());
        assertEquals(1, r.getWarnings().size(), r.toFeedback());
        assertTrue(r.getWarnings().get(0).contains("infra 无响应"), r.getWarnings().get(0));
    }

    /**
     * 表由本流程自己建、而建表参数读不出来：表与列两项都只能跳过，且<b>不许</b>反过来说「库里没这张表」
     *
     * <p>🔴 本用例原先叫 {@code bothChecksSkipped}，断言「连 warning 都不该有」。
     * V30 之后前半句仍成立（两项确实都跳过），后半句<b>反转</b>了：静默跳过正是本类要消灭的那类缺陷，
     * 所以现在必须留一句黄标认领。而「不落回查库那一路」这一半是本用例真正的价值 ——
     * 落回去会报「数据库里没有这张表，请加一个 datamodel_create 节点」，
     * 而 DSL 里<b>已经有一个</b>了：一句假话加一条错修法，模型照它改就是再叠一个建表节点。
     */
    @Test
    void validate_saveDraftInDslCreatorUnreadable_skipsBothChecksAndDoesNotInventAGhostTable() throws Exception {
        AgentValidateResult r = validate(createThenSaveDraftDsl("", "biz_device",
                "[{\"field\":\"device_name\",\"widget\":\"input\",\"title\":\"设备名称\"}]"));

        assertTrue(r.isPassed(), "读不出建表参数不是「表不存在」：" + r.toFeedback());
        assertEquals(1, r.getWarnings().size(), r.toFeedback());
        String warning = r.getWarnings().get(0);
        assertTrue(warning.contains("读不出来"), warning);
        assertTrue(warning.contains("make_table"), "要点名是哪个节点读不出来：" + warning);
        assertFalse(r.toFeedback().contains("数据库里没有这张表"),
                "DSL 里已经有一个建表节点，不许再叫模型加一个：" + r.toFeedback());
    }

    // -------------------- V30：字段必须落进「同 DSL 建表节点声明的列」 --------------------

    /**
     * 🔴 复现真实事故（「帮我做一个访客来访登记…」）：建表 7 列、表单 8 个字段，
     *    体检全绿 ⟹ 表真建出来（DDL 不可回滚）、草稿真存进去，一路成功到<b>最后一步</b>发布才报
     *    「表中不存在列(status)」。用户拿到的是一张失败卡片、一张回不去的物理表和一份发不出去的草稿。
     *
     * <p>这一条与 {@link #validate_saveDraftUnknownColumn_rejected}（V6，查库）分工：
     * V6 判的是「表<b>已经在库里</b>」的那种，而本流程自己建的表在体检期<b>还不存在</b>，
     * 查库那一路对它无话可说 —— 判据只能来自同一份 DSL 里的建表参数。
     */
    @Test
    void validate_saveDraftColumnMissingFromInDslCreator_rejected() throws Exception {
        AgentValidateResult r = validate(createThenSaveDraftDsl(
                createArgs("biz_visitor_registration", "visitor_name", "visit_reason", "visit_time"),
                "biz_visitor_registration", """
                [{"field":"visitor_name","widget":"input","title":"来访人"},
                 {"field":"visit_reason","widget":"textarea","title":"来访事由"},
                 {"field":"visit_time","widget":"datePicker","title":"来访时间"},
                 {"field":"status","widget":"input","title":"状态"}]"""));

        assertFalse(r.isPassed(), "建表参数就在同一份 DSL 里，漏列必须规划期拦下：" + r.toFeedback());
        String error = r.getErrors().get(0);
        assertTrue(error.contains("status"), "要点名漏掉的那一列：" + error);
        assertTrue(error.contains("visitor_name"), "要把已声明的列念出来，否则模型只能再猜一轮：" + error);
        assertTrue(error.contains("make_table"), "要说清该去改哪个节点：" + error);
        assertTrue(error.contains("不可回滚"), "要说清为什么这条比别的列错更贵：" + error);
        assertTrue(error.contains("tenant_id"),
                "系统列名单要念出来，否则模型会把 create_time 也补进建表参数（那会被建表校验直接拦掉）："
                        + error);
        assertFalse(r.toFeedback().contains("数据库里没有这张表"),
                "根因是漏列，不是没表 —— 两条一起报会把模型推向「再加一个建表节点」：" + r.toFeedback());
    }

    /** 列都对得上时一句提示都不该留（大小写不敏感，与 MySQL 同口径） */
    @Test
    void validate_saveDraftColumnsAllDeclaredInFlow_passed() throws Exception {
        AgentValidateResult r = validate(createThenSaveDraftDsl(
                createArgs("biz_device", "device_name", "production_date"), "biz_device", """
                [{"field":"device_name","widget":"input","title":"设备名称"},
                 {"field":"生产日期","column":"Production_Date","widget":"datePicker","title":"生产日期"}]"""));

        assertTrue(r.isPassed(), r.toFeedback());
        assertTrue(r.getWarnings().isEmpty(), "列都对得上时不该留任何提示：" + r.getWarnings());
    }

    /**
     * 表单字段<b>可以</b>映射系统列（比如把创建时间显示出来），而建表参数里写系统列会被
     * {@code DataModelCreateValidator} 直接拦掉 —— 所以系统列那一半必须并进判据，
     * 不并的话每个映射 {@code create_time} 的字段都是一条假 error。
     */
    @Test
    void validate_saveDraftFieldMappedToSystemColumn_passed() throws Exception {
        AgentValidateResult r = validate(createThenSaveDraftDsl(
                createArgs("biz_device", "device_name"), "biz_device", """
                [{"field":"device_name","widget":"input","title":"设备名称"},
                 {"field":"create_time","widget":"datePicker","title":"创建时间"}]"""));

        assertTrue(r.isPassed(), r.toFeedback());
        assertTrue(r.getWarnings().isEmpty(), r.getWarnings().toString());
    }

    /**
     * 建表节点建的是<b>别的</b>表（一主一子是常见编排）：子表的建表参数不该拿来判主表的列，
     * 而该落回查库那一路 —— 主表真不在库里时，报的就是根因正确的那条。
     */
    @Test
    void validate_saveDraftInDslCreatorForAnotherTable_fallsBackToDatabaseCheck() throws Exception {
        AgentValidateResult r = validate(createThenSaveDraftDsl(
                createArgs("biz_device_part", "part_name"), "biz_device",
                "[{\"field\":\"device_name\",\"widget\":\"input\",\"title\":\"设备名称\"}]"));

        assertFalse(r.isPassed(), "没人建 biz_device，就得报它不存在：" + r.toFeedback());
        assertTrue(r.toFeedback().contains("数据库里没有这张表"), r.toFeedback());
        assertFalse(r.toFeedback().contains("part_name"),
                "子表声明的列不该被拿来判主表：" + r.toFeedback());
    }

    /**
     * {@code datamodel_import} 登记的是<b>既有</b>物理表，有哪些列静态看不见 ⟹ 认不出，落回查库。
     * 表在库里、列也对得上 ⟹ 放行。
     */
    @Test
    void validate_saveDraftInDslImport_fallsBackToDatabaseCheck() throws Exception {
        stubRegisteredTable("biz_device", "id", "device_name", "tenant_id");

        AgentValidateResult r = validate("""
                {"id":"wf",
                 "variables":[{"key":"input","type":"object"},{"key":"tbl","type":"object"},
                              {"key":"draft","type":"object"}],
                 "nodes":[
                   {"id":"register","type":"tool","config":{"toolId":"datamodel_import","outputVar":"tbl",
                       "args":{"tableNames":["biz_device"]}}},
                   {"id":"save_draft","type":"tool","config":{
                       "toolId":"form_save_draft","outputVar":"draft",
                       "args":{"name":"设备管理","code":"device_management","mainTable":"biz_device",
                               "fields":[{"field":"device_name","widget":"input","title":"设备名称"}]}}}],
                 "edges":[{"from":"START","to":"register"},{"from":"register","to":"save_draft"},
                          {"from":"save_draft","to":"END"}]}
                """);

        assertTrue(r.isPassed(), r.toFeedback());
    }

    /**
     * 🔴 {@code model} 被模型序列化成一个 JSON <b>字符串</b>时照样读得出来
     *
     * <p>这正是 {@code WorkflowDslValidator#declaredModelOf} 借运行期那个 {@code DataModelParamResolver}
     * 而不是自己 {@code JSONUtil.parseObj} 的理由：只认对象形态的话，同一份 DSL 在执行期能跑通、
     * 在这一侧却被当成「读不出来」—— 于是 V30 静默降级成一句黄标，漏列照样到最后一步才炸。
     */
    @Test
    void validate_saveDraftModelGivenAsJsonString_stillRead() throws Exception {
        String model = "{\\\"tableName\\\":\\\"biz_device\\\",\\\"columns\\\":"
                + "[{\\\"columnName\\\":\\\"device_name\\\",\\\"columnType\\\":\\\"STRING\\\"}]}";
        AgentValidateResult r = validate(createThenSaveDraftDsl(
                ",\"args\":{\"model\":\"" + model + "\"}", "biz_device", """
                [{"field":"device_name","widget":"input","title":"设备名称"},
                 {"field":"status","widget":"input","title":"状态"}]"""));

        assertFalse(r.isPassed(), "串形态的 model 也必须读得出声明的列：" + r.toFeedback());
        assertTrue(r.getErrors().get(0).contains("status"), r.getErrors().get(0));
    }

    /**
     * 🔴 系统列名单读的是一次<b>不接受任何入参</b>的全局读 ⟹ 故障必然持续 ⟹ 整趟停手、且只认领一次
     * （{@code warnProbeAborted} 那条纪律；与「按节点入参查表」的 V6 只能跳过本节点正相反）。
     *
     * <p>⚠️ 顺带钉住反面写法：把「探不到」缓存成<b>空集</b>的话，空集在判据里恰好等于
     * 「一个系统列都没有」，于是一次 infra 抖动被翻译成一批假 error，
     * 附送一个教模型去建 {@code create_time} 的假修法。
     */
    @Test
    void validate_saveDraftSystemColumnProbeFailure_warnsOnceForWholeTrip() throws Exception {
        when(dataModelApi.getTableConstraints()).thenThrow(new RuntimeException("infra 无响应"));

        AgentValidateResult r = validate("""
                {"id":"wf",
                 "variables":[{"key":"input","type":"object"},{"key":"t1","type":"object"},
                              {"key":"t2","type":"object"},{"key":"d1","type":"object"},
                              {"key":"d2","type":"object"}],
                 "nodes":[
                   {"id":"make_a","type":"tool","config":{"toolId":"datamodel_create","outputVar":"t1",
                       "args":{"model":{"tableName":"biz_a","columns":[
                           {"columnName":"name","columnType":"STRING"}]}}}},
                   {"id":"save_a","type":"tool","config":{"toolId":"form_save_draft","outputVar":"d1",
                       "args":{"mainTable":"biz_a","fields":[
                           {"field":"whatever","widget":"input","title":"随便"}]}}},
                   {"id":"make_b","type":"tool","config":{"toolId":"datamodel_create","outputVar":"t2",
                       "args":{"model":{"tableName":"biz_b","columns":[
                           {"columnName":"name","columnType":"STRING"}]}}}},
                   {"id":"save_b","type":"tool","config":{"toolId":"form_save_draft","outputVar":"d2",
                       "args":{"mainTable":"biz_b","fields":[
                           {"field":"whatever","widget":"input","title":"随便"}]}}}],
                 "edges":[{"from":"START","to":"make_a"},{"from":"make_a","to":"save_a"},
                          {"from":"save_a","to":"make_b"},{"from":"make_b","to":"save_b"},
                          {"from":"save_b","to":"END"}]}
                """);

        assertTrue(r.isPassed(), "名单读不到 ⟹ 判据缺一半，不能拿它去报 error：" + r.toFeedback());
        assertEquals(1, r.getWarnings().size(), "同一次故障只许认领一次：" + r.getWarnings());
        String warning = r.getWarnings().get(0);
        assertTrue(warning.contains("infra 无响应"), warning);
        assertTrue(warning.contains("全部未校验"), "要说清跳过的范围是整趟，不是这一个节点：" + warning);
    }

    /** 建表参数里<b>一个业务列都没声明</b>：判据仍然成立（表单每个字段都落空），且报错不许念出一句空清单 */
    @Test
    void validate_saveDraftInDslCreatorWithoutColumns_rejected() throws Exception {
        AgentValidateResult r = validate(createThenSaveDraftDsl(
                ",\"args\":{\"model\":{\"tableName\":\"biz_device\",\"columns\":[]}}", "biz_device",
                "[{\"field\":\"device_name\",\"widget\":\"input\",\"title\":\"设备名称\"}]"));

        assertFalse(r.isPassed(), r.toFeedback());
        assertTrue(r.getErrors().get(0).contains("一个业务列都没声明"), r.getErrors().get(0));
    }

    /**
     * 拼一份「本流程先建表、再存表单草稿」的 DSL；V30 的用例都基于它
     *
     * @param createArgsJson {@code datamodel_create} 的 {@code ,"args":{…}} 片段
     *                       （<b>自带前导逗号</b>，与 {@link #saveDraftDsl} 的 {@code extraVarsJson} 同一约定）。
     *                       传空串即「这个节点压根没有 args」—— V30 认不出的第 ③ 种形状
     */
    private static String createThenSaveDraftDsl(String createArgsJson, String mainTable, String fieldsJson) {
        return """
                {"id":"wf",
                 "variables":[{"key":"input","type":"object"},{"key":"tbl","type":"object"},
                              {"key":"draft","type":"object"}],
                 "nodes":[
                   {"id":"make_table","type":"tool","config":{"toolId":"datamodel_create","outputVar":"tbl"%s}},
                   {"id":"save_draft","type":"tool","config":{
                       "toolId":"form_save_draft","outputVar":"draft",
                       "args":{"name":"设备管理","code":"device_management","mainTable":"%s",
                               "fields":%s}}}],
                 "edges":[{"from":"START","to":"make_table"},{"from":"make_table","to":"save_draft"},
                          {"from":"save_draft","to":"END"}]}
                """.formatted(createArgsJson, mainTable, fieldsJson);
    }

    /** 一段 {@code datamodel_create} 的 args：建 {@code tableName}，业务列就是给的那几个（一律 STRING） */
    private static String createArgs(String tableName, String... columns) {
        StringBuilder sb = new StringBuilder();
        for (String column : columns) {
            if (!sb.isEmpty()) {
                sb.append(',');
            }
            sb.append("{\"columnName\":\"").append(column).append("\",\"columnType\":\"STRING\"}");
        }
        return ",\"args\":{\"model\":{\"tableName\":\"%s\",\"columns\":[%s]}}"
                .formatted(tableName, sb);
    }

    // -------------------- V7：bpmProcessDefKey 必须指向已部署的流程 --------------------

    @Test
    void validate_bpmApprovalUnknownKey_rejected() throws Exception {
        stubBpmModels(bpmModel("oa_leave", "请假", true));

        AgentValidateResult r = validate(bpmApprovalDsl("\"bpmProcessDefKey\":\"oa_trip\""));

        assertFalse(r.isPassed(), r.toFeedback());
        assertTrue(r.toFeedback().contains("oa_trip"), "要点名那个查不到的标识：" + r.toFeedback());
        assertTrue(r.toFeedback().contains("process_create_and_deploy"), "要给出路：" + r.toFeedback());
    }

    @Test
    void validate_bpmApprovalUndeployed_rejected() throws Exception {
        stubBpmModels(bpmModel("oa_leave", "请假", false));

        AgentValidateResult r = validate(bpmApprovalDsl("\"bpmProcessDefKey\":\"oa_leave\""));

        assertFalse(r.isPassed(), "建好但没部署的流程发不起实例：" + r.toFeedback());
        assertTrue(r.toFeedback().contains("从未部署"), r.toFeedback());
        assertTrue(r.toFeedback().contains("请假"), "要报出流程名，让人知道撞上了哪一个：" + r.toFeedback());
    }

    @Test
    void validate_bpmApprovalDeployed_passed() throws Exception {
        stubBpmModels(bpmModel("oa_leave", "请假", true));

        AgentValidateResult r = validate(bpmApprovalDsl("\"bpmProcessDefKey\":\"oa_leave\""));

        assertTrue(r.isPassed(), r.toFeedback());
        assertTrue(r.getWarnings().isEmpty(), r.getWarnings().toString());
    }

    @Test
    void validate_bpmApprovalBlankKey_rejected() throws Exception {
        stubBpmModels(bpmModel("oa_leave", "请假", true));

        AgentValidateResult r = validate(bpmApprovalDsl("\"formVars\":{}"));

        assertFalse(r.isPassed(), r.toFeedback());
        assertTrue(r.toFeedback().contains("缺少 bpmProcessDefKey"), r.toFeedback());
    }

    /**
     * key 是<b>字面量</b>（{@code NodeIoContract:191} 登记为 {@code MODE_LITERAL}，
     * {@code AgentBpmApprovalService#resolveConfig} 也是 {@code configStr} 直取）：
     * 占位符会被原样当成流程标识去发起，故不能让它落到「查不到这个流程」那句上 ——
     * 那会把模型往「换个 key」引，而它该改的是「别用占位符」。
     */
    @Test
    void validate_bpmApprovalPlaceholderKey_rejected() throws Exception {
        stubBpmModels(bpmModel("oa_leave", "请假", true));

        AgentValidateResult r = validate("""
                {"id":"wf",
                 "variables":[{"key":"input","type":"object"},{"key":"procKey","type":"string"}],
                 "nodes":[{"id":"approve","type":"hitl.bpm_approval",
                           "config":{"bpmProcessDefKey":"{{$.procKey}}"}}],
                 "edges":[{"from":"START","to":"approve"},{"from":"approve","to":"END"}]}
                """);

        assertFalse(r.isPassed(), r.toFeedback());
        assertEquals(1, r.getErrors().size(), "只该报「这一格不做变量替换」这一件事：" + r.toFeedback());
        assertTrue(r.getErrors().get(0).contains("不做变量替换"), r.getErrors().get(0));
    }

    /** 本流程自己会部署这个流程：不能要求它「此刻已部署」 */
    @Test
    void validate_bpmApprovalDeployedByThisFlow_skipped() throws Exception {
        stubBpmModels();

        AgentValidateResult r = validate("""
                {"id":"wf",
                 "variables":[{"key":"input","type":"object"},{"key":"proc","type":"object"}],
                 "nodes":[
                   {"id":"deploy","type":"tool","config":{"toolId":"process_create_and_deploy","outputVar":"proc"}},
                   {"id":"approve","type":"hitl.bpm_approval","config":{"bpmProcessDefKey":"oa_leave"}}],
                 "edges":[{"from":"START","to":"deploy"},{"from":"deploy","to":"approve"},
                          {"from":"approve","to":"END"}]}
                """);

        assertTrue(r.isPassed(), "「建流程 → 发起审批」是合法的单流程编排：" + r.toFeedback());
    }

    @Test
    void validate_bpmApprovalApiFailure_warnsOnly() throws Exception {
        when(bpmModelApi.getModelList(isNull())).thenThrow(new RuntimeException("bpm 无响应"));

        AgentValidateResult r = validate(bpmApprovalDsl("\"bpmProcessDefKey\":\"oa_leave\""));

        assertTrue(r.isPassed(), "探测失败不是不合法：" + r.toFeedback());
        assertEquals(1, r.getWarnings().size(), r.toFeedback());
        assertTrue(r.getWarnings().get(0).contains("bpm 无响应"), r.getWarnings().get(0));
    }

    /**
     * 🔴 匹配的是流程<b>标识</b>（key），不是流程<b>名称</b>（name）。
     *
     * <p>{@code BpmModelApi#getModelList(String)} 那个入参过滤的是<b>名称</b>，
     * 拿 key 去当名称传只会漏；故校验路径传 null、在客户端按 key 比。
     * 这条用例钉住「名称撞上不算撞上」。
     */
    @Test
    void validate_bpmApprovalMatchesKeyNotName() throws Exception {
        stubBpmModels(bpmModel("oa_leave_real", "oa_leave", true));

        AgentValidateResult r = validate(bpmApprovalDsl("\"bpmProcessDefKey\":\"oa_leave\""));

        assertFalse(r.isPassed(), "流程名相同、标识不同 = 发不起实例：" + r.toFeedback());
        assertTrue(r.toFeedback().contains("查不到"), r.toFeedback());
    }

    // -------------------- V8：llm / condition.llm 的 modelId --------------------

    /**
     * 🔴 本条最容易写错的一半：<b>留空不报</b>。
     *
     * <p>{@code LlmNodeExecutor:61} 写着「modelId 留空 = 动态默认 + 故障切换」，
     * 那比钉死一个 id 更健壮。把它报成问题等于逼模型往差的那种写法上改。
     * 连库都不该查 —— 故这里用 {@code verify(never())} 而不只是断言没报错。
     */
    @Test
    void validate_llmBlankModelId_silentAndUnqueried() throws Exception {
        stubChatModels();

        AgentValidateResult r = validate("""
                {"id":"wf",
                 "variables":[{"key":"input","type":"object"},{"key":"txt","type":"string"}],
                 "nodes":[{"id":"gen","type":"llm","config":{"prompt":"hi","outputVar":"txt"}}],
                 "edges":[{"from":"START","to":"gen"},{"from":"gen","to":"END"}]}
                """);

        assertTrue(r.isPassed(), r.toFeedback());
        assertTrue(r.getWarnings().isEmpty(), r.getWarnings().toString());
        verify(aiModelApi, never()).getEnabledModelList(any());
    }

    /** 模型被删/停用是<b>运维状态</b>、不是这份 DSL 写错了，且删掉这一格就能绕过 ⟹ 只 warning */
    @Test
    void validate_llmUnknownModelId_warnsOnly() throws Exception {
        stubChatModels(1L);

        AgentValidateResult r = validate("""
                {"id":"wf",
                 "variables":[{"key":"input","type":"object"},{"key":"txt","type":"string"}],
                 "nodes":[{"id":"gen","type":"llm","config":{"prompt":"hi","outputVar":"txt","modelId":7}}],
                 "edges":[{"from":"START","to":"gen"},{"from":"gen","to":"END"}]}
                """);

        assertTrue(r.isPassed(), "查不到模型不该阻断发布：" + r.toFeedback());
        assertEquals(1, r.getWarnings().size(), r.toFeedback());
        assertTrue(r.getWarnings().get(0).contains("不在当前可用的聊天模型里"), r.getWarnings().get(0));
    }

    /**
     * 一份 DSL 里既有钉了 modelId 的、也有留空的：只有钉了的那个该被报。
     *
     * <p>「留空不报」那半边光靠 {@link #validate_llmBlankModelId_silentAndUnqueried} 钉不住 ——
     * 那份 DSL 一个 modelId 都没钉，压根走不到循环里（{@code anyPinned} 那道门就把它拦了）。
     * 真正考「循环内逐个跳过留空的」必须是这种混合形状。
     */
    @Test
    void validate_llmMixedPinnedAndBlank_onlyPinnedReported() throws Exception {
        stubChatModels(1L);

        AgentValidateResult r = validate("""
                {"id":"wf",
                 "variables":[{"key":"input","type":"object"},{"key":"txt","type":"string"},
                              {"key":"txt2","type":"string"}],
                 "nodes":[
                   {"id":"pinned","type":"llm","config":{"prompt":"hi","outputVar":"txt","modelId":7}},
                   {"id":"free","type":"llm","config":{"prompt":"hi","outputVar":"txt2"}}],
                 "edges":[{"from":"START","to":"pinned"},{"from":"pinned","to":"free"},
                          {"from":"free","to":"END"}]}
                """);

        assertTrue(r.isPassed(), r.toFeedback());
        assertEquals(1, r.getWarnings().size(), "留空的那个不该被报：" + r.toFeedback());
        assertTrue(r.getWarnings().get(0).contains("id=pinned"), r.getWarnings().get(0));
    }

    @Test
    void validate_llmKnownModelId_passed() throws Exception {
        stubChatModels(7L);

        AgentValidateResult r = validate("""
                {"id":"wf",
                 "variables":[{"key":"input","type":"object"},{"key":"txt","type":"string"}],
                 "nodes":[{"id":"gen","type":"llm","config":{"prompt":"hi","outputVar":"txt","modelId":7}}],
                 "edges":[{"from":"START","to":"gen"},{"from":"gen","to":"END"}]}
                """);

        assertTrue(r.isPassed(), r.toFeedback());
        assertTrue(r.getWarnings().isEmpty(), r.getWarnings().toString());
    }

    /**
     * 🔴 与非数字 modelId 一样是 <b>error</b>，而且这条真正钉的是「体检端点不许自己 500」：
     * {@code NodeDTO#configLong} 对任何非 {@code Number} 的值直接 {@code Long.parseLong}，
     * 而这一格<b>不走</b> {@code StateTemplateResolver}（只有 prompt/systemPrompt 走）。
     * 校验路径若也读 {@code configLong}，这份 DSL 就会把 /validate 打成 500 ——
     * 用户既看不到错、也发不出去，与 {@code Set.of().contains(null)} 是同一类雷。
     */
    @Test
    void validate_llmPlaceholderModelId_rejectedWithoutBlowingUp() throws Exception {
        stubChatModels(7L);

        AgentValidateResult r = validate("""
                {"id":"wf",
                 "variables":[{"key":"input","type":"object"},{"key":"txt","type":"string"},
                              {"key":"m","type":"string"}],
                 "nodes":[{"id":"gen","type":"llm",
                           "config":{"prompt":"hi","outputVar":"txt","modelId":"{{$.m}}"}}],
                 "edges":[{"from":"START","to":"gen"},{"from":"gen","to":"END"}]}
                """);

        assertFalse(r.isPassed(), r.toFeedback());
        assertTrue(r.toFeedback().contains("NumberFormatException"), r.toFeedback());
    }

    /** {@code condition.llm} 的 modelId 与 {@code llm} 同源（都是 {@code configLong} 直取），必须一起管 */
    @Test
    void validate_conditionLlmModelId_alsoChecked() throws Exception {
        stubChatModels(1L);

        AgentValidateResult r = validate("""
                {"id":"wf",
                 "variables":[{"key":"input","type":"object"},{"key":"branch","type":"string"}],
                 "nodes":[{"id":"judge","type":"condition.llm","config":{
                     "prompt":"判断","choices":["yes","no"],"default":"no",
                     "outputVar":"branch","modelId":7}}],
                 "edges":[{"from":"START","to":"judge"},{"from":"judge","to":"END"}]}
                """);

        assertTrue(r.isPassed(), r.toFeedback());
        assertEquals(1, r.getWarnings().size(), "condition.llm 也要查：" + r.toFeedback());
        assertTrue(r.getWarnings().get(0).contains("id=judge"), r.getWarnings().get(0));
    }

    @Test
    void validate_llmModelApiFailure_warnsOnly() throws Exception {
        when(aiModelApi.getEnabledModelList(1)).thenThrow(new RuntimeException("ai 无响应"));

        AgentValidateResult r = validate("""
                {"id":"wf",
                 "variables":[{"key":"input","type":"object"},{"key":"txt","type":"string"}],
                 "nodes":[{"id":"gen","type":"llm","config":{"prompt":"hi","outputVar":"txt","modelId":7}}],
                 "edges":[{"from":"START","to":"gen"},{"from":"gen","to":"END"}]}
                """);

        assertTrue(r.isPassed(), r.toFeedback());
        assertEquals(1, r.getWarnings().size(), r.toFeedback());
        assertTrue(r.getWarnings().get(0).contains("ai 无响应"), r.getWarnings().get(0));
    }

    // -------------------- V10：sub_workflow.workflowId 必须是可执行的子流程 --------------------

    @Test
    void validate_subWorkflowUnknownCode_rejected() throws Exception {
        when(defService.getSubWorkflowDef(anyString(), any()))
                .thenThrow(new ServiceException(1_041_006_006, "子流程(nope_wf)不存在或不可执行"));

        AgentValidateResult r = validate(subWorkflowDsl("\"workflowId\":\"nope_wf\""));

        assertFalse(r.isPassed(), r.toFeedback());
        assertTrue(r.toFeedback().contains("子流程用不了"), r.toFeedback());
        assertTrue(r.toFeedback().contains("nope_wf"), "要把服务层的原话透传出去：" + r.toFeedback());
    }

    /** 自己引用自己：编译期必然以「检测到循环引用」拒绝，且这一问压根不用查库 */
    @Test
    void validate_subWorkflowSelfReference_rejectedWithoutQuerying() throws Exception {
        AgentValidateResult r = validate(subWorkflowDsl("\"workflowId\":\"wf\""));

        assertFalse(r.isPassed(), r.toFeedback());
        assertTrue(r.toFeedback().contains("引用了它自己"), r.toFeedback());
        verify(defService, never()).getSubWorkflowDef(anyString(), any());
    }

    @Test
    void validate_subWorkflowBlankCode_rejected() throws Exception {
        AgentValidateResult r = validate(subWorkflowDsl("\"version\":1"));

        assertFalse(r.isPassed(), r.toFeedback());
        assertTrue(r.toFeedback().contains("缺少 workflowId"), r.toFeedback());
        assertTrue(r.toFeedback().contains("code"), "要点破「这个键装的是 code 不是数字编号」：" + r.toFeedback());
        verify(defService, never()).getSubWorkflowDef(anyString(), any());
    }

    @Test
    void validate_subWorkflowPlaceholderCode_rejected() throws Exception {
        AgentValidateResult r = validate("""
                {"id":"wf",
                 "variables":[{"key":"input","type":"object"},{"key":"sub_code","type":"string"}],
                 "nodes":[{"id":"sub","type":"sub_workflow","config":{"workflowId":"{{$.sub_code}}"}}],
                 "edges":[{"from":"START","to":"sub"},{"from":"sub","to":"END"}]}
                """);

        assertFalse(r.isPassed(), r.toFeedback());
        assertEquals(1, r.getErrors().size(), r.toFeedback());
        assertTrue(r.getErrors().get(0).contains("不做变量替换"), r.getErrors().get(0));
    }

    @Test
    void validate_subWorkflowPublished_passed() throws Exception {
        AgentValidateResult r = validate(subWorkflowDsl("\"workflowId\":\"child_wf\""));

        assertTrue(r.isPassed(), r.toFeedback());
        assertTrue(r.getWarnings().isEmpty(), r.getWarnings().toString());
    }

    /**
     * pin 了 version 就必须按那一版查：{@code getSubWorkflowDef} 比 {@code getPublishedByCode}
     * 多管一件事 —— pin 的那一版还是草稿时同样判「不可执行」。丢掉 version 就会给出一次假绿灯。
     */
    @Test
    void validate_subWorkflowPinnedVersion_queriedWithThatVersion() throws Exception {
        AgentValidateResult r = validate(subWorkflowDsl("\"workflowId\":\"child_wf\",\"version\":3"));

        assertTrue(r.isPassed(), r.toFeedback());
        verify(defService).getSubWorkflowDef("child_wf", 3);
    }

    /**
     * 🔴 version 填了个非数字（占位符是最常见的形状）时，体检端点<b>不许自己 500</b>：
     * {@code NodeDTO#configInt} 对任何非 {@code Number} 的值直接 {@code Integer.parseInt}。
     * 这里当「没 pin 版本」查，那一格填错由深层干跑报（编译器读的正是同一个 {@code configInt}，它收得住）。
     */
    @Test
    void validate_subWorkflowGarbageVersion_treatedAsUnpinnedInsteadOfThrowing() throws Exception {
        AgentValidateResult r = validate("""
                {"id":"wf",
                 "variables":[{"key":"input","type":"object"},{"key":"v","type":"string"}],
                 "nodes":[{"id":"sub","type":"sub_workflow",
                           "config":{"workflowId":"child_wf","version":"{{$.v}}"}}],
                 "edges":[{"from":"START","to":"sub"},{"from":"sub","to":"END"}]}
                """);

        assertTrue(r.isPassed(), r.toFeedback());
        verify(defService).getSubWorkflowDef("child_wf", null);
    }

    /** 非业务异常（依赖故障）与「子流程不可用」必须分开：前者只提示 */
    @Test
    void validate_subWorkflowDependencyFailure_warnsOnly() throws Exception {
        when(defService.getSubWorkflowDef(anyString(), any())).thenThrow(new RuntimeException("库连不上"));

        AgentValidateResult r = validate(subWorkflowDsl("\"workflowId\":\"child_wf\""));

        assertTrue(r.isPassed(), r.toFeedback());
        assertEquals(1, r.getWarnings().size(), r.toFeedback());
        assertTrue(r.getWarnings().get(0).contains("库连不上"), r.getWarnings().get(0));
    }

    // ==================== ⑨ 触发器 × 人工闸口（V11/V13/V14） ====================

    /**
     * 触发器块片段（含结尾逗号）。DSL 里写全名 {@code trigger.xxx}，落库时由
     * {@code WorkflowTriggerExtractor#normalizeType} 剥前缀 —— 校验器读的是<b>同一份 DSL</b>，
     * 故它也必须剥；下面 {@code validate_triggerTypeWrittenAsShortName_judgedTheSame} 钉这件事。
     */
    private static String trigger(String shortType) {
        return "\"trigger\":{\"type\":\"trigger." + shortType + "\"},";
    }

    /**
     * 「没有 trigger 块」：这种定义不会被任何自动入口拉起来，只能由人手动 {@code run-stream}。
     * 🔴 V11 的矩阵以<b>触发器</b>为行标题，{@code none} 没有对应行，故三条规则一起不适用
     * （理由写在 {@code validateTriggerRiskGates} 的注释里）。
     */
    private static final String NO_TRIGGER = "";

    /**
     * webhook 触发器块（含结尾逗号），config 由调用方给。
     * {@link #trigger(String)} 那种「只有 type」的写法在 webhook 上等于 {@code authType} 没填（= 不鉴权），
     * 会被 V15 拦下，故凡是要单考别的规则、又非得用 webhook 这一行的用例，都用本方法把鉴权配齐。
     */
    private static String webhookTrigger(String configJson) {
        return "\"trigger\":{\"type\":\"trigger.webhook\",\"config\":{%s}},".formatted(configJson);
    }

    /** 一个「配齐了鉴权」的 webhook config：V15 该闭嘴，其余规则照判 */
    private static final String AUTHED_WEBHOOK = "\"path\":\"order_hook\",\"authType\":\"hmac\",\"secret\":\"s3cr3t\"";

    /** START → 一个工具节点 → END */
    private static String toolFlowDsl(String triggerJson, String toolId) {
        return """
                {"id":"wf",%s
                 "variables":[{"key":"input","type":"object"},{"key":"out","type":"object"}],
                 "nodes":[{"id":"act","type":"tool","config":{"toolId":"%s","outputVar":"out"}}],
                 "edges":[{"from":"START","to":"act"},{"from":"act","to":"END"}]}
                """.formatted(triggerJson, toolId);
    }

    /** START → 人工闸口 → 工具 → END：闸口在<b>上游</b>，拦得住那次写操作 */
    private static String gatedToolFlowDsl(String triggerJson, String gateType,
                                           String gateConfigJson, String toolId) {
        return """
                {"id":"wf",%s
                 "variables":[{"key":"input","type":"object"},{"key":"out","type":"object"}],
                 "nodes":[{"id":"gate","type":"%s","config":{%s}},
                          {"id":"act","type":"tool","config":{"toolId":"%s","outputVar":"out"}}],
                 "edges":[{"from":"START","to":"gate"},{"from":"gate","to":"act"},
                          {"from":"act","to":"END"}]}
                """.formatted(triggerJson, gateType, gateConfigJson, toolId);
    }

    /** START → 工具 → 人工闸口 → END：闸口在<b>下游</b>，数据已经改完了才去问人 */
    private static String toolBeforeGateDsl(String triggerJson, String gateType,
                                            String gateConfigJson, String toolId) {
        return """
                {"id":"wf",%s
                 "variables":[{"key":"input","type":"object"},{"key":"out","type":"object"}],
                 "nodes":[{"id":"act","type":"tool","config":{"toolId":"%s","outputVar":"out"}},
                          {"id":"gate","type":"%s","config":{%s}}],
                 "edges":[{"from":"START","to":"act"},{"from":"act","to":"gate"},
                          {"from":"gate","to":"END"}]}
                """.formatted(triggerJson, toolId, gateType, gateConfigJson);
    }

    /** START → wait → END */
    private static String waitFlowDsl(String triggerJson, String waitConfigJson) {
        return """
                {"id":"wf",%s
                 "variables":[{"key":"input","type":"object"}],
                 "nodes":[{"id":"pause","type":"wait","config":{%s}}],
                 "edges":[{"from":"START","to":"pause"},{"from":"pause","to":"END"}]}
                """.formatted(triggerJson, waitConfigJson);
    }

    /** 一个已部署的 BPM 流程模型：凡 DSL 里带 {@code hitl.bpm_approval} 的用例都要先桩它，否则 V7 会插进来报错 */
    private void stubDeployedBpmModel() {
        stubBpmModels(bpmModel("oa_leave", "请假流程", true));
    }

    private static final String BPM_GATE_CONFIG = "\"bpmProcessDefKey\":\"oa_leave\"";

    // -------------------- V11：触发器 × 声明风险级的判定矩阵 --------------------

    /** 矩阵第 1 行：chat + IRREVERSIBLE + 上游无闸口 → error（chat 下两种闸口都算，故文案给的是 confirm） */
    @Test
    void validate_chatTriggerIrreversibleWithoutGate_rejected() throws Exception {
        AgentValidateResult r = validate(toolFlowDsl(trigger("chat"), "datamodel_create"));

        assertFalse(r.isPassed(), r.toFeedback());
        assertEquals(1, r.getErrors().size(), r.toFeedback());
        assertTrue(r.getErrors().get(0).contains("不可逆"), r.getErrors().get(0));
        assertTrue(r.getErrors().get(0).contains("hitl.confirm"), "chat 下要指认聊天确认卡：" + r.getErrors().get(0));
    }

    @Test
    void validate_chatTriggerIrreversibleWithConfirmGate_passed() throws Exception {
        AgentValidateResult r = validate(
                gatedToolFlowDsl(trigger("chat"), "hitl.confirm", "", "datamodel_create"));

        assertTrue(r.isPassed(), r.toFeedback());
        assertTrue(r.getWarnings().isEmpty(), r.getWarnings().toString());
    }

    /** 矩阵第 1 行明写「两者在 chat 下都能唤醒」：BPM 待办也是有人在场时的一条合法出路 */
    @Test
    void validate_chatTriggerIrreversibleWithBpmGate_passed() throws Exception {
        stubDeployedBpmModel();

        AgentValidateResult r = validate(gatedToolFlowDsl(trigger("chat"),
                "hitl.bpm_approval", BPM_GATE_CONFIG, "datamodel_create"));

        assertTrue(r.isPassed(), r.toFeedback());
        assertTrue(r.getWarnings().isEmpty(), r.getWarnings().toString());
    }

    /**
     * 🔴 分型矩阵的存在理由就是这一格：无头 + IRREVERSIBLE，上游<b>有</b> {@code hitl.confirm} 也不放行。
     *
     * <p>「上游有闸口」在 v1 的一刀切 formulation 下就算合格了，可定时任务跑起来时没有聊天窗口，
     * 那张确认卡推给谁？于是这条流程会静悄悄地卡死。两个 error 各说一件事：
     * V11 说「这个闸口在无头下不算数」，V13 说「无头里压根不许放 confirm」—— 刻意不合并成一条，
     * 否则模型改完一个还红着另一个，白烧一轮自修正。
     */
    @Test
    void validate_headlessIrreversibleWithConfirmGate_stillRejected() throws Exception {
        AgentValidateResult r = validate(gatedToolFlowDsl(trigger("schedule"),
                "hitl.confirm", "", "datamodel_create"));

        assertFalse(r.isPassed(), r.toFeedback());
        assertEquals(2, r.getErrors().size(), r.toFeedback());
        assertTrue(r.toFeedback().contains("声明风险是「不可逆」"), "V11 那条要在：" + r.toFeedback());
        assertTrue(r.toFeedback().contains("没有任何唤醒方"), "V13 那条也要在：" + r.toFeedback());
    }

    /**
     * 🔴 方案 T5-② 的正面断言：无头 + IRREVERSIBLE + {@code hitl.bpm_approval} 必须<b>通过</b>。
     * 这一格是「分型没把唯一可用闸口堵死」的唯一证据 —— 它红了就意味着所有无头建表流程都发不出去。
     */
    @Test
    void validate_headlessIrreversibleWithBpmGate_passed() throws Exception {
        stubDeployedBpmModel();

        AgentValidateResult r = validate(gatedToolFlowDsl(trigger("schedule"),
                "hitl.bpm_approval", BPM_GATE_CONFIG, "datamodel_create"));

        assertTrue(r.isPassed(), r.toFeedback());
        assertTrue(r.getWarnings().isEmpty(), r.getWarnings().toString());
    }

    /**
     * 方案 T5-⑤：无头 + IRREVERSIBLE + 完全没有任何 hitl → error，且出路只给 bpm_approval
     *
     * <p>🔴 触发器用 {@code schedule} 而不是 {@code webhook}：后者缺 {@code authType} 时
     * V15 会<b>另外</b>报一条（那是独立的根因，见 ⑩ 节），叠在这里会让「只有 V11 一条」这个断言失真。
     */
    @Test
    void validate_headlessIrreversibleWithoutGate_rejected() throws Exception {
        AgentValidateResult r = validate(toolFlowDsl(trigger("schedule"), "datamodel_create"));

        assertFalse(r.isPassed(), r.toFeedback());
        assertEquals(1, r.getErrors().size(), r.toFeedback());
        assertTrue(r.getErrors().get(0).contains("hitl.bpm_approval"), r.getErrors().get(0));
        assertTrue(r.getErrors().get(0).contains("没有人在场"), "要说清为什么 confirm 不算：" + r.getErrors().get(0));
    }

    /** 闸口在下游 = 数据已经改完了才去问人，等于没闸口 */
    @Test
    void validate_gateDownstreamOfIrreversible_stillRejected() throws Exception {
        AgentValidateResult r = validate(toolBeforeGateDsl(trigger("chat"),
                "hitl.confirm", "", "datamodel_create"));

        assertFalse(r.isPassed(), r.toFeedback());
        assertTrue(r.toFeedback().contains("上游没有人工闸口"), r.toFeedback());
    }

    /**
     * 🔴 D11 的双态用例（方案点名要的那条）：租户用一行 {@code agent_tool_override}
     * 把 {@code datamodel_create} 的<b>生效</b>风险降成 READ_ONLY 之后，V11 仍必须报出来。
     *
     * <p>桩的就是「声明 IRREVERSIBLE / 生效 READ_ONLY」这个组合。读 {@code getRisk()} 的实现
     * 在这里会看见只读工具、直接放行「定时触发 → 无人值守建表」，且界面上什么都看不出来。
     */
    @Test
    void validate_irreversibleDowngradedByTenantOverride_stillRejected() throws Exception {
        stubBuiltinTool("datamodel_create", RiskLevel.IRREVERSIBLE, RiskLevel.READ_ONLY, true);

        AgentValidateResult r = validate(toolFlowDsl(trigger("schedule"), "datamodel_create"));

        assertFalse(r.isPassed(), "生效风险被数据降过级，声明风险仍是不可逆，必须照拦：" + r.toFeedback());
        assertTrue(r.toFeedback().contains("不可逆"), r.toFeedback());
    }

    /** 矩阵第 2 行：chat + 非 IRREVERSIBLE 的写操作 → 只 warning，不阻断 */
    @Test
    void validate_chatTriggerWriteWithoutGate_warnsOnly() throws Exception {
        AgentValidateResult r = validate(toolFlowDsl(trigger("chat"), "tool_delete_record"));

        assertTrue(r.isPassed(), r.toFeedback());
        assertEquals(1, r.getWarnings().size(), r.toFeedback());
        assertTrue(r.getWarnings().get(0).contains("高风险"), r.getWarnings().get(0));
    }

    /**
     * 🔴 多个无闸口写操作只出<b>一条</b> warning：一条流程里 10 个写节点刷 10 句一模一样的建议，
     * 那不是提醒，是在教读者「这个黄标不用看」。合并后仍要带上处数、最高风险级与具体位置。
     */
    @Test
    void validate_severalUnguardedWrites_warnOnceWithAllOfThem() throws Exception {
        AgentValidateResult r = validate("""
                {"id":"wf","trigger":{"type":"trigger.form_event"},
                 "variables":[{"key":"input","type":"object"},{"key":"a","type":"object"},
                               {"key":"b","type":"object"}],
                 "nodes":[{"id":"save","type":"tool","config":{"toolId":"form_save_draft","outputVar":"a"}},
                          {"id":"del","type":"tool","config":{"toolId":"tool_delete_record","outputVar":"b"}}],
                 "edges":[{"from":"START","to":"save"},{"from":"save","to":"del"},{"from":"del","to":"END"}]}
                """);

        assertTrue(r.isPassed(), r.toFeedback());
        assertEquals(1, r.getWarnings().size(), "两个无闸口写操作只能出一条：" + r.toFeedback());
        String warning = r.getWarnings().get(0);
        assertTrue(warning.contains("2 个"), warning);
        assertTrue(warning.contains("高风险"), "要带上最高那档：" + warning);
        assertTrue(warning.contains("save") && warning.contains("del"), "要点名到节点：" + warning);
    }

    /** 矩阵最后一行：READ_ONLY 无要求，一个黄标都不该有 */
    @Test
    void validate_headlessReadOnlyTools_silent() throws Exception {
        AgentValidateResult r = validate(toolFlowDsl(trigger("schedule"), "data_interface_list"));

        assertTrue(r.isPassed(), r.toFeedback());
        assertTrue(r.getWarnings().isEmpty(), "只读工具在无头下完全正常：" + r.getWarnings());
    }

    /**
     * 🔴 没有 trigger 块 → 三条规则一起不适用。这条钉的是「{@code none} 在矩阵里没有行」这个判定，
     * 而不是「碰巧没报」：同一份 DSL 一旦配上 {@code trigger.schedule} 就必须立刻报出来。
     */
    @Test
    void validate_noTriggerBlock_matrixNotApplicable() throws Exception {
        AgentValidateResult untriggered = validate(toolFlowDsl(NO_TRIGGER, "datamodel_create"));
        assertTrue(untriggered.isPassed(), "没有触发器 = 只能由人手动跑，矩阵无对应行：" + untriggered.toFeedback());
        assertTrue(untriggered.getWarnings().isEmpty(), untriggered.getWarnings().toString());

        AgentValidateResult scheduled = validate(toolFlowDsl(trigger("schedule"), "datamodel_create"));
        assertFalse(scheduled.isPassed(), "配上触发器就必须按那一行判：" + scheduled.toFeedback());
    }

    /** 全名与短名必须判成同一个触发器：落库那列存短名，两处口径漂了会让三条规则一起静默失效 */
    @Test
    void validate_triggerTypeWrittenAsShortName_judgedTheSame() throws Exception {
        AgentValidateResult fullName = validate(toolFlowDsl(trigger("schedule"), "datamodel_create"));
        AgentValidateResult shortName = validate("""
                {"id":"wf","trigger":{"type":"schedule"},
                 "variables":[{"key":"input","type":"object"},{"key":"out","type":"object"}],
                 "nodes":[{"id":"act","type":"tool","config":{"toolId":"datamodel_create","outputVar":"out"}}],
                 "edges":[{"from":"START","to":"act"},{"from":"act","to":"END"}]}
                """);

        assertFalse(fullName.isPassed(), fullName.toFeedback());
        assertFalse(shortName.isPassed(), "写短名也要判成无头：" + shortName.toFeedback());
        assertEquals(fullName.getErrors(), shortName.getErrors(), "两种写法的判定必须逐字一致");
    }

    /** 大小写与空白也要归一（同 {@code normalizeType} 的 trim + toLowerCase） */
    @Test
    void validate_triggerTypeWithPaddingAndUpperCase_judgedTheSame() throws Exception {
        AgentValidateResult r = validate("""
                {"id":"wf","trigger":{"type":"  TRIGGER.Schedule "},
                 "variables":[{"key":"input","type":"object"},{"key":"out","type":"object"}],
                 "nodes":[{"id":"act","type":"tool","config":{"toolId":"datamodel_create","outputVar":"out"}}],
                 "edges":[{"from":"START","to":"act"},{"from":"act","to":"END"}]}
                """);

        assertFalse(r.isPassed(), r.toFeedback());
        assertTrue(r.toFeedback().contains("定时任务"), "归一之后要认得出这是定时触发：" + r.toFeedback());
    }

    // -------------------- V13：无头触发里不许有 hitl.confirm --------------------

    @Test
    void validate_headlessHitlConfirm_rejected() throws Exception {
        AgentValidateResult r = validate("""
                {"id":"wf","trigger":{"type":"trigger.schedule"},
                 "variables":[{"key":"input","type":"object"},{"key":"ok","type":"boolean"}],
                 "nodes":[{"id":"ask","type":"hitl.confirm","config":{"approveVar":"ok"}}],
                 "edges":[{"from":"START","to":"ask"},{"from":"ask","to":"END"}]}
                """);

        assertFalse(r.isPassed(), r.toFeedback());
        assertEquals(1, r.getErrors().size(), r.toFeedback());
        assertTrue(r.getErrors().get(0).contains("hitl.bpm_approval"), "必须给出路，否则作者会把闸口整个删掉：" + r.getErrors().get(0));
        assertTrue(r.getErrors().get(0).contains("INTERRUPTED"), "要说清后果：" + r.getErrors().get(0));
    }

    /** 三种无头触发器都要拦：漏掉任何一种都是一个「静悄悄卡死」的入口 */
    @Test
    void validate_everyHeadlessTriggerRejectsHitlConfirm() throws Exception {
        for (String type : List.of("schedule", "webhook", "form_event")) {
            AgentValidateResult r = validate("""
                    {"id":"wf","trigger":{"type":"trigger.%s"},
                     "variables":[{"key":"input","type":"object"},{"key":"ok","type":"boolean"}],
                     "nodes":[{"id":"ask","type":"hitl.confirm","config":{"approveVar":"ok"}}],
                     "edges":[{"from":"START","to":"ask"},{"from":"ask","to":"END"}]}
                    """.formatted(type));

            assertFalse(r.isPassed(), type + " 触发也必须拦：" + r.toFeedback());
        }
    }

    /** 反向对照：chat 下 {@code hitl.confirm} 是<b>唯一</b>正确的闸口，一个提示都不该有 */
    @Test
    void validate_chatHitlConfirm_passed() throws Exception {
        AgentValidateResult r = validate("""
                {"id":"wf","trigger":{"type":"trigger.chat"},
                 "variables":[{"key":"input","type":"object"},{"key":"ok","type":"boolean"}],
                 "nodes":[{"id":"ask","type":"hitl.confirm","config":{"approveVar":"ok"}}],
                 "edges":[{"from":"START","to":"ask"},{"from":"ask","to":"END"}]}
                """);

        assertTrue(r.isPassed(), r.toFeedback());
        assertTrue(r.getWarnings().isEmpty(), r.getWarnings().toString());
    }

    /**
     * 🔴 认不出来的触发器类型按<b>无头</b>处理（fail-close）。
     *
     * <p>四种已知触发器里只有 chat 有人在场，一个平台不认识的类型不可能是它；放行等于给一个
     * 谁都没审过的入口开绿灯。而从严不会造成「本来能发的发不出去」—— 这类 DSL 到发布时会被
     * {@code WorkflowTriggerExtractor} 以 {@code WORKFLOW_TRIGGER_CONFIG_INVALID} 拒掉。
     * ⚠️ 「类型不认识」本身刻意<b>不在校验器里报</b>：那是提取器的规则，抄一份就是 D7 那类多处副本。
     */
    @Test
    void validate_unknownTriggerType_treatedAsHeadless() throws Exception {
        AgentValidateResult r = validate("""
                {"id":"wf","trigger":{"type":"trigger.mystery"},
                 "variables":[{"key":"input","type":"object"},{"key":"ok","type":"boolean"}],
                 "nodes":[{"id":"ask","type":"hitl.confirm","config":{"approveVar":"ok"}}],
                 "edges":[{"from":"START","to":"ask"},{"from":"ask","to":"END"}]}
                """);

        assertFalse(r.isPassed(), r.toFeedback());
        assertTrue(r.toFeedback().contains("平台不认识的触发器"), "要把「按无头处理」这件事说出来：" + r.toFeedback());
    }

    // -------------------- V14：无头 + wait(event) 必须声明外部唤醒方 --------------------

    @Test
    void validate_headlessWaitEventWithoutWakeup_rejected() throws Exception {
        AgentValidateResult r = validate(
                waitFlowDsl(trigger("schedule"), "\"waitType\":\"event\",\"eventKey\":\"order_paid\""));

        assertFalse(r.isPassed(), r.toFeedback());
        assertEquals(1, r.getErrors().size(), r.toFeedback());
        String error = r.getErrors().get(0);
        assertTrue(error.contains("order_paid"), "要点名那个等不到的 eventKey：" + error);
        assertTrue(error.contains("externalWakeup"), "要给出路，并给可原样粘贴的那一段：" + error);
        assertTrue(error.contains("duration"), "另一条出路（改成定时等待）也要说：" + error);
    }

    /** 声明了唤醒方 → 降到 warning，且把声明原文摘要带上（发布时说过谁唤醒，事后要追得到） */
    @Test
    void validate_headlessWaitEventWithWakeup_warnsOnly() throws Exception {
        // 🔴 webhook 这一行必须带上鉴权，否则 V15 会另外报一条 error（那是独立根因，见 ⑩ 节），
        //    本条要考的只是「V14 声明了唤醒方 ⟹ 降级为 warning」
        AgentValidateResult r = validate(waitFlowDsl(webhookTrigger(AUTHED_WEBHOOK),
                "\"waitType\":\"event\",\"eventKey\":\"order_paid\","
                        + "\"externalWakeup\":{\"owner\":\"订单中心\",\"endpoint\":\"POST /agent/workflow/event\"}"));

        assertTrue(r.isPassed(), r.toFeedback());
        assertEquals(1, r.getWarnings().size(), r.toFeedback());
        assertTrue(r.getWarnings().get(0).contains("订单中心"), r.getWarnings().get(0));
    }

    /** 🔴 光写一句 note 不算声明：本条要逼作者回答的正是「<b>谁</b>来发、<b>从哪</b>发」 */
    @Test
    void validate_headlessWaitEventWakeupNoteOnly_stillRejected() throws Exception {
        AgentValidateResult r = validate(waitFlowDsl(trigger("schedule"),
                "\"waitType\":\"event\",\"eventKey\":\"order_paid\","
                        + "\"externalWakeup\":{\"note\":\"应该会有人调吧\"}"));

        assertFalse(r.isPassed(), "只抄一遍问题当答案，等于没声明：" + r.toFeedback());
        assertTrue(r.toFeedback().contains("没有声明外部唤醒方"), r.toFeedback());
    }

    /** 这一格不是对象（写成字符串）时 {@code configMap} 回 null，一律按「未声明」判，不另报类型错 */
    @Test
    void validate_headlessWaitEventWakeupAsString_treatedAsUndeclared() throws Exception {
        AgentValidateResult r = validate(waitFlowDsl(trigger("schedule"),
                "\"waitType\":\"event\",\"eventKey\":\"order_paid\",\"externalWakeup\":\"运维部\""));

        assertFalse(r.isPassed(), r.toFeedback());
        assertTrue(r.toFeedback().contains("没有声明外部唤醒方"), r.toFeedback());
    }

    /** {@code duration} 那一档有平台内的自动唤醒方（{@code WorkflowWaitResumeScheduler}），完全合法 */
    @Test
    void validate_headlessWaitDuration_passed() throws Exception {
        AgentValidateResult r = validate(
                waitFlowDsl(trigger("schedule"), "\"waitType\":\"duration\",\"duration\":600"));

        assertTrue(r.isPassed(), r.toFeedback());
        assertTrue(r.getWarnings().isEmpty(), "定时等待不该有任何提示：" + r.getWarnings());
    }

    /** 反向对照：chat 下的 {@code wait(event)} 不在本条射程内（V14 与 V13 一样只管无头） */
    @Test
    void validate_chatWaitEventWithoutWakeup_notJudged() throws Exception {
        AgentValidateResult r = validate(
                waitFlowDsl(trigger("chat"), "\"waitType\":\"event\",\"eventKey\":\"order_paid\""));

        assertTrue(r.isPassed(), r.toFeedback());
        assertTrue(r.getWarnings().isEmpty(), r.getWarnings().toString());
    }

    /**
     * 🔴 {@code waitType} 的大小写<b>敏感</b>，逐字对齐编译器 {@code validateWaitConfig} 那个 switch。
     *
     * <p>{@code EVENT} 会被编译器判「未知，仅支持 duration/event」—— 那份 DSL 压根跑不起来，
     * 根因只有一条。本条若放宽成不敏感，作者就会同时收到「waitType 未知」和「没声明唤醒方」两句，
     * 而改对大小写之后第二句自动消失：那是噪音，不是信息。
     * （取值合法性是编译器的权威，本类不重建结构校验 —— 见类注释。）
     */
    @Test
    void validate_headlessWaitWrongCasedWaitType_leftToTheCompiler() throws Exception {
        AgentValidateResult r = validate(
                waitFlowDsl(trigger("schedule"), "\"waitType\":\"EVENT\",\"eventKey\":\"order_paid\""));

        assertTrue(r.isPassed(), "取值非法由深层干跑独家报，本层不插一句：" + r.toFeedback());
        assertTrue(r.getWarnings().isEmpty(), r.getWarnings().toString());
    }

    // ==================== ⑩ 入站面 / 不可达 / 写而未声明（V15/V16/V18/V19） ====================

    // -------------------- V15：webhook 触发器必须配鉴权 --------------------

    /** START → 一个只读工具 → END：V11 与 V19 都该闭嘴，于是 webhook 那一格是本节唯一的声音 */
    private static String readOnlyToolFlowDsl(String triggerJson) {
        return toolFlowDsl(triggerJson, "data_interface_list");
    }

    /**
     * 🔴 {@code authType} 缺省时，提取器与入站验签<b>都不再</b>把它当成「不鉴权放行」而是直接拒；
     * V15 仍然要认得这一格没填，因为存量 DSL（提取器收紧之前入库的）和直改数据库的 DSL 走不到那两道。
     * 这条是 V15 的主用例：设计器原来把「无（仅靠不可猜的 path）」当成一个正常选项摆在下拉框里，
     * 而 path 其实是作者手填的（{@code WorkflowForm.vue}，两个仓库都没有随机串生成）。
     */
    @Test
    void validate_webhookWithoutAuthType_rejected() throws Exception {
        AgentValidateResult r = validate(readOnlyToolFlowDsl(trigger("webhook")));

        assertFalse(r.isPassed(), r.toFeedback());
        assertEquals(1, r.getErrors().size(), "只该有 V15 这一条：" + r.toFeedback());
        String error = r.getErrors().get(0);
        assertTrue(error.contains("authType=未填"), "要说清是「这一格空着」而不是某个具体取值：" + error);
        assertTrue(error.contains("免登录"), "要说清入口是 @PermitAll 的：" + error);
        assertTrue(error.contains("发布者身份"), "要说清它以谁的身份跑起来：" + error);
        assertTrue(error.contains("hmac") && error.contains("X-Webhook-Signature"),
                "出路要给到能照做的粒度（含请求头名）：" + error);
        assertTrue(error.contains("token") && error.contains("X-Webhook-Token"), "另一条出路也要给：" + error);
        assertTrue(error.contains("path 猜不到") || error.contains("别指望"),
                "必须拆掉「安全全靠不可猜的 path」那句假话：" + error);
        // 🔴 #72 起 verifyAuth 对 none 是一律拒的，文案若还说「一个字节都不校验」就成了假话
        assertFalse(error.contains("一个字节都不校验"), "别再声称运行期不校验——#72 后它已经会拒：" + error);
    }

    @Test
    void validate_webhookExplicitNone_rejected() throws Exception {
        AgentValidateResult r = validate(readOnlyToolFlowDsl(webhookTrigger("\"authType\":\"none\"")));

        assertFalse(r.isPassed(), r.toFeedback());
        assertEquals(1, r.getErrors().size(), r.toFeedback());
        assertTrue(r.getErrors().get(0).contains("authType=none"), r.getErrors().get(0));
    }

    /**
     * 提取器对这一格做了 {@code toLowerCase()}，故 {@code "NONE"} 与 {@code "none"} 落地时是同一件事。
     * 本条若不跟着不区分大小写，同一份 DSL 就会「体检全绿、落库/入站被拒」—— 正是本模块要消灭的那类缺陷。
     */
    @Test
    void validate_webhookNoneInUpperCase_rejected() throws Exception {
        AgentValidateResult r = validate(readOnlyToolFlowDsl(webhookTrigger("\"authType\":\"NONE\"")));

        assertFalse(r.isPassed(), "大小写不能成为绕过 V15 的路：" + r.toFeedback());
        assertEquals(1, r.getErrors().size(), r.toFeedback());
    }

    /** 填了纯空白：提取器 {@code str()} 会 trim 成空、随即拒收，故本条同样按「没配鉴权」判 */
    @Test
    void validate_webhookBlankAuthType_rejected() throws Exception {
        AgentValidateResult r = validate(readOnlyToolFlowDsl(webhookTrigger("\"authType\":\"   \"")));

        assertFalse(r.isPassed(), r.toFeedback());
        assertEquals(1, r.getErrors().size(), r.toFeedback());
    }

    @Test
    void validate_webhookHmacWithSecret_passed() throws Exception {
        AgentValidateResult r = validate(readOnlyToolFlowDsl(webhookTrigger(AUTHED_WEBHOOK)));

        assertTrue(r.isPassed(), r.toFeedback());
        assertTrue(r.getWarnings().isEmpty(), r.getWarnings().toString());
    }

    @Test
    void validate_webhookTokenWithSecret_passed() throws Exception {
        AgentValidateResult r = validate(readOnlyToolFlowDsl(
                webhookTrigger("\"path\":\"order_hook\",\"authType\":\"token\",\"secret\":\"s3cr3t\"")));

        assertTrue(r.isPassed(), r.toFeedback());
        assertTrue(r.getWarnings().isEmpty(), r.getWarnings().toString());
    }

    /** 文案要指名请求体落进哪个变量：作者配了自定义 {@code inputVar} 时，「input」是一句假话 */
    @Test
    void validate_webhookCustomInputVar_namedInMessage() throws Exception {
        AgentValidateResult r = validate(readOnlyToolFlowDsl(
                webhookTrigger("\"authType\":\"none\",\"inputVar\":\"payload\"")));

        assertFalse(r.isPassed(), r.toFeedback());
        assertTrue(r.getErrors().get(0).contains("payload"), r.getErrors().get(0));
    }

    /**
     * 🔴 未知 {@code authType} 与「配了签名模式却没 secret」<b>都归提取器管</b>（{@code applyWebhook}），
     * V15 一句话都不说 —— 一个根因只由一个权威报一遍（D7）。
     * 注意这与 {@code none} 那条<b>刻意重叠</b>不冲突：重叠的是「同一条 DSL 两处都拒」，
     * 这里分的是「取值合法性/密钥齐不齐」这两个 V15 压根不判的根因。
     */
    @Test
    void validate_webhookUnknownAuthType_leftToTheExtractor() throws Exception {
        AgentValidateResult r = validate(readOnlyToolFlowDsl(webhookTrigger("\"authType\":\"oauth2\"")));

        assertTrue(r.isPassed(), "取值合法性是提取器的权威，本层不插一句：" + r.toFeedback());
    }

    @Test
    void validate_webhookHmacWithoutSecret_leftToTheExtractor() throws Exception {
        AgentValidateResult r = validate(readOnlyToolFlowDsl(webhookTrigger("\"authType\":\"hmac\"")));

        assertTrue(r.isPassed(), "缺 secret 由落库前的提取器拦，本层不重复：" + r.toFeedback());
    }

    /** 另三种触发器没有对外的匿名入站面，V15 一律不判 */
    @Test
    void validate_nonWebhookTriggers_notJudged() throws Exception {
        for (String shortType : List.of("chat", "schedule", "form_event")) {
            AgentValidateResult r = validate(readOnlyToolFlowDsl(trigger(shortType)));
            assertTrue(r.isPassed(), shortType + " 不该被 V15 判：" + r.toFeedback());
        }
        assertTrue(validate(readOnlyToolFlowDsl(NO_TRIGGER)).isPassed(), "没有 trigger 块也不该被判");
    }

    // -------------------- V18：从 START 走不到的节点 --------------------

    /**
     * START → a → END，外加一个<b>没有任何边</b>连到它的写工具节点 b。
     * 触发器留空：V11 的矩阵以触发器为行标题，{@code none} 没有对应行，于是 b 的风险级不会另生一条噪音。
     */
    private static final String ORPHAN_WRITE_NODE_DSL = """
            {"id":"wf",
             "variables":[{"key":"input","type":"object"},{"key":"out","type":"object"}],
             "nodes":[{"id":"a","type":"tool","config":{"toolId":"data_interface_list","outputVar":"out"}},
                      {"id":"b","type":"tool","config":{"toolId":"datamodel_create","outputVar":"out"}}],
             "edges":[{"from":"START","to":"a"},{"from":"a","to":"END"}]}
            """;

    /**
     * 🔴 文案必须说「它永远不会执行」，不能说「图不连通」。
     * 后者是图论术语，作者读完不知道该干什么；前者直接给出后果 ——
     * 而这个 b 恰恰是个 IRREVERSIBLE 的写工具，「你以为它会建表，它其实一次都没跑」。
     */
    @Test
    void validate_unreachableWriteNode_saysItNeverRuns() throws Exception {
        AgentValidateResult r = validate(ORPHAN_WRITE_NODE_DSL);

        assertFalse(r.isPassed(), r.toFeedback());
        assertEquals(1, r.getErrors().size(), r.toFeedback());
        String error = r.getErrors().get(0);
        assertTrue(error.contains("b"), "要点名是哪个节点：" + error);
        assertTrue(error.contains("永远不会执行"), "要说后果，不能说图论术语：" + error);
        assertFalse(error.contains("不连通"), "「图不连通」作者读完不知道该干什么：" + error);
        assertTrue(error.contains("\"to\":\"b\""), "要给出可原样粘贴的那条边：" + error);
        assertTrue(error.contains("删掉"), "另一条出路（这节点已废弃）也要给：" + error);
    }

    @Test
    void validate_allNodesReachable_passed() throws Exception {
        AgentValidateResult r = validate("""
                {"id":"wf",
                 "variables":[{"key":"input","type":"object"},{"key":"out","type":"object"}],
                 "nodes":[{"id":"a","type":"tool","config":{"toolId":"data_interface_list","outputVar":"out"}},
                          {"id":"b","type":"tool","config":{"toolId":"x","outputVar":"out"}}],
                 "edges":[{"from":"START","to":"a"},{"from":"a","to":"b"},{"from":"b","to":"END"}]}
                """);

        assertTrue(r.isPassed(), r.toFeedback());
    }

    /**
     * ⚠️ {@code iteration.body} 里的节点<b>写在顶层 nodes[]</b>，但编译器刻意把它从父图排除、
     * 并要求作者<b>不要</b>把它连进 edges[]。不排除就是把「按规范写对了」判成错。
     */
    @Test
    void validate_iterationBodyNode_notReportedUnreachable() throws Exception {
        AgentValidateResult r = validate("""
                {"id":"wf",
                 "variables":[{"key":"input","type":"object"},{"key":"items","type":"array"},
                              {"key":"out","type":"object"}],
                 "nodes":[{"id":"loop","type":"iteration",
                           "config":{"itemsVar":"items","itemVar":"item","body":["step"]}},
                          {"id":"step","type":"tool","config":{"toolId":"data_interface_list","outputVar":"out"}}],
                 "edges":[{"from":"START","to":"loop"},{"from":"loop","to":"END"}]}
                """);

        assertTrue(r.isPassed(), "循环体节点天然不在 reachableFrom(START) 里：" + r.toFeedback());
    }

    /** 同理，{@code parallel.branches} 里的节点也不进父图的 edges（连边由 {@code expandParallel} 生成） */
    @Test
    void validate_parallelBranchNodes_notReportedUnreachable() throws Exception {
        AgentValidateResult r = validate("""
                {"id":"wf",
                 "variables":[{"key":"input","type":"object"},{"key":"out","type":"object"}],
                 "nodes":[{"id":"par","type":"parallel",
                           "config":{"join":"all","maxParallelism":2,"branches":[["lft"],["rgt"]]}},
                          {"id":"lft","type":"tool","config":{"toolId":"data_interface_list","outputVar":"out"}},
                          {"id":"rgt","type":"tool","config":{"toolId":"x","outputVar":"out"}}],
                 "edges":[{"from":"START","to":"par"},{"from":"par","to":"END"}]}
                """);

        assertTrue(r.isPassed(), "并行分支节点同样天然缺席：" + r.toFeedback());
    }

    /**
     * 🔴 扁平的 {@code branches:["lft","rgt"]} 是<b>形状写错了</b>（规范是 {@code List<List<String>>}），
     * 编译器会以「每条 branch 必须是非空的节点 id 链」拒掉。V18 照样把这两个 id 排除 ——
     * 于是作者只收到那<b>一条</b>「形状不对」，而不是再叠上两条「节点不可达」。一个根因只说一遍。
     */
    @Test
    void validate_parallelBranchesFlatShape_onlyTheCompilerComplains() throws Exception {
        AgentValidateResult r = validate("""
                {"id":"wf",
                 "variables":[{"key":"input","type":"object"},{"key":"out","type":"object"}],
                 "nodes":[{"id":"par","type":"parallel",
                           "config":{"join":"all","maxParallelism":2,"branches":["lft","rgt"]}},
                          {"id":"lft","type":"tool","config":{"toolId":"data_interface_list","outputVar":"out"}},
                          {"id":"rgt","type":"tool","config":{"toolId":"x","outputVar":"out"}}],
                 "edges":[{"from":"START","to":"par"},{"from":"par","to":"END"}]}
                """);

        assertFalse(r.toFeedback().contains("走不到"), "形状错是编译器的权威，别叠上 N 条不可达：" + r.toFeedback());
    }

    /**
     * ⚠️ 没有一条边从 START 出发时整条跳过：那个根因 {@code validateEdges} 已经报过，
     * 而编译器在这种情况下另有兜底（直接接上第一个非循环体节点）。
     * 这里再刷一屏「所有节点都不可达」是同一个根因说 N 遍。
     */
    @Test
    void validate_noEdgeFromStart_unreachableRuleStaysSilent() throws Exception {
        AgentValidateResult r = validate("""
                {"id":"wf",
                 "variables":[{"key":"input","type":"object"},{"key":"out","type":"object"}],
                 "nodes":[{"id":"a","type":"tool","config":{"toolId":"data_interface_list","outputVar":"out"}},
                          {"id":"b","type":"tool","config":{"toolId":"x","outputVar":"out"}}],
                 "edges":[{"from":"a","to":"b"},{"from":"b","to":"END"}]}
                """);

        assertFalse(r.isPassed(), r.toFeedback());
        assertTrue(r.toFeedback().contains("没有任何一条边从 START 出发"), r.toFeedback());
        assertFalse(r.toFeedback().contains("走不到"), "一个根因只说一遍：" + r.toFeedback());
    }

    // -------------------- V19：写进 state 却没在 variables[] 里声明 --------------------

    /**
     * START → w（type/config 由调用方给）→ wait(duration) → END。
     * 🔴 那个 wait 是本节所有用例的<b>前提</b>：{@code OverAllState.input(checkpointState)} 的过滤
     * 只在 resume 时发生，没有挂起点就一次 resume 都没有，未声明的键一个也丢不了。
     *
     * <p>⚠️ 键名是 {@code duration}（<b>秒</b>），不是 {@code durationMs}：那是编译器
     * {@code resolveWaitDurationSeconds} 读的唯一键，写错了这个 wait 节点<b>压根编译不过</b>，
     * 于是「下游真有一个挂起点」这个前提就成了假的 —— 而本节只跑校验器、不跑编译器，
     * 那种错法在这里永远是绿的，只有真跑 /validate 才暴露（已实测踩过一次）。
     */
    private static String writeThenSuspendDsl(String nodeType, String nodeConfigJson) {
        return """
                {"id":"wf",
                 "variables":[{"key":"input","type":"object"}],
                 "nodes":[{"id":"w","type":"%s","config":{%s}},
                          {"id":"pause","type":"wait","config":{"waitType":"duration","duration":600}}],
                 "edges":[{"from":"START","to":"w"},{"from":"w","to":"pause"},{"from":"pause","to":"END"}]}
                """.formatted(nodeType, nodeConfigJson);
    }

    /** 同上，但把 wait 拿掉 —— 一条从头跑到尾不挂起的流程 */
    private static String writeWithoutSuspendDsl(String nodeType, String nodeConfigJson) {
        return """
                {"id":"wf",
                 "variables":[{"key":"input","type":"object"}],
                 "nodes":[{"id":"w","type":"%s","config":{%s}}],
                 "edges":[{"from":"START","to":"w"},{"from":"w","to":"END"}]}
                """.formatted(nodeType, nodeConfigJson);
    }

    private static final String TOOL_WITH_NAMED_OUTPUT = "\"toolId\":\"x\",\"outputVar\":\"undeclaredOut\"";

    /**
     * 🔴 这条替代了原来那条 {@code validate_undeclaredOutputVar_isWarningNotError}。
     * 旧文案的理由是「引擎对未声明键按 REPLACE 兜底、值不会丢」—— 那句<b>只在同一次运行内成立</b>，
     * 跨 resume 就是假的（{@code KeyStrategyFactoryGenerator} 的类头把这条写得很直白，
     * {@code WaitNodeRuntimeTest} 已实测）。而 S4a-h 正在给执行历史抽屉加那枚「恢复」按钮。
     *
     * <p>⚠️ 原先这里写的是「{@code KeyStrategyFactoryGenerator:25-30}」——
     * 一个<b>硬编码的行号区间</b>。C8 改了那个类头，区间当场就漂了，而
     * {@code CommentLineRefBanTest} 刻意不扫 src/test（它的域只有 src/main），所以<b>不会有测试变红</b>。
     * 改成引类名：漂不了，且点进去就能找到。
     */
    @Test
    void validate_namedOutputVarWithSuspendDownstream_rejected() throws Exception {
        AgentValidateResult r = validate(writeThenSuspendDsl("tool", TOOL_WITH_NAMED_OUTPUT));

        assertFalse(r.isPassed(), r.toFeedback());
        assertEquals(1, r.getErrors().size(), r.toFeedback());
        String error = r.getErrors().get(0);
        assertTrue(error.contains("undeclaredOut"), "要点名那个会丢的键：" + error);
        assertTrue(error.contains("outputVar"), "要说清是哪一格写的名字：" + error);
        assertTrue(error.contains("pause"), "要指名下游那个挂起点，作者才核得了这句话：" + error);
        assertTrue(error.contains("{\"key\":\"undeclaredOut\",\"type\":\"object\"}"),
                "要给出可原样粘贴的那条声明：" + error);
        assertTrue(error.contains("append"), "跨轮累积那档也要提一句：" + error);
    }

    /**
     * 🔴 前提①：没有挂起点就没有 resume，这个键<b>丢不了</b>。
     * 对它报 error 是把不存在的危害说成存在的 —— 而 error 阻断发布，代价是作者学会无视红字。
     */
    @Test
    void validate_namedOutputVarWithoutSuspend_silent() throws Exception {
        AgentValidateResult r = validate(writeWithoutSuspendDsl("tool", TOOL_WITH_NAMED_OUTPUT));

        assertTrue(r.isPassed(), "一条不挂起的流程丢不了任何键：" + r.toFeedback());
        assertTrue(r.getWarnings().isEmpty(), r.getWarnings().toString());
    }

    /**
     * 🔴 前提②：作者<b>没填</b>这一格时用的是契约缺省名（{@code tool_result}），那一档一律不报。
     * 要求作者为了拿到<b>缺省</b>行为先去 {@code variables[]} 里把 {@code tool_result} 抄一遍，
     * 是把平台的活儿推给每一个作者，且几乎每一条存量流程都会因此体检不过（Req6 向后兼容红线）。
     *
     * <p>✅ 根治点已落地（缺陷 C8）：{@code KeyStrategyFactoryGenerator#generate} 现在遍历
     * {@code NodeIoContract.defaultOutputNames()} 把<b>全部</b>缺省输出名无条件注册，
     * 不再只保那三个 HITL 键。于是本条的「不报」从<b>知情不报</b>（洞真实存在，只是不该由作者补）
     * 变成了<b>确实不会丢</b>。
     *
     * <p>⚠️ 这句话现在<b>依赖 C8 活着</b>：谁把生成器那一趟遍历删掉，本条<b>照样绿</b>
     * （它断的是「校验器不报」，而校验器本来就不报这一档）—— 洞会静默回来。
     * 守住它的是 {@code KeyStrategyFactoryGeneratorTest#generate_registersEveryContractDefaultName_evenWhenVariablesDoNotDeclareThem}。
     */
    @Test
    void validate_defaultOutputVar_silentEvenWithSuspendDownstream() throws Exception {
        AgentValidateResult r = validate(writeThenSuspendDsl("tool", "\"toolId\":\"x\""));

        assertTrue(r.isPassed(), "缺省名那一档不阻断：" + r.toFeedback());
        assertTrue(r.getWarnings().isEmpty(), "也不该留一条黄标教作者无视警告：" + r.getWarnings().toString());
    }

    @Test
    void validate_namedOutputVarAlreadyDeclared_passed() throws Exception {
        AgentValidateResult r = validate("""
                {"id":"wf",
                 "variables":[{"key":"input","type":"object"},{"key":"undeclaredOut","type":"object"}],
                 "nodes":[{"id":"w","type":"tool","config":{%s}},
                          {"id":"pause","type":"wait","config":{"waitType":"duration","duration":600}}],
                 "edges":[{"from":"START","to":"w"},{"from":"w","to":"pause"},{"from":"pause","to":"END"}]}
                """.formatted(TOOL_WITH_NAMED_OUTPUT));

        assertTrue(r.isPassed(), r.toFeedback());
    }

    /**
     * 🔴 {@code hitl.*} 的挂起<b>伤不到它自己的写入</b>：它是在自己那次 resume <b>之后</b>
     * 才把 {@code approveVar} 写进 state 的。故 {@code reachableFrom} 恒含起点这件事必须挖掉自己，
     * 否则每一个聊天确认卡都会收到一条假警报。
     *
     * <h3>🔴 本条原先断言的是 {@code r.isPassed()}，V23 落地后<b>必须</b>改，且改法只有一种</h3>
     * 那份 DSL（自定义 {@code approveVar} + 未声明 + 下游无挂起点）恰好<b>就是</b> V23 的靶心：
     * 出站方向确实伤不到自己（V19 沉默是对的），但<b>入站</b>方向另有一条死法 ——
     * 调用方按自定义键传 feedback 时那个值进不了 state（运行期三档实测见
     * {@code KeyStrategyResumeRuntimeTest} 第 ③ 组，探针原文归档 logs/56），
     * 「同意」被静默判成「拒绝」。所以 {@code isPassed()} 现在<b>理应</b>为假。
     *
     * <p>⚠️ <b>不能</b>靠「把 {@code myApproved} 补进 variables[]」来让本条重新变绿：那样 V19 的
     * <b>豁免</b>会先短路，压根走不到自排除那一步，本条就退化成「声明过的键不报」——
     * 而那是上面 {@code validate_namedOutputVarAlreadyDeclared_passed} 早就钉过的事。
     * <b>恒绿的负控比没有负控更坏</b>（{@code KeyStrategyResumeRuntimeTest} 的夹具自检同款理由）。
     *
     * <p>🔴 于是判据落在<b>文案</b>上，而它非空洞：挖掉 {@code suspendAfterWrite} 里那个
     * {@code !suspendId.equals(probe)}，本条 DSL 会被 V19 认领（V23 随即因「下游有挂起点」而闭嘴），
     * 报出来的是含「下游还有一个挂起点(gate)」的那句 —— 而 gate 就是它自己，那句话是<b>假的</b>，
     * 下面第二条断言当场变红。「只报一条」这条断言同时钉住 V19/V23 <b>互补不重叠</b>
     * （同一个键报两条，作者会以为是两件事）。
     */
    @Test
    void validate_hitlConfirmOwnSuspend_v19Silent_v23OwnsIt() throws Exception {
        AgentValidateResult r = validate("""
                {"id":"wf","trigger":{"type":"trigger.chat"},
                 "variables":[{"key":"input","type":"object"}],
                 "nodes":[{"id":"gate","type":"hitl.confirm","config":{"approveVar":"myApproved"}}],
                 "edges":[{"from":"START","to":"gate"},{"from":"gate","to":"END"}]}
                """);

        assertFalse(r.isPassed(), "入站方向真有危害，V23 该拦：" + r.toFeedback());
        assertEquals(1, r.getErrors().size(), "V19 与 V23 互补不重叠，同一个键只许报一条：" + r.toFeedback());
        String error = r.getErrors().get(0);
        assertFalse(error.contains("下游还有一个挂起点"),
                "V19 的自排除必须还在：节点自己不是自己的下游，那句话是假的。实际报的是：" + error);
        assertTrue(error.contains("这个节点自己就是挂起点"), "这一档归 V23：" + error);
    }

    /** 同上一个节点，但<b>下游还有一个</b> wait —— 那一次 resume 就会把 {@code myApproved} 丢掉 */
    @Test
    void validate_hitlConfirmApproveVarWithSecondSuspendDownstream_rejected() throws Exception {
        AgentValidateResult r = validate("""
                {"id":"wf","trigger":{"type":"trigger.chat"},
                 "variables":[{"key":"input","type":"object"}],
                 "nodes":[{"id":"gate","type":"hitl.confirm","config":{"approveVar":"myApproved"}},
                          {"id":"pause","type":"wait","config":{"waitType":"duration","duration":600}}],
                 "edges":[{"from":"START","to":"gate"},{"from":"gate","to":"pause"},{"from":"pause","to":"END"}]}
                """);

        assertFalse(r.isPassed(), r.toFeedback());
        assertEquals(1, r.getErrors().size(), "下游有挂起点 ⟹ 归 V19，V23 必须闭嘴：" + r.toFeedback());
        assertTrue(r.getErrors().get(0).contains("myApproved"), r.getErrors().get(0));
        assertTrue(r.getErrors().get(0).contains("下游还有一个挂起点"),
                "报这条的必须是 V19 而不是 V23（两条的文案不同，作者据此才知道该往哪看）："
                        + r.getErrors().get(0));
    }

    /**
     * 🔴 这些键之所以能活过 resume，是因为 {@code KeyStrategyFactoryGenerator#generate}
     * <b>无条件</b>给它们注册了 KeyStrategy。作者把缺省名<b>显式抄一遍</b>时也必须照样豁免 ——
     * 否则报出来的是一条改法为「把这格删掉」的假警报。
     *
     * <p>⚠️ 原先这里写的是「{@code KeyStrategyFactoryGenerator:46-52} 给<b>这三个</b>键注册」，
     * C8 之后两处都漂了：那个类现在遍历 {@code NodeIoContract.defaultOutputNames()}，
     * 豁免集也从 3 个扩到<b>全部</b>非空缺省输出名（7 个）。行号区间照旧不会有人发现 ——
     * {@code CommentLineRefBanTest} 刻意不扫 src/test。改成引方法名。
     */
    @Test
    void validate_explicitlyRestatedRegisteredKey_silent() throws Exception {
        stubDeployedBpmModel();
        AgentValidateResult r = validate("""
                {"id":"wf",
                 "variables":[{"key":"input","type":"object"}],
                 "nodes":[{"id":"gate","type":"hitl.bpm_approval",
                           "config":{%s,"approveVar":"bpm_approved"}},
                          {"id":"pause","type":"wait","config":{"waitType":"duration","duration":600}}],
                 "edges":[{"from":"START","to":"gate"},{"from":"gate","to":"pause"},{"from":"pause","to":"END"}]}
                """.formatted(BPM_GATE_CONFIG));

        assertTrue(r.isPassed(), "缺省名已无条件注册，声明与否都活得下来：" + r.toFeedback());
    }

    /**
     * 🔴 C8 在<b>校验器这一侧</b>的唯一可观测后果：非 hitl 的缺省名同样豁免。
     *
     * <p>上面那条用 {@code bpm_approved}，而它<b>C8 之前就已经豁免了</b>（那三个键一直是硬编码在册的），
     * 所以它证明不了推广真的发生 —— 把 {@code RESUME_REGISTERED_VARS} 改回三个键，它照样绿。
     * 这一条用 {@code tool_result}：<b>C8 之前必红</b>（V19 会报「写而未声明」），之后必绿。
     *
     * <p>⚠️ 这个场景不是编的：编排器/AI 生成 DSL 时把缺省名<b>显式写出来</b>是很常见的
     * （写出来比留空更「完整」，模型倾向这么做），而作者手抄示例时也一样。
     * C8 之前他们会收到一条 error，文案还教他们「把这格删掉」—— 删了行为不变，
     * 但那是靠运气（执行器兜的缺省名恰好就是他删掉的那个值），且白折腾一轮。
     */
    @Test
    void validate_explicitlyRestatedNonHitlDefaultName_silent() throws Exception {
        AgentValidateResult r = validate(writeThenSuspendDsl("tool",
                "\"toolId\":\"x\",\"outputVar\":\"%s\"".formatted(ToolNodeExecutor.DEFAULT_OUTPUT_VAR)));

        assertTrue(r.isPassed(),
                "把契约缺省名显式抄一遍不该被报：那个键 KeyStrategyFactoryGenerator 已无条件注册，"
                        + "resume 时 OverAllState.input() 滤不掉它 —— 报出来就是假警报，"
                        + "而它附带的改法（删掉这一格）还会让作者白改一轮：" + r.toFeedback());
        assertTrue(r.getWarnings().isEmpty(), r.getWarnings().toString());
    }

    /**
     * 🔴 #58 修完之后，{@code statusVar} 是<b>按配的名字</b>写回的
     * （{@code HitlBpmApprovalNodeExecutor#execute} 里 {@code delta.put(statusVar, …)} 那一处），
     * 所以它同样在 V19 的射程里。覆盖面从「只盯 outputVar」扩到「遍历契约的 outputs()」，
     * 于是将来新增一种节点类型时这一条自动跟着覆盖（D7）。
     *
     * <p>⚠️ 原先这里引的是 {@code HitlBpmApprovalNodeExecutor:100}。核对下来那个行号<b>此刻仍然是对的</b>
     * （C8 没动那个文件），改成引方法名不是因为已经漂了，而是因为<b>没有任何东西守着它不漂</b>：
     * {@code CommentLineRefBanTest} 的域只有 src/main，扫不到这里。
     */
    @Test
    void validate_bpmApprovalStatusVar_rejected() throws Exception {
        stubDeployedBpmModel();
        AgentValidateResult r = validate("""
                {"id":"wf",
                 "variables":[{"key":"input","type":"object"}],
                 "nodes":[{"id":"gate","type":"hitl.bpm_approval","config":{%s,"statusVar":"myStatus"}},
                          {"id":"pause","type":"wait","config":{"waitType":"duration","duration":600}}],
                 "edges":[{"from":"START","to":"gate"},{"from":"gate","to":"pause"},{"from":"pause","to":"END"}]}
                """.formatted(BPM_GATE_CONFIG));

        assertFalse(r.isPassed(), r.toFeedback());
        assertEquals(1, r.getErrors().size(), r.toFeedback());
        String error = r.getErrors().get(0);
        assertTrue(error.contains("myStatus"), error);
        assertTrue(error.contains("statusVar"), error);
    }

    /** {@code citationsVar} 是契约登记的<b>第二个</b>输出端口：旧实现只盯 outputVar，它压根不在射程里 */
    @Test
    void validate_knowledgeRetrievalCitationsVar_rejected() throws Exception {
        AgentValidateResult r = validate(writeThenSuspendDsl("knowledge_retrieval",
                "\"datasetIds\":[1],\"outputVar\":\"chunks\",\"citationsVar\":\"cites\""));

        assertFalse(r.isPassed(), r.toFeedback());
        assertEquals(2, r.getErrors().size(), "两个端口都没声明，就该报两条：" + r.toFeedback());
        assertTrue(r.toFeedback().contains("cites"), r.toFeedback());
        assertTrue(r.toFeedback().contains("chunks"), r.toFeedback());
    }

    /**
     * 🔴 子图节点要<b>顺着容器问</b>：{@code step} 不在 edges[] 里，{@code reachableFrom(step)} 恒等于它自己，
     * 直接问就会得到「下游没有挂起点」这个假答案 —— 而它写的键一直活到循环跑完，
     * 循环之后那个 wait 照样会在 resume 时把它丢掉。
     */
    @Test
    void validate_iterationBodyNodeJudgedThroughItsContainer() throws Exception {
        AgentValidateResult r = validate("""
                {"id":"wf",
                 "variables":[{"key":"input","type":"object"},{"key":"items","type":"array"}],
                 "nodes":[{"id":"loop","type":"iteration",
                           "config":{"itemsVar":"items","itemVar":"item","body":["step"]}},
                          {"id":"step","type":"tool","config":{"toolId":"data_interface_list","outputVar":"rowOut"}},
                          {"id":"pause","type":"wait","config":{"waitType":"duration","duration":600}}],
                 "edges":[{"from":"START","to":"loop"},{"from":"loop","to":"pause"},{"from":"pause","to":"END"}]}
                """);

        assertFalse(r.isPassed(), r.toFeedback());
        assertEquals(1, r.getErrors().size(), r.toFeedback());
        String error = r.getErrors().get(0);
        assertTrue(error.contains("rowOut"), error);
        assertTrue(error.contains("step"), "要指名是循环体里那个节点：" + error);
    }

    /** 未知 type 没有端口清单可查；那条根因 {@code validateNodes} 与深层干跑都已经报了 */
    @Test
    void validate_unknownNodeType_skippedByThisRule() throws Exception {
        AgentValidateResult r = validate(writeThenSuspendDsl("nope", "\"outputVar\":\"whatever\""));

        assertTrue(r.toFeedback().contains("不被支持"), r.toFeedback());
        assertFalse(r.toFeedback().contains("whatever"), "没有端口清单，别猜它写哪个键：" + r.toFeedback());
    }

    // -------------------- V19 × sub_workflow.outputMap（台账 E3） --------------------

    /**
     * START → sub（{@code sub_workflow}）→ wait(duration) → END；{@code varsJson} 是接在
     * {@code input} 那一条<b>后面</b>的额外声明（含前导逗号，可为空串）。
     *
     * <p>🔴 那个 wait 与 {@link #writeThenSuspendDsl} 里的是同一个前提：
     * {@code OverAllState.input(checkpointState)} 的过滤<b>只在 resume 时发生</b>，
     * 没有下游挂起点就一次 resume 都没有，未声明的键一个也丢不了（运行期 C 档实测，见
     * {@code SubWorkflowOutputMapResumeRuntimeTest}）。
     *
     * <p>⚠️ {@code childOut} 那一条声明是<b>历史遗留</b>，E3-2 之后已<b>不再是必需</b>，但刻意保留。
     * 旧实现里 {@code validatePlaceholders} 拿<b>父 def</b> 的名字表去判 {@code outputMap} 的<b>值</b>，
     * 而那个值里的占位符指的是<b>子流程</b>里的变量（契约把 outputMap 标成 {@code MODE_LITERAL}
     * 并明写「这里的 {{$.x}} 指的是子流程里的 x」）⟹ 本节每条用例都会多出一条与 V19 无关的
     * 「引用了 {{$.childOut}} 但解析不到」，声明它只是为了「一红不因两事」。
     * 那一档本轮已修（判据改成「父 ∪ 子」，见
     * {@link #validate_subWorkflowOutputMapValue_judgedAgainstChildDefNotParent}），且本节的默认桩
     * 给的是一份<b>空</b>子 DO（{@code dsl=null}）⟹ 走「取不到子 def 就整格不判」的降级路，
     * 两种口径下都不会再报。保留它的理由不是覆盖：本节要考的是 V19 的<b>键</b>，
     * 从夹具里拿掉一个无关声明只会增加「改夹具时顺手弄红别的用例」的机会。
     */
    private static String subWorkflowThenSuspendDsl(String varsJson, String subConfigJson) {
        return """
                {"id":"wf",
                 "variables":[{"key":"input","type":"object"},{"key":"childOut","type":"object"}%s],
                 "nodes":[{"id":"sub","type":"sub_workflow","config":{%s}},
                          {"id":"pause","type":"wait","config":{"waitType":"duration","duration":600}}],
                 "edges":[{"from":"START","to":"sub"},{"from":"sub","to":"pause"},{"from":"pause","to":"END"}]}
                """.formatted(varsJson, subConfigJson);
    }

    /** 同上，但把 wait 拿掉 —— 一条从头跑到尾不挂起的流程 */
    private static String subWorkflowWithoutSuspendDsl(String varsJson, String subConfigJson) {
        return """
                {"id":"wf",
                 "variables":[{"key":"input","type":"object"},{"key":"childOut","type":"object"}%s],
                 "nodes":[{"id":"sub","type":"sub_workflow","config":{%s}}],
                 "edges":[{"from":"START","to":"sub"},{"from":"sub","to":"END"}]}
                """.formatted(varsJson, subConfigJson);
    }

    private static final String SUB_WITH_OUTPUT_MAP =
            "\"workflowId\":\"child_wf\",\"outputMap\":{\"parentStruct\":\"{{$.childOut}}\"}";

    /**
     * 🔴 E3 的靶心：{@code outputMap} 的<b>键</b>是父流程侧的写入名，运行期由编译器合成的出参节点
     * 写进父流程共享 state，与 {@code outputVar} 同族；但它在契约里登记成<b>入</b>端口、
     * 而 {@code sub_workflow} 的 {@code outputs()} 是空的 ⟹ 遍历 {@code outputs()} 那个通用形状
     * 对它<b>结构性失明</b>，配了它 + 下游有挂起点 + 没声明的流程能一路发布成功。
     *
     * <p>危害已运行期实测（三档对照，探针原文归档 logs/16、可读版 logs/17，常驻强制点
     * {@code SubWorkflowOutputMapResumeRuntimeTest}）：未声明时下游读到 {@code null}，
     * 声明了就活。所以文案给的那条出路是<b>验证过的</b>，不是安慰。
     */
    @Test
    void validate_subWorkflowOutputMapKeyUndeclared_rejected() throws Exception {
        AgentValidateResult r = validate(subWorkflowThenSuspendDsl("", SUB_WITH_OUTPUT_MAP));

        assertFalse(r.isPassed(), "outputMap 的键同样会在 resume 那一刻被丢，V19 该拦：" + r.toFeedback());
        assertEquals(1, r.getErrors().size(), "只该报这一条（多出来的说明夹具踩了别的规则）：" + r.toFeedback());
        String error = r.getErrors().get(0);
        assertTrue(error.contains("parentStruct"), "要点名那个会丢的键：" + error);
        assertTrue(error.contains("outputMap"), "要说清是哪一格写的名字：" + error);
        assertTrue(error.contains("pause"), "要指名下游那个挂起点，作者才核得了这句话：" + error);
        assertTrue(error.contains("{\"key\":\"parentStruct\"}"), "要给出可原样粘贴的那条声明：" + error);
        assertFalse(error.contains("\"type\":\""),
                "outputMap 的值走 resolveMapValue，单整占位符保原始类型 ⟹ 类型只有作者知道，"
                        + "编一个填进文案就是让人照错方向改：" + error);
        assertTrue(error.contains("append"), "跨轮累积那档也要提一句（与 outputVar 那条文案同口径）：" + error);
    }

    /** 🔴 出路<b>实测有效</b>：与上一条只差 {@code variables[]} 里那一条声明（运行期 B 档） */
    @Test
    void validate_subWorkflowOutputMapKeyDeclared_passed() throws Exception {
        AgentValidateResult r = validate(subWorkflowThenSuspendDsl(
                ",{\"key\":\"parentStruct\",\"type\":\"object\"}", SUB_WITH_OUTPUT_MAP));

        assertTrue(r.isPassed(), r.toFeedback());
    }

    /**
     * 🔴 前提①：下游没有挂起点 ⟹ 一次 resume 都没有 ⟹ 这个键<b>丢不了</b>（运行期 C 档实测）。
     * 对它报 error 是把不存在的危害说成存在的，而 error 阻断发布，代价是作者学会无视红字。
     */
    @Test
    void validate_subWorkflowOutputMapKeyWithoutSuspendDownstream_silent() throws Exception {
        AgentValidateResult r = validate(subWorkflowWithoutSuspendDsl("", SUB_WITH_OUTPUT_MAP));

        assertTrue(r.isPassed(), "一条不挂起的流程丢不了任何键：" + r.toFeedback());
    }

    /**
     * 🔴 豁免集必须<b>同源</b>：作者把 outputMap 的键正好起成契约缺省输出名（这里用
     * {@code ToolNodeExecutor.DEFAULT_OUTPUT_VAR}）时，那个键已被
     * {@code KeyStrategyFactoryGenerator#generate} 无条件注册 ⟹ 滤不掉 ⟹ 报了就是假警报。
     *
     * <p>这个场景不是编的：outputMap 的键是作者随手起的父流程变量名，撞上缺省名毫不稀奇，
     * 而 AI 生成 DSL 时尤其爱用「看起来就是那个意思」的缺省名。
     */
    @Test
    void validate_subWorkflowOutputMapKeyRestatingContractDefaultName_silent() throws Exception {
        AgentValidateResult r = validate(subWorkflowThenSuspendDsl("",
                "\"workflowId\":\"child_wf\",\"outputMap\":{\"%s\":\"{{$.childOut}}\"}"
                        .formatted(ToolNodeExecutor.DEFAULT_OUTPUT_VAR)));

        assertTrue(r.isPassed(), "缺省名已无条件注册，声明与否都活得下来：" + r.toFeedback());
    }

    /**
     * 🔴 键名口径与运行期同源：{@code DslToStateGraphCompiler#validateMapping} 是
     * {@code String.valueOf(key).trim()} 之后才校格式、再 put 的，所以真被写进 state 的键<b>必然已 trim</b>。
     * 校验器若不 trim，报出来的是一个运行期压根不存在的键，且文案给的那条声明也补不对
     * （补 {@code " parentStruct "} 不会被 trim，注册不上；补 {@code parentStruct} 才对）。
     */
    @Test
    void validate_subWorkflowOutputMapKeyWithSurroundingSpaces_reportsTheTrimmedRuntimeName() throws Exception {
        AgentValidateResult r = validate(subWorkflowThenSuspendDsl("",
                "\"workflowId\":\"child_wf\",\"outputMap\":{\"  parentStruct  \":\"{{$.childOut}}\"}"));

        assertFalse(r.isPassed(), r.toFeedback());
        assertEquals(1, r.getErrors().size(), r.toFeedback());
        assertTrue(r.getErrors().get(0).contains("{\"key\":\"parentStruct\"}"),
                "要给出运行期真写的那个（已 trim 的）名字，作者才粘得对：" + r.getErrors().get(0));
    }

    /**
     * 多个未声明的键各报一条，且<b>按作者书写序</b>（{@code configMap} 直接返回 Jackson 的 Map，
     * 键序稳定）。理由与 {@code collectSuspendOrder} 那条一样：{@code HashSet} 的迭代序会让
     * 同一份 DSL 每次报出不同的顺序，作者复现不出来、测试也没法断言。
     */
    @Test
    void validate_subWorkflowOutputMapSeveralUndeclaredKeys_reportsEachInAuthorOrder() throws Exception {
        AgentValidateResult r = validate(subWorkflowThenSuspendDsl("",
                "\"workflowId\":\"child_wf\",\"outputMap\":{"
                        + "\"firstOut\":\"{{$.childOut}}\",\"secondOut\":\"{{$.childOut}}\"}"));

        assertFalse(r.isPassed(), r.toFeedback());
        assertEquals(2, r.getErrors().size(), "两个键都没声明，就该报两条：" + r.toFeedback());
        assertTrue(r.getErrors().get(0).contains("firstOut"), r.getErrors().toString());
        assertTrue(r.getErrors().get(1).contains("secondOut"), r.getErrors().toString());
    }

    /**
     * 🔴 射程边界（<b>刻意不报</b>，且这是一道<b>绊线</b>）：{@code inputMap} 的键运行期同样写进
     * 父流程共享 state、同样会被 resume 丢掉、下游同样读到 {@code null}
     * （实测档 D：{@code modelId}=5 进差集，恢复后 cap 读到 null）。
     *
     * <p>但本支<b>不报</b>它，理由不是漏了：那个名字是<b>子流程的入参词汇</b>，不是父流程的产出变量，
     * 要求父作者在父流程 {@code variables[]} 里声明子流程的入参名，会在「每个带 inputMap +
     * 下游挂起点的 sub_workflow」上<b>稳定</b>报错（Req6 向后兼容红线）；而按契约语义父流程下游
     * 节点<b>不该</b>读它 —— 能读到只是 SAA 原生子图共享 key 空间的副作用
     * （{@code DslToStateGraphCompiler#addSubWorkflowNode} 的 javadoc 明写「这是重命名/显式契约，
     * 不是硬命名空间隔离」）。真要靠它，正确的修法是让 {@code KeyStrategyFactoryGenerator}
     * 把子 def 的声明并进来，而不是让每个父作者手抄一遍子流程的入参名。
     *
     * <p>⚠️ 于是本条红了只有两种成因，都得人来判：① 有人把 inputMap 纳入了本支（那要先改上面那段
     * 取舍并把运行期档 D 的对照一起改）；② 有人把 outputMap 那一支写宽到「两个映射都遍历」。
     */
    @Test
    void validate_subWorkflowInputMapKey_notReportedByDesign() throws Exception {
        AgentValidateResult r = validate(subWorkflowThenSuspendDsl(
                ",{\"key\":\"parentStruct\",\"type\":\"object\"}",
                "\"workflowId\":\"child_wf\",\"inputMap\":{\"modelId\":\"{{$.input}}\"},"
                        + "\"outputMap\":{\"parentStruct\":\"{{$.childOut}}\"}"));

        assertTrue(r.isPassed(), "inputMap 的键刻意不在本支射程内（理由见方法注释）：" + r.toFeedback());
        assertFalse(r.toFeedback().contains("modelId"),
                "inputMap 的键一旦被报出来，就是有人把本支写宽了：" + r.toFeedback());
    }

    // -------------------- E3-2：sub_workflow.outputMap 的<b>值</b>按「父 ∪ 子」判 --------------------

    /**
     * 🔴 E3-2 的靶心：{@code sub_workflow.outputMap} 的<b>取值表达式</b>必须按<b>子 def</b> 的名字表判，
     * 不能按父 def 判。
     *
     * <p>旧口径下这一条是<b>假警报</b>，而且假在最糟的位置上：契约把 outputMap 的值标成
     * {@code MODE_LITERAL} 并明写「这里的 {{$.x}} 指的是<b>子流程</b>里的 x」，运行期
     * {@code DslToStateGraphCompiler#resolveMapValue} 求的正是子图写回后的<b>共享 state</b> ⟹
     * 「引用子流程自己的变量」是这一格<b>唯一正确</b>的写法，而旧实现偏偏只对它报 error，
     * 且文案给的出路（「改引用已声明的变量，或直接写字面值」）照做会把一个本来能跑的流程改坏。
     *
     * <p>⚠️ 历史取舍（上一轮写的「本轮不修」，本轮已按要求重估<b>并修掉</b>）：当时不修的理由是
     * 「豁免整格会让 {@code {{$.拼错的父变量}}} 这类真错误一起漏掉，按子 def 解析又要在校验器里
     * 把子流程的变量表取出来」。重估结论：<b>后者才是对的修法，而且成本比当时估的低</b> ——
     * V10 早就在查同一个 {@code getSubWorkflowDef}，返回的 DO 上就带着 {@code dsl}，
     * 解析一份名字表出来即可；而「豁免整格」那个备选方案本身是错的（它连父名字表也一起扔了）。
     * 于是本条不再钉假警报，改钉并集；下面几条分别钉住「并集的两半各自都不能少」与「取不到子 def 时降级」。
     *
     * <p>🔴 本条<b>单独绿不能证明</b>并集真的生效：子 def 解析失败走降级路（整格不判）时它同样绿。
     * 必须与 {@link #validate_subWorkflowOutputMapValue_typoInNeitherDef_stillRejected} 一起读 ——
     * 那条才是「子名字表真被加载了」的强制点。
     */
    @Test
    void validate_subWorkflowOutputMapValue_judgedAgainstChildDefNotParent() throws Exception {
        stubChildDef();

        AgentValidateResult r = validate(subWorkflowDsl(SUB_WITH_OUTPUT_MAP));

        assertTrue(r.isPassed(),
                "childOut 是子流程那个 capture 的 outputVar，运行期它就在子图写回后的共享 state 里："
                        + r.toFeedback());
        assertFalse(r.toFeedback().contains("运行期这一格会被替成"), r.toFeedback());
    }

    /**
     * 🔴 「子名字表真被加载」的强制点：{@code childOtu} 是 {@code childOut} 的手误，
     * 父子两份名字表里都没有它 ⟹ 必须照旧报 error。
     *
     * <p>这一条同时挡住两种写宽：① 把 outputMap 整格豁免（旧取舍里那个错方案）；
     * ② 子 def 根本没解析成功、悄悄走了降级路 —— 那种情况下这条 typo 报不出来，本条就红。
     */
    @Test
    void validate_subWorkflowOutputMapValue_typoInNeitherDef_stillRejected() throws Exception {
        stubChildDef();

        AgentValidateResult r = validate(subWorkflowDsl(
                "\"workflowId\":\"child_wf\",\"outputMap\":{\"parentStruct\":\"{{$.childOtu}}\"}"));

        assertFalse(r.isPassed(), "父子两份名字表里都没有 childOtu，这正是本规则该抓的那一类：" + r.toFeedback());
        assertEquals(1, r.getErrors().size(), r.toFeedback());
        String error = r.getErrors().get(0);
        assertTrue(error.contains("childOtu"), "要点名那个解析不到的根变量：" + error);
        // 🔴 标记串是「运行期这一格会被替成」而不是旧文案那句「运行期解析不到会把占位符原样当值传下去」：
        //    旧那句是<b>错的</b>（解析不到时落成的是空串，不是原样传下去 —— 见规则头 javadoc），
        //    2026-10-18 随 V35 一起改正。本条要钉的是「子名字表真被加载」，与那半句措辞无关，
        //    故只跟着换标记串，判据不变。
        assertTrue(error.contains("运行期这一格会被替成"), error);
    }

    /**
     * 并集的<b>父</b>那一半不能少：outputMap 的值引用父流程自己的变量同样合法。
     *
     * <p>运行期依据：SAA 原生子图是「整包喂入 + 共享 key 空间」，子图跑完后父的键<b>仍在</b>那份 state 里，
     * {@code _<sw>_out} 求值时读得到。所以判据必须是「父 ∪ 子」，
     * 而<b>不是</b>「把父表换成子表」—— 换表就会把这一档变成新的假警报。
     */
    @Test
    void validate_subWorkflowOutputMapValue_parentVarStillResolvable() throws Exception {
        stubChildDef();

        AgentValidateResult r = validate("""
                {"id":"wf",
                 "variables":[{"key":"input","type":"object"},{"key":"parentVar","type":"object"}],
                 "nodes":[{"id":"sub","type":"sub_workflow","config":{"workflowId":"child_wf",
                            "outputMap":{"parentStruct":"{{$.parentVar}}"}}}],
                 "edges":[{"from":"START","to":"sub"},{"from":"sub","to":"END"}]}
                """);

        assertTrue(r.isPassed(), "父的键在共享 state 里也在场，判据必须是并集而不是换成子表：" + r.toFeedback());
    }

    /** 并集里「子」那一半的两个来源之一：子 def 顶层 {@code variables[]} 声明的键（另一个来源是子节点的 outputVar） */
    @Test
    void validate_subWorkflowOutputMapValue_childDeclaredVarResolvable() throws Exception {
        stubChildDef("childDeclared");

        AgentValidateResult r = validate(subWorkflowDsl(
                "\"workflowId\":\"child_wf\",\"outputMap\":{\"parentStruct\":\"{{$.childDeclared}}\"}"));

        assertTrue(r.isPassed(), "子 def 声明过的变量同样在子图那份 state 里：" + r.toFeedback());
    }

    /**
     * 并集的第三项：本节点 {@code inputMap} 的<b>键</b>。
     *
     * <p>运行期依据：编译器把配了映射的 sw 改写成 {@code X→_<sw>_in→sw→_<sw>_out→Y}，
     * {@code _<sw>_in} 在子图<b>之前</b>就把 inputMap 的键写进了同一份共享 state，
     * 故 {@code _<sw>_out} 求值时它们在场。这些名字是父作者写在<b>同一个节点</b>里的，不查库就能拿到。
     */
    @Test
    void validate_subWorkflowOutputMapValue_inputMapKeyResolvable() throws Exception {
        stubChildDef();

        AgentValidateResult r = validate(subWorkflowDsl(
                "\"workflowId\":\"child_wf\",\"inputMap\":{\"modelId\":\"{{$.input}}\"},"
                        + "\"outputMap\":{\"parentStruct\":\"{{$.modelId}}\"}"));

        assertTrue(r.isPassed(), "inputMap 的键由 _<sw>_in 先写进共享 state，_out 求值时读得到：" + r.toFeedback());
    }

    /**
     * ⚠️ <b>绊线</b>：{@code inputMap} 的<b>值</b>仍按<b>父</b>名字表判，没跟着 outputMap 一起放宽。
     *
     * <p>这不是漏改，是运行期真的不一样：{@code _<sw>_in} 跑在子图<b>之前</b>，那一刻共享 state 里
     * 还没有子流程的任何产出 ⟹ 在 inputMap 的值里引用子变量，运行期必然取到 {@code null}
     * （{@code StatePathNavigator#navigate} 对取不到的路径返回 null，不报错）。
     *
     * <p>本条红了只有一种成因：有人把「换名字表」写宽到了两个映射上。
     */
    @Test
    void validate_subWorkflowInputMapValue_stillJudgedAgainstParent() throws Exception {
        stubChildDef();

        AgentValidateResult r = validate(subWorkflowDsl(
                "\"workflowId\":\"child_wf\",\"inputMap\":{\"modelId\":\"{{$.childOut}}\"}"));

        assertFalse(r.isPassed(), "_<sw>_in 跑在子图之前，那时 childOut 还不存在 ⟹ 这一格按父表判是对的："
                + r.toFeedback());
        assertEquals(1, r.getErrors().size(), r.toFeedback());
        assertTrue(r.getErrors().get(0).contains("childOut"), r.getErrors().get(0));
    }

    /**
     * 子 def 取不到（code 填错 / 未发布 / pin 到草稿 / 库连不上）⟹ outputMap 这一格<b>整格不判</b>，
     * 根因交给 V10 那句可操作的话。
     *
     * <p>🔴 降级方向是刻意的：此时<b>退回按父表判</b>等于把上面那条假警报原样留着、只换个触发条件；
     * 而子流程压根取不到时，outputMap 里引用什么本来就无从判起。
     * 也刻意<b>不</b>叠一条 warning —— V10 已经把真根因说了，多一句只会把它挤下去。
     */
    @Test
    void validate_subWorkflowOutputMapValue_childDefUnavailable_skipsThatCell() throws Exception {
        when(defService.getSubWorkflowDef(anyString(), any()))
                .thenThrow(new ServiceException(1_041_006_006, "子流程(nope_wf)不存在或不可执行"));

        AgentValidateResult r = validate(subWorkflowDsl(SUB_WITH_OUTPUT_MAP));

        assertFalse(r.isPassed(), "子流程用不了这件事本身必须报：" + r.toFeedback());
        assertTrue(r.toFeedback().contains("子流程用不了"), r.toFeedback());
        assertFalse(r.toFeedback().contains("childOut"),
                "子 def 取不到就不该拿父表去判它的 outputMap（那正是本轮修掉的假警报）：" + r.toFeedback());
    }

    /**
     * 一次 {@code validate} 内，同一份子流程被两个 sw 节点引用时<b>只解析一次</b>。
     *
     * <p>钉的是 {@code validatePlaceholders} javadoc 里那条 🔴 缓存声明：不钉住就会有人
     * 「顺手简化」掉那个 Map，于是每个 sw 节点各查一次库、各解析一次 DSL —— 而 validate 是
     * 自修正回路里<b>每轮都跑</b>的，那一轮可能有三五个 sw 节点。
     */
    @Test
    void validate_subWorkflowOutputMapValue_sameChildParsedOncePerValidate() throws Exception {
        stubChildDef();
        DslParser spyParser = spy(new DslParser());
        ReflectionTestUtils.setField(spyParser, "objectMapper", mapper);
        ReflectionTestUtils.setField(validator, "dslParser", spyParser);

        AgentValidateResult r = validate("""
                {"id":"wf",
                 "variables":[{"key":"input","type":"object"},
                              {"key":"a","type":"object"},{"key":"b","type":"object"}],
                 "nodes":[{"id":"s1","type":"sub_workflow","config":{"workflowId":"child_wf","outputMap":{"a":"{{$.childOut}}"}}},
                          {"id":"s2","type":"sub_workflow","config":{"workflowId":"child_wf","outputMap":{"b":"{{$.childOut}}"}}}],
                 "edges":[{"from":"START","to":"s1"},{"from":"s1","to":"s2"},{"from":"s2","to":"END"}]}
                """);

        assertTrue(r.isPassed(), r.toFeedback());
        verify(spyParser, times(1)).parse(anyString());
    }

    // -------------------- V23：挂起节点自己那一次 resume 要读的键也没声明 --------------------

    /**
     * {@code hitl.confirm} 配了自定义 {@code approveVar} 且未声明、<b>下游没有</b>挂起点。
     *
     * <p>🔴 这一档 V19 结构性地看不见（它挖掉了节点自己），而危害是真的、且已<b>运行期实测</b>：
     * 恢复时调用方按 {@code myApproved} 传进来的决定要先过 {@code OverAllState.input()}，
     * 没注册就地被滤掉 ⟹ 执行器读到 null ⟹ 回退默认键也没人写 ⟹ 「同意」判成「拒绝」，
     * 执行状态照样 SUCCEEDED。三档对照（未声明+自定义键 → rejected；未声明+默认键 → approved；
     * 已声明+自定义键 → approved）见归档 logs/56。
     *
     * <p>⚠️ 文案必须<b>两条出路都给</b>，且第二条要带上代价：删掉这一格改用缺省名虽然更省事
     * （缺省名已被无条件注册），但下游所有 {@code {{$.myApproved}}} 会一起失效 ——
     * 只说「删掉」就是让作者拿一个静默的空值换掉另一个静默的空值。
     */
    @Test
    void validate_v23_customApproveVarUndeclared_rejected() throws Exception {
        AgentValidateResult r = validate("""
                {"id":"wf","trigger":{"type":"trigger.chat"},
                 "variables":[{"key":"input","type":"object"}],
                 "nodes":[{"id":"gate","type":"hitl.confirm","config":{"approveVar":"myApproved"}}],
                 "edges":[{"from":"START","to":"gate"},{"from":"gate","to":"END"}]}
                """);

        assertFalse(r.isPassed(), r.toFeedback());
        assertEquals(1, r.getErrors().size(), r.toFeedback());
        String error = r.getErrors().get(0);
        assertTrue(error.contains("myApproved"), "要点名那个进不了 state 的键：" + error);
        assertTrue(error.contains("approveVar"), "要说清是哪一格填的名字：" + error);
        assertTrue(error.contains("这个节点自己就是挂起点"), "要说清危害来自它自己那次 resume，不是下游：" + error);
        assertTrue(error.contains("静默判成「拒绝」"), "要把后果说到「同意变拒绝」这一层，否则作者不知道轻重：" + error);
        assertTrue(error.contains("{\"key\":\"myApproved\",\"type\":\"boolean\"}"),
                "出路①：给出可原样粘贴的那条声明：" + error);
        assertTrue(error.contains("{{$.myApproved}}"),
                "出路②必须带上代价（下游引用要一并改），不能只说「删掉这一格」：" + error);
    }

    /**
     * 🔴 出路①<b>实测有效</b>：与上一条的 DSL 只差 {@code variables[]} 里那一条声明，结果从「拒绝」翻成「通过」。
     *
     * <p>少了这条，上面那段文案就只是断言而没有被验证过 —— 而「给了一条其实救不了的出路」
     * 比不给更坏（作者照做了、问题还在、于是不再信红字）。运行期那一半的实证在 logs/56 的 C 档。
     */
    @Test
    void validate_v23_customApproveVarDeclared_passed() throws Exception {
        AgentValidateResult r = validate("""
                {"id":"wf","trigger":{"type":"trigger.chat"},
                 "variables":[{"key":"input","type":"object"},
                              {"key":"myApproved","type":"boolean","merge":"replace"}],
                 "nodes":[{"id":"gate","type":"hitl.confirm","config":{"approveVar":"myApproved"}}],
                 "edges":[{"from":"START","to":"gate"},{"from":"gate","to":"END"}]}
                """);

        assertTrue(r.isPassed(), "声明了就真能活过 input() 那道过滤：" + r.toFeedback());
        assertTrue(r.getWarnings().isEmpty(), r.getWarnings().toString());
    }

    /**
     * 🔴 出路②<b>也</b>得是真出路：作者把契约缺省名<b>显式抄一遍</b>时必须豁免。
     *
     * <p>那一档走的是 logs/56 的 B 档（未声明 + 默认键 → approved）：缺省名被
     * {@code KeyStrategyFactoryGenerator#generate} 无条件注册，{@code input()} 滤不掉它，
     * 报 error 就是把不存在的危害说成存在的，而它给的改法（删掉这一格）行为压根不变 —— 白折腾一轮。
     * 与 V19 那条 {@code validate_explicitlyRestatedRegisteredKey_silent} 同一条理由、同一个豁免集。
     */
    @Test
    void validate_v23_explicitlyRestatedDefaultName_silent() throws Exception {
        AgentValidateResult r = validate("""
                {"id":"wf","trigger":{"type":"trigger.chat"},
                 "variables":[{"key":"input","type":"object"}],
                 "nodes":[{"id":"gate","type":"hitl.confirm",
                           "config":{"approveVar":"%s"}}],
                 "edges":[{"from":"START","to":"gate"},{"from":"gate","to":"END"}]}
                """.formatted(HitlConfirmNodeExecutor.DEFAULT_APPROVE_VAR));

        assertTrue(r.isPassed(), "缺省名已无条件注册，声明与否都活得下来：" + r.toFeedback());
        assertTrue(r.getWarnings().isEmpty(), r.getWarnings().toString());
    }

    /** 作者<b>没填</b>这一格 ⟹ 执行器落缺省名 ⟹ 同上一条，不报（V19 的前提②在这里同源适用） */
    @Test
    void validate_v23_slotLeftBlank_silent() throws Exception {
        AgentValidateResult r = validate("""
                {"id":"wf","trigger":{"type":"trigger.chat"},
                 "variables":[{"key":"input","type":"object"}],
                 "nodes":[{"id":"gate","type":"hitl.confirm","config":{}}],
                 "edges":[{"from":"START","to":"gate"},{"from":"gate","to":"END"}]}
                """);

        assertTrue(r.isPassed(), r.toFeedback());
        assertTrue(r.getWarnings().isEmpty(), r.getWarnings().toString());
    }

    /**
     * {@code hitl.bpm_approval} 的<b>两个</b>端口都在射程里，且各报一条。
     *
     * <p>{@code statusVar} 那一格尤其容易被漏：它丢了的后果不是「通过变驳回」，而是
     * {@code HitlBpmApprovalNodeExecutor#resolveResult} 少了状态码 4 那一档 ⟹ <b>「取消」被念成「驳回」</b>。
     * 同样是静默的，同样只在自定义名时露。
     */
    @Test
    void validate_v23_bpmApprovalBothPortsUndeclared_reportsTwo() throws Exception {
        stubDeployedBpmModel();
        AgentValidateResult r = validate("""
                {"id":"wf",
                 "variables":[{"key":"input","type":"object"}],
                 "nodes":[{"id":"gate","type":"hitl.bpm_approval",
                           "config":{%s,"approveVar":"myApproved","statusVar":"myStatus"}}],
                 "edges":[{"from":"START","to":"gate"},{"from":"gate","to":"END"}]}
                """.formatted(BPM_GATE_CONFIG));

        assertFalse(r.isPassed(), r.toFeedback());
        assertEquals(2, r.getErrors().size(), "两个端口都没声明就该报两条：" + r.toFeedback());
        assertTrue(r.toFeedback().contains("myApproved"), r.toFeedback());
        assertTrue(r.toFeedback().contains("myStatus"), r.toFeedback());
    }

    /**
     * 🔴 射程的<b>负控</b>：V23 只管挂起节点，普通节点写而未声明且下游无挂起点时<b>一个都不许报</b>。
     *
     * <p>这条钉的是 V19 的前提①与 V23 的入口条件<b>同一条</b>事实：{@code input()} 那层过滤只在
     * resume 时发生，一条从头跑到尾不挂起的流程丢不了任何键。谁把 V23 的
     * {@code SUSPEND_NODE_TYPES.contains(...)} 那道判断删掉（「顺手统一一下」），本条当场变红 ——
     * 而代价是每个不挂起的流程都会收到一条假 error、发布被拦，作者学会无视红字。
     */
    @Test
    void validate_v23_nonSuspendNodeWithoutAnySuspend_silent() throws Exception {
        AgentValidateResult r = validate("""
                {"id":"wf",
                 "variables":[{"key":"input","type":"object"}],
                 "nodes":[{"id":"w","type":"tool","config":{"toolId":"x","outputVar":"undeclaredOut"}}],
                 "edges":[{"from":"START","to":"w"},{"from":"w","to":"END"}]}
                """);

        assertTrue(r.isPassed(), "没有挂起点就没有 resume，这个键丢不了：" + r.toFeedback());
        assertTrue(r.getWarnings().isEmpty(), r.getWarnings().toString());
    }

    // -------------------- V16：外部能写的数据流进了写工具的入参 --------------------

    /** webhook 触发 + 一个写工具节点（id 固定为 {@code act}，与下面各条溯源结果的 sinkNodeId 对上） */
    private static String webhookWriteFlowDsl(String toolId) {
        return """
                {"id":"wf","trigger":{"type":"trigger.webhook","config":{%s}},
                 "variables":[{"key":"input","type":"object"},{"key":"out","type":"object"}],
                 "nodes":[{"id":"act","type":"tool","config":{"toolId":"%s","outputVar":"out",
                                                            "args":{"tableName":"{{$.input.name}}"}}}],
                 "edges":[{"from":"START","to":"act"},{"from":"act","to":"END"}]}
                """.formatted(AUTHED_WEBHOOK, toolId);
    }

    private static ChangeManifest.UntrustedInput untrustedFlow(String sinkKind, String sinkNodeId,
                                                               String sinkArg, String summary) {
        ChangeManifest.UntrustedInput flow = new ChangeManifest.UntrustedInput();
        flow.setSourceKind(UntrustedInputTracer.SourceKind.TRIGGER_WEBHOOK.name());
        flow.setSinkKind(sinkKind);
        flow.setSinkNodeId(sinkNodeId);
        flow.setSinkArg(sinkArg);
        flow.setSummary(summary);
        return flow;
    }

    private static ChangeManifest manifestOfUntrusted(ChangeManifest.UntrustedInput... flows) {
        ChangeManifest manifest = new ChangeManifest();
        // 🔴 不用 List.of：它不收 null，而「清单里有一项是 null」正是下面那条防御用例要喂进来的
        for (ChangeManifest.UntrustedInput flow : flows) {
            manifest.getUntrustedInputs().add(flow);
        }
        return manifest;
    }

    /**
     * 🔴 本组用例<b>桩的是「溯源结果」</b>，「溯源算得对不对」由 {@code UntrustedInputTracerTest} 负责 ——
     * 在校验器里另起一份溯源就是 {@code UntrustedInputTracer} 的第二个副本（D7）。
     *
     * <p>⚠️ 这句话原来还搭着半句已<b>作废</b>的话：「V16 吃的是一份调用方已经建好的清单，
     * 自己不发起任何溯源」。台账 C9 之后不成立了 —— 传 null 清单时折叠器<b>会</b>自己调
     * {@code UntrustedInputTracer.trace(def)}。但那半句<b>作废的方式</b>要读准：
     * 它没有变成「校验器里另起了一份溯源」（那仍然是 D7），而是「校验器多调了同一个实现的另一个入口」。
     * 于是本组用例传手写清单仍然成立（那是在桩溯源结果），而「null 清单」那一档从
     * 「静默跳过」翻成「自己追一遍」，两条都由下面 {@code fold_nullManifest_*} 那组钉住。
     */
    private AgentValidateResult foldUntrusted(String dsl, ChangeManifest manifest) throws Exception {
        WorkflowDefDTO def = mapper.readValue(dsl, WorkflowDefDTO.class);
        AgentValidateResult result = new AgentValidateResult();
        validator.foldUntrustedWrites(def, manifest, result);
        return result;
    }

    /**
     * 🔴 这条<b>永远是 warning、从不 error</b>：① 判据里的「谁生成的这份 DSL」是一个 DO 列，
     * {@code validate(WorkflowDefDTO)} 压根看不见，没法按方案那样分「AI 生成 = error / 人工 = warning」；
     * ② 方案那句「AI 生成的 DSL 走的是零校验路径」是假的（{@code WorkflowPlannerServiceImpl:117}
     * 每一轮都跑两层校验）；③ 报 error 会阻断最常见的那种合法 webhook 集成，
     * 而真正灾难性的那一格（无头 + 写工具 + 无闸口）V11 已经按 error 拦了。
     */
    @Test
    void fold_webhookBodyIntoWriteToolArg_warnsWithoutBlocking() throws Exception {
        AgentValidateResult r = foldUntrusted(webhookWriteFlowDsl("datamodel_create"),
                manifestOfUntrusted(untrustedFlow(UntrustedInputTracer.SinkKind.TOOL_ARG.name(),
                        "act", "tableName", "节点(act 建员工表) 的入参 tableName 来自 webhook 请求体")));

        assertTrue(r.isPassed(), "V16 不阻断：" + r.toFeedback());
        assertEquals(1, r.getWarnings().size(), r.toFeedback());
        String warning = r.getWarnings().get(0);
        assertTrue(warning.contains("节点(act 建员工表) 的入参 tableName 来自 webhook 请求体"),
                "必须原样透传溯源自己那句话，不许拿六个字段重拼：" + warning);
        assertTrue(warning.contains("没有人在场"), warning);
        assertTrue(warning.contains("发布者身份"), warning);
        assertTrue(warning.contains("hitl.bpm_approval"), "出路要给到能照做的粒度：" + warning);
    }

    /** 只读工具的入参被外部决定不叫「远程改数据」，V16 一声不吭（判据同 {@code SinkKind.TOOL_ARG} 的注释） */
    @Test
    void fold_readOnlyToolSink_silent() throws Exception {
        AgentValidateResult r = foldUntrusted(webhookWriteFlowDsl("data_interface_list"),
                manifestOfUntrusted(untrustedFlow(UntrustedInputTracer.SinkKind.TOOL_ARG.name(),
                        "act", "tableName", "入参来自 webhook 请求体")));

        assertTrue(r.isPassed(), r.toFeedback());
        assertTrue(r.getWarnings().isEmpty(), r.getWarnings().toString());
    }

    /**
     * 🔴 只认<b>声明</b>风险级：租户用一行 {@code agent_tool_override} 把一个写工具的<b>生效</b>风险
     * 降成 READ_ONLY，不该让 V16 看不见它（与 V3/V11 同一条纪律，见 {@code declaredRiskOf}）。
     */
    @Test
    void fold_usesDeclaredRiskNotTheTenantOverride() throws Exception {
        stubBuiltinTool("tool_create_record", RiskLevel.LOW, RiskLevel.READ_ONLY, true);
        AgentValidateResult r = foldUntrusted(webhookWriteFlowDsl("tool_create_record"),
                manifestOfUntrusted(untrustedFlow(UntrustedInputTracer.SinkKind.TOOL_ARG.name(),
                        "act", "row", "入参来自 webhook 请求体")));

        assertEquals(1, r.getWarnings().size(), "降级行不能把这条抹掉：" + r.toFeedback());
    }

    /** 聊天触发器下有人在场，那个组合正是 HITL 的正常工作方式，故整条跳过 */
    @Test
    void fold_chatTrigger_silent() throws Exception {
        AgentValidateResult r = foldUntrusted(toolFlowDsl(trigger("chat"), "datamodel_create"),
                manifestOfUntrusted(untrustedFlow(UntrustedInputTracer.SinkKind.TOOL_ARG.name(),
                        "act", "tableName", "入参来自聊天用户输入")));

        assertTrue(r.isPassed(), r.toFeedback());
        assertTrue(r.getWarnings().isEmpty(), r.getWarnings().toString());
    }

    /** 流进 prompt / 检索词 / 脚本沙箱都<b>不是</b>「远程改数据」：TOOL_ARG 之外一律不判 */
    @Test
    void fold_nonToolArgSink_silent() throws Exception {
        AgentValidateResult r = foldUntrusted(webhookWriteFlowDsl("datamodel_create"),
                manifestOfUntrusted(untrustedFlow(UntrustedInputTracer.SinkKind.LLM_PROMPT.name(),
                        "act", "prompt", "入参进了 prompt")));

        assertTrue(r.getWarnings().isEmpty(), r.getWarnings().toString());
    }

    /** 溯源指的那个节点根本不是 tool（比如是 llm）：读不到 {@code toolId}，也就无从判风险级 */
    @Test
    void fold_sinkNodeIsNotATool_silent() throws Exception {
        AgentValidateResult r = foldUntrusted("""
                {"id":"wf","trigger":{"type":"trigger.webhook","config":{%s}},
                 "variables":[{"key":"input","type":"object"},{"key":"txt","type":"string"}],
                 "nodes":[{"id":"act","type":"llm","config":{"outputVar":"txt"}}],
                 "edges":[{"from":"START","to":"act"},{"from":"act","to":"END"}]}
                """.formatted(AUTHED_WEBHOOK),
                manifestOfUntrusted(untrustedFlow(UntrustedInputTracer.SinkKind.TOOL_ARG.name(),
                        "act", "prompt", "入参来自 webhook 请求体")));

        assertTrue(r.getWarnings().isEmpty(), r.getWarnings().toString());
    }

    /** 多处流向合成<b>一条</b> warning、逐条列出：刷 N 条黄标的结果是没人读 */
    @Test
    void fold_severalFlows_oneWarningListingEachSummary() throws Exception {
        AgentValidateResult r = foldUntrusted(webhookWriteFlowDsl("datamodel_create"),
                manifestOfUntrusted(
                        untrustedFlow(UntrustedInputTracer.SinkKind.TOOL_ARG.name(),
                                "act", "tableName", "第一处：表名来自 webhook 请求体"),
                        untrustedFlow(UntrustedInputTracer.SinkKind.TOOL_ARG.name(),
                                "act", "columns", "第二处：列映射来自 webhook 请求体")));

        assertEquals(1, r.getWarnings().size(), r.toFeedback());
        String warning = r.getWarnings().get(0);
        assertTrue(warning.contains("第一处"), warning);
        assertTrue(warning.contains("第二处"), warning);
        assertTrue(warning.contains("2 处"), "要说清一共几处：" + warning);
    }

    /** 溯源没给 summary 时退化成「节点(id) 的入参 x」，绝不拼出一句半截话 */
    @Test
    void fold_blankSummary_fallsBackToNodeAndArg() throws Exception {
        AgentValidateResult r = foldUntrusted(webhookWriteFlowDsl("datamodel_create"),
                manifestOfUntrusted(untrustedFlow(UntrustedInputTracer.SinkKind.TOOL_ARG.name(),
                        "act", "tableName", null)));

        assertEquals(1, r.getWarnings().size(), r.toFeedback());
        assertTrue(r.getWarnings().get(0).contains("节点(act) 的入参 tableName"), r.getWarnings().get(0));
    }

    /**
     * 🔴 C9：没清单<b>不再</b>等于「V16 一句也不说」—— 折叠器自己追一遍
     *
     * <p>本条原先叫 {@code fold_nullOrEmptyManifest_silent}，javadoc 写的是「防御：调用方没建清单
     * （{@code WorkflowPlannerServiceImpl} 那条路）时静默跳过，不 NPE」。那句「防御」把<b>缺陷当成了期望行为</b>：
     * AI 自修正回路刻意不建清单（干跑最多 15 秒 × 最多 6 轮），于是模型在 6 轮里从没听说过
     * 「你这份草稿是远程改数据的形状」，用户要等到发布闸门才第一次看见这条警告。
     * 而溯源本身零查询、零干跑 —— 贵的是干跑，不是溯源，把两者绑在一起才是病。
     *
     * <p>⚠️ 改后它<b>实测变红</b>（归档 logs/27：{@code fold_nullOrEmptyManifest_silent:3400 null 清单
     * ==> expected: <true> but was: <false>}），红的位置正是那半句「静默跳过」——
     * 这就是「C9 真的改了行为」的凭据，不是「加了几条新测试所以看起来改过了」。
     */
    @Test
    void fold_nullManifest_stillSpeaks() throws Exception {
        AgentValidateResult r = foldUntrusted(webhookWriteFlowDsl("datamodel_create"), null);

        assertTrue(r.isPassed(), "V16 永远是 warning、从不阻断：" + r.toFeedback());
        assertEquals(1, r.getWarnings().size(), r.toFeedback());
        String warning = r.getWarnings().get(0);
        assertTrue(warning.contains("webhook 请求体"), "要点名不可信源：" + warning);
        assertTrue(warning.contains("args.tableName"), "要点名那个入参：" + warning);
        assertTrue(warning.contains("没有人在场"), warning);
        assertTrue(warning.contains("hitl.bpm_approval"), "出路要给到能照做的粒度：" + warning);
    }

    /**
     * 🔴 C9 的真判据：<b>有没有清单，V16 说的话逐字相同</b>
     *
     * <p>为什么这一条比上一条要紧：上一条只证明「null 清单时它会说话」，而它完全可能说的是<b>另一套话</b> ——
     * 那样 AI 回路上模型看到的警告与发布闸门上审批人看到的清单就不是同一份结论，
     * 而两边都印着「webhook 请求体」，没人会怀疑它们算的不是一回事（D7 换个位置复发）。
     *
     * <p>两次折叠刻意<b>各自重新 parse 一遍 DSL</b>（{@link #foldUntrusted} 内部就是 parse），
     * 于是比的不是同一个对象实例，而是「同一份 DSL 文本 → 同一句话」。
     */
    @Test
    void fold_nullManifestAndRealManifestSayTheSameThingVerbatim() throws Exception {
        String dsl = webhookWriteFlowDsl("datamodel_create");
        // 用真溯源建一份清单，模拟 builder 交出来的那份（builder 也是调同一个 trace）
        ChangeManifest manifest = new ChangeManifest();
        manifest.getUntrustedInputs().addAll(
                UntrustedInputTracer.trace(mapper.readValue(dsl, WorkflowDefDTO.class)));
        assertFalse(manifest.getUntrustedInputs().isEmpty(),
                "夹具自检：这份 DSL 本该追出流向，否则下面比的是两句空话");

        List<String> withManifest = foldUntrusted(dsl, manifest).getWarnings();
        List<String> withoutManifest = foldUntrusted(dsl, null).getWarnings();

        assertEquals(1, withManifest.size(), withManifest.toString());
        assertEquals(withManifest, withoutManifest,
                "两个入口必须说出逐字相同的那句话；不同 = 某一侧的图或播种算歪了");
    }

    /**
     * 负控：null 清单 + <b>只读</b>工具 ⟹ 仍然静默。
     *
     * <p>🔴 没有这一条，上面那条绿着也可能是因为「null 清单时压根没跑溯源」以外的另一个原因 ——
     * 比如整条规则被短路了。本条与 {@code fold_nullManifest_stillSpeaks} 只差工具的声明风险级
     * （{@code data_interface_list} = READ_ONLY vs {@code datamodel_create} = IRREVERSIBLE，
     * 两个桩都在 {@code @BeforeEach} 里），一红一绿 ⟹ 静默来自<b>风险级过滤</b>，不是来自 null。
     */
    @Test
    void fold_nullManifestWithReadOnlyTool_silent() throws Exception {
        AgentValidateResult r = foldUntrusted(webhookWriteFlowDsl("data_interface_list"), null);

        assertTrue(r.isPassed(), r.toFeedback());
        assertTrue(r.getWarnings().isEmpty(), r.getWarnings().toString());
    }

    /**
     * 清单<b>非 null 但为空</b>仍然静默 —— 与「null 清单」是两种不同的语义，别混成一档
     *
     * <p>null = 调用方<b>没建</b>清单（AI 回路、两参重载、或 builder fail-soft 失败）⟹ 折叠器自己追；
     * 空清单 = 调用方<b>建了</b>、结论就是「没有流向」⟹ 那是权威结论，再追一遍只是白跑一趟。
     * 「清单里有一项是 null」那半是原来的防御用例，照旧留着（防 NPE）。
     */
    @Test
    void fold_emptyManifestOrNullItem_silent() throws Exception {
        String dsl = webhookWriteFlowDsl("datamodel_create");

        assertTrue(foldUntrusted(dsl, new ChangeManifest()).getWarnings().isEmpty(), "空清单");
        assertTrue(foldUntrusted(dsl, manifestOfUntrusted((ChangeManifest.UntrustedInput) null))
                .getWarnings().isEmpty(), "清单里有一项是 null");
    }

    // ==================== ⑪ 探测本身故障时的作用域与文案（D-new8 / S4a-g） ====================

    /**
     * 🔴 判据是「<b>探测的作用域跟着它的入参走</b>」，不是「一律 continue」也不是「一律 return」。
     *
     * <p>本节三处（控件契约 / BPM 流程模型 / 可用模型）的探测都是<b>一次全局批量读、不接受逐项参数</b>，
     * 故障必然持续 ⟹ 整趟停手 + 一条认领<b>真实范围</b>的 warning。
     * 而 {@code mainTableExists} / 列结构的探测入参<b>就是那张表</b> ⟹ 只跳过本节点，
     * 那一半由 {@code validate_mainTableQueryFailure_skipsOnlyThatNode}（S4a-d）钉住，本节不重复。
     *
     * <p>D-new8 最初记成「两处 catch 的 return → continue」，那是<b>错的</b>：照它改，
     * 一个 20 字段的表单会把同一个故障 API 打 20 遍、再刷 20 条一模一样的黄标 ——
     * 而「20 条相同的黄标」不是提醒，是在教读者「这个黄标不用看」。
     */
    private static final String TWO_SAVE_DRAFT_NODES = """
            {"id":"wf",
             "variables":[{"key":"input","type":"object"},
                          {"key":"d1","type":"object"},{"key":"d2","type":"object"}],
             "nodes":[{"id":"first","type":"tool","config":{
                         "toolId":"form_save_draft","outputVar":"d1",
                         "args":{"name":"设备管理","code":"device_management","mainTable":"biz_device",
                                 "fields":[{"field":"设备名称","column":"device_name","widget":"input"},
                                           {"field":"设备编号","column":"device_code","widget":"input"}]}}},
                      {"id":"second","type":"tool","config":{
                         "toolId":"form_save_draft","outputVar":"d2",
                         "args":{"name":"设备台账","code":"device_ledger","mainTable":"biz_device",
                                 "fields":[{"field":"设备名称","column":"device_name","widget":"input"},
                                           {"field":"设备编号","column":"device_code","widget":"input"}]}}}],
             "edges":[{"from":"START","to":"first"},{"from":"first","to":"second"},
                      {"from":"second","to":"END"}]}
            """;

    /**
     * 🔴 核心那条：4 个字段（2 节点 × 2 字段）只打<b>一次</b>接口、只出<b>一条</b> warning。
     *
     * <p>把 {@code return} 改成 {@code continue} 的话，这条会同时以两种方式变红：
     * {@code getWidgetContracts()} 被打 4 遍、warning 变成 4 条。这正是它存在的理由 ——
     * 「不重试」这个决定必须被钉住，否则下一个人会把它当成 bug「修」掉。
     *
     * <p>🔴 {@code times(1)} 现在还钉住第二件事：V29（选项来源）读的是<b>同一份</b>契约，
     * 白名单这条中止后它必须整趟跳过、一次都不许再探（靠 {@code validateSaveDraftWidgets} 的返回值传话）。
     * 少了那一串，这里会变成 2 次调用 + 2 条同因黄标。
     */
    @Test
    void validate_widgetContractProbeFailure_probesOnceAndWarnsOnce() throws Exception {
        stubRegisteredTable("biz_device", "id", "device_name", "device_code", "tenant_id");
        when(formDesignApi.getWidgetContracts()).thenThrow(new RuntimeException("infra 无响应"));

        AgentValidateResult r = validate(TWO_SAVE_DRAFT_NODES);

        assertTrue(r.isPassed(), "探测失败不是不合法（方案 §6.1）：" + r.toFeedback());
        verify(formDesignApi, times(1)).getWidgetContracts();
        assertEquals(1, r.getWarnings().size(), "持续性故障刷 N 条一样的黄标 = 教读者别看了：" + r.toFeedback());
    }

    /**
     * 🔴 文案必须认领<b>真实</b>跳过范围。
     *
     * <p>改之前那句是「字段 设备名称 的控件类型 input 未校验」—— 只点了当前这一个字段，
     * 而 {@code return} 掉的是<b>整趟</b>：本节点剩下的字段、加上后面所有 form_save_draft 节点。
     * 读者按文案理解范围，就会以为其余 3 处都校验过了。「静默少校验一块」比报错坏。
     */
    @Test
    void validate_widgetContractProbeFailure_warningClaimsWholeScope() throws Exception {
        stubRegisteredTable("biz_device", "id", "device_name", "device_code", "tenant_id");
        when(formDesignApi.getWidgetContracts()).thenThrow(new RuntimeException("infra 无响应"));

        AgentValidateResult r = validate(TWO_SAVE_DRAFT_NODES);

        assertEquals(1, r.getWarnings().size(), r.toFeedback());
        String warn = r.getWarnings().get(0);
        assertTrue(warn.contains("infra 无响应"), "要带上故障原因，否则没法判断是不是自己的问题：" + warn);
        assertTrue(warn.contains("已校验 0 处"), "要说清停在哪一步之前：" + warn);
        assertTrue(warn.contains("全部未校验"), "要明说这是整趟、不是某一个字段：" + warn);
        assertTrue(warn.contains("first") && warn.contains("设备名称"), "要指出停在哪个节点哪个字段：" + warn);
        assertTrue(warn.contains("选项来源"),
                "同一次故障也让 V29 整趟停手（读的是同一份契约），跳过范围必须一起认领，"
                        + "否则黄标只说了一半、另一半成了静默少校验：" + warn);
        assertFalse(warn.contains("字段 设备名称 的控件类型 input 未校验"),
                "旧的单字段口径不许再出现，它谎报了范围：" + warn);
    }

    /** 反向对照：探测正常时一条 warning 都不该有（否则上面两条就成了永真断言） */
    @Test
    void validate_widgetContractHealthy_noProbeWarning() throws Exception {
        stubRegisteredTable("biz_device", "id", "device_name", "device_code", "tenant_id");

        AgentValidateResult r = validate(TWO_SAVE_DRAFT_NODES);

        assertTrue(r.isPassed(), r.toFeedback());
        assertTrue(r.getWarnings().isEmpty(), r.getWarnings().toString());
        // 4 个字段各探一次：契约读得好好的，就没有「不重试」这回事。
        // 🔴 是 8 不是 4：每个字段过<b>两条</b>读契约的判据 ——「控件白名单」与「选项来源（V29）」，
        //    各读一次。这个倍数同时钉住了 V29 的成本纪律：它把「是不是选项类控件」与「规范名」
        //    合成 optionWidgetCanonical 一次拿到，若拆成两次调用，这里就会变成 12。
        //    getWidgetContracts() 在 infra 那侧每次调用都重建整份契约 DTO，多读不是免费的。
        verify(formDesignApi, times(8)).getWidgetContracts();
    }

    @Test
    void validate_bpmProbeFailure_warningCountsEverySkippedNode() throws Exception {
        when(bpmModelApi.getModelList(isNull())).thenThrow(new RuntimeException("bpm 无响应"));

        AgentValidateResult r = validate("""
                {"id":"wf",
                 "variables":[{"key":"input","type":"object"}],
                 "nodes":[{"id":"a1","type":"hitl.bpm_approval","config":{"bpmProcessDefKey":"oa_leave"}},
                          {"id":"a2","type":"hitl.bpm_approval","config":{"bpmProcessDefKey":"oa_leave"}}],
                 "edges":[{"from":"START","to":"a1"},{"from":"a1","to":"a2"},{"from":"a2","to":"END"}]}
                """);

        assertTrue(r.isPassed(), r.toFeedback());
        assertEquals(1, r.getWarnings().size(), r.toFeedback());
        assertTrue(r.getWarnings().get(0).contains("2 个 hitl.bpm_approval"),
                "旧文案「bpmProcessDefKey 未校验」是单数说法，读的人会以为只漏了一处：" + r.getWarnings().get(0));
        verify(bpmModelApi, times(1)).getModelList(isNull());
    }

    @Test
    void validate_llmModelProbeFailure_warningCountsEverySkippedNode() throws Exception {
        when(aiModelApi.getEnabledModelList(1)).thenThrow(new RuntimeException("ai 无响应"));

        AgentValidateResult r = validate("""
                {"id":"wf",
                 "variables":[{"key":"input","type":"object"},
                              {"key":"t1","type":"string"},{"key":"t2","type":"string"}],
                 "nodes":[{"id":"g1","type":"llm","config":{"prompt":"hi","outputVar":"t1","modelId":7}},
                          {"id":"g2","type":"llm","config":{"prompt":"hi","outputVar":"t2","modelId":7}}],
                 "edges":[{"from":"START","to":"g1"},{"from":"g1","to":"g2"},{"from":"g2","to":"END"}]}
                """);

        assertTrue(r.isPassed(), r.toFeedback());
        assertEquals(1, r.getWarnings().size(), r.toFeedback());
        assertTrue(r.getWarnings().get(0).contains("2 个钉了 modelId"),
                "同上：单数说法谎报了范围：" + r.getWarnings().get(0));
        verify(aiModelApi, times(1)).getEnabledModelList(1);
    }

    /**
     * 只算<b>真会被探测</b>的那些：留空 modelId 是合法且更健壮的写法，不该被算进「未校验」的数目里
     * —— 数目虚高同样是谎报范围，只是方向相反。
     */
    @Test
    void validate_llmModelProbeFailure_countIgnoresUnpinnedNodes() throws Exception {
        when(aiModelApi.getEnabledModelList(1)).thenThrow(new RuntimeException("ai 无响应"));

        AgentValidateResult r = validate("""
                {"id":"wf",
                 "variables":[{"key":"input","type":"object"},
                              {"key":"t1","type":"string"},{"key":"t2","type":"string"}],
                 "nodes":[{"id":"g1","type":"llm","config":{"prompt":"hi","outputVar":"t1","modelId":7}},
                          {"id":"g2","type":"llm","config":{"prompt":"hi","outputVar":"t2"}}],
                 "edges":[{"from":"START","to":"g1"},{"from":"g1","to":"g2"},{"from":"g2","to":"END"}]}
                """);

        assertTrue(r.isPassed(), r.toFeedback());
        assertTrue(r.getWarnings().get(0).contains("1 个钉了 modelId"), r.getWarnings().get(0));
    }

    // ==================== ⑫ 会话腿没人写回复键（V20） ====================

    /**
     * V20 文案的标记串：它引用的是聊天侧那句 fail-soft 兜底<b>原文</b>
     * （{@code AgentAssistantServiceImpl.WORKFLOW_NO_OUTPUT_HINT}），别的规则不会用到它。
     * 用标记过滤而不是断言 {@code getWarnings().size()}，是为了不被同一次校验里别的警告干扰
     * （与 ⑤ 那节的 {@code REACH_HINT} 同一个做法）。
     */
    private static final String MUTE_LEG_HINT = "未产出文本回复";

    /**
     * 🔴 def 416 的真实形状（简化）：同意腿有 llm 写 output，驳回腿 {@code default -> END} 一路哑到底。
     * 这一份是下面好几条用例的基准，改它等于同时改几条用例的前提。
     */
    private static final String MUTE_REJECT_LEG_DSL = """
            {"id":"wf",
             "variables":[{"key":"input","type":"object"},{"key":"approved","type":"boolean"},
                          {"key":"output","type":"string"}],
             "nodes":[
               {"id":"ask","type":"hitl.confirm","config":{"approveVar":"approved"}},
               {"id":"gate","type":"condition.rule",
                "config":{"branches":[{"target":"approved","conditions":[]}],"default":"rejected"}},
               {"id":"summarize","type":"llm","config":{"prompt":"说清结果","outputVar":"output"}}
             ],
             "edges":[
               {"from":"START","to":"ask"},{"from":"ask","to":"gate"},
               {"from":"gate","type":"conditional","cases":{"approved":"summarize"},"default":"END"},
               {"from":"summarize","to":"END"}
             ]}
            """;

    /**
     * 实测疤：库里 def 416「设备管理表单创建与体检」（PUBLISHED）就是这个形状。
     * 用户点「驳回」之后只收到那句兜底 —— 他不知道自己驳回成功了没有，
     * 而执行记录是 SUCCEEDED、体检全绿、发布也过，没有任何一处说这条腿是哑的。
     */
    @Test
    void validate_muteRejectLeg_warnsNamingThatLeg() throws Exception {
        AgentValidateResult r = validate(MUTE_REJECT_LEG_DSL);

        assertTrue(r.isPassed(), "V20 是<b>警告</b>，不该阻断发布（理由见方法注释那一档）：" + r.toFeedback());
        assertTrue(r.getWarnings().stream().anyMatch(w -> w.contains(MUTE_LEG_HINT)),
                "驳回腿没人写 output，必须点出来：" + r.getWarnings());

        String w = r.getWarnings().stream().filter(x -> x.contains(MUTE_LEG_HINT)).findFirst().orElseThrow();
        assertTrue(w.contains("id=gate"), "要点名是哪条腿（收在 END 上的那个节点）：" + w);
        assertTrue(w.contains("output"), "要说清缺的是哪个键 —— 它可以被 trigger 覆盖，不是恒为 output：" + w);
        assertTrue(w.contains("\"outputVar\":\"output\""), "要给出可原样粘贴的那一格：" + w);
        assertTrue(w.contains("漏了这一条"),
                "要说清这是「别的腿写了、就这条没写」，否则作者会以为整张图都要改：" + w);
    }

    @Test
    void validate_bothLegsWriteReply_silent() throws Exception {
        AgentValidateResult r = validate("""
                {"id":"wf",
                 "variables":[{"key":"input","type":"object"},{"key":"approved","type":"boolean"},
                              {"key":"output","type":"string"}],
                 "nodes":[
                   {"id":"gate","type":"condition.rule",
                    "config":{"branches":[{"target":"approved","conditions":[]}],"default":"rejected"}},
                   {"id":"say_yes","type":"llm","config":{"prompt":"做完了","outputVar":"output"}},
                   {"id":"say_no","type":"llm","config":{"prompt":"没做，原因是","outputVar":"output"}}
                 ],
                 "edges":[
                   {"from":"START","to":"gate"},
                   {"from":"gate","type":"conditional","cases":{"approved":"say_yes","rejected":"say_no"},
                    "default":"say_no"},
                   {"from":"say_yes","to":"END"},{"from":"say_no","to":"END"}
                 ]}
                """);

        assertTrue(r.isPassed(), r.toFeedback());
        assertTrue(r.getWarnings().stream().noneMatch(x -> x.contains(MUTE_LEG_HINT)),
                "每条腿都写了回复键，不该报：" + r.getWarnings());
    }

    /**
     * 🔴 沉默条件里最要紧的一格：**一条会说话的腿都没有**时整条规则闭嘴。
     *
     * <p>这不是漏判，是 Req6 向后兼容红线：那种流程<b>每次</b>跑都只回兜底句，症状明显得多，
     * 而报它等于让<b>存量流程集体亮黄</b>（本文件 ① 那节几乎每条 fixture 都是这个形状）——
     * 体检面板上一片黄等于没有黄。与 V19 对「缺省名」那一档不报是同一条理由。
     */
    @Test
    void validate_noLegWritesReply_silent() throws Exception {
        AgentValidateResult r = validate("""
                {"id":"wf",
                 "variables":[{"key":"input","type":"object"},{"key":"summary","type":"string"}],
                 "nodes":[{"id":"a","type":"llm","config":{"prompt":"p","outputVar":"summary"}}],
                 "edges":[{"from":"START","to":"a"},{"from":"a","to":"END"}]}
                """);

        assertTrue(r.isPassed(), r.toFeedback());
        assertTrue(r.getWarnings().stream().noneMatch(x -> x.contains(MUTE_LEG_HINT)),
                "整张图一个回复键都没写属于另一档，本条刻意不报：" + r.getWarnings());
    }

    /** 回复键是<b>会话</b>约定；无头执行没有聊天窗口，没人会读它 ⟹ 整条跳过 */
    @Test
    void validate_headlessTrigger_muteLegStillSilent() throws Exception {
        AgentValidateResult r = validate("""
                {"id":"wf","trigger":{"type":"trigger.schedule","config":{"cron":"0 0 8 * * ?"}},
                 "variables":[{"key":"input","type":"object"},{"key":"output","type":"string"},
                              {"key":"tmp","type":"string"}],
                 "nodes":[
                   {"id":"gate","type":"condition.rule",
                    "config":{"branches":[{"target":"ok","conditions":[]}],"default":"no"}},
                   {"id":"say","type":"llm","config":{"prompt":"p","outputVar":"output"}},
                   {"id":"quiet","type":"llm","config":{"prompt":"q","outputVar":"tmp"}}
                 ],
                 "edges":[
                   {"from":"START","to":"gate"},
                   {"from":"gate","type":"conditional","cases":{"ok":"say","no":"quiet"},"default":"quiet"},
                   {"from":"say","to":"END"},{"from":"quiet","to":"END"}
                 ]}
                """);

        assertTrue(r.isPassed(), r.toFeedback());
        assertTrue(r.getWarnings().stream().noneMatch(x -> x.contains(MUTE_LEG_HINT)),
                "定时触发的流程不该被拿会话约定去要求：" + r.getWarnings());
    }

    /**
     * 🔴 {@code trigger == null} <b>要</b>报：编排器产的 BUILD 草稿恰恰不落 trigger 却能被会话拉起，
     * def 416 就是这个形状。漏掉它等于本条形同虚设 —— 它要抓的那张图压根不在射程里。
     * （{@code isHeadlessTrigger(null)} 返回 false，与这一档同向。）
     */
    @Test
    void validate_noTriggerBlock_stillJudgedAsChatRunnable() throws Exception {
        AgentValidateResult r = validate(MUTE_REJECT_LEG_DSL);
        assertTrue(r.getWarnings().stream().anyMatch(w -> w.contains(MUTE_LEG_HINT)),
                "def 416 没有 trigger 块，照样是会话可跑的：" + r.getWarnings());
    }

    /**
     * 键名可被 {@code trigger.chat.config.outputVar} 覆盖 ⟹ 判定必须跟着覆盖走。
     * 这一条同时钉两个方向：写了 {@code reply} 的腿算说话，写了缺省 {@code output} 的腿<b>不算</b>。
     */
    @Test
    void validate_customOutputVarOverride_judgedAgainstTheOverriddenKey() throws Exception {
        AgentValidateResult r = validate("""
                {"id":"wf","trigger":{"type":"trigger.chat","config":{"outputVar":"reply"}},
                 "variables":[{"key":"input","type":"object"},{"key":"reply","type":"string"},
                              {"key":"output","type":"string"}],
                 "nodes":[
                   {"id":"gate","type":"condition.rule",
                    "config":{"branches":[{"target":"ok","conditions":[]}],"default":"no"}},
                   {"id":"say_reply","type":"llm","config":{"prompt":"p","outputVar":"reply"}},
                   {"id":"say_output","type":"llm","config":{"prompt":"q","outputVar":"output"}}
                 ],
                 "edges":[
                   {"from":"START","to":"gate"},
                   {"from":"gate","type":"conditional","cases":{"ok":"say_reply","no":"say_output"},
                    "default":"say_output"},
                   {"from":"say_reply","to":"END"},{"from":"say_output","to":"END"}
                 ]}
                """);

        assertTrue(r.isPassed(), r.toFeedback());
        List<String> hits = r.getWarnings().stream().filter(w -> w.contains(MUTE_LEG_HINT)).toList();
        assertEquals(1, hits.size(), "只该报写 output 那条腿（覆盖之后它不是回复键）：" + r.getWarnings());
        assertTrue(hits.get(0).contains("id=say_output"), "点名点错了腿：" + hits.get(0));
        assertTrue(hits.get(0).contains("reply") && hits.get(0).contains("\"outputVar\":\"reply\""),
                "文案里的键名必须是覆盖后的那个，否则作者照它改完仍旧是哑的：" + hits.get(0));
    }

    /**
     * {@code iteration.body} 里的节点<b>不在</b>父图 edges 里，{@code ancestorsOf} 问不到它们，
     * 但运行期它们确实写 state。不顺着 owner 补一遍就会得到「没人写」的假答案 ——
     * V19 的 {@code suspendAfterWrite} 踩过同一个坑。
     *
     * <p>🔴 两条用例是<b>一对</b>：唯一差别是循环体里那个 llm 写 {@code output} 还是写 {@code tmp}。
     * 只有前半绿、后半也绿，说明「不报」是因为整条规则没生效（假绿）；
     * 只有前半绿、后半红在点名上，才证明真的是循环体那一笔把 gate 那条腿救回来的。
     */
    @Test
    void validate_iterationBodyWritesReply_legCountsAsSpeaking() throws Exception {
        AgentValidateResult speaks = validate(iterationBodyDsl("output"));
        assertTrue(speaks.isPassed(), speaks.toFeedback());
        assertTrue(speaks.getWarnings().stream().noneMatch(w -> w.contains(MUTE_LEG_HINT)),
                "循环体里写了回复键，运行期就真写了，不该报 gate：" + speaks.getWarnings());

        AgentValidateResult mute = validate(iterationBodyDsl("tmp"));
        assertTrue(mute.isPassed(), mute.toFeedback());
        assertTrue(mute.getWarnings().stream().anyMatch(w -> w.contains(MUTE_LEG_HINT) && w.contains("id=gate")),
                "阳性对照：循环体写的是别的键时，gate 那条腿就该被点出来 —— "
                        + "上一条「不报」必须是因为循环体真的写了回复键，而不是规则压根没跑：" + mute.getWarnings());
    }

    /** 循环体里那个 llm 写 {@code bodyVar}；gate 的 default 腿直接连 END */
    private static String iterationBodyDsl(String bodyVar) {
        return """
                {"id":"wf",
                 "variables":[{"key":"input","type":"object"},{"key":"items","type":"array"},
                              {"key":"output","type":"string"},{"key":"tmp","type":"string"}],
                 "nodes":[
                   {"id":"loop","type":"iteration",
                    "config":{"itemsVar":"items","itemVar":"item","body":["say"]}},
                   {"id":"say","type":"llm","config":{"prompt":"p","outputVar":"%s"}},
                   {"id":"gate","type":"condition.rule",
                    "config":{"branches":[{"target":"ok","conditions":[]}],"default":"no"}},
                   {"id":"done","type":"llm","config":{"prompt":"q","outputVar":"output"}}
                 ],
                 "edges":[
                   {"from":"START","to":"loop"},{"from":"loop","to":"gate"},
                   {"from":"gate","type":"conditional","cases":{"ok":"done"},"default":"END"},
                   {"from":"done","to":"END"}
                 ]}
                """.formatted(bodyVar);
    }

    // ==================== ⑬ tool 节点的 args 不是对象（V21） ====================

    /**
     * V21 文案的标记串：取自 {@link ManifestArgs#argsShapeProblem} 那句里<b>只有它会出现</b>的一段。
     * 用标记过滤而不是断言 {@code getErrors().size()}，是为了不被同一次校验里别的错干扰
     * （与 ⑫ 那节的 {@code MUTE_LEG_HINT} 同一个做法）。
     */
    private static final String ARGS_SHAPE_HINT = "只把 args 当「参数名 → 取值模板」";

    /**
     * args <b>整体</b>写成占位符 —— run8 示例 55 与 59 的真实形状（两次的节点都是 {@code do_publish}）。
     * 模型的意图是「让前置 script 节点把参数表拼好，我整体引一下」，而执行期压根不是这么读的。
     */
    private static final String ARGS_PLACEHOLDER_DSL = """
            {"id":"wf",
             "variables":[{"key":"input","type":"object"},{"key":"bundle","type":"object"},
                          {"key":"out","type":"object"}],
             "nodes":[{"id":"do_publish","type":"tool",
                       "config":{"toolId":"form_publish_bundle","args":"{{$.bundle}}","outputVar":"out"}}],
             "edges":[{"from":"START","to":"do_publish"},{"from":"do_publish","to":"END"}]}
            """;

    /** 数一数带标记串的 error，顺带把原文交出去当断言消息（红了才看得见到底报了什么） */
    private static List<String> argsShapeErrors(AgentValidateResult r) {
        return r.getErrors().stream().filter(e -> e.contains(ARGS_SHAPE_HINT)).toList();
    }

    /**
     * 🔴 命中侧：这种形状过去能<b>通过体检、存成草稿、发布出去</b>，直到真跑才炸。
     * 执行期 {@code ToolNodeExecutor:66-70} 是 {@code if (args != null)} —— {@code configMap}
     * 认不出字符串就返回 null，于是整个参数装配被跳过、工具以<b>空参数表</b>被调用。
     * 结论是「一定跑不通」而不是「可能」，故必须是 error（fail-close），不是 warning。
     */
    @Test
    void validate_toolArgsIsPlaceholder_rejected() throws Exception {
        AgentValidateResult r = validate(ARGS_PLACEHOLDER_DSL);

        assertFalse(r.isPassed(), "args 整体是占位符，执行期必然以空参数表调工具，不该放行：" + r.toFeedback());
        assertEquals(1, argsShapeErrors(r).size(), "该恰好一条 V21，多了就是同一根因说了几遍：" + r.getErrors());
        assertTrue(argsShapeErrors(r).get(0).contains("id=do_publish"),
                "文案要点名是哪个节点，否则模型不知道改哪儿：" + argsShapeErrors(r).get(0));
        assertTrue(r.getWarnings().stream().noneMatch(w -> w.contains(ARGS_SHAPE_HINT)),
                "这条必须是 error 一档，降到 warning 就等于放它过去：" + r.getWarnings());
    }

    /**
     * 🔴 文案<b>同源</b>：V21 说的必须与变更清单那一格红标说的是<b>同一句话</b>，逐字。
     *
     * <p>这一条是「不造第二份文案」那个纪律的强制点。两处各写一份的后果是同一种形状
     * 在体检里叫一个说法、在清单里叫另一个说法，用户当成两件事去改两遍 ——
     * 而校验器这边的文案还是给<b>模型</b>看的（自修正回路照它改），措辞漂了模型就改错方向。
     * 断言直接调 {@link ManifestArgs#argsShapeProblem} 取权威文案来比，
     * 所以任何一侧改字都会红，而不是「两边一起漂、测试一起绿」。
     */
    @Test
    void validate_toolArgsShape_wordingIsTheManifestsVerbatim() throws Exception {
        WorkflowDefDTO def = mapper.readValue(ARGS_PLACEHOLDER_DSL, WorkflowDefDTO.class);
        String authoritative = ManifestArgs.argsShapeProblem(def.getNodes().get(0));
        assertTrue(authoritative != null && authoritative.contains(ARGS_SHAPE_HINT),
                "标记串已与权威文案脱钩，本节其余用例的过滤器会恒假（挡不住任何东西）：" + authoritative);

        AgentValidateResult r = validate(ARGS_PLACEHOLDER_DSL);

        assertTrue(argsShapeErrors(r).stream().anyMatch(e -> e.contains(authoritative)),
                "V21 的文案不是从 ManifestArgs.argsShapeProblem 借来的那一份 —— 两侧已经漂了。\n"
                        + "权威：" + authoritative + "\n实际：" + r.getErrors());
    }

    /**
     * 数组同样是「不是对象」：{@code configMap} 对它一样返回 null，执行期一样是空参数表。
     * 只判字符串的话，{@code "args": []}（模型想表达「这个工具不用参数」时的常见写法）就漏了，
     * 而它比字符串那种更隐蔽 —— 看起来像是个合法的空参数表。
     */
    @Test
    void validate_toolArgsIsArray_rejected() throws Exception {
        AgentValidateResult empty = validate(ARGS_PLACEHOLDER_DSL.replace("\"{{$.bundle}}\"", "[]"));
        assertEquals(1, argsShapeErrors(empty).size(), "args 是空数组也该报：" + empty.getErrors());

        AgentValidateResult filled = validate(ARGS_PLACEHOLDER_DSL.replace("\"{{$.bundle}}\"", "[\"id\"]"));
        assertEquals(1, argsShapeErrors(filled).size(), "args 是非空数组也该报：" + filled.getErrors());
    }

    /**
     * 🔴 沉默条件①：<b>是对象就一个字都不说</b>，包括值里带占位符的那种 ——
     * 那正是 V21 文案自己给出的<b>正确出路</b>（脚本把结果写进 outputVar，args 里逐键引用）。
     * 连它也报的话，模型按文案改完还是红的，自修正回路会在 maxRounds 里空转烧钱。
     */
    @Test
    void validate_toolArgsIsObject_passed() throws Exception {
        AgentValidateResult literal = validate(ARGS_PLACEHOLDER_DSL.replace("\"{{$.bundle}}\"", "{\"id\":1024}"));
        assertTrue(literal.isPassed(), "args 是普通对象，本该通过：" + literal.toFeedback());

        AgentValidateResult perKey = validate(
                ARGS_PLACEHOLDER_DSL.replace("\"{{$.bundle}}\"", "{\"id\":\"{{$.bundle.id}}\"}"));
        assertTrue(perKey.isPassed(), "逐键引用是 V21 文案教的出路，不该反过来被它拦：" + perKey.toFeedback());
    }

    /**
     * 🔴 沉默条件②：args <b>缺失</b>或写成 {@code null} 都不报。
     * 契约里 {@code args} 的 required=false（{@code NodeIoContract:172}），无参工具就该不写 ——
     * 报了的话每一张用只读工具的画布都会红一条，作者从此不看红字（⑩ 那节写过的代价）。
     */
    @Test
    void validate_toolArgsMissingOrNull_passed() throws Exception {
        AgentValidateResult absent = validate("""
                {"id":"wf",
                 "variables":[{"key":"input","type":"object"},{"key":"out","type":"object"}],
                 "nodes":[{"id":"list","type":"tool",
                           "config":{"toolId":"data_interface_list","outputVar":"out"}}],
                 "edges":[{"from":"START","to":"list"},{"from":"list","to":"END"}]}
                """);
        assertTrue(absent.isPassed(), "没写 args 的无参工具本该通过：" + absent.toFeedback());

        AgentValidateResult nulled = validate(ARGS_PLACEHOLDER_DSL.replace("\"{{$.bundle}}\"", "null"));
        assertTrue(nulled.isPassed(), "args 显式写 null 等价于没写，不该报：" + nulled.toFeedback());
    }

    /**
     * 🔴 沉默条件③：<b>只管 {@code type == "tool"}</b>。
     * {@code args} 这个端口在 {@code NodeIoContract} 里只有 tool 声明，
     * 别的节点类型 config 里混进一个 args 键，执行器压根不读它 —— 报了纯属噪音。
     * 顺带钉住「判据是节点类型而不是『config 里有没有 args 这个键』」，
     * 后者写法在 llm / script 节点上会误报，而这两种节点在存量画布里遍地都是。
     */
    @Test
    void validate_nonToolNodeWithArgsKey_notReported() throws Exception {
        AgentValidateResult r = validate("""
                {"id":"wf",
                 "variables":[{"key":"input","type":"object"},{"key":"bundle","type":"object"},
                              {"key":"output","type":"string"}],
                 "nodes":[{"id":"say","type":"llm",
                           "config":{"prompt":"p","outputVar":"output","args":"{{$.bundle}}"}}],
                 "edges":[{"from":"START","to":"say"},{"from":"say","to":"END"}]}
                """);

        assertTrue(argsShapeErrors(r).isEmpty(),
                "llm 节点的多余 args 键不该被 V21 拦（只有 tool 声明了这个端口）：" + r.getErrors());
    }

    /**
     * 收集式：一份 DSL 里两个 tool 节点都写坏，两条都要报出来、且各点自己的名。
     * 只报第一条的话，模型改完一个再撞下一个，{@code maxRounds} 几轮就烧光了（类注释那条纪律）。
     */
    @Test
    void validate_twoBadToolNodes_bothReported() throws Exception {
        AgentValidateResult r = validate("""
                {"id":"wf",
                 "variables":[{"key":"input","type":"object"},{"key":"bundle","type":"object"},
                              {"key":"a","type":"object"},{"key":"b","type":"object"}],
                 "nodes":[
                   {"id":"pub_one","type":"tool",
                    "config":{"toolId":"form_publish_bundle","args":"{{$.bundle}}","outputVar":"a"}},
                   {"id":"pub_two","type":"tool",
                    "config":{"toolId":"form_publish_bundle","args":"{{$.bundle}}","outputVar":"b"}}
                 ],
                 "edges":[{"from":"START","to":"pub_one"},{"from":"pub_one","to":"pub_two"},
                          {"from":"pub_two","to":"END"}]}
                """);

        List<String> hits = argsShapeErrors(r);
        assertEquals(2, hits.size(), "两个坏节点该各报一条：" + r.getErrors());
        assertTrue(hits.stream().anyMatch(e -> e.contains("id=pub_one"))
                        && hits.stream().anyMatch(e -> e.contains("id=pub_two")),
                "两条要各点自己的名，否则模型只能猜改哪个：" + hits);
    }

    /**
     * 🔴 顺序前提：args 形状一坏，{@code validateSaveDraftWidgets} / {@code validateSaveDraftMainTable} /
     * {@code validateSaveDraftColumns} 三条<b>全部静默跳过</b>（它们的入口都是 {@code configMap("args")}，
     * 拿不到就 {@code continue}，实测 758 / 872 / 972 三处）。
     * 所以 {@code validate()} 里 V21 必须排在那三条<b>之前</b> —— 否则模型收到的是一份
     * 「控件、主表、列名都没意见」的体检报告，而真相是这个节点<b>一个参数都传不进去</b>。
     *
     * <p>本条钉的就是「只剩根因这一条」：既证明那三条确实跳过了（不是我以为的跳过），
     * 也证明 V21 没有把同一个根因说成几条。
     */
    @Test
    void validate_saveDraftWithBadArgs_reportsOnlyTheRootCause() throws Exception {
        AgentValidateResult r = validate("""
                {"id":"wf",
                 "variables":[{"key":"input","type":"object"},{"key":"bundle","type":"object"},
                              {"key":"out","type":"object"}],
                 "nodes":[{"id":"save_draft","type":"tool",
                           "config":{"toolId":"form_save_draft","args":"{{$.bundle}}","outputVar":"out"}}],
                 "edges":[{"from":"START","to":"save_draft"},{"from":"save_draft","to":"END"}]}
                """);

        assertEquals(1, argsShapeErrors(r).size(), "根因该被说出来：" + r.getErrors());
        assertEquals(1, r.getErrors().size(),
                "args 坏掉时 save_draft 那三条一条也跑不到，故只该有 V21 这一条 error；"
                        + "多出来的说明它们并没有静默跳过，V21 的排序理由与「一个根因只说一遍」都要重核："
                        + r.getErrors());
    }

    // ==================== ⑭ script 节点里调沙箱不存在的 tools.*（V22） ====================

    /**
     * V22 文案的标记串：取自 {@code validateScriptToolCalls} 那句里<b>只有它会出现</b>的一段。
     * 用标记过滤而不是断言 {@code getErrors().size()}，与 ⑬ 节的 {@link #ARGS_SHAPE_HINT} 同一个理由。
     */
    private static final String SCRIPT_TOOLS_HINT = "只注入了一个只读的 state";

    /** run13（2026-10-05 08:43）T2 示例 57 / def #463 里那个 script 节点的真实形状，缩成一行 */
    private static final String SCRIPT_CALLS_TOOL_CODE =
            "var formCode = 'visit_record'; "
                    + "var fields = [{field:'customer', widget:'input', title:'客户'}]; "
                    + "var saveResult = tools.form_save_draft({name:'客户拜访记录表', code: formCode, fields: fields}); "
                    + "return saveResult;";

    /** 数一数带标记串的 error，顺带把原文交出去当断言消息（红了才看得见到底报了什么） */
    private static List<String> scriptToolsErrors(AgentValidateResult r) {
        return r.getErrors().stream().filter(e -> e.contains(SCRIPT_TOOLS_HINT)).toList();
    }

    /**
     * 拼一份「START → script → END」的最小 DSL。
     *
     * <p>🔴 传进来的 code <b>只准用单引号</b>：它是用 {@code %s} 直接塞进 JSON 字符串的，
     * 双引号会把 JSON 撑破 —— 而撑破的表现是 Jackson 抛异常，看起来像「校验器坏了」，
     * 排查会一路走到错的地方去。
     */
    private static String scriptDsl(String nodeId, String code) {
        return """
                {"id":"wf",
                 "variables":[{"key":"input","type":"object"},{"key":"calc","type":"object"}],
                 "nodes":[{"id":"%s","type":"script",
                           "config":{"code":"%s","outputVar":"calc"}}],
                 "edges":[{"from":"START","to":"%s"},{"from":"%s","to":"END"}]}
                """.formatted(nodeId, code, nodeId, nodeId, nodeId);
    }

    /**
     * 🔴 命中侧：这个形状在 V22 落地之前<b>四件事同时成立</b> —— 体检通过（warnings 0 条）、
     * 变更清单 {@code kinds=[]}、{@code riskLevel=READ_ONLY}、草稿卡照出标着「待确认」，
     * 直到用户点完确认才在执行日志里读到 {@code ReferenceError: tools is not defined}。
     *
     * <p>沙箱那边只塞了一个东西（{@code ScriptSandbox:114} 一句 {@code putMember("__stateJson", …)}），
     * 所以这不是「可能失败」而是<b>确定失败</b>，故必须 error 一档（与 V21 同档，§3.1 fail-close）。
     * 清单那一格才是最严重的：它是审批人点「同意」前唯一看的东西，
     * 而它说「一条变更都没有」，实际要建一张表单。
     */
    @Test
    void validate_scriptCallsTools_rejected() throws Exception {
        AgentValidateResult r = validate(scriptDsl("build_form", SCRIPT_CALLS_TOOL_CODE));

        assertFalse(r.isPassed(), "script 里调不存在的 tools.*，运行期必炸，不该放行：" + r.toFeedback());
        List<String> hits = scriptToolsErrors(r);
        assertEquals(1, hits.size(), "该恰好一条 V22，多了就是同一根因说了几遍：" + r.getErrors());
        assertTrue(hits.get(0).contains("id=build_form"), "文案要点名是哪个节点，否则模型不知道改哪儿：" + hits.get(0));
        assertTrue(hits.get(0).contains("tools.form_save_draft"), "文案要说清调的是哪一个：" + hits.get(0));
        assertTrue(hits.get(0).contains("tool 节点"),
                "文案必须给出处路。只说「不行」的话，模型最省事的改法是把这一行删掉 —— "
                        + "那等于把用户要的建表单动作整个抹了（D4-16 那个方向）：" + hits.get(0));
        assertTrue(r.getWarnings().stream().noneMatch(w -> w.contains(SCRIPT_TOOLS_HINT)),
                "这条必须是 error：降到 warning 就等于放那张「看着只读、其实要建表」的卡走到用户面前："
                        + r.getWarnings());
    }

    /**
     * 🔴 出路必须是通的：script 只算不写、把值 return 出来，读写业务数据交给下游 tool 节点。
     * 这条钉的是「模型照 V22 的文案改完不会还是红的」—— 改完还红，自修正回路就会在
     * {@code maxRounds} 里空转烧钱（与 ⑬ 节 {@code validate_toolArgsIsObject_passed} 同一条理由）。
     */
    @Test
    void validate_scriptReturnsAndToolNodeWrites_passed() throws Exception {
        AgentValidateResult r = validate("""
                {"id":"wf",
                 "variables":[{"key":"input","type":"object"},{"key":"calc","type":"object"},
                              {"key":"out","type":"object"}],
                 "nodes":[
                   {"id":"shape_args","type":"script",
                    "config":{"code":"return {q: state.input.keyword};","outputVar":"calc"}},
                   {"id":"do_query","type":"tool",
                    "config":{"toolId":"data_interface_list","args":{"q":"{{$.calc.q}}"},"outputVar":"out"}}
                 ],
                 "edges":[{"from":"START","to":"shape_args"},{"from":"shape_args","to":"do_query"},
                          {"from":"do_query","to":"END"}]}
                """);

        assertTrue(r.isPassed(), "「script 算 → tool 写」正是 V22 文案教的出路，不该反过来被它拦：" + r.toFeedback());
    }

    /**
     * 🔴 免报条件①：作者<b>自己声明</b>过 {@code tools}。
     * 这种写法里那个 {@code tools.form_save_draft(...)} 是作者自己的对象，运行期真有这个东西 ——
     * 报了就是假警报，而假警报的代价是自修正回路去改一个没病的脚本（每轮一次真实计费）。
     * 声明与形参两种写法都要认。
     */
    @Test
    void validate_scriptWithSelfDeclaredTools_notReported() throws Exception {
        AgentValidateResult declared = validate(scriptDsl("calc_fields",
                "var tools = {form_save_draft: function (o) { return o; }}; return tools.form_save_draft({});"));
        assertTrue(scriptToolsErrors(declared).isEmpty(),
                "作者自己 var 出来的 tools 不是沙箱里缺的那个句柄：" + declared.getErrors());

        AgentValidateResult param = validate(scriptDsl("calc_fields",
                "function run(tools) { return tools.form_save_draft({}); } return run({});"));
        assertTrue(scriptToolsErrors(param).isEmpty(), "形参里那个 tools 同理：" + param.getErrors());
    }

    /**
     * 🔴 免报条件②③：<b>属性访问</b>上的那个 {@code tools}，以及<b>字符串字面量</b>里提到的一次调用。
     * 前者是作者自己放进 state 的数据（{@code state.tools.save()}），
     * 后者压根不是调用（例如把「请改用 tool 节点」当成给用户看的提示语 return 出去）。
     *
     * <p>{@code \b} 挡不住前者（{@code .} 本身就是词边界），所以判据用的是 {@code (?<![\w$.])}；
     * 后者靠匹配前抠掉字符串字面量。两处取舍都写在 {@code SCRIPT_TOOLS_CALL} /
     * {@code SCRIPT_STRING_LITERAL} 的注释里，改那两条正则前先读它们。
     */
    @Test
    void validate_scriptToolsOnPropertyOrInsideString_notReported() throws Exception {
        AgentValidateResult property = validate(scriptDsl("calc_fields", "return state.tools.save({});"));
        assertTrue(scriptToolsErrors(property).isEmpty(),
                "state.tools 是作者自己的数据，不是沙箱缺的那个句柄：" + property.getErrors());

        AgentValidateResult literal = validate(scriptDsl("calc_fields",
                "return 'tools.form_save_draft(...) 在脚本里不可用，请改用 tool 节点';"));
        assertTrue(scriptToolsErrors(literal).isEmpty(), "字符串里提一句不是调用：" + literal.getErrors());
    }

    /**
     * 🔴 沉默条件：<b>只看 script 的 {@code config.code}</b>。
     * llm 节点的提示词里出现同一串字不该被拦 —— 而这几乎必然发生：
     * {@code WorkflowDslPlannerPrompt} 教模型「别写 {@code tools.form_save_draft(...)}」的那句话
     * 本身就含这串字，作者把它抄进提示词是很自然的。
     * 判据是「这段文本会不会被当 JS 跑」，不是「这段文本里有没有这串字」。
     */
    @Test
    void validate_toolsMentionInNonScriptNode_notReported() throws Exception {
        AgentValidateResult r = validate("""
                {"id":"wf",
                 "variables":[{"key":"input","type":"object"},{"key":"output","type":"string"}],
                 "nodes":[{"id":"say","type":"llm",
                           "config":{"prompt":"别写 tools.form_save_draft(...) 这种调用","outputVar":"output"}}],
                 "edges":[{"from":"START","to":"say"},{"from":"say","to":"END"}]}
                """);

        assertTrue(scriptToolsErrors(r).isEmpty(), "llm 的提示词不是脚本，不该被 V22 拦：" + r.getErrors());
    }

    /**
     * 🔴 沉默条件：{@code code} 缺失时本条一个字都不说。
     *
     * <p>⚠️ 顺带钉一个实测发现的<b>缺口</b>（2026-10-05 核过）：校验器目前<b>没有任何一条规则</b>
     * 拦「script 缺 code」—— 没有按 {@code NodeIoContract} 做的必填端口通检，
     * {@code 缺少 toolId} / {@code 缺少 bpmProcessDefKey} / {@code 缺少 workflowId} 三条都是各自硬写的。
     * 于是这种 def 能存成草稿、能发布，运行期才由 {@code ScriptNodeExecutor} 抛
     * {@code WORKFLOW_DSL_INVALID}（消息还算清楚，但自修正回路已经够不着它了）。
     * 本条<b>刻意不顺手补</b>那个缺口 —— 那是另一条规则的事，混进来会让 V22 的沉默条件说不清。
     * 已报给用户。
     */
    @Test
    void validate_scriptWithoutCode_notReportedByV22() throws Exception {
        AgentValidateResult r = validate("""
                {"id":"wf",
                 "variables":[{"key":"input","type":"object"},{"key":"calc","type":"object"}],
                 "nodes":[{"id":"calc_it","type":"script","config":{"outputVar":"calc"}}],
                 "edges":[{"from":"START","to":"calc_it"},{"from":"calc_it","to":"END"}]}
                """);

        assertTrue(scriptToolsErrors(r).isEmpty(), "没有 code 就没有可调的东西，V22 该沉默：" + r.getErrors());
    }

    /**
     * 收集式：一份 DSL 里两个 script 节点都在调，两条都要报出来、且各点自己的名。
     * 只报第一条的话，模型改完一个再撞下一个，{@code maxRounds} 几轮就烧光了（类注释那条纪律）。
     */
    @Test
    void validate_twoScriptNodesCallTools_bothReported() throws Exception {
        AgentValidateResult r = validate("""
                {"id":"wf",
                 "variables":[{"key":"input","type":"object"},{"key":"a","type":"object"},
                              {"key":"b","type":"object"}],
                 "nodes":[
                   {"id":"one","type":"script","config":{"code":"return tools.form_save_draft({});","outputVar":"a"}},
                   {"id":"two","type":"script","config":{"code":"return tools.datamodel_create({});","outputVar":"b"}}
                 ],
                 "edges":[{"from":"START","to":"one"},{"from":"one","to":"two"},{"from":"two","to":"END"}]}
                """);

        List<String> hits = scriptToolsErrors(r);
        assertEquals(2, hits.size(), "两个坏节点该各报一条：" + r.getErrors());
        assertTrue(hits.stream().anyMatch(e -> e.contains("id=one"))
                        && hits.stream().anyMatch(e -> e.contains("id=two")),
                "两条要各点自己的名，否则模型只能猜改哪个：" + hits);
    }

    /**
     * 排版变体也要认：带空格的 {@code tools . form_save_draft (}、跨行的 {@code tools\n.form_save_draft(}。
     * 只认「紧贴着写」的话，模型换个排版就绕过去了 —— 而这类绕行是<b>静默</b>的，
     * 规则漏了没人会知道（漏报不像误报那样会自己冒出来）。
     */
    @Test
    void validate_scriptToolsCallWithOddSpacing_rejected() throws Exception {
        AgentValidateResult spaced = validate(scriptDsl("calc_fields", "return tools . form_save_draft ({}) ;"));
        assertEquals(1, scriptToolsErrors(spaced).size(), "带空格的写法同样要认：" + spaced.getErrors());

        AgentValidateResult wrapped = validate(scriptDsl("calc_fields", "return tools\\n.form_save_draft({});"));
        assertEquals(1, scriptToolsErrors(wrapped).size(), "跨行的写法同样要认：" + wrapped.getErrors());
    }

    // ==================== ⑮ 规则条件读的键没人写（V24） ====================

    /**
     * V24 文案的标记串：取自 {@code validateRuleConditionVarsWritten} 那句里<b>只有它会出现</b>的一段。
     * 用标记过滤而不是断言 {@code getErrors().size()}，与 ⑬ 节的 {@link #ARGS_SHAPE_HINT}、
     * ⑭ 节的 {@link #SCRIPT_TOOLS_HINT} 同一个理由：本节用例的 DSL 里还跑着别的规则，
     * 断总数会把「V24 报对了」与「别的规则闭嘴了」两件事混成一件。
     */
    private static final String RULE_VAR_HINT = "没有任何节点写这个键";

    /** 数一数带标记串的 error，顺带把原文交出去当断言消息（红了才看得见到底报了什么） */
    private static List<String> ruleVarErrors(AgentValidateResult r) {
        return r.getErrors().stream().filter(e -> e.contains(RULE_VAR_HINT)).toList();
    }

    /**
     * 拼一份「START → script(写 flag) → condition.rule(判 {@code gateVar}) → 两条腿各写 output → END」
     *
     * <p>这份夹具刻意把<b>其余规则全部喂饱</b>，好让每条用例只考 V24 一件事：
     * 两条腿各有一个 llm 写 {@code output}（V20 闭嘴）、没有工具节点（V1/V2/V11 闭嘴）、
     * 没有 {@code {{$.x}}} 占位符（V7/V8 闭嘴）、{@code flag} 与 {@code output} 都在
     * {@code variables[]} 里声明过（V19 闭嘴）。
     *
     * @param gateVar  condition.rule 要判的那个键名 —— 本节唯一要拧的旋钮
     * @param varsJson 额外追加进 {@code variables[]} 的声明（考「声明了也没用」那一档），可为空串
     */
    private static String ruleGateDsl(String triggerJson, String gateVar, String varsJson) {
        return """
                {"id":"wf",%s
                 "variables":[{"key":"input","type":"object"},{"key":"flag","type":"boolean"},
                              {"key":"output","type":"string"}%s],
                 "nodes":[
                   {"id":"calc","type":"script","config":{"code":"return true;","outputVar":"flag"}},
                   {"id":"gate","type":"condition.rule",
                    "config":{"branches":[{"target":"yes","conditions":[{"var":"%s","op":"is_true"}]}],
                              "default":"no"}},
                   {"id":"say_yes","type":"llm","config":{"prompt":"说清结果","outputVar":"output"}},
                   {"id":"say_no","type":"llm","config":{"prompt":"说清结果","outputVar":"output"}}
                 ],
                 "edges":[
                   {"from":"START","to":"calc"},{"from":"calc","to":"gate"},
                   {"from":"gate","type":"conditional",
                    "cases":{"yes":"say_yes","no":"say_no"},"default":"say_no"},
                   {"from":"say_yes","to":"END"},{"from":"say_no","to":"END"}
                 ]}
                """.formatted(triggerJson, varsJson, gateVar);
    }

    /**
     * 🔴 命中侧：草稿 610（2026-10-09，示例「会议室预订登记表」）的真实形状。
     *
     * <p>那份草稿的 {@code gate_publish} 判的是 {@code confirm_publish}，而上游 hitl.confirm 的
     * {@code approveVar} 填的是 {@code confirm_create} —— <b>两个名字差一个词</b>，
     * 于是 {@code ctx.get("confirm_publish")} 恒为 {@code null}：除 {@code not_exists} 外
     * 所有算子都判不命中，两条分支一起死，流程每次都走 {@code default:"cancel"}，
     * {@code publish_form} 那个节点<b>永远执行不到</b>，而执行记录是<b>成功</b>。
     * 同一份草稿的总结话术还写着「最后由人工确认后发布」。
     *
     * <p>V24 落地之前，本类对 {@code condition.rule} 的 {@code var} <b>一条规则都没有</b>：
     * V7/V8 管的是 {@code {{$.x}}} 占位符，而 {@code var} 在契约里是 MODE_LITERAL
     * （执行期直接 {@code ctx.get(var)}，不过 {@code StateTemplateResolver}），两条都碰不到它。
     */
    @Test
    void validate_ruleConditionVarNobodyWrites_rejected() throws Exception {
        AgentValidateResult r = validate(ruleGateDsl(trigger("chat"), "confirm_publish", ""));

        assertFalse(r.isPassed(), "判一个没人写的键 ⟹ 那条分支运行期永远走不到，不该放行：" + r.toFeedback());
        List<String> hits = ruleVarErrors(r);
        assertEquals(1, hits.size(), "该恰好一条 V24，多了就是同一根因说了几遍：" + r.getErrors());
        String msg = hits.get(0);
        assertTrue(msg.contains("id=gate"), "文案要点名是哪个节点，否则模型不知道改哪儿：" + msg);
        assertTrue(msg.contains("confirm_publish"), "文案要点名是哪个键，一份 DSL 里可能有好几个条件节点：" + msg);
        assertTrue(msg.contains("顶层"),
                "必须说清 var 只认<b>顶层</b>键：实测那三种错法（拿 script 返回的对象取字段、"
                        + "写 a.b 这种嵌套路径、判一个没人写的键）根因是同一句话，不说它就只会换个错法再撞一次：" + msg);
        assertTrue(msg.contains("hitl.confirm"),
                "出路①要点名到具体节点类型。只说「让上游节点真写出这个键」，"
                        + "模型拿不到「人工决定」这种东西该怎么产 —— 而这条正是 610 缺的那半：" + msg);
        assertTrue(msg.contains("approveVar"),
                "光说 hitl.confirm 还不够：610 的错误恰恰是<b>有</b> hitl.confirm、但 approveVar 填了另一个名字：" + msg);
        assertTrue(msg.contains("flag"),
                "出路②要把本流程真被写过的键列出来。不列的话模型只能再编一个名字，"
                        + "那就是它连猜六个控件名那个病（appendWidgetCatalog 类注释）的翻版：" + msg);
        assertTrue(msg.contains("flag、input、output"),
                "「现有可选」必须<b>按字典序</b>印全：豁免集是 HashSet，迭代序不作保证，"
                        + "同一份草稿点两次体检给出两种措辞，用户会当成两次不同的结论，本断言也没法写。"
                        + "⚠️ 这里断的是整段拼好的串、不是三个 indexOf 的先后 —— "
                        + "「output」在文案里出现过两次（出路①那句的 outputVar 就含它），indexOf 会指到前一个："
                        + msg);
        assertTrue(r.getWarnings().stream().noneMatch(w -> w.contains(RULE_VAR_HINT)),
                "这条必须是 error：降到 warning 就等于放那张「分支图上有、运行期一条都走不到」的草稿"
                        + "走到用户面前，而执行记录还是绿的：" + r.getWarnings());
    }

    /**
     * 🔴 反向对照（阳性）：同一个位置改判一个<b>真有节点写</b>的键，整份体检就该干净通过。
     *
     * <p>这条同时是夹具的自检 —— 它红了说明 {@link #ruleGateDsl} 本身不合法，
     * 那么上面那条命中侧用例的 {@code assertFalse(isPassed)} 就是<b>永真</b>的，
     * 判据完全失效（与 {@code WorkflowDslPlannerPromptTest} 里 {@code section()} 自带阳性对照同一条纪律）。
     */
    @Test
    void validate_ruleConditionVarWrittenByScript_passed() throws Exception {
        AgentValidateResult r = validate(ruleGateDsl(trigger("chat"), "flag", ""));

        assertTrue(ruleVarErrors(r).isEmpty(), "flag 由 calc 的 outputVar 写出，V24 该闭嘴：" + r.getErrors());
        assertTrue(r.isPassed(), "夹具本身必须是一份干净可用的 DSL，否则命中侧那条成了永真断言：" + r.toFeedback());
        assertTrue(r.getWarnings().isEmpty(), r.getWarnings().toString());
    }

    /**
     * 🔴 在 {@code variables[]} 里声明了<b>不算</b>写过 —— 这是 610 教的最硬的一课，也是本条规则
     * 设计上<b>唯一</b>容易让步的地方。
     *
     * <p>那份草稿里 {@code confirm_publish} 是<b>声明过的</b>（{@code variables[]} 里明明白白一条），
     * 所以「声明了就豁免」这个看起来很合理的妥协，会让 V24 对<b>真实缺陷</b>保持沉默。
     * 运行期 {@code variables[]} 的作用只是给 {@code KeyStrategyFactoryGenerator} 注册合并策略，
     * 它<b>不赋值</b>；起跑时 state 里有的键完全由触发器那一次 {@code run(code, input, …)} 决定。
     *
     * <p>⚠️ 这一条也是 V24 与 V19 方向相反的地方：V19 报「写了却没声明」，本条报「声明了却没写」。
     * 两者不冲突（一个根因只说一遍），但别把它们合成一条。
     */
    @Test
    void validate_ruleConditionVarDeclaredButNeverWritten_stillRejected() throws Exception {
        AgentValidateResult r = validate(ruleGateDsl(trigger("chat"), "confirm_publish",
                ",{\"key\":\"confirm_publish\",\"type\":\"boolean\"}"));

        List<String> hits = ruleVarErrors(r);
        assertEquals(1, hits.size(), "声明只注册合并策略、不赋值，故声明了照样是死分支：" + r.getErrors());
        assertTrue(hits.get(0).contains("声明"),
                "文案必须<b>主动</b>堵掉「那我声明一下总行了吧」这条最省事的改法 —— "
                        + "不堵的话模型下一轮就会去加一条 variables[] 声明，然后拿着同一份死分支再来一遍："
                        + hits.get(0));
    }

    /** 触发器入参是起跑时 state 里<b>唯一真有值</b>的键，判它完全合法（会话流程最常见的一种写法） */
    @Test
    void validate_ruleConditionVarIsTriggerInput_passed() throws Exception {
        AgentValidateResult r = validate(ruleGateDsl(trigger("chat"), "input", ""));

        assertTrue(ruleVarErrors(r).isEmpty(), "input 由触发器写进 state，V24 该闭嘴：" + r.getErrors());
        assertTrue(r.isPassed(), r.toFeedback());
    }

    /** 作者配了自定义 {@code inputVar} 时，豁免的必须是<b>那个</b>名字，不能只认缺省的 input */
    @Test
    void validate_ruleConditionVarIsCustomInputVar_passed() throws Exception {
        AgentValidateResult r = validate(ruleGateDsl(
                "\"trigger\":{\"type\":\"trigger.chat\",\"config\":{\"inputVar\":\"question\"}},",
                "question", ""));

        assertTrue(ruleVarErrors(r).isEmpty(), "自定义 inputVar 同样算被写过：" + r.getErrors());
        assertTrue(r.isPassed(), r.toFeedback());
    }

    /**
     * 🔴 只有<b>会话</b>触发才判 —— 四种触发器 + 「没有 trigger 块」逐个过一遍。
     *
     * <p>分型判据不是「有没有人在场」，而是「起跑时 state 里有几个键」：
     * 会话触发那两条入口给的是 {@code Map.of(inputVar, question)}（一句话，字符串）
     * 与「确认并运行」的<b>空</b> map，两种都<b>恰好</b>不含任何业务键，前提才成立。
     * 而 {@code POST /agent/workflow/run-stream} 直接把调用方的 map 摊进 state、
     * 且<b>不按触发器分型</b>（{@code KeyStrategyFactoryGenerator} 的 REPLACE 策略收未声明的键），
     * 所以无头流程的键完全可能由外部系统喂进来 —— 那时候判「没人写」就是<b>误报</b>，
     * 而误报会把一份本来能跑的流程拦在发布之前，比漏报更坏。
     *
     * <p>⚠️ 判据写的是 {@code TRIGGER_TYPE_CHAT.equals(triggerTypeOf(def))}，
     * <b>不是</b> {@code !isHeadlessTrigger(...)}：后者对「没有 trigger 块」（{@code triggerTypeOf} 返回
     * {@code null}）也为真，而那一档同样只能由人手动 run-stream 拉起、同样可能被喂任意键。
     * 下面 {@link #NO_TRIGGER} 那一轮就是钉这件事的。
     */
    @Test
    void validate_ruleConditionVarOnlyJudgedForChatTrigger() throws Exception {
        for (String triggerJson : List.of(NO_TRIGGER, trigger("schedule"), trigger("form_event"),
                webhookTrigger(AUTHED_WEBHOOK))) {
            AgentValidateResult r = validate(ruleGateDsl(triggerJson, "confirm_publish", ""));
            assertTrue(ruleVarErrors(r).isEmpty(),
                    "非会话触发下外部调用方能直接喂顶层键，判「没人写」会是误报。触发器="
                            + triggerJson + "，报出：" + r.getErrors());
        }
    }

    /**
     * {@code conditionGroups} 在场时本条<b>不判</b> —— 因为执行期压根不看扁平的 {@code conditions}。
     *
     * <p>{@code RuleConditionNodeExecutor#matchBranch} 一开头就短路：
     * {@code branch.get("conditionGroups") instanceof Map} 时整个交给
     * {@code infraLogicRuleApi.matchesConditionGroups(groups, ctx.data())}，
     * 后者吃的是<b>整份 state map</b>（不是逐键 {@code ctx.get}），取值语义与裸键名不同。
     * 拿本条的判据去判那一档，报出来的错<b>不是执行期真走的那条路</b> —— 报了也不是根因，
     * 只会把模型的自修正引到没病的字段上去（与 {@code validateToolArgsShape} 的排位纪律同一条理由）。
     */
    @Test
    void validate_ruleConditionWithConditionGroups_notJudged() throws Exception {
        AgentValidateResult r = validate("""
                {"id":"wf","trigger":{"type":"trigger.chat"},
                 "variables":[{"key":"input","type":"object"},{"key":"output","type":"string"}],
                 "nodes":[
                   {"id":"gate","type":"condition.rule",
                    "config":{"branches":[{"target":"yes","conditionGroups":{"logic":"and","conditions":[]},
                                           "conditions":[{"var":"confirm_publish","op":"is_true"}]}],
                              "default":"no"}},
                   {"id":"say_yes","type":"llm","config":{"prompt":"说清结果","outputVar":"output"}},
                   {"id":"say_no","type":"llm","config":{"prompt":"说清结果","outputVar":"output"}}
                 ],
                 "edges":[
                   {"from":"START","to":"gate"},
                   {"from":"gate","type":"conditional",
                    "cases":{"yes":"say_yes","no":"say_no"},"default":"say_no"},
                   {"from":"say_yes","to":"END"},{"from":"say_no","to":"END"}
                 ]}
                """);

        assertTrue(ruleVarErrors(r).isEmpty(), "conditionGroups 那一档执行期走的是另一套取值语义：" + r.getErrors());
    }

    /**
     * 🔴 出路①必须<b>实测有效</b>：照文案加一个 hitl.confirm、把它的 {@code approveVar} 填成要判的键，
     * 这份 DSL 就该干净通过。
     *
     * <p>钉的是「模型照 V24 的文案改完不会还是红的」—— 改完还红，自修正回路就会在
     * {@code maxRounds} 里空转烧钱（与 ⑭ 节 {@code validate_scriptReturnsAndToolNodeWrites_passed}
     * 同一条理由，那节的注释记着这是本类反复踩过的坑）。
     */
    @Test
    void validate_ruleConditionVarWrittenByHitlConfirm_passed() throws Exception {
        AgentValidateResult r = validate("""
                {"id":"wf","trigger":{"type":"trigger.chat"},
                 "variables":[{"key":"input","type":"object"},{"key":"confirm_publish","type":"boolean"},
                              {"key":"output","type":"string"}],
                 "nodes":[
                   {"id":"ask","type":"hitl.confirm","config":{"approveVar":"confirm_publish"}},
                   {"id":"gate","type":"condition.rule",
                    "config":{"branches":[{"target":"yes",
                                           "conditions":[{"var":"confirm_publish","op":"is_true"}]}],
                              "default":"no"}},
                   {"id":"say_yes","type":"llm","config":{"prompt":"说清结果","outputVar":"output"}},
                   {"id":"say_no","type":"llm","config":{"prompt":"说清结果","outputVar":"output"}}
                 ],
                 "edges":[
                   {"from":"START","to":"ask"},{"from":"ask","to":"gate"},
                   {"from":"gate","type":"conditional",
                    "cases":{"yes":"say_yes","no":"say_no"},"default":"say_no"},
                   {"from":"say_yes","to":"END"},{"from":"say_no","to":"END"}
                 ]}
                """);

        assertTrue(ruleVarErrors(r).isEmpty(),
                "hitl.confirm 的 approveVar 就是「让这个键真被写出来」的正解，V24 该闭嘴：" + r.getErrors());
        assertTrue(r.isPassed(), "文案给的出路必须走得通，否则自修正只会空转：" + r.toFeedback());
        assertTrue(r.getWarnings().isEmpty(), r.getWarnings().toString());
    }

    /**
     * 收集式纪律在这里同样成立：一份 DSL 里两个条件节点各判一个没人写的键，要一次说完。
     * 只报第一条的话，模型改完一个再撞下一个，几轮就烧光了（类注释那条纪律）。
     */
    @Test
    void validate_twoRuleConditionsBothUnwritten_bothReported() throws Exception {
        AgentValidateResult r = validate("""
                {"id":"wf","trigger":{"type":"trigger.chat"},
                 "variables":[{"key":"input","type":"object"},{"key":"output","type":"string"}],
                 "nodes":[
                   {"id":"gate_one","type":"condition.rule",
                    "config":{"branches":[{"target":"yes","conditions":[{"var":"aaa_missing","op":"is_true"}]}],
                              "default":"no"}},
                   {"id":"gate_two","type":"condition.rule",
                    "config":{"branches":[{"target":"yes","conditions":[{"var":"zzz_missing","op":"is_true"}]}],
                              "default":"no"}},
                   {"id":"say","type":"llm","config":{"prompt":"说清结果","outputVar":"output"}}
                 ],
                 "edges":[
                   {"from":"START","to":"gate_one"},
                   {"from":"gate_one","type":"conditional","cases":{"yes":"gate_two"},"default":"gate_two"},
                   {"from":"gate_two","type":"conditional","cases":{"yes":"say"},"default":"say"},
                   {"from":"say","to":"END"}
                 ]}
                """);

        List<String> hits = ruleVarErrors(r);
        assertEquals(2, hits.size(), "两个坏条件节点该各报一条：" + r.getErrors());
        assertTrue(hits.stream().anyMatch(e -> e.contains("aaa_missing"))
                        && hits.stream().anyMatch(e -> e.contains("zzz_missing")),
                "两条要各点自己的名，否则模型只能猜改哪个：" + hits);
    }

    // ==================== ⑯ 条件边的出发点（V25） ====================

    /**
     * V25 文案的标记串：取自 {@code validateConditionalEdgeSources} 那句里<b>只有它会出现</b>的一段。
     * 用标记过滤而不是断言 {@code getErrors().size()}，与 ⑮ 节的 {@link #RULE_VAR_HINT} 同一个理由：
     * 本节有几条用例（触发器矩阵那条）故意让别的规则一起响。
     */
    private static final String COND_EDGE_SOURCE_HINT = "永远不会命中 cases";

    /** 数一数带标记串的 error，顺带把原文交出去当断言消息（红了才看得见到底报了什么） */
    private static List<String> condEdgeSourceErrors(AgentValidateResult r) {
        return r.getErrors().stream().filter(e -> e.contains(COND_EDGE_SOURCE_HINT)).toList();
    }

    /**
     * 正解里那个 {@code condition.rule} 节点：判 {@code ask} 的 {@code approveVar}（{@code confirm_it}）。
     *
     * <p>🔴 它同时喂饱 V24 —— 判的键真有节点写（{@code HitlConfirmNodeExecutor} 会把归一化后的布尔值
     * 写进 {@code approveVar}）。两条规则<b>必须一起可满足</b>，否则模型照着 V25 的文案改完
     * 会立刻撞上 V24，自修正回路白烧一轮。
     */
    private static final String RULE_GATE_NODE = """
            {"id":"gate","type":"condition.rule",
             "config":{"branches":[{"target":"yes","conditions":[{"var":"confirm_it","op":"is_true"}]}],
                       "default":"no"}},
            """;

    /** {@code condition.llm} 那一种出发点：路由键来自 {@code choices}，与 cases 逐字对齐 */
    private static final String LLM_GATE_NODE = """
            {"id":"gate","type":"condition.llm",
             "config":{"prompt":"用户同意了吗？","choices":["yes","no"],"default":"no"}},
            """;

    /**
     * 拼一份「START → hitl.confirm(ask) →〔可选的条件节点〕→ 条件边 → 两条腿各写 output → END」
     *
     * <p>这份夹具刻意把<b>其余规则全部喂饱</b>，好让每条用例只考 V25 一件事：
     * 两条腿各有一个 llm 写 {@code output}（V20 闭嘴）、没有工具节点（V1/V2/V11 闭嘴）、
     * 没有 {@code {{$.x}}} 占位符（V7/V8 闭嘴）、{@code confirm_it} 与 {@code output} 都在
     * {@code variables[]} 里声明过（V19/V23 闭嘴）。
     *
     * @param triggerJson  触发器块，见 {@link #trigger(String)}
     * @param gateNodeJson 额外插在 ask 之后的节点（含结尾逗号），不要就传空串
     * @param askOutEdge   ask 的普通出边（含结尾逗号）；条件边直接接在 ask 后面时传空串
     * @param condFrom     条件边的 {@code from} —— 本节唯一要拧的旋钮
     */
    private static String confirmGateDsl(String triggerJson, String gateNodeJson,
                                         String askOutEdge, String condFrom) {
        return """
                {"id":"wf",%s
                 "variables":[{"key":"input","type":"object"},{"key":"confirm_it","type":"boolean"},
                              {"key":"output","type":"string"}],
                 "nodes":[
                   {"id":"ask","type":"hitl.confirm","config":{"approveVar":"confirm_it"}},
                   %s
                   {"id":"say_yes","type":"llm","config":{"prompt":"说清结果","outputVar":"output"}},
                   {"id":"say_no","type":"llm","config":{"prompt":"说清结果","outputVar":"output"}}
                 ],
                 "edges":[
                   {"from":"START","to":"ask"},%s
                   {"from":"%s","type":"conditional",
                    "cases":{"yes":"say_yes","no":"say_no"},"default":"say_no"},
                   {"from":"say_yes","to":"END"},{"from":"say_no","to":"END"}
                 ]}
                """.formatted(triggerJson, gateNodeJson, askOutEdge, condFrom);
    }

    /**
     * 🔴 命中侧：草稿 618（2026-10-09，示例「设备维修记录加字段」）的真实形状 ——
     * 条件边<b>直接接在 {@code hitl.confirm} 后面</b>。
     *
     * <p>那次实跑（执行 186）的结果是：用户点了同意、resume 喂进 {@code {"confirmed":true}}、
     * 执行报 DONE，而终态里 {@code update_result} / {@code publish_result} 全是 null，
     * {@code output} 是「已收到您的取消请求，DDL 未执行，表结构保持不变」。
     * 原因是 {@code HitlConfirmNodeExecutor} 只写 {@code approveVar} 与 {@code <id>_confirmed}，
     * <b>不写路由键</b>，于是条件边恒读不到、恒落 {@code default}（那条腿正是「汇报取消」）。
     *
     * <p>V25 落地之前没有任何一层拦得住它：{@code validateEdges} 只看端点与 cases/default 有没有，
     * 干跑能把图建得好好的（{@code addConditionalEdge} 那句注释甚至<b>断言</b>「源恒为 condition 节点」，
     * 却没人检查这个假设），运行期则静默走 default。
     */
    @Test
    void validate_conditionalEdgeFromHitlConfirm_rejected() throws Exception {
        AgentValidateResult r = validate(confirmGateDsl(trigger("chat"), "", "", "ask"));

        assertFalse(r.isPassed(), "条件边接在 hitl.confirm 后面 ⟹ cases 一条都命中不了，不该放行：" + r.toFeedback());
        List<String> hits = condEdgeSourceErrors(r);
        assertEquals(1, hits.size(), "该恰好一条 V25，多了就是同一根因说了几遍：" + r.getErrors());
        assertEquals(hits, r.getErrors(),
                "夹具本身除了这一处坏边必须是干净的：若还有别的规则在响，"
                        + "上面那句 assertFalse(isPassed) 就成了永真断言（与 ⑮ 节阳性对照同一条纪律）："
                        + r.getErrors());
        String msg = hits.get(0);
        assertTrue(msg.contains("节点(ask)"), "文案要点名是哪条边，一份 DSL 里可能有好几条条件边：" + msg);
        assertTrue(msg.contains("」出发"),
                "「」与中文之间不该有空格（拉丁字母那一档才要空格）：这句是喂给模型与体检面板的，"
                        + "读起来别扭就会被当成机器翻译，而它的说服力全在「说清怎么改」上：" + msg);
        assertTrue(msg.contains("hitl.confirm"),
                "文案要点名 hitl.confirm 也不写路由键 —— 这正是模型最容易想当然的那一格："
                        + "它以为「人工确认完就该按确认结果分叉」，而分叉得由条件节点做：" + msg);
        assertTrue(msg.contains("__route_"),
                "要把「条件边读的是哪个键」说出来，否则模型只能猜为什么读不到：" + msg);
        assertTrue(msg.contains("condition.rule"), "出路要点名到具体节点类型，不能只说「加个条件节点」：" + msg);
        assertTrue(msg.contains("approveVar"),
                "光说 condition.rule 还不够：它的 var 填什么才是这一改的关键，填错就接着撞 V24：" + msg);
        assertTrue(msg.contains("say_no"),
                "要点名 default 那条腿是谁，作者才知道「每次实际走的是哪一条」：" + msg);
        assertTrue(r.getWarnings().stream().noneMatch(w -> w.contains(COND_EDGE_SOURCE_HINT)),
                "这条必须是 error：降到 warning 就等于放那张「同意被当成拒绝、还报执行成功」的草稿"
                        + "走到用户面前：" + r.getWarnings());
    }

    /**
     * 🔴 反向对照（阳性）：照 V25 的文案在 ask 与条件边之间插一个 {@code condition.rule}，
     * 整份体检就该干净通过 —— 0 error、0 warning。
     *
     * <p>钉的是「模型照文案改完不会还是红的」：改完还红，自修正回路就会在 {@code maxRounds}
     * 里空转烧钱（⑭⑮ 两节都记着这个坑）。同时它也证明 V25 与 V24 <b>一起可满足</b> ——
     * 那个 {@code condition.rule} 判的 {@code confirm_it} 正是 {@code ask} 的 {@code approveVar}。
     */
    @Test
    void validate_conditionalEdgeFromRuleCondition_passed() throws Exception {
        AgentValidateResult r = validate(confirmGateDsl(trigger("chat"), RULE_GATE_NODE,
                "{\"from\":\"ask\",\"to\":\"gate\"},", "gate"));

        assertTrue(condEdgeSourceErrors(r).isEmpty(), "condition.rule 会写路由键，V25 该闭嘴：" + r.getErrors());
        assertTrue(r.isPassed(), "文案给的出路必须走得通：" + r.toFeedback());
        assertTrue(r.getWarnings().isEmpty(), r.getWarnings().toString());
    }

    /** 另一种合法出发点：{@code condition.llm} 同样把路由键写进 {@code __route_<id>} */
    @Test
    void validate_conditionalEdgeFromLlmCondition_passed() throws Exception {
        AgentValidateResult r = validate(confirmGateDsl(trigger("chat"), LLM_GATE_NODE,
                "{\"from\":\"ask\",\"to\":\"gate\"},", "gate"));

        assertTrue(condEdgeSourceErrors(r).isEmpty(), "condition.llm 也写路由键，V25 该闭嘴：" + r.getErrors());
        assertTrue(r.isPassed(), r.toFeedback());
        assertTrue(r.getWarnings().isEmpty(), r.getWarnings().toString());
    }

    /**
     * 第二种实测会想到的错法：{@code script} 节点 return 一个路由串，作者就把条件边直接接在它后面。
     *
     * <p>它与 hitl 那一档同因同果（script 写的是 {@code outputVar}，不是路由键），
     * 但<b>更容易被作者认为是对的</b> —— 「我都算出 yes/no 了，cases 里也写了 yes/no，凭什么不匹配」。
     * 故文案里那句「别的节点一个都不写」必须覆盖到它。
     */
    @Test
    void validate_conditionalEdgeFromScript_rejected() throws Exception {
        AgentValidateResult r = validate("""
                {"id":"wf","trigger":{"type":"trigger.chat"},
                 "variables":[{"key":"input","type":"object"},{"key":"route","type":"string"},
                              {"key":"output","type":"string"}],
                 "nodes":[
                   {"id":"calc","type":"script",
                    "config":{"code":"return 'yes';","outputVar":"route"}},
                   {"id":"say_yes","type":"llm","config":{"prompt":"说清结果","outputVar":"output"}},
                   {"id":"say_no","type":"llm","config":{"prompt":"说清结果","outputVar":"output"}}
                 ],
                 "edges":[
                   {"from":"START","to":"calc"},
                   {"from":"calc","type":"conditional",
                    "cases":{"yes":"say_yes","no":"say_no"},"default":"say_no"},
                   {"from":"say_yes","to":"END"},{"from":"say_no","to":"END"}
                 ]}
                """);

        List<String> hits = condEdgeSourceErrors(r);
        assertEquals(1, hits.size(), "script 也不写路由键，同样恒落 default：" + r.getErrors());
        assertTrue(hits.get(0).contains("script"), "要点名出发点的类型，模型才知道是哪条边：" + hits.get(0));
    }

    /**
     * START / END 出发的条件边<b>照样报</b>：{@code routeKey("START")} 同样没人写。
     *
     * <p>🔴 这一档<b>不会被别的层拦住</b>：{@code addConditionalEdge} 不看 from 的类型，
     * 而 {@code buildStateGraph} 只在「一条非条件的 START 出边都没有」时自己合成一条入口边
     * （hasStartEdge 那段）—— 于是入口有了、这条条件边也挂着，只是每次都落 default。
     */
    @Test
    void validate_conditionalEdgeFromStart_rejected() throws Exception {
        AgentValidateResult r = validate("""
                {"id":"wf","trigger":{"type":"trigger.chat"},
                 "variables":[{"key":"input","type":"object"},{"key":"output","type":"string"}],
                 "nodes":[
                   {"id":"say_yes","type":"llm","config":{"prompt":"说清结果","outputVar":"output"}},
                   {"id":"say_no","type":"llm","config":{"prompt":"说清结果","outputVar":"output"}}
                 ],
                 "edges":[
                   {"from":"START","type":"conditional",
                    "cases":{"yes":"say_yes","no":"say_no"},"default":"say_no"},
                   {"from":"say_yes","to":"END"},{"from":"say_no","to":"END"}
                 ]}
                """);

        List<String> hits = condEdgeSourceErrors(r);
        assertEquals(1, hits.size(), "START 出发的条件边同样恒落 default：" + r.getErrors());
        assertTrue(hits.get(0).contains("保留字 START 出发"),
                "要点名是 START 这条边；且拉丁字母与中文之间该有一个空格：" + hits.get(0));
    }

    /**
     * {@code from} 解析不到任何节点时 V25 <b>闭嘴</b> —— 那个根因 {@code validateEdges} 已经报过，
     * 同一条坏边报两种错只会让模型去改没病的地方（本类「一个根因只说一遍」的纪律）。
     */
    @Test
    void validate_conditionalEdgeFromUnknownNode_leftToValidateEdges() throws Exception {
        AgentValidateResult r = validate(confirmGateDsl(trigger("chat"), "", "", "ghost"));

        assertTrue(condEdgeSourceErrors(r).isEmpty(), "from 压根解析不到节点，那是 validateEdges 的活：" + r.getErrors());
        assertTrue(r.getErrors().stream().anyMatch(e -> e.contains("解析不到任何节点")),
                "但那条错必须在，否则这个形状就没人拦了：" + r.getErrors());
    }

    /**
     * {@code parallel} 出发的条件边豁免：编译器<b>当场拒绝</b>它
     * （「parallel 节点的出边不能是条件边（MVP 只支持静态汇聚目标）」），响的那一层已经说清怎么改。
     *
     * <p>🔴 这条同时钉住一个既有事实：⑦ 节
     * {@link #validate_parallelJoinReferenceBothBranches_noWarning} 的夹具正是用
     * 「parallel 出发的条件边」表达 fan-out 的，不豁免会把那份绿夹具判红。
     */
    @Test
    void validate_conditionalEdgeFromParallel_leftToTheCompiler() throws Exception {
        AgentValidateResult r = validate("""
                {"id":"wf",
                 "variables":[{"key":"input","type":"object"},{"key":"ra","type":"object"},
                              {"key":"rb","type":"object"},{"key":"txt","type":"string"}],
                 "nodes":[
                   {"id":"p","type":"parallel","config":{"join":"all"}},
                   {"id":"a","type":"tool","config":{"toolId":"data_interface_list","outputVar":"ra"}},
                   {"id":"b","type":"tool","config":{"toolId":"data_interface_list","outputVar":"rb"}},
                   {"id":"j","type":"llm","config":{"prompt":"{{$.ra}} 与 {{$.rb}}","outputVar":"txt"}}
                 ],
                 "edges":[
                   {"from":"START","to":"p"},
                   {"from":"p","type":"conditional","cases":{"b1":"a","b2":"b"}},
                   {"from":"a","to":"j"},{"from":"b","to":"j"},{"from":"j","to":"END"}
                 ]}
                """);

        assertTrue(condEdgeSourceErrors(r).isEmpty(), "parallel 那一档由编译器响着拒，V25 不叠：" + r.getErrors());
        assertTrue(r.isPassed(), r.toFeedback());
    }

    /**
     * 🔴 与 V24 <b>相反</b>：本条<b>不分触发器</b>，四种触发器 + 「没有 trigger 块」全判。
     *
     * <p>理由是判据的确定性不同：V24 判「这个键恒为 null」，前提取决于起跑时 state 里有几个键
     * （手动 run-stream 那档调用方能自己撒键进来，故不能判）；而 V25 判的是
     * 「<b>没有任何节点</b>会写 {@code __route_<from>}」，这与谁把流程拉起来<b>无关</b> ——
     * 执行器不写就是不写。
     *
     * <p>⚠️ 本节只断 V25 那一条：无头触发下 {@code hitl.confirm} 会被 V13 另外拦一次
     * （没人在场，闸口等不到唤醒），那是<b>另一个</b>根因，两条错并存是对的。
     */
    @Test
    void validate_conditionalEdgeSourceJudgedForEveryTrigger() throws Exception {
        for (String triggerJson : List.of(NO_TRIGGER, trigger("chat"), trigger("schedule"),
                trigger("form_event"), webhookTrigger(AUTHED_WEBHOOK))) {
            AgentValidateResult r = validate(confirmGateDsl(triggerJson, "", "", "ask"));
            assertEquals(1, condEdgeSourceErrors(r).size(),
                    "路由键没人写与触发器无关，每一档都该报。触发器=" + triggerJson + "，报出：" + r.getErrors());
        }
    }

    /** 收集式纪律：一份 DSL 里两条坏条件边要一次说完（类注释那条「别 fail-fast」的纪律） */
    @Test
    void validate_twoBadConditionalEdges_bothReported() throws Exception {
        AgentValidateResult r = validate("""
                {"id":"wf","trigger":{"type":"trigger.chat"},
                 "variables":[{"key":"input","type":"object"},{"key":"confirm_it","type":"boolean"},
                              {"key":"flag","type":"boolean"},{"key":"output","type":"string"}],
                 "nodes":[
                   {"id":"ask","type":"hitl.confirm","config":{"approveVar":"confirm_it"}},
                   {"id":"calc","type":"script","config":{"code":"return true;","outputVar":"flag"}},
                   {"id":"say_yes","type":"llm","config":{"prompt":"说清结果","outputVar":"output"}},
                   {"id":"say_no","type":"llm","config":{"prompt":"说清结果","outputVar":"output"}}
                 ],
                 "edges":[
                   {"from":"START","to":"ask"},
                   {"from":"ask","type":"conditional","cases":{"yes":"calc"},"default":"say_no"},
                   {"from":"calc","type":"conditional","cases":{"yes":"say_yes"},"default":"say_no"},
                   {"from":"say_yes","to":"END"},{"from":"say_no","to":"END"}
                 ]}
                """);

        List<String> hits = condEdgeSourceErrors(r);
        assertEquals(2, hits.size(), "两条坏边该各报一条：" + r.getErrors());
        assertTrue(hits.stream().anyMatch(e -> e.contains("节点(ask)"))
                        && hits.stream().anyMatch(e -> e.contains("节点(calc)")),
                "两条要各点自己的名，否则模型只能猜改哪条：" + hits);
    }

    // ==================== ⑰ script 从工具结果里取列表的方式（V26） ====================

    /**
     * 四个形状各自的文案标记串：取自 {@code validateScriptResultShape} 那四句里<b>只有它会出现</b>的一段。
     *
     * <p>🔴 用标记过滤而不是断言 {@code getErrors().size()}，与 ⑬ 节 {@link #ARGS_SHAPE_HINT}、
     * ⑭ 节 {@link #SCRIPT_TOOLS_HINT} 同一个理由：本节夹具只喂饱了 script 与 tool 两个节点，
     * 别的规则（干跑折叠、平台引用四查）在真实工具键上还会各自说话，断总数就是「测 A 却因为 B 变红」。
     */
    private static final String PICKROWS_UNDEFINED_HINT = "却没有在【本脚本里】定义它";
    private static final String PICKROWS_UNKEYED_HINT = "不点名取值";
    private static final String PICKROWS_NO_ARRAY_HINT = "没有任何数组值的键";
    private static final String ARRAY_METHOD_HINT = "当数组用了";
    private static final String GUESSED_WRAPPER_KEY_HINT = "恒为 undefined";

    /**
     * 提示词教模型<b>抄</b>的那段 {@code pickRows} 函数体，缩成一行。
     *
     * <p>🔴 绿色用例里必须真把它抄上，否则形状一（「调了却没定义」）会先响，
     * 那条红看起来像「形状二/三/四的免判失效」，排查会走到错的地方去。
     * <p>⚠️ 与提示词里那份<b>不是</b>逐字镜像（这里省略了两处错误消息的候选键拼接），
     * 故不受 {@code WorkflowDslPlannerPromptTest#promptMainListKeys_mirrorsTheJavaConstant} 约束；
     * 本节的判据只看「有没有定义」与「点没点名」，不看函数体内部。
     */
    private static final String PICKROWS_DEF =
            "function pickRows(o, key) { if (Array.isArray(o)) return o; "
                    + "if (!o) throw new Error('上游没有写回结果'); "
                    + "if (key) { if (!Array.isArray(o[key])) throw new Error('没有名为 ' + key + ' 的数组'); "
                    + "return o[key]; } "
                    + "var ks = Object.keys(o).filter(function (k) { return Array.isArray(o[k]); }); "
                    + "if (ks.length === 1) return o[ks[0]]; "
                    + "var MAIN = ['items', 'list', 'models']; "
                    + "var hit = ks.filter(function (k) { return MAIN.indexOf(k) !== -1; }); "
                    + "if (hit.length === 1) return o[hit[0]]; "
                    + "throw new Error('猜不出该取哪个数组'); } ";

    /** 登记一个内置工具桩并声明它的返回形状；{@code arrayKeys} 为空 = 「<b>确实</b>不回数组」 */
    private void stubToolShape(String name, RiskLevel risk, List<String> arrayKeys) {
        stubBuiltinTool(name, risk);
        AgentToolRegistry.ToolDefinition def = toolStubs.get(name);
        when(def.getDeclaredResultArrayKeys()).thenReturn(arrayKeys);
        when(def.isDeclaredResultShapeKnown()).thenReturn(true);
    }

    /**
     * 拼一份「START → tool(产出 {@code varName}) → script({@code code}) → END」的最小 DSL
     *
     * <p>🔴 tool 节点<b>必须排在 script 前面</b>：V26 的免判条件之一就是「产出者不在上游就不判」
     * （那一档的根因是排序，报「该点名哪个键」会把自修正回路引向没病的代码）。
     * 考那一档的用例用 {@link #scriptBeforeToolDsl}。
     *
     * <p>🔴 传进来的 code <b>只准用单引号</b>（同 {@link #scriptDsl} 那条理由）：
     * 它是用 {@code %s} 直接塞进 JSON 字符串的，双引号会把 JSON 撑破，
     * 而撑破的表现是 Jackson 抛异常，看起来像「校验器坏了」。
     */
    private static String toolThenScriptDsl(String toolId, String varName, String code) {
        return """
                {"id":"wf",
                 "variables":[{"key":"input","type":"object"},{"key":"%s","type":"object"},
                              {"key":"calc","type":"object"}],
                 "nodes":[
                   {"id":"fetch","type":"tool","config":{"toolId":"%s","args":{},"outputVar":"%s"}},
                   {"id":"shape","type":"script","config":{"code":"%s","outputVar":"calc"}}
                 ],
                 "edges":[{"from":"START","to":"fetch"},{"from":"fetch","to":"shape"},
                          {"from":"shape","to":"END"}]}
                """.formatted(varName, toolId, varName, code);
    }

    /** 同上，但把 script 排在 tool <b>前面</b> —— 考「产出者不在上游 ⟹ V26 一律沉默」那一档 */
    private static String scriptBeforeToolDsl(String toolId, String varName, String code) {
        return """
                {"id":"wf",
                 "variables":[{"key":"input","type":"object"},{"key":"%s","type":"object"},
                              {"key":"calc","type":"object"}],
                 "nodes":[
                   {"id":"shape","type":"script","config":{"code":"%s","outputVar":"calc"}},
                   {"id":"fetch","type":"tool","config":{"toolId":"%s","args":{},"outputVar":"%s"}}
                 ],
                 "edges":[{"from":"START","to":"shape"},{"from":"shape","to":"fetch"},
                          {"from":"fetch","to":"END"}]}
                """.formatted(varName, code, toolId, varName);
    }

    private static List<String> hitsOf(AgentValidateResult r, String marker) {
        return r.getErrors().stream().filter(e -> e.contains(marker)).toList();
    }

    /**
     * 🔴 形状四（猜包裹键名）—— 本条规则存在的<b>主要</b>理由，因为它是四个形状里唯一<b>静默</b>的
     *
     * <p>形状就是实测疤：一条加字段流程写 {@code state.model_list_out.items || state.model_list_out.list}，
     * 而 {@code datamodel_list} 回的键叫 {@code models} ⟹ 两个表达式都是 {@code undefined} ⟹
     * {@code rows} 是 {@code []} ⟹ 本节记 SUCCESS，下一节才报
     * 「工具(datamodel_structure)的参数不合法：id 必须是数字，收到的是：」——
     * 排查的人根本看不出根因在<b>上一节</b>猜错了键名。
     *
     * <p>与 V19/V24 同一档危害分级：<b>静默</b>才是必须阻断的那一档。
     */
    @Test
    void validate_scriptGuessesWrapperKey_rejected() throws Exception {
        // 🔴 声明取自真实工具而不是手抄一份：桩漂了这条用例就会变成「V26 与自己打架」的空测
        stubToolShape("datamodel_list", RiskLevel.READ_ONLY, new DataModelListTool().resultArrayKeys());
        AgentValidateResult r = validate(toolThenScriptDsl("datamodel_list", "model_list_out",
                "var rows = state.model_list_out.items || state.model_list_out.list; return rows.length;"));

        List<String> hits = hitsOf(r, GUESSED_WRAPPER_KEY_HINT);
        assertEquals(1, hits.size(), "同一变量上猜错两个键是【一个】根因（不知道正确的键叫什么），"
                + "该合并成一条，多了自修正回路会改两遍同一处：" + r.getErrors());
        assertTrue(hits.get(0).contains("id=shape"), "文案要点名是哪个节点：" + hits.get(0));
        assertTrue(hits.get(0).contains("items、list"), "文案要把猜错的键都列出来：" + hits.get(0));
        assertTrue(hits.get(0).contains("models"), "文案要给出<b>声明的</b>数组键，否则模型只能再猜一次：" + hits.get(0));
        assertTrue(hits.get(0).contains("pickRows(state.model_list_out, 'models')"),
                "只有一个数组时该直接给出可用的改法（出路必须是通的，否则自修正回路空转烧钱）：" + hits.get(0));
        assertTrue(hits.get(0).contains("本节照样记成功"),
                "文案必须说清这条的危害是【静默】：只说「键不存在」的话，模型看不出为什么值得阻断：" + hits.get(0));
        assertTrue(r.getWarnings().stream().noneMatch(w -> w.contains(GUESSED_WRAPPER_KEY_HINT)),
                "这条必须是 error：warning <b>不会单独</b>逼出一轮自修正（planToWorkflow 的闸门是 !isPassed，"
                        + "而 isPassed 只看 errors），降到 warning 等于规划期什么都不会变：" + r.getWarnings());
    }

    /**
     * 🔴 形状四的判据必须与提示词那张表<b>同源</b>：不点名行不行，V26 与提示词里的 JS 给的答案必须一致
     *
     * <p>两处各写一份的后果（提示词说能不点名、校验器说必须点名，同一段脚本规划期被判合法、
     * 运行期才抛）见 {@link ToolResultShapes} 类注释。本用例把 V26 钉在
     * {@link ToolResultShapes#resolvableUnkeyed} 上，而提示词那份 JS 由
     * {@code WorkflowDslPlannerPromptTest#pickRowsUnkeyed_agreesWithToolResultShapesOnEveryDeclaredShape}
     * 钉在同一个方法上 —— 于是「JS ⟺ Java 判据 ⟺ V26」三点一线，改任何一处都会响。
     *
     * <p>形状清单刻意包含两个边界：{@code []}（<b>已知</b>不回数组，走另一句文案）与
     * {@code [items, list]}（两个都沾惯例名 ⟹ 惯例名不是「随便挑一个」的许可证）。
     */
    @Test
    void unkeyedPickRows_verdictAgreesWithToolResultShapesOnEveryShape() throws Exception {
        List<List<String>> shapes = List.of(
                List.of(),
                List.of("items"),
                List.of("models"),
                List.of("items", "danglingRegistrations"),
                List.of("systemColumns", "businessColumns", "indexes"),
                List.of("addedColumns", "modifiedColumns", "droppedColumns", "ddl", "warnings"),
                List.of("items", "list"),
                List.of("types", "选型指导"));
        for (List<String> keys : shapes) {
            stubToolShape("probe_tool", RiskLevel.READ_ONLY, keys);
            AgentValidateResult r = validate(toolThenScriptDsl("probe_tool", "probe_out",
                    PICKROWS_DEF + "return pickRows(state.probe_out);"));
            boolean expectBlocked = !ToolResultShapes.resolvableUnkeyed(keys);
            List<String> hits = r.getErrors().stream()
                    .filter(e -> e.contains(PICKROWS_UNKEYED_HINT) || e.contains(PICKROWS_NO_ARRAY_HINT))
                    .toList();
            assertEquals(expectBlocked, !hits.isEmpty(),
                    "声明=" + keys + "（不点名该" + (expectBlocked ? "拦" : "放") + "）：" + r.getErrors());
            assertEquals(expectBlocked ? 1 : 0, hits.size(),
                    "同一个根因只许说一遍，声明=" + keys + "：" + hits);
            assertTrue(hitsOf(r, PICKROWS_UNDEFINED_HINT).isEmpty(),
                    "本夹具抄了 pickRows，形状一不该响（响了说明 PICKROWS_DEF 与判据正则对不上）：" + r.getErrors());
        }
    }

    /**
     * 🔴 多个数组时文案<b>不许</b>替模型挑一个键
     *
     * <p>{@code datamodel_structure} 的头一个数组是 {@code systemColumns}（系统列）——
     * 「建议用第一个」会让模型拿 {@code creator} / {@code create_time} 去判业务列，
     * 静默判错还不报错，正是 {@link ToolResultShapes#MAIN_LIST_KEYS} 注释里点名不许放宽的那个反例。
     * 该点哪个键是<b>业务</b>判断，校验器给不出，只能把候选列全。
     */
    @Test
    void validate_multiArrayToolUnkeyed_messageDoesNotPrePickAKey() throws Exception {
        stubToolShape("datamodel_structure", RiskLevel.READ_ONLY,
                new DataModelStructureTool().resultArrayKeys());
        AgentValidateResult r = validate(toolThenScriptDsl("datamodel_structure", "structure_out",
                PICKROWS_DEF + "return pickRows(state.structure_out).length;"));

        List<String> hits = hitsOf(r, PICKROWS_UNKEYED_HINT);
        assertEquals(1, hits.size(), "该恰好一条形状二：" + r.getErrors());
        assertTrue(hits.get(0).contains("systemColumns") && hits.get(0).contains("businessColumns")
                        && hits.get(0).contains("indexes"),
                "候选要列全，模型才知道自己有几个选择：" + hits.get(0));
        assertFalse(hits.get(0).contains("'systemColumns'"),
                "不许把候选之一写成「建议这么改」——那是替模型做了业务判断：" + hits.get(0));
        assertTrue(hits.get(0).contains("别默认取第一个"), "要明说该按业务意图挑：" + hits.get(0));
    }

    /**
     * 形状二的另一支：工具<b>已知</b>不回数组（{@code explain_process} 那一档），对它用 pickRows 一定 throw
     *
     * <p>🔴 与「形状<b>不可知</b>」是两件事，故文案也不同：不可知的（自定义工具、
     * {@code data_interface_query}）V26 一律沉默，提示词教的是「先把键名打出来看一眼」；
     * 已知无数组的则要说清「别绕 pickRows，直接读字段」。
     */
    @Test
    void validate_pickRowsOnToolWithoutArrays_rejectedWithItsOwnMessage() throws Exception {
        stubToolShape("explain_process", RiskLevel.READ_ONLY, List.of());
        AgentValidateResult r = validate(toolThenScriptDsl("explain_process", "explain_out",
                PICKROWS_DEF + "return pickRows(state.explain_out);"));

        List<String> hits = hitsOf(r, PICKROWS_NO_ARRAY_HINT);
        assertEquals(1, hits.size(), "该恰好一条，且走「无数组」那句而不是「多个数组」那句：" + r.getErrors());
        assertTrue(hitsOf(r, PICKROWS_UNKEYED_HINT).isEmpty(), "两句话是互斥的两档，不该同时报：" + r.getErrors());
        assertTrue(hits.get(0).contains("直接写 state.explain_out.字段名"),
                "要给出出路，否则模型最省事的改法是把这一节删掉：" + hits.get(0));
    }

    /**
     * 形状一：调了 {@code pickRows} 却没在<b>本脚本</b>里定义它 ⟹ 确定的 {@code ReferenceError}
     *
     * <p>沙箱只注入了 {@code __stateJson} 一个绑定（{@code ScriptSandbox#eval}），
     * {@code ScriptNodeExecutor#execute} 也是把 {@code code} 原样传下去的 —— <b>没有任何前置定义</b>。
     * {@code pickRows} 是提示词教模型抄的一段函数体，不是内置函数。
     *
     * <p>⚠️ 本用例刻意用<b>形状不可知</b>的工具（{@code setUp} 里那个默认桩
     * {@code isDeclaredResultShapeKnown()} 是 Mockito 的 false）：形状一压根不需要知道形状，
     * 用一个形状未知的工具才能证明这一点，顺带也让本节只响一条。
     *
     * <p>落地前实测（2026-10-09，29 份活定义、38 个 script 节点）：11 个节点调了 pickRows，
     * <b>11 个都在同脚本里定义了它</b> ⟹ 本形状零命中，但<b>不是空测</b>。它是<b>回归闸</b>：
     * 挡的是「模型某天以为它是内置函数」那一次。
     */
    @Test
    void validate_scriptCallsPickRowsWithoutDefiningIt_rejected() throws Exception {
        AgentValidateResult r = validate(toolThenScriptDsl("data_interface_list", "iface_out",
                "return pickRows(state.iface_out).length;"));

        List<String> hits = hitsOf(r, PICKROWS_UNDEFINED_HINT);
        assertEquals(1, hits.size(), "该恰好一条形状一：" + r.getErrors());
        assertTrue(hits.get(0).contains("id=shape"), "文案要点名是哪个节点：" + hits.get(0));
        assertTrue(hits.get(0).contains("ReferenceError"), "要说清运行期的确定后果：" + hits.get(0));
        assertTrue(hits.get(0).contains("每个用到它的 script 节点都要各自抄一份"),
                "沙箱不跨节点共享定义 —— 不说这句，模型会以为上游抄过一次就够了：" + hits.get(0));
        assertTrue(hitsOf(r, PICKROWS_UNKEYED_HINT).isEmpty() && hitsOf(r, PICKROWS_NO_ARRAY_HINT).isEmpty(),
                "形状不可知 ⟹ 形状二必须沉默（否则就是拿猜的形状判了必抛）：" + r.getErrors());
    }

    /** 形状一的免报条件：本脚本里定义了 pickRows（函数声明与赋值两种写法都要认） */
    @Test
    void validate_scriptDefinesPickRows_notReported() throws Exception {
        AgentValidateResult declared = validate(toolThenScriptDsl("data_interface_list", "iface_out",
                PICKROWS_DEF + "return pickRows(state.iface_out);"));
        assertTrue(hitsOf(declared, PICKROWS_UNDEFINED_HINT).isEmpty(),
                "抄了函数体就是合法写法，报了就是假警报（自修正回路会去改一个没病的脚本，每轮一次真实计费）："
                        + declared.getErrors());

        AgentValidateResult assigned = validate(toolThenScriptDsl("data_interface_list", "iface_out",
                "var pickRows = function (o) { return o; }; return pickRows(state.iface_out);"));
        assertTrue(hitsOf(assigned, PICKROWS_UNDEFINED_HINT).isEmpty(),
                "赋值那种写法同理：" + assigned.getErrors());
    }

    /**
     * 形状三：把结果<b>对象</b>当数组遍历 ⟹ 确定的 {@code TypeError}
     *
     * <p>🔴 只在「确实回对象」（{@code declaresArrays} 为真）时判：声明为空有两种可能 ——
     * 「对象里没有数组」与「结果本身就是个数组」，后者 {@code .map} 是<b>合法</b>的，判了就是假警报。
     * 这正是形状不可知的工具一律沉默的同一条理由。
     */
    @Test
    void validate_scriptTreatsResultObjectAsArray_rejected() throws Exception {
        stubToolShape("datamodel_list", RiskLevel.READ_ONLY, new DataModelListTool().resultArrayKeys());
        AgentValidateResult r = validate(toolThenScriptDsl("datamodel_list", "model_list_out",
                "return state.model_list_out.map(function (row) { return row.id; });"));

        List<String> hits = hitsOf(r, ARRAY_METHOD_HINT);
        assertEquals(1, hits.size(), "该恰好一条形状三：" + r.getErrors());
        assertTrue(hits.get(0).contains(".map("), "文案要点名是哪个方法，否则模型不知道改哪一处：" + hits.get(0));
        assertTrue(hits.get(0).contains("TypeError"), "要说清运行期的确定后果：" + hits.get(0));
        assertTrue(hits.get(0).contains("pickRows(state.model_list_out, 'models')"),
                "出路必须是通的：" + hits.get(0));
    }

    /**
     * 形状四的三档免判，每档都是「本条给不出确定结论」而不是「懒得判」
     *
     * <ol>
     *   <li><b>键名真的在声明里</b>：{@code state.form_info.items} 对 {@code form_list} 是<b>正确</b>写法；</li>
     *   <li><b>{@code data} 不在猜测名单里</b>：它是 {@code form_data_get} 等工具<b>真实</b>的标量键，
     *       算进「猜错」就是一类稳定的假警报（见 {@link ToolResultShapes#LIST_WRAPPER_KEYS} 注释）；</li>
     *   <li><b>产出者是自定义工具</b>：{@code source=DATA_INTERFACE} 的结果由那个接口自己决定，
     *       与提示词 {@code customTools()} 传 {@code List.of(), false} 同一口径。</li>
     * </ol>
     */
    @Test
    void validate_wrapperKeyExemptions_notReported() throws Exception {
        stubToolShape("form_list", RiskLevel.READ_ONLY, List.of("items"));
        AgentValidateResult declaredKey = validate(toolThenScriptDsl("form_list", "form_info",
                "return state.form_info.items.length;"));
        assertTrue(hitsOf(declaredKey, GUESSED_WRAPPER_KEY_HINT).isEmpty(),
                "items 就是 form_list 声明的键，这是正确写法：" + declaredKey.getErrors());

        AgentValidateResult dataKey = validate(toolThenScriptDsl("form_list", "form_info",
                "return state.form_info.data;"));
        assertTrue(hitsOf(dataKey, GUESSED_WRAPPER_KEY_HINT).isEmpty(),
                "data 刻意不在猜测名单里（它是别的工具真实的标量键）：" + dataKey.getErrors());

        stubCustomTool("my_iface_tool", true, RiskLevel.READ_ONLY);
        AgentValidateResult custom = validate(toolThenScriptDsl("my_iface_tool", "iface_out",
                "return state.iface_out.items;"));
        assertTrue(hitsOf(custom, GUESSED_WRAPPER_KEY_HINT).isEmpty(),
                "自定义工具的形状不可知，V26 必须沉默：" + custom.getErrors());
    }

    /**
     * 🔴 免判：产出者<b>不在上游</b> / <b>不唯一</b>
     *
     * <p>前者运行期 {@code state.X} 是 null，真正的根因是<b>排序</b>；报「该点名 models」会把
     * 自修正回路引向一处没病的代码。⚠️ 这一档<b>没有</b>别的规则会说：{@code collectAllRoots}
     * 只认 {@code {{$.x}}} 占位符，而 {@code state.X} 是脚本里的裸属性访问。
     *
     * <p>后者是两条分支可能写不同工具的结果，取谁的形状来判都是赌 —— 而本条报的是 error，
     * 赌错就把一份好 DSL 判死。
     */
    @Test
    void validate_producerNotAncestorOrNotUnique_notReported() throws Exception {
        stubToolShape("datamodel_list", RiskLevel.READ_ONLY, new DataModelListTool().resultArrayKeys());

        AgentValidateResult reordered = validate(scriptBeforeToolDsl("datamodel_list", "model_list_out",
                "return state.model_list_out.items;"));
        assertTrue(hitsOf(reordered, GUESSED_WRAPPER_KEY_HINT).isEmpty(),
                "产出者排在下游 ⟹ 根因是排序，不是键名：" + reordered.getErrors());

        AgentValidateResult twoProducers = validate("""
                {"id":"wf",
                 "variables":[{"key":"input","type":"object"},{"key":"model_list_out","type":"object"},
                              {"key":"calc","type":"object"}],
                 "nodes":[
                   {"id":"fetch_a","type":"tool",
                    "config":{"toolId":"datamodel_list","args":{},"outputVar":"model_list_out"}},
                   {"id":"fetch_b","type":"tool",
                    "config":{"toolId":"datamodel_list","args":{},"outputVar":"model_list_out"}},
                   {"id":"shape","type":"script","config":{"code":"return state.model_list_out.items;","outputVar":"calc"}}
                 ],
                 "edges":[{"from":"START","to":"fetch_a"},{"from":"fetch_a","to":"fetch_b"},
                          {"from":"fetch_b","to":"shape"},{"from":"shape","to":"END"}]}
                """);
        assertTrue(hitsOf(twoProducers, GUESSED_WRAPPER_KEY_HINT).isEmpty(),
                "两个节点写同一个键 ⟹ 形状取谁的都不对，一律沉默：" + twoProducers.getErrors());
    }

    /**
     * 🔴 出路必须是通的：按声明点名之后整份 DSL 该<b>通过</b>体检
     *
     * <p>这条钉的是「模型照 V26 的文案改完不会还是红的」—— 改完还红，自修正回路就会在
     * {@code maxRounds} 里空转烧钱（与 ⑬ 节 {@code validate_toolArgsIsObject_passed}、
     * ⑭ 节 {@code validate_scriptReturnsAndToolNodeWrites_passed} 同一条理由）。
     */
    @Test
    void validate_scriptNamesKeyPerDeclaration_passed() throws Exception {
        stubToolShape("datamodel_list", RiskLevel.READ_ONLY, new DataModelListTool().resultArrayKeys());
        AgentValidateResult r = validate(toolThenScriptDsl("datamodel_list", "model_list_out",
                PICKROWS_DEF + "var rows = pickRows(state.model_list_out, 'models'); return rows.length;"));

        assertTrue(r.isPassed(), "照声明点名 + 抄了函数体，这是 V26 教的标准写法，不该被任何规则拦："
                + r.toFeedback());
    }

    // ==================== ⑱ 发布挂到哪个菜单（form_publish_bundle.parentMenuName / parentMenuId） ====================

    /**
     * 拼一个「先建表、再发布」的两节点 DSL；{@code publishArgsJson} 是发布节点的 args 对象
     *
     * <p>实测那条事故链是 5 个节点（widget_contract_get → datamodel_create → form_save_draft →
     * form_inspect → form_publish_bundle），这里只留<b>吃重的那半</b>：一个 IRREVERSIBLE 的建表节点
     * 排在发布节点<b>前面</b>。多出来的三格与本节规则无关，留着只会让「红的是哪一条」分不清。
     */
    private static String publishAfterDdlDsl(String publishArgsJson) {
        return """
                {"id":"wf",
                 "variables":[{"key":"input","type":"object"},{"key":"tbl","type":"object"},
                              {"key":"draft","type":"object"},{"key":"pub","type":"object"}],
                 "nodes":[
                   {"id":"make_table","type":"tool",
                    "config":{"toolId":"datamodel_create","outputVar":"tbl"}},
                   {"id":"do_publish","type":"tool",
                    "config":{"toolId":"form_publish_bundle","args":%s,"outputVar":"pub"}}],
                 "edges":[{"from":"START","to":"make_table"},{"from":"make_table","to":"do_publish"},
                          {"from":"do_publish","to":"END"}]}
                """.formatted(publishArgsJson);
    }

    /** 同上，但挂<b>两个</b>发布节点：只为钉「读目录清单失败时整趟只报一条、只读一次」 */
    private static String twoPublishAfterDdlDsl(String firstArgsJson, String secondArgsJson) {
        return """
                {"id":"wf",
                 "variables":[{"key":"input","type":"object"},{"key":"tbl","type":"object"},
                              {"key":"draft","type":"object"},{"key":"pub_a","type":"object"},
                              {"key":"pub_b","type":"object"}],
                 "nodes":[
                   {"id":"make_table","type":"tool",
                    "config":{"toolId":"datamodel_create","outputVar":"tbl"}},
                   {"id":"pub_one","type":"tool",
                    "config":{"toolId":"form_publish_bundle","args":%s,"outputVar":"pub_a"}},
                   {"id":"pub_two","type":"tool",
                    "config":{"toolId":"form_publish_bundle","args":%s,"outputVar":"pub_b"}}],
                 "edges":[{"from":"START","to":"make_table"},{"from":"make_table","to":"pub_one"},
                          {"from":"pub_one","to":"pub_two"},{"from":"pub_two","to":"END"}]}
                """.formatted(firstArgsJson, secondArgsJson);
    }

    /**
     * 🔴 复现的事故：目录名是模型凭「这是人事用的表单」编的，流程跑到<b>最后一步</b>才炸，
     * 而建表的 DDL 早已执行完、回滚不掉
     *
     * <p>实测（「帮我做一张员工入职登记表…」）产出的 DSL 里写着 {@code "parentMenuName":"人事管理"}，
     * 库里没有这个目录。运行期 {@code FormPublishParamResolver#resolveParentMenuId} 如实拒绝，
     * 但那时表<b>已经建了</b>、草稿<b>已经存了</b>、体检<b>已经过了</b>：用户拿到的是一张失败卡片，
     * 外加一张建出来却没发布的表单和一张回滚不掉的物理表（执行 id=193 就是这个形状）。
     *
     * <p>所以这条必须是 <b>error</b> 而不是 warning：警告<b>不会单独</b>逼出一轮自修正
     * （闸门是 {@code !isPassed()}，而 {@code isPassed()} 只看 {@code errors}；
     * {@code toFeedback()} 里那份 warnings 只在别的 error 已经逼出这一轮时才顺带捎上），
     * 写成 warning 等于什么都没修。
     */
    @Test
    void validate_publishParentMenuUnknownName_rejected() throws Exception {
        AgentValidateResult r = validate(publishAfterDdlDsl(
                "{\"id\":\"{{$.draft.id}}\",\"parentMenuName\":\"人事管理\"}"));

        assertFalse(r.isPassed(), "运行期才拒绝 = 建表的 DDL 已经回滚不掉，必须规划期拦：" + r.toFeedback());
        assertEquals(1, r.getErrors().size(), "只该报这一条，别的规则不该被这份 DSL 触发：" + r.toFeedback());
        String error = r.getErrors().get(0);
        assertTrue(error.contains("id=do_publish"), "要点名是哪个节点：" + error);
        assertTrue(error.contains("人事管理"), "要念出那个查无此名的目录，作者才知道说的是哪一格：" + error);
        assertTrue(error.contains("在线示例"), "要把可选目录念出来，否则模型只能再猜一轮：" + error);
        assertTrue(error.contains("回滚不掉"), "要说清留到运行期的后果：" + error);
    }

    /**
     * 肯定式对照（§5.3）：名字对得上就<b>一声不响</b>地过。
     *
     * <p>没有这一条，下面「占位符跳过」那条绿着也说明不了什么 ——
     * 把 {@code validatePublishParentMenu} 整个删掉它一样绿。
     */
    @Test
    void validate_publishParentMenuKnownName_passed() throws Exception {
        AgentValidateResult r = validate(publishAfterDdlDsl(
                "{\"id\":\"{{$.draft.id}}\",\"parentMenuName\":\"在线示例\"}"));

        assertTrue(r.isPassed(), r.toFeedback());
        assertTrue(r.getWarnings().isEmpty(), "名字对得上时不该留任何提示：" + r.getWarnings());
    }

    /** 与运行期同一个口径：运行期那边也是 {@code toString().trim()} 之后 {@code equals}，校验不该比它更严 */
    @Test
    void validate_publishParentMenuNameWithPadding_passed() throws Exception {
        AgentValidateResult r = validate(publishAfterDdlDsl(
                "{\"id\":\"{{$.draft.id}}\",\"parentMenuName\":\" 在线示例 \"}"));

        assertTrue(r.isPassed(), "首尾空格运行期会被 trim 掉，静态层不许比运行期严：" + r.toFeedback());
    }

    /**
     * 🔴 菜单名要到运行期才确定时，不许拿字面串去比目录清单
     *
     * <p>{@code parentMenuName} 是 {@code tool.args} 的<b>顶层字符串值</b>，运行期会被
     * {@code StateTemplateResolver} 替换掉 ⟹「运行期才算出菜单名」是个真能跑通的形状，
     * 与 {@code mainTable} 那一格同理（见 #80 那条）。这里若拦下来，报的还是一句
     * 「改成不传或改传 parentMenuId」—— <b>假阳性给出了一个不可能成立的修法</b>，比单纯报错更坏。
     */
    @Test
    void validate_publishParentMenuPlaceholder_skipped() throws Exception {
        AgentValidateResult r = validate(publishAfterDdlDsl(
                "{\"id\":\"{{$.draft.id}}\",\"parentMenuName\":\"{{$.draft.parentMenuName}}\"}"));

        assertTrue(r.isPassed(), "运行期才定的菜单名不等于「没有这个目录」：" + r.toFeedback());
        assertEquals(1, r.getWarnings().size(), r.toFeedback());
        String warning = r.getWarnings().get(0);
        assertTrue(warning.contains("{{$.draft.parentMenuName}}"), "要念出那个占位符：" + warning);
        assertTrue(warning.contains("跳过了"), "要如实认领这一项没校验：" + warning);
        // 肯定式对照：证明是「压根没查」而不是「查了、恰好查到」
        verify(menuApi, never()).getEnabledDirectoryList();
    }

    /**
     * 探测本身故障时不拦规划，且<b>整趟只报一条</b>
     *
     * <p>读启用目录清单是一次不接受逐项参数的全局批量读，故障必然是持续性的 ——
     * 逐节点重试只会把同一个故障 API 再打 N 遍、刷出 N 条一模一样的黄标，
     * 而「10 条相同的黄标」不是提醒，是在教读者「这个黄标不用看」（{@code warnProbeAborted} 的理由）。
     */
    @Test
    void validate_publishParentMenuProbeFailure_warnsOnceAndDoesNotBlock() throws Exception {
        when(menuApi.getEnabledDirectoryList()).thenThrow(new RuntimeException("menu 数据源超时"));

        AgentValidateResult r = validate(twoPublishAfterDdlDsl(
                "{\"parentMenuName\":\"人事管理\"}", "{\"parentMenuName\":\"财务管理\"}"));

        assertTrue(r.isPassed(), "查目录这个动作故障时不拦规划：" + r.toFeedback());
        // 🔴 这一条钉的是「探测故障绝不能变成 error」。真出那种错的话文案还会附送
        //    「两个参数都别传、挂到顶层」这个假修法 —— 照着改就把菜单挂错地方了
        assertEquals(0, r.getErrors().size(), "探测故障绝不能变成一条 error：" + r.toFeedback());
        assertEquals(1, r.getWarnings().size(), "一个根因只说一遍，不许每个节点刷一条：" + r.getWarnings());
        String warning = r.getWarnings().get(0);
        assertTrue(warning.contains("上级菜单校验"), warning);
        assertTrue(warning.contains("menu 数据源超时"), "要念出故障本身，否则作者不知道要恢复什么：" + warning);
        // 🔴 D-new8：warning 必须认领<b>真实</b>的跳过范围 —— 这里两个节点都没校验，
        //    只说「这一项没校验」会让作者以为另一个发布节点是被检查过的（那正是「假绿」）
        assertTrue(warning.contains("其余 form_publish_bundle 节点"), warning);
        // 停手点要念出来：作者据此知道「从这一格往后的都没查」，而不是自己去猜哪几个节点受影响
        assertTrue(warning.contains("id=pub_one"), warning);
        assertTrue(warning.contains("人事管理"), warning);
        assertFalse(warning.contains("财务管理"),
                "第二个节点是被「整趟停手」跳过的，不该被写成也报了一条：" + warning);
        verify(menuApi, times(1)).getEnabledDirectoryList();
    }

    /**
     * 🔴 出路必须是通的：库里一个启用目录都没有时，不能只甩一句「可选的目录名有：」（空的）
     *
     * <p>那是一份没有出路的错误 —— 模型照着改也改不出能过的 DSL，只会在 {@code maxRounds} 里空转烧钱。
     * 这一档要如实告诉它「两个参数都别传，挂到顶层」。
     */
    @Test
    void validate_publishNoEnabledDirectoryAtAll_tellsModelToOmit() throws Exception {
        when(menuApi.getEnabledDirectoryList()).thenReturn(List.of());

        AgentValidateResult r = validate(publishAfterDdlDsl("{\"parentMenuName\":\"在线示例\"}"));

        assertFalse(r.isPassed(), r.toFeedback());
        assertTrue(r.getErrors().get(0).contains("挂到顶层"),
                "空清单时必须给出一个真能走通的修法：" + r.getErrors().get(0));
    }

    /**
     * 🔴 钉住谓词选择的一半：「只有左半花括号、没有闭合」的畸形串<b>不算</b>占位符，必须照样拦
     *
     * <p>本类自己那个 {@code isPlaceholder} 是 {@code contains("{{")}，用它这里就会静默跳过 ——
     * 而运行期 {@code StateTemplateResolver.PLACEHOLDER} 要求花括号成对闭合，匹配不上就
     * <b>原样把字面串传下去</b>，{@code FormPublishParamResolver} 那边一定拒绝。
     * 静态说「无从判定」、运行期说「没有这个目录」就是两层口径分岔，而从界面上完全看不出来。
     */
    @Test
    void validate_publishParentMenuMalformedPlaceholder_stillRejected() throws Exception {
        AgentValidateResult r = validate(publishAfterDdlDsl(
                "{\"id\":\"{{$.draft.id}}\",\"parentMenuName\":\"{{人事管理\"}"));

        assertFalse(r.isPassed(), "花括号没闭合 ⟹ 运行期原样传字面串，静态层必须同口径：" + r.toFeedback());
        assertEquals(1, r.getErrors().size(), "只该报这一条：" + r.toFeedback());
        assertTrue(r.getErrors().get(0).contains("{{人事管理"), r.toFeedback());
    }

    /** {@code parentMenuId} 写的是字面量就必须是数字：运行期那个解析器对非数字直接抛，而那已经是最后一步 */
    @Test
    void validate_publishParentMenuIdNonNumeric_rejected() throws Exception {
        AgentValidateResult r = validate(publishAfterDdlDsl(
                "{\"id\":\"{{$.draft.id}}\",\"parentMenuId\":\"在线示例\"}"));

        assertFalse(r.isPassed(), r.toFeedback());
        assertEquals(1, r.getErrors().size(), r.toFeedback());
        assertTrue(r.getErrors().get(0).contains("必须是数字"), r.toFeedback());
        assertTrue(r.getErrors().get(0).contains("在线示例"), "要念出实际收到的值：" + r.getErrors().get(0));
    }

    /**
     * 免判三格：{@code parentMenuId} 是占位符 / 空串 / 压根没写
     *
     * <p>前两格运行期会先替换或先 trim 成「没传」，静态层拿字面串去判只会误报；
     * 第三格是<b>合法</b>的（不传 = 挂到顶层），而 {@code parentMenuName} 不传同样合法
     * （改传 parentMenuId）。{@code parentMenuName} 不是字符串时也沉默：运行期会 {@code toString()}，
     * 静态层不猜它转出来是什么 —— 宁可漏报也不误报。
     */
    @Test
    void validate_publishParentMenuIdPlaceholderOrAbsent_skipped() throws Exception {
        AgentValidateResult placeholder = validate(publishAfterDdlDsl(
                "{\"id\":\"{{$.draft.id}}\",\"parentMenuId\":\"{{$.draft.menuId}}\"}"));
        assertTrue(placeholder.isPassed(), "运行期才定的菜单编号不等于「不是数字」：" + placeholder.toFeedback());
        assertTrue(placeholder.getWarnings().isEmpty(),
                "这一格没有「查了库但查不了」的成分，不该刷黄标：" + placeholder.getWarnings());

        AgentValidateResult blank = validate(publishAfterDdlDsl(
                "{\"id\":\"{{$.draft.id}}\",\"parentMenuId\":\"\"}"));
        assertTrue(blank.isPassed(), "空串运行期就当没传：" + blank.toFeedback());
        assertTrue(blank.getWarnings().isEmpty(), blank.getWarnings().toString());

        AgentValidateResult absent = validate(publishAfterDdlDsl("{\"id\":\"{{$.draft.id}}\"}"));
        assertTrue(absent.isPassed(), "两个参数都不传 = 挂到顶层，合法：" + absent.toFeedback());
        assertTrue(absent.getWarnings().isEmpty(), absent.getWarnings().toString());

        AgentValidateResult notAString = validate(publishAfterDdlDsl(
                "{\"id\":\"{{$.draft.id}}\",\"parentMenuName\":123}"));
        assertTrue(notAString.isPassed(), "非字符串沉默（运行期会 toString，静态层不猜）：" + notAString.toFeedback());

        // 肯定式对照：这四格都不该去读目录清单（读了 = 拿字面串去比，正是要避免的那个误报）
        verify(menuApi, never()).getEnabledDirectoryList();
    }

    // ==================== ⑲ chat 触发器绑的 Agent 编码（V28） ====================

    /**
     * 一份最小的带触发器流程，只有 {@code trigger} 那一整块随用例变
     *
     * <p>🔴 节点部分刻意是一个<b>只读</b> tool（{@code data_interface_list}，setUp 里声明为 READ_ONLY）：
     * V28 只看 trigger 块，而一份带写工具又没有人工闸口的 DSL 会额外撞 V11，
     * 用例就得去数「这几条里哪一条才是本条报的」。把其它规则的射程清干净，
     * {@code assertEquals(1, errors.size())} 才是一句真断言而不是碰巧成立。
     */
    private static String triggerDsl(String triggerJson) {
        return """
                {"id":"wf","trigger":%s,
                 "variables":[{"key":"input","type":"object"},{"key":"out","type":"object"}],
                 "nodes":[{"id":"read","type":"tool",
                           "config":{"toolId":"data_interface_list","outputVar":"out"}}],
                 "edges":[{"from":"START","to":"read"},{"from":"read","to":"END"}]}
                """.formatted(triggerJson);
    }

    /** chat 触发器 + 一格 {@code agentId}，省得每条用例都手抄一遍 trigger 块的形状 */
    private static String chatWithAgentId(String agentIdJson) {
        return triggerDsl("{\"type\":\"trigger.chat\",\"config\":{\"agentId\":" + agentIdJson + "}}");
    }

    /**
     * 🔴 复现的事故形状：模型<b>自己编</b>了一个 Agent 编码，规划期全绿、点发布才被拒
     *
     * <p>而同一份 DSL 走 {@code auditAll} 盘点时一条都不报（那边只跑 {@code doValidateDsl}，
     * 不调发布路径上的 {@code validateChatBinding}）—— 这正是发布闸门 javadoc 声明
     * 「盘点里 failed 的那一行 = 发布被拒的那一行是<b>结构保证</b>」被打破的地方。
     */
    @Test
    void validate_chatAgentIdUnknown_rejected() throws Exception {
        when(agentDefMapper.existsByCode("agent_meeting_room")).thenReturn(false);

        AgentValidateResult r = validate(chatWithAgentId("\"agent_meeting_room\""));

        assertFalse(r.isPassed(), r.toFeedback());
        assertEquals(1, r.getErrors().size(), "只该报这一条：" + r.toFeedback());
        String msg = r.getErrors().get(0);
        assertTrue(msg.contains("agent_meeting_room"), "要念出实际填的编码：" + msg);
        assertTrue(msg.contains("删掉"), "必须给出那唯一一条能走通的修法：" + msg);
        // 🔴 刻意<b>不</b>列「有哪些 Agent 编码可用」，与 V27（上级菜单）那条相反：
        //    那边列目录名是唯一出路（表单总得挂在某个目录下），这边列了就是在教模型
        //    去绑一个真实存在的 Agent —— 而聊天入口是按 Agent 反查 chatflow 的，
        //    把一条不相干的流程绑上去等于抢走那个 Agent 的会话入口。
        //    钉法是「与 mapper 的交互仅此一次存在性查询」：日后谁要加候选清单，必然多一次查询，这条就红
        verify(agentDefMapper).existsByCode("agent_meeting_room");
        verifyNoMoreInteractions(agentDefMapper);
    }

    /** 肯定式对照（§5.3）：同一份夹具、编码真存在 ⟹ 一条都不该有 */
    @Test
    void validate_chatAgentIdKnown_passed() throws Exception {
        AgentValidateResult r = validate(chatWithAgentId("\"smart_service\""));

        assertTrue(r.isPassed(), r.toFeedback());
        assertTrue(r.getWarnings().isEmpty(), r.getWarnings().toString());
        verify(agentDefMapper).existsByCode("smart_service");
    }

    /**
     * 沉默条件：<b>留空合法</b>，四格都不该报、也不该去查库
     *
     * <p>{@code applyChat} 明写「留空 = 该 chat 流程不绑定任何 Agent（仅手动 run-stream / 规划草稿用）」，
     * 前端新建骨架 {@code WorkflowDefFormDialog.DEFAULT_DSL} 就没有这一格。
     * 对空白报 error 会把全部存量骨架与绝大多数规划产物一起打死 —— 而沉默条件写错一格的代价
     * 是每张画布刷一屏假警报，作者从此不看红字。
     */
    @Test
    void validate_chatAgentIdBlankOrAbsent_silent() throws Exception {
        for (String trigger : List.of(
                "{\"type\":\"trigger.chat\"}",
                "{\"type\":\"trigger.chat\",\"config\":{}}",
                "{\"type\":\"trigger.chat\",\"config\":{\"agentId\":\"\"}}",
                "{\"type\":\"trigger.chat\",\"config\":{\"agentId\":\"   \"}}")) {
            AgentValidateResult r = validate(triggerDsl(trigger));
            assertTrue(r.isPassed(), trigger + " ⟹ 留空 = 不绑定，合法：" + r.toFeedback());
            assertTrue(r.getWarnings().isEmpty(), trigger + "：" + r.getWarnings());
        }
        verify(agentDefMapper, never()).existsByCode(anyString());
    }

    /**
     * 🔴 占位符用<b>严格</b>谓词拦下，且不拿字面串去查库
     *
     * <p>判据是「运行期会不会把它换掉」，而这一格<b>压根不做变量替换</b>：{@code applyChat} 在落库时
     * 就把它原样搬进 {@code trigger_key}。所以 `{{unclosed` 这种畸形形状同样要拦 ——
     * 换成 {@code StateTemplateResolver#containsPlaceholder}（要求花括号成对闭合）就会把它放过去，
     * 而它落地后是一个永远反查不命中的字面串。
     */
    @Test
    void validate_chatAgentIdPlaceholder_rejected() throws Exception {
        AgentValidateResult strict = validate(chatWithAgentId("\"{{$.agentCode}}\""));
        assertFalse(strict.isPassed(), strict.toFeedback());
        assertEquals(1, strict.getErrors().size(), strict.toFeedback());
        assertTrue(strict.getErrors().get(0).contains("不做变量替换"), strict.getErrors().get(0));

        AgentValidateResult malformed = validate(chatWithAgentId("\"{{agentCode\""));
        assertFalse(malformed.isPassed(), "严格谓词必须认得畸形形状：" + malformed.toFeedback());

        verify(agentDefMapper, never()).existsByCode(anyString());
    }

    /**
     * 沉默条件：<b>只对 chat 生效</b>
     *
     * <p>判据是 {@code TRIGGER_TYPE_CHAT.equals(triggerTypeOf(def))}，不是「config 里有 agentId 就判」。
     * 别的触发器那一格是死配置、运行期没人读，报 error 就是把一个没病的字段点名给模型改 ——
     * 而它照做也修不好任何东西（{@link #validate} 的反馈只在 {@code !isPassed()} 时回灌）。
     */
    @Test
    void validate_nonChatTriggerWithAgentId_notJudged() throws Exception {
        AgentValidateResult r = validate(triggerDsl(
                "{\"type\":\"trigger.schedule\",\"config\":{\"cron\":\"0 0 8 * * ?\",\"agentId\":\"ghost\"}}"));

        assertTrue(r.isPassed(), r.toFeedback());
        verify(agentDefMapper, never()).existsByCode(anyString());
    }

    /**
     * 探测本身故障 ⟹ 只出黄标、不阻断（§6.1：探测得出「不合法」才是 error，探测失败不是）
     *
     * <p>⚠️ 这里<b>不</b>走 {@code warnProbeAborted}：那个出口是给「一次全局批量读、不接受逐项参数、
     * 故障必然持续」的三处用的。本条的探测入参就是它自己那一个编码，作用域跟着入参走 ——
     * 故用 V10（{@code validateSubWorkflowRefs}）那套单项跳过的写法。
     */
    @Test
    void validate_chatAgentIdProbeFailure_warnsNotBlocks() throws Exception {
        when(agentDefMapper.existsByCode("smart_service"))
                .thenThrow(new RuntimeException("connection refused"));

        AgentValidateResult r = validate(chatWithAgentId("\"smart_service\""));

        assertTrue(r.isPassed(), "查不动不等于查出不存在：" + r.toFeedback());
        assertEquals(1, r.getWarnings().size(), r.getWarnings().toString());
        assertTrue(r.getWarnings().get(0).contains("smart_service"),
                "要念出漏判的是哪个编码：" + r.getWarnings().get(0));
        assertTrue(r.getWarnings().get(0).contains("connection refused"), r.getWarnings().get(0));
    }

    /**
     * 🔴 trim 口径必须与提取器逐字一致
     *
     * <p>{@code WorkflowTriggerExtractor#str} 是 {@code String.valueOf(v).trim()} 之后才写进
     * {@code trigger_key} 的，所以本条也必须拿 trim 过的值去查。两边不一致的表现是
     * 「体检说这个编码不存在、而真发布时聊天入口反查得到」（或反过来），
     * 而这两句话分别出现在两个界面上，谁也看不见对方。
     */
    @Test
    void validate_chatAgentIdPadded_queriedTrimmed() throws Exception {
        AgentValidateResult r = validate(chatWithAgentId("\"  smart_service  \""));

        assertTrue(r.isPassed(), r.toFeedback());
        verify(agentDefMapper).existsByCode("smart_service");
    }

    // ==================== ⑳ form_save_draft 冻在 DSL 里的选项来源（V29） ====================

    /**
     * 本节钉的是「AI 建出来的下拉是空的，而一路没有任何一处会主动说」。
     *
     * <p>🔴 与 {@code FormSchemaValidatorTest} 那节<b>不重复</b>：那边考的是判据本身
     * （字典在不在、静态选项什么形状、三种来源只能用一种），本节考的是<b>规划期借到了同一份判据</b> ——
     * 借的动作是「用运行期那一个 {@code applyOptionSource} 把字段描述摊成 rule，再过运行期那一个
     * {@code validateOptionSource}」，所以本节大半在钉<b>沉默条件</b>与<b>探测作用域</b>：
     * 判据抄了第二份、或探测故障时把整个规划打灭，都会在这里红，而在那边全绿。
     *
     * <p>另一个只有本节钉得住的事实：这份参数是<b>冻进 DSL</b> 的，运行期不会再问模型 ——
     * 故每条报错都必须点名是哪个节点、并说清「必须在规划期改对」。
     */
    /** 桩一个字典：给了几个状态就有几条数据，一条都不给 = 「字典在、但下拉是空的」 */
    private void stubDict(String dictType, CommonStatusEnum... statuses) {
        DictTypeRespDTO type = new DictTypeRespDTO();
        type.setType(dictType);
        when(dictDataApi.getDictType(dictType)).thenReturn(type);
        List<DictDataRespDTO> data = new ArrayList<>();
        for (CommonStatusEnum status : statuses) {
            DictDataRespDTO item = new DictDataRespDTO();
            item.setDictType(dictType);
            item.setLabel("标签" + status.getStatus());
            item.setValue("value" + status.getStatus());
            item.setStatus(status.getStatus());
            data.add(item);
        }
        when(dictDataApi.getDictDataList(dictType)).thenReturn(data);
    }

    /**
     * 一条绑到 {@code status} 列的 form_save_draft DSL；顺带把那张表桩成已登记
     * （V6 会拿 fields[].column 去比对列清单，不桩的话本节每条都会先多报一个无关的「列不存在」）
     */
    private String optionSourceDsl(String fieldJson) throws Exception {
        stubRegisteredTable("biz_device", "id", "device_name", "status", "tenant_id");
        return saveDraftDsl("biz_device", fieldJson);
    }

    private static String selectField(String extraJson) {
        return "[{\"field\":\"处理状态\",\"column\":\"status\",\"widget\":\"select\",\"title\":\"处理状态\""
                + extraJson + "}]";
    }

    @Test
    void validate_saveDraftOptionWidgetWithoutSource_rejected() throws Exception {
        AgentValidateResult r = validate(optionSourceDsl(selectField("")));

        assertFalse(r.isPassed(), "空下拉在存草稿与发布时都不报错，规划期是唯一能拦的地方：" + r.toFeedback());
        assertEquals(1, r.getErrors().size(), "一个根因只说一遍：" + r.toFeedback());
        String feedback = r.toFeedback();
        assertTrue(feedback.contains("save_draft"), "本类每条报错都要点名是哪个节点：" + feedback);
        assertTrue(feedback.contains("处理状态"), feedback);
        assertTrue(feedback.contains("没有任何选项来源"), feedback);
        assertTrue(feedback.contains("options"), "要给出照做得到的出路，否则模型只会再编一个字典名：" + feedback);
        assertTrue(feedback.contains("冻进 DSL"), "要说清为什么必须现在改：" + feedback);
    }

    /**
     * 🔴 本节的由来：模型<b>凭记忆编</b>一个 dictType。实测 34 份定义里 7 个 dictType、6 个是编的，
     * 其中 2 个挂在已发布的定义上
     */
    @Test
    void validate_saveDraftInventedDictType_rejected() throws Exception {
        // 不桩 dictDataApi：未桩方法返回 null = 「任何字典都不存在」，正是编出来的那个名字的处境
        AgentValidateResult r = validate(optionSourceDsl(selectField(",\"dictType\":\"handle_status\"")));

        assertFalse(r.isPassed(), r.toFeedback());
        assertEquals(1, r.getErrors().size(), r.toFeedback());
        String feedback = r.toFeedback();
        assertTrue(feedback.contains("handle_status"), "要念出那个编出来的字典名，模型才知道改哪一格：" + feedback);
        assertTrue(feedback.contains("不存在"), feedback);
        assertTrue(feedback.contains("options"), "出路只有两条，其中这一条它照做得到：" + feedback);
        assertTrue(feedback.contains("没有建字典的工具"), feedback);
    }

    @Test
    void validate_saveDraftUsableDict_passes() throws Exception {
        stubDict("common_status", CommonStatusEnum.ENABLE, CommonStatusEnum.ENABLE);

        AgentValidateResult r = validate(optionSourceDsl(selectField(",\"dictType\":\"common_status\"")));

        assertTrue(r.isPassed(), r.toFeedback());
        assertTrue(r.getWarnings().isEmpty(), r.getWarnings().toString());
    }

    /**
     * 字典在、但一条启用数据都没有 ⟹ <b>只黄不红</b>
     *
     * <p>分档理由见 {@code FormSchemaValidator#checkDictTypeHasEnabledData}：管理员随后补上就好了，
     * infra 的发布关也不拦它。在这里拦就会比设计器更严，出现「AI 说建不了、手工配同一个字典却能发布」
     */
    @Test
    void validate_saveDraftDictWithoutEnabledData_warnsNotBlocks() throws Exception {
        stubDict("common_status", CommonStatusEnum.DISABLE);

        AgentValidateResult r = validate(optionSourceDsl(selectField(",\"dictType\":\"common_status\"")));

        assertTrue(r.isPassed(), r.toFeedback());
        assertEquals(1, r.getWarnings().size(), r.getWarnings().toString());
        String warn = r.getWarnings().get(0);
        assertTrue(warn.contains("common_status"), warn);
        assertTrue(warn.contains("一条「开启」状态的字典数据都没有"), warn);
        assertTrue(warn.contains("save_draft"), "黄标同样要能定位到节点：" + warn);
    }

    /** 固定选项那条出路必须真的走得通，而且<b>不去碰字典</b> */
    @Test
    void validate_saveDraftStaticOptions_passesWithoutTouchingDict() throws Exception {
        AgentValidateResult r = validate(optionSourceDsl(selectField(
                ",\"options\":[{\"label\":\"待处理\",\"value\":\"pending\"},{\"label\":\"已处理\",\"value\":\"done\"}]")));

        assertTrue(r.isPassed(), r.toFeedback());
        assertTrue(r.getWarnings().isEmpty(), r.getWarnings().toString());
        verify(dictDataApi, never()).getDictType(anyString());
    }

    /** 静态选项写成裸字符串数组 ⟹ 前端读不到 label/value，下拉里是一排空白项，同样不报错 */
    @Test
    void validate_saveDraftMalformedStaticOptions_rejected() throws Exception {
        AgentValidateResult r = validate(optionSourceDsl(selectField(",\"options\":[\"待处理\"]")));

        assertFalse(r.isPassed(), r.toFeedback());
        assertTrue(r.toFeedback().contains("label"), "要说清缺的是哪一格：" + r.toFeedback());
    }

    /**
     * 描述符里两个来源都写 ⟹ <b>字典赢</b>，静态那份被 {@code applyOptionSource} 直接丢掉
     *
     * <p>🔴 钉的是「丢得确定、且丢完剩下的那个来源<b>照样会被判</b>」：字典是编的 ⟹ 报错点落在字典那条上，
     * 不会因为「还有一份静态选项」就被当成有来源而放行 —— 那才是真正的静默失效
     * （表单建好、发布通过、下拉里一个选项都没有）。
     *
     * <p>⚠️ 所以这里<b>断言不出现</b>「只能用一种」那句：它是 rule 层面的判据
     * （{@code FormSchemaValidatorTest#testBothSourcesIsRejected} 钉它，服务的是库里存着的既有 rule），
     * 而描述符层面的「两个都写了」在摊成 rule 之前就已经被消解掉了，那句在这条路上到不了。
     * 优先级本身（接口 → 字典 → 静态）与前端 {@code detectOptionSourceType} 同源，本节不重新发明。
     */
    @Test
    void validate_saveDraftBothOptionSources_dictWinsAndIsStillJudged() throws Exception {
        AgentValidateResult r = validate(optionSourceDsl(selectField(
                ",\"dictType\":\"handle_status\",\"options\":[{\"label\":\"待处理\",\"value\":\"pending\"}]")));

        assertFalse(r.isPassed(), "静态那份被丢掉之后，剩下的字典是编的 ⟹ 必须拦：" + r.toFeedback());
        assertEquals(1, r.getErrors().size(), r.toFeedback());
        assertTrue(r.toFeedback().contains("handle_status"), r.toFeedback());
        assertFalse(r.toFeedback().contains("只能用一种"), r.toFeedback());
    }

    /**
     * 选项来源写在<b>非</b>选项类控件上 ⟹ 当场拒
     *
     * <p>这一格在界面上一个痕迹都不留：这两个键不会进 rule，唯一的去处是查询项，
     * 而查询项那边也认不出来（render 页要 {@code widget=SELECT} 且带 dictType 才渲染成下拉）
     */
    @Test
    void validate_saveDraftOptionSourceOnNonOptionWidget_rejected() throws Exception {
        AgentValidateResult r = validate(optionSourceDsl(
                "[{\"field\":\"设备名称\",\"column\":\"device_name\",\"widget\":\"input\","
                        + "\"title\":\"设备名称\",\"dictType\":\"common_status\"}]"));

        assertFalse(r.isPassed(), r.toFeedback());
        assertEquals(1, r.getErrors().size(), r.toFeedback());
        String feedback = r.toFeedback();
        assertTrue(feedback.contains("不是选项类控件"), feedback);
        assertTrue(feedback.contains(FormFieldFactory.optionWidgetCatalog()),
                "名单必须是从常量渲染进来的那一份，不能是文案里手敲的第二份：" + feedback);
        verify(dictDataApi, never()).getDictType(anyString());
    }

    /**
     * 🔴 沉默条件：控件名本身是编的 ⟹ 只有白名单那条开口，本条闭嘴
     *
     * <p>叠一句「mySelect 不是选项类控件」只是把同一个根因说两遍，还会把模型的注意力
     * 从真正要改的那一格（控件名）引到字典名上 —— 而它照后者改也修不好任何东西
     */
    @Test
    void validate_saveDraftUnknownWidgetWithSource_speaksOnce() throws Exception {
        AgentValidateResult r = validate(optionSourceDsl(
                "[{\"field\":\"处理状态\",\"column\":\"status\",\"widget\":\"mySelect\","
                        + "\"title\":\"处理状态\",\"dictType\":\"handle_status\"}]"));

        assertFalse(r.isPassed(), r.toFeedback());
        assertEquals(1, r.getErrors().size(), "同一个根因只许说一遍：" + r.toFeedback());
        assertTrue(r.getErrors().get(0).contains("mySelect"), r.toFeedback());
        assertTrue(r.getErrors().get(0).contains("不存在"), r.toFeedback());
        assertFalse(r.toFeedback().contains("handle_status"),
                "V29 该闭嘴：模型要改的是控件名，不是字典名：" + r.toFeedback());
    }

    /**
     * 别名（{@code dropdown}→{@code select}）照样被判据认出来
     *
     * <p>不归一的话这一条会退化成上面那条「不是选项类控件」，
     * 于是「其实是选项控件」的写法在最需要它的地方判反
     */
    @Test
    void validate_saveDraftAliasWidgetJudgedAsOptionWidget() throws Exception {
        AgentValidateResult r = validate(optionSourceDsl(
                "[{\"field\":\"处理状态\",\"column\":\"status\",\"widget\":\"dropdown\",\"title\":\"处理状态\"}]"));

        assertFalse(r.isPassed(), r.toFeedback());
        assertEquals(1, r.getErrors().size(), r.toFeedback());
        assertTrue(r.getErrors().get(0).contains("没有任何选项来源"), r.toFeedback());
        assertTrue(r.getErrors().get(0).contains("select"), "点名要用归一后的规范名：" + r.toFeedback());
    }

    /**
     * 查字典本身故障 ⟹ <b>不阻断</b>规划，而且这一路<b>一条黄标都不出</b>
     *
     * <p>fail-soft 是在 {@code FormSchemaValidator#validateOptionSource} 内部做的（log.warn 后跳过），
     * 与 {@code loadValidationPatterns} 同一条纪律：一次基础设施抖动不该挡掉一整条业务流程。
     * 兜底仍在 —— infra 的发布关会拒编出来的字典。
     * ⚠️ 所以这里断言的是「静默」而不是「有一句黄标」：把 fail-soft 改成往外抛，
     * 表现就是整趟体检按下面的探测中止那条走，两档不许混
     */
    @Test
    void validate_saveDraftDictProbeFailure_doesNotBlockPlanning() throws Exception {
        when(dictDataApi.getDictType("common_status")).thenThrow(new RuntimeException("字典服务不可用"));

        AgentValidateResult r = validate(optionSourceDsl(selectField(",\"dictType\":\"common_status\"")));

        assertTrue(r.isPassed(), "查不动不等于查出不存在：" + r.toFeedback());
        assertTrue(r.getWarnings().isEmpty(), r.getWarnings().toString());
    }

    /**
     * 🔴 本节唯一一条「V29 自己撞上契约故障」的用例：一句黄标、认领真实范围、<b>不阻断</b>
     *
     * <p>正常情况下走不到这里 —— {@code validateSaveDraftWidgets} 排在前面、读的是同一份契约，
     * 它中止时返回 {@code false}，本条整趟跳过（那一半由
     * {@code validate_widgetContractProbeFailure_probesOnceAndWarnsOnce} 的 {@code times(1)} 钉住）。
     * 本条造的是「前面读得好好的、轮到 V29 才抽风」，为的是钉住两件事：
     * 探测<b>包在 try 里</b>（裸在外面的话异常会穿出 {@code validate()}，体检接口 500），
     * 以及作用域是<b>整趟</b>而不是逐字段刷 N 条同因黄标。
     */
    @Test
    void validate_optionSourceProbeFailure_warnsWithWholeScope() throws Exception {
        stubRegisteredTable("biz_device", "id", "device_name", "device_code", "tenant_id");
        // 前 4 次给清单（控件白名单那条：TWO_SAVE_DRAFT_NODES 的 4 个字段各一次，
        // 这个倍数由 validate_widgetContractHealthy_noProbeWarning 的 times(8) 钉住），第 5 次起抽风 = V29
        when(formDesignApi.getWidgetContracts())
                .thenReturn(contractWidgets(), contractWidgets(), contractWidgets(), contractWidgets())
                .thenThrow(new RuntimeException("infra 抽风"));

        AgentValidateResult r = validate(TWO_SAVE_DRAFT_NODES);

        assertTrue(r.isPassed(), "探测失败不是不合法（方案 §6.1）：" + r.toFeedback());
        assertEquals(1, r.getWarnings().size(), "持续性故障刷 N 条一样的黄标 = 教读者别看了：" + r.toFeedback());
        String warn = r.getWarnings().get(0);
        assertTrue(warn.contains("选项来源校验"), "要说是哪一条校验给不出结论：" + warn);
        assertTrue(warn.contains("infra 抽风"), warn);
        assertTrue(warn.contains("已校验 0 处"), warn);
        assertTrue(warn.contains("全部未校验"), "要明说这是整趟、不是某一个字段：" + warn);
        assertTrue(warn.contains("first") && warn.contains("设备名称"), warn);
        verify(formDesignApi, times(5)).getWidgetContracts();
    }

    // -------------------- 控件白名单的第三道判定：在白名单里 ≠ AI 建得出来 --------------------

    /**
     * 一个<b>带配置项</b>的可用控件（判据只读键名与 required 这两格）
     */
    private static FormDesignApi.WidgetSimpleRespDTO widgetWithProps(String type,
                                                                     FormDesignApi.WidgetPropDTO... props) {
        FormDesignApi.WidgetSimpleRespDTO dto = widget(type);
        dto.setProps(List.of(props));
        return dto;
    }

    private static FormDesignApi.WidgetPropDTO prop(String name, boolean required) {
        FormDesignApi.WidgetPropDTO dto = new FormDesignApi.WidgetPropDTO();
        dto.setName(name);
        dto.setRequired(required);
        return dto;
    }

    /**
     * 把契约桩换成带配置项的一份
     *
     * <p>🔴 不改 {@link #contractWidgets()} 而是在用例里另桩：那一份是<b>整节</b>共用的，
     * 往里加带 required 配置的控件会让每条老用例都多顶一条与本节无关的 error
     * （「测 A 却因为 B 变红」）。这里桩的三种形状与 {@code FormSchemaValidatorTest} 那份一致：
     * 必填项、选项来源落在 props、以及不需要任何配置的对照。
     */
    private void stubContractWithProps() {
        List<FormDesignApi.WidgetSimpleRespDTO> widgets = new ArrayList<>(contractWidgets());
        widgets.add(widgetWithProps("DictSelect", prop("dictType", true)));
        widgets.add(widgetWithProps("cascader", prop("options", false)));
        widgets.add(widgetWithProps("inputNumber", prop("precision", false)));
        when(formDesignApi.getWidgetContracts()).thenReturn(widgets);
    }

    /**
     * 🔴 那道「控件白名单」比它的名字宽。复现的形状（实测 55 个可用控件里有 18 个是这种）：
     * 用户说「客户等级用字典选」，模型选了契约里确实存在的 {@code DictSelect} ——
     * 而它要的 {@code props.dictType} 在字段描述（fields[]）里<b>没有格子能放</b>。
     *
     * <p>这一格连运行期都不报：草稿照存、发布照过，用户拿到一个点不开的下拉。
     * 所以只能在规划期拦 —— args 是冻进 DSL 的，运行期不会再问模型。
     */
    @Test
    void validate_saveDraftUnauthorableWidget_rejected() throws Exception {
        stubContractWithProps();
        stubRegisteredTable("biz_device", "id", "customer_level", "tenant_id");

        AgentValidateResult r = validate(saveDraftDsl("biz_device",
                "[{\"field\":\"customer_level\",\"widget\":\"DictSelect\",\"title\":\"客户等级\"}]"));

        assertFalse(r.isPassed(), r.toFeedback());
        assertEquals(1, r.getErrors().size(), r.toFeedback());
        String error = r.getErrors().get(0);
        assertTrue(error.contains("save_draft"), "要点名是哪个节点：" + error);
        assertTrue(error.contains("customer_level"), "要点名是哪一格：" + error);
        assertTrue(error.contains("DictSelect") && error.contains("dictType"), error);
        assertTrue(error.contains(FormFieldFactory.UNAUTHORABLE_MARK),
                "要点名提示词清单里那个标记，否则模型无从对照：" + error);
        assertFalse(error.contains("不存在") || error.contains("已停用"),
                "这是第三种根因，不能借用前两种的说法（那会把模型推去改控件名的拼写）：" + error);
    }

    /**
     * 🔴 沉默条件跟着 widen 一起走：{@code cascader} 配了 {@code dictType} 时，
     *    只有白名单那条开口，V29 闭嘴。
     *
     * <p>「cascader 不是选项类控件」与「cascader 的选项 AI 写不进去」是<b>同一个根因</b>的两种说法，
     * 叠两句只会把模型的注意力从真正要改的那一格上引开。
     */
    @Test
    void validate_saveDraftUnauthorableOptionWidget_speaksOnce() throws Exception {
        stubContractWithProps();
        stubRegisteredTable("biz_device", "id", "region", "tenant_id");

        AgentValidateResult r = validate(saveDraftDsl("biz_device",
                "[{\"field\":\"region\",\"widget\":\"cascader\",\"title\":\"区域\","
                        + "\"dictType\":\"common_status\"}]"));

        assertFalse(r.isPassed(), r.toFeedback());
        assertEquals(1, r.getErrors().size(), "同一个根因只许说一遍：" + r.toFeedback());
        String error = r.getErrors().get(0);
        assertTrue(error.contains("cascader") && error.contains("选项来源"), error);
        assertFalse(error.contains("不是选项类控件"), "V29 该闭嘴：" + r.toFeedback());
    }

    /** 反向对照：配置项都不是必填的控件照旧放行 —— 这道闸不许见 props 就拦 */
    @Test
    void validate_saveDraftWidgetWithOptionalPropsOnly_passed() throws Exception {
        stubContractWithProps();
        stubRegisteredTable("biz_device", "id", "count", "tenant_id");

        AgentValidateResult r = validate(saveDraftDsl("biz_device",
                "[{\"field\":\"count\",\"widget\":\"inputNumber\",\"title\":\"数量\"}]"));

        assertTrue(r.isPassed(), r.toFeedback());
        assertTrue(r.getWarnings().isEmpty(), r.getWarnings().toString());
    }

    /**
     * 🔴 这道闸<b>一次额外探测都不花</b>：它挂在 {@code checkWidgetType} 里，
     *    而白名单那条本来就要为每个字段读一次契约。
     *
     * <p>钉住这一点是因为「多读一次契约」在本类里是有代价的：那个读没有缓存
     * （infra 侧每次都重建整份契约 DTO），而且每多一处读就多一个「探测中止」的点
     * （见 {@code warnProbeAborted} 那条纪律）。
     *
     * <p>⚠️ 是 <b>2</b> 不是 1：每个字段本来就过<b>两条</b>读契约的判据 ——
     * 「控件白名单」与「选项来源（V29）」，倍数由 {@code validate_optionSourceProbeFailure_warnsWithWholeScope}
     * 那节的 {@code times(8)}/{@code times(5)} 钉住。第三道判定折叠进前者，所以仍是 2、不是 3。
     */
    @Test
    void validate_saveDraftUnauthorableWidget_doesNotProbeContractTwice() throws Exception {
        stubContractWithProps();
        stubRegisteredTable("biz_device", "id", "customer_level", "tenant_id");

        validate(saveDraftDsl("biz_device",
                "[{\"field\":\"customer_level\",\"widget\":\"DictSelect\",\"title\":\"客户等级\"}]"));

        verify(formDesignApi, times(2)).getWidgetContracts();
    }

    // ==================== ㉑ 审批流绑的在线表单是本流程自己建的（V31） ====================

    /**
     * 🔴 复现的就是这一份形状（实测「设备报修登记」）：规划期 2 轮收敛、warnings 为空，
     *    点发布才拒，而拒绝的理由被 infra 说成了「表单不存在，<b>可能已被删除</b>」——
     *    没有人删过它，建它的那个节点就在这份 DSL 里，只是要到运行期才跑。
     *
     * <p>所以断言分两半：<b>拦得住</b>（规划期就 error，不等发布），
     * 以及<b>说得对</b>（点名两个节点、说清「此刻的只读快照」这个真原因、
     * 把那句假话点出来、并给出这条路上照做得到的两条出路）。
     * 后半段不是文案洁癖：模型拿到这句话要能改，用户拿到这句话要能懂。
     */
    @Test
    void validate_processDeployBindsFormCreatedByThisFlow_rejected() throws Exception {
        stubRegisteredTable("biz_device", "device_name");

        AgentValidateResult r = validate(draftThenDeployDsl("device_repair_register",
                "\"key\":\"repair_approve\",\"name\":\"报修审批\","
                        + "\"formDesignCode\":\"device_repair_register\""));

        assertFalse(r.isPassed(), "绑本流程自己建的表单永远发布不了，必须规划期就拦：" + r.toFeedback());
        assertEquals(1, r.getErrors().size(), "只该报这一件事：" + r.toFeedback());
        String error = r.getErrors().get(0);
        // 点名两个节点：作者要能看出是哪一格绑了哪一个。
        // ⚠️ 形状跟着 displayId 走（渲染成 id=xxx，id 为空时退化成「第 N 个」），
        //    不是裸 id —— 断言写死裸 id 会在下一次有人换成 displayId 时假绿
        assertTrue(error.contains("节点(id=deploy)"), error);
        assertTrue(error.contains("id=save_draft"), error);
        assertTrue(error.contains("device_repair_register"), error);
        // 真原因，以及那句把原因说反的话
        assertTrue(error.contains("此刻"), "要说清发布体检读的是平台此刻的状态：" + error);
        assertTrue(error.contains("可能已被删除"), "要把那句假话点出来，否则用户会去找一次没发生的删除：" + error);
        // 两条出路都得是这条路上照做得到的
        assertTrue(error.contains("hitl.confirm"), error);
        assertTrue(error.contains("hitl.bpm_approval"), error);
        assertTrue(error.contains("form_publish_bundle 排到它前面也没用"),
                "要堵住「把发布那一步排到前面」这个看起来对、其实救不了的改法：" + error);
    }

    /**
     * 🔴 反面：绑一张<b>既有</b>表单是完全合法的，本条不许开口。
     *
     * <p>它此刻在不在库里、发布没发布、主表两列齐不齐，全是发布体检
     * （{@code ChangeManifestDryRunner#checkMainForm}）的活儿 —— 那是<b>要读库</b>的判定，
     * 而本条一个字都不读库。两边重叠就会同一个根因报两遍。
     */
    @Test
    void validate_processDeployBindsExistingForm_notV31sBusiness() throws Exception {
        stubRegisteredTable("biz_device", "device_name");

        AgentValidateResult r = validate(draftThenDeployDsl("device_repair_register",
                "\"key\":\"repair_approve\",\"name\":\"报修审批\",\"formDesignCode\":\"oa_leave_form\""));

        assertTrue(r.isPassed(), "绑既有表单不归本条管：" + r.toFeedback());
    }

    /**
     * 🔴 钉住规则头「刻意不判」的那一档：编码写成占位符时本条<b>沉默</b>。
     *
     * <p>不是漏判 —— 那一档在「中间真有一步把草稿发布掉、且建表时带上了那两列」时是能跑通的，
     * 静态判它要同时证成两件事，误判的代价是一条合法流程被拒。
     * 把它钉成「沉默」是为了让日后的改动<b>必须显式推翻这个取舍</b>，而不是顺手把判据放宽到占位符。
     * 模型也不会被留在那一档踩坑：{@code ProcessCreateAndDeployTool} 原先那句
     * 「formDesignCode…来自 form_save_draft 的结果」正是把它往这儿引的话，已随 V31 一并改掉
     * （由 {@code ProcessCreateAndDeployToolTest} 钉住）。
     *
     * <p>⚠️ 这份沉默是靠<b>「按编码相等来匹配」</b>实现的，绑定侧并没有一道专门的占位符判断 ——
     * 占位符匹配不上创建侧任何字面量编码，自然就落空了。见
     * {@code WorkflowDslValidator#validateProcessDeployFormSource} 绑定侧那段注释。
     */
    @Test
    void validate_processDeployBindsPlaceholderFromSaveDraft_notJudgedHere() throws Exception {
        stubRegisteredTable("biz_device", "device_name");

        AgentValidateResult r = validate(draftThenDeployDsl("device_repair_register",
                "\"key\":\"repair_approve\",\"name\":\"报修审批\",\"formDesignCode\":\"{{$.draft.code}}\""));

        assertTrue(r.isPassed(), "占位符那一档本条刻意不判：" + r.toFeedback());
    }

    /** 走 {@code formId}（BPM 自带动态表单）那一档压根没有在线表单，本条沉默 */
    @Test
    void validate_processDeployUsesFormId_notJudged() throws Exception {
        stubRegisteredTable("biz_device", "device_name");

        AgentValidateResult r = validate(draftThenDeployDsl("device_repair_register",
                "\"key\":\"repair_approve\",\"name\":\"报修审批\",\"formId\":1024"));

        assertTrue(r.isPassed(), r.toFeedback());
    }

    /**
     * 🔴 钉住「刻意不看可达性」：save_draft 挂在一条<b>走不到</b>部署节点的分支上，照样报。
     *
     * <p>发布体检拒的是「此刻不存在」，这个结论与两个节点之间有没有边无关；
     * 而 save_draft 不可达时那张表单<b>永远</b>建不出来，绑定它更是错的。
     * 加一道可达性只会让本条在最该开口的时候闭嘴。
     */
    @Test
    void validate_processDeployBindsInFlowForm_ignoresReachability() throws Exception {
        stubRegisteredTable("biz_device", "device_name");

        AgentValidateResult r = validate("""
                {"id":"wf",
                 "variables":[{"key":"input","type":"object"},{"key":"draft","type":"object"},
                              {"key":"proc","type":"object"}],
                 "nodes":[
                   {"id":"save_draft","type":"tool","config":{
                       "toolId":"form_save_draft","outputVar":"draft",
                       "args":{"name":"设备报修","code":"device_repair_register","mainTable":"biz_device",
                               "fields":[{"field":"device_name","widget":"input","title":"设备名称"}]}}},
                   {"id":"deploy","type":"tool","config":{
                       "toolId":"process_create_and_deploy","outputVar":"proc",
                       "args":{"key":"repair_approve","name":"报修审批",
                               "formDesignCode":"device_repair_register"}}}],
                 "edges":[{"from":"START","to":"deploy"},{"from":"deploy","to":"END"}]}
                """);

        assertTrue(r.getErrors().stream().anyMatch(error -> error.contains("本流程自己建的")),
                "save_draft 不可达也要报：" + r.toFeedback());
    }

    /**
     * 编码比对忽略大小写与首尾空白 —— 表单编码在库里是唯一索引，
     * 而 {@code form_save_draft} 与 BPM 那两侧都不区分大小写地取它。
     * 只按原样比的话，模型写 {@code Device_Repair_Register} 就能绕过本条，
     * 然后在发布那一步吃到同一句假话。
     */
    @Test
    void validate_processDeployFormCodeMatchedCaseInsensitively_rejected() throws Exception {
        stubRegisteredTable("biz_device", "device_name");

        AgentValidateResult r = validate(draftThenDeployDsl("device_repair_register",
                "\"key\":\"repair_approve\",\"name\":\"报修审批\",\"formDesignCode\":\" Device_Repair_Register \""));

        assertFalse(r.isPassed(), r.toFeedback());
        assertTrue(r.getErrors().get(0).contains("本流程自己建的"), r.getErrors().get(0));
    }

    /**
     * 拼一份「本流程建表单草稿 → 部署审批流并绑这张表单」的 DSL；V31 的用例都基于它
     *
     * @param draftCode      {@code form_save_draft} 声明的表单编码
     * @param deployArgsJson 部署节点 {@code args} 对象的<b>内容</b>（不含外层花括号）
     */
    private static String draftThenDeployDsl(String draftCode, String deployArgsJson) {
        return """
                {"id":"wf",
                 "variables":[{"key":"input","type":"object"},{"key":"draft","type":"object"},
                              {"key":"proc","type":"object"}],
                 "nodes":[
                   {"id":"save_draft","type":"tool","config":{
                       "toolId":"form_save_draft","outputVar":"draft",
                       "args":{"name":"设备报修","code":"%s","mainTable":"biz_device",
                               "fields":[{"field":"device_name","widget":"input","title":"设备名称"}]}}},
                   {"id":"deploy","type":"tool","config":{
                       "toolId":"process_create_and_deploy","outputVar":"proc",
                       "args":{%s}}}],
                 "edges":[{"from":"START","to":"save_draft"},{"from":"save_draft","to":"deploy"},
                          {"from":"deploy","to":"END"}]}
                """.formatted(draftCode, deployArgsJson);
    }

    // ==================== ㉒ 存在性算子撞上支配它的写入者（V32） ====================

    /** V32 文案的标记串（那句「结论由图定死」是本条独有的判据表述） */
    private static final String TAUTOLOGY_HINT = "结论由【图】定死";

    /** 数一数 V32 的 error，顺带把原文交出去当断言消息 */
    private static List<String> tautologyErrors(AgentValidateResult r) {
        return r.getErrors().stream().filter(e -> e.contains(TAUTOLOGY_HINT)).toList();
    }

    /**
     * 拼一份「START → script(写 {@code decision}) → condition.rule(用 {@code op} 判它) → 两条腿各写 output → END」
     *
     * <p>写入者是闸门的<b>必经之路</b>，正是 2026-10-10 那次实跑（图书借阅登记，执行号 197）的形状：
     * 那份 DSL 的 {@code decide_table} 三个 return 分支各返回一个对象、永不返回 null，
     * 而 {@code check_decision} 判的是 {@code {"var":"table_decision","op":"exists"}} ⟹ 恒真 ⟹
     * 恒走 {@code reuse_path}，全图唯一的建表节点 {@code create_table} 成了死代码，
     * 下一步 {@code form_save_draft} 报「主表 biz_library_borrow 在数据库里不存在」。
     *
     * <p>与 {@link #ruleGateDsl} 一样，这份夹具刻意把<b>其余规则全部喂饱</b>
     * （两条腿各有一个 llm 写 {@code output} ⟹ V20 闭嘴；没有工具节点 ⟹ V1/V2/V11 闭嘴；
     * 没有占位符 ⟹ V7/V8 闭嘴；写入的键都声明过 ⟹ V19 闭嘴），好让每条用例只考 V32 一件事。
     *
     * @param op      闸门用的算子 —— 本节的旋钮之一
     * @param gateVar 闸门判的键名；填 {@code decision} 之外的名字即变成「没人写的键」（那档归 V24）
     */
    private static String dominatedGateDsl(String triggerJson, String op, String gateVar) {
        return """
                {"id":"wf",%s
                 "variables":[{"key":"input","type":"object"},{"key":"decision","type":"object"},
                              {"key":"output","type":"string"}],
                 "nodes":[
                   {"id":"decide","type":"script","config":{"code":"return {use:false};","outputVar":"decision"}},
                   {"id":"gate","type":"condition.rule",
                    "config":{"branches":[{"target":"reuse","conditions":[{"var":"%s","op":"%s"}]}],
                              "default":"create"}},
                   {"id":"say_reuse","type":"llm","config":{"prompt":"说清结果","outputVar":"output"}},
                   {"id":"say_create","type":"llm","config":{"prompt":"说清结果","outputVar":"output"}}
                 ],
                 "edges":[
                   {"from":"START","to":"decide"},{"from":"decide","to":"gate"},
                   {"from":"gate","type":"conditional",
                    "cases":{"reuse":"say_reuse","create":"say_create"},"default":"say_create"},
                   {"from":"say_reuse","to":"END"},{"from":"say_create","to":"END"}
                 ]}
                """.formatted(triggerJson, gateVar, op);
    }

    /**
     * 🔴 命中侧：{@code exists} 判一个必经节点写出来的键 ⟹ 那条分支恒命中、{@code default} 那条腿是死代码。
     *
     * <p>断言里逐格点名文案该说的事，因为这句话就是模型自修正时能读到的<b>全部</b>：
     * 本条落在 {@code errors} 里，而 {@code errors} 正是
     * {@code WorkflowPlannerServiceImpl#planToWorkflow} 回灌给模型的那一份
     * （warning 那一档不会单独逼出一轮，见 {@code AgentValidateResult#isPassed}）。
     * 不点名写入者，模型不知道是谁把键写了；不说「恒为真」，它以为只是「有时候不准」；
     * 不给 {@code is_true}/{@code is_false} 这条出路，它只会换个算子再撞一次。
     */
    @Test
    void validate_existenceOpOnDominatedKey_rejected() throws Exception {
        AgentValidateResult r = validate(dominatedGateDsl(trigger("chat"), "exists", "decision"));

        assertFalse(r.isPassed(), "分支结论被图定死 ⟹ 另一条腿永远走不到，不该放行：" + r.toFeedback());
        List<String> hits = tautologyErrors(r);
        assertEquals(1, hits.size(), "该恰好一条 V32，多了就是同一根因说了几遍：" + r.getErrors());
        String msg = hits.get(0);
        assertTrue(msg.contains("id=gate"), "文案要点名是哪个闸门，否则模型不知道改哪儿：" + msg);
        assertTrue(msg.contains("id=decide"),
                "必须点名<b>是哪个节点</b>把键写了：实跑那次模型看到的报错是「主表不存在」，"
                        + "它去改建表参数、改表单绑定，全都没碰到病根（那条死分支）：" + msg);
        assertTrue(msg.contains("decision"), "文案要点名是哪个键，一份 DSL 里可能有好几个条件节点：" + msg);
        assertTrue(msg.contains("恒为真"),
                "要说清 exists 这一档的结论是什么。只说「写法不对」，模型会以为换个算子就行：" + msg);
        assertTrue(msg.contains("「值不为 null」"),
                "执行期判据必须与 RuleConditionNodeExecutor 里 exists 那一格逐字对上（actual != null）："
                        + "说清「它问的只是有没有写过」，模型才知道为什么自己算出来的结论被扔掉了：" + msg);
        assertTrue(msg.contains("is_true"), "出路必须点名到具体算子，这是提示词与 V24 共同教的那条正解：" + msg);
        assertTrue(msg.contains("default"),
                "要点名死掉的是哪条腿 —— 那次实跑里它是全图唯一的建表节点，"
                        + "不说清「哪条腿走不到」，作者看不出代价有多大：" + msg);
        assertTrue(ruleVarErrors(r).isEmpty(),
                "V24 与 V32 互斥（一个说没人写、一个说必有人写），同一个条件被两条点名就是把一个根因说两遍："
                        + r.getErrors());
        assertTrue(r.getWarnings().stream().noneMatch(w -> w.contains(TAUTOLOGY_HINT)),
                "这条必须是 error：降到 warning 就等于放那张「有一条腿是装饰」的草稿走到用户面前，"
                        + "而执行记录还是绿的：" + r.getWarnings());
    }

    /**
     * {@code not_exists} 是同一档的另一面：恒为假 ⟹ 那条分支永远不命中、每次都落 {@code default}。
     *
     * <p>🔴 这一格<b>必须</b>逐字断言执行期判据，因为它真的说反过一次：文案里那句
     * 「在执行期问的是……」当时写死了 exists 的语义（{@code actual != null}），而占位符收的是 {@code op} ——
     * 于是 {@code not_exists} 那档把判据说成自己的反面，且<b>没有任何测试会红</b>
     * （断言只查了「恒为假」，那半句是对的）。两档共用一句文案 ⟹ 必有一档说反，
     * 所以下面同时钉住「说了 {@code not_exists} 的判据」与「没抄 {@code exists} 的判据」。
     */
    @Test
    void validate_notExistsOpOnDominatedKey_rejected() throws Exception {
        AgentValidateResult r = validate(dominatedGateDsl(trigger("chat"), "not_exists", "decision"));

        List<String> hits = tautologyErrors(r);
        assertEquals(1, hits.size(), r.getErrors().toString());
        String msg = hits.get(0);
        assertTrue(msg.contains("恒为假"),
                "not_exists 那一档的结论是<b>恒为假</b>，与 exists 相反 —— 两个算子共用一句文案就会说反一半：" + msg);
        assertTrue(msg.contains("「值为 null」"),
                "执行期判据要翻面成 not_exists 那一格（RuleConditionNodeExecutor 里是 actual == null）：" + msg);
        assertFalse(msg.contains("「值不为 null」"),
                "exists 的判据一个字都不许留在这条文案里 —— 留着就是把执行期语义说反，"
                        + "而这句话就是模型自修正时读到的那份反馈：" + msg);
        assertTrue(msg.contains("每次都落 default"),
                "要说清代价落在哪条腿上：not_exists 恒假 ⟹ 每次都走 default，"
                        + "那条分支成了纯粹的装饰，执行记录却还是绿的：" + msg);
    }

    /**
     * 🔴 与触发器<b>无关</b>（这是 V32 与 V24 唯一的方向差别，也是最容易写错的一格）。
     *
     * <p>V24 只对会话触发生效，因为它的前提是「起跑时 state 里只有触发器那一个键」，
     * 而手动 {@code run-stream} 那一档调用方可以给任意初始 state。V32 的前提是
     * 「闸门之前必有一个节点<b>无条件</b>写过这个键」，靠的是<b>图</b>：
     * 初始 state 里有没有这个键都改不了「写入者会覆盖它」。
     *
     * <p>用 {@code trigger == null}（{@link #NO_TRIGGER}）作对照最锋利：那一档 V24 <b>整条跳过</b>，
     * 所以本用例同时钉住「V32 没有抄 V24 那句分型判据」。
     */
    @Test
    void validate_existenceOpOnDominatedKey_judgedEvenWithoutChatTrigger() throws Exception {
        AgentValidateResult r = validate(dominatedGateDsl(NO_TRIGGER, "exists", "decision"));

        assertEquals(1, tautologyErrors(r).size(),
                "trigger 缺席时 V32 照样要判（那种定义只能由人手动 run-stream 跑起来，"
                        + "而图上那条死腿与来路无关）：" + r.toFeedback());
        assertTrue(ruleVarErrors(r).isEmpty(),
                "对照组：V24 在 trigger == null 这一档整条跳过，两条规则的分型判据不该被混用：" + r.getErrors());
    }

    /**
     * 🔴 反向对照（阴性）：写入者只在<b>某条</b>分支上 ⟹ 它<b>不支配</b>闸门 ⟹ {@code exists} 完全合法。
     *
     * <p>这条是 V32 的<b>假警报防线</b>，也是本规则唯一的技术难点：
     * {@code try_cache} 确实是 {@code gate} 的<b>祖先</b>（{@code split} 的 probe 那条路经过它），
     * 所以拿 {@code ancestorsOf} 判就会把它报成恒真 —— 而运行期 {@code skip} 那条路绕开它，
     * {@code exists} 的结论<b>真的</b>随数据变（{@code try_cache} 没查到就 return null）。
     * 判据必须是「摘掉写入者后从 START 还走不走得到闸门」，落在
     * {@code GraphReachability#reachableFromAvoiding}。
     *
     * <p>它同时是夹具自检：红了说明 {@link #siblingWriterGateDsl} 本身不合法，
     * 那么上面几条命中侧用例的 {@code assertFalse(isPassed)} 就有<b>永真</b>的风险。
     */
    @Test
    void validate_existenceOpOnSiblingBranchWriter_passed() throws Exception {
        AgentValidateResult r = validate(siblingWriterGateDsl(trigger("chat")));

        assertTrue(tautologyErrors(r).isEmpty(),
                "写入者在兄弟分支上、不支配闸门 ⟹ exists 问的是「那条分支跑过没有」，这是合法写法："
                        + r.toFeedback());
        assertTrue(r.isPassed(), "夹具本身必须是一份干净可用的 DSL：" + r.toFeedback());
        assertTrue(r.getWarnings().isEmpty(), r.getWarnings().toString());
    }

    /**
     * ⚠️ 已承认的代价那一档<b>不</b>在这里测：「支配闸门的写入者故意 return null」是本条唯一的假警报
     * （{@code exists} 执行期问的是 {@code actual != null}，不是「键在不在」），
     * 而 V32 的规则头写了三条理由认下它。这里测的是<b>非</b>存在性算子：
     * 同一个必经写入者、同一个键，只把算子换成 {@code is_true}，V32 就该闭嘴 ——
     * 那正是文案给出的出路，出路本身不该再被拦。
     */
    @Test
    void validate_nonExistenceOpOnDominatedKey_passed() throws Exception {
        AgentValidateResult r = validate(dominatedGateDsl(trigger("chat"), "is_true", "decision"));

        assertTrue(tautologyErrors(r).isEmpty(),
                "is_true 判的是<b>值</b>，结论随运行期数据变，V32 不该拦：" + r.toFeedback());
        assertTrue(r.isPassed(), r.toFeedback());
    }

    /**
     * 「没人写这个键」那一档归 V24，V32 必须沉默 —— 一个根因只说一遍。
     *
     * <p>🔴 这一条同时钉住两条规则的<b>互斥</b>：它们共用 {@code collectRuleConditions} 这一份形状解析器，
     * 若哪天 V32 把「没人写」也报一遍，用户会在体检面板上看到两句指向同一个条件的话，
     * 而模型自修正时会照着第二句去改一个没病的字段。
     */
    @Test
    void validate_existenceOpOnKeyNobodyWrites_leftToV24() throws Exception {
        AgentValidateResult r = validate(dominatedGateDsl(trigger("chat"), "exists", "ghost"));

        assertEquals(1, ruleVarErrors(r).size(), "V24 该认领这一档：" + r.getErrors());
        assertTrue(tautologyErrors(r).isEmpty(),
                "没人写 ⟹ 谈不上「必经的写入者」，V32 该闭嘴：" + r.getErrors());
    }

    /**
     * 不可达的闸门归 V18（{@code validateUnreachableNodes}），V32 沉默。
     *
     * <p>不只是「让给别的规则」：从 START 压根走不到的节点，「摘掉写入者后走不到它」这件事
     * <b>恒成立</b>，于是每个写入者都会被算成支配它 —— 判了就是一句错话。
     */
    @Test
    void validate_existenceOpOnUnreachableGate_leftToV18() throws Exception {
        AgentValidateResult r = validate("""
                {"id":"wf",
                 "variables":[{"key":"input","type":"object"},{"key":"decision","type":"object"},
                              {"key":"output","type":"string"}],
                 "nodes":[
                   {"id":"decide","type":"script","config":{"code":"return {use:false};","outputVar":"decision"}},
                   {"id":"orphan_gate","type":"condition.rule",
                    "config":{"branches":[{"target":"reuse","conditions":[{"var":"decision","op":"exists"}]}],
                              "default":"create"}},
                   {"id":"summarize","type":"llm","config":{"prompt":"说清结果","outputVar":"output"}}
                 ],
                 "edges":[{"from":"START","to":"decide"},{"from":"decide","to":"summarize"},
                          {"from":"summarize","to":"END"}]}
                """);

        assertTrue(tautologyErrors(r).isEmpty(), "不可达的闸门不该由 V32 说：" + r.toFeedback());
        assertTrue(r.getErrors().stream().anyMatch(e -> e.contains("id=orphan_gate") && e.contains("永远不会执行")),
                "该由 V18 点名它：" + r.getErrors());
    }

    /**
     * 内层图（{@code iteration.body}）里的闸门不判 —— 哪怕作者把它<b>也</b>写进了 {@code edges[]}。
     *
     * <p>正常情况下内层节点不在 {@code edges[]} 里，于是「不可达」那一档已经把它们挡住了；
     * 本用例考的是<b>畸形</b>形状：提示词明写「分支节点仍写在顶层 nodes[]，但<b>不要</b>把它们写进 edges[]」，
     * 而这条错法<b>没有任何规则会报</b>（端点解析得到，V18 又把内层 id 排除在不可达之外）。
     * 那时父图的可达性对内层节点就是<b>假</b>的（它按 body 逐项执行），
     * 拿它去判支配会得到一句「图上说得通、运行期不成立」的话，故显式跳过。
     */
    @Test
    void validate_existenceOpOnInnerGraphGate_skipped() throws Exception {
        AgentValidateResult r = validate("""
                {"id":"wf",
                 "variables":[{"key":"input","type":"object"},{"key":"decision","type":"object"},
                              {"key":"item","type":"object"},{"key":"output","type":"string"}],
                 "nodes":[
                   {"id":"decide","type":"script","config":{"code":"return {use:false};","outputVar":"decision"}},
                   {"id":"loop","type":"iteration",
                    "config":{"itemsVar":"input","itemVar":"item","body":["inner_gate"]}},
                   {"id":"inner_gate","type":"condition.rule",
                    "config":{"branches":[{"target":"reuse","conditions":[{"var":"decision","op":"exists"}]}],
                              "default":"create"}},
                   {"id":"summarize","type":"llm","config":{"prompt":"说清结果","outputVar":"output"}}
                 ],
                 "edges":[{"from":"START","to":"decide"},{"from":"decide","to":"loop"},
                          {"from":"loop","to":"inner_gate"},{"from":"inner_gate","to":"summarize"},
                          {"from":"summarize","to":"END"}]}
                """);

        assertTrue(tautologyErrors(r).isEmpty(),
                "内层图节点的支配关系在父图上判不准，V32 该整个跳过：" + r.toFeedback());
    }

    /**
     * 拼一份「写入者在<b>兄弟分支</b>上」的菱形图：
     * {@code START → calc(script 写 flag) → split(判 flag) →{probe: try_cache 写 cache_hit | skip: 直接到 gate}
     * → gate(判 cache_hit 的 exists) → 两条腿各写 output → END}。
     *
     * <p>{@code try_cache} 是 {@code gate} 的<b>祖先</b>但<b>不支配</b>它 —— 这一格差别就是
     * {@link #validate_existenceOpOnSiblingBranchWriter_passed} 要钉的东西。
     * 它刻意 {@code return null} 表示「没查到」：那是 {@code exists} 唯一真正有信息量的用法。
     */
    private static String siblingWriterGateDsl(String triggerJson) {
        return """
                {"id":"wf",%s
                 "variables":[{"key":"input","type":"object"},{"key":"flag","type":"boolean"},
                              {"key":"cache_hit","type":"object"},{"key":"output","type":"string"}],
                 "nodes":[
                   {"id":"calc","type":"script","config":{"code":"return true;","outputVar":"flag"}},
                   {"id":"split","type":"condition.rule",
                    "config":{"branches":[{"target":"probe","conditions":[{"var":"flag","op":"is_true"}]}],
                              "default":"skip"}},
                   {"id":"try_cache","type":"script","config":{"code":"return null;","outputVar":"cache_hit"}},
                   {"id":"gate","type":"condition.rule",
                    "config":{"branches":[{"target":"hit","conditions":[{"var":"cache_hit","op":"exists"}]}],
                              "default":"miss"}},
                   {"id":"say_hit","type":"llm","config":{"prompt":"说清结果","outputVar":"output"}},
                   {"id":"say_miss","type":"llm","config":{"prompt":"说清结果","outputVar":"output"}}
                 ],
                 "edges":[
                   {"from":"START","to":"calc"},{"from":"calc","to":"split"},
                   {"from":"split","type":"conditional",
                    "cases":{"probe":"try_cache","skip":"gate"},"default":"gate"},
                   {"from":"try_cache","to":"gate"},
                   {"from":"gate","type":"conditional",
                    "cases":{"hit":"say_hit","miss":"say_miss"},"default":"say_miss"},
                   {"from":"say_hit","to":"END"},{"from":"say_miss","to":"END"}
                 ]}
                """.formatted(triggerJson);
    }

    // ==================== ㉓ 条件节点能给出的路由值没有对应的 case（V33） ====================

    /**
     * V33 文案的标记串：取自 {@code validateRouteKeysCoveredByCases} 那句里<b>只有它会出现</b>的一段。
     * 用标记过滤而不是断言 {@code getErrors().size()}，与 ⑯ 节的 {@link #COND_EDGE_SOURCE_HINT} 同一个理由：
     * V25 与 V33 看的是同一条条件边的两侧，本节有几条用例故意让 V25 一起响。
     */
    private static final String ROUTE_KEY_HINT = "在这条条件边的 cases 里没有对应的键";

    /** 数一数带标记串的 error，顺带把原文交出去当断言消息（红了才看得见到底报了什么） */
    private static List<String> routeKeyErrors(AgentValidateResult r) {
        return r.getErrors().stream().filter(e -> e.contains(ROUTE_KEY_HINT)).toList();
    }

    /**
     * 拼一份「START → calc(script 写 flag) → gate →〔条件边〕→ 两条腿各写 output → END」
     *
     * <p>与 ⑯ 节的 {@link #confirmGateDsl} 同一个套路：把<b>其余规则全部喂饱</b>，好让每条用例只考 V33 一件事 ——
     * 两条腿各有一个 llm 写 {@code output}（V20 闭嘴）、{@code flag} 真有节点写（V24 闭嘴，
     * 且用的是 {@code is_true} 而不是 {@code exists}，故 V32 也闭嘴）、没有工具节点（V1/V2/V11 闭嘴）、
     * 没有 {@code {{$.x}}} 占位符（V7/V8 闭嘴）。
     *
     * <p>🔴 刻意用 {@link #NO_TRIGGER}：本节的旋钮只有「gate 列了哪些路由值」与「cases 收了哪些键」两格，
     * 挂上 chat 触发器会把 V24（读的键有没有人写）也拉进来，两条规则一起响就看不出是谁判的红。
     *
     * @param gateNodeJson gate 节点整块（含结尾逗号）
     * @param casesJson    条件边的 {@code cases} 对象
     */
    private static String routeKeyDsl(String gateNodeJson, String casesJson) {
        return """
                {"id":"wf",
                 "variables":[{"key":"input","type":"object"},{"key":"flag","type":"boolean"},
                              {"key":"output","type":"string"}],
                 "nodes":[
                   {"id":"calc","type":"script","config":{"code":"return true;","outputVar":"flag"}},
                   %s
                   {"id":"say_yes","type":"llm","config":{"prompt":"说清结果","outputVar":"output"}},
                   {"id":"say_no","type":"llm","config":{"prompt":"说清结果","outputVar":"output"}}
                 ],
                 "edges":[
                   {"from":"START","to":"calc"},{"from":"calc","to":"gate"},
                   {"from":"gate","type":"conditional","cases":%s,"default":"say_no"},
                   {"from":"say_yes","to":"END"},{"from":"say_no","to":"END"}
                 ]}
                """.formatted(gateNodeJson, casesJson);
    }

    /**
     * 🔴 命中侧：作者<b>逐条列出</b>了两个路由值，cases 只收了一个。
     *
     * <p>运行期的实际行为是 {@code flag} 为假时 gate 写出 {@code "no"}，
     * {@code addConditionalEdge} 查不到它就落 {@code default} —— 本例里 default 恰好是
     * {@code say_no}，所以<b>结果看着是对的</b>。那正是它危险的地方：作者从此养成
     * 「反正有 default 兜着」的习惯，等到哪一天 default 指的是另一条腿（V25 那个实测疤就是），
     * 这条拼错/漏配的腿就会把流程带到完全相反的结果上，而执行记录仍然报成功。
     */
    private static final String TWO_TARGET_GATE = """
            {"id":"gate","type":"condition.rule",
             "config":{"branches":[{"target":"yes","conditions":[{"var":"flag","op":"is_true"}]},
                                   {"target":"no","conditions":[{"var":"flag","op":"is_false"}]}]}},
            """;

    @Test
    void validate_routeKeyMissingFromCases_rejected() throws Exception {
        AgentValidateResult r = validate(routeKeyDsl(TWO_TARGET_GATE, "{\"yes\":\"say_yes\"}"));

        assertFalse(r.isPassed(), "列出来的路由值没人接 ⟹ 那条腿永远走不到，不该放行：" + r.toFeedback());
        List<String> hits = routeKeyErrors(r);
        assertEquals(1, hits.size(), "该恰好一条 V33（一条边只报一条），多了就是同一格配置说了几遍：" + r.getErrors());
        assertEquals(hits, r.getErrors(),
                "夹具本身除了这一处必须是干净的：若还有别的规则在响，上面那句 assertFalse(isPassed) "
                        + "就成了永真断言（与 ⑯ 节同一条纪律）：" + r.getErrors());
        String msg = hits.get(0);
        assertTrue(msg.contains("节点(gate)"), "文案要点名是哪个条件节点，一份 DSL 里可能有好几个：" + msg);
        assertTrue(msg.contains("「no」"), "要点名缺的是哪个路由值，否则作者得自己去数 branches：" + msg);
        assertFalse(msg.contains("「yes」"), "已经被 cases 收下的那个不该被点名，否则作者会去改没病的一格：" + msg);
        assertTrue(msg.contains("say_no"), "要说清「实际会落到哪条腿」，作者才知道后果是什么：" + msg);
        assertTrue(msg.contains("逐字"),
                "必须把「比对是逐字的」说出来：漏一个空格与拼错一个字母是这一格的两种主要错法，"
                        + "不说清作者会以为自己写的「差不多就行」：" + msg);
        assertTrue(r.getWarnings().stream().noneMatch(w -> w.contains(ROUTE_KEY_HINT)),
                "这条必须是 error：降到 warning 就等于放「那条腿一次也走不到、执行却报成功」的草稿发布："
                        + r.getWarnings());
    }

    /**
     * 🔴 反向对照（阳性）：把缺的那个键补进 cases，整份体检就该干净通过 —— 0 error、0 warning。
     *
     * <p>钉的是「模型照文案改完不会还是红的」：改完还红，自修正回路就会在 {@code maxRounds} 里空转烧钱
     * （⑭⑮⑯ 三节都记着这个坑）。
     */
    @Test
    void validate_routeKeyCoveredByCases_passed() throws Exception {
        AgentValidateResult r = validate(routeKeyDsl(TWO_TARGET_GATE,
                "{\"yes\":\"say_yes\",\"no\":\"say_no\"}"));

        assertTrue(routeKeyErrors(r).isEmpty(), "两个路由值都被 cases 收下了，V33 该闭嘴：" + r.getErrors());
        assertTrue(r.isPassed(), "文案给的出路①必须走得通：" + r.toFeedback());
        assertTrue(r.getWarnings().isEmpty(), r.getWarnings().toString());
    }

    /**
     * 🔴 只差一个<b>首尾空格</b>也必须判红 —— 这一条钉的是「校验器不许比运行期宽容」
     *
     * <p>三处写入/读取方全都不 trim（{@code RuleConditionNodeExecutor} 的
     * {@code String.valueOf(target)}、{@code LlmConditionNodeExecutor#matchChoice} 返回的原样 choice、
     * {@code addConditionalEdge} 建 {@code routeMap} 用的原样键），所以 {@code "no "} 与 {@code "no"}
     * 在运行期就是两个不同的路由值。
     *
     * <p>⚠️ 一旦有人「好心」在校验器里 trim 一下（或复用 {@code addTrimmedKey} —— 那个是给 state 键名用的，
     * 键名确实到处 trim，口径正好相反），本条就会变绿，而流程照样静默落 default：
     * 那是<b>亲手造一个假绿</b>，比没有这条规则更坏。
     *
     * <p>🔴 夹具刻意用字符串拼接而不是文本块：Java 文本块会吃掉每行<b>行尾</b>的空白，
     * 用文本块写这个用例的话那个空格根本进不了 JSON，测试会「绿得毫无意义」。
     */
    @Test
    void validate_routeKeyDiffersByTrailingSpace_rejected() throws Exception {
        String gate = "{\"id\":\"gate\",\"type\":\"condition.rule\",\"config\":{\"branches\":["
                + "{\"target\":\"yes\",\"conditions\":[{\"var\":\"flag\",\"op\":\"is_true\"}]},"
                + "{\"target\":\"no \",\"conditions\":[{\"var\":\"flag\",\"op\":\"is_false\"}]}]}},";
        AgentValidateResult r = validate(routeKeyDsl(gate, "{\"yes\":\"say_yes\",\"no\":\"say_no\"}"));

        List<String> hits = routeKeyErrors(r);
        assertEquals(1, hits.size(), "差一个空格就是差：运行期不 trim，校验器也不许 trim：" + r.toFeedback());
        assertTrue(hits.get(0).contains("「no 」"),
                "点名的必须是原样那个值（含空格），否则作者对着文案看不出自己错在哪：" + hits.get(0));
    }

    /** 同上，只是把空格换成大小写 —— {@code addConditionalEdge} 的 {@code containsKey} 同样区分大小写 */
    @Test
    void validate_routeKeyDiffersByCase_rejected() throws Exception {
        String gate = """
                {"id":"gate","type":"condition.rule",
                 "config":{"branches":[{"target":"Yes","conditions":[{"var":"flag","op":"is_true"}]}]}},
                """;
        AgentValidateResult r = validate(routeKeyDsl(gate, "{\"yes\":\"say_yes\"}"));

        List<String> hits = routeKeyErrors(r);
        assertEquals(1, hits.size(), "大小写不同就是不同的路由值：" + r.toFeedback());
        assertTrue(hits.get(0).contains("「Yes」"), hits.get(0));
    }

    /**
     * 🔴 {@code config.default} <b>不在射程内</b> —— 这一条钉的是规则的边界，别「顺手补全」
     *
     * <p>用的是库里 def 416「设备管理表单创建与体检」（PUBLISHED）的真实形状：
     * {@code branches=[{target:"yes"}]} + {@code default:"no"}，而 cases 里<b>只有</b> {@code yes}。
     * 它跑得好好的，因为 {@code config.default} 是「一个分支都没命中」的兜底路由值，
     * 而兜底的自然接手方本来就是<b>边的</b> {@code default}（这里正是 {@code say_no}）——
     * 作者没把它写进 cases，说明他没打算给它专属目标，<b>没有被顶替掉的意图</b>。
     *
     * <p>⚠️ 把 {@code config.default} 算进来的后果不是「多报一条」那么轻：{@code error} 是<b>拦发布</b>的，
     * 于是这个正在用的流程<b>再也发不了版</b>，而提示它的文案还会让作者去改一处本来没坏的配置。
     * 提示词那句「逐字一致」点名的也只有 {@code branches[].target} 与 {@code choices} 两格。
     */
    @Test
    void validate_ruleConditionDefaultRouteValue_exempt() throws Exception {
        String gate = """
                {"id":"gate","type":"condition.rule",
                 "config":{"branches":[{"target":"yes","conditions":[{"var":"flag","op":"is_true"}]}],
                           "default":"no"}},
                """;
        AgentValidateResult r = validate(routeKeyDsl(gate, "{\"yes\":\"say_yes\"}"));

        assertTrue(routeKeyErrors(r).isEmpty(),
                "config.default 是兜底路由值，落边的 default 正是它该有的去处：" + r.toFeedback());
        assertTrue(r.isPassed(), "def 416 的形状必须能发布：" + r.toFeedback());
    }

    /** 另一种写入方：{@code condition.llm} 的路由值来自 {@code choices}，同样要与 cases 逐字对齐 */
    @Test
    void validate_llmChoiceMissingFromCases_rejected() throws Exception {
        String gate = """
                {"id":"gate","type":"condition.llm",
                 "config":{"prompt":"flag 是真的吗？","choices":["yes","no"]}},
                """;
        AgentValidateResult r = validate(routeKeyDsl(gate, "{\"yes\":\"say_yes\"}"));

        List<String> hits = routeKeyErrors(r);
        assertEquals(1, hits.size(), "模型从 choices 里选出 no 时，条件边查不到它就落 default：" + r.toFeedback());
        assertTrue(hits.get(0).contains("condition.llm"),
                "出路要点名到具体节点类型（改 choices 还是改 branches[].target，两格的字段名不同）："
                        + hits.get(0));
    }

    /**
     * 豁免 ②：出发点不是会写路由键的那两种节点时，V33 必须<b>整条沉默</b>，由 V25 说
     *
     * <p>钉的是「一个根因只说一遍」：{@code script} 出发的条件边，cases 里的键<b>一个</b>都命中不了
     * （没人写路由键），此时再叠一条「这两个键没有对应的 case」是把同一个坏边说成两个毛病，
     * 而第二条的说法还是错的 —— 问题不在键对不对，在于压根没人写那个键。
     */
    @Test
    void validate_routeKeyCheckSilentWhenSourceIsNotAWriter() throws Exception {
        AgentValidateResult r = validate("""
                {"id":"wf",
                 "variables":[{"key":"input","type":"object"},{"key":"flag","type":"boolean"},
                              {"key":"output","type":"string"}],
                 "nodes":[
                   {"id":"calc","type":"script","config":{"code":"return true;","outputVar":"flag"}},
                   {"id":"say_yes","type":"llm","config":{"prompt":"说清结果","outputVar":"output"}},
                   {"id":"say_no","type":"llm","config":{"prompt":"说清结果","outputVar":"output"}}
                 ],
                 "edges":[
                   {"from":"START","to":"calc"},
                   {"from":"calc","type":"conditional","cases":{"yes":"say_yes","no":"say_no"},
                    "default":"say_no"},
                   {"from":"say_yes","to":"END"},{"from":"say_no","to":"END"}
                 ]}
                """);

        assertTrue(routeKeyErrors(r).isEmpty(), "V33 该把这条边让给 V25：" + r.getErrors());
        assertEquals(1, condEdgeSourceErrors(r).size(),
                "阳性对照：这条边确实坏，只是归 V25 说 —— 若这里也是 0，"
                        + "上面那句「V33 沉默」就可能是整条规则没跑（假绿）：" + r.getErrors());
    }

    /**
     * 豁免 ①：条件节点没有<b>条件</b>出边时，它写什么路由值都无害 —— 普通边压根不读那个键
     *
     * <p>这一档同时覆盖了内层图（{@code iteration.body} / {@code parallel.branches}）里的条件节点：
     * 按规范它们不许出现在 {@code edges[]}（V18 那节记着编译器硬性拒绝），故天然落在本豁免里，
     * 不需要专门判一层。
     */
    @Test
    void validate_conditionNodeWithPlainOutEdge_exempt() throws Exception {
        AgentValidateResult r = validate("""
                {"id":"wf",
                 "variables":[{"key":"input","type":"object"},{"key":"flag","type":"boolean"},
                              {"key":"output","type":"string"}],
                 "nodes":[
                   {"id":"calc","type":"script","config":{"code":"return true;","outputVar":"flag"}},
                   {"id":"gate","type":"condition.rule",
                    "config":{"branches":[{"target":"没人接也无所谓","conditions":[{"var":"flag","op":"is_true"}]}]}},
                   {"id":"say","type":"llm","config":{"prompt":"说清结果","outputVar":"output"}}
                 ],
                 "edges":[
                   {"from":"START","to":"calc"},{"from":"calc","to":"gate"},
                   {"from":"gate","to":"say"},{"from":"say","to":"END"}
                 ]}
                """);

        assertTrue(routeKeyErrors(r).isEmpty(), "普通边不读路由键，V33 无从判起：" + r.getErrors());
        assertTrue(r.isPassed(), r.toFeedback());
    }

    // ==================== ㉔ 一个节点最多一条普通出边（V34） ====================

    /**
     * V34 文案的标记串。与 ⑯ 节的 {@link #COND_EDGE_SOURCE_HINT} 同一个理由用标记过滤：
     * 本节有几条用例故意让别的规则一起响（豁免档要的就是「别人报、V34 不报」）。
     */
    private static final String PLAIN_OUT_EDGE_HINT = "条普通出边";

    private static List<String> plainOutEdgeErrors(AgentValidateResult r) {
        return r.getErrors().stream().filter(e -> e.contains(PLAIN_OUT_EDGE_HINT)).toList();
    }

    /**
     * 🔴 命中侧：定义 900215（2026-10-18，M4 第二轮 s1 实录）的真实形状 ——
     * AI 规划器把 {@code hitl.confirm} 的「同意 / 拒绝」画成了<b>两条普通出边</b>
     * （{@code confirm_publish→publish} 与 {@code confirm_publish→report_cancel}）。
     *
     * <p>那次的代价是整场录制作废：体检全绿、发布成功、草稿卡上九个节点排得整整齐齐，
     * 用户点「确认并运行」之后 {@code StateGraph.compile()} 才抛
     * {@code GraphStateException: parallel node [confirm_publish] must have only one target,
     * but [__END__, report_success] have been found!}（执行 215 FAILED，{@code failed_at_node} 是 NULL）。
     * 根因是发布前体检走 {@code dryRunValidate} → {@code buildStateGraph}，<b>只建图、不 compile</b>，
     * 于是这条约束在 V34 之前没有任何一层拦得住。
     *
     * <p>这里用 ⑯ 节那份夹具复刻同一形状（ask 同时接 gate 与 say_no），
     * 好让「命中」与「改完就绿」两侧只差那一条边。
     */
    @Test
    void validate_twoPlainOutEdgesFromOneNode_rejected() throws Exception {
        AgentValidateResult r = validate(confirmGateDsl(trigger("chat"), RULE_GATE_NODE,
                "{\"from\":\"ask\",\"to\":\"gate\"},{\"from\":\"ask\",\"to\":\"say_no\"},", "gate"));

        List<String> hits = plainOutEdgeErrors(r);
        assertEquals(1, hits.size(), "两条普通出边该恰好报一条 V34，多了就是同一个根因说了几遍：" + r.getErrors());
        String msg = hits.get(0);
        assertTrue(msg.contains("节点(ask)"), "文案要点名是哪个节点在分叉：" + msg);
        assertTrue(msg.contains("gate、say_no"),
                "两个目标都要列出来，且顺序跟 edges[] 里的书写顺序一致（模型才认得出自己写的是哪两条）：" + msg);
        assertTrue(msg.contains("condition.rule") && msg.contains("approveVar"),
                "出路要点名到具体节点类型<b>和</b>它的 var 填什么 —— 光说「加个条件节点」，"
                        + "模型改完就撞 V24，自修正回路白烧一轮（⑯ 节同一条纪律）：" + msg);
        assertTrue(msg.contains("体检阶段看不出来"),
                "要说清「为什么草稿卡是全绿的」，否则作者会以为是自己点错了地方：" + msg);
        assertTrue(r.getWarnings().stream().noneMatch(w -> w.contains(PLAIN_OUT_EDGE_HINT)),
                "这条必须是 error：降到 warning 就等于放一张「一确认就崩、还指不出崩在哪」的草稿走到用户面前："
                        + r.getWarnings());
    }

    /**
     * 🔴 反向对照（阳性）：把上面那<b>多余的一条</b>普通出边删掉，整份体检就该干净通过。
     *
     * <p>钉的是 V34 给出的出路真能走通 —— 这份 DSL 正是 ⑯ 节 {@code
     * validate_conditionalEdgeFromRuleCondition_passed} 的那份，故它同时证明
     * V34 与 V25/V24 <b>一起可满足</b>：分叉交给 condition.rule，ask 只留一条普通出边。
     */
    @Test
    void validate_singlePlainOutEdge_passed() throws Exception {
        AgentValidateResult r = validate(confirmGateDsl(trigger("chat"), RULE_GATE_NODE,
                "{\"from\":\"ask\",\"to\":\"gate\"},", "gate"));

        assertTrue(plainOutEdgeErrors(r).isEmpty(), "一条普通出边是正常形状，V34 该闭嘴：" + r.getErrors());
        assertTrue(r.isPassed(), "文案给的出路必须走得通：" + r.toFeedback());
        assertTrue(r.getWarnings().isEmpty(), r.getWarnings().toString());
    }

    /**
     * 豁免 ①：{@code parallel} 节点的两条出边不是「分叉」，是 fan-out 的 join 目标写法，
     * 而编译器另有一档「parallel 节点只能有一条出边」当场拒绝 —— V34 不重复报。
     *
     * <p>🔴 阳性对照放在<b>同一份 DSL</b> 里：ask 那两条普通出边照样被 V34 点名。
     * 少了这一手，「V34 对 par 沉默」就可能是整条规则没跑（假绿，㉓ 节末尾那条纪律）。
     */
    @Test
    void validate_parallelSourceWithTwoOutEdges_exempt() throws Exception {
        AgentValidateResult r = validate("""
                {"id":"wf",
                 "variables":[{"key":"input","type":"object"},{"key":"confirm_it","type":"boolean"},
                              {"key":"output","type":"string"}],
                 "nodes":[
                   {"id":"ask","type":"hitl.confirm","config":{"approveVar":"confirm_it"}},
                   {"id":"par","type":"parallel",
                    "config":{"join":"all","maxParallelism":2,"branches":[["lft"],["rgt"]]}},
                   {"id":"lft","type":"llm","config":{"prompt":"左边","outputVar":"output"}},
                   {"id":"rgt","type":"llm","config":{"prompt":"右边","outputVar":"output"}},
                   {"id":"tail","type":"llm","config":{"prompt":"说清结果","outputVar":"output"}}
                 ],
                 "edges":[
                   {"from":"START","to":"ask"},
                   {"from":"ask","to":"par"},{"from":"ask","to":"tail"},
                   {"from":"par","to":"tail"},{"from":"par","to":"END"},
                   {"from":"tail","to":"END"}
                 ]}
                """);

        List<String> hits = plainOutEdgeErrors(r);
        assertEquals(1, hits.size(),
                "阳性对照：规则确实在跑，只是豁免了 parallel —— 若这里是 0，下面那句「对 par 沉默」就没意义了："
                        + r.getErrors());
        assertTrue(hits.get(0).contains("节点(ask)"), hits.get(0));
        assertTrue(hits.stream().noneMatch(e -> e.contains("节点(par)")),
                "parallel 的出边归编译器那档说，V34 不该重复报：" + hits);
    }

    /**
     * 豁免 ③：{@code from} 解析不到任何已声明节点时 V34 整条沉默，由 {@code validateEdges} 说
     *
     * <p>同一个坏端点再叠一条「它有两条普通出边」，等于把一个根因说成两个毛病，
     * 而第二条的说法还是错的 —— 问题不在边挂了几条，在那个节点压根不存在。
     */
    @Test
    void validate_unresolvableSource_exempt() throws Exception {
        AgentValidateResult r = validate("""
                {"id":"wf",
                 "variables":[{"key":"input","type":"object"},{"key":"output","type":"string"}],
                 "nodes":[
                   {"id":"a","type":"llm","config":{"prompt":"说清结果","outputVar":"output"}},
                   {"id":"b","type":"llm","config":{"prompt":"说清结果","outputVar":"output"}}
                 ],
                 "edges":[
                   {"from":"START","to":"ghost"},{"from":"ghost","to":"a"},{"from":"ghost","to":"b"},
                   {"from":"a","to":"END"},{"from":"b","to":"END"}
                 ]}
                """);

        assertTrue(plainOutEdgeErrors(r).isEmpty(), "V34 该把这个端点让给 validateEdges：" + r.getErrors());
        assertTrue(r.getErrors().stream().anyMatch(e -> e.contains("解析不到任何节点")),
                "阳性对照：这条 DSL 确实坏，只是归别人说 —— 若这里也是空，"
                        + "上面那句「V34 沉默」就可能是整条规则没跑（假绿）：" + r.getErrors());
    }

    /**
     * 豁免 ②：条件边指向多个目标是它的<b>本职</b>，不算分叉
     *
     * <p>这一档在 ⑯ 节的正解里天天出现（一条条件边同时给出 yes/no 两个目标 + default），
     * 但那里断言的是 V25 沉默；本条把「V34 也不许把它数成两条出边」单独钉一次 ——
     * 判据是 {@code edge.isConditional()}，不是数 cases 的键。
     */
    @Test
    void validate_conditionalEdgeNotCountedAsPlainOutEdge() throws Exception {
        AgentValidateResult r = validate("""
                {"id":"wf",
                 "variables":[{"key":"input","type":"object"},{"key":"flag","type":"boolean"},
                              {"key":"output","type":"string"}],
                 "nodes":[
                   {"id":"calc","type":"script","config":{"code":"return true;","outputVar":"flag"}},
                   {"id":"gate","type":"condition.rule",
                    "config":{"branches":[{"target":"yes","conditions":[{"var":"flag","op":"is_true"}]}],
                              "default":"no"}},
                   {"id":"say_yes","type":"llm","config":{"prompt":"说清结果","outputVar":"output"}},
                   {"id":"say_no","type":"llm","config":{"prompt":"说清结果","outputVar":"output"}}
                 ],
                 "edges":[
                   {"from":"START","to":"calc"},{"from":"calc","to":"gate"},
                   {"from":"gate","type":"conditional","cases":{"yes":"say_yes","no":"say_no"},
                    "default":"say_no"},
                   {"from":"say_yes","to":"END"},{"from":"say_no","to":"END"}
                 ]}
                """);

        assertTrue(plainOutEdgeErrors(r).isEmpty(), "条件边的多目标不是分叉：" + r.getErrors());
        assertTrue(r.isPassed(), r.toFeedback());
    }

    // ==================== ㉕ tool.args 里占位符的子键不存在（V35） ====================

    /**
     * V35 文案的标记串。与本节其余标记串同一个理由用过滤而不断总数：
     * 消费节点写坏的子键往往同时踩到别的规则，断总数就成了「测 A 却因为 B 变红」。
     */
    private static final String SUBKEY_HINT = "的顶层键只有";

    private static List<String> subkeyErrors(AgentValidateResult r) {
        return r.getErrors().stream().filter(e -> e.contains(SUBKEY_HINT)).toList();
    }

    /**
     * 登记一个内置工具桩并声明它的<b>顶层键</b>（V35 的唯一依据）
     *
     * <p>🔴 与 {@link #stubToolShape} 刻意分成两个方法而不是给那边加一个参数：
     * 那边「{@code arrayKeys} 为空」的意思是<b>「确实不回数组」</b>（V26 敢据此断言），
     * 这边「{@code resultKeys} 为空」的意思是<b>「平台还没登记」</b>（V35 必须沉默）。
     * 同一个空列表在两处语义相反，合成一个方法迟早有人把一档当另一档用。
     */
    private void stubToolResultKeys(String name, RiskLevel risk, List<String> resultKeys) {
        stubBuiltinTool(name, risk);
        AgentToolRegistry.ToolDefinition def = toolStubs.get(name);
        when(def.getDeclaredResultKeys()).thenReturn(resultKeys);
        when(def.isDeclaredResultShapeKnown()).thenReturn(true);
    }

    /**
     * 登记一个「形状可静态声明、但顶层键<b>没登记</b>」的桩 —— 51 个内置工具里绝大多数当下的真实状态
     *
     * <p>🔴 不能靠 Mockito 的默认值来充当这一档：默认 {@code isDeclaredResultShapeKnown()} 是 false，
     * 那样「V35 对未登记的工具沉默」就会被 {@code shapeKnown} 那道闸解释掉，
     * 用例绿了也说明不了「空键列表 = 不知道」这条边界真的落地了。故这里把 shapeKnown 显式钉成 true。
     */
    private void stubToolKeysUnregistered(String name, RiskLevel risk) {
        stubToolResultKeys(name, risk, List.of());
    }

    /**
     * 本节的标准舞台：三个产出节点（已登记键 / 未登记键 / 形状不可知）串在一个消费节点前面
     *
     * <p>🔴 三个产出者<b>同时在场</b>是刻意的：每条豁免用例都能在<b>同一份 DSL</b> 里
     * 顺带断言「已登记那一格照样被点名」（阳性对照）。少了这一手，
     * 「V35 对某档沉默」就可能是整条规则没跑（假绿，㉓/㉔ 两节末尾那条纪律）。
     *
     * <p>⚠️ 工具键用中性的 {@code tbl_*} 而不是真名 {@code datamodel_create} / {@code datamodel_structure}：
     * 真名各自还挂着别的规则（V21 的 args 形状、V30 的系统列），
     * 用它们会让本节的红绿说明不了 V35 判得对不对。真实事故形状记在规则头 javadoc 里。
     */
    private static String subkeyStageDsl(String consumerArgsJson) {
        return """
                {"id":"wf",
                 "variables":[{"key":"input","type":"object"},{"key":"create_out","type":"object"},
                              {"key":"raw_out","type":"object"},{"key":"dyn_out","type":"object"},
                              {"key":"struct_out","type":"object"}],
                 "nodes":[
                   {"id":"create_tbl","type":"tool",
                    "config":{"toolId":"tbl_create","args":{},"outputVar":"create_out"}},
                   {"id":"raw_tbl","type":"tool",
                    "config":{"toolId":"tbl_raw","args":{},"outputVar":"raw_out"}},
                   {"id":"dyn_tbl","type":"tool",
                    "config":{"toolId":"tbl_dyn","args":{},"outputVar":"dyn_out"}},
                   {"id":"read_tbl","type":"tool",
                    "config":{"toolId":"tbl_structure","args":%s,"outputVar":"struct_out"}}
                 ],
                 "edges":[{"from":"START","to":"create_tbl"},{"from":"create_tbl","to":"raw_tbl"},
                          {"from":"raw_tbl","to":"dyn_tbl"},{"from":"dyn_tbl","to":"read_tbl"},
                          {"from":"read_tbl","to":"END"}]}
                """.formatted(consumerArgsJson);
    }

    /** 把标准舞台的三个产出者登记好；用例只需再动消费节点的 args */
    private void stubSubkeyStage() {
        stubToolResultKeys("tbl_create", RiskLevel.LOW,
                List.of("id", "tableName", "columnCount", "message", "skipped", "warnings", "改表须知"));
        stubToolKeysUnregistered("tbl_raw", RiskLevel.LOW);
        stubBuiltinTool("tbl_dyn", RiskLevel.LOW);
        // 🔴 形状不可知那一档<b>照样登记键名</b>：若这里也留空，「V35 对 dyn_out 沉默」
        //    就能被「未登记」解释掉，shapeKnown 那道闸等于没测
        when(toolStubs.get("tbl_dyn").getDeclaredResultKeys()).thenReturn(List.of("id", "rows"));
        when(toolStubs.get("tbl_dyn").isDeclaredResultShapeKnown()).thenReturn(false);
        stubBuiltinTool("tbl_structure", RiskLevel.READ_ONLY);
    }

    /**
     * 🔴 命中侧：定义 900217（2026-10-18，M4 第四轮 s1 实录）的真实形状 ——
     * 建表结果<b>扁平</b>（{@code id} 在顶层），规划器却给下游写了 {@code {{$.create_out.model.id}}}，
     * 把入参那层 {@code args.model} 的嵌套镜像到了出参上。
     *
     * <p>那次的表现是执行 217 FAILED，报错「工具(datamodel_structure)的参数不合法：
     * id 必须是数字，收到的是：」—— 冒号后面空的。取不到值时
     * {@code StatePathNavigator} 刻意返回 null、{@code StateTemplateResolver} 再把它落成空串，
     * 一路没有任何一处会说「你那个 {@code .model} 是编的」。
     * 而发布前体检全绿，因为 {@code PLACEHOLDER_ROOT} 只抓到第一段 {@code create_out}，那一段确实有人产出。
     */
    @Test
    void validate_toolArgSubkeyNotDeclared_rejected() throws Exception {
        stubSubkeyStage();
        AgentValidateResult r = validate(subkeyStageDsl(
                "{\"id\":\"{{$.create_out.model.id}}\",\"note\":\"{{$.raw_out.whatever}}\"}"));

        List<String> hits = subkeyErrors(r);
        assertEquals(1, hits.size(),
                "写坏的子键该恰好报一条 V35，多了就是同一个根因说了几遍：" + r.getErrors());
        String msg = hits.get(0);
        assertTrue(msg.contains("节点(id=read_tbl)"), "文案要点名是哪个节点在取子键：" + msg);
        assertTrue(msg.contains("的参数 id "), "要点名是哪一格参数，模型才知道改哪儿：" + msg);
        assertTrue(msg.contains("create_out") && msg.contains("model"),
                "要把写错的那段路径原样印出来，模型才认得出自己写的是哪一个：" + msg);
        assertTrue(msg.contains("id / tableName / columnCount"),
                "出路必须是「正确键名的清单」而不是一句「写错了」—— 光说没有 .model 这一层，"
                        + "模型下一轮只会换一个别的猜法（同 ⑯/㉔ 两节那条纪律）：" + msg);
        assertTrue(msg.contains("安静地变成空串"),
                "要说清运行期的真实后果，否则作者会以为「取不到就原样传下去」、"
                        + "从而以为下游能看出这是个占位符：" + msg);
        assertTrue(hits.stream().noneMatch(e -> e.contains("note")),
                "同一份 DSL 里未登记键的那一格必须沉默（阳性对照见下一条）：" + hits);
        assertTrue(r.getWarnings().stream().noneMatch(w -> w.contains(SUBKEY_HINT)),
                "这条必须是 error：降到 warning 就等于放一张「一运行就炸、还指不出炸在哪」的草稿走到用户面前："
                        + r.getWarnings());
    }

    /**
     * 🔴 反向对照（阳性）：把 {@code .model} 那一层去掉，整份体检就该干净通过
     *
     * <p>钉的是 V35 给出的出路真能走通 —— {@code {{$.create_out.id}}} 正是规则头那段
     * 「结果对象本身就是扁平的」要教模型写的那个形状。
     */
    @Test
    void validate_toolArgSubkeyDeclared_passed() throws Exception {
        stubSubkeyStage();
        AgentValidateResult r = validate(subkeyStageDsl(
                "{\"id\":\"{{$.create_out.id}}\",\"note\":\"{{$.raw_out.whatever}}\"}"));

        assertTrue(subkeyErrors(r).isEmpty(), "已登记的子键是正常形状，V35 该闭嘴：" + r.getErrors());
        assertTrue(r.isPassed(), "文案给的出路必须走得通：" + r.toFeedback());
        assertTrue(r.getWarnings().isEmpty(), r.getWarnings().toString());
    }

    /**
     * 豁免 ①：产出者<b>未登记</b>顶层键 ⟹ 沉默（宁可漏报，不可误报）
     *
     * <p>这一档是 V35 唯一敢静默的正当理由：{@code resultKeys()} 返回空的意思是
     * 「平台还没审到这个工具」，不是「它不回对象」。拿「不知道」当「没有」去报 error，
     * 会把一份本来能跑的流程拦在体检外，而用户没法证明平台说错了。
     */
    @Test
    void validate_toolArgSubkeyUnregisteredProducer_exempt() throws Exception {
        stubSubkeyStage();
        AgentValidateResult r = validate(subkeyStageDsl(
                "{\"note\":\"{{$.raw_out.anything.at.all}}\",\"id\":\"{{$.create_out.model.id}}\"}"));

        assertTrue(subkeyErrors(r).stream().noneMatch(e -> e.contains("note")),
                "未登记键的工具不该被判：" + subkeyErrors(r));
        assertEquals(1, subkeyErrors(r).size(),
                "阳性对照：规则确实在跑，只是豁免了未登记的那一格 —— 若这里是 0，"
                        + "上面那句「沉默」就没意义了：" + r.getErrors());
        assertTrue(subkeyErrors(r).get(0).contains("的参数 id "), subkeyErrors(r).toString());
    }

    /**
     * 豁免 ②：产出者<b>形状不可知</b>（{@code resultShapeKnown()=false}，即 {@code source=DATA_INTERFACE}
     * 那类结果随接口而定的工具）⟹ 沉默
     *
     * <p>🔴 桩里<b>照样登记了键名</b>（见 {@link #stubSubkeyStage}）：若那一格也留空，
     * 本条就会被「未登记」那道闸解释掉，{@code shapeKnown} 这一档等于没测 ——
     * 而它恰恰是「不知道回什么」与「知道但不回对象」两件事里更容易被混掉的那一件。
     */
    @Test
    void validate_toolArgSubkeyShapeUnknownProducer_exempt() throws Exception {
        stubSubkeyStage();
        AgentValidateResult r = validate(subkeyStageDsl(
                "{\"v\":\"{{$.dyn_out.id}}\",\"id\":\"{{$.create_out.model.id}}\"}"));

        assertTrue(subkeyErrors(r).stream().noneMatch(e -> e.contains("的参数 v ")),
                "形状不可知就无从判起，报出来就是拿「不知道」当「没有」：" + subkeyErrors(r));
        assertEquals(1, subkeyErrors(r).size(), "阳性对照：规则确实在跑：" + r.getErrors());
    }

    /**
     * 豁免 ③：同一个变量有<b>多于一个</b>产出者 ⟹ 沉默
     *
     * <p>那时无法确定形状（两个工具各自回什么），猜就是误报。
     * 与 V18/V24/V26 共用 {@code collectProducers} 那一份口径，故本条不另立判据。
     */
    @Test
    void validate_toolArgSubkeyMultipleProducers_exempt() throws Exception {
        stubSubkeyStage();
        stubToolResultKeys("tbl_create2", RiskLevel.LOW, List.of("id", "tableName"));
        AgentValidateResult r = validate("""
                {"id":"wf",
                 "variables":[{"key":"input","type":"object"},{"key":"create_out","type":"object"},
                              {"key":"create2_out","type":"object"},{"key":"struct_out","type":"object"}],
                 "nodes":[
                   {"id":"p1","type":"tool","config":{"toolId":"tbl_create","args":{},"outputVar":"create_out"}},
                   {"id":"p2","type":"tool","config":{"toolId":"tbl_raw","args":{},"outputVar":"create_out"}},
                   {"id":"p3","type":"tool","config":{"toolId":"tbl_create2","args":{},"outputVar":"create2_out"}},
                   {"id":"read_tbl","type":"tool","config":{"toolId":"tbl_structure",
                     "args":{"id":"{{$.create_out.model.id}}","x":"{{$.create2_out.model.id}}"},
                     "outputVar":"struct_out"}}
                 ],
                 "edges":[{"from":"START","to":"p1"},{"from":"p1","to":"p2"},{"from":"p2","to":"p3"},
                          {"from":"p3","to":"read_tbl"},{"from":"read_tbl","to":"END"}]}
                """);

        assertTrue(subkeyErrors(r).stream().noneMatch(e -> e.contains("的参数 id ")),
                "两个节点都写 create_out 时形状无从确定，V35 该让开：" + subkeyErrors(r));
        assertEquals(1, subkeyErrors(r).size(), "阳性对照：单一产出者那一格照样被点名：" + r.getErrors());
        assertTrue(subkeyErrors(r).get(0).contains("的参数 x "), subkeyErrors(r).toString());
    }

    /**
     * 豁免 ④：产出者<b>不是 tool 节点</b>（{@code llm} / {@code script} 之流）⟹ 沉默
     *
     * <p>那些节点写进 state 的东西没有「工具代码里声明的顶层键」这个可证明的事实可依，
     * 而 {@code llm} 出的 JSON 形状本来就是运行期才知道的。
     */
    @Test
    void validate_toolArgSubkeyProducerNotTool_exempt() throws Exception {
        stubSubkeyStage();
        AgentValidateResult r = validate("""
                {"id":"wf",
                 "variables":[{"key":"input","type":"object"},{"key":"create_out","type":"object"},
                              {"key":"create2_out","type":"object"},{"key":"struct_out","type":"object"}],
                 "nodes":[
                   {"id":"gen","type":"llm","config":{"prompt":"输出一段 JSON","outputVar":"create_out"}},
                   {"id":"p3","type":"tool","config":{"toolId":"tbl_create","args":{},"outputVar":"create2_out"}},
                   {"id":"read_tbl","type":"tool","config":{"toolId":"tbl_structure",
                     "args":{"id":"{{$.create_out.model.id}}","x":"{{$.create2_out.model.id}}"},
                     "outputVar":"struct_out"}}
                 ],
                 "edges":[{"from":"START","to":"gen"},{"from":"gen","to":"p3"},
                          {"from":"p3","to":"read_tbl"},{"from":"read_tbl","to":"END"}]}
                """);

        assertTrue(subkeyErrors(r).stream().noneMatch(e -> e.contains("的参数 id ")),
                "llm 写进 state 的形状静态看不见，V35 该让开：" + subkeyErrors(r));
        assertEquals(1, subkeyErrors(r).size(), "阳性对照：tool 产出者那一格照样被点名：" + r.getErrors());
        assertTrue(subkeyErrors(r).get(0).contains("的参数 x "), subkeyErrors(r).toString());
    }

    /**
     * 豁免 ⑤：占位符<b>嵌在 args 的对象/数组里</b> ⟹ 沉默
     *
     * <p>{@code ToolNodeExecutor} 只对 args 的<b>顶层 String 值</b>做模板替换，嵌套的原样当字面串传下去。
     * 扫得更宽就会去判一些运行期压根不解析的字符串，报了就是误报 ——
     * 这也是本条与已被否决的 N17（「任意深度嵌套占位符加强制点」）的分界。
     */
    @Test
    void validate_toolArgSubkeyNestedInObjectArg_exempt() throws Exception {
        stubSubkeyStage();
        AgentValidateResult r = validate(subkeyStageDsl(
                "{\"model\":{\"id\":\"{{$.create_out.model.id}}\"},\"id\":\"{{$.create_out.model.id}}\"}"));

        List<String> hits = subkeyErrors(r);
        assertEquals(1, hits.size(),
                "同一串坏路径写在两处，只有顶层那一处运行期真会解析，故只报一条：" + r.getErrors());
        assertTrue(hits.get(0).contains("的参数 id "),
                "报的必须是顶层那格，不是嵌套那格：" + hits.get(0));
    }

    /**
     * 豁免 ⑥：产出者<b>压根认不出</b>（工具键打错）⟹ 沉默，由 V1 说
     *
     * <p>同一个坏键再叠一条「它的顶层键里没有 model」是错的 —— 平台根本不知道那个工具回什么。
     */
    @Test
    void validate_toolArgSubkeyUnknownProducerTool_exempt() throws Exception {
        stubSubkeyStage();
        AgentValidateResult r = validate("""
                {"id":"wf",
                 "variables":[{"key":"input","type":"object"},{"key":"create_out","type":"object"},
                              {"key":"struct_out","type":"object"}],
                 "nodes":[
                   {"id":"p1","type":"tool","config":{"toolId":"tbl_ghost","args":{},"outputVar":"create_out"}},
                   {"id":"read_tbl","type":"tool","config":{"toolId":"tbl_structure",
                     "args":{"id":"{{$.create_out.model.id}}"},"outputVar":"struct_out"}}
                 ],
                 "edges":[{"from":"START","to":"p1"},{"from":"p1","to":"read_tbl"},
                          {"from":"read_tbl","to":"END"}]}
                """);

        assertTrue(subkeyErrors(r).isEmpty(), "认不出的工具归 V1 说，V35 一律沉默：" + r.getErrors());
        assertTrue(r.toFeedback().contains("tbl_ghost"),
                "阳性对照：这条 DSL 确实坏，只是归别人说 —— 若这里也是空，"
                        + "上面那句「V35 沉默」就可能是整条规则没跑（假绿）：" + r.toFeedback());
    }

    /**
     * 判据只看<b>第一段</b>子键：下标段与中文键都不该被误判
     *
     * <p>{@code {{$.create_out.warnings[0]}}} 判的是 {@code warnings} 在不在（下标越界是运行期的另一件事，
     * 混在一起报会把人引向错的修法）；{@code 改表须知} 是 {@code datamodel_structure} 真有的中文键，
     * 用来钉住正则的字符类没把非 ASCII 挡在外面。
     */
    @Test
    void validate_toolArgSubkeyIndexSegmentAndChineseKey_passed() throws Exception {
        stubSubkeyStage();
        AgentValidateResult r = validate(subkeyStageDsl(
                "{\"w\":\"{{$.create_out.warnings[0]}}\",\"c\":\"{{$.create_out.改表须知}}\","
                        + "\"n\":\"{{$.create_out.tableName}}\"}"));

        assertTrue(subkeyErrors(r).isEmpty(), "三个都是已登记的键，V35 该闭嘴：" + r.getErrors());
        assertTrue(r.isPassed(), r.toFeedback());
    }

}
