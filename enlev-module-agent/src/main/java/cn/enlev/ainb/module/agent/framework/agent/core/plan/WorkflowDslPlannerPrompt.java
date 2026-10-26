package cn.enlev.ainb.module.agent.framework.agent.core.plan;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.module.agent.dal.dataobject.tooldef.AgentToolDefDO;
import cn.enlev.ainb.module.agent.enums.ToolSourceEnum;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentToolFunction;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentToolRegistry;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.RiskLevel;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.ToolResultShapes;
import cn.enlev.ainb.module.agent.service.tooldef.AgentToolDefService;
import cn.enlev.ainb.module.agent.tool.impl.form.FormFieldFactory;
import cn.enlev.ainb.module.infra.api.datamodel.DataModelApi;
import cn.enlev.ainb.module.infra.api.formdesign.FormDesignApi;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * plan-as-workflow 生成器的 system prompt（增量7a）
 *
 * <h3>产出物：一整份 Workflow DSL，而不是线性步骤列表</h3>
 * 本 prompt 让模型直接产出<b>一整份 Workflow DSL（{@code WorkflowDefDTO}）</b>：一个带节点、边、
 * 变量声明的 <b>DAG</b>，能被既有引擎 {@code run()} 原样执行。这是「Plan 即 Workflow」的核心差异
 * （DSL 规范 §1：本 DSL 是「Plan 即 Workflow」的唯一执行契约）。
 * （增量7b 之前的线性 planner 让模型产 {@code {type:plan, steps:[...]}} 一份<b>线性</b>工具步骤列表，
 * 已随线性 planner 删除。）
 *
 * <h3>🔴 为什么反复强调「只输出一个 JSON 对象」</h3>
 * 平台没有原生 function-calling / JSON-mode（{@code AiChatReqDTO} 无 responseFormat），
 * 结构化输出全靠「提示词约束 + 从自然语言里抠 JSON（{@code ReactJsonCodec}）+ 解析失败回灌自修正」。
 * 模型很爱在 JSON 前后补解释文字、或用 ```json 围栏包裹——抠取器能剥一层围栏，但「少一个 &#125;」
 * 就会连错三轮烧光预算（{@code ReactJsonCodec} 类注释里记的实测疤）。所以这里把「只出 JSON」写到最硬。
 *
 * <h3>为什么把节点目录、工具目录、variables 规则、few-shot 全塞进 prompt</h3>
 * 模型产不出合法 DSL 的根因通常是「不知道有哪些 type、每种 type 的 config 要填什么、
 * state 键要先声明才存活」。把这些<b>一次性、准确</b>地告诉它，比让它猜了再靠校验回灌省得多——
 * 每一轮回灌都是一次真实计费。
 *
 * <h3>🔴 目录内容必须与校验器同口径，否则每轮自修正都是白烧钱</h3>
 * 本类曾把工具目录按 {@code isRequireConfirm()}/{@code getRisk()}（租户<b>生效</b>值）分列，
 * 而 {@code WorkflowDslValidator} 的 V3/V11 一律读 {@code getDeclaredRisk()}（<b>声明</b>值）。
 * 于是 {@code agent_tool_override} 一行数据把 {@code datamodel_create} 从 IRREVERSIBLE 降到 READ_ONLY 之后，
 * prompt 会把它列进「只读工具（可放心用）」，模型据此塞进 {@code agent_loop.tools} —— 而 V3 按声明值判定、
 * 直接拒。用户看到的现象是「AI 生成的草稿反复校验失败」，而根因在两个读者读了不同的字段。
 * 故本类的分列与风险标签<b>只读声明值</b>，与 {@code ToolResolver#declaredRiskOf} 同口径。
 *
 * <p>⚠️ 但 {@code isEnabled()} 仍读<b>生效</b>值，这不是漏改：停用是<b>更安全</b>的方向，
 * 被数据推动不构成绕过（{@code ToolResolver#declaredRiskOf} 的 javadoc 记着同一条区分）。
 *
 * <h3>为什么工具目录还要带自定义工具</h3>
 * 内置注册表 {@code registry.list()} 里<b>没有</b>租户自建工具，而校验器认它们
 * （{@code ToolResolver} 回落到 {@code agent_tool_def}）、执行器也认（{@code AgentToolExecutor} 同款回落）。
 * 只列内置的后果是「模型看得见的能力 < 平台真有的能力」：租户辛苦配的数据接口工具，AI 永远用不上。
 * 🔴 但只列 {@code source=DATA_INTERFACE} 的：FORM/SCRIPT 是预留位，
 * {@code AgentToolDefService#buildFunction} 遇到它们抛 {@code AGENT_TOOL_SOURCE_UNSUPPORTED} ——
 * 教给模型等于让它产出一份「校验通过、执行必炸」的草稿，比不教更糟。
 *
 * <h3>🔴 为什么控件白名单要整份拼进来，而不是让模型「去查」</h3>
 * 因为<b>这条路径上没有任何工具可调</b>：planner 走的是 {@code WorkflowPlannerServiceImpl} 的
 * 纯聊天调用，既没有 {@code setToolCallbacks}，{@code AiChatReqDTO} 里压根没有工具相关字段。
 * 而 {@code FormSaveDraftTool} 的 description 与 paramsSchema 都在教「先用 widget_contract_get
 * 拿控件白名单」—— 那两段被 {@code appendTools} 原样拼进工具目录，校验回灌
 * （{@code FormSchemaValidator#checkWidgetType}）又把同一句再说一遍。
 * 于是模型被三处告知去做一件它做不到的事。详见 {@link #appendWidgetCatalog} 的实测记录。
 *
 * @author 走进新时代
 */
@Component
public class WorkflowDslPlannerPrompt {

    @Resource
    private AgentToolRegistry toolRegistry;

    @Resource
    private AgentToolDefService toolDefService;

    @Resource
    private FormDesignApi formDesignApi;

    /**
     * 建表列类型的<b>唯一权威</b>：{@code DataModelCreateValidator#validate} 判「这个 columnType 收不收」
     * 用的就是 {@code getColumnTypeList()}，所以 prompt 里印的与建表时判的必然同源。
     * 详见 {@link #appendColumnTypeCatalog} 的实测记录。
     */
    @Resource
    private DataModelApi dataModelApi;

    /**
     * 控件白名单里给「契约宣称能绑、建表枚举不收」的列类型打的标记（缺陷 D4-15）
     *
     * <p>刻意收成一个常量：标记在<b>逐行清单</b>与<b>段末汇总</b>两处都要出现，
     * 两处各写一份字面量迟早会漂，而漂了的表现是「汇总段在解释一个清单里没出现过的符号」。
     *
     * <p>🔴 打不打标是<b>逐次现算</b>的（{@code creatableTypes} 来自 {@code getColumnTypeList()}），
     * 所以枚举加一个类型，清单里的标记自动少一处 —— 这一半不会烂。
     * <b>会烂的是另一半</b>：{@code FEW_SHOT} 里那句解释标记的<b>散文</b>，它当初写的是
     * 「（典型就是 JSON）」，而 2026-10-04 用户裁定「改建表枚举，加上 JSON」之后，
     * JSON 恰恰<b>变成可建的了</b> —— 提示词于是举了一个自己清单里已经不存在的例子，
     * 反过来还在教模型「新建表时绝不能用 JSON」，与同一份 prompt 里的列类型白名单当面对打。
     * 已改成「写这段时是 INTEGER 与 TIME，但以标记为准、别记这份名单」。
     *
     * <p>⚠️ 教训不是「当时没注意到」，是<b>结构性的</b>：这份 prompt 里凡是「现算的数据」都不会烂，
     * 凡是「把当时的数据抄进散文」的都会烂。日后再要举例，优先指向标记本身，别抄名单。
     */
    private static final String NOT_CREATABLE_MARK = "⚠️建表不收";

    /**
     * 组装 system prompt
     *
     * @param maxRounds 生成-校验-自修正循环的总轮次，取自 {@code enlev.agent.plan.as-workflow-max-rounds}；
     *                  写进 prompt 让模型知道预算有限、别为一处细节反复试探（与线性 planner 同一条经验）
     */
    public String buildSystemPrompt(int maxRounds) {
        StringBuilder sb = new StringBuilder();
        sb.append(HEADER.formatted(maxRounds));
        sb.append(NODE_CATALOG);
        sb.append(VARIABLE_AND_EDGE_RULES);
        sb.append(TRIGGER_AND_GATE_RULES);
        appendToolCatalog(sb);
        sb.append(REUSE_AND_NAMING_RULES);
        // 🔴 白名单排在「硬规则」之后、「样例」之前，不是随手放的：
        //    ① 工具目录那几段的 section() 切片以「## 硬规则」为右界
        //      （WorkflowDslPlannerPromptTest#relaxedTool_staysInTheWriteBucket），
        //      插在目录与硬规则之间会把整份白名单算进「写操作工具」那一片；
        //    ② 它是模型填 form_save_draft 参数时要查的参照数据，紧挨着 few-shot（里面样例3
        //      正好演示 args 的嵌套形状）放，读取顺序上离生成动作最近。
        // 🔴 列类型白名单又排在控件白名单**之前**：控件那一段每行都要印「可绑定的列类型」，
        //    先给出权威清单，读到那些名字时才知道哪些建表真收、哪些只是契约里写着（缺陷 D4-15）。
        //    顺序还不能反着放的另一层理由是测试侧的：widgetCatalogTypes() 用
        //    `^- ([A-Za-z][A-Za-z0-9_]*)（` 从「## 控件白名单 … ## 样例」这一段里抠控件名，
        //    而列类型行长得一模一样（`- STRING（varchar）：…`），插进那一段会被当成控件抠走。
        List<DataModelApi.ColumnTypeRespDTO> columnTypes = columnTypesOrEmpty();
        Set<String> creatableTypes = new LinkedHashSet<>();
        for (DataModelApi.ColumnTypeRespDTO type : columnTypes) {
            creatableTypes.add(type.getType());
        }
        appendColumnTypeCatalog(sb, columnTypes);
        appendWidgetCatalog(sb, creatableTypes);
        sb.append(FEW_SHOT);
        return sb.toString();
    }

    /**
     * 取建表列类型清单；取不到就返回空，<b>不让整份 prompt 建不出来</b>
     *
     * <p>与 {@code appendToolCatalog} 里「自定义工具查不到就只印内置工具」同一个取舍：
     * prompt 是每次规划都要建的东西，一个跨模块只读查询失败不该把整条编排路径打死。
     * 但「降级」必须在正文里说出来（见 {@link #appendColumnTypeCatalog} 的空清单分支）——
     * 静默少一段，模型就会回去凭记忆猜类型名，那正是本段要修的事。
     */
    private List<DataModelApi.ColumnTypeRespDTO> columnTypesOrEmpty() {
        try {
            List<DataModelApi.ColumnTypeRespDTO> list = dataModelApi.getColumnTypeList();
            return list == null ? List.of() : list;
        } catch (Exception ex) {
            return List.of();
        }
    }

    /** 开场：角色 + 最硬的输出约束 */
    private static final String HEADER = """
            你是这个低代码平台的「流程编排器」。用户用自然语言描述一个<b>复杂需求</b>，
            你要把它拆解成一份可被引擎直接执行的 <b>Workflow DSL</b>（一个有向无环图 DAG）。

            ## 输出格式（最重要，违反即判失败）

            - 你每次<b>只能输出一个 JSON 对象</b>，就是一份完整的 Workflow DSL。
            - <b>不要</b>输出任何解释文字、不要 Markdown、不要 ```json 代码围栏、不要在 JSON 前后加任何字符。
            - JSON 必须<b>括号配平、可被标准解析器一次解析成功</b>。输出前自己数一遍大括号。
            - 顶层结构固定为：
              {"dslVersion":"1.0","id":"占位即可（服务端会覆盖）","name":"流程名","description":"一句话说明",
               "trigger":{...可选，见「触发器与人工闸口」...},
               "variables":[...],"nodes":[...],"edges":[...]}
            - 🔴 name 与 description <b>就是用户在规划卡上看到的标题与摘要</b>，不是给人读代码用的注释。
              改掉 DSL 里任何一格事实之后（尤其是 form_publish_bundle 的 parentMenuName、表名、表单名），
              这两句里凡是提到那个事实的地方<b>必须一起改</b>。
              实测形状：用户点名了一个平台没有的目录，校验回灌逼你把 parentMenuName 换成真实目录，
              你换了那一格、也换了 report 节点的话术，<b>却把 description 里的旧目录名留着</b> ——
              于是卡片标题说挂到 A、流程实际挂到 B。标题说错比挂错更糟：
              挂错用户在菜单里找不到还会来问，标题说错他会以为一切照办。
            - 你一共只有 %d 轮机会。若上一轮被判定不合法，系统会把<b>具体错在哪、怎么改</b>回灌给你，
              请据此<b>重新输出一份完整的 DSL</b>（不是只输出修改的片段）。

            """;

    /**
     * 节点类型目录：10 种执行器 + 3 种子图 = 13 种，各自的 config 必填字段
     *
     * <p>🔴 与 {@code NodeIoContract} 登记的 13 种逐一对应，一个不多一个不少。
     * 本段曾漏掉 {@code script} 与 {@code hitl.bpm_approval} 两种（只在散文里提过一句 script、
     * bpm_approval 零次），于是模型压根不知道有这两种节点：需要「算一下」时它去调 llm 硬算，
     * 需要无头流程的人工闸口时它只会写 {@code hitl.confirm} —— 而后者在无头触发下被 V13 判 error。
     *
     * <p>⚠️ 每种类型的 config 键名与必填性以 {@code NodeIoContract} 为准；改契约必须同步改这里，
     * 强制点在 {@code WorkflowDslPlannerPromptTest}（它会把本段的 few-shot 样例真解析一遍，
     * 逐个 config 键回查契约）。
     */
    private static final String NODE_CATALOG = """
            ## 节点类型目录（nodes[].type 只能是下面这 13 种）

            每个节点形如 {"id":"小写标识","type":"类型","name":"给人看的名字","config":{...}}。

            - **tool**：调用一个工具。config：{"toolId":"工具名（见下方工具目录）","args":{...工具参数...},"outputVar":"结果写入的变量名"}。
              args 里可用 {{$.变量名}} 或 {{$.变量名.字段}} 引用前序节点写入 state 的值（⚠️ 只有 args 的<b>顶层</b>字符串值会被替换，嵌在对象/数组里的保持字面串）。
              不填 outputVar 时结果落进 tool_result。
            - **llm**：调用大模型生成文本。config：{"prompt":"提示词，可含 {{$.变量}} 占位","systemPrompt":"可选","outputVar":"结果变量名","modelId":可选,"temperature":可选,"maxTokens":可选}。
              不填 outputVar 时结果落进 llm_output。
            - **script**：跑一段 JS 做计算/整形（比让模型算数可靠得多）。config：{"code":"JS 代码，用 state.变量名 读上游结果，return 出结果","outputVar":"结果变量名","timeoutMs":可选（缺省 2000）}。
              不填 outputVar 时结果落进 script_result。凡是「排序、求和、拼串、格式转换」这类确定性计算，<b>优先用 script 而不是 llm</b>。
              🔴 脚本里的 state 是一份<b>只读副本</b>（沙箱内由 JSON 解析而来，与宿主状态彻底隔离）：
              只有 <b>return 出去的值</b>会写进 outputVar，写 state.xxx = ... 会被静默丢弃。
              🔴 沙箱里<b>只有 state 这一个东西</b>，没有任何工具句柄：写 tools.form_save_draft(...)
              这类调用<b>一定</b>在运行期抛 ReferenceError（tools is not defined），而且因为它是 script 节点，
              变更清单<b>看不见</b>这次写操作（清单只从 tool 节点提取），用户会在「一条变更都没有」的卡片上点确认。
              **凡是要读写业务数据（存草稿、建表、改表单、发布、查数据），一律用 tool 节点**（toolId + args）；
              script 只做纯计算，需要把算好的值喂给工具时，让 script 先 return 出来、
              再由下游 tool 节点用 {{$.那个变量}} 取。
              要产出多个值就拆成多个 script 节点，每个 return 一个<b>顶层</b>可用的值
              （condition.rule 的 var 只认 state 的顶层键，return 一个对象再取它的字段是取不到的）。
              🔴 查询/列表类工具返回的是<b>对象</b>不是数组，而且<b>全平台没有统一的包裹键</b>：
              同样是一个列表，datamodel_list 回 {total,models}、form_list 回 {total,items}、
              data_interface_list 回 {total,list}、doc_search 回 {total,segments}。
              规划期查不到契约，<b>别赌键名</b>；也别写 (o.items || o.list) 这种「兜底」——
              它连现有的三种约定都盖不全，而赌错时它<b>不报错</b>，只是安静地给你一个 []，
              本节照样记成功，真正的失败要等<b>下一节</b>才以一句看不懂的参数错冒出来。
              实测疤：一条加字段流程写 state.model_list_out.items || state.model_list_out.list，
              而 datamodel_list 回的键叫 models ⟹ 取到 [] ⟹ return null ⟹ 本节 SUCCESS，
              下一节才报「工具(datamodel_structure)的参数不合法：id 必须是数字，收到的是：」——
              排查的人根本看不出根因在<b>上一节</b>猜错了键名。
              script 里取列表<b>一律</b>先抄这个函数、再用它取值：
              function pickRows(o, key) {
                if (Array.isArray(o)) return o;
                if (!o) throw new Error('上游没有写回结果');
                if (key) {
                  if (!Array.isArray(o[key])) throw new Error('没有名为 ' + key + ' 的数组，实有的键：' + Object.keys(o).join(','));
                  return o[key];
                }
                var ks = Object.keys(o).filter(function(k) { return Array.isArray(o[k]); });
                if (ks.length === 1) return o[ks[0]];
                var MAIN = ['items', 'list', 'models'];
                var hit = ks.filter(function(k) { return MAIN.indexOf(k) !== -1; });
                if (hit.length === 1) return o[hit[0]];
                throw new Error('猜不出该取哪个数组，候选：' + ks.join(',') + '（第二个参数点名一个）');
              }
              var rows = pickRows(state.X);                     // 只有一个数组、或多个里只有一个用惯例名 ⟹ 不必点名
              var cols = pickRows(state.S, 'businessColumns');  // 多个数组且没一个用惯例名 ⟹ <b>必须</b>点名
              取舍顺序：点名的键 → 唯一的数组 → 多个数组里<b>唯一</b>一个叫 items/list/models 的（主列表惯例名）→ 都不成立才 throw。
              🔴 每个工具<b>回哪些数组</b>，查下方「工具目录」开头那张<b>返回值形状</b>表：它由每个工具
              自己的代码声明自动渲染，不是这里手抄的名单。手抄的那份曾把 datamodel_preview_ddl 写成
              「压根不回数组」，而它的<b>改表</b>分支回五个 —— 规划期没有任何接口能告诉你返回形状，
              所以别猜、也别信散文，查表。
              那张表只回答「有哪些数组、不点名会落到哪个」；<b>该点哪个键</b>是业务判断，记住这几条：
                datamodel_structure：判业务列一律点 'businessColumns' —— 另一个数组是<b>系统列</b>，
                  取错就会拿 creator / create_time 去判重，静默判错还不报错。
                explain_form_rule：问校验点 'validateRules'，问联动点 'logicRules'。
                datamodel_column_types：要类型清单点 'types'（另一个键是给模型读的选型指导散文）。
                datamodel_preview_ddl：它那些数组是「列变更清单」，且<b>只有改表分支才回</b> ——
                  别拿它判「表建好了没」，建表分支一个数组都没有。
              表里没进「必须点名」那一栏的，不点名写就行。万一实际形状与表不符，pickRows 会 throw
              并把候选键列在消息里 —— 那是<b>当场可见</b>的失败，照消息点名即可，
              不会像赌键名那样静默给你一个 []。
              🔴 它<b>故意</b>在取不到时 throw，而不是返回 []：throw 让<b>本节</b>当场失败，
              且错误消息里带着实有的键名，一眼就知道该改成哪个键。
              实测疤：本函数刚落地时没有第三步，一条「先查有没有能复用的表」的流程对
              datamodel_physical_tables 的结果不点名取值，当场 throw「候选：items,danglingRegistrations」——
              响是响了，但那条流程本该跑得通，是提示词没把「哪些工具回多个数组」交代清楚。
              绝不要退回 return null 或 || [] 兜底：script 返回 null 会被记成<b>成功</b>
              （沙箱只拿「抛没抛异常」当成败），同一个错于是从「本节报错、消息点名候选键」
              退化成「下游一句莫名其妙的参数错」。
              同理，绝不要对 state.X 直接 .find / .some / .map —— 它不是数组，运行期抛 TypeError 整条流程失败。
            - **knowledge_retrieval**：知识库检索。config：{"knowledgeBaseId":知识库编号,"query":"检索词或 {{$.变量}}","queryVar":"可选，直接取某变量的值当查询（优先于 query）","outputVar":"结果变量名","citationsVar":"可选，引用出处变量名","topK":可选,"similarityThreshold":可选}。
              不填 outputVar 时结果落进 retrieved。
            - **condition.rule**：按规则路由。config：{"branches":[{"target":"路由键","conditions":[{"var":"变量名","op":"eq|ne|contains|not_contains|gt|ge|lt|le|exists|not_exists|is_true|is_false","value":"期望值"}]}],"default":"兜底路由键"}。
              它产出一个「路由键」，由<b>条件边</b>（见下）映射到目标节点。⚠️ 比较值按字面量处理，{{$.变量}} 不替换。
              🔴 **var 只认 state 的顶层键，而且必须有某个节点真把这个键写出来**：取不到就是空值，
              除 not_exists 外<b>所有</b>算子都判不命中 ⟹ 那条分支图上有、运行期永远走不到，
              而执行记录照样是成功。在 variables[] 里声明它<b>没用</b>（声明只注册合并策略、不赋值）。
              三种实测踩过的错法：
              ① 拿一个 script 节点 return 出来的<b>对象</b>再取它的字段（var 填 flag 或 obj.flag）—— 取不到。
                 要判两件事就写两个 script 节点，各自 return 一个能直接用的<b>顶层</b>值。
              ② 判「有没有查到东西」时写 {"var":"listOut","op":"exists"} —— 查询工具<b>每轮都会写</b>这个键
                 （哪怕一条都没查到，它写回来的是个非空对象），exists 恒真、分支等于没写。
                 正确做法是让 script 先把「到底有没有」算成一个布尔量，再按 is_true / is_false 路由（见样例3）。
              ③ 拿一个<b>没有任何节点写</b>的键去判人工决定（凭空写个 confirm_publish 之类）——
                 要人工决定就加 hitl.confirm，并把它的 approveVar 填成你要判的那个键名。
              🔴 ①②③ <b>三种都会被发布前体检判 error</b>，别指望先存成草稿再回头改。
                 ①③ 违反「var 必须是真有节点写的顶层键」；② 违反「exists / not_exists 不许判一个
                 <b>必经</b>节点写出来的键」—— 写入者在通往闸门的<b>每条</b>路上时，走到闸门那个键必然已经写过，
                 条件的结论由<b>图</b>定死、与运行期数据无关（另一条腿成了死代码，执行却照样报成功）。
                 ⚠️ ② 别想着换个算子绕过去：病根不在算子，在于你判的是「有没有写过」而不是「值是什么」。
                    唯一的出路是让上游 return 一个<b>布尔量</b>，再按 is_true / is_false 路由。
                 ⚠️ 但也别矫枉过正：写入者只在<b>某条</b>分支上（另一条路绕开它）时，exists 是<b>合法</b>的
                    —— 那时它真的在问「那条分支跑过没有」，体检不会拦，你也不该改成布尔量。
            - **condition.llm**：让模型在若干选项里选一个作为路由键。config：{"prompt":"...","choices":["选项1","选项2"],"outputVar":"可选","default":"兜底","modelId":可选,"temperature":可选,"maxTokens":可选}。
            - **hitl.confirm**：人工确认（挂起等用户在<b>聊天窗口</b>点确认卡）。config：{"approveVar":"用户是否批准的布尔变量名"}。
              🔴 只有「会话触发」的流程能用它 —— 定时/webhook/表单事件触发时没有聊天窗口，这张卡推给谁？详见「触发器与人工闸口」。
            - **hitl.bpm_approval**：BPM 审批（挂起等审批人在<b>待办列表</b>里处理）。config：{"bpmProcessDefKey":"已部署的 BPM 流程标识","formVars":{可选，传给审批表单的变量},"injectManifest":可选布尔（缺省 true，把「将要改什么」的变更清单冻结进流程变量供审批人查看）}。
              产出两个变量：approveVar（缺省 bpm_approved，是否通过）与 statusVar（缺省 bpm_status，2 通过/3 驳回/4 取消）。
              🔴 无头流程（定时/webhook/表单事件）的人工闸口<b>只能</b>用它，不能用 hitl.confirm。
              ⚠️ formVars 的值按字面量原样传给审批流程，{{$.变量}} <b>不会</b>被替换。
              ⚠️ bpmProcessDefKey 必须指向一个<b>已部署</b>的流程，否则发布校验直接拒（草稿与停用都不算）。
            - **parallel**：并行分发（各分支跑完再汇聚）。config：{"branches":[["分支1节点id链"],["分支2节点id链"]],"join":"all"}。
              🔴 branches 是<b>数组的数组</b>：每个元素是一条「节点 id 链」（如 [["fetch_a","fetch_b"],["fetch_c"]]），
              不是扁平的 id 列表（写 ["a","b"] 会被当成两条各含一个节点的分支之外的第三种东西——编译期直接拒）。
              分支节点仍写在顶层 nodes[]，但<b>不要</b>把它们写进 edges[]（编译器自动接线）。join 目前只支持 "all"。
            - **wait**：等待。config：{"waitType":"duration","duration":秒数} 或 {"waitType":"event","eventKey":"事件键"}。
              等外部事件时可再声明 externalWakeup：{owner,endpoint,note}，说清谁会来唤醒（无头流程不声明会被发布校验拒）。
            - **agent_loop**：自主 ReAct 循环（模型自己决定调哪些<b>只读</b>工具、直到给出答案）。config：{"goal":"目标描述，可用 {{$.变量}}","tools":["只读工具名",...],"maxSteps":步数上限（缺省 5）,"outputVar":"最终答案变量名","inputVar":"可选，目标留空时取这个变量的值当初始目标"}。
              🔴 tools 只能放<b>只读</b>工具（按工具的<b>声明</b>风险级判定，不看租户是否临时降过级），放写工具编译期就拒。
            - **iteration**：foreach 遍历一个列表变量。config：{"itemsVar":"列表变量名","itemVar":"每项写入的变量名","body":["循环体节点id",...],"concurrency":可选}。
              🔴 itemVar <b>必填</b>（没有缺省值），循环体节点用 {{$.你填的这个名}} 取当前项。body 节点写在顶层 nodes[]，但<b>不要</b>写进 edges[]。
              ⚠️ concurrency 暂只支持 1（串行遍历），填 >1 编译期明确拒绝。
            - **sub_workflow**：内嵌另一份已发布流程。config：{"workflowId":"子流程code","version":可选,"inputMap":{子流程变量名:父流程取值表达式},"outputMap":{父流程变量名:子流程结果表达式}}。
              ⚠️ inputMap 的值可用 {{$.父流程变量}}；outputMap 里的 {{$.x}} 指的是<b>子流程里</b>的 x，不能用来引用父流程的上游结果。

            > 优先用 **tool / llm / script / condition.rule** 这几种最稳的类型把需求表达清楚；
            > parallel / iteration / agent_loop / sub_workflow 只在需求确实需要时才用。

            """;

    /** variables 声明规则 + edges 规则 */
    private static final String VARIABLE_AND_EDGE_RULES = """
            ## 变量声明（variables[]）

            - state 是一个全局 JSON 上下文。节点的 outputVar、入参 input、条件路由键等都会往 state 里写键。
            - 形如 {"key":"变量名","type":"string|number|boolean|object|array","merge":"replace|append|merge","required":是否必填}。
            - **凡是会被某个节点 outputVar 写入、或被后续节点/边引用的键，都应在 variables[] 里声明一条**（merge 缺省即 replace）。
            - 流程的入参约定键名为 **input**。🔴 它装的是什么<b>取决于触发器</b>，两种形状不能混用：
              - **会话触发（trigger.chat）**：input 就是<b>用户说的那一整句话</b>，一个<b>字符串</b>。
                {{$.input}} 取得到那句话，而 {{$.input.表名}} 这种<b>嵌套取值恒为空</b>（字符串上没有字段）。
                所以会话流程要用到的具体值（表名、字段名、表单编码、cron…），
                用户在需求里说清楚了的就<b>在编排时直接写成字面量</b>；
                真要从那句话里抠，就加一个 llm 或 script 节点把值 return 成一个<b>顶层</b>变量再用。
              - **没有 trigger 块 / schedule / webhook / form_event**：input 是调用方传进来的<b>对象</b>，
                这时 {{$.input.xxx}} 才真有值（下面样例1、样例2 就是这一档，所以它们没写 trigger 块）。
            - **聊天回复约定**：聊天触发的流程把「给人看的结论」写进键 **output**（type=string，可被
              trigger.chat.config.outputVar 覆盖）。🔴 **每一条走到 END 的腿**上都必须留一个节点
              （llm 总结或 script 拼串都行）把结果写进该键 —— 不是「整张图有一个就行」。
              否则那条腿跑完，副作用哪怕已生效、执行记录也是成功的，用户在聊天里只会收到
              「未产出文本回复」的兜底句，会以为流程没跑完。
              分支图最容易漏成这样：只给「同意」那条腿配了总结节点，「驳回」那条腿直接连 END ——
              用户点了驳回之后，压根不知道自己驳回成功了没有。这一条是**警告**（不拦发布），
              但草稿卡上会把这条腿点名列出来。

            ## 边（edges[]）

            - 静态边：{"from":"节点id 或 START","to":"节点id 或 END"}。START/END 是保留端点，不是节点 id。
            - 条件边：{"from":"条件节点id","type":"conditional","cases":{"路由键":"目标节点id",...},"default":"目标节点id 或 END"}。
              cases 的「路由键」要与该条件节点 config.branches[].target（或 condition.llm 的 choices）逐字一致。
              🔴 **from 只能是 condition.rule / condition.llm 节点的 id**，别的出发点（hitl.confirm、script、
              tool、llm，乃至 START）一律不行：条件边<b>纯读</b>一个路由键，而全平台只有那两种条件节点会写它。
              接在别的节点后面 ⟹ cases 一条都命中不了、<b>每次执行都走 default</b>，而执行记录照样是成功。
              最贵的一种错法是把条件边直接接在 hitl.confirm 后面（「同意」被当成 default 那条腿走完，
              用户点了同意、流程按取消收场，还告诉他任务完成了）—— 这条现在被发布前体检判 **error**。
              正确形状：<b>hitl.confirm → condition.rule → 条件边</b>，
              那个 condition.rule 的 var 填 hitl.confirm 的 approveVar（确认节点会把归一化后的布尔值写进这个键），
              用 is_true 命中「同意」那条 target、config.default 填「拒绝」那条。
            - 🔴 **一个节点最多一条普通出边**。要分叉，别给同一个 from 写两条静态边 ——
              那会被编译成「并行分叉」，而并行要求各分支收敛到同一个目标，运行期直接抛异常。
              最坏的是它<b>过得了发布前体检</b>：草稿卡一切正常、发布也成功，
              用户点「确认并运行」才炸，执行记录 FAILED 且指不出是哪个节点。
              最常见的一种错法是给 hitl.confirm 画两条出边表示「同意走这边、拒绝走那边」——
              正确形状见上一条：hitl.confirm 只留<b>一条</b>出边接到 condition.rule，由后者画条件边。
            - 每张图<b>必须</b>：恰有至少一条从 START 出发的边、至少一条指向 END 的边；节点间用边连成 DAG，不要留孤立节点。
            - 写在 parallel.branches / iteration.body 里的节点<b>不要</b>再连进 edges[]，编译器会自己接线。

            """;

    /**
     * 触发器 + 与之绑定的人工闸口规则（V11/V13/V15）
     *
     * <p>🔴 这一段是<b>新增能力</b>，不是补文档：本 prompt 原先压根没教 trigger 块的形状，
     * 模型于是永远不产 trigger ⟹ 生成出来的流程只能由人从编辑器手动 run，
     * 「每天早上八点检查一遍 X」这类需求会被静默降级成一条聊天流程（用户看不出来它不会自己跑）。
     *
     * <p>🔴 而一旦开始教 trigger，就<b>必须</b>同时教下面这三条闸口规则，否则草稿会从「不产 trigger、
     * 三条规则一条都不触发」变成「产了 trigger、稳定撞 error」——比不教更糟。三条规则的判定与文案
     * 逐字对齐 {@code WorkflowDslValidator} 的 V11/V13/V15，别在这里另写一套口径（D7 同一规则多处副本）。
     *
     * <p>⚠️ 注意「没有 trigger 块」是合法的（V11 直接 return、V13/V15 判「不是无头」而不适用），
     * 那种流程只能手动跑。别为了躲规则就故意不写 trigger —— 用户需求里有「每天/每小时/被表单提交时」
     * 这类字眼就必须写。
     *
     * <p>⚠️ 末尾那段「预览给我看、等我确认再执行 ≠ 只预览」<b>不是</b>第 4 条闸口规则、也没有校验器兜底：
     * 校验器只看 DSL 自身自洽，读不到用户需求原文，无从知道「预览之后本该还有一次执行」。
     * 它是一条需求保真提示，故意不编号，免得和上面「与 V11/V13/V15 逐字对齐」的三条混成一套口径。
     *
     * <p>🔴 {@code trigger.chat} 那行「不要自己加 agentId」对齐的是 <b>V28</b>
     * （{@code WorkflowDslValidator#validateChatBindingAgentExists}），它<b>不在</b>上面那三条闸口规则里 ——
     * 那三条管「触发器与人工闸口配不配」，这一条管「trigger 块里冻死的一格引用运行期还解析得到吗」。
     * 实测形状：模型凭「这是会议室预订流程」编了个 {@code agentId:"agent_meeting_room"}，
     * 规划期体检全绿、草稿落库，直到点发布才撞上 {@code WORKFLOW_TRIGGER_CHAT_AGENT_NOT_FOUND}。
     * 判据在校验器那一份，这里只教「怎么产出合法形状」，别在这儿另写一套口径（同上面那条纪律）。
     */
    private static final String TRIGGER_AND_GATE_RULES = """
            ## 触发器与人工闸口（trigger）

            trigger 块决定「谁来拉起这条流程、初始 state 是什么」。不写 trigger 也合法，
            但那样这条流程只能由人从编辑器/执行历史手动运行 —— 用户需求里出现「每天」「每小时」
            「当表单提交时」「外部系统调过来」这类字眼时<b>必须</b>写。

            形状：{"type":"trigger.chat|trigger.schedule|trigger.webhook|trigger.form_event","config":{...}}

            - **trigger.chat**：会话触发（<b>唯一</b>「跑起来时有人在场」的一种）。config：{"outputVar":"可选，覆盖聊天回复取哪个键（缺省 output）"}。
              🔴 <b>不要自己加 agentId 这一格</b>。它填的必须是<b>用户明确点名的、已经存在的</b>智能体编码
              （agent_def.code），含义是「这条 chatflow 接管那个 Agent 的会话入口」。
              编一个不存在的编码 ⟹ 发布被拒；挑一个真实存在的 Agent 绑上去 ⟹ 更糟，等于抢走它的会话入口。
              用户需求里没点名智能体时就<b>省略这一格</b>（省略 = 不绑定任何 Agent，完全合法，流程照样能跑）。
            - **trigger.schedule**：定时触发。config：{"cron":"Spring cron 表达式，6 段，如 0 0 8 * * ?"}。
            - **trigger.webhook**：外部系统 POST 触发。config：{"path":"URL 路径片段","authType":"hmac 或 token","secret":"校验密钥"}。
            - **trigger.form_event**：表单数据事件触发。config：{"formCode":"表单编码","event":"事件类型"}。

            ### 🔴 三条必须遵守的闸口规则（违反即发布被拒，不是警告）

            1. **不可逆动作的上游必须有人工闸口**（且闸口类型要配得上触发器）：
               - 工具目录里标了「不可逆」的工具，它前面必须有一条能到达它的人工闸口节点。
               - **会话触发**：hitl.confirm 或 hitl.bpm_approval 都行，但 🔴 **默认用 hitl.confirm** ——
                 它就是为「有人在聊天窗口里点一下」造的，不需要任何前置准备。
                 **别为了拿到一个闸口而去调 process_create_and_deploy 现造一个 BPM 流程**：
                 那等于往流程里多塞一个高风险写操作（它<b>自己</b>又需要上游闸口，先有鸡还是先有蛋，
                 体检会把这条点名成警告），而且那个流程一造出来就<b>真部署进 BPM 库</b>了、删不掉。
                 hitl.bpm_approval 只在两种情况下用：①触发器是无头的（见下一条）；
                 ②需求<b>本身</b>就是要走某个既有审批流（用户点名了一个已部署的流程标识）。
               - **无头触发**（schedule / webhook / form_event）：<b>只能</b>用 hitl.bpm_approval ——
                 因为跑起来时没有聊天窗口，hitl.confirm 推的确认卡没人能点。
               - 标了「中/高风险」但不是不可逆的写操作：不阻断，但建议加闸口（会出一条警告）。
            2. **无头流程里不许出现 hitl.confirm**：它靠聊天确认卡唤醒，而无头执行既没有聊天窗口、
               resume_at 与 event_key 也都是空的，会一直停在 INTERRUPTED，谁也不知道。请改用 hitl.bpm_approval。
            3. **webhook 触发器必须配鉴权**：入口 POST /agent/webhook/{path} 是免登录接口，
               不配鉴权就等于开了一个「以发布者身份无头拉起流程」的远程执行入口。
               authType 必须填 hmac（校验请求头 X-Webhook-Signature）或 token
               （校验请求头 X-Webhook-Token），两者都必须同时配 secret。
               平台<b>已不再支持</b>不鉴权，别指望「path 猜不到」——path 是作者手填的一格，平台不生成随机串。

            ### 🔴 「预览给我看、等我确认再执行」≠「只预览」

            需求里出现「先预览 / 给我看方案 / 等我确认 / 我确认之前不要执行」这类话时，它要的是<b>两段</b>：
            先预览，<b>确认后真的执行</b>。只画前半段（预览完就 END，再在汇报话术里补一句
            「实际变更请人工去界面操作」）等于把后半段需求丢掉 —— 用户以为流程会替他改，结果还得自己动手，
            而汇报读起来还像任务完成了。
            正确形状：预览类工具 → hitl.confirm → 执行类工具 → 汇报；确认被拒走取消分支。
            会话触发下这个闸口就是 hitl.confirm（见规则 1），不需要任何额外准备；
            「确认之前不要执行」靠闸口的位置保证，不靠把执行节点整个删掉。
            实测疤：一条「加字段、DDL 预览等我确认」的需求被画成 查列表→查结构→预览→汇报 四个节点、
            一个写操作都没有，汇报里还写着「本流程仅做预览」。

            """;

    /**
     * 复用优先 + id 命名规范
     *
     * <p>🔴 里面那条「反方向同样硬」是缺陷 <b>D4-16</b> 补的，别当成啰嗦删掉。
     * 2026-10-04 run10 的 T2 里，id=57「客户拜访记录表」产出的计划<b>四个节点全是只读</b>
     * （查物理表 → 取控件白名单 → script 算 schema → llm 汇报并反问用户），
     * 一个写节点都没有，manifest 因此是空的（riskLevel=READ_ONLY、kinds=[]），
     * 而草稿卡照样渲染了出来 —— 用户点开看到的是一张什么也不建的卡。
     * 模型把理由写在了那个 script 节点的注释里（原文，逐字）：
     * 「更严谨的做法是：如果没找到合适表，先调用 datamodel_create 建表。
     * 但 datamodel_create 是不可逆操作，且需要人工闸口。
     * 为了符合『每步做完先给我看结果』的交互，我们将分阶段进行。第一阶段：只做到生成 Schema 建议。」
     *
     * <p>也就是说：模型<b>知道</b>正确形状，是被提示词劝退的 —— 改之前本段只有单向抑制
     * （「除非用户明确要求，否则优先用只读工具」），而「除非…否则…」读起来是<b>许可</b>不是<b>义务</b>，
     * 配上工具目录那句「写操作工具（…非必要不用）」，模型就把「不可逆 + 要闸口」
     * 理解成「那我先不放进去」。所以补的是**义务**方向，并把那几种错法逐条点名。
     *
     * <p>🔴 那条清单在 D4-19 又长了一条（现在四条），而新那条<b>正是本条修复带出来的</b>：
     * 2026-10-05 run13 的 T2 里，<b>同一条</b> id=57「客户拜访记录表」（def #463）这回
     * 老老实实把写步骤编进了计划 —— 但编成了 script 节点里的
     * {@code var saveResult = tools.form_save_draft({…})}。沙箱里没有 {@code tools}
     * （{@code ScriptSandbox#doEval} 只塞了 {@code __stateJson}），运行期必炸；
     * 而 {@code WorkflowChangeManifestBuilder#build} 把 script 归进「不产变更项」，
     * 于是清单又是 {@code kinds=[]}、{@code riskLevel=READ_ONLY} —— <b>表面症状与 D4-16 一模一样</b>
     * （都是一张「什么也不建」的卡），根因却相反：那次是「写步骤没编进来」，这次是「编进来了但藏错了地方」。
     * 教训记在这里：<b>把「必须做 X」写进提示词之后，紧接着要问「模型会在哪儿做 X」</b> ——
     * 只给义务不给落点，它就会挑一个自己顺手、平台看不见的地方。
     * 运行期那半边由校验器 V22（{@code validateScriptToolCalls}）兜住。
     *
     * <p>⚠️ 这<b>不是</b> D4-01（「模型在轮次上限内产不出合法 JSON」）：run10 这条是
     * <b>第 1 轮就过闸门</b>的合法 JSON，37.9s 出来，与 run6 那个 6 轮全废、480s 超时是两种病。
     * 别拿这条红去论证「该换 planner 模型」——JSON 生成能力这次没出问题，出问题的是计划的取舍。
     */
    private static final String REUSE_AND_NAMING_RULES = """
            ## 硬规则

            - **id 命名**：节点 id、变量 key 都必须匹配 ^[a-z][a-z0-9_]{0,62}$（小写字母开头，仅小写字母/数字/下划线）。
            - **顶层 id 字段**（流程 code）随便填个占位即可，服务端会用唯一 code 覆盖，不必纠结。
            - **复用优先**：能用一个 tool 节点查到的，就不要拆成多个节点；不要凭空造工具名——只能用下方工具目录里列出的工具。
            - **只读 vs 写**：工具目录里标了【写】的工具会产生真实变更，除非用户明确要求，否则优先用只读工具把需求表达成「查询/汇总」类流程。
            - 🔴 **反方向同样硬：用户明确要求建/改东西时，那些写步骤必须编进计划，一条都不能省。**
              「我要做一张 X 表」「给 Y 表单加一个字段」就是明确要求 —— 这时交一份只有查询和汇报的计划，等于没干活。
              下面三种想法都是错的（都是实测踩过的，不是假想）：
              - **「这步不可逆、上游还得有人工闸口，所以先不编进去」** —— 恰恰相反：闸口在**发布之前**，
                编进计划 ≠ 会被执行，发布前用户还能改、还能干脆不发布。
                省掉它，用户拿到的草稿卡里根本没有那一步，想确认也无从确认。
              - **「先查有没有能复用的表 / 别急着建新表」= 先查 + 用 condition 分支**（查到就复用、
                查不到就在闸口后面建，写法见样例3），**不是**「不编建表节点，停下来反问用户」。
              - **别把计划切成「第一阶段先做到这里」**：草稿卡是用户对这份工作唯一的抓手，
                交半份计划，用户看到的就是一张什么也不建的卡。
              - 🔴 **也别把写步骤藏进 script 里**（tools.form_save_draft(...) 这种）：沙箱里<b>没有 tools</b>，
                运行期必抛 ReferenceError（tools is not defined）；更要紧的是变更清单只从 tool 节点提取，
                于是用户会在一张「一条变更都没有」的<b>只读</b>卡上点确认。
                写步骤<b>必须</b>是 tool 节点；script 只算参数，算完 return 出来，
                再由下游 tool 节点用 {{$.那个变量}} 取。
            - **算数别交给模型**：排序、求和、计数、拼串、格式转换一律用 script 节点，llm 只用来做「理解」和「措辞」。
            - **参数从哪来，先看触发器**（口径见「变量声明」里 input 那一节，这里不抄第二份）：
              需求里已经写明的值<b>直接写成字面量</b>；只有「每次跑都不一样、且调用方会结构化传进来」的值
              才写 {{$.input.xxx}}。🔴 <b>会话触发拿不到这种值</b> —— 它的 input 是用户那句话（字符串），
              {{$.input.表名}} 运行期恒为空，工具于是收到一个空表名，报出来的错跟根因毫无关系。
              所以会话流程里的参数一律在编排时就定下来，别为了「不编造字面量」而写一个取不到的占位符。

            """;

    /**
     * few-shot：三个最小可用样例
     *
     * <p>🔴 这三段<b>必须是合法 JSON</b>，且每个节点的 config 键都必须在 {@code NodeIoContract} 里登记过 ——
     * {@code WorkflowDslPlannerPromptTest} 会把它们真解析一遍、逐键回查契约。
     * 这不是形式主义：样例是模型唯一的「形状参照」，样例里写错一个键名，模型就会照抄那个错键名，
     * 而校验器报的错又指向别处，一来一回烧掉两轮真实计费。
     *
     * <p>🔴 而「config 键登记过」<b>守不住 args 里的内容</b> —— 那是本类实测踩过的最深的一坑：
     * 样例3 曾把建表写成 {@code "args":{"name":"{{$.table_name}}"}}，一个扁平的单键。
     * {@code args} 确实是 {@code tool} 节点登记过的键，于是 CI 全绿；
     * 但 {@code datamodel_create} 真正要的是 {@code args.model.{tableName,tableComment,columns[]}}
     * 四层嵌套（见 {@code DataModelParamResolver.CREATE_PARAMS_SCHEMA}），
     * 样例里<b>一处这样的嵌套都没有</b>。等真需求要建一张有五个字段的表，
     * 模型只能凭空造那四层嵌套 —— 然后在 {@code columns} 数组收尾处把 {@code ]}} 少写一个 {@code &#125;}，
     * 六轮里五轮错在<b>同一个字符位置</b>，第 3~6 轮的原文 md5 完全相同，
     * 约十万 prompt token 烧完，用户只看到一句「未能产出结构合法的 DSL」。
     * 回灌自修正救不了它：模型缺的不是「哪里错了」，是「正确形状长什么样」，
     * 而后者只有样例能给。故 {@code WorkflowDslPlannerPromptTest} 另有一条专盯样例3 的 args 形状。
     *
     * <p>样例3 刻意选「无头 + 不可逆」这个组合：它是全套规则里<b>唯一</b>只有一条合法写法的地方
     * （必须 {@code hitl.bpm_approval}，不能 {@code hitl.confirm}，见 V11 的类注释），
     * 不给样例的话模型几乎必然写成 confirm 然后被 V13 拒。
     *
     * <p>🔴 样例里<b>不许出现带引号的默认输出名</b>（形如 bpm_approved 这种键名被引号包起来）。
     * 这不是洁癖，是 {@code NodeIoContractTest#mainSourcesHaveNoDefaultNameLiterals} 的硬约束：
     * 它扫全部 src/main，除执行器的常量声明行外一律不许出现默认名字面量 —— 同值字面量能骗过值比对
     * （契约引用常量之后 {@code assertEquals(X,X)} 恒真），而改执行器常量时这个读者不会跟着动。
     * 样例要用默认名就<b>把那个 config 键整个省掉</b>（执行器自己兜底），别把名字抄进来。
     * ⚠️ 探测器只认「带引号」的形态，故本段与上面节点目录里不带引号地点名这些键是安全的
     * （它分不清注释与代码，见那条测试 javadoc 的域边界第 3 条）。
     */
    private static final String FEW_SHOT = """
            ## 样例（照这个形状产出，不要照抄内容）

            样例1（需求：列出关键词为 X 的数据接口，最多 5 条）：
            {"dslVersion":"1.0","id":"demo_list","name":"查询数据接口","description":"按关键词列出数据接口",
             "variables":[{"key":"input","type":"object"},{"key":"listOut","type":"object"}],
             "nodes":[{"id":"list","type":"tool","name":"查数据接口",
                       "config":{"toolId":"data_interface_list","args":{"keyword":"{{$.input.keyword}}","limit":5},"outputVar":"listOut"}}],
             "edges":[{"from":"START","to":"list"},{"from":"list","to":"END"}]}

            样例2（需求：取某条数据，再用模型总结）：
            {"dslVersion":"1.0","id":"demo_get_summarize","name":"取数并总结","description":"先查一条数据再让模型总结",
             "variables":[{"key":"input","type":"object"},{"key":"getOut","type":"object"},{"key":"summary","type":"string"}],
             "nodes":[
               {"id":"get","type":"tool","name":"取数据","config":{"toolId":"form_data_get","args":{"code":"{{$.input.code}}","pk":"{{$.input.pk}}"},"outputVar":"getOut"}},
               {"id":"summarize","type":"llm","name":"总结","config":{"prompt":"请用一句话总结这条数据：{{$.getOut}}","outputVar":"summary"}}
             ],
             "edges":[{"from":"START","to":"get"},{"from":"get","to":"summarize"},{"from":"summarize","to":"END"}]}

            样例3（需求：每天早上八点盘点一遍，发现异常就建一张表，建表前要有人审批）：
            {"dslVersion":"1.0","id":"demo_headless_create","name":"每日盘点并按需建表","description":"定时触发；不可逆动作前挂 BPM 审批",
             "trigger":{"type":"trigger.schedule","config":{"cron":"0 0 8 * * ?"}},
             "variables":[{"key":"listOut","type":"object"},{"key":"has_abnormal","type":"boolean"},{"key":"createOut","type":"object"},{"key":"output","type":"string"}],
             "nodes":[
               {"id":"scan","type":"tool","name":"盘点","config":{"toolId":"data_interface_list","args":{"limit":200},"outputVar":"listOut"}},
               {"id":"judge","type":"script","name":"判断有无异常","config":{"code":"function pickRows(o, key) { if (Array.isArray(o)) return o; if (!o) throw new Error('上游没有写回结果'); if (key) { if (!Array.isArray(o[key])) throw new Error('没有名为 ' + key + ' 的数组，实有的键：' + Object.keys(o).join(',')); return o[key]; } var ks = Object.keys(o).filter(function(k) { return Array.isArray(o[k]); }); if (ks.length === 1) return o[ks[0]]; var MAIN = ['items', 'list', 'models']; var hit = ks.filter(function(k) { return MAIN.indexOf(k) !== -1; }); if (hit.length === 1) return o[hit[0]]; throw new Error('猜不出该取哪个数组，候选：' + ks.join(',') + '（第二个参数点名一个）'); } var rows = pickRows(state.listOut); return rows.some(function(r){ return r.status === 'ABNORMAL'; });","outputVar":"has_abnormal"}},
               {"id":"gate","type":"condition.rule","name":"有没有异常","config":{"branches":[{"target":"need_create","conditions":[{"var":"has_abnormal","op":"is_true"}]}],"default":"nothing"}},
               {"id":"approve","type":"hitl.bpm_approval","name":"建表前审批","config":{"bpmProcessDefKey":"agent_datamodel_create"}},
               {"id":"create","type":"tool","name":"建表","config":{"toolId":"datamodel_create","args":{"model":{"tableName":"biz_abnormal_log","tableComment":"盘点异常记录","columns":[{"columnName":"biz_date","columnComment":"盘点日期","columnType":"STRING","length":32},{"columnName":"detail","columnComment":"异常详情","columnType":"TEXT"}]}},"outputVar":"createOut"}},
               {"id":"report","type":"llm","name":"写结论","config":{"prompt":"用一句话汇报本次盘点与建表结果：{{$.createOut}}","outputVar":"output"}}
             ],
             "edges":[
               {"from":"START","to":"scan"},{"from":"scan","to":"judge"},{"from":"judge","to":"gate"},
               {"from":"gate","type":"conditional","cases":{"need_create":"approve","nothing":"report"},"default":"report"},
               {"from":"approve","to":"create"},{"from":"create","to":"report"},{"from":"report","to":"END"}
             ]}

            ⚠️ 样例3 有四条是<b>刻意</b>这么写的，照着做别改回去：
            - 不可逆的 datamodel_create 上游挂的是 hitl.bpm_approval 而不是 hitl.confirm：
              定时触发跑起来时没有聊天窗口，confirm 推的卡没人能点，会被发布校验判 error。
            - 判断「有没有异常」是 script 算出一个<b>布尔量</b>、再由 condition.rule 按 is_true 路由。
              为什么必须这样、以及那三种错法（拿对象取字段 / 对每轮都被写的键用 exists / 判一个没人写的键），
              见上面 condition.rule 那一节 —— 这里刻意不抄第二份，两份必然漂。
            - 🔴 datamodel_create 的参数<b>整块嵌在 args.model 里</b>，且 tableName 只能写<b>字面量</b>：
              模板替换 {{$.变量}} <b>只作用于 args 这个对象里各个键的字符串值</b>，嵌在 model/columns 里的会原样当字面串传下去
              （写成 "tableName":"{{$.table_name}}" 会被建表校验按「不以 biz_ 开头」直接拒）。
              所以别为它单开一个 script 节点去算表名 —— 算了也传不进去，表名要在编排时就定下来。
              🔴 同理，<b>args 自己不能整个写成一个占位符</b>。{"args":"{{$.prepared_input}}"} 这种写法，
              执行器发现 args 不是对象时会直接以<b>空参数表</b>去调工具，那个工具必然报「缺少参数」——
              而这样一份 DSL 是能通过体检、存成草稿的，直到真跑才炸。
              要让脚本替你拼参数，就让它把结果写进 outputVar，再在 args 里<b>逐键</b>引用：
              {"args":{"id":"{{$.prepared_input.id}}","parentMenuName":"{{$.prepared_input.parentMenuName}}"}}。
              columns 每项的 columnType 只能取上面「## 建表列类型白名单」里列出的那些 type，
              <b>不是</b> MySQL 类型名：写 VARCHAR、TIMESTAMP、bigint 一律拒；
              标了【length 必填】的必须显式给 length（标了 scale 的还要给 scale）。
              🔴 控件白名单里有些控件的「可绑定的列类型」带着 <b>⚠️建表不收</b> 标记 ——
              那是控件契约宣称能绑、而建表枚举里<b>根本没有</b>的类型（写这段时是 INTEGER 与 TIME 两种，
              但<b>以标记为准、别记这份名单</b>：枚举加一个类型，名单就少一个，这里不会跟着变）。
              新建表时绝不能用它们；那种控件只能绑到<b>已有表</b>的既有列上。
            - 审批节点<b>没有</b>配 approveVar / statusVar：这两个产出有缺省键名（见上面节点目录），
              下游不读它们时就别自己起名字 —— 起了名字还得在 variables[] 里多声明两条，纯属噪音。

            现在，请针对用户的需求，<b>只输出一个</b>符合上述规范的 Workflow DSL JSON 对象。
            """;

    // ==================== 工具目录 ====================

    /**
     * 目录里的一行：内置工具与自定义工具归一成同一个形状
     *
     * <p>刻意不直接暴露 {@code ToolDefinition} 或 {@code AgentToolDefDO}：两者的风险级一个是枚举、
     * 一个是待解析的字符串，展示名一个可能为空、一个是租户自己填的，归一之后下面就不必知道这行是哪来的
     * （与 {@code WorkflowChangeManifestBuilder.ToolMeta} 同一条理由）。
     *
     * @param declaredRisk <b>声明</b>风险级。分列与标签都只看它，理由见类注释
     * @param resultArrayKeys 返回值里数组值的键（全部返回分支的并集）。🔴 只在 {@code shapeKnown} 为真时有意义
     * @param resultKeys 结果对象<b>顶层</b>的键（全部返回分支的并集），供下游写 {@code {{$.变量.子键}}} 时对照。
     *                   🔴 <b>空列表 = 未登记</b>，不是「不回对象」—— 51 个内置工具目前只登记了一部分，
     *                   把空列表当断言就会对没登记的工具说一句假话（渲染时单独归一栏，理由见
     *                   {@code AgentToolFunction#resultKeys()}）。自定义工具一律空：它的结果就是那个数据接口回的东西。
     * @param shapeKnown 形状是否<b>可静态声明</b>。内置工具取自己的声明
     *                   （{@code AgentToolFunction#resultShapeKnown}），自定义工具一律 false ——
     *                   {@code source=DATA_INTERFACE} 的结果就是那个数据接口回的东西，平台无从知道。
     *                   🔴 与「声明为空」是两件事：{@code explain_process} 是<b>已知</b>不回数组，
     *                   {@code data_interface_invoke} 是<b>不知道</b>回什么。混成一样就会把「未知」
     *                   印成「压根不回数组，别对它用 pickRows」—— 一句确定的假指引，
     *                   模型照它写出的取值代码要到运行期才炸。
     */
    private record CatalogTool(String key, String displayName, String description,
                               String paramsSchema, RiskLevel declaredRisk,
                               List<String> resultArrayKeys, List<String> resultKeys,
                               boolean shapeKnown) {
    }

    /** 工具目录：内置（按声明风险分列）+ 当前租户可执行的自定义工具 */
    private void appendToolCatalog(StringBuilder sb) {
        List<CatalogTool> readonly = new ArrayList<>();
        List<CatalogTool> write = new ArrayList<>();
        List<CatalogTool> all = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (CatalogTool tool : builtinTools()) {
            keys.add(tool.key());
            all.add(tool);
            bucket(tool, readonly, write);
        }
        // 自定义工具排在内置之后，且<b>跳过与内置同名的</b>：执行路径是 registry-first，
        // 同名时真正跑的是内置那个。两边都列出来，模型可能按自定义那份的参数说明去调内置工具 ——
        // 这类分叉从界面上完全看不出来（WorkflowChangeManifestBuilder 类注释里记的同款风险）
        for (CatalogTool tool : customTools()) {
            if (keys.add(tool.key())) {
                all.add(tool);
                bucket(tool, readonly, write);
            }
        }

        sb.append("## 工具目录（tool 节点的 toolId 只能取这里列出的名字）\n\n");
        appendResultShapes(sb, all);
        sb.append("### 只读工具（可放心用于查询/汇总类流程，也可放进 agent_loop.tools）\n\n");
        appendTools(sb, readonly);
        // 🔴 这个标题原本写的是「写操作工具（会产生真实变更，非必要不用）」。
        //    「非必要不用」四个字是缺陷 D4-16 的半个病根：模型把它读成「能不用就不用」，
        //    于是 id=57 那条明明用户开口就要建表，交上来的计划里一个写节点都没有
        //    （详见 REUSE_AND_NAMING_RULES 的类注释）。现改成把两个方向都写死。
        //    ⚠️ 改这个标题只能**在末尾追加**：WorkflowDslPlannerPromptTest 有两条用例
        //    按字面量 "### 写操作工具" 切段落，动了前缀它们会切出空串然后恒假。
        sb.append("\n### 写操作工具（会产生真实变更；用户没要求改动时别自作主张，用户明确要求建/改时必须编进计划）\n\n");
        sb.append("> 标了「不可逆」的，上游必须有人工闸口；无头触发时那个闸口只能是 hitl.bpm_approval。\n");
        sb.append("> 🔴 「不可逆 + 上游要有闸口」是**照样把它编进计划**的理由，不是**省掉这一步**的理由 —— 闸口在发布之前。\n\n");
        appendTools(sb, write);
        sb.append('\n');
    }

    private void bucket(CatalogTool tool, List<CatalogTool> readonly, List<CatalogTool> write) {
        // 🔴 判据是「声明风险级 == READ_ONLY」，不是 isRequireConfirm()：
        //    后者走租户生效值，一行 agent_tool_override 就能把写工具挪进「只读」那一栏
        if (tool.declaredRisk() == RiskLevel.READ_ONLY) {
            readonly.add(tool);
        } else {
            write.add(tool);
        }
    }

    private void appendTools(StringBuilder sb, List<CatalogTool> tools) {
        for (CatalogTool tool : tools) {
            sb.append("- **").append(tool.key()).append("**");
            if (StrUtil.isNotBlank(tool.displayName()) && !tool.displayName().equals(tool.key())) {
                sb.append("（").append(tool.displayName()).append("）");
            }
            if (tool.declaredRisk() != RiskLevel.READ_ONLY) {
                sb.append("  【写，风险：").append(tool.declaredRisk().getDescription()).append("】");
            }
            sb.append('：').append(StrUtil.blankToDefault(tool.description(), "（无说明）")).append('\n');
            if (StrUtil.isNotBlank(tool.paramsSchema())) {
                sb.append("  参数：").append(tool.paramsSchema().replace("\n", " ")).append('\n');
            }
        }
    }

    /**
     * 「谁回哪些数组」的清单：从每个工具的<b>代码声明</b>渲染，不再手抄
     *
     * <h3>🔴 手抄的那份已经错过一次，而且错在最坏的那一栏</h3>
     * 本节曾是一段硬编码散文，把 {@code datamodel_preview_ddl} 写进「压根不回数组，别对它用 pickRows」。
     * 那只在它的 CREATE 分支成立；ALTER 分支回五个数组
     * （addedColumns / modifiedColumns / droppedColumns / ddl / warnings）。
     * 「不回数组」是四栏里<b>唯一</b>会引导模型写出必炸代码的一栏：照着它直接写
     * {@code state.preview_out.addedColumns.length} 之外的取值方式（或干脆当对象字段摸），
     * 规划期一声不响，运行期才抛 —— 而抛的那句还指向别的节点。
     * 散文不随代码变，声明会：判据挪进 {@link AgentToolFunction#resultArrayKeys()}，本方法只负责渲染。
     *
     * <h3>为什么排在两栏工具清单<b>之前</b></h3>
     * {@code WorkflowDslPlannerPromptTest} 按字面量 {@code "### 只读工具"} / {@code "### 写操作工具"}
     * 切段落，并断言「被租户降级成只读的不可逆工具<b>不</b>出现在只读段」
     * 「被升级成不可逆的只读工具<b>不</b>出现在写操作段」。本表<b>跨两栏</b>列工具名，
     * 插在任何一栏之后都会让那两条断言变红 —— 而它们挡的是真缺陷（prompt 与 V3/V11 分叉），
     * 不能为了排版去放宽。
     */
    private void appendResultShapes(StringBuilder sb, List<CatalogTool> tools) {
        List<CatalogTool> mustName = new ArrayList<>();
        List<CatalogTool> unkeyed = new ArrayList<>();
        List<CatalogTool> noArrays = new ArrayList<>();
        List<CatalogTool> unknown = new ArrayList<>();
        for (CatalogTool tool : tools) {
            if (!tool.shapeKnown()) {
                unknown.add(tool);
            } else if (!ToolResultShapes.declaresArrays(tool.resultArrayKeys())) {
                noArrays.add(tool);
            } else if (ToolResultShapes.mustName(tool.resultArrayKeys())) {
                mustName.add(tool);
            } else {
                unkeyed.add(tool);
            }
        }

        sb.append("### 返回值形状（script 节点里 pickRows 的依据；本表由每个工具的代码声明自动渲染，别自己猜）\n\n");
        sb.append("【必须点名，否则运行期 throw】\n");
        for (CatalogTool tool : mustName) {
            sb.append("  ").append(tool.key()).append(" → ")
                    .append(String.join(" / ", tool.resultArrayKeys())).append('\n');
        }
        sb.append("【不点名也对（只有一个数组，或多个里恰有一个叫 ")
                .append(String.join("/", ToolResultShapes.MAIN_LIST_KEYS)).append("）】\n");
        for (CatalogTool tool : unkeyed) {
            List<String> ks = tool.resultArrayKeys();
            sb.append("  ").append(tool.key()).append(" → ").append(String.join(" + ", ks));
            // 🔴 多于一个数组时必须印出「不点名落到哪个」：模型看得见落点，
            //    才会在落点不是它要的那个时改用点名（datamodel_physical_tables 的 items 就是这种）
            if (ks.size() > 1) {
                sb.append("（不点名取到 ").append(ToolResultShapes.autoResolvedKey(ks)).append("）");
            }
            sb.append('\n');
        }
        sb.append("【压根不回数组，别对它用 pickRows】\n");
        appendKeyList(sb, noArrays);
        sb.append("【形状随接口而定，无法静态声明 —— 要解析它的结果就先用 script 把键名打出来看一眼】\n");
        appendKeyList(sb, unknown);
        sb.append("> 表里只说「有哪些数组」，不说「哪个才是你要的行列表」：")
                .append("warnings / ddl 这类是诊断信息，别当业务行去遍历。\n");
        sb.append("> 新工具会自动落进对应那一栏（声明写在工具自己的代码里），本表不需要谁去维护。\n\n");
        appendResultKeys(sb, tools);
    }

    /**
     * 「谁回哪些<b>顶层键</b>」的清单：下游节点写 {@code {{$.某变量.子键}}} 时的唯一依据
     *
     * <h3>🔴 为什么非印不可：子键写错不报错，只安静地变成空串</h3>
     * 实测（execution 217 / def 900217）：{@code datamodel_create} 回的是扁平对象
     * {@code {id, tableName, columnCount, message}}，模型却写了
     * {@code "id":"{{$.create_out.model.id}}"} —— 它把<b>入参</b>的形状（建表参数确实整块嵌在
     * {@code args.model} 里）镜像成了<b>出参</b>的形状。{@code .model} 不存在 ⟹
     * {@code StatePathNavigator} 按设计返回 null（它刻意不抛）⟹ {@code stringify(null)} = {@code ""} ⟹
     * 下游 {@code datamodel_structure} 抛「id 必须是数字，收到的是：」，冒号后面空的。
     * 而<b>发布前体检查不出来</b>：{@code PLACEHOLDER_ROOT} 只截取 {@code $.} 后第一个标识符
     * （{@code create_out}，确实有人写过），{@code .model.id} 整段被丢掉。
     *
     * <p>模型会这么猜，是因为那张标量键<b>从来没被印给它们看过</b>：本表此前只渲染数组键，
     * 而 {@code datamodel_create} 只声明了 {@code warnings}；全文唯一提到建表结果的地方是
     * 样例3 里一句 {@code {{$.createOut}}}（整个对象塞进 prompt），没有一个样例演示过「取它的 id」。
     *
     * <h3>为什么未登记者<b>不逐个列名</b></h3>
     * 与上面那四栏刻意不同：那四栏每栏的<b>指引不同</b>（点名 / 不点名 / 别用 pickRows / 先打一眼），
     * 逐栏列名才有意义。本栏只有「已登记」与「没登记」两种，而没登记的有四十多个 ——
     * 列出来是五百字的噪音，模型真正需要的是一句「没列出来的别猜」。
     * 而且「没登记」是<b>平台自己的欠账</b>，把它印成一长串工具名等于让模型替平台的未完成事项负责。
     */
    private void appendResultKeys(StringBuilder sb, List<CatalogTool> tools) {
        List<CatalogTool> declared = new ArrayList<>();
        for (CatalogTool tool : tools) {
            if (tool.shapeKnown() && !tool.resultKeys().isEmpty()) {
                declared.add(tool);
            }
        }

        sb.append("### 顶层键（下游写 {{$.某变量.子键}} 的唯一依据；同样由代码声明自动渲染，别自己猜）\n\n");
        for (CatalogTool tool : declared) {
            sb.append("  ").append(tool.key()).append(" → ")
                    .append(String.join(" / ", tool.resultKeys())).append('\n');
        }
        sb.append("""

                🔴 子键路径写错的后果<b>不是报错，是安静地变成空串</b>：路径里任一段不存在，引擎按设计返回 null \
                再转成 ""，下游工具于是收到一个空值（实测那句「id 必须是数字，收到的是：」冒号后面就是空的），\
                而发布前体检只核对 $. 后面<b>第一个变量名</b>有没有人写过，子键一概不看 —— 点了运行才炸。所以：
                - 上面<b>列出来的</b>工具，子键只能从它那一行里取，一个字母都别改；
                - 上面<b>没列出来的</b>工具（含所有自定义工具），平台尚未登记它的顶层键 —— \
                别对它写 {{$.变量.子键}}；真要取子键，先插一个 script 节点把键名打出来看一眼，别赌；
                - 🔴 建表结果里那个 id 在<b>顶层</b>：写 {{$.create_out.id}}，<b>没有 .model 这一层</b>。\
                入参才整块嵌在 args.model 里，出参不嵌 —— 这一进一出的差别最容易搞反。
                """);
        sb.append("> 新登记的工具会自动出现在上面（声明写在工具自己的代码里），本表不需要谁去维护。\n\n");
    }

    private void appendKeyList(StringBuilder sb, List<CatalogTool> tools) {
        if (tools.isEmpty()) {
            sb.append("  （无）\n");
            return;
        }
        sb.append("  ");
        for (int i = 0; i < tools.size(); i++) {
            if (i > 0) {
                sb.append("、");
            }
            sb.append(tools.get(i).key());
        }
        sb.append('\n');
    }

    /** 内置工具：启用状态读生效值（停用是更安全的方向），风险级读声明值 */
    private List<CatalogTool> builtinTools() {
        List<CatalogTool> out = new ArrayList<>();
        Collection<AgentToolRegistry.ToolDefinition> tools = toolRegistry.list();
        if (tools == null) {
            return out;
        }
        for (AgentToolRegistry.ToolDefinition tool : tools) {
            if (!tool.isEnabled()) {
                continue;
            }
            // declaredRisk 理论上不会为 null（注解必填）；真为 null 按 HIGH 兜 ——
            // 与 ToolResolver#declaredRiskOf、WorkflowChangeManifestBuilder#resolveTool 同方向：
            // 宁可多说风险，不能把写操作标成只读
            RiskLevel declared = tool.getDeclaredRisk() == null ? RiskLevel.HIGH : tool.getDeclaredRisk();
            out.add(new CatalogTool(tool.getName(), tool.getDisplayName(), tool.getDescription(),
                    tool.getParamsSchema(), declared, tool.getDeclaredResultArrayKeys(),
                    tool.getDeclaredResultKeys(), tool.isDeclaredResultShapeKnown()));
        }
        return out;
    }

    /**
     * 当前租户可执行的自定义工具
     *
     * <p>只取 {@code source=DATA_INTERFACE}：FORM/SCRIPT 是预留位，执行期抛
     * {@code AGENT_TOOL_SOURCE_UNSUPPORTED}。列出来等于教模型产一份「校验能过、执行必炸」的草稿。
     *
     * <p>🔴 查库失败时<b>返回空目录</b>而不是抛：prompt 组装在聊天主链路上，
     * 自定义工具表挂了不该让整个 AI 编排不可用 —— 退化成「只认内置工具」是安全方向
     * （模型少一个可用工具，不会多一个）。
     */
    private List<CatalogTool> customTools() {
        List<CatalogTool> out = new ArrayList<>();
        List<AgentToolDefDO> rows;
        try {
            rows = toolDefService.getEnabledToolDefList();
        } catch (Exception ex) {
            return out;
        }
        if (rows == null) {
            return out;
        }
        for (AgentToolDefDO row : rows) {
            if (row == null || StrUtil.isBlank(row.getToolKey())
                    || !ToolSourceEnum.isDataInterface(row.getSource())) {
                continue;
            }
            out.add(new CatalogTool(row.getToolKey(),
                    StrUtil.blankToDefault(row.getDisplayName(), row.getToolKey()),
                    row.getDescription(), row.getParamsSchema(),
                    RiskLevel.parseOrHigh(row.getRiskLevel()), List.of(), List.of(), false));
        }
        return out;
    }

    // ==================== 控件白名单 ====================

    /**
     * 主子表能力缺口的<b>如实</b>告知（拼在控件清单之前，理由见 {@link #appendWidgetCatalog} 类注释那一节）
     */
    private static final String SUB_TABLE_GAP = """
            🔴 **主子表（明细表）需求：form_save_draft 搭不出来 —— 别硬搭，更别静默吞掉。**
            下面清单里那条 group（子表）看着像能用来装明细，实际<b>不能</b>：
            fields[] 里没有任何能放子字段的键（没有 children / rule / columns），
            而「子表控件 ↔ 明细表」的绑定关系住在表单设计的数据绑定里（tableBinding.subTables），
            <b>不在</b> form_save_draft 的参数范围内。硬写 group 的后果：存草稿<b>能过</b>，
            发布体检才报「子表控件没有配置对应的明细表，提交的明细数据会丢失」，
            还附带一条「列不存在」（它被当成主表字段去配列映射了）—— 两条错都指向别处，白烧几轮。
            遇到主子表需求，按这三步做，<b>一步都不许省</b>：
            ① 用 datamodel_create 把<b>两张</b>物理表都建出来：子表带一个指向主表主键的外键列
               （例如 record_id，类型 BIGINT）。全平台不建物理外键，关系就体现在这个列上。
            ② form_save_draft <b>只搭主表</b>的字段，不要放 group 控件。
            ③ 在最终 output 节点的话术里<b>点名</b>说清：子表那张表已经建好了，
               但绑定必须人工去「表单设计 → 数据绑定」里配（加 group 控件、填明细表名与外键列、逐列做字段映射），
               配之前明细数据存不进去。
            🔴 第 ③ 步是硬要求：漏了它，用户拿到的是一张看起来完整的表单 ——
               填了明细、提交、提示成功、数据静默丢失。那比一开始就说「这步我做不了」坏得多。

            """;

    /**
     * 把 {@code widget_contract_get} 的选型清单<b>整份拼进 prompt</b>
     *
     * <h3>🔴 这条路径上没有任何工具可调，「去查白名单」是一句照做不了的指令</h3>
     * planner 走的是 {@code WorkflowPlannerServiceImpl} 的纯聊天调用：既没有
     * {@code setToolCallbacks}，{@code AiChatReqDTO} 里也<b>压根没有</b>工具相关字段。
     * 而模型被<b>三处</b>告知要先查白名单：{@code FormSaveDraftTool} 的 {@code @AgentTool}
     * description（「调用前必须先用 widget_contract_get 拿控件白名单」）、它的
     * {@code paramsSchema()}（「widget 必填，控件类型，必须来自 widget_contract_get」）——
     * 这两段被 {@code appendTools} 原样拼进工具目录，加上校验回灌里
     * {@code FormSchemaValidator#checkWidgetType} 的「可用类型请调 widget_contract_get 查询」。
     *
     * <p>实测（2026-10-04，T2 的 id=55「会议室预定」与 id=57「客户拜访」）：需求里出现
     * 「负责人」「主管」这类<b>要选人</b>的字段时，模型连猜六个名字，两例各烧满 6 轮上限 ——
     * id=55 依次是 {@code UserSelect}（判「已停用」）→ {@code user_picker} →
     * {@code UserSingleSelect} → {@code user_select} → {@code {{$.valid_widgets.user_widget}}} →
     * {@code UserSelectSingle}；id=57 是 {@code UserSelector} → {@code UserMultiSelector} →
     * {@code UserSelector} → {@code User} → {@code UserSelector} → {@code UserPicker}。
     * 🔴 第五轮那个<b>变量引用</b>是最硬的证据：模型已经放弃猜名字，改成「让运行期替我取值」——
     * 它完全相信了「白名单要现查」，只是找不到查的入口。两例最后都 fail-close 成
     * 「未能产出结构合法的 DSL」，各烧掉约 8 分钟与六轮真实计费。
     *
     * <p>而正确的名字 {@code OrgPicker}（组织选择器）一直躺在契约里 —— 模型只是<b>看不见</b>。
     *
     * <h3>为什么整份拼进来不会撑爆预算</h3>
     * 55 个可用控件按「type（中文含义）：可绑定列类型」压成一行一个，实测约 <b>1.5 KB</b>。
     * {@code WidgetContractGetTool} 之所以要拆成「清单/详情」两段，是因为它连 {@code props}
     * 一起返回有两万多字符、而回灌预算只有 4000；这里<b>不带 props</b>，只给选型要的三样，
     * 量级完全不同。（{@code AgentTool#truncateResult} 的 javadoc 记着同一次截断事故：
     * 清单被砍到只剩前 8 个控件、{@code datePicker} 排第 13 位，模型于是编了个
     * {@code dateTimePicker}，反复重查反复被同样截断，5 轮耗尽。同一个病，这里是同一味药：
     * 要么完整给出，要么明说自己不完整。）
     *
     * <h3>🔴 清单内容一律现读 {@code getWidgetContracts()}，绝不写死</h3>
     * 写死就是第三份副本（契约 JSON 是规范副本，{@code WidgetContractGetTool} 与
     * {@code FormSchemaValidator} 是既有的两个读者）。漂移的表现是「prompt 教的名字校验器不认」
     * —— 模型照 prompt 写、校验器照契约拒，回灌里还看不出分歧在哪，比不教更糟。
     * 强制点在 {@code WorkflowDslPlannerPromptTest}：它把本段每一行的 type 抠出来，
     * 与契约的 {@code supported=true} 集合做<b>双向</b>差集比对。
     *
     * <h3>⚠️ 停用控件在这里<b>要点名</b>，与 {@code WidgetContractGetTool} 的取舍相反</h3>
     * 那个工具的类注释记着「选型清单里躺着一条 {@code upload}，模型无视 supported 标记直接选了它」，
     * 于是它把停用控件整个滤掉。本段的处境不同：停用项被单独列在<b>清单之外的黑名单段</b>里，
     * 并且原样带上契约给的 label（「用户选择器（已删除）」这种，理由自带）。
     * 之所以必须点名，是因为上面那六个猜测<b>全部</b>是「用户/部门选择器」的变体 ——
     * 这类控件在本平台<b>曾经存在过又被删掉</b>，模型对它们有很强的先验；
     * 只给白名单不说「那几个名字已删除」，它仍会先去撞自己最熟的那个。
     *
     * <h3>🔴 子表那一段（{@link #SUB_TABLE_GAP}）是 2026-10-09 补的，补的是一次<b>静默丢需求</b></h3>
     * 用户要「设备巡检记录单：主表记巡检人…子表记每个检查项…」，产出的草稿 611 把主表建了、
     * 控件也选对了，而<b>子表那半条需求一个字都没提</b> —— 话术里也没说少了东西，
     * 用户看到的是一张「看起来很完整」的表单。根因不是模型偷懒，是这份 prompt 自己教的：
     * 「每行格式…写「不绑列」的是布局/展示类控件」＋循环印出的那一行 {@code group（子表）：不绑列}
     * ⟹ 「group 是个装饰性小标题」是<b>照字面读出来的正确推断</b>。
     *
     * <p>⚠️ 用户当时给的修法是「主子表必须用 group」，而<b>实查推翻了它</b>：
     * {@code FormSaveDraftTool#paramsSchema} 的 fields[] 里没有任何能放子字段的键，
     * {@code FormFieldFactory#toRule} 拼出的 props 只有 placeholder，
     * {@code FormSaveDraftTool#build} 压根不设 {@code tableBinding.subTables}。
     * 于是 group 存草稿<b>能过</b>（契约里 supported=true，agent 侧两个校验都只看这一条），
     * 到发布体检才被 {@code FormDesignPublishValidator#validateSubTableWidgetsBound} 拒掉
     * （「子表控件没有配置对应的明细表，提交的明细数据会丢失」），
     * 外加 {@code build} 把 group 字段当主表列自动配了映射、再撞一条「列不存在」——
     * <b>两条错都指向别处</b>，看不出根因是「这个工具表达不了子表」。
     * 所以这里教的不是「用 group」，而是「用不了，且必须如实说」。
     *
     * <p>🔴 <b>本段没有校验器规则给它兜底</b>，这是如实记录、不是遗漏：
     * 要在规划期认出「这是子表容器控件」，就得知道契约里哪个 type 的 {@code storage=subTable}，
     * 而 {@code FormDesignApi.WidgetSimpleRespDTO} 上<b>没有</b> storage 这一格 ——
     * 它那个 {@code subTable} 布尔的语义是「能否<b>放进</b>子表控件内部」（契约的 {@code caps.subTable}），
     * group 自己是 {@code false}、input 才是 {@code true}，拿它当容器判据会正好判反。
     * 于是只剩两条路：给 api 加一格（要动 infra 的契约→DTO 映射），
     * 或在 agent 模块里写死 {@code "group"} 字面量 ——
     * 后者是第三份副本（契约 JSON 是规范副本，{@code WidgetContractRegistry#getSubTableWidgetType}
     * 与 {@code ImportWidgetRegistry#SUB_TABLE_TYPES} 是既有的两个读者），
     * 而 {@code WidgetContractRegistry} 的类注释<b>点名批评过</b>正是这种散落硬编码。
     * 取舍：发布体检那道闸门拦得住（只是晚），故本次只改 prompt，不加一份会漂的字面量。
     *
     * <h3>🔴 选项来源那一段是 2026-10-09 补的，补的是「控件选对了、下拉是空的」</h3>
     * 实查 34 个未删 def：10 个选项类字段里 7 个写了 {@code dictType}（其中 <b>6 个字典压根不存在</b>，
     * 2 个还挂在 PUBLISHED 上），另外 3 个<b>什么来源都没给</b>，静态 {@code options} 是 0 个。
     * 三种形状在存草稿与发布上<b>一律静默通过</b> —— 根因当时在工具侧
     * （{@code FormFieldFactory#toRule} 从不写 {@code options} / {@code props.dictSource}），
     * 那一半已同批修掉；这里补的是<b>模型侧</b>：字段描述里原本连 {@code options} 这个键都没有，
     * 模型想表达「固定选项」也没有写法，于是只能去编一个 dictType。
     *
     * <p>为什么贴在控件清单里而不是只写进 {@code FormSaveDraftTool#paramsSchema}：
     * 这里是模型<b>决定用哪个控件</b>的地方，而「选了它就必须给来源」是这个决定的直接后果；
     * 两段话说的是同一件事的两半，分开放就要各写一遍控件名 —— 故控件名一律从
     * {@link FormFieldFactory#optionWidgetCatalog()} 渲染，本文件里不出现 {@code "select"} 字面量。
     * ⚠️ 校验兜底在 {@code WorkflowDslValidator#validateSaveDraftOptionSources}（V29），
     * 但 prompt 这段不是冗余：错误回灌要多烧一轮计费，而「先编再被拦」的形状
     * 用户是能从 Plan 卡片上看见的。
     *
     * <h3>🔴 {@code ⚠️AI建不出来} 那个标记是 2026-10-10 补的，补的是「在白名单里、却建不出来」</h3>
     * 上面那份清单印的是<b>契约里 supported=true 的全部控件</b>，而实测 63 个里有 <b>18 个</b>
     * 的必需配置<b>字段描述压根表达不了</b>（{@code DictSelect} 要 {@code dictType}、
     * {@code cascader} 的静态选项要写进 {@code props.options}、{@code Formula} 要 {@code expression}…
     * 而 {@code FormFieldFactory#toRule} 往 props 里只写 placeholder 与 validateRules）。
     * 不打标的话，模型看到的是一份「这 63 个都能用」的清单，它挑中哪一个都合规矩 ——
     * 于是需求里出现「按字典选」「省市区级联」时，它<b>照清单</b>选了 {@code DictSelect} / {@code cascader}，
     * 一路存草稿、发布、上线都不报错，用户点进去是一个点不开的下拉。
     * 与 {@link #SUB_TABLE_GAP} 那一段是同一类缺陷（能力缺口没如实告知 ⟹ 静默交付半成品），
     * 只是这一段能<b>逐行打标</b>，不必整段散文。
     *
     * <p>🔴 打不打标是<b>逐次现算</b>的（{@code FormFieldFactory#checkWidgetAuthorable} 读的就是
     * 同一份契约的 {@code props.required} 与键名），所以契约给某个控件补上必填项、
     * 或 {@code toRule} 学会写那一格，标记自动跟着变 —— 这一半不会烂。
     * <b>会烂的是汇总段里的散文</b>，故那里<b>刻意不举控件名</b>（{@code NOT_CREATABLE_MARK} 那段
     * 记着同一个教训：凡「现算的数据」都不会烂，凡「把当时的数据抄进散文」的都会烂），
     * 只说「带这个标记的那些」；控件名一律由 {@code unauthorable} 现渲染。
     *
     * <p>⚠️ 校验兜底在 {@code FormSchemaValidator#checkWidgetType}（规划期，被
     * {@code WorkflowDslValidator#validateSaveDraftWidgets} 复用）与
     * {@code FormSchemaValidator#validateWidgetConfig}（运行期建/改表单）。
     * 那段汇总同样不是冗余，理由与上面选项来源那一段逐字相同。
     */
    private void appendWidgetCatalog(StringBuilder sb, Set<String> creatableTypes) {
        List<FormDesignApi.WidgetSimpleRespDTO> widgets = formDesignApi.getWidgetContracts();
        List<FormDesignApi.WidgetSimpleRespDTO> usable = new ArrayList<>();
        List<String> unusable = new ArrayList<>();
        for (FormDesignApi.WidgetSimpleRespDTO widget : widgets) {
            if (Boolean.TRUE.equals(widget.getSupported())) {
                usable.add(widget);
            } else {
                unusable.add(widget.getType() + "「"
                        + StrUtil.blankToDefault(widget.getLabel(), "无标签") + "」");
            }
        }

        sb.append("## 控件白名单（form_save_draft 的 fields[].widget 只能取这里列出的 type）\n\n");
        sb.append("🔴 下面这 ").append(usable.size())
                .append(" 个就是<b>全部</b>可用控件：清单完整、没有省略、也<b>不需要</b>再去查。")
                .append("你当前这条路径上<b>没有任何工具可调</b> —— widget_contract_get 调不到，")
                .append("写 {{$.valid_widgets.xxx}} 这类占位也不会有人替你填。直接从下面挑。\n");
        sb.append("上面工具目录里 form_save_draft 的参数说明写着「widget 必须来自 widget_contract_get」，")
                .append("那是<b>执行期</b>的说法；在<b>这里</b>，那份清单就是下面这一段。\n\n");
        sb.append("每行格式：type（中文含义）：可绑定的列类型。写「不绑列」的是布局/展示类控件。\n\n");
        // 🔴 子表这段必须排在清单**之前**：group 那一行是下面循环自动印出来的，
        //    而「每行格式」那句刚把「不绑列」解释成「布局/展示类控件」——
        //    先印清单，模型读到 `- group（子表）：不绑列` 时的正确推断就是「一个装饰性小标题」，
        //    于是把用户的明细表需求整条吞掉（草稿 611 就是这么来的）。
        //    先说清「不绑列 ≠ 用得了」，再给它看清单。
        //    ⚠️ 位置也不能挪到清单之后：那一段自己就写着「下面清单里那条 group」，指代关系会反过来。
        sb.append(SUB_TABLE_GAP);
        // 🔴 契约宣称能绑、而建表枚举里根本没有的列类型，当场打标（缺陷 D4-15）。
        //    不打标的后果是实测出来的：run8 示例 57 照这一段印出的「JSON」去写 columnType，
        //    建表被拒、整条示例红掉 —— 是这份 prompt 自己把模型引进沟里的。
        //    creatableTypes 为空说明清单没取到（见 columnTypesOrEmpty），那时**一个都不标**：
        //    把全部类型都标成「不收」比不标更坏，模型会以为平台压根不能建表。
        //
        // ⚠️ JSON 这一条已经**从根上修掉了**（2026-10-04 用户裁定「改建表枚举，加上 JSON」，
        //    DataModelColumnTypeEnum 里现在有 JSON），所以它不再被打标。但这段逻辑**没有变成死代码**：
        //    契约的 columnTypes 用的是 JdbcTypeFamily 那套词表，实测还声明了 INTEGER（12 个控件）
        //    与 TIME（1 个控件），而建表枚举里是 INT、且没有 TIME —— 这两个照样会被标出来。
        //    契约与枚举是两份手工维护的清单，这段就是它们之间唯一的一致性检查，别因为
        //    「JSON 修好了」就把它删掉。
        Set<String> notCreatable = new LinkedHashSet<>();
        List<String> unauthorable = new ArrayList<>();
        for (FormDesignApi.WidgetSimpleRespDTO widget : usable) {
            sb.append("- ").append(widget.getType())
                    .append('（').append(StrUtil.blankToDefault(widget.getLabel(), widget.getType())).append('）');
            if (Boolean.TRUE.equals(widget.getDeprecated())) {
                sb.append("【已废弃，新表单别选】");
            }
            // 🔴 「在白名单里」与「AI 建得出来」是两件事：契约里存在、supported=true，
            //    而它必需的配置（DictSelect 的 dictType、cascader 的 props.options…）
            //    字段描述里没有能放它的格子 ⟹ 写了不报错，草稿照存、发布照过，
            //    运行期那一格是个空控件。判据在 FormFieldFactory#checkWidgetAuthorable（从契约现算），
            //    这里只负责把结论印给模型看 —— 不印的话它只能等校验回灌，白烧一轮计费。
            String gap = FormFieldFactory.checkWidgetAuthorable(widget);
            if (gap != null) {
                unauthorable.add(widget.getType());
                sb.append('【').append(FormFieldFactory.UNAUTHORABLE_MARK).append('：').append(gap).append('】');
            }
            List<String> columnTypes = widget.getColumnTypes();
            sb.append('：');
            if (CollUtil.isEmpty(columnTypes)) {
                sb.append("不绑列");
            } else {
                List<String> rendered = new ArrayList<>(columnTypes.size());
                for (String columnType : columnTypes) {
                    boolean creatable = creatableTypes.isEmpty() || creatableTypes.contains(columnType);
                    if (!creatable) {
                        notCreatable.add(columnType);
                    }
                    rendered.add(creatable ? columnType : columnType + NOT_CREATABLE_MARK);
                }
                sb.append(String.join("/", rendered));
            }
            sb.append('\n');
        }
        if (!notCreatable.isEmpty()) {
            sb.append("\n🔴 上面带 ").append(NOT_CREATABLE_MARK).append(" 的列类型（")
                    .append(String.join("、", notCreatable))
                    .append("）是<b>控件契约宣称能绑、而建表枚举里没有</b>的：")
                    .append("新建表时写了必被拒。这类控件只能绑到<b>已有表</b>的既有列上。\n");
        }

        if (!unauthorable.isEmpty()) {
            sb.append("\n🔴 上面带 ").append(FormFieldFactory.UNAUTHORABLE_MARK)
                    .append(" 标记的这 ").append(unauthorable.size()).append(" 个控件（")
                    .append(String.join("、", unauthorable))
                    .append("）在契约里<b>确实存在、也确实可用</b>，但它们的必需配置")
                    .append("字段描述（fields[]）里没有能放它的格子。写了<b>不报错</b>：草稿照存、发布照过，")
                    .append("运行期那一格是个空控件（点不开的下拉 / 显示不出内容的块），")
                    .append("用户只会觉得「这表单坏了」，没有任何一处会主动说。<b>一律别选</b>。\n")
                    .append("· 选择类需求（含级联、树形选择）⟹ 用 ")
                    .append(FormFieldFactory.optionWidgetCatalog())
                    .append(" + options/dictType，语义等价，而且真能出选项。\n")
                    .append("· 用户<b>点名</b>要带这个标记的控件 ⟹ 把表单其余部分建好，")
                    .append("并在最终 output 节点的话术里<b>如实说清</b>哪一格需要人工去「表单设计」里配。")
                    .append("不说就等于交付一张看起来完整、其实缺一格的表单 —— 与上面子表那一段同一条要求。\n");
        }

        if (!unusable.isEmpty()) {
            sb.append("\n🔴 下面这些名字在契约里<b>存在但已停用</b>，写了会被直接拒")
                    .append("（引号里是契约给的 label，已写明原因）：\n\n");
            sb.append(String.join("、", unusable)).append('\n');
        }
        sb.append("\n契约里<b>没有</b>「用户选择器」「部门选择器」这类独立控件。")
                .append("需要选人/选部门/选组织时，请从上面清单的<b>中文含义</b>里挑一个 —— ")
                .append("别凭 Element Plus 或别的低代码平台的记忆编名字，编了必被拒。\n\n");

        sb.append("🔴 选了 ").append(FormFieldFactory.optionWidgetCatalog())
                .append(" 之一，就<b>必须</b>在 fields[] 里给它一个选项来源：")
                .append("固定选项写 options:[{\"label\":\"选项名\",\"value\":\"存进库的值\"}]，")
                .append("用字典写 dictType。两个都不写<b>不会报错</b> —— ")
                .append("表单照样建好、照样发布成功，而运行期那一格是个点开为空的下拉，")
                .append("用户只会觉得「这表单坏了」，没有任何一处会主动说。\n")
                .append("· 用户把选项说出来了（「状态分待处理/已处理」「性别男女」）⟹ 写 options。\n")
                .append("· 用户<b>点名了系统里已存在的字典</b> ⟹ 才写 dictType。")
                .append("你这条路径上<b>没有建字典的工具</b>，凭记忆编一个 dictType 的后果同样不是报错，")
                .append("而是下拉里一个选项都没有。\n")
                .append("编的 dictType 与「一个来源都没给」规划期就会被拦下来、把错误回灌给你重改，")
                .append("但别指望靠它兜底：这份字段描述是<b>冻进 DSL</b> 的，运行期不会再问模型。\n\n");
    }

    // ==================== 建表列类型白名单 ====================

    /**
     * 把 {@code DataModelApi#getColumnTypeList()} 整份印进 prompt（缺陷 D4-15）
     *
     * <h3>🔴 为什么必须内联，而不是让模型「去查 datamodel_column_types」</h3>
     * 与 {@link #appendWidgetCatalog} 同一个理由：这条路径上<b>没有任何工具可调</b>。
     * 而原先这里只有一句手写的话——「只能取 datamodel_column_type 里的那些值
     * （STRING/TEXT/INT/LONG/DECIMAL/BOOLEAN/DATE/DATETIME 等）」——三处都错：
     * 工具名少了个 s（真名是 {@code datamodel_column_types}）、清单漏了 LONG_TEXT 与 TINYINT、
     * 末尾那个「等」字还明写着「清单不全，剩下的你自己猜」。
     *
     * <h3>run8 的代价是实测出来的，不是推演的</h3>
     * 示例 57 给「参与人」这一列写了 {@code columnType: JSON}，建表被拒、整条示例红掉。
     * 而模型不是凭空编的：<b>控件契约里有 7 个控件宣称自己能绑 JSON</b>
     * （checkbox / cascader / tree / elTransfer / UploadFile / UploadImgs / OrgPicker），
     * 杠杆A 把契约原样印进「可绑定的列类型」，模型照我印的写，撞上的却是
     * {@code DataModelColumnTypeEnum} —— 那份枚举里<b>没有 JSON</b>。
     * 是这份 prompt 自己把模型引进沟里的，所以修也得修在这里。
     *
     * <h3>为什么这样就叫「同源」</h3>
     * 判「这个 columnType 收不收」的 {@code DataModelCreateValidator} 用的正是
     * {@code getColumnTypeList()}，与本段印的是<b>同一个出口的同一份数据</b>。
     * 于是「模型看到的清单」与「建表时判的清单」不可能再漂 —— 而此前一边是手写字符串、
     * 一边是枚举，漂了也不报错，表现就是「照 prompt 写、被 prompt 说的那个工具拒」。
     *
     * <p>✅ <b>JSON 那一条不一致已在根上修掉</b>（2026-10-04 用户裁定「改建表枚举，加上 JSON」）：
     * {@code DataModelColumnTypeEnum} 里现在有 JSON，示例 57 那种「照 prompt 写、被 prompt 说的那个
     * 工具拒」不会再发生。改的是枚举而<b>不是</b>契约 JSON —— 契约的 {@code columnTypes} 用的是
     * {@code JdbcTypeFamily} 那套词表（描述控件能绑什么物理列），本来就该含 JSON；
     * 缺的是建表侧，所以在建表侧补。这样也绕开了「契约是双副本、改它要两侧同步」那一摊。
     *
     * <p>⚠️ <b>但契约与枚举仍未同源</b>：契约还声明了 INTEGER（12 个控件）与 TIME（1 个控件），
     * 枚举里是 INT、且没有 TIME。这两条由 {@link #appendWidgetCatalog} 的打标逻辑兜着
     * （模型会看到「⚠️建表不收」），本段不涉及。真要同源，得让契约改引枚举或反之，那是另一刀。
     *
     * @param types 权威列类型清单；为空表示没取到，此时明说「别建表」而不是留白
     */
    private void appendColumnTypeCatalog(StringBuilder sb, List<DataModelApi.ColumnTypeRespDTO> types) {
        sb.append("## 建表列类型白名单（datamodel_create 的 columns[].columnType 只能取这里列出的 type）\n\n");
        if (CollUtil.isEmpty(types)) {
            sb.append("⚠️ 这份清单本次<b>没取到</b>。取不到就<b>不要</b>编排建表节点 —— ")
                    .append("凭记忆猜一个类型名，猜错就是整条流程被拒。\n\n");
            return;
        }
        sb.append("🔴 下面这 ").append(types.size())
                .append(" 个就是<b>全部</b>可建列类型：清单完整、没有省略、也<b>不需要</b>再去查。")
                .append("这条路径上<b>没有任何工具可调</b> —— datamodel_column_types 调不到。")
                .append("判「收不收」用的就是这份清单本身，所以照抄即合法。\n");
        sb.append("这些是<b>平台自己的类型名</b>，不是 MySQL 类型名：写 VARCHAR、TIMESTAMP、bigint 一律被拒。")
                .append("括号里是它落成的 MySQL 类型，供你从业务含义反推该选哪个。\n\n");
        sb.append("每行格式：type（MySQL 类型）：说明【必须显式给的东西】。\n\n");
        for (DataModelApi.ColumnTypeRespDTO type : types) {
            sb.append("- ").append(type.getType())
                    .append('（').append(StrUtil.blankToDefault(type.getMysqlType(), "?")).append('）');
            if (StrUtil.isNotBlank(type.getDescription())) {
                sb.append('：').append(type.getDescription());
            }
            List<String> required = new ArrayList<>(2);
            if (Boolean.TRUE.equals(type.getNeedLength())) {
                required.add("length 必填");
            }
            if (Boolean.TRUE.equals(type.getNeedScale())) {
                required.add("scale 必填");
            }
            if (!required.isEmpty()) {
                sb.append("【").append(String.join("、", required)).append("】");
            }
            sb.append('\n');
        }
        sb.append('\n');
    }

}
