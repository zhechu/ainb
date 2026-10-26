package cn.enlev.ainb.module.infra.api.formdesign;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.framework.common.util.json.JsonUtils;
import cn.enlev.ainb.framework.common.util.object.BeanUtils;
import cn.enlev.ainb.module.infra.api.formdesign.dto.FormDataPageReqDTO;
import cn.enlev.ainb.module.infra.api.formdesign.dto.FormDesignMenuPreviewRespDTO;
import cn.enlev.ainb.module.infra.api.formdesign.dto.FormDesignPublishRespDTO;
import cn.enlev.ainb.module.infra.api.formdesign.dto.FormDesignRespDTO;
import cn.enlev.ainb.module.infra.api.formdesign.dto.FormDesignSaveReqDTO;
import cn.enlev.ainb.module.infra.controller.admin.formdesign.vo.FormDesignPublishReqVO;
import cn.enlev.ainb.module.infra.controller.admin.formdesign.vo.FormDesignSaveReqVO;
import cn.enlev.ainb.module.infra.dal.dataobject.formdesign.FormDesignDO;
import cn.enlev.ainb.module.infra.enums.artifact.ArtifactSourceEnum;
import cn.enlev.ainb.module.infra.enums.formdesign.FormDesignStatusEnum;
import cn.enlev.ainb.module.infra.enums.formdesign.OperatorEnum;
import cn.enlev.ainb.module.infra.framework.formdesign.core.FormFieldsCodec;
import cn.enlev.ainb.module.infra.framework.formdesign.core.binding.TableBinding;
import cn.enlev.ainb.module.infra.framework.formdesign.core.contract.WidgetContractRegistry;
import cn.enlev.ainb.module.infra.framework.formdesign.core.permission.FormFieldCropper;
import cn.enlev.ainb.module.infra.framework.formdesign.core.sql.FormDesignSqlBuilder;
import cn.enlev.ainb.module.infra.framework.formdesign.core.validation.ValidationPatternRegistry;
import cn.enlev.ainb.module.infra.service.db.DatabaseTableService;
import cn.enlev.ainb.module.infra.service.formdesign.FormDesignService;
import cn.enlev.ainb.module.infra.service.formdesign.runtime.FormDesignRuntimeService;
import com.baomidou.mybatisplus.generator.config.po.TableField;
import com.baomidou.mybatisplus.generator.config.po.TableInfo;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.util.*;

/**
 * 表单设计 API 实现类
 *
 * @author 走进新时代
 */
@Service
@Validated
@Slf4j
public class FormDesignApiImpl implements FormDesignApi {

    /**
     * 流程实例编号列名
     *
     * 固定列名、不接受映射到别名：允许别名会让校验、回写、排障三处都要处理映射，收益不抵成本。
     */
    public static final String COLUMN_PROCESS_INSTANCE_ID = "process_instance_id";
    /**
     * 流程状态列名。仅主表单要求。
     *
     * 用 process_status 而非 status —— 业务表常有自己的 status 业务字段，
     * 流程状态必须与之解耦，否则同表无法共存、校验与回写也无从区分。
     */
    public static final String COLUMN_PROCESS_STATUS = "process_status";
    /**
     * process_instance_id 的最小长度。Flowable 的流程实例编号为雪花 ID 字符串，64 足够
     *
     * <p>🔴 public 是给 {@code DataModelApiImpl#getTableConstraints()} 用的：那条约束要连同
     * 这个长度一起交出去，建表校验器才拦得住「列名对、长度不够」。这里再抄一个 64 就是第二份口径。
     */
    public static final int PROCESS_INSTANCE_ID_MIN_LENGTH = 64;
    /**
     * 主库数据源编号。本期流程表单仅支持主库
     */
    private static final Long MAIN_DATA_SOURCE_ID = 0L;

    /**
     * 子表控件在 form-create 规则里的 type
     *
     * 统一取自控件契约，本类不再持有副本 —— 原先这里与
     * {@code FormDesignPublishValidator} 各有一份字面量，靠注释互相提醒保持一致。
     */
    private String subTableWidgetType() {
        return widgetContractRegistry.getSubTableWidgetType();
    }

    @Resource
    private FormDesignService formDesignService;
    @Resource
    private FormDesignRuntimeService formDesignRuntimeService;
    @Resource
    private DatabaseTableService databaseTableService;

    /**
     * 控件契约注册表。子表控件类型等结构性事实统一从这里取
     */
    @Resource
    private WidgetContractRegistry widgetContractRegistry;

    /**
     * 内置正则库。字段格式校验的键名目录统一从这里取（出口见 {@link #getValidationPatternList()}）
     */
    @Resource
    private ValidationPatternRegistry patternRegistry;

    /**
     * 字段级裁剪器。见 {@link FormDesignApi#getRuntimeFormDesign(String)}
     */
    @Resource
    private FormFieldCropper fieldCropper;

    @Override
    public List<FormDesignRespDTO> getFlowFormList(boolean mainForm) {
        List<FormDesignDO> list = formDesignService.getFormDesignSimpleList();
        if (list == null || list.isEmpty()) {
            return Collections.emptyList();
        }
        List<FormDesignRespDTO> result = new ArrayList<>(list.size());
        // 逐个标注就绪状态，而不是直接过滤：用户需要知道自己的表单为什么不在列表里
        for (FormDesignDO formDesign : list) {
            FormDesignRespDTO dto = convert(formDesign);
            String reason = resolveUnavailableReason(formDesign, mainForm);
            dto.setFlowReady(reason == null);
            dto.setUnavailableReason(reason);
            result.add(dto);
        }
        return result;
    }

    @Override
    public FormDesignRespDTO getFormDesign(String code) {
        FormDesignDO formDesign = formDesignService.getFormDesignByCode(code);
        return formDesign == null ? null : convert(formDesign);
    }

    @Override
    public FormDesignRespDTO getRuntimeFormDesign(String code) {
        return crop(getFormDesign(code));
    }

    @Override
    public List<FormDesignRespDTO> getFormDesignList(Collection<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return Collections.emptyList();
        }
        List<FormDesignRespDTO> result = new ArrayList<>(codes.size());
        for (String code : new LinkedHashSet<>(codes)) {
            FormDesignDO formDesign = formDesignService.getFormDesignByCode(code);
            if (formDesign != null) {
                result.add(convert(formDesign));
            }
        }
        return result;
    }

    @Override
    public List<FormDesignRespDTO> getRuntimeFormDesignList(Collection<String> codes) {
        List<FormDesignRespDTO> list = getFormDesignList(codes);
        list.forEach(this::crop);
        return list;
    }

    /**
     * 按当前登录人的字段权限裁剪表单结构
     *
     * ⚠️ 裁的是 {@code formFields}（渲染规则），{@code formConf} 不动 ——
     * 后者是表单级配置（布局、提交按钮…），里面没有字段。
     * 口径与在线表单自己的 {@code getRenderConfig} 完全一致，
     * 两条路径裁得不一样的话，同一张表单「从表单菜单进」与「从流程进」会看到不同的字段，
     * 而这种不一致没有任何提示
     */
    private FormDesignRespDTO crop(FormDesignRespDTO dto) {
        if (dto == null) {
            return null;
        }
        dto.setFormFields(fieldCropper.cropFormFields(dto.getCode(), dto.getFormFields()));
        return dto;
    }

    @Override
    public String validateFlowReady(String code, boolean mainForm) {
        FormDesignDO formDesign = formDesignService.getFormDesignByCode(code);
        if (formDesign == null) {
            return StrUtil.format("表单({})不存在，可能已被删除", code);
        }
        return resolveUnavailableReason(formDesign, mainForm);
    }

    @Override
    public Object createData(String code, Map<String, Object> data) {
        return formDesignRuntimeService.create(code, data);
    }

    @Override
    public void updateData(String code, Object pkValue, Map<String, Object> data) {
        formDesignRuntimeService.update(code, pkValue, data);
    }

    @Override
    public Map<String, Object> getData(String code, Object pkValue) {
        return formDesignRuntimeService.get(code, pkValue);
    }

    @Override
    public void deleteData(String code, Object pkValue) {
        formDesignRuntimeService.delete(code, pkValue);
    }

    @Override
    public void updateSystemColumns(String code, Object pkValue, Map<String, Object> columnValues) {
        formDesignRuntimeService.updateSystemColumns(code, pkValue, columnValues);
    }

    @Override
    public PageResult<Map<String, Object>> pageData(String code, FormDataPageReqDTO reqDTO) {
        // 把 api 级 DTO 的条件逐条转成 service 内部类型 QueryCondition。
        // 与 FormDesignRuntimeController.convertConditions 同口径：只搬 field/operator/value/value2，
        // 不校验 operator —— OperatorEnum 白名单在 FormDesignSqlBuilder 内部兜底（防注入唯一收口）
        List<FormDesignSqlBuilder.QueryCondition> conditions = new ArrayList<>();
        if (reqDTO != null && reqDTO.getConditions() != null) {
            for (FormDataPageReqDTO.Condition condition : reqDTO.getConditions()) {
                if (condition == null) {
                    continue;
                }
                FormDesignSqlBuilder.QueryCondition queryCondition = new FormDesignSqlBuilder.QueryCondition();
                queryCondition.setField(condition.getField());
                queryCondition.setOperator(condition.getOperator());
                queryCondition.setValue(condition.getValue());
                queryCondition.setValue2(condition.getValue2());
                conditions.add(queryCondition);
            }
        }
        // 分页参数缺省兜底：pageNo→1、pageSize→10，与 FormDataPageReqVO 的默认一致
        int pageNo = reqDTO == null || reqDTO.getPageNo() == null ? 1 : reqDTO.getPageNo();
        int pageSize = reqDTO == null || reqDTO.getPageSize() == null ? 10 : reqDTO.getPageSize();
        return formDesignRuntimeService.page(code, conditions,
                reqDTO == null ? null : reqDTO.getSortField(),
                reqDTO == null ? null : reqDTO.getSortOrder(), pageNo, pageSize);
    }

    // ========== 就绪校验 ==========

    /**
     * 解析表单不可用于流程的原因
     *
     * 每种原因单独成句并指明修复动作 —— 合并成「该表单不可用」会显著增加排障成本。
     *
     * @param formDesign 表单
     * @param mainForm   是否作为主表单校验
     * @return 不可用原因；可用时返回 null
     */
    private String resolveUnavailableReason(FormDesignDO formDesign, boolean mainForm) {
        // 1. 必须已发布 —— 草稿的表结构与字段随时会变，不能被流程引用
        if (!Objects.equals(formDesign.getStatus(), FormDesignStatusEnum.PUBLISHED.getStatus())
                && !Objects.equals(formDesign.getStatus(), FormDesignStatusEnum.PUBLISHED_MODIFIED.getStatus())) {
            return "表单未发布，请先在【表单设计】中发布该表单";
        }
        // 2. 本期仅支持主库
        Long dataSourceId = formDesign.getDataSourceConfigId() == null
                ? MAIN_DATA_SOURCE_ID : formDesign.getDataSourceConfigId();
        if (!Objects.equals(dataSourceId, MAIN_DATA_SOURCE_ID)) {
            return "表单绑定的是非主库数据源，本期流程仅支持主库表单";
        }
        // 3. 必须有表绑定
        TableBinding binding = parseTableBinding(formDesign);
        if (binding == null || StrUtil.isBlank(binding.getMainTable())) {
            return "表单未完成表绑定，请先在【表单设计】的表绑定步骤中选择主表";
        }
        // 4. 副表与子表已接入流程链路：
        //    写入走 FormDesignRuntimeService.create/update，它本就按主表 → 副表 → 子表的顺序
        //    在同一事务里处理；读取走 get()，副表字段平铺、子表明细以嵌套数组返回。
        //    所以不再一刀切拦下带副表/子表的表单，只拦真正会丢数据的那一种：
        //    表单里放了子表控件、但表绑定里没有对应的明细表配置 ——
        //    这类表单提交的明细写不进任何表，且全程不报错。
        //    新表单在发布校验里就会被拦住，这里是为了兜住那之前已经发布的存量表单。
        String unboundWidget = findUnboundSubFormWidget(formDesign.getFormFields(), binding);
        if (unboundWidget != null) {
            return StrUtil.format(
                    "表单中的子表控件「{}」没有配置对应的明细表，提交的明细数据会丢失。"
                            + "请在【表单设计】的数据绑定步骤添加一对多明细表并重新发布", unboundWidget);
        }
        // 5. 主表必须存在
        String mainTable = binding.getMainTable();
        TableInfo table = databaseTableService.getTable(dataSourceId, mainTable);
        if (table == null) {
            return StrUtil.format("表单绑定的主表({})不存在，可能已被删除", mainTable);
        }
        Map<String, TableField> columns = buildColumnMap(table.getFields());
        // 6. process_instance_id：主表单与节点表单都要求
        String reason = validateProcessInstanceIdColumn(mainTable, columns);
        if (reason != null) {
            return reason;
        }
        // 7. process_status：仅主表单要求。节点表单只是补充填写，不承载流程状态
        if (mainForm) {
            return validateProcessStatusColumn(mainTable, columns);
        }
        return null;
    }

    private String validateProcessInstanceIdColumn(String mainTable, Map<String, TableField> columns) {
        TableField field = columns.get(COLUMN_PROCESS_INSTANCE_ID);
        if (field == null) {
            return StrUtil.format("主表({})缺少 {} 列，请补加 varchar({}) 列",
                    mainTable, COLUMN_PROCESS_INSTANCE_ID, PROCESS_INSTANCE_ID_MIN_LENGTH);
        }
        String jdbcType = resolveJdbcType(field);
        if (jdbcType == null) {
            // 元数据缺失时放行：拿不到类型就拦截，会把正常表单误伤成不可用
            return null;
        }
        if (!isStringType(jdbcType)) {
            return StrUtil.format("主表({})的 {} 列类型为 {}，需改为 varchar({})",
                    mainTable, COLUMN_PROCESS_INSTANCE_ID, jdbcType, PROCESS_INSTANCE_ID_MIN_LENGTH);
        }
        Integer length = resolveColumnLength(field);
        if (length != null && length < PROCESS_INSTANCE_ID_MIN_LENGTH) {
            return StrUtil.format("主表({})的 {} 列长度为 {}，不足以存放流程实例编号，需不小于 {}",
                    mainTable, COLUMN_PROCESS_INSTANCE_ID, length, PROCESS_INSTANCE_ID_MIN_LENGTH);
        }
        return null;
    }

    private String validateProcessStatusColumn(String mainTable, Map<String, TableField> columns) {
        TableField field = columns.get(COLUMN_PROCESS_STATUS);
        if (field == null) {
            return StrUtil.format("主表({})缺少 {} 列，请补加 tinyint 列（作为主表单需要它承载流程状态）",
                    mainTable, COLUMN_PROCESS_STATUS);
        }
        String jdbcType = resolveJdbcType(field);
        if (jdbcType == null) {
            return null;
        }
        if (!isIntegerType(jdbcType)) {
            return StrUtil.format("主表({})的 {} 列类型为 {}，需改为整数型（tinyint / int / bigint）",
                    mainTable, COLUMN_PROCESS_STATUS, jdbcType);
        }
        return null;
    }

    // ========== 工具方法：与 FormDesignPublishValidator 保持一致的类型判断口径 ==========

    /**
     * 找出没有配置明细表的子表控件
     *
     * form-create 的子表控件 type 为 group，其值是一个数组，对应一对多的明细表。
     * 控件与 {@code tableBinding.subTables} 靠 field 关联，两边对不上时提交的明细
     * 写不进任何表，而且接口照样返回成功 —— 这类静默丢失必须在选表单时就拦住。
     *
     * 布局容器里嵌的控件也要扫：把子表控件拖进一个栅格就检测不到，等于漏了。
     *
     * @return 首个未绑定的子表控件标题（没有则返回 null）
     */
    private String findUnboundSubFormWidget(String formFields, TableBinding binding) {
        if (StrUtil.isBlank(formFields)) {
            return null;
        }
        Set<String> boundFields = new HashSet<>();
        if (binding.getSubTables() != null) {
            binding.getSubTables().forEach(sub -> {
                if (sub != null && StrUtil.isNotBlank(sub.getField())) {
                    boundFields.add(sub.getField());
                }
            });
        }
        List<String> rules = FormFieldsCodec.read(formFields);
        if (rules == null) {
            return null;
        }
        for (String ruleJson : rules) {
            Map<String, Object> rule = JsonUtils.parseObject(ruleJson, Map.class);
            String hit = findUnboundSubFormWidget(rule, boundFields);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private String findUnboundSubFormWidget(Map<String, Object> rule, Set<String> boundFields) {
        if (rule == null) {
            return null;
        }
        if (subTableWidgetType().equals(rule.get("type"))) {
            Object field = rule.get("field");
            if (field instanceof String fieldName && !boundFields.contains(fieldName)) {
                Object title = rule.get("title");
                return title != null ? title.toString() : fieldName;
            }
        }
        // 布局容器的子控件在 children 里；子表控件内部规则在 props.rule 下，也可能嵌套子表控件
        for (Object nested : new Object[]{rule.get("children"),
                rule.get("props") instanceof Map<?, ?> p ? ((Map<String, Object>) p).get("rule") : null}) {
            if (nested instanceof List<?> list) {
                for (Object child : list) {
                    if (child instanceof Map<?, ?> childMap) {
                        String hit = findUnboundSubFormWidget((Map<String, Object>) childMap, boundFields);
                        if (hit != null) {
                            return hit;
                        }
                    }
                }
            }
        }
        return null;
    }

    private TableBinding parseTableBinding(FormDesignDO formDesign) {
        if (StrUtil.isBlank(formDesign.getTableBinding())) {
            return null;
        }
        return JsonUtils.parseObject(formDesign.getTableBinding(), TableBinding.class);
    }

    private Map<String, TableField> buildColumnMap(List<TableField> fields) {
        Map<String, TableField> map = new HashMap<>();
        if (fields == null) {
            return map;
        }
        for (TableField field : fields) {
            // 列名大小写在不同数据库下不一致，统一按小写存放
            map.put(StrUtil.toUnderlineCase(field.getName()).toLowerCase(), field);
            map.put(field.getName().toLowerCase(), field);
        }
        return map;
    }

    private String resolveJdbcType(TableField field) {
        if (field.getMetaInfo() == null || field.getMetaInfo().getJdbcType() == null) {
            return null;
        }
        return field.getMetaInfo().getJdbcType().name();
    }

    private Integer resolveColumnLength(TableField field) {
        if (field.getMetaInfo() == null) {
            return null;
        }
        int length = field.getMetaInfo().getLength();
        return length <= 0 ? null : length;
    }

    private boolean isStringType(String jdbcType) {
        String upper = jdbcType.toUpperCase();
        return upper.contains("CHAR") || upper.contains("TEXT");
    }

    private boolean isIntegerType(String jdbcType) {
        String upper = jdbcType.toUpperCase();
        return upper.contains("INT") || upper.equals("SMALLINT") || upper.equals("TINYINT");
    }

    // ==================================================================
    // 设计态方法（方案 §3.1.1），供 agent 模块使用
    //
    // 一律是对 FormDesignService 的<b>薄封装</b>，只做 DTO ↔ VO 搬运。
    // 一旦这里出现业务判断，就意味着「api 路径」与「Controller 路径」开始分叉，
    // 而那类不一致是本项目最难反查的缺陷形态。
    // ==================================================================

    @Override
    public Long createFormDesign(FormDesignSaveReqDTO reqDTO) {
        // 🔴 来源在这里定死为 AI，不从 reqDTO 取 —— DTO 上【根本没有】source 字段。
        //    若让调用方自报，AI 就能把产物洗成 MANUAL，
        //    而整条 manual_edited_flag 治理（「AI 建的、没人碰过」）当场失效
        return formDesignService.createFormDesign(toSaveReqVO(reqDTO), ArtifactSourceEnum.AI);
    }

    @Override
    public void updateFormDesign(FormDesignSaveReqDTO reqDTO) {
        // operator=AI 只影响「人改过 AI 产物」那个标记，不改 source（来源是出生证明）。
        // reqDTO 里的 version 必须原样回传 —— 它就是这条写路径的乐观锁基线，
        // FormUpdateTool 的「读-改-写」窗口很长，不靠它并发覆盖不会报错
        formDesignService.updateFormDesign(toSaveReqVO(reqDTO), ArtifactSourceEnum.AI);
    }

    @Override
    public List<FormDesignSimpleRespDTO> getFormDesignSimpleList() {
        return formDesignService.getFormDesignSimpleList().stream().map(item -> {
            FormDesignSimpleRespDTO dto = new FormDesignSimpleRespDTO();
            dto.setId(item.getId());
            dto.setCode(item.getCode());
            dto.setName(item.getName());
            dto.setCategory(item.getCategory());
            dto.setMainTable(item.getMainTable());
            dto.setStatus(item.getStatus());
            return dto;
        }).toList();
    }

    @Override
    public FormDesignSaveReqDTO getFormDesignByCode(String code) {
        return toSaveReqDTO(formDesignService.getFormDesignByCode(code));
    }

    @Override
    public FormDesignSaveReqDTO getFormDesignById(Long id) {
        return toSaveReqDTO(formDesignService.getFormDesign(id));
    }

    private FormDesignSaveReqDTO toSaveReqDTO(FormDesignDO formDesign) {
        if (formDesign == null) {
            return null;
        }
        FormDesignSaveReqDTO dto = BeanUtils.toBean(formDesign, FormDesignSaveReqDTO.class);
        // formFields 在 DO 里是整份 JSON 字符串，DTO 上是数组。
        // BeanUtils 拷不过来（类型不同），必须手工转 —— 漏了的表现是
        // 「取回来改一改再存回去，字段清单没了」，而且不报错
        dto.setFormFields(FormFieldsCodec.read(formDesign.getFormFields()));
        // ⚠️ version 靠 BeanUtils 按同名字段自动带过去，这里不写第二遍。
        //    它是 AI 侧唯一能拿到乐观锁基线的通道：漏了的表现是
        //    updateFormDesign 被 FORM_DESIGN_RECORD_VERSION_REQUIRED 拒掉，
        //    而模型看不出该去哪儿把它补上
        return dto;
    }

    @Override
    public FormDesignPublishRespDTO publishFormDesign(Long id, Long parentMenuId) {
        FormDesignPublishReqVO reqVO = new FormDesignPublishReqVO();
        reqVO.setId(id);
        reqVO.setParentMenuId(parentMenuId);
        return BeanUtils.toBean(formDesignService.publish(reqVO), FormDesignPublishRespDTO.class);
    }

    @Override
    public FormDesignMenuPreviewRespDTO previewPublishMenu(Long id, Long parentMenuId) {
        // 直接透传：service 返回的就是 api 层的 DTO。
        // 这里不套一层同形状的 VO 再转回来 —— 那正是「改了一边忘了另一边」的温床，
        // 而这两个类描述的是同一份菜单配置
        return formDesignService.previewPublishMenu(id, parentMenuId);
    }

    @Override
    public void bindMenu(Long designId, Long menuId, List<Long> permissionMenuIds) {
        formDesignService.bindMenu(designId, menuId, permissionMenuIds);
    }

    @Override
    public List<String> inspectFormDesign(Long id) {
        return formDesignService.inspect(id);
    }

    @Override
    public String getSubFieldSeparator() {
        return widgetContractRegistry.getSubFieldSeparator();
    }

    @Override
    public List<WidgetSimpleRespDTO> getWidgetContracts() {
        return widgetContractRegistry.listAll().stream().map(contract -> {
            WidgetSimpleRespDTO dto = new WidgetSimpleRespDTO();
            dto.setType(contract.getType());
            dto.setLabel(contract.getLabel());
            dto.setGroup(contract.getGroup());
            dto.setSupported(contract.getSupported());
            dto.setDeprecated(contract.getDeprecated());
            dto.setColumnTypes(contract.getColumnTypesOrEmpty().stream()
                    .map(Enum::name).toList());
            dto.setSubTable(contract.getCaps() == null ? null : contract.getCaps().getSubTable());
            // 配置项原样带出去。契约里没有 props 的控件给 null 而不是空数组 ——
            // 空数组会在 JSON 里多出一个 "props":[]，几十个控件加起来全是噪声
            dto.setProps(contract.getPropsOrEmpty().isEmpty() ? null
                    : contract.getPropsOrEmpty().stream().map(spec -> {
                        WidgetPropDTO prop = new WidgetPropDTO();
                        prop.setName(spec.getName());
                        prop.setType(spec.getType());
                        prop.setRequired(spec.getRequired());
                        prop.setDesc(spec.getDesc());
                        prop.setAllowedValues(spec.getAllowedValues());
                        prop.setSource(spec.getSource());
                        prop.setExample(spec.getExample());
                        return prop;
                    }).toList());
            return dto;
        }).toList();
    }

    @Override
    public List<SearchOperatorRespDTO> getSearchOperatorList() {
        // 只搬 code + 中文描述，sqlFragment 不出 api 层（理由见接口注释）
        return Arrays.stream(OperatorEnum.values()).map(op -> {
            SearchOperatorRespDTO dto = new SearchOperatorRespDTO();
            dto.setOperator(op.getCode());
            dto.setLabel(op.getDescription());
            return dto;
        }).toList();
    }

    /**
     * {@inheritDoc}
     *
     * <p>🔴 只搬 key + label + message，正则原文不出 api 层（理由见接口注释）。
     * 库加载失败时 {@code listAll()} 是空列表 —— 这里<b>不</b>抛，
     * 与 {@code ValidationPatternRegistry} 自己的取舍保持一致：正则库整个加载不出来时
     * 应用仍然要能启动，那时提示词只是印不出键名，不该连建表单都做不到。
     */
    @Override
    public List<ValidationPatternRespDTO> getValidationPatternList() {        return patternRegistry.listAll().stream().map(pattern -> {
            ValidationPatternRespDTO dto = new ValidationPatternRespDTO();
            dto.setKey(pattern.getKey());
            dto.setLabel(pattern.getLabel());
            dto.setMessage(pattern.getMessage());
            return dto;
        }).toList();
    }

    private FormDesignSaveReqVO toSaveReqVO(FormDesignSaveReqDTO reqDTO) {
        return BeanUtils.toBean(reqDTO, FormDesignSaveReqVO.class);
    }

    private FormDesignRespDTO convert(FormDesignDO formDesign) {
        FormDesignRespDTO dto = new FormDesignRespDTO();
        dto.setCode(formDesign.getCode());
        dto.setName(formDesign.getName());
        dto.setCategory(formDesign.getCategory());
        dto.setFormConf(formDesign.getFormConf());
        // formFields 在 DO 中是整份 JSON 字符串，对外统一转成数组，与 bpm_form.fields 的形态一致
        dto.setFormFields(FormFieldsCodec.read(formDesign.getFormFields()));
        TableBinding binding = parseTableBinding(formDesign);
        if (binding != null) {
            dto.setMainTable(binding.getMainTable());
            dto.setMainPk(binding.getMainPk());
        }
        return dto;
    }

}
