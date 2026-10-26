package cn.enlev.ainb.module.agent.tool.impl.datamodel;

import cn.enlev.ainb.framework.common.exception.ServiceException;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentTool;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentToolFunction;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.RiskLevel;
import cn.enlev.ainb.module.agent.framework.agent.core.validate.AgentValidateResult;
import cn.enlev.ainb.module.agent.framework.agent.core.validate.DataModelCreateValidator;
import cn.enlev.ainb.module.agent.framework.agent.core.validate.DataModelUpdateValidator;
import cn.enlev.ainb.module.infra.api.datamodel.DataModelApi;
import cn.enlev.ainb.module.infra.api.datamodel.dto.DataModelSaveReqDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.AGENT_TOOL_PARAM_INVALID;

/**
 * 工具：预览待执行的 DDL（方案 §5.1 只读工具的最后一个）
 *
 * <h3>它解决的问题：把「撞一次错、改一次」换成「先问一句」</h3>
 * 建表与改表都是不可逆的，而模型产出的参数<b>不一定一次就对</b>。
 * 没有这个工具时，唯一的反馈途径是真的去执行一次 —— 错了要么失败、要么
 * 已经在库里留下痕迹。有了它，模型可以在进 Plan 之前先确认一遍，
 * 拿到的反馈与真正执行时完全一致。
 *
 * <h3>🔴 「预览通过 = 执行能过」是这个工具的全部价值，也是它唯一的风险</h3>
 * 一旦预览与执行有任何一处不同，这个工具就从「可行性判据」变成「误导源」——
 * 而且是最难查的那种误导：模型有理有据地做了错误决策。
 *
 * <p>所以它不自己解析入参、也不自己攒校验：
 * <ul>
 *   <li>入参组装走 {@link DataModelParamResolver}，与 create/update 工具同一份代码</li>
 *   <li>agent 侧校验走 {@link DataModelCreateValidator} / {@link DataModelUpdateValidator}，
 *       与 create/update 工具同一个 bean</li>
 *   <li>后端侧校验走 {@code DataModelApi#previewDdl}，它与 create/update
 *       在 {@code DataModelServiceImpl} 里共用同一套 {@code SqlSafeGuard} 与规范校验
 *       （S0 阶段补上 {@code checkNotFrameworkTable} 修的就是这条缺口）</li>
 * </ul>
 *
 * <h3>它<b>不</b>保证什么</h3>
 * 预览通过只代表「此刻能过」。真正执行时网关与校验器会<b>再跑一遍</b> ——
 * 因为从预览到确认之间可能隔着几分钟，期间完全可能有人建了个同名表。
 * 这不是冗余，两次校验的时点不同。
 *
 * @author 走进新时代
 */
@Component
@AgentTool(
        name = DataModelPreviewDdlTool.NAME,
        displayName = "预览建表语句",
        groupName = "数据建模",
        description = "预览建表或改表将要执行的 DDL，不实际执行、不产生任何副作用。"
                + "拿不准参数对不对时先调它，它的校验与真正执行时完全一致 —— "
                + "预览通过就说明这份参数能过，不必靠试错。"
                + "预览建表传 model（同 datamodel_create）；"
                + "预览改表传 tableName + addColumns/modifyColumns/dropColumns（同 datamodel_update）",
        permission = "infra:data-model:query",
        risk = RiskLevel.READ_ONLY)
@Slf4j
public class DataModelPreviewDdlTool implements AgentToolFunction {

    /**
     * 工具键。🔴 上面注解的 {@code name} 引用它，而不是再写一遍字面量。
     *
     * <p>本工具是只读的，没有变更清单抽取器，所以不存在「抽取器 toolId 与注解漂移」那条路；
     * 但它的键被 {@code DataModelCreateTool} / {@code DataModelUpdateTool} 的说明文字当作
     * 「拿不准就先预览」的指引提到，改名时那两处要一起改（那是散文，编译器不会提醒）。
     */
    public static final String NAME = "datamodel_preview_ddl";

    @Resource
    private DataModelApi dataModelApi;
    @Resource
    private DataModelParamResolver paramResolver;
    @Resource
    private DataModelCreateValidator createValidator;
    @Resource
    private DataModelUpdateValidator updateValidator;

    @Override
    public Object execute(Map<String, Object> params) {
        boolean hasModel = params != null && params.get(DataModelParamResolver.PARAM_MODEL) != null;
        boolean hasTableName = params != null
                && params.get(DataModelParamResolver.PARAM_TABLE_NAME) != null;
        // 两个都传等于没说清楚要预览哪件事。这里拒绝而不是挑一个执行 ——
        // 猜错的话模型拿到的是「另一件事」的预览结果，它不会察觉
        if (hasModel && hasTableName) {
            throw exception(AGENT_TOOL_PARAM_INVALID, NAME,
                    "model 与 tableName 只能传一个：传 model 是预览建表，传 tableName 是预览改表");
        }
        if (hasModel) {
            return previewCreate(params);
        }
        if (hasTableName) {
            return previewAlter(params);
        }
        throw exception(AGENT_TOOL_PARAM_INVALID, NAME,
                "缺少参数：预览建表请传 model（同 datamodel_create），"
                        + "预览改表请传 tableName + addColumns/modifyColumns/dropColumns"
                        + "（同 datamodel_update）");
    }

    // ==================== 建表预览 ====================

    private Object previewCreate(Map<String, Object> params) {
        DataModelSaveReqDTO reqDTO = paramResolver.resolveCreate(params, NAME);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("mode", "CREATE");
        resp.put("tableName", reqDTO.getTableName());

        AgentValidateResult result = createValidator.validate(reqDTO);
        if (!result.isPassed()) {
            return reject(resp, result.toFeedback(), "datamodel_create");
        }
        putWarnings(resp, result);
        return execPreview(resp, reqDTO, "datamodel_create");
    }

    // ==================== 改表预览 ====================

    private Object previewAlter(Map<String, Object> params) {
        DataModelParamResolver.UpdateResolution resolution =
                paramResolver.resolveUpdate(params, NAME);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("mode", "ALTER");
        resp.put("tableName", resolution.reqDTO().getTableName());
        // 把变更意图原样回显：模型能据此确认「我以为要改的」和「工具理解的」是不是一回事
        resp.put("addedColumns", resolution.added());
        resp.put("modifiedColumns", resolution.modified());
        resp.put("droppedColumns", resolution.dropped());

        AgentValidateResult result = updateValidator.validate(resolution.reqDTO(),
                resolution.current());
        if (!result.isPassed()) {
            return reject(resp, result.toFeedback(), "datamodel_update");
        }
        putWarnings(resp, result);
        return execPreview(resp, resolution.reqDTO(), "datamodel_update");
    }

    // ==================== 公共 ====================

    /**
     * 调后端预览
     *
     * <p>🔴 这里刻意<b>接住</b> {@link ServiceException} 转成「不通过 + 原因」，
     * 而不是让它抛出去。因为模型问的是「这份参数能不能过」——
     * 「不能过，因为表名必须 biz_ 开头」是对这个问题的<b>成功回答</b>，不是工具故障。
     * 抛出去的话，模型收到的是一条工具调用失败，它更可能原样重试而不是改参数。
     *
     * <p>只接 {@code ServiceException}（业务校验），其余异常照常抛 ——
     * 数据库连不上之类的真故障不该被伪装成「参数不合法」。
     */
    private Object execPreview(Map<String, Object> resp, DataModelSaveReqDTO reqDTO,
                               String realTool) {
        List<String> ddl;
        try {
            ddl = dataModelApi.previewDdl(reqDTO);
        } catch (ServiceException ex) {
            log.info("[execPreview][预览不通过 表名({}) 原因({})]", reqDTO.getTableName(),
                    ex.getMessage());
            return reject(resp, ex.getMessage(), realTool);
        }
        resp.put("passed", true);
        resp.put("ddl", ddl);
        resp.put("message", "预览通过。这些 DDL 尚未执行 —— 要真正执行，请调用 " + realTool
                + "，参数原样即可。⚠️ 执行是不可逆的");
        return resp;
    }

    private Object reject(Map<String, Object> resp, String reason, String realTool) {
        resp.put("passed", false);
        resp.put("reason", reason);
        resp.put("message", "预览未通过，" + realTool + " 现在调也会因同样的原因失败。"
                + "请按上面的原因改参数后再预览一次");
        return resp;
    }

    private void putWarnings(Map<String, Object> resp, AgentValidateResult result) {
        if (!result.getWarnings().isEmpty()) {
            resp.put("warnings", result.getWarnings());
        }
    }

    /** 见 {@link AgentToolFunction#resultArrayKeys}：本工具全部返回分支里数组值的键（并集） */
    @Override
    public List<String> resultArrayKeys() {
        return List.of("addedColumns", "modifiedColumns", "droppedColumns", "ddl", "warnings");
    }

    @Override
    public String paramsSchema() {
        return """
                二选一，传哪个就预览哪件事：

                【预览建表】
                """ + DataModelParamResolver.CREATE_PARAMS_SCHEMA + """


                【预览改表】
                """ + DataModelParamResolver.UPDATE_PARAMS_SCHEMA + """


                返回值里 passed=true 表示这份参数能过，可以原样交给
                datamodel_create / datamodel_update 去真正执行；
                passed=false 时 reason 里是原因，改完可以再预览一次。
                本工具不产生任何副作用，可以反复调用。""";
    }

}
