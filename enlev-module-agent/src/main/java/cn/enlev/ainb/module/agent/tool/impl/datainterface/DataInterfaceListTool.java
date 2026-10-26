package cn.enlev.ainb.module.agent.tool.impl.datainterface;

import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentTool;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentToolFunction;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.RiskLevel;
import cn.enlev.ainb.module.infra.api.datainterface.DataInterfaceApi;
import cn.enlev.ainb.module.infra.api.datainterface.dto.DataInterfaceSimpleRespDTO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具：列出平台已治理的<b>数据接口目录</b>（{@code infra_data_interface}）
 *
 * <h3>🔴 与 {@code datamodel_list}（物理表）/{@code form_list}（表单定义）的区分</h3>
 * 这里列的是「数据接口」—— 平台注册表里治理好的取数/写数出口（SQL 查询、HTTP 转发、静态 JSON），
 * 带参数签名与启用状态。AI 要用 {@code data_interface_query}/{@code data_interface_invoke}
 * 之前，先经本工具拿到准确的 code 与入参定义，别猜。
 *
 * <h3>🔴 目录很大，必须收敛</h3>
 * 现网 380+ 条（含大量测试数据），一次全量列出就是 token 炸弹。
 * 故支持 keyword 模糊过滤 + limit 钳制（缺省 20、上限 50，resolver 与 infra Impl 双保险）。
 *
 * <h3>权限口径</h3>
 * {@code agent:assistant:use}（网关校验）。只下发接口「签名」（code/name/入参定义），
 * 不下发 config/script 实现细节 —— 与 infra {@code /simple-list} 端点同一白名单口径。
 *
 * @author 走进新时代
 */
@Component
@AgentTool(
        name = DataInterfaceListTool.NAME,
        displayName = "查询数据接口目录",
        groupName = "数据接口",
        description = "列出平台的数据接口目录（已治理的 SQL 查询/HTTP 转发/静态 JSON 接口），"
                + "返回每个接口的编码、名称、类型、是否含写操作与入参定义。"
                + "调用 data_interface_query / data_interface_invoke 之前先用它拿准确的 code 和参数签名，不要猜。"
                + "⚠️ 目录可能很大，务必用 keyword 过滤（匹配编码/名称）并控制 limit",
        permission = "agent:assistant:use",
        risk = RiskLevel.READ_ONLY)
public class DataInterfaceListTool implements AgentToolFunction {

    public static final String NAME = "data_interface_list";

    @Resource
    private DataInterfaceApi dataInterfaceApi;
    @Resource
    private DataInterfaceParamResolver resolver;

    @Override
    public Object execute(Map<String, Object> params) {
        String keyword = resolver.resolveKeyword(params);
        boolean onlyReadOnly = resolver.resolveOnlyReadOnly(params);
        int limit = resolver.resolveLimit(params, NAME);
        List<DataInterfaceSimpleRespDTO> list = dataInterfaceApi.listInterfaces(keyword, onlyReadOnly, limit);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("total", list.size());
        resp.put("list", list);
        return resp;
    }

    /** 见 {@link AgentToolFunction#resultArrayKeys}：本工具全部返回分支里数组值的键（并集） */
    @Override
    public List<String> resultArrayKeys() {
        return List.of("list");
    }

    @Override
    public String paramsSchema() {
        return DataInterfaceParamResolver.LIST_SCHEMA;
    }

}
