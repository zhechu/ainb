package cn.enlev.ainb.module.agent.tool.impl.formdata;

import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentTool;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentToolFunction;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.RiskLevel;
import cn.enlev.ainb.module.infra.api.formdesign.FormDesignApi;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具：修改某张表单里<b>一条已提交记录</b>的若干字段
 *
 * <h3>🔴 操作的是「数据行」，不是「表单定义」</h3>
 * 见 {@link FormDataGetTool} 的同名说明。改的是一条记录里的字段值，不是表单的结构。
 *
 * <h3>🔴 两层权限</h3>
 * 第 2 层动态门 {@code infra:form-design-run:{code}:update}。
 *
 * <h3>🔴🔴 读-合-写：这是本工具存在的全部理由</h3>
 * {@code FormDesignApi.updateData} 是<b>按 fieldMapping 全量覆盖</b>的 —— 传进去的 Map 里没有的字段，
 * 一律写 null。若把模型给的 data（通常只含「要改的那几个字段」）直接传下去，
 * <b>其余字段会被静默清空</b>，且接口返回成功、无任何报错。这是最坏的一类数据损坏。
 *
 * <p>对策：逐字镜像 {@code BpmOnlineFormDataHandler.update}（bpm 侧因同样的全量覆盖语义而采用的权威先例）——
 * 先 {@code getData} 读回整行，再把 data 覆盖到同名字段上，最后整体回写。
 * 效果是 <b>PATCH 语义</b>：只改传入的字段，没传的保持原值。模型无需、也不应传完整行。
 *
 * <p>行不存在时 {@code getData} 抛 {@code FORM_DESIGN_RUNTIME_DATA_NOT_EXISTS}，
 * 原样冒泡，绝不会走到 updateData（不会「更新一条不存在的记录」）。
 *
 * @author 走进新时代
 */
@Component
@AgentTool(
        name = FormDataUpdateTool.NAME,
        displayName = "修改表单数据",
        groupName = "表单数据",
        description = "修改某张表单里一条已提交记录的若干字段（PATCH 语义：只改 data 里给的字段，没给的保持原值不变）。"
                + "⚠️ 操作的是表单提交进来的数据行，不是表单定义 —— 改表单结构用 form_update。"
                + "按主键 pk 定位记录，data 只放要改的「字段名 → 新值」；不必传完整行，工具会先读回整行再合并",
        permission = "agent:assistant:use",
        risk = RiskLevel.MEDIUM)
@Slf4j
public class FormDataUpdateTool implements AgentToolFunction {

    public static final String NAME = "form_data_update";

    @Resource
    private FormDesignApi formDesignApi;
    @Resource
    private FormDataPermissionChecker permissionChecker;
    @Resource
    private FormDataParamResolver resolver;

    @Override
    public Object execute(Map<String, Object> params) {
        String code = resolver.resolveCode(params, NAME);
        String pk = resolver.resolvePk(params, NAME);
        Map<String, Object> data = resolver.resolveData(params, NAME);
        // 🔴 第 2 层动态数据权限门，必须在调 api 之前
        permissionChecker.check(code, FormDataPermissionChecker.ACTION_UPDATE);

        // 🔴 读-合-写，逐字镜像 BpmOnlineFormDataHandler.update（见类注释）
        Map<String, Object> merged = new LinkedHashMap<>();
        Map<String, Object> exists = formDesignApi.getData(code, pk);
        if (exists != null) {
            merged.putAll(exists);
        }
        // 入参覆盖同名字段，未提到的字段保留 → PATCH 语义
        merged.putAll(data);
        formDesignApi.updateData(code, pk, merged);
        log.info("[execute][AI 更新表单({}) 记录({}) 字段({})]", code, pk, data.keySet());

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("id", pk);
        resp.put("updatedFields", new ArrayList<>(data.keySet()));
        resp.put("message", "已更新表单 " + code + " 中记录 " + pk + " 的 " + data.size() + " 个字段");
        return resp;
    }

    /** 见 {@link AgentToolFunction#resultArrayKeys}：本工具全部返回分支里数组值的键（并集） */
    @Override
    public List<String> resultArrayKeys() {
        return List.of("updatedFields");
    }

    @Override
    public String paramsSchema() {
        return FormDataParamResolver.UPDATE_SCHEMA;
    }

}
