package cn.enlev.ainb.module.agent.service.tooldef;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.framework.common.util.object.BeanUtils;
import cn.enlev.ainb.module.agent.controller.admin.tooldef.vo.AgentToolDefPageReqVO;
import cn.enlev.ainb.module.agent.controller.admin.tooldef.vo.AgentToolDefSaveReqVO;
import cn.enlev.ainb.module.agent.dal.dataobject.tooldef.AgentToolDefDO;
import cn.enlev.ainb.module.agent.dal.mysql.tooldef.AgentToolDefMapper;
import cn.enlev.ainb.module.agent.enums.ToolSourceEnum;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentToolFunction;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentToolRegistry;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.RiskLevel;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.datainterface.DataInterfaceToolConfig;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.datainterface.DataInterfaceToolFunction;
import cn.enlev.ainb.module.infra.api.datainterface.DataInterfaceApi;
import cn.enlev.ainb.module.infra.api.datainterface.dto.DataInterfaceSimpleRespDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.AGENT_TOOL_DEF_INTERFACE_INVALID;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.AGENT_TOOL_DEF_INVOKE_PERMISSION_MISSING;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.AGENT_TOOL_DEF_KEY_CONFLICT_CODE_TOOL;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.AGENT_TOOL_DEF_KEY_DUPLICATE;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.AGENT_TOOL_DEF_NOT_EXISTS;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.AGENT_TOOL_DEF_RISK_INVALID;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.AGENT_TOOL_SOURCE_UNSUPPORTED;

/**
 * 用户自定义工具目录 Service 实现
 *
 * <h3>🔴 自定义工具 = 数据接口的「AI 包装层」（2026-09-29 起）</h3>
 * 一行 {@code agent_tool_def} 只做两件事：把一个<b>已治理的数据接口</b>（{@code infra_data_interface}）
 * 以 AI 能理解的方式命名和描述，并为它声明风险与权限。真正的执行交给
 * {@code DataInterfaceApi}——SQL/HTTP 转发/静态 JSON、入参校验、只读判定、租户注入、系统表保护、
 * 版本与日志全在那边。此前这里另有一套自建 HTTP 出站（含 SSRF 守卫与凭据脱敏），与数据接口重复，已删除。
 *
 * @author 走进新时代
 */
@Service
@Validated
@Slf4j
public class AgentToolDefServiceImpl implements AgentToolDefService {

    /**
     * 校验绑定接口时向数据接口目录取的条数上限
     *
     * <p>{@code listInterfaces} 的 limit 在 infra 侧被钳制在 [1,50]，这里直接取上限：
     * 关键字传的是完整 code，命中集通常只有一两条，取满是为了防「code 是某个更长 code 的子串」时
     * 被别的结果挤出前 50 条而误判成「接口不存在」。
     */
    private static final int INTERFACE_LOOKUP_LIMIT = 50;

    /** 全能力（含写）执行数据接口所需的权限串，与 infra {@code /invoke} 端点的 @PreAuthorize 逐字一致 */
    private static final String INVOKE_PERMISSION = "infra:data-interface:invoke";

    @Resource
    private AgentToolDefMapper toolDefMapper;
    /** 用于登记时校验 tool_key 不与代码工具重名（registry-first，代码工具永远赢） */
    @Resource
    private AgentToolRegistry toolRegistry;
    /** buildFunction 时注入给执行器；登记时也用它确认绑定的接口确实存在且启用 */
    @Resource
    private DataInterfaceApi dataInterfaceApi;

    @Override
    public Long createToolDef(AgentToolDefSaveReqVO createReqVO) {
        DataInterfaceSimpleRespDTO bound = validateForSave(createReqVO, null);
        AgentToolDefDO toolDef = BeanUtils.toBean(createReqVO, AgentToolDefDO.class);
        // enabled 缺省视为启用（DO 列默认 b'1'，这里显式兜底避免 null 落库）
        if (toolDef.getEnabled() == null) {
            toolDef.setEnabled(true);
        }
        toolDef.setParamsSchema(effectiveParamsSchema(createReqVO.getParamsSchema(), bound));
        toolDefMapper.insert(toolDef);
        return toolDef.getId();
    }

    @Override
    public void updateToolDef(AgentToolDefSaveReqVO updateReqVO) {
        // selectById 走租户隔离，碰不到他租户的行
        AgentToolDefDO old = updateReqVO.getId() == null ? null : toolDefMapper.selectById(updateReqVO.getId());
        if (old == null) {
            throw exception(AGENT_TOOL_DEF_NOT_EXISTS, updateReqVO.getId());
        }
        DataInterfaceSimpleRespDTO bound = validateForSave(updateReqVO, updateReqVO.getId());
        AgentToolDefDO updateObj = BeanUtils.toBean(updateReqVO, AgentToolDefDO.class);
        updateObj.setParamsSchema(effectiveParamsSchema(updateReqVO.getParamsSchema(), bound));
        toolDefMapper.updateById(updateObj);
    }

    @Override
    public void deleteToolDef(Long id) {
        validateExists(id);
        toolDefMapper.deleteById(id);
    }

    @Override
    public AgentToolDefDO getToolDef(Long id) {
        AgentToolDefDO toolDef = toolDefMapper.selectById(id);
        if (toolDef == null) {
            throw exception(AGENT_TOOL_DEF_NOT_EXISTS, id);
        }
        return toolDef;
    }

    @Override
    public PageResult<AgentToolDefDO> getToolDefPage(AgentToolDefPageReqVO pageReqVO) {
        return toolDefMapper.selectPage(pageReqVO);
    }

    @Override
    public List<AgentToolDefDO> getEnabledToolDefList() {
        return toolDefMapper.selectEnabledList();
    }

    @Override
    public List<AgentToolDefDO> getToolDefListIncludingDisabled() {
        return toolDefMapper.selectListIncludingDisabled();
    }

    @Override
    public List<DataInterfaceSimpleRespDTO> listBindableInterfaces(String keyword) {
        // 只列启用中的接口（api 层语义即如此）；目录现网 380+ 条，keyword 收敛结果、limit 取上限
        return dataInterfaceApi.listInterfaces(StrUtil.trimToNull(keyword), false, INTERFACE_LOOKUP_LIMIT);
    }

    @Override
    public GatewayView resolveForGateway(String toolKey) {
        if (StrUtil.isBlank(toolKey)) {
            return null;
        }
        // 🔴 租户隔离：selectByToolKey 走 TenantLineInnerInterceptor 自动拼 tenant_id=?，
        // 在调用方（网关）的租户上下文里跑，A 租户的工具绝不会被 B 租户解析到。
        AgentToolDefDO toolDef = toolDefMapper.selectByToolKey(toolKey);
        if (toolDef == null) {
            return null;
        }
        boolean enabled = Boolean.TRUE.equals(toolDef.getEnabled());
        return new GatewayView(enabled, splitPermissions(toolDef.getPermissions()),
                RiskLevel.parseOrHigh(toolDef.getRiskLevel()));
    }

    @Override
    public AgentToolFunction buildFunction(String toolKey) {
        if (StrUtil.isBlank(toolKey)) {
            return null;
        }
        AgentToolDefDO toolDef = toolDefMapper.selectByToolKey(toolKey);
        if (toolDef == null) {
            return null;
        }
        String source = toolDef.getSource();
        if (!ToolSourceEnum.isDataInterface(source)) {
            throw exception(AGENT_TOOL_SOURCE_UNSUPPORTED, source);
        }
        DataInterfaceToolConfig config = DataInterfaceToolConfig.parse(toolDef.getConfig(), toolKey);
        return new DataInterfaceToolFunction(config, dataInterfaceApi, toolKey, toolDef.getParamsSchema());
    }

    // ==================== 校验 ====================

    /**
     * 新增/修改共用校验
     *
     * @param selfId 修改时传自身 id（唯一性校验排除自己）；新增传 null
     * @return 绑定的那个数据接口（供 {@link #effectiveParamsSchema} 派生参数说明），必非 null
     */
    private DataInterfaceSimpleRespDTO validateForSave(AgentToolDefSaveReqVO reqVO, Long selfId) {
        String toolKey = reqVO.getToolKey();
        // 1) 🔴 不得与代码工具重名：registry-first 下代码工具永远赢，用户工具即便登记成功也调不到。
        //    与其让它「配了不生效且无提示」（平台头号缺陷），不如登记时就拦下。
        if (toolRegistry.get(toolKey) != null) {
            throw exception(AGENT_TOOL_DEF_KEY_CONFLICT_CODE_TOOL, toolKey);
        }
        // 2) 同租户唯一（排除自己）
        AgentToolDefDO existing = toolDefMapper.selectByToolKey(toolKey);
        if (existing != null && !existing.getId().equals(selfId)) {
            throw exception(AGENT_TOOL_DEF_KEY_DUPLICATE, toolKey);
        }
        // 3) 风险等级必填且合法（会改数据的接口不该被默认当只读）
        validateRiskLevel(reqVO.getRiskLevel());
        // 4) source 只放行数据接口
        String source = reqVO.getSource();
        if (!ToolSourceEnum.isDataInterface(source)) {
            throw exception(AGENT_TOOL_SOURCE_UNSUPPORTED, source);
        }
        // 5) config 可解析（内含 code 非空、mode 合法校验）
        DataInterfaceToolConfig config = DataInterfaceToolConfig.parse(reqVO.getConfig(), toolKey);
        // 6) 🔴 绑定的接口必须真的存在且启用，否则就是「配了不生效且无提示」
        DataInterfaceSimpleRespDTO bound = lookupInterface(config.getCode(), toolKey);
        // 7) 🔴 安全边界：全能力（含写）执行必须由网关把守，而网关只认 permissions 列。
        //    DataInterfaceApi 自己不做任何权限校验，漏声明 = 任何登录用户都能经 AI 改库。
        if (config.isInvoke() && !splitPermissions(reqVO.getPermissions()).contains(INVOKE_PERMISSION)) {
            throw exception(AGENT_TOOL_DEF_INVOKE_PERMISSION_MISSING, toolKey);
        }
        // 8) 只读模式却绑了写型接口：queryData 运行期会被 infra 拒，登记时就说清楚，别留到调用
        if (!config.isInvoke() && Boolean.TRUE.equals(bound.getWriteOperation())) {
            throw exception(AGENT_TOOL_DEF_INTERFACE_INVALID, toolKey,
                    "接口(" + bound.getCode() + ")含写操作，只读模式执行不了它；请把执行方式改为「全能力执行」");
        }
        // 9) 🔴 反过来那一档：声明「只读」却选了「全能力执行」（可写）。第 8 条管的是
        //    「执行方式与接口能力对不上」，本条管的是「风险声明与执行方式对不上」——两条缺一不可。
        //
        //    为什么必须在<b>登记</b>时就拦：READ_ONLY 是四道只读闸（AgentDefServiceImpl 发布闸、
        //    AgentLoopGraphBuilder 编译期/运行期闸、AgentChatToolLoop 运行期闸）唯一的放行判据，
        //    而这四道闸守护的都是<b>无人值守</b>的循环——聊天工具循环与 agent_loop 自主循环里
        //    没有 Plan 确认兜着。一行 risk_level=READ_ONLY + mode=INVOKE 的记录，
        //    就等于让模型可以在没有任何人点「确认」的情况下经 AI 改库。
        //
        //    ⚠️ B2 之前这个洞<b>够不着</b>：那四道闸只查内置注册表，自定义工具一律被「未登记」挡在外面。
        //    B2 放开自定义工具之后它就真的通了，故必须与放开<b>同一批</b>堵上——
        //    内置工具那边对应的防线是「闸只读 getDeclaredRisk() 声明值，agent_tool_override 压不动它」，
        //    而自定义工具<b>没有覆盖表</b>，risk_level 那一列就是它的声明、搬不动它也就无从加固，
        //    唯一能做的是不让「只读」这个声明与「可写」这个执行方式同时落库。
        if (config.isInvoke() && RiskLevel.READ_ONLY.name().equals(StrUtil.trimToEmpty(reqVO.getRiskLevel()))) {
            throw exception(AGENT_TOOL_DEF_INTERFACE_INVALID, toolKey,
                    "风险等级声明为「只读」，执行方式却是「全能力执行」（可写），二者矛盾。"
                            + "只读工具会被无人值守的 AI 循环（聊天工具循环 / agent_loop）自动执行、不经用户确认，"
                            + "故请按接口的真实能力选风险等级，或把执行方式改为「只读执行」");
        }
        return bound;
    }

    /**
     * 按 code 在启用中的数据接口目录里找那一条
     *
     * <p>api 层没有「按 code 取单条」的方法（{@code queryData/invokeData} 会<b>执行</b>接口，
     * 校验期绝不能调），故用 {@code listInterfaces(code, ...)} 做关键字检索再精确比对 code。
     * 该方法只返回<b>启用中</b>的接口，所以「查不到」天然覆盖了「已停用」——正是我们要的语义。
     */
    private DataInterfaceSimpleRespDTO lookupInterface(String code, String toolKey) {
        List<DataInterfaceSimpleRespDTO> candidates =
                dataInterfaceApi.listInterfaces(code, false, INTERFACE_LOOKUP_LIMIT);
        if (CollUtil.isNotEmpty(candidates)) {
            for (DataInterfaceSimpleRespDTO item : candidates) {
                if (code.equals(item.getCode())) {
                    return item;
                }
            }
        }
        throw exception(AGENT_TOOL_DEF_INTERFACE_INVALID, toolKey,
                "数据接口(" + code + ")不存在或未启用，请先到「基础设施 → 数据管理 → 接口管理」确认");
    }

    /**
     * 参数说明：管理员填了就用他填的；留空则按绑定接口的入参定义自动生成
     *
     * <p>🔴 为什么要自动生成：{@code paramsSchema} 是<b>唯一</b>告诉模型「该传哪些入参」的地方。
     * 留空的话模型只能瞎猜键名，接口报「缺少必填参数 xx」，它照着错误再试，又是一轮计费。
     * 而接口的入参定义（名称/类型/必填/说明）在数据接口那边是现成的、权威的，抄过来即可。
     * 生成的是<b>快照</b>：接口改了入参不会自动跟过来，管理员可编辑覆盖，故这里只在留空时补。
     */
    private String effectiveParamsSchema(String declared, DataInterfaceSimpleRespDTO bound) {
        if (StrUtil.isNotBlank(declared)) {
            return declared;
        }
        List<DataInterfaceSimpleRespDTO.Param> params = bound.getParams();
        if (CollUtil.isEmpty(params)) {
            return "无入参";
        }
        return params.stream()
                .map(p -> {
                    StringBuilder sb = new StringBuilder();
                    sb.append(StrUtil.nullToEmpty(p.getName()))
                            .append(Boolean.TRUE.equals(p.getRequired()) ? "（必填" : "（可选");
                    if (StrUtil.isNotBlank(p.getType())) {
                        sb.append("，").append(p.getType());
                    }
                    sb.append("）");
                    String label = StrUtil.blankToDefault(p.getLabel(), p.getRemark());
                    if (StrUtil.isNotBlank(label)) {
                        sb.append("：").append(label);
                    }
                    return sb.toString();
                })
                .collect(Collectors.joining("\n"));
    }

    private void validateRiskLevel(String riskLevel) {
        if (StrUtil.isBlank(riskLevel)) {
            throw exception(AGENT_TOOL_DEF_RISK_INVALID, "(空)");
        }
        try {
            RiskLevel.valueOf(riskLevel.trim());
        } catch (IllegalArgumentException ex) {
            throw exception(AGENT_TOOL_DEF_RISK_INVALID, riskLevel);
        }
    }

    private void validateExists(Long id) {
        // selectById 同样租户隔离：碰不到他租户的行
        if (id == null || toolDefMapper.selectById(id) == null) {
            throw exception(AGENT_TOOL_DEF_NOT_EXISTS, id);
        }
    }

    /** 逗号分隔权限串 → 去空白、去重、不可变列表；空串表示无需额外权限（登录即可调用） */
    private List<String> splitPermissions(String raw) {
        if (StrUtil.isBlank(raw)) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(StrUtil::isNotBlank)
                .distinct()
                .collect(Collectors.toUnmodifiableList());
    }

}
