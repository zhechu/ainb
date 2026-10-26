package cn.enlev.ainb.module.agent.service.tooldef;

import cn.enlev.ainb.framework.common.exception.ServiceException;
import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.agent.controller.admin.tooldef.vo.AgentToolDefSaveReqVO;
import cn.enlev.ainb.module.agent.dal.dataobject.tooldef.AgentToolDefDO;
import cn.enlev.ainb.module.agent.dal.mysql.tooldef.AgentToolDefMapper;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentTool;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentToolFunction;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentToolRegistry;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.RiskLevel;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.datainterface.DataInterfaceToolFunction;
import cn.enlev.ainb.module.infra.api.datainterface.DataInterfaceApi;
import cn.enlev.ainb.module.infra.api.datainterface.dto.DataInterfaceSimpleRespDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用户自定义工具目录 Service 的单元测试
 *
 * <p>mock mapper（不连库）+ mock 数据接口 api + 真 registry（验代码工具重名）。
 * 重点测「登记时的各种拒绝」——目录的价值全在把非法/越权/绑错接口的登记挡在门外。
 *
 * @author 走进新时代
 */
public class AgentToolDefServiceTest extends BaseMockitoUnitTest {

    /** 一份存在于目录里的只读接口（各用例按需换成写型接口） */
    private static final String READONLY_CODE = "order_stat_daily";
    private static final String WRITE_CODE = "order_stat_reset";
    private static final String INVOKE_PERMISSION = "infra:data-interface:invoke";

    @InjectMocks
    private AgentToolDefServiceImpl service;

    @Mock
    private AgentToolDefMapper toolDefMapper;
    @Mock
    private DataInterfaceApi dataInterfaceApi;
    @Spy
    private AgentToolRegistry toolRegistry = new AgentToolRegistry();

    @AgentTool(name = "demo_code_tool", permission = "infra:data-model:query", risk = RiskLevel.READ_ONLY)
    static class DemoCodeTool implements AgentToolFunction {
        @Override
        public Object execute(Map<String, Object> params) {
            return null;
        }
    }

    @BeforeEach
    void setUp() {
        toolRegistry.register(DemoCodeTool.class, new DemoCodeTool());
        // 目录默认空：没登记过的 code 自然「查不到」，这正是 testCreateInterfaceNotFound 想要的语义。
        // 🔴 必须 lenient：重名/风险/source 非法等用例在「查目录」之前就抛了，严格模式会把这条打桩
        //    报成 UnnecessaryStubbing，让测试红在一个与被测逻辑无关的地方。
        lenient().when(dataInterfaceApi.listInterfaces(any(), eq(false), anyInt())).thenReturn(List.of());
        // 目录里再放一个只读接口与一个写型接口，各带一份入参定义
        stubInterface(READONLY_CODE, false, param("statDate", "DATE", true, "统计日期"));
        stubInterface(WRITE_CODE, true, param("orderId", "LONG", true, "订单编号"));
    }

    private DataInterfaceSimpleRespDTO.Param param(String name, String type, boolean required, String label) {
        DataInterfaceSimpleRespDTO.Param p = new DataInterfaceSimpleRespDTO.Param();
        p.setName(name);
        p.setType(type);
        p.setRequired(required);
        p.setLabel(label);
        return p;
    }

    /** 让 listInterfaces(该 code, ...) 返回一条命中；未 stub 的 code 即「目录里查不到」 */
    private void stubInterface(String code, boolean writeOperation, DataInterfaceSimpleRespDTO.Param... params) {
        DataInterfaceSimpleRespDTO dto = new DataInterfaceSimpleRespDTO();
        dto.setCode(code);
        dto.setName(code);
        dto.setWriteOperation(writeOperation);
        dto.setParams(List.of(params));
        // 同 setUp 的理由：登记前的校验有好几步，不少用例根本走不到查目录
        lenient().when(dataInterfaceApi.listInterfaces(eq(code), eq(false), anyInt())).thenReturn(List.of(dto));
    }

    /** 一份合法的登记 VO（绑只读接口、QUERY 模式）；各用例按需改字段 */
    private AgentToolDefSaveReqVO validReq() {
        AgentToolDefSaveReqVO req = new AgentToolDefSaveReqVO();
        req.setToolKey("sales_daily_report");
        req.setDisplayName("销售日报");
        req.setSource("DATA_INTERFACE");
        req.setRiskLevel("READ_ONLY");
        req.setPermissions("agent:assistant:use");
        req.setEnabled(true);
        req.setConfig("{\"code\":\"" + READONLY_CODE + "\",\"mode\":\"QUERY\"}");
        return req;
    }

    // ==================== create：各种拒绝 ====================

    @Test
    @DisplayName("🔴 与代码工具重名 → 拒（registry-first 下用户工具永远抢不到这个名字）")
    void testCreateConflictCodeTool() {
        AgentToolDefSaveReqVO req = validReq();
        req.setToolKey("demo_code_tool");
        ServiceException ex = assertThrows(ServiceException.class, () -> service.createToolDef(req));
        assertTrue(ex.getMessage().contains("重名"), ex.getMessage());
        verify(toolDefMapper, never()).insert(any(AgentToolDefDO.class));
    }

    @Test
    @DisplayName("🔴 同租户 tool_key 重复 → 拒")
    void testCreateDuplicateKey() {
        AgentToolDefDO existing = new AgentToolDefDO();
        existing.setId(7L);
        existing.setToolKey("sales_daily_report");
        when(toolDefMapper.selectByToolKey("sales_daily_report")).thenReturn(existing);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.createToolDef(validReq()));
        assertTrue(ex.getMessage().contains("已存在"), ex.getMessage());
        verify(toolDefMapper, never()).insert(any(AgentToolDefDO.class));
    }

    @Test
    @DisplayName("🔴 风险等级缺失/非法 → 拒（会改数据的接口不该被默认当只读）")
    void testCreateRiskInvalid() {
        AgentToolDefSaveReqVO blank = validReq();
        blank.setRiskLevel("");
        assertTrue(assertThrows(ServiceException.class, () -> service.createToolDef(blank))
                .getMessage().contains("风险等级"));

        AgentToolDefSaveReqVO bogus = validReq();
        bogus.setRiskLevel("SUPER_SAFE");
        assertTrue(assertThrows(ServiceException.class, () -> service.createToolDef(bogus))
                .getMessage().contains("风险等级"));
        verify(toolDefMapper, never()).insert(any(AgentToolDefDO.class));
    }

    @Test
    @DisplayName("🔴 source 是预留位（FORM/HTTP）→ 拒「暂不支持执行」")
    void testCreateUnsupportedSource() {
        for (String source : List.of("FORM", "SCRIPT", "HTTP")) {
            AgentToolDefSaveReqVO req = validReq();
            req.setSource(source);
            ServiceException ex = assertThrows(ServiceException.class, () -> service.createToolDef(req));
            assertTrue(ex.getMessage().contains("暂不支持"), source + " → " + ex.getMessage());
        }
    }

    @Test
    @DisplayName("🔴 config 不可解析 / 缺 code / mode 非法 → 拒「数据接口不合法」")
    void testCreateConfigInvalid() {
        AgentToolDefSaveReqVO badJson = validReq();
        badJson.setConfig("this is not json");
        assertTrue(assertThrows(ServiceException.class, () -> service.createToolDef(badJson))
                .getMessage().contains("不合法"));

        AgentToolDefSaveReqVO noCode = validReq();
        noCode.setConfig("{\"mode\":\"QUERY\"}");
        assertTrue(assertThrows(ServiceException.class, () -> service.createToolDef(noCode))
                .getMessage().contains("不合法"));

        AgentToolDefSaveReqVO badMode = validReq();
        badMode.setConfig("{\"code\":\"" + READONLY_CODE + "\",\"mode\":\"DELETE_EVERYTHING\"}");
        assertTrue(assertThrows(ServiceException.class, () -> service.createToolDef(badMode))
                .getMessage().contains("不合法"));
    }

    @Test
    @DisplayName("🔴 绑的接口不存在/未启用 → 拒（正是「配了不生效且无提示」要挡的形状）")
    void testCreateInterfaceNotFound() {
        AgentToolDefSaveReqVO req = validReq();
        req.setConfig("{\"code\":\"no_such_interface\",\"mode\":\"QUERY\"}");
        ServiceException ex = assertThrows(ServiceException.class, () -> service.createToolDef(req));
        assertTrue(ex.getMessage().contains("不存在或未启用"), ex.getMessage());
        verify(toolDefMapper, never()).insert(any(AgentToolDefDO.class));
    }

    @Test
    @DisplayName("🔴 关键字命中了别的接口（子串巧合）→ 仍拒：只认 code 精确相等")
    void testCreateInterfaceCodeMustMatchExactly() {
        // 模糊查找 "order_stat" 确实会捞回 code=order_stat_daily，但登记的是 order_stat，
        // 若只判「返回列表非空」就会放行一个绑错接口的工具 —— 运行期才炸，且炸在别的会话里
        DataInterfaceSimpleRespDTO near = new DataInterfaceSimpleRespDTO();
        near.setCode(READONLY_CODE);
        near.setName("销售日报");
        lenient().when(dataInterfaceApi.listInterfaces(eq("order_stat"), eq(false), anyInt()))
                .thenReturn(List.of(near));

        AgentToolDefSaveReqVO req = validReq();
        req.setConfig("{\"code\":\"order_stat\",\"mode\":\"QUERY\"}");
        assertTrue(assertThrows(ServiceException.class, () -> service.createToolDef(req))
                .getMessage().contains("不存在或未启用"));
    }

    @Test
    @DisplayName("🔴 安全边界：INVOKE（含写）却没声明 invoke 权限 → 拒")
    void testCreateInvokeWithoutPermission() {
        AgentToolDefSaveReqVO req = validReq();
        req.setConfig("{\"code\":\"" + WRITE_CODE + "\",\"mode\":\"INVOKE\"}");
        req.setRiskLevel("HIGH");
        req.setPermissions("agent:assistant:use"); // 少了 infra:data-interface:invoke
        ServiceException ex = assertThrows(ServiceException.class, () -> service.createToolDef(req));
        assertTrue(ex.getMessage().contains(INVOKE_PERMISSION), ex.getMessage());
        verify(toolDefMapper, never()).insert(any(AgentToolDefDO.class));
    }

    @Test
    @DisplayName("QUERY 模式绑了写型接口 → 拒（运行期必被 infra 拒，登记时就说清）")
    void testCreateQueryModeOnWriteInterface() {
        AgentToolDefSaveReqVO req = validReq();
        req.setConfig("{\"code\":\"" + WRITE_CODE + "\",\"mode\":\"QUERY\"}");
        ServiceException ex = assertThrows(ServiceException.class, () -> service.createToolDef(req));
        assertTrue(ex.getMessage().contains("含写操作"), ex.getMessage());
    }

    /**
     * 🔴 B2 连带补上的那一档：声明「只读」却选了「全能力执行」（INVOKE）→ 拒。
     *
     * <p>为什么非补不可：{@code READ_ONLY} 是四道<b>无人值守</b>只读闸（发布期
     * {@code AgentDefServiceImpl#validateToolKeys}、聊天工具循环、agent_loop 编译期与运行期）
     * 唯一的准入判据 —— 判据是「声明风险级不要求确认」，不看别的。而 INVOKE 模式意味着
     * 这个工具走 {@code invokeData}，<b>能写</b>。两者凑在一行，就等于登记出一个
     * 「会被 AI 循环自动执行、不经用户确认」的写工具。
     *
     * <p>B2 之前这个洞走不到：那四道闸一律拒自定义工具，所以「只读」声明再怎么填也进不了循环。
     * 放宽准入的同时不补这一档，就是把一个响亮的报错换成一次静默的越权写入。
     *
     * <p>内置工具的等价防线是「闸读 {@code getDeclaredRisk()}，覆盖行动不了它」；
     * 自定义工具<b>没有覆盖表</b>（{@code AgentToolRegistry#sanitize} 会丢掉指向未注册工具的覆盖行），
     * {@code risk_level} 那一列就是它的声明、也就无从「声明与能力分离」—— 唯一能做的
     * 是在登记入口拒绝这对自相矛盾的组合。
     *
     * <p>🔴 刻意用<b>只读</b>接口（{@code READONLY_CODE}）而不是写型接口来触发：
     * 判据是「声明与执行方式这对组合」，不是「接口当前是否含写操作」。
     * 接口定义日后能改（把它改成写型不会回头改这一行的 risk_level），
     * 而执行方式选 INVOKE 的那一刻，这个工具就已经<b>有能力</b>写了。
     *
     * <p><b>反空转对照</b>：把判据写成「一律拒绝 READ_ONLY」会让 {@code testCreateSuccess} 变红，
     * 写成「一律拒绝 INVOKE」会让 {@code testCreateInvokeWithPermission} 变红 —— 两条都已在下面那节，
     * 故这里不必再补一条近乎重复的放行用例。
     */
    @Test
    @DisplayName("🔴 声明 READ_ONLY 却选 INVOKE（可写）→ 拒：只读是四道无人值守闸唯一的准入判据")
    void testCreateReadOnlyRiskWithInvokeMode() {
        AgentToolDefSaveReqVO req = validReq(); // READONLY_CODE，riskLevel 已是 READ_ONLY
        req.setConfig("{\"code\":\"" + READONLY_CODE + "\",\"mode\":\"INVOKE\"}");
        // 补上 invoke 权限，好让第 7 步先过 —— 本用例要钉的是第 9 步，不能被前一步的报错顶掉
        req.setPermissions("agent:assistant:use," + INVOKE_PERMISSION);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.createToolDef(req));
        assertTrue(ex.getMessage().contains("只读") && ex.getMessage().contains("全能力执行"),
                "报错要把矛盾的两头都点出来，否则管理员不知道该改哪一头。实际：" + ex.getMessage());
        assertTrue(ex.getMessage().contains("无人值守"),
                "报错要说清后果为什么严重（会被 AI 循环自动执行）。实际：" + ex.getMessage());
        verify(toolDefMapper, never()).insert(any(AgentToolDefDO.class));
    }

    // ==================== create：成功 ====================

    @Test
    @DisplayName("合法登记 → 落库并返回 id；enabled 缺省兜底为 true")
    void testCreateSuccess() {
        doAnswer(inv -> {
            AgentToolDefDO saved = inv.getArgument(0);
            saved.setId(123L);
            assertEquals(Boolean.TRUE, saved.getEnabled(), "enabled 缺省应兜底为 true");
            return 1;
        }).when(toolDefMapper).insert(any(AgentToolDefDO.class));

        AgentToolDefSaveReqVO req = validReq();
        req.setEnabled(null); // 缺省
        assertEquals(123L, service.createToolDef(req));
    }

    @Test
    @DisplayName("paramsSchema 留空 → 按接口入参定义自动生成（否则模型只能瞎猜键名）")
    void testCreateAutoFillsParamsSchema() {
        doAnswer(inv -> {
            AgentToolDefDO saved = inv.getArgument(0);
            saved.setId(124L);
            assertTrue(saved.getParamsSchema().contains("statDate"), saved.getParamsSchema());
            assertTrue(saved.getParamsSchema().contains("必填"), saved.getParamsSchema());
            return 1;
        }).when(toolDefMapper).insert(any(AgentToolDefDO.class));

        AgentToolDefSaveReqVO req = validReq();
        req.setParamsSchema(null);
        service.createToolDef(req);
    }

    @Test
    @DisplayName("paramsSchema 管理员填了 → 用他填的，不覆盖")
    void testCreateKeepsDeclaredParamsSchema() {
        doAnswer(inv -> {
            AgentToolDefDO saved = inv.getArgument(0);
            saved.setId(125L);
            assertEquals("管理员手写的说明", saved.getParamsSchema());
            return 1;
        }).when(toolDefMapper).insert(any(AgentToolDefDO.class));

        AgentToolDefSaveReqVO req = validReq();
        req.setParamsSchema("管理员手写的说明");
        service.createToolDef(req);
    }

    @Test
    @DisplayName("INVOKE + 声明了 invoke 权限 → 放行（写工具本就该能登记，靠网关与 Plan 确认管住）")
    void testCreateInvokeWithPermission() {
        doAnswer(inv -> {
            ((AgentToolDefDO) inv.getArgument(0)).setId(126L);
            return 1;
        }).when(toolDefMapper).insert(any(AgentToolDefDO.class));

        AgentToolDefSaveReqVO req = validReq();
        req.setToolKey("order_reset");
        req.setConfig("{\"code\":\"" + WRITE_CODE + "\",\"mode\":\"INVOKE\"}");
        req.setRiskLevel("HIGH");
        req.setPermissions("agent:assistant:use," + INVOKE_PERMISSION);
        assertEquals(126L, service.createToolDef(req));
    }

    // ==================== resolveForGateway ====================

    @Test
    @DisplayName("resolveForGateway：返回 enabled + 拆分后的权限列表")
    void testResolveForGateway() {
        AgentToolDefDO toolDef = new AgentToolDefDO();
        toolDef.setEnabled(true);
        toolDef.setPermissions("a:query, b:update ,a:query"); // 含空白与重复
        when(toolDefMapper.selectByToolKey("k")).thenReturn(toolDef);

        AgentToolDefService.GatewayView view = service.resolveForGateway("k");
        assertTrue(view.enabled());
        assertEquals(List.of("a:query", "b:update"), view.permissions(), "应去空白、去重");
    }

    @Test
    @DisplayName("resolveForGateway：风险缺失/非法 → 保守回落 HIGH（宁可多 mock 一次，不可真跑出副作用）")
    void testResolveForGatewayRiskFallback() {
        AgentToolDefDO blankRisk = new AgentToolDefDO();
        blankRisk.setEnabled(true);
        when(toolDefMapper.selectByToolKey("k")).thenReturn(blankRisk);
        assertEquals(RiskLevel.HIGH, service.resolveForGateway("k").riskLevel());

        AgentToolDefDO bogusRisk = new AgentToolDefDO();
        bogusRisk.setEnabled(true);
        bogusRisk.setRiskLevel("SUPER_SAFE");
        when(toolDefMapper.selectByToolKey("k")).thenReturn(bogusRisk);
        assertEquals(RiskLevel.HIGH, service.resolveForGateway("k").riskLevel());
    }

    @Test
    @DisplayName("resolveForGateway：权限空串 → 空列表（登录即可调用）；停用 → enabled=false")
    void testResolveForGatewayEmptyPermsAndDisabled() {
        AgentToolDefDO disabled = new AgentToolDefDO();
        disabled.setEnabled(false);
        disabled.setPermissions("");
        when(toolDefMapper.selectByToolKey("k")).thenReturn(disabled);

        AgentToolDefService.GatewayView view = service.resolveForGateway("k");
        assertFalse(view.enabled());
        assertTrue(view.permissions().isEmpty());
    }

    @Test
    @DisplayName("resolveForGateway：查不到 / 空 key → null（网关据此报「工具不存在」）")
    void testResolveForGatewayNotFound() {
        when(toolDefMapper.selectByToolKey("nope")).thenReturn(null);
        assertNull(service.resolveForGateway("nope"));
        assertNull(service.resolveForGateway(""));
        assertNull(service.resolveForGateway(null));
    }

    // ==================== buildFunction ====================

    @Test
    @DisplayName("buildFunction：DATA_INTERFACE → 构造出 DataInterfaceToolFunction")
    void testBuildFunctionDataInterface() {
        AgentToolDefDO toolDef = new AgentToolDefDO();
        toolDef.setToolKey("sales_daily_report");
        toolDef.setSource("DATA_INTERFACE");
        toolDef.setConfig("{\"code\":\"" + READONLY_CODE + "\",\"mode\":\"QUERY\"}");
        toolDef.setParamsSchema("statDate（必填）");
        when(toolDefMapper.selectByToolKey("sales_daily_report")).thenReturn(toolDef);

        AgentToolFunction fn = service.buildFunction("sales_daily_report");
        assertInstanceOf(DataInterfaceToolFunction.class, fn);
        assertEquals("statDate（必填）", fn.paramsSchema());
    }

    @Test
    @DisplayName("buildFunction：执行体把入参原样交给 api 的 queryData（只读模式）")
    void testBuildFunctionExecutesQuery() {
        AgentToolDefDO toolDef = new AgentToolDefDO();
        toolDef.setToolKey("sales_daily_report");
        toolDef.setSource("DATA_INTERFACE");
        toolDef.setConfig("{\"code\":\"" + READONLY_CODE + "\",\"mode\":\"QUERY\"}");
        when(toolDefMapper.selectByToolKey("sales_daily_report")).thenReturn(toolDef);
        when(dataInterfaceApi.queryData(anyString(), any())).thenReturn("ok");

        Object result = service.buildFunction("sales_daily_report")
                .execute(Map.of("statDate", "2026-09-29"));

        assertEquals("ok", result);
        verify(dataInterfaceApi).queryData(eq(READONLY_CODE), eq(Map.of("statDate", "2026-09-29")));
        verify(dataInterfaceApi, never()).invokeData(anyString(), any());
    }

    @Test
    @DisplayName("buildFunction：INVOKE 模式走 invokeData（含写）")
    void testBuildFunctionExecutesInvoke() {
        AgentToolDefDO toolDef = new AgentToolDefDO();
        toolDef.setToolKey("order_reset");
        toolDef.setSource("DATA_INTERFACE");
        toolDef.setConfig("{\"code\":\"" + WRITE_CODE + "\",\"mode\":\"INVOKE\"}");
        when(toolDefMapper.selectByToolKey("order_reset")).thenReturn(toolDef);
        when(dataInterfaceApi.invokeData(anyString(), any())).thenReturn(1);

        service.buildFunction("order_reset").execute(Map.of("orderId", 5L));

        verify(dataInterfaceApi).invokeData(eq(WRITE_CODE), eq(Map.of("orderId", 5L)));
        verify(dataInterfaceApi, never()).queryData(anyString(), any());
    }

    @Test
    @DisplayName("buildFunction：入参被多包了一层 params → 脱掉那层（与通用数据接口工具的形状一致）")
    void testBuildFunctionUnwrapsNestedParams() {
        AgentToolDefDO toolDef = new AgentToolDefDO();
        toolDef.setToolKey("sales_daily_report");
        toolDef.setSource("DATA_INTERFACE");
        toolDef.setConfig("{\"code\":\"" + READONLY_CODE + "\",\"mode\":\"QUERY\"}");
        when(toolDefMapper.selectByToolKey("sales_daily_report")).thenReturn(toolDef);
        when(dataInterfaceApi.queryData(anyString(), any())).thenReturn("ok");

        service.buildFunction("sales_daily_report")
                .execute(Map.of("params", Map.of("statDate", "2026-09-29")));

        verify(dataInterfaceApi).queryData(eq(READONLY_CODE), eq(Map.of("statDate", "2026-09-29")));
    }

    @Test
    @DisplayName("buildFunction：预留来源（FORM）→ 抛「暂不支持执行」")
    void testBuildFunctionUnsupportedSource() {
        AgentToolDefDO toolDef = new AgentToolDefDO();
        toolDef.setSource("FORM");
        toolDef.setConfig("{}");
        when(toolDefMapper.selectByToolKey("form_tool")).thenReturn(toolDef);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.buildFunction("form_tool"));
        assertTrue(ex.getMessage().contains("暂不支持"), ex.getMessage());
    }

    @Test
    @DisplayName("buildFunction：查不到 / 空 key → null（执行器据此报「工具不存在」）")
    void testBuildFunctionNotFound() {
        when(toolDefMapper.selectByToolKey("nope")).thenReturn(null);
        assertNull(service.buildFunction("nope"));
        assertNull(service.buildFunction(""));
        assertNull(service.buildFunction(null));
    }

    // ==================== listBindableInterfaces ====================

    @Test
    @DisplayName("listBindableInterfaces：关键字透传给 api，只列启用中的接口")
    void testListBindableInterfaces() {
        when(dataInterfaceApi.listInterfaces(eq("order"), eq(false), anyInt()))
                .thenReturn(List.of(new DataInterfaceSimpleRespDTO()));

        assertEquals(1, service.listBindableInterfaces(" order ").size());
        verify(dataInterfaceApi).listInterfaces(eq("order"), eq(false), anyInt());
    }

    // ==================== get / delete ====================

    @Test
    @DisplayName("getToolDef：查不到 → 抛「不存在」")
    void testGetNotExists() {
        when(toolDefMapper.selectById(1L)).thenReturn(null);
        assertTrue(assertThrows(ServiceException.class, () -> service.getToolDef(1L))
                .getMessage().contains("不存在"));
    }

    @Test
    @DisplayName("deleteToolDef：先校验存在再删")
    void testDeleteValidateExists() {
        when(toolDefMapper.selectById(1L)).thenReturn(null);
        assertThrows(ServiceException.class, () -> service.deleteToolDef(1L));
        verify(toolDefMapper, never()).deleteById(any());
    }

}
