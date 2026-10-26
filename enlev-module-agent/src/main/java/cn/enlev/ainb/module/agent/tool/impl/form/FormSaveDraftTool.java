package cn.enlev.ainb.module.agent.tool.impl.form;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentTool;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.AgentToolFunction;
import cn.enlev.ainb.module.agent.framework.agent.core.tool.RiskLevel;
import cn.enlev.ainb.module.agent.framework.agent.core.validate.AgentValidateResult;
import cn.enlev.ainb.module.agent.framework.agent.core.validate.FormSchemaValidator;
import cn.enlev.ainb.module.infra.api.datamodel.DataModelApi;
import cn.enlev.ainb.module.infra.api.formdesign.FormDesignApi;
import cn.enlev.ainb.module.infra.api.formdesign.dto.FormDesignSaveReqDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.module.agent.enums.ErrorCodeConstants.AGENT_TOOL_PARAM_INVALID;

/**
 * 工具：新建表单草稿
 *
 * <h3>为什么是「草稿」而不是直接发布</h3>
 * 发布是个<b>原子动作</b>（建菜单、建按钮权限、分配角色，见 {@code form_publish_bundle}），
 * 而且发布前应当先体检（{@code form_inspect}）。
 * 把「存草稿」与「发布」拆成两步，让 Plan 能这样排：
 * <pre>
 *   建表 → 存表单草稿 → 体检 → 发布（含建菜单、配权限）
 * </pre>
 * 合成一步的话，体检失败就会留下一个「发布了一半」的表单。
 *
 * <h3>风险等级是 LOW</h3>
 * 草稿不影响任何在跑的东西，删掉即可。但仍然要进 Plan ——
 * 铁律是「用户确认前不执行任何写操作」，可逆只影响风险标注，不影响是否需要确认。
 *
 * @author 走进新时代
 */
@Component
@AgentTool(
        name = FormSaveDraftTool.NAME,
        displayName = "保存表单草稿",
        groupName = "表单设计",
        description = "新建一个表单草稿（不发布）。表单绑定到一张已存在的物理表上。"
                + "调用前必须先用 widget_contract_get 拿控件白名单、"
                + "用 datamodel_structure 拿目标表的列，不要凭记忆写控件类型或列名。"
                + "保存后应当再调 form_inspect 体检一次",
        permission = "infra:form-design:create",
        risk = RiskLevel.LOW)
@Slf4j
public class FormSaveDraftTool implements AgentToolFunction {

    /**
     * 工具键
     *
     * <p>🔴 上面注解的 {@code name} 与变更清单抽取器的 {@code toolId()} 都必须引用它。
     * 三处各写一遍字面量时，改名不会报错 —— 只会让清单静默退回兜底解读，
     * 表现是「新建表单」在确认框里显示成「调用工具」。
     */
    public static final String NAME = "form_save_draft";

    public static final String PARAM_NAME = "name";
    public static final String PARAM_CODE = "code";
    public static final String PARAM_MAIN_TABLE = "mainTable";
    public static final String PARAM_FIELDS = "fields";

    // ==================== 对外文案 ====================
    // 🔴 下面两句话是 public static 而不是就地写在 execute 里，因为<b>变更清单的干跑器
    //    要说同一句话</b>（ChangeManifestDryRunner 的 FORM_CREATE 那一支）。
    //    干跑器要在用户点「同意」之前就说出「这么建会失败、失败原因是什么」，
    //    而它读不到 execute 里的字面量 —— 各写一份的后果是清单上的拒绝理由与
    //    执行时的报错对不上，用户按清单改完还是失败。放在这里，两边共用一份。

    /**
     * 主表在库里不存在时的说明
     *
     * @param mainTable 表单要绑的主表名
     * @return 说清「为什么不行」与「怎么改」的一句话
     */
    public static String mainTableMissingMessage(String mainTable) {
        return String.format("主表 %s 在数据库里不存在。表单发布后列表与提交都直接读写这张表，"
                        + "绑一张不存在的表只会「保存成功、一用就炸」。"
                        + "请先用 datamodel_create 把它建出来（已有表用 datamodel_import 登记），再保存表单",
                mainTable);
    }

    /**
     * 表单编码已被占用时的说明
     *
     * <p>🔴 不能只报「编码已存在」：重跑同一条流程是失败后的第一反应，
     * 而上一轮建的草稿还占着这个编码 —— 只报「已存在」用户会以为又是 bug，其实表单早就建好了。
     *
     * @param code         表单编码
     * @param existingFormId 占用它的表单编号
     * @return 说清「两条出路各是什么」的一句话
     */
    public static String codeTakenMessage(String code, Long existingFormId) {
        return String.format("编码 %s 已被表单 #%s 占用，不能重复建。"
                        + "它若还是草稿：到「表单设计」里打开它继续编辑并发布即可，无需重跑流程；"
                        + "确要用这个编码重建，请先删除占用它的表单",
                code, existingFormId);
    }

    @Resource
    private FormDesignApi formDesignApi;
    @Resource
    private FormSchemaValidator schemaValidator;
    @Resource
    private DataModelApi dataModelApi;
    /**
     * 描述符 → rule / 列 / 查询项 / 映射的转换
     *
     * <p>🔴 与 {@link FormUpdateTool} <b>共用同一个 bean</b>。两边各写一份的后果已经实测过一次：
     * 新建认 {@code searchable}、修改不认，于是「给已有表单加个查询条件」保存成功、查询区纹丝不动。
     */
    @Resource
    private FormFieldFactory fieldFactory;

    @Override
    public Object execute(Map<String, Object> params) {
        String name = requireString(params, PARAM_NAME, "表单名称");
        String code = requireString(params, PARAM_CODE, "表单编码");
        String mainTable = requireString(params, PARAM_MAIN_TABLE, "主表名");

        // 🔴 主表必须真实存在。表单发布后列表与提交都直接 SQL 打这张表，
        //    绑一张不存在的表就是「保存成功、一用就炸」—— 比保存失败坏得多（实测炸过：
        //    流程里 save_draft 绑了规划期凭空写的 biz_device，执行还显示成功）
        validateMainTableExists(mainTable);

        List<JSONObject> fields = parseFields(params);

        // 把简化的字段描述展开成 form-create 的 rule + fieldMapping。
        // 让模型直接写 form-create 的原始结构是不现实的 —— 它有 _fc_id、name、
        // _fc_drag_tag 这类内部字段，模型编出来的多半不对，而错了不报错、只是渲染不出来
        BuildResult built = build(fields, mainTable);

        // 🔴 validateNewForm 而不是 validate：新建时「select 一个选项来源都没有」是 error，
        //    改表单那条路只能是 warning（存量里真有这种控件，理由见 FormSchemaValidator#validateOptionSource）
        AgentValidateResult validateResult =
                schemaValidator.validateNewForm(built.formFields, built.tableBinding);
        if (!validateResult.isPassed()) {
            throw exception(AGENT_TOOL_PARAM_INVALID, "form_save_draft",
                    validateResult.toFeedback());
        }

        // 🔴 编码被占用时把「怎么办」说清楚，而不是等 create 撞唯一索引/业务异常原样漏出去
        FormDesignSaveReqDTO existing = formDesignApi.getFormDesignByCode(code);
        if (existing != null) {
            throw exception(AGENT_TOOL_PARAM_INVALID, "form_save_draft",
                    codeTakenMessage(code, existing.getId()));
        }

        FormDesignSaveReqDTO reqDTO = new FormDesignSaveReqDTO();
        reqDTO.setName(name);
        reqDTO.setCode(code);
        // formType=2（列表+表单）而不是 1（仅录入）：绑定物理表的表单天然是一个
        // 可增删改查的实体，用户发布后期望看到「列表 + 新增/编辑弹窗」。
        // 只给 formType=1 的话发布出去只有一个录入框、没有列表，
        // 不满足「表单设计里面的列表，能够发布出去直接使用」
        reqDTO.setFormType(2);
        reqDTO.setMainTable(mainTable);
        reqDTO.setMainPk("id");
        reqDTO.setFormFields(built.formFields);
        reqDTO.setTableBinding(built.tableBinding);
        reqDTO.setListConf(fieldFactory.buildListConf(built.columns, built.searchItems));
        reqDTO.setFormConf(fieldFactory.defaultFormConf());
        reqDTO.setDataSourceConfigId(0L);
        reqDTO.setSort(0);

        Long id = formDesignApi.createFormDesign(reqDTO);
        log.info("[execute][AI 建表单草稿完成 编码({}) 编号({})]", code, id);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("id", id);
        resp.put("code", code);
        resp.put("name", name);
        resp.put("fieldCount", built.formFields.size());
        if (!validateResult.getWarnings().isEmpty()) {
            resp.put("warnings", validateResult.getWarnings());
        }
        resp.put("message", "表单草稿已保存。下一步应调 form_inspect 体检，"
                + "通过后再用 form_publish_bundle 发布");
        return resp;
    }

    // ==================== 构造 ====================

    private record BuildResult(List<String> formFields, String tableBinding, List<JSONObject> columns,
                               List<JSONObject> searchItems) {
    }

    /**
     * 由简化的字段描述构造 form-create rule、表绑定、列表列与查询项
     *
     * <p>rule / 列 / 查询项 / 映射这四份产物的具体形状都在 {@link FormFieldFactory}，
     * 本方法只负责「遍历字段 + 装配 tableBinding」。装配留在这里是因为它只在<b>新建</b>时发生：
     * 改表单是在已有 binding 上补 {@code fieldMapping} 的一格，绝不重建整份 binding ——
     * 重建会把 {@code logicDelete}/{@code autoFill}/{@code pkStrategy} 打回默认值。
     */
    private BuildResult build(List<JSONObject> fields, String mainTable) {
        List<String> formFields = new ArrayList<>();
        List<JSONObject> columns = new ArrayList<>();
        List<JSONObject> searchItems = new ArrayList<>();
        JSONObject fieldMapping = new JSONObject();
        // 物理表事实：既用来把表单的 required 和列的 NOT NULL 对齐，也用来把 binding 里的
        // jdbcType 归真成物理列的真实类型（见 FormFieldFactory#resolveJdbcType）。
        // fail-soft：读不到结构（表还没建 / 未登记）就退回模型给的值，绝不因为
        // 这步查询失败而让整条建表单流程崩掉
        FormFieldFactory.PhysicalSchema schema = fieldFactory.loadPhysicalSchema(mainTable);
        Map<String, Boolean> nullability = schema.nullability();

        int seq = 1;
        for (JSONObject field : fields) {
            String fieldName = field.getStr("field");
            // 归一到契约规范名：模型常把 input 写成 Input，契约大小写敏感，
            // 不归一这里存进去的 rule 渲染不出来
            String widget = fieldFactory.canonicalType(field.getStr("widget"));
            String title = field.getStr("title");
            String column = StrUtil.blankToDefault(field.getStr("column"), fieldName);
            String jdbcType = field.getStr("jdbcType");

            // _fc_id / name 由工厂统一发号。让模型去编只会编出一堆重复值，
            // 而重复的后果是控件互相覆盖
            JSONObject rule = fieldFactory.toRule(field, seq, nullability);
            formFields.add(rule.toString());

            columns.add(fieldFactory.toColumn(fieldName, title, widget));

            // 查询项：只对模型标了 searchable=true 的字段生成。
            // 「列表里要用某字段查询」此前被整个忽略 —— listConf 里根本没有 searchItems，
            // 运行时 render/index.vue 读 conf.searchItems 为空，查询区一个框都不渲染。
            if (field.getBool("searchable", false)) {
                searchItems.add(fieldFactory.buildSearchItem(fieldName, title, widget,
                        field.getStr("dictType"), field.getStr("operator")));
            }

            fieldMapping.set(fieldName, fieldFactory.toFieldMapping(mainTable, column, jdbcType, schema));
            seq++;
        }

        JSONObject binding = new JSONObject();
        binding.set("mainTable", mainTable);
        binding.set("mainPk", "id");
        binding.set("pkStrategy", "SNOWFLAKE");
        // 逻辑删除与自动填充按平台约定配好 —— 这些不该让模型来决定，
        // 配错了的表现是「删了还在」或「创建人是空的」
        JSONObject logicDelete = new JSONObject();
        logicDelete.set("enabled", true);
        logicDelete.set("column", "deleted");
        logicDelete.set("deletedValue", "1");
        logicDelete.set("normalValue", "0");
        logicDelete.set("valueType", "NUMBER");
        binding.set("logicDelete", logicDelete);
        JSONObject autoFill = new JSONObject();
        autoFill.set("creatorColumn", "creator");
        autoFill.set("creatorMode", "AUTO");
        autoFill.set("createTimeColumn", "create_time");
        autoFill.set("createTimeMode", "AUTO");
        autoFill.set("updaterColumn", "updater");
        autoFill.set("updaterMode", "AUTO");
        autoFill.set("updateTimeColumn", "update_time");
        autoFill.set("updateTimeMode", "AUTO");
        binding.set("autoFill", autoFill);
        binding.set("fieldMapping", fieldMapping);

        return new BuildResult(formFields, binding.toString(), columns, searchItems);
    }

    /**
     * 主表存在性硬校验：数据库里没有这张表就不许保存表单
     *
     * <p>🔴 为什么是硬校验而不是像 {@link FormFieldFactory#loadPhysicalSchema} 那样 fail-soft：
     * 那步读不到结构只是 required 与 jdbcType 退化成模型给的值，表单本身还能用；
     * 而主表不存在时表单<b>照样保存成功</b>，用户发布后点列表才撞 SQL 异常 ——
     * 正是 AGENTS.md 点名的「配置保存成功但不生效且无提示」这类缺陷。
     *
     * <p>但「查表」这个动作本身失败（数据源故障）时放行：那种情况下后续读结构也会 fail-soft，
     * 不该把「校验的依赖」故障变成建表单失败。
     */
    private void validateMainTableExists(String mainTable) {
        List<DataModelApi.PhysicalTableRespDTO> tables;
        try {
            tables = dataModelApi.getPhysicalTableList(mainTable);
        } catch (Exception ex) {
            log.warn("[validateMainTableExists][查询物理表({}) 失败，跳过存在性校验：{}]",
                    mainTable, ex.getMessage());
            return;
        }
        boolean exists = tables.stream()
                .anyMatch(table -> mainTable.equalsIgnoreCase(table.getTableName()));
        if (!exists) {
            throw exception(AGENT_TOOL_PARAM_INVALID, "form_save_draft",
                    mainTableMissingMessage(mainTable));
        }
    }

    // ==================== 入参 ====================

    private String requireString(Map<String, Object> params, String key, String label) {
        Object value = params.get(key);
        if (value == null || StrUtil.isBlank(value.toString())) {
            throw exception(AGENT_TOOL_PARAM_INVALID, "form_save_draft",
                    "缺少 " + key + "（" + label + "）");
        }
        return value.toString().trim();
    }

    private List<JSONObject> parseFields(Map<String, Object> params) {
        Object raw = params.get(PARAM_FIELDS);
        if (raw == null) {
            throw exception(AGENT_TOOL_PARAM_INVALID, "form_save_draft",
                    "缺少 fields（表单字段列表）");
        }
        JSONArray array;
        try {
            array = JSONUtil.parseArray(raw);
        } catch (Exception ex) {
            throw exception(AGENT_TOOL_PARAM_INVALID, "form_save_draft",
                    "fields 不是合法的数组：" + ex.getMessage());
        }
        if (array.isEmpty()) {
            throw exception(AGENT_TOOL_PARAM_INVALID, "form_save_draft", "fields 是空的");
        }
        List<JSONObject> fields = new ArrayList<>();
        for (Object item : array) {
            if (item instanceof JSONObject obj) {
                fields.add(obj);
            }
        }
        if (CollUtil.isEmpty(fields)) {
            throw exception(AGENT_TOOL_PARAM_INVALID, "form_save_draft",
                    "fields 里没有有效的字段定义");
        }
        return fields;
    }

    /**
     * 🔴 这份 schema 是「{@code searchable} 教法唯一那一处」：模型要不要给某个字段标可查询，
     * 只由下面 {@code searchable} 那三行文字决定 —— 代码里既没有默认值也没有推断，
     * {@code buildFormFields} 那句就是 {@code field.getBool("searchable", false)}。
     * 所以「用户要了查询、发布出来查询区却是空的」这类缺陷，改的地方在这里，不在执行逻辑里。
     *
     * <h3>A6（迭代七第 08 轮）：触发条件从「用户说」放宽到「用户提到」</h3>
     * 原文是「用户说『列表里要用某字段查询』时，给该字段传 true」——
     * 那等于要求用户<b>照着工具的词</b>把需求再说一遍。实测用户不这么说：
     * case 57 的原话是「主管能<b>按客户和按人查</b>拜访情况」，把查询说成<b>用途</b>，
     * 全句没有一个「查询条件」的字眼。而同一个模型、同一段提问，
     * 有的轮次标了 4 个 {@code searchable}、有的轮次一个都没标
     * （三轮对照见迭代四方案 §15-6），规划路径的 {@code temperature} 早已钉死在 0.1，
     * 高温采样解释不了这个抖动 ⟹ 是<b>遵从度不稳定</b>，不是「照教执行」。
     *
     * <p>放宽后的写法把「查询 / 筛选 / 统计」三种说法与「隐含表述」都点了名，
     * 例子直接取自 case 57 那句原话：举一个模型真会遇到的例子，
     * 比再添一条抽象规则管用 —— 抽象规则它已经有一条了，正是那条不够用。
     *
     * <p>⚠️ 刻意<b>不</b>给 {@code searchable} 一个默认值。那是已经撤掉的旧待裁项（方案 §3.4）：
     * 默认全标的表现是每张表的查询区塞十几个框，而「忘了标」与「不该标」在数据上无法区分，
     * 兜底只会把后一种也一并改掉。
     *
     * <p>⚠️ 全文没有 markdown 粗体：这份文本是原样喂给模型的工具描述，
     * 方案里那两处 {@code **…**} 是给读方案的人看的强调，不是要照抄的字面量。
     */
    /** 见 {@link AgentToolFunction#resultArrayKeys}：本工具全部返回分支里数组值的键（并集） */
    @Override
    public List<String> resultArrayKeys() {
        return List.of("warnings");
    }

    /**
     * 见 {@link AgentToolFunction#resultKeys}：本工具顶层键
     *
     * <p>{@code id} 就是下游 {@code form_inspect} / {@code form_publish_bundle} 要的那个表单编号 ——
     * 扁平在顶层，写作 {@code {{$.某outputVar.id}}}。
     */
    @Override
    public List<String> resultKeys() {
        return List.of("id", "code", "name", "fieldCount", "warnings", "message");
    }

    /**
     * 🔴 {@code validateRules} 那一段的键名清单是<b>渲染出来的</b>，不是写在这里的字面量
     *
     * <p>出处是 {@link FormSchemaValidator#validateRulesCatalog()}，它读 infra 的
     * {@code validation-patterns.json}。写死一份的话，正则库加一条 / 改个名，
     * 这份 schema 就成了假话 —— 而模型照着假话写的键名，
     * 运行期是「放过 + 记日志」（{@code FormValidationGuard#checkBuiltin}），
     * 用户要的校验静默失效，正是本工具其余各条注释反复在防的那个形状。
     *
     * <p>⚠️ 本方法在<b>启动时</b>被 {@code AgentToolRegistry} 调一次并冻进工具定义
     * （{@code declaredParamsSchema}），所以渲染结果不随运行期变化 ——
     * 正则库是 classpath 资源，运行期本来也不会变。库加载失败时目录是空的，
     * {@code validateRulesCatalog()} 会退成一句「改用 pattern 写自定义正则」，
     * 不会让启动失败，也不会印出一份空清单让模型去猜。
     */
    @Override
    public String paramsSchema() {
        return """
                name（必填，字符串）：表单名称，如「设备巡检记录」
                code（必填，字符串）：表单编码，全小写下划线，如 device_inspection_record。
                  发布后不可修改
                mainTable（必填，字符串）：绑定的物理表名，必须是已存在的表
                fields（必填，数组）：表单字段，每项：
                  field     必填，字段标识，一般与列名相同，不能以下划线开头
                  widget    必填，控件类型，必须来自 widget_contract_get。
                    🔴 有些类型虽然在白名单里、也确实可用，但它们的必需配置在 fields[] 里
                    没有对应的格子，AI 建不出来。写了【不报错】：草稿照存、发布照过，
                    运行期那一格是个空控件（点不开的下拉 / 显示不出内容的块）。
                    widget_contract_get 的清单里标了 aiAuthorable=false 的一律别选；
                    选择类需求（含级联、树形选择）用 %s + options/dictType 就够表达。
                    用户【点名】要那种控件时，把表单其余部分建好，
                    并在最终话术里如实说清哪一格需要人工去「表单设计」里配
                  title     必填，中文标签
                  column    可选，物理列名，不传默认与 field 相同
                  jdbcType  必填，如 VARCHAR / BIGINT / DECIMAL / TIMESTAMP / INTEGER。
                    平台会拿物理列的真实类型覆盖它（读不到列结构时才用你给的这个值），
                    所以填错不会让表单存不下值；但仍按你的判断填，别写明显不相干的类型
                  required  可选，布尔，是否必填；物理列是 NOT NULL 时会自动置为必填
                  searchable 可选，布尔，是否在列表查询区显示为查询条件。
                    用户提到要按某字段查询 / 筛选 / 统计时，给该字段传 true
                    （包括像「主管能按客户查」这种把查询说成用途的隐含表述）
                  options   可选，数组，【固定】选项，每项 {"label":"选项名","value":"存进库的值"}。
                    用户把选项说出来了（「状态分待处理/已处理」「性别男女」）就写它 ——
                    这是选项类控件唯一不依赖别处配置就能出选项的写法。
                    🔴 %s【必须】给 options 或 dictType 之一：
                    两个都没有时表单照样建好、照样发布成功，而那一格是个点开为空的下拉，
                    用户只会觉得「这个表单坏了」，没有任何一处会主动说
                  dictType  可选，字典类型，同时用于表单控件与列表查询区的下拉。
                    🔴 只能是【系统里已存在】的字典 —— 你没有建字典的工具，
                    凭记忆编一个 dictType 的后果不是报错，而是下拉里一个选项都没有。
                    所以：用户点名了某个字典才写 dictType，选项是固定的就写 options
                  operator  可选，查询操作符（LIKE/EQ/BETWEEN…），不传按控件类型自动选
                  placeholder 可选，输入提示
                  validateRules 可选，数组，字段【格式】校验（身份证 / 手机号 / 邮箱 / 金额…）。
                    用户提到要「校验格式」「得是手机号」「身份证填错了要拦住」时给该字段传它
                    （包括像「身份证号要真实的」这种把校验说成要求的隐含表述）。
                    不传就是没有任何格式校验，而这件事【不会报错】：
                    表单照样建好、照样发布成功、填什么都收 —— 所以别等用户追问才补
                    每项写 {"type":"BUILTIN","patternKey":"键名"}，键名只能从这里取：%s
                    库里确实没有的格式才写 {"type":"REGEX","pattern":"正则","message":"提示语"}。
                    能用内置的就别自己写正则：内置那批前端即时校验与服务端复校验两侧都对齐过，
                    自己写的正则要两侧语法都吃得下，否则又是「配了但不生效」
                    只对单行输入（widget=input）有意义，别的控件传了也不生效
                不要映射 tenant_id（会造成跨租户写入），也不要映射
                id/creator/create_time/updater/update_time/deleted（平台自动维护）"""
                // 🔴 两份名单都从常量/目录渲染，不在文案里手敲：
                //    选项类控件那三个名字在本模块已经有唯一出处（FormFieldFactory#OPTION_WIDGETS），
                //    手敲一份的话，日后往选项族里加控件时这份 schema 就成了假话 ——
                //    而假话的表现是「模型照它写、判据按新的判」，两侧都不报错。
                //    widget 那段同理：它刻意不点名任何一个「建不出来」的控件，
                //    那份名单也是从契约现算的（FormFieldFactory#checkWidgetAuthorable）
                .formatted(FormFieldFactory.optionWidgetCatalog(),
                        FormFieldFactory.optionWidgetCatalog(),
                        schemaValidator.validateRulesCatalog());
    }

}
