package cn.enlev.ainb.module.agent.framework.agent.core.copy;

import cn.enlev.ainb.framework.common.exception.ServiceException;
import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.compiler.DslParser;
import cn.enlev.ainb.module.agent.framework.agent.core.engine.trigger.WorkflowTriggerExtractor;
import cn.enlev.ainb.module.agent.framework.agent.core.validate.AgentValidateResult;
import cn.enlev.ainb.module.agent.framework.agent.core.validate.DataModelCreateValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.WORKFLOW_DSL_INVALID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link WorkflowCopyRewriter} 单测（S5-g）
 *
 * <h3>为什么这一类值得单独钉死</h3>
 * 复制走 {@code insertNewVersion}，全方法<b>不跑</b> {@code validateDsl}（S4b 的闸门在<b>发布期</b>，
 * 复制在<b>建草稿期</b>）⟹ 改写器漏改一格，产物是一份<b>必然发不出去、或发出去就与源流程抢同一份不可逆资源</b>
 * 的草稿，而当场一个错都不报。{@code rewritten[]} 是这一步唯一的用户可见信号，所以本类的断言重心是
 * <b>「清单会不会骗人」</b>，不只是「DSL 改没改对」：
 * <ol>
 *   <li><b>改了的必须出现在清单里</b>（否则就是本仓 §6 那「8 条静默改写」的重演）；</li>
 *   <li><b>刻意没改的也必须出现在清单里</b>，且 {@code from == to}（前端据此分成两组渲染）——
 *       审批流 / 子流程 / 验签密钥 / cron / form_event.formCode / 占位符 / 加完后缀不合法的表名，
 *       一条都不能静默；</li>
 *   <li><b>没冲突时清单为空，而不是「清单不可用」</b>：改写器对 parse 不出来的 DSL 是<b>抛</b>，
 *       所以「空清单」只可能意味着「这份 DSL 真的没有会撞车的配置」。</li>
 * </ol>
 *
 * <h3>🔴 DSL 一律写成<b>字面量</b> JSON，不引工具侧常量</h3>
 * 这是刻意的：DSL 是<b>落库契约</b>（库里已有的行、前端设计器产出的、LLM 生成的都用这套键名），
 * 常量改名而 DSL 键名跟着改，等于把存量行全部作废。测试引常量的话会<b>静默跟随</b>那次改名 ⟹
 * 绿灯照着一条已经读不懂存量数据的代码。写字面量则任何一侧漂了都当场红。
 * 同理，断言的期望值也写字面量（{@code biz_emp_copy}），不拼 {@code SUFFIX}。
 *
 * <h3>为什么 {@link DataModelCreateValidator} 是 mock</h3>
 * 「表名合不合法」的判据（业务前缀 + 标识符正则 + 框架保留前缀）归
 * {@code DataModelCreateValidatorTest} 守；本类只钉改写器<b>怎么用那个判据</b>——
 * 问了没有、按候选串问的、结论为假时是保留原值 + 回显而不是抛。
 * 在这里重造一份表名规则就是方案 §2-D7 说的第二份口径。
 *
 * <h3>⚠️ 本类<b>不</b>覆盖的</h3>
 * 「复制品能不能过发布闸门」不在这里（那要真跑 {@code /validate}，见 S5-g 的真跑存证）；
 * {@code /copy} 的编排（查重、落 DRAFT/MANUAL、描述沿用）在 {@code AgentWorkflowDefServiceCopyTest}。
 *
 * @author 走进新时代
 */
class WorkflowCopyRewriterTest extends BaseMockitoUnitTest {

    /** 源流程的建表名；下面多条用例都围绕它 */
    private static final String SRC_TABLE = "biz_emp";
    private static final String SRC_TABLE_COPY = "biz_emp_copy";

    /** 🔴 一份真密钥形状的字面量：清单里任何一格出现它都算测试失败（见 secret 那条用例） */
    private static final String SRC_SECRET = "s3cr3t-do-not-log";

    @InjectMocks
    private WorkflowCopyRewriter rewriter;

    @Mock
    private DataModelCreateValidator dataModelCreateValidator;

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        // 改写器刻意走 DslParser#parseTree（原始树）而不是 DTO 往返，故这里给真 DslParser，
        // mock 掉它等于把「未建模字段一个不丢」那条承诺一起 mock 掉
        DslParser parser = new DslParser();
        objectMapper = new ObjectMapper();
        ReflectionTestUtils.setField(parser, "objectMapper", objectMapper);
        ReflectionTestUtils.setField(rewriter, "dslParser", parser);
    }

    // ==================== 夹具 ====================

    /**
     * 一份「什么都撞」的 DSL：五种触发格 + 四类节点，且<b>建表节点排在表单节点之后</b>
     *
     * <p>🔴 这个顺序是刻意的：{@code form_save_draft.mainTable} 的规则是「跟随本流程新建表的改写」，
     * 一趟写完的实现在这份 DSL 上会让 mainTable 跟不上 ⟹ 表单写进一张不存在的表，而那是执行期才炸。
     * 未建模字段（{@code futureField} / {@code unknown*}）是「走原始树」那条承诺的探针。
     */
    private static final String FULL_DSL = """
            {
              "dslVersion": "1.0",
              "id": "wf_src",
              "name": "源流程",
              "description": "源描述",
              "futureField": {"nested": [1, 2, 3]},
              "trigger": {
                "type": "trigger.webhook",
                "config": {
                  "path": "wf-src-hook",
                  "authType": "hmac",
                  "secret": "%s",
                  "unknownTriggerKey": "keep-me"
                }
              },
              "variables": [{"key": "input", "type": "string", "extraVarKey": "keep"}],
              "nodes": [
                {"id": "save", "type": "tool", "name": "存草稿",
                 "config": {"toolId": "form_save_draft", "unknownNodeKey": "keep",
                            "args": {"code": "src_form", "mainTable": "%s"}}},
                {"id": "create", "type": "tool", "name": "建表",
                 "config": {"toolId": "datamodel_create",
                            "args": {"model": {"tableName": "%s", "comment": "员工"}}}},
                {"id": "deploy", "type": "tool", "name": "部署审批流",
                 "config": {"toolId": "process_create_and_deploy",
                            "args": {"key": "src_bpm_key", "name": "审批"}}},
                {"id": "approve", "type": "hitl.bpm_approval", "name": "审批",
                 "config": {"bpmProcessDefKey": "src_bpm_key:1:1"}},
                {"id": "sub", "type": "sub_workflow", "name": "子流程",
                 "config": {"workflowId": "wf_other"}}
              ],
              "edges": [{"from": "START", "to": "save"}],
              "settings": {"recovery": "forward", "unknownSetting": 42}
            }
            """.formatted(SRC_SECRET, SRC_TABLE, SRC_TABLE);

    private WorkflowCopyRewriter.Result rewrite(String dsl) {
        return rewrite(dsl, true);
    }

    private WorkflowCopyRewriter.Result rewrite(String dsl, boolean rewriteConflicts) {
        return rewriter.rewrite(dsl, "wf_new", "新流程", null, rewriteConflicts);
    }

    /** 放行一个候选表名（{@link AgentValidateResult} 无 error 即通过） */
    private void allowTable(String candidate) {
        when(dataModelCreateValidator.validateTableName(candidate)).thenReturn(new AgentValidateResult());
    }

    /** 驳回一个候选表名，错误文案会被原样拼进回显 */
    private void rejectTable(String candidate, String error) {
        AgentValidateResult verdict = new AgentValidateResult();
        verdict.addError(error);
        when(dataModelCreateValidator.validateTableName(candidate)).thenReturn(verdict);
    }

    private static WorkflowCopyRewriter.Rewrite byPath(List<WorkflowCopyRewriter.Rewrite> list, String path) {
        List<WorkflowCopyRewriter.Rewrite> hits = list.stream().filter(r -> r.path().equals(path)).toList();
        assertEquals(1, hits.size(), "清单里 path=%s 应当恰好一条，实际 %s".formatted(path, list));
        return hits.get(0);
    }

    /** 断言「服务端替你改了」：from != to，且 to 是期望值 */
    private static void assertChanged(List<WorkflowCopyRewriter.Rewrite> list, String path,
                                      String from, String to, String reasonFragment) {
        WorkflowCopyRewriter.Rewrite r = byPath(list, path);
        assertEquals(from, r.from(), "path=%s 的源值".formatted(path));
        assertEquals(to, r.to(), "path=%s 改写后的值".formatted(path));
        assertFalse(r.from().equals(r.to()), "from == to 会被前端渲染成「未改写」：%s".formatted(r));
        assertTrue(r.reason().contains(reasonFragment),
                "path=%s 的原因应当含「%s」，实际「%s」".formatted(path, reasonFragment, r.reason()));
    }

    /** 断言「刻意没改但你要知道」：from == to，且值仍是源值 */
    private static void assertKept(List<WorkflowCopyRewriter.Rewrite> list, String path,
                                   String value, String reasonFragment) {
        WorkflowCopyRewriter.Rewrite r = byPath(list, path);
        assertEquals(value, r.from(), "path=%s 的源值".formatted(path));
        assertEquals(r.from(), r.to(), "path=%s 必须 from == to（前端据此渲染成「未改写」）".formatted(path));
        assertTrue(r.reason().contains(reasonFragment),
                "path=%s 的原因应当含「%s」，实际「%s」".formatted(path, reasonFragment, r.reason()));
    }

    private JsonNode out(WorkflowCopyRewriter.Result result) throws Exception {
        return objectMapper.readTree(result.dsl());
    }

    // ==================== 整份清单 ====================

    @Test
    void rewrite_fullDsl_manifestCoversEveryRule() throws Exception {
        allowTable(SRC_TABLE_COPY);

        WorkflowCopyRewriter.Result result = rewrite(FULL_DSL);
        List<WorkflowCopyRewriter.Rewrite> m = result.rewritten();

        // 8 条：path/secret（webhook 的两格）+ code/mainTable（表单）+ tableName（建表）
        //      + key（BPM 部署）+ bpmProcessDefKey（审批）+ workflowId（子流程）。
        // form_event.formCode 与 schedule.cron 这份 DSL 里没有 ⟹ 不产条目（见各自的用例）
        assertEquals(8, m.size(), "整份清单应当恰好 8 条，实际 %s".formatted(m));

        // 改了的 4 格
        assertChanged(m, "trigger.config.path", "wf-src-hook", "wf-src-hook_copy", "webhook 入站路径已改写");
        assertChanged(m, "nodes[save].config.args.code", "src_form", "src_form_copy", "表单编码加了后缀");
        assertChanged(m, "nodes[create].config.args.model.tableName", SRC_TABLE, SRC_TABLE_COPY, "建表名加了后缀");
        assertChanged(m, "nodes[deploy].config.args.key", "src_bpm_key", "src_bpm_key_copy", "BPM 流程标识加了后缀");

        // 🔴 主表跟随：这一条是「两趟、顺序不可换」在单测里唯一的证据。
        //    建表节点在 DSL 里排在表单节点<b>之后</b>，一趟写完的实现这里会给出「未改写」
        assertChanged(m, "nodes[save].config.args.mainTable", SRC_TABLE, SRC_TABLE_COPY, "主表跟随");

        // 刻意没改的 4 格，一条都不能静默
        assertKept(m, "trigger.config.secret", "（已沿用源流程的密钥，值不显示）", "验签密钥沿用了源流程");
        assertKept(m, "nodes[approve].config.bpmProcessDefKey", "src_bpm_key:1:1", "审批流未复制");
        assertKept(m, "nodes[sub].config.workflowId", "wf_other", "子流程未复制");

        // 清单顺序 = 发现顺序：trigger 先于 nodes；nodes 内**建表名那条排在最前**（它是第一趟的产物，
        // 而 mainTable 在第二趟）—— 这正是回显文案能说「原因见上一条 / 建表名见上」的前提。
        // 前端逐格渲染，顺序抖动会让人以为两次复制的结果不一样
        assertEquals(List.of("trigger.config.path", "trigger.config.secret",
                "nodes[create].config.args.model.tableName",
                "nodes[save].config.args.code", "nodes[save].config.args.mainTable",
                "nodes[deploy].config.args.key",
                "nodes[approve].config.bpmProcessDefKey", "nodes[sub].config.workflowId"),
                m.stream().map(WorkflowCopyRewriter.Rewrite::path).toList());

        // DSL 里真的改了（不只是清单上写了）
        JsonNode root = out(result);
        assertEquals("wf-src-hook_copy", root.at("/trigger/config/path").asText());
        assertEquals("src_form_copy", root.at("/nodes/0/config/args/code").asText());
        assertEquals(SRC_TABLE_COPY, root.at("/nodes/0/config/args/mainTable").asText());
        assertEquals(SRC_TABLE_COPY, root.at("/nodes/1/config/args/model/tableName").asText());
        assertEquals("src_bpm_key_copy", root.at("/nodes/2/config/args/key").asText());
        // 没改的那三格在 DSL 里也真的没动
        assertEquals(SRC_SECRET, root.at("/trigger/config/secret").asText());
        assertEquals("src_bpm_key:1:1", root.at("/nodes/3/config/bpmProcessDefKey").asText());
        assertEquals("wf_other", root.at("/nodes/4/config/workflowId").asText());
    }

    @Test
    void rewrite_unmodeledFieldsSurvive() throws Exception {
        allowTable(SRC_TABLE_COPY);

        JsonNode root = out(rewrite(FULL_DSL));

        // 🔴 「走原始树而不是 DTO 往返」那条决定唯一的正面证据：
        //    WorkflowDefDTO/NodeDTO 都挂着 @JsonIgnoreProperties(ignoreUnknown=true)，
        //    往返一趟会把这些格子全部静默丢掉，而复制的承诺是「与源一致，除了我告诉你改了的那些」
        assertEquals(3, root.at("/futureField/nested").size(), "顶层未建模字段丢了");
        assertEquals("keep-me", root.at("/trigger/config/unknownTriggerKey").asText());
        assertEquals("keep", root.at("/variables/0/extraVarKey").asText());
        assertEquals("keep", root.at("/nodes/0/config/unknownNodeKey").asText());
        assertEquals(42, root.at("/settings/unknownSetting").asInt());
        // 结构键本身也没被改名（DSL 是落库契约，键名漂移 = 存量行读不懂）
        assertEquals("1.0", root.at("/dslVersion").asText());
        assertEquals(1, root.at("/edges").size());
    }

    // ==================== 身份三格 ====================

    @Test
    void rewrite_identityAlwaysRewritten_andStaysOutOfManifest() throws Exception {
        allowTable(SRC_TABLE_COPY);

        WorkflowCopyRewriter.Result result = rewriter.rewrite(FULL_DSL, "wf_new", "新流程", "新描述", true);
        JsonNode root = out(result);
        assertEquals("wf_new", root.at("/id").asText(), "DSL 的 id 就是流程 code，不改等于复制出一份编码对不上自己的草稿");
        assertEquals("新流程", root.at("/name").asText());
        assertEquals("新描述", root.at("/description").asText());
        // 🔴 这三格<b>不进</b>清单：它们是用户在对话框里亲手填的，回显是噪声。
        //    清单只装「用户没填、服务端替他决定了的」东西
        assertTrue(result.rewritten().stream().noneMatch(r ->
                        r.path().equals("id") || r.path().equals("name") || r.path().equals("description")),
                "身份三格不该出现在清单里：%s".formatted(result.rewritten()));
    }

    @Test
    void rewrite_blankDescriptionKeepsSourceValue() throws Exception {
        allowTable(SRC_TABLE_COPY);

        // 空白描述 = 沿用源。🔴 不是「写回一个 trim 过的副本」：那等于改了一格没人要求改的东西
        JsonNode root = out(rewriter.rewrite(FULL_DSL, "wf_new", "新流程", "   ", true));
        assertEquals("源描述", root.at("/description").asText());
    }

    @Test
    void rewrite_suffixIsFixedLiteral_notDerivedFromNewCode() throws Exception {
        allowTable(SRC_TABLE_COPY);

        // 用户把副本命名成 wf_x_v2 时表名仍是 biz_emp_copy：后缀不跟 newCode 走。
        // 跟了的话「表名会改成什么」就变成一件要算才知道的事（且这条断言会因拼 SUFFIX 而恒绿）
        JsonNode root = out(rewriter.rewrite(FULL_DSL, "wf_x_v2", "第二版", null, true));
        assertEquals(SRC_TABLE_COPY, root.at("/nodes/1/config/args/model/tableName").asText());
        assertEquals("_copy", WorkflowCopyRewriter.SUFFIX);
    }

    // ==================== 建表名的四种分支 ====================

    @Test
    void rewrite_modelAsJsonString_isRewrittenAndStaysAString() throws Exception {
        allowTable(SRC_TABLE_COPY);
        // 🔴 model 有两种形态：执行期 DataModelParamResolver 用 JSONUtil.parseObj 取值，
        //    Map 与「模型把对象序列化成的 JSON 串」都认。只认 Map 的话，同一份 DSL 执行期能跑通、
        //    复制里却被跳过 ⟹ 表名原样带走、两条流程抢同一张表，而清单上一句提示都没有
        String dsl = """
                {"id":"wf_src","name":"源","nodes":[
                  {"id":"create","type":"tool","config":{"toolId":"datamodel_create",
                    "args":{"model":"{\\"tableName\\":\\"%s\\",\\"comment\\":\\"员工\\"}"}}}]}\
                """.formatted(SRC_TABLE);

        WorkflowCopyRewriter.Result result = rewrite(dsl);
        assertChanged(result.rewritten(), "nodes[create].config.args.model.tableName",
                SRC_TABLE, SRC_TABLE_COPY, "建表名加了后缀");

        JsonNode model = out(result).at("/nodes/0/config/args/model");
        assertTrue(model.isTextual(), "改完必须塞回同一个键、仍是串（与源形态一致），实际 %s".formatted(model));
        assertEquals(SRC_TABLE_COPY, objectMapper.readTree(model.asText()).at("/tableName").asText());
    }

    @Test
    void rewrite_modelAsIllegalJsonString_isSkippedWithoutFailing() throws Exception {
        String dsl = """
                {"id":"wf_src","name":"源","nodes":[
                  {"id":"create","type":"tool","config":{"toolId":"datamodel_create",
                    "args":{"model":"这不是 JSON"}}}]}\
                """;

        WorkflowCopyRewriter.Result result = rewrite(dsl);

        // 执行期 JSONUtil.parseObj 同样取不出表名 ⟹ 没什么可改的，
        // 也不该由「复制」替「执行」报错（源可能就是条存量脏行，把它复制出来看恰恰是合理动作）
        assertTrue(result.rewritten().isEmpty(), "非法 model 串不该产条目：%s".formatted(result.rewritten()));
        assertEquals("这不是 JSON", out(result).at("/nodes/0/config/args/model").asText());
        verifyNoInteractions(dataModelCreateValidator);
    }

    @Test
    void rewrite_tableNameSuffixRejected_keepsOriginalAndSaysWhy() throws Exception {
        rejectTable(SRC_TABLE_COPY, "表名 biz_emp_copy 已被占用");

        WorkflowCopyRewriter.Result result = rewrite(FULL_DSL);

        // 🔴 改不动就说出来，不让整个复制失败。而 insertNewVersion 不跑校验 ⟹ 这条回显是唯一的信号
        assertKept(result.rewritten(), "nodes[create].config.args.model.tableName", SRC_TABLE, "不符合建表规范");
        assertTrue(byPath(result.rewritten(), "nodes[create].config.args.model.tableName").reason()
                        .contains("表名 biz_emp_copy 已被占用"),
                "判据方给的原因必须原样带出，否则用户不知道该改成什么");
        assertEquals(SRC_TABLE, out(result).at("/nodes/1/config/args/model/tableName").asText());
    }

    @Test
    void rewrite_mainTableWhoseRenameWasRejected_saysBothMustBeFixedTogether() {
        rejectTable(SRC_TABLE_COPY, "表名 biz_emp_copy 已被占用");

        WorkflowCopyRewriter.Result result = rewrite(FULL_DSL);

        // 🔴 S5-g 量出的缺陷（写这条用例时才发现）：这一档原先被并进「这张表不是本流程建的」
        //    （判据是 tableRenames 为空，而改名被驳回时它确实是空的）。那是一句<b>反向</b>的行动指引 ——
        //    它告诉用户「不用管」，而这恰恰是最需要管的一档：建表名没改成 ⟹ 复制品会去建源流程那张同名表，
        //    建表不可逆；主表也没跟着改 ⟹ 两处必须一起手工改。清单是这一步唯一的用户可见信号，
        //    在这里说错话比不说更坏
        assertKept(result.rewritten(), "nodes[create].config.args.model.tableName", SRC_TABLE, "不符合建表规范");
        assertKept(result.rewritten(), "nodes[save].config.args.mainTable", SRC_TABLE, "两处必须一起改");
        assertFalse(byPath(result.rewritten(), "nodes[save].config.args.mainTable").reason().contains("不是本流程建的"),
                "本流程确实建这张表，不能这么说");
    }

    @Test
    void rewrite_tableNameIsPlaceholder_isSkipped() throws Exception {
        String dsl = """
                {"id":"wf_src","name":"源","nodes":[
                  {"id":"create","type":"tool","config":{"toolId":"datamodel_create",
                    "args":{"model":{"tableName":"{{runtimeTable}}"}}}}]}\
                """;

        WorkflowCopyRewriter.Result result = rewrite(dsl);

        // 运行期才定的表名，复制时看不出真值 ⟹ 回显而不是猜一个后缀
        assertKept(result.rewritten(), "nodes[create].config.args.model.tableName", "{{runtimeTable}}", "占位符");
        verifyNoInteractions(dataModelCreateValidator);
    }

    // ==================== 主表的各档「没跟随上」 ====================

    @Test
    void rewrite_mainTableOfForeignTable_saysItIsNotCreatedByThisWorkflow() {
        String dsl = """
                {"id":"wf_src","name":"源","nodes":[
                  {"id":"save","type":"tool","config":{"toolId":"form_save_draft",
                    "args":{"code":"src_form","mainTable":"biz_other"}}}]}\
                """;

        WorkflowCopyRewriter.Result result = rewrite(dsl);

        assertChanged(result.rewritten(), "nodes[save].config.args.code", "src_form", "src_form_copy", "表单编码加了后缀");
        // 🔴 各档「没跟随上」的原因必须分开说，否则会给出错误的行动指引：
        //    这张表本来就不是本流程建的 ⟹ 不该跟随
        assertKept(result.rewritten(), "nodes[save].config.args.mainTable", "biz_other", "这张表不是本流程建的");
        verifyNoInteractions(dataModelCreateValidator);
    }

    @Test
    void rewrite_mainTableMismatchingCreatedTable_saysTheyDoNotMatch() {
        allowTable(SRC_TABLE_COPY);
        String dsl = """
                {"id":"wf_src","name":"源","nodes":[
                  {"id":"create","type":"tool","config":{"toolId":"datamodel_create",
                    "args":{"model":{"tableName":"%s"}}}},
                  {"id":"save","type":"tool","config":{"toolId":"form_save_draft",
                    "args":{"code":"src_form","mainTable":"biz_other"}}}]}\
                """.formatted(SRC_TABLE);

        WorkflowCopyRewriter.Result result = rewrite(dsl);

        // 本流程建了表、但表单指向另一张 ⟹ 源流程自己就不一致，照实说
        assertKept(result.rewritten(), "nodes[save].config.args.mainTable", "biz_other", "对不上");
    }

    // ==================== 触发器 ====================

    @Test
    void rewrite_formEventTrigger_formCodeKeptAndEchoed() {
        String dsl = """
                {"id":"wf_src","name":"源",
                 "trigger":{"type":"trigger.form_event","config":{"formCode":"src_form","eventType":"create"}},
                 "nodes":[]}\
                """;

        WorkflowCopyRewriter.Result result = rewrite(dsl);

        // 订阅同一张表单的事件通常正是复制的意图 ⟹ 不改，但照样回显
        assertKept(result.rewritten(), "trigger.config.formCode", "src_form", "表单事件订阅未改写");
        assertEquals(1, result.rewritten().size(), "form_event 只该产这一条：%s".formatted(result.rewritten()));
    }

    @Test
    void rewrite_scheduleTrigger_cronKeptAndEchoed() {
        String dsl = """
                {"id":"wf_src","name":"源",
                 "trigger":{"type":"trigger.schedule","config":{"cron":"0 0 2 * * ?"}},
                 "nodes":[]}\
                """;

        WorkflowCopyRewriter.Result result = rewrite(dsl);

        // 🔴 不能「默认停掉触发器」：删掉整个 trigger 块本身就是一次静默的语义改写
        //    （复制品从「定时自动跑」变成「只能手动跑」）。留着也不危险：定时轮询只扫 PUBLISHED，
        //    而复制品一律 DRAFT ⟹ 风险推迟到发布那一刻，作者那时正看着这份清单
        assertKept(result.rewritten(), "trigger.config.cron", "0 0 2 * * ?", "定时表达式沿用源流程");
    }

    @Test
    void rewrite_webhookSecret_neverLeaksIntoManifest_butStaysInDsl() throws Exception {
        // 关掉自动改写：本用例只关心 secret，顺带免掉建表名的打桩（关掉开关时压根不问合法性）
        WorkflowCopyRewriter.Result result = rewrite(FULL_DSL, false);

        // 🔴 安全断言，不是文案断言：这份对象会经 /copy 回到浏览器、可能被前端打进日志、
        //    也可能被冻结进 BPM 流程变量 —— 任何一条路都会把一份凭据从「只有 DSL 里有」变成「到处都是」
        for (WorkflowCopyRewriter.Rewrite r : result.rewritten()) {
            assertFalse(String.join("|", r.path(), r.from(), r.to(), r.reason()).contains(SRC_SECRET),
                    "密钥真值进了清单：%s".formatted(r));
        }
        // 掩码本身要出现在清单里：一份凭据凭空多出一条副本，正是本类要消灭的那个形状，不能静默带走
        assertKept(result.rewritten(), "trigger.config.secret", "（已沿用源流程的密钥，值不显示）", "验签密钥沿用");
        // 但 DSL 里必须原样保留：applyWebhook 对 secret 是无条件必填，删了它
        // ⟹ 每一份 webhook 复制品在 insertNewVersion 就抛 WORKFLOW_TRIGGER_CONFIG_INVALID，
        //    而前端对话框没有填 secret 的格子 ⟹「复制」对 webhook 流程直接不可用
        assertEquals(SRC_SECRET, out(result).at("/trigger/config/secret").asText());
        verifyNoInteractions(dataModelCreateValidator);
    }

    @Test
    void rewrite_webhookPathTooLong_isShrunkToLegal() throws Exception {
        // 🔴 上限<b>不写死</b>：从白名单自己量出来（"a" 逐个加长到不匹配为止）。
        //    写死「64」的话白名单一改这条就变成假绿（截多了没人发现）或假红（与本刀无关地红）
        int ceiling = 1;
        while (WorkflowTriggerExtractor.WEBHOOK_PATH_PATTERN.matcher("a".repeat(ceiling + 1)).matches()) {
            ceiling++;
        }
        String longPath = "a".repeat(ceiling);
        String dsl = """
                {"id":"wf_src","name":"源",
                 "trigger":{"type":"trigger.webhook","config":{"path":"%s","authType":"hmac","secret":"x"}},
                 "nodes":[]}\
                """.formatted(longPath);

        WorkflowCopyRewriter.Result result = rewrite(dsl);
        WorkflowCopyRewriter.Rewrite r = byPath(result.rewritten(), "trigger.config.path");

        // 🔴 溢出不会静默：insertNewVersion 在 insert 之前跑 triggerExtractor.apply，非法 path 直接抛
        //    WORKFLOW_TRIGGER_CONFIG_INVALID ⟹ 复制当场失败，而前端对话框没有让用户改 path 的格子。
        //    所以改写器自己得截短。判据引白名单本身，不在测试里抄「64」这个数（抄了就会漂）
        assertTrue(WorkflowTriggerExtractor.WEBHOOK_PATH_PATTERN.matcher(r.to()).matches(),
                "改写后的 path 必须落进白名单，实际「%s」".formatted(r.to()));
        assertTrue(r.to().endsWith("_copy"), "后缀不该被截掉，实际「%s」".formatted(r.to()));
        // 判据是「削到刚好够用」而不是「削掉了几个字符」：后者同样是抄一个数。
        // ① 确实削短了（原长 + 后缀一定溢出）；② 再多留一个字符就非法 ⟹ 没有多削
        assertTrue(r.to().length() < longPath.length() + "_copy".length(), "压根没截短");
        String oneMore = longPath.substring(0, r.to().length() - "_copy".length() + 1) + "_copy";
        assertFalse(WorkflowTriggerExtractor.WEBHOOK_PATH_PATTERN.matcher(oneMore).matches(),
                "还能再长一个字符 ⟹ 截多了，实际「%s」".formatted(r.to()));
        assertEquals(r.to(), out(result).at("/trigger/config/path").asText());
    }

    @Test
    void rewrite_webhookPathIsPlaceholder_isSkipped() {
        String dsl = """
                {"id":"wf_src","name":"源",
                 "trigger":{"type":"trigger.webhook","config":{"path":"{{hookPath}}","authType":"hmac","secret":"x"}},
                 "nodes":[]}\
                """;

        assertKept(rewrite(dsl).rewritten(), "trigger.config.path", "{{hookPath}}", "占位符");
    }

    // ==================== 关掉自动改写 ====================

    @Test
    void rewrite_conflictsOff_nothingChangedButEverythingStillEchoed() throws Exception {
        WorkflowCopyRewriter.Result result = rewrite(FULL_DSL, false);

        // 🔴 用户主动关掉改写，那他<b>更</b>需要知道有哪些格子等着他手工处理 ⟹ 条目数一条不少
        assertEquals(8, result.rewritten().size(), "关掉开关不该减少条目：%s".formatted(result.rewritten()));
        assertTrue(result.rewritten().stream().allMatch(r -> r.from().equals(r.to())),
                "关掉开关后不该有任何一格被改写：%s".formatted(result.rewritten()));
        for (String path : List.of("trigger.config.path", "nodes[save].config.args.code",
                "nodes[save].config.args.mainTable", "nodes[create].config.args.model.tableName",
                "nodes[deploy].config.args.key")) {
            assertTrue(byPath(result.rewritten(), path).reason().contains("你关掉了自动改写"),
                    "path=%s 的原因必须点明是用户自己关的，否则行动指引就错了".formatted(path));
        }
        // 关掉开关连「新表名合不合法」都不必问
        verifyNoInteractions(dataModelCreateValidator);
        // DSL 里真的一格没动（身份三格除外）
        JsonNode root = out(result);
        assertEquals("wf-src-hook", root.at("/trigger/config/path").asText());
        assertEquals(SRC_TABLE, root.at("/nodes/1/config/args/model/tableName").asText());
        assertEquals("src_form", root.at("/nodes/0/config/args/code").asText());
        assertEquals(SRC_TABLE, root.at("/nodes/0/config/args/mainTable").asText());
        assertEquals("src_bpm_key", root.at("/nodes/2/config/args/key").asText());
    }

    // ==================== 清单为空 / 边界 ====================

    @Test
    void rewrite_pureLlmWorkflow_manifestIsEmptyNotNull() throws Exception {
        String dsl = """
                {"dslVersion":"1.0","id":"wf_src","name":"问答","description":"纯 LLM",
                 "variables":[{"key":"input","type":"string"}],
                 "nodes":[{"id":"ask","type":"llm","config":{"prompt":"回答 {{input}}"}}],
                 "edges":[{"from":"START","to":"ask"},{"from":"ask","to":"END"}]}\
                """;

        WorkflowCopyRewriter.Result result = rewrite(dsl);

        // ⚠️ 空清单是<b>正常</b>的（这份 DSL 没有任何平台级标识），它与「清单不可用」是两回事：
        //    前端措辞必须是「没有需要处理的冲突配置」，绝不能写成「清单不可用」
        assertNotNull(result.rewritten(), "清单为 null 会让前端分不清「没有冲突」与「没量到」");
        assertTrue(result.rewritten().isEmpty(), "实际 %s".formatted(result.rewritten()));
        assertEquals("wf_new", out(result).at("/id").asText());
        verifyNoInteractions(dataModelCreateValidator);
    }

    @Test
    void rewrite_nodeWithoutId_fallsBackToIndexInPath() {
        String dsl = """
                {"id":"wf_src","name":"源","nodes":[
                  {"type":"hitl.bpm_approval","config":{"bpmProcessDefKey":"src_bpm_key:1:1"}}]}\
                """;

        // 优先用节点 id（人能对上画布），没有才退回下标 —— 但无论哪种都必须给出定位串，
        // 否则清单里那一行没法对应到画布上的哪个节点
        assertKept(rewrite(dsl).rewritten(), "nodes[0].config.bpmProcessDefKey", "src_bpm_key:1:1", "审批流未复制");
    }

    @Test
    void rewrite_numericIdentifier_isSeenNotSkipped() {
        String dsl = """
                {"id":"wf_src","name":"源","nodes":[
                  {"id":"save","type":"tool","config":{"toolId":"form_save_draft","args":{"code":123}}}]}\
                """;

        // DSL 里把标识符写成数字是常见笔误：认成「没有这一格」会让复制静默跳过它，
        // 而转成串至少能在清单上被看见
        assertChanged(rewrite(dsl).rewritten(), "nodes[save].config.args.code", "123", "123_copy", "表单编码加了后缀");
    }

    @Test
    void rewrite_nodesNotAnArray_isSkippedWithoutFailing() throws Exception {
        String dsl = """
                {"id":"wf_src","name":"源","nodes":{"oops":"不是数组"}}\
                """;

        WorkflowCopyRewriter.Result result = rewrite(dsl);

        // 这份 DSL 该由校验器拒，不该由「复制」替它报错（同非法 model 串那条的取向）
        assertTrue(result.rewritten().isEmpty(), "实际 %s".formatted(result.rewritten()));
        assertEquals("不是数组", out(result).at("/nodes/oops").asText());
    }

    @Test
    void rewrite_illegalDsl_throwsInsteadOfReturningSourceWithEmptyManifest() {
        // 🔴 这里必须抛而不是「认不出就当没有」：改写器的产物是要落库的，parse 不出来还吐一份原文
        //    等于把源 DSL 原样复制一遍 —— 冲突一个没改而清单是空的，那是一份「看起来干净」的假清单
        for (String bad : List.of("不是 JSON", "{\"id\":", "[1,2,3]", "   ", "")) {
            ServiceException ex = assertThrows(ServiceException.class, () -> rewrite(bad),
                    "非法 DSL 应当抛，而不是吐一份原文：%s".formatted(bad));
            // 错误码必须与 DslParser#parse（发布/落库那一支）同源：调用方不该因为走了哪一支
            // 而看到两种「DSL 解析不了」的说法
            assertEquals(WORKFLOW_DSL_INVALID.getCode(), ex.getCode(), "错误码不同源：%s".formatted(bad));
        }
        assertThrows(ServiceException.class, () -> rewrite(null), "null DSL 同样该拒");
        verifyNoInteractions(dataModelCreateValidator);
    }

}
