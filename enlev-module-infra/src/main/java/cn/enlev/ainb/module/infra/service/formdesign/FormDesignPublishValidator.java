package cn.enlev.ainb.module.infra.service.formdesign;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.common.biz.system.dict.DictDataCommonApi;
import cn.enlev.ainb.framework.common.biz.system.dict.dto.DictTypeRespDTO;
import cn.enlev.ainb.framework.common.enums.CommonStatusEnum;
import cn.enlev.ainb.framework.common.util.json.JsonUtils;
import cn.enlev.ainb.framework.mybatis.core.util.SystemTablePolicy;
import cn.enlev.ainb.framework.mybatis.core.util.TableOperation;
import cn.enlev.ainb.module.infra.dal.dataobject.billtemplate.BillTemplateDO;
import cn.enlev.ainb.module.infra.dal.dataobject.db.DataSourceConfigDO;
import cn.enlev.ainb.module.infra.dal.dataobject.formdesign.FormDesignDO;
import cn.enlev.ainb.module.infra.enums.billtemplate.SegmentSourceEnum;
import cn.enlev.ainb.module.infra.enums.formdesign.PkStrategyEnum;
import cn.enlev.ainb.module.infra.framework.billtemplate.core.BillFieldDefinition;
import cn.enlev.ainb.module.infra.framework.billtemplate.core.FormBillFieldResolver;
import cn.enlev.ainb.module.infra.framework.billtemplate.core.InlineRuleConfig;
import cn.enlev.ainb.module.infra.framework.billtemplate.core.InlineRuleEvaluator;
import cn.enlev.ainb.module.infra.framework.formdesign.core.FormFieldsCodec;
import cn.enlev.ainb.module.infra.framework.formdesign.core.binding.AutoFillConfig;
import cn.enlev.ainb.module.infra.framework.formdesign.core.binding.FieldMapping;
import cn.enlev.ainb.module.infra.framework.formdesign.core.binding.JoinTableBinding;
import cn.enlev.ainb.module.infra.framework.formdesign.core.binding.OptimisticLockConfig;
import cn.enlev.ainb.module.infra.framework.formdesign.core.binding.SideTableBinding;
import cn.enlev.ainb.module.infra.framework.formdesign.core.binding.SubTableBinding;
import cn.enlev.ainb.module.infra.framework.formdesign.core.binding.TableBinding;
import cn.enlev.ainb.module.infra.framework.formdesign.core.contract.JdbcTypeFamily;
import cn.enlev.ainb.module.infra.framework.formdesign.core.contract.WidgetContract;
import cn.enlev.ainb.module.infra.framework.formdesign.core.contract.WidgetContractRegistry;
import cn.enlev.ainb.module.infra.framework.formdesign.core.extension.FormDesignExtensionRegistry;
import cn.enlev.ainb.module.infra.framework.formdesign.core.formula.FormulaEvaluator;
import cn.enlev.ainb.module.infra.framework.formdesign.core.mask.MaskRule;
import cn.enlev.ainb.module.infra.framework.formdesign.core.formula.FormulaRecalculator;
import cn.enlev.ainb.module.infra.framework.formdesign.core.relation.RelationFormResolver;
import cn.enlev.ainb.module.infra.framework.formdesign.core.system.SystemValueGuard;
import cn.enlev.ainb.module.infra.enums.formdesign.AutoFillModeEnum;
import cn.enlev.ainb.module.infra.dal.mysql.formdesign.FormDesignMapper;
import cn.enlev.ainb.module.infra.framework.formdesign.core.logic.FormLogicGuard;
import cn.enlev.ainb.module.infra.framework.formdesign.core.logic.LogicEnums;
import cn.enlev.ainb.module.infra.framework.formdesign.core.logic.LogicRule;
import cn.enlev.ainb.module.infra.framework.formdesign.core.logic.LogicRuleCycleDetector;
import cn.enlev.ainb.module.infra.framework.formdesign.core.validation.CrossFieldRule;
import cn.enlev.ainb.module.infra.framework.formdesign.core.validation.FieldValidationRule;
import cn.enlev.ainb.module.infra.framework.formdesign.core.validation.FormValidationGuard;
import cn.enlev.ainb.module.infra.framework.formdesign.core.validation.ValidationPatternRegistry;
import cn.enlev.ainb.module.infra.dal.dataobject.datainterface.DataInterfaceDO;
import cn.enlev.ainb.module.infra.dal.mysql.datainterface.DataInterfaceMapper;
import cn.enlev.ainb.module.infra.framework.formdesign.core.button.CustomButton;
import cn.enlev.ainb.module.infra.framework.formdesign.core.binding.ChangeLogConfig;
import cn.enlev.ainb.module.infra.framework.formdesign.core.tab.DetailTab;
import cn.enlev.ainb.module.infra.framework.formdesign.core.print.PrintTemplate;
import cn.enlev.ainb.module.infra.framework.formdesign.core.meta.TableMetadataCache;
import cn.enlev.ainb.module.infra.framework.formdesign.core.widget.LinkUrlGuard;
import cn.enlev.ainb.module.infra.enums.formdesign.FormDesignStatusEnum;
import cn.enlev.ainb.module.infra.enums.formdesign.FormExtensionModeEnum;
import cn.enlev.ainb.module.infra.service.billtemplate.BillTemplateService;
import cn.enlev.ainb.module.infra.service.db.DataSourceConfigService;
import cn.enlev.ainb.module.infra.service.db.DatabaseTableService;
import com.baomidou.mybatisplus.generator.config.po.TableField;
import com.baomidou.mybatisplus.generator.config.po.TableInfo;
import com.fasterxml.jackson.core.type.TypeReference;
import cn.enlev.ainb.framework.common.exception.ErrorCode;
import cn.enlev.ainb.framework.common.exception.ServiceException;
import com.google.common.annotations.VisibleForTesting;
import jakarta.annotation.Resource;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.module.infra.enums.ErrorCodeConstants.*;

/**
 * 表单设计发布校验器
 *
 * 「只绑定已有表」方案的核心约束：表与列必须真实存在且结构匹配，
 * 发布时强制校验，防止运行时 SQL 报错。
 *
 * @author 走进新时代
 */
@Component
@Slf4j
public class FormDesignPublishValidator {

    /**
     * 子表控件在 form-create 规则里的 type
     *
     * 子表控件的内部规则挂在 {@code props.rule} 下，而不是常规布局容器的 {@code children}。
     *
     * <p>改造说明：此值原先是本类的 private static final 字面量，同时在
     * {@code FormDesignApiImpl} 与前端 4 处各有一份副本，每处都靠注释提醒「必须与另一处保持一致」。
     * 现已统一到控件契约（{@link WidgetContractRegistry#getSubTableWidgetType()}），
     * 后端不再有第二份定义。
     */
    private String subTableWidgetType() {
        return widgetContractRegistry.getSubTableWidgetType();
    }

    /**
     * 字典分组的节点类型值，对应 system 模块的 DictTypeNodeEnum.GROUP
     *
     * 这里用字面量而不是引用枚举：infra 不依赖 module-system，
     * 跨模块只走 enlev-common 里的 DTO
     */
    private static final Integer DICT_NODE_TYPE_GROUP = 1;

    /**
     * 字典懒加载的缓存模式值，对应 system 模块的 DictCacheModeEnum.LAZY
     */
    private static final Integer DICT_CACHE_MODE_LAZY = 2;

    /**
     * 由后端按登录上下文维护的列，不允许被表单字段映射
     *
     * 与 {@code FormDesignSqlBuilder.BACKEND_MANAGED_COLUMNS} 保持一致。
     * 两边都要有：SQL 构建层是安全边界（发布后改设计不会重走本校验），
     * 这里则负责在发布时给出人能看懂的提示。
     */
    private static final Set<String> BACKEND_MANAGED_COLUMNS = Set.of("tenant_id");

    /**
     * 文件类字段映射到字符串列时，列长度的下限
     *
     * 见 {@link #validateFileColumnLength} 的说明：取「字符串列的常规长度」这个约定，
     * 而不是精确算某种存储器的 URL 长度 —— 后者会随存储器配置变化，
     * 把它写成一个会漂移的数字，比一个偏保守的约定更难维护。
     */
    private static final int MIN_FILE_URL_COLUMN_LENGTH = 255;

    @Resource
    private DataSourceConfigService dataSourceConfigService;
    /**
     * 表结构元数据
     *
     * 走缓存而不是直连 {@code DatabaseTableService}：一次发布要读十几遍表结构
     * （主表、各从表、每张表的列长度校验各一遍），而每次直读都是一个新连接 + 一次
     * 整库解析。⚠️ 但发布必须看到<b>最新</b>结构，所以 {@link #validate} 一开始
     * 会先把这张表单涉及的表全部 invalidate 掉，见那里的注释。
     */
    @Resource
    private TableMetadataCache metadataCache;
    @Resource
    private FormBillFieldResolver billFieldResolver;
    /**
     * 校验「调数据接口」按钮引用的接口存在且启用。
     * 直接用 Mapper 而不是 Service：只要按编码查一行，走 Service 会把
     * 「校验」和「调用」两条路耦在一起
     */
    @Resource
    private DataInterfaceMapper dataInterfaceMapper;
    @Resource
    private BillTemplateService billTemplateService;

    /**
     * 控件契约注册表
     *
     * 控件的结构性事实（子表控件类型、是否支持、值类型、可映射列类型）统一从这里取，
     * 不再在本类里硬编码。
     */
    @Resource
    private WidgetContractRegistry widgetContractRegistry;

    /**
     * 联动规则的环检测
     */
    @Resource
    private LogicRuleCycleDetector cycleDetector;
    @Resource
    private FormLogicGuard logicGuard;
    @Resource
    private FormValidationGuard validationGuard;
    @Resource
    private ValidationPatternRegistry patternRegistry;
    /**
     * 服务端扩展点注册表。用于发布期的反向模式错配检查
     */
    @Resource
    private FormDesignExtensionRegistry extensionRegistry;
    /**
     * 公式字段解析。发布前要核实公式引用的字段存在、且没有循环引用
     */
    @Resource
    private FormulaRecalculator formulaRecalculator;
    /**
     * 关联表单配置解析。发布前要核实被关联表单已发布、带出字段存在
     */
    @Resource
    private RelationFormResolver relationFormResolver;
    /**
     * ⚠️ 关联数据列表页签要核「目标表单存在且已发布」，用的也是它。
     * <b>不能改成注入 FormDesignService</b>：那会构成循环依赖
     * （FormDesignServiceImpl 注入本类做发布校验），启动时报
     * BeanCurrentlyInCreationException —— 而单测直接 new 出本类、不经过容器，
     * 发现不了。交接文档第四节 A 类记的就是这一条
     */
    @Resource
    private FormDesignMapper formDesignMapper;
    @Resource
    private SystemValueGuard systemValueGuard;

    /**
     * 跨模块只走 enlev-common 里的通道，infra 不依赖 module-system
     */
    @Resource
    private DictDataCommonApi dictDataApi;

    /**
     * 发布前校验
     *
     * 校验失败直接抛业务异常，不返回结构化的校验结果 —— 发布是低频操作，
     * 用户会逐项改完再重试，不需要一次性列出所有错误。
     *
     * @param formDesign 待发布的表单设计
     */
    public void validate(FormDesignDO formDesign) {
        List<String> problems = inspect(formDesign);
        if (!problems.isEmpty()) {
            throw exception(FORM_DESIGN_PUBLISH_INVALID, String.join("；", problems));
        }
    }

    /**
     * 体检：把这张表单的问题<b>一次列全</b>，不抛异常
     *
     * <h3>为什么发布也走它</h3>
     *
     * 发布仍然「一条不满足就拒绝」，只是<b>报的时候一次列全</b>。
     * 两条路走同一份校验，体检说没问题而发布被拒是最糟的情况 ——
     * 用户会不知道该信哪个。
     *
     * <h3>⚠️ 分组短路：基础组有错就只报这一组</h3>
     *
     * 「收集全部」有个副作用：前面的校验失败后，后面的校验会基于错误的前提继续跑，
     * <b>冒出一堆由第一个错误派生出来的次生错误</b>。表还不存在时，列不存在、
     * 外键类型不匹配、列放不下文件 URL……会一口气报十几条，而真正要修的只有一条。
     * <b>次生错误比漏报更烦人</b>：它把真正的原因埋了。
     *
     * 所以分两组：
     *
     * <ul>
     *   <li><b>基础组</b>（字段非空、主表存在、控件受支持、tableBinding 可解析、
     *       数据源存在、主表列读得到）—— 有错就<b>立刻返回</b>，不再往下跑</li>
     *   <li><b>其余</b> —— 全部跑完，逐段收集</li>
     * </ul>
     *
     * 基础组之外不再细分：那些校验各自独立，一条失败不会让另一条的前提失效。
     *
     * @return 问题清单。空表示这张表单可以发布
     */
    public List<String> inspect(FormDesignDO formDesign) {
        // ⚠️ 去重放在这一层，不放在 inspect0 末尾：基础组短路时是【提前 return】的，
        // 放在末尾的话那几条路径全都绕过了去重 —— 实测「主表不存在」会报两遍
        // （validateMainTable 与紧接着的元数据读取各报一次），看起来像系统出了错
        return inspect0(formDesign).stream().distinct().collect(Collectors.toList());
    }

    private List<String> inspect0(FormDesignDO formDesign) {
        List<String> problems = new ArrayList<>();
        // 基础校验
        if (formDesign.getFormFields() == null || formDesign.getFormFields().isEmpty()) {
            problems.add(messageOf(FORM_DESIGN_FIELDS_EMPTY));
        }
        if (StrUtil.isBlank(formDesign.getMainTable())) {
            problems.add(messageOf(FORM_DESIGN_MAIN_TABLE_REQUIRED));
        }
        if (!problems.isEmpty()) {
            return problems;
        }

        // 校验表单里没有本模块不支持的控件。
        // 放在最前面：不支持的控件会让后续所有校验都建立在错误的字段清单上，
        // 先报它才能给出准确的提示
        run(problems, () -> validateWidgetsSupported(formDesign.getFormFields()));
        run(problems, () -> validateFieldKeysUnique(formDesign.getFormFields()));

        // 解析 tableBinding
        TableBinding binding = parseTableBinding(formDesign.getTableBinding());
        if (binding == null) {
            problems.add(messageOf(FORM_DESIGN_MAIN_TABLE_REQUIRED));
        }
        if (!problems.isEmpty()) {
            return problems;
        }

        // 校验数据源存在
        Long dataSourceId = formDesign.getDataSourceConfigId();
        if (dataSourceId == null) {
            dataSourceId = 0L;
        }
        if (dataSourceId != 0) {
            DataSourceConfigDO ds = dataSourceConfigService.getDataSourceConfig(dataSourceId);
            if (ds == null) {
                return List.of(messageOf(FORM_DESIGN_DATA_SOURCE_NOT_EXISTS));
            }
        }
        final Long dsId = dataSourceId;

        // ⚠️ 先把这张表单会用到的表的元数据缓存清掉，再开始校验
        //
        // 运行时为了性能走 TableMetadataCache（5 分钟 TTL），但发布这一步的职责恰恰是
        // 「真去核实表与列存在」—— 拿一份可能过期的结构去核实等于没核实：
        // DBA 刚加的列会被误判成不存在（用户配对了却发布不了），刚删的列会被放过
        // （发布通过、运行时才报错）。清一次的代价是每张表一次元数据读取，
        // 而发布是低频动作，这个代价换的是这道关口的可信度
        invalidateMetadataCache(dataSourceId, binding);

        // 校验主表存在且不是框架表。⚠️ 这是基础组的最后一条 ——
        // 主表读不到的话，后面每一条关于「列」的校验都会派生出次生错误
        run(problems, () -> validateMainTable(dsId, binding.getMainTable()));
        TableInfo mainTableInfo = metadataCache.get(dsId, binding.getMainTable());
        if (mainTableInfo == null || mainTableInfo.getFields() == null) {
            problems.add(messageOf(FORM_DESIGN_TABLE_NOT_EXISTS, binding.getMainTable()));
        }
        if (!problems.isEmpty()) {
            return problems;
        }
        Map<String, TableField> mainColumns = buildColumnMap(mainTableInfo.getFields());

        // 校验字段映射
        problems.addAll(validateFieldMapping(binding.getFieldMapping(), binding.getMainTable(), mainColumns));

        // 校验主键列类型能容纳所选主键策略
        run(problems, () -> validatePkType(binding, mainColumns));

        // 校验系统自动填充与字段映射没有争抢同一列
        run(problems, () -> validateAutoFillConflict(binding));

        // 多表绑定：表名不能跨分类重复，写入型从表不能是主表（只读自关联放开）
        run(problems, () -> validateTableCategories(binding));

        // 关联表展示列的别名不能与主表/副表字段重名 —— 同名列在结果 Map 里只会留一个
        run(problems, () -> validateJoinSelectAliases(binding));

        // 校验副表（1:1，参与写入）
        // 逐张收集：三张副表各有各的问题时，一次列全比「改一张再发布」省事
        for (SideTableBinding sideTable : nullSafe(binding.getSideTables())) {
            run(problems, () -> validateSideTable(dsId, binding, sideTable, mainColumns));
        }

        // 校验子表
        Set<String> widgetFields = collectSubTableWidgetFields(formDesign.getFormFields());
        for (SubTableBinding subTable : nullSafe(binding.getSubTables())) {
            run(problems, () -> validateSubTable(dsId, subTable, widgetFields));
            // 外键类型必须能容纳主表主键值
            run(problems, () -> validateForeignKeyType(dsId, binding, subTable.getTable(),
                    subTable.getForeignKey(), mainColumns));
        }
        // 反向校验：表单里的每个子表控件都必须有对应的明细表配置。
        // 少了它，控件提交的明细写不进任何表，而且接口照样返回成功 ——
        // 用户是在单据存了一堆之后才发现明细全是空的
        run(problems, () -> validateSubTableWidgetsBound(widgetFields, binding));

        // 校验只读关联表
        for (JoinTableBinding joinTable : nullSafe(binding.getJoinTables())) {
            run(problems, () -> validateJoinTable(dsId, joinTable));
        }

        // 校验单据字段：绑定的模板要存在且启用，且必须有落库的去处
        run(problems, () -> validateBillFields(formDesign, binding));

        // 校验选项字段引用的数据字典可用
        run(problems, () -> validateDictSources(formDesign));

        // 校验联动规则没有环形赋值
        run(problems, () -> validateLogicRuleCycle(formDesign));
        run(problems, () -> validateLogicRuleFields(formDesign));

        // 校验校验规则：正则要能编译、引用的内置正则与字段要存在
        run(problems, () -> validateValidationRules(formDesign));

        // 校验超链接的协议在白名单内
        run(problems, () -> validateLinkUrls(formDesign));

        // 校验公式字段：引用的字段要存在、不得循环引用、节点类型求值器要认识
        run(problems, () -> validateFormulaFields(formDesign));

        // 校验脱敏配置：策略名要认得出。认不出的话运行时会静默按「不脱敏」处理，
        // 而那意味着敏感字段在列表、详情、导出三个出口上全是明文
        run(problems, () -> validateMaskRules(formDesign));

        // 校验关联表单：被关联表单要已发布，带出字段要存在
        run(problems, () -> validateRelationFields(formDesign));

        // 校验系统值控件：绑定的列必须与自动填充配置一致
        run(problems, () -> validateSystemValueFields(formDesign, binding));

        // 校验文件类字段映射到的列放得下一个文件 URL
        run(problems, () -> validateFileColumnLength(formDesign, binding, dsId));

        // 校验乐观锁的版本列：存在、是整数列、且没被表单字段映射
        run(problems, () -> validateOptimisticLock(binding, mainColumns));

        // 校验自定义按钮：标识唯一、位置与动作合法、跳转协议在白名单内
        run(problems, () -> validateCustomButtons(formDesign));

        // 校验详情页页签：标识唯一、类型合法、变更日志页签要求开了变更日志
        run(problems, () -> validateDetailTabs(formDesign, binding));

        // 校验打印模板：占位符引用的字段要能取到值
        run(problems, () -> validatePrintTemplate(formDesign, binding));

        // 第三道模式错配告警：选了「服务端扩展点」却找不到实现
        run(problems, () -> validateExtensionModeConsistency(formDesign));

        // 表单+列表类型必须配置列表列
        if (Objects.equals(formDesign.getFormType(), 2) && StrUtil.isBlank(formDesign.getListConf())) {
            problems.add(messageOf(FORM_DESIGN_LIST_COLUMNS_EMPTY));
        }
        return problems;
    }

    /**
     * 清单非空就抛出去
     *
     * 给那些「本身是 void、由 run() 收集」的校验用：它们内部调用了收集式的子校验，
     * 结果得转回异常才能被 run() 捕获。同一张表的多条问题拼成一条 ——
     * 再往上拆需要把 validateSubTable / validateSideTable 也改成收集式，
     * 收益不抵改动面。
     */
    private void throwIfAny(List<String> problems) {
        if (!problems.isEmpty()) {
            throw exception(FORM_DESIGN_PUBLISH_INVALID, String.join("；", problems));
        }
    }

    /**
     * 跑一段校验，把它抛出的业务异常收进清单
     *
     * ⚠️ 只吞 {@link ServiceException}。别的异常（空指针、数据库连不上）是真的出错了，
     * 吞掉会让体检报告显示「没问题」，而发布时照样失败 —— 那比不体检更糟。
     */
    private void run(List<String> problems, Runnable check) {
        try {
            check.run();
        } catch (ServiceException e) {
            problems.add(e.getMessage());
        }
    }

    /**
     * 取错误码格式化后的文案，但不抛出
     *
     * 与 {@code exception(...)} 走同一条格式化路径 —— 各写一套的话，
     * 体检报告里的文案与发布时的报错会对不上，而用户会以为是两个问题
     */
    private String messageOf(ErrorCode errorCode, Object... params) {
        return exception(errorCode, params).getMessage();
    }

    /**
     * 校验选项字段引用的数据字典可用
     *
     * 下拉框、单选框、级联等控件可以把数据字典当作选项来源。字典一旦被删除、停用，
     * 或者被改成了懒加载（不进前端全量缓存），这些控件的选项会静默变成空白——
     * 页面上不报错，用户只会觉得「这个下拉框坏了」，排查时也没有任何线索指向字典。
     * 发布是把问题拦在上线之前的最后一道关口。
     */
    @VisibleForTesting
    void validateDictSources(FormDesignDO formDesign) {
        Map<String, String> fieldToDictType = collectDictSources(formDesign.getFormFields());
        if (CollUtil.isEmpty(fieldToDictType)) {
            return;
        }
        Map<String, DictTypeRespDTO> dictTypes = dictDataApi.getDictTypeMap(fieldToDictType.values());
        fieldToDictType.forEach((field, dictType) -> {
            DictTypeRespDTO type = dictTypes.get(dictType);
            if (type == null) {
                throw exception(FORM_DESIGN_DICT_SOURCE_NOT_EXISTS, field, dictType);
            }
            if (!CommonStatusEnum.isEnable(type.getStatus())) {
                throw exception(FORM_DESIGN_DICT_SOURCE_NOT_ENABLE, field, dictType);
            }
            if (Objects.equals(DICT_NODE_TYPE_GROUP, type.getNodeType())) {
                throw exception(FORM_DESIGN_DICT_SOURCE_IS_GROUP, field, dictType);
            }
            if (Objects.equals(DICT_CACHE_MODE_LAZY, type.getCacheMode())) {
                throw exception(FORM_DESIGN_DICT_SOURCE_LAZY, field, dictType);
            }
        });
    }

    /**
     * 收集表单里所有「以字典为选项来源」的字段
     *
     * @return 字段名 -> 字典类型
     */
    private Map<String, String> collectDictSources(String formFields) {
        List<String> rules = FormFieldsCodec.read(formFields);
        if (rules.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (String rule : rules) {
            Map<String, Object> node = JsonUtils.parseObjectQuietly(rule,
                    new TypeReference<Map<String, Object>>() {});
            if (node != null) {
                collectDictSources(node, result);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private void collectDictSources(Map<String, Object> node, Map<String, String> result) {
        if (node.get("props") instanceof Map<?, ?> propsMap) {
            Map<String, Object> props = (Map<String, Object>) propsMap;
            if (props.get("dictSource") instanceof Map<?, ?> sourceMap
                    && ((Map<String, Object>) sourceMap).get("dictType") instanceof String dictType
                    && StrUtil.isNotBlank(dictType)) {
                // 用 title 而不是 field 报错：field 是 UUID，用户在设计器里对不上号
                Object title = node.get("title");
                result.put(title instanceof String t && StrUtil.isNotBlank(t)
                        ? t : String.valueOf(node.get("field")), dictType);
            }
            // 子表控件的内部规则同样要扫
            if (props.get("rule") instanceof List<?> innerRules) {
                for (Object child : innerRules) {
                    if (child instanceof Map<?, ?> childMap) {
                        collectDictSources((Map<String, Object>) childMap, result);
                    }
                }
            }
        }
        if (node.get("children") instanceof List<?> list) {
            for (Object child : list) {
                if (child instanceof Map<?, ?> childMap) {
                    collectDictSources((Map<String, Object>) childMap, result);
                }
            }
        }
    }

    /**
     * 校验单据字段
     *
     * 这三类问题都属于「发布时不拦，运行时才发现」的典型：单号静默不生成、
     * 或生成了却无处存放。用户往往要等到第一张单子存进去、发现单号是空的才察觉，
     * 那时错误数据已经产生了。
     */
    private void validateBillFields(FormDesignDO formDesign, TableBinding binding) {
        List<BillFieldDefinition> fields = billFieldResolver.resolve(
                formDesign.getCode(), formDesign.getPublishedVersion(),
                formDesign.getFormFields(), binding);
        if (fields.isEmpty()) {
            return;
        }
        for (BillFieldDefinition field : fields) {
            // 必须有字段映射，否则号生成了没有列可写
            if (!hasMapping(binding, field)) {
                throw exception(BILL_TEMPLATE_FIELD_NO_MAPPING, field.displayName());
            }
            if (field.isInlineMode()) {
                validateInlineRule(binding, field);
                continue;
            }
            if (StrUtil.isBlank(field.getTemplateCode())) {
                throw exception(BILL_TEMPLATE_FIELD_UNBOUND, field.displayName());
            }
            BillTemplateDO template = billTemplateService.getBillTemplateByCode(field.getTemplateCode());
            if (template == null) {
                throw exception(BILL_TEMPLATE_NOT_EXISTS);
            }
            if (!Objects.equals(template.getStatus(), CommonStatusEnum.ENABLE.getStatus())) {
                throw exception(BILL_TEMPLATE_DISABLED, template.getName());
            }
        }
    }

    /**
     * 校验内联规则引用的字段在作用域内存在
     *
     * 引用了一个不存在的字段，运行时该段会静默输出为空 —— 单号少一截，
     * 但不会报错，用户很难发现。发布时拦下来是唯一能给出明确提示的时机。
     *
     * <p>作用域规则与运行时求值保持一致：子表字段可以引用本子表的字段，
     * 也可以回退引用主表字段；主表字段只能引用主表字段。
     */
    private void validateInlineRule(TableBinding binding, BillFieldDefinition field) {
        InlineRuleConfig config = InlineRuleEvaluator.parse(field.getRuleConfig());
        if (config == null) {
            return;
        }
        Set<String> scope = buildReferenceScope(binding, field);
        for (List<InlineRuleConfig.RuleSegment> segments :
                List.of(nullToEmpty(config.getPrefixSegments()), nullToEmpty(config.getSuffixSegments()))) {
            for (InlineRuleConfig.RuleSegment segment : segments) {
                if (segment == null || SegmentSourceEnum.typeOf(segment.getSourceType()) != SegmentSourceEnum.FIELD) {
                    continue;
                }
                if (StrUtil.isNotBlank(segment.getValue()) && !scope.contains(segment.getValue())) {
                    throw exception(BILL_TEMPLATE_REF_FIELD_UNKNOWN, segment.getValue());
                }
            }
        }
    }

    /**
     * 构造某个单据字段可以引用的字段集合
     */
    private Set<String> buildReferenceScope(TableBinding binding, BillFieldDefinition field) {
        Set<String> scope = new HashSet<>();
        if (binding.getFieldMapping() != null) {
            scope.addAll(binding.getFieldMapping().keySet());
        }
        if (field.isMainTable() || binding.getSubTables() == null) {
            return scope;
        }
        // 子表字段额外可以引用本子表内的字段
        for (SubTableBinding subTable : binding.getSubTables()) {
            if (subTable != null && Objects.equals(subTable.getField(), field.getSubTableField())
                    && subTable.getFieldMapping() != null) {
                scope.addAll(subTable.getFieldMapping().keySet());
            }
        }
        return scope;
    }

    private <T> List<T> nullToEmpty(List<T> list) {
        return list != null ? list : Collections.emptyList();
    }

    /**
     * 单据字段是否配了物理列
     *
     * 主表字段查主表映射，子表字段查该子表自己的映射。
     */
    private boolean hasMapping(TableBinding binding, BillFieldDefinition field) {
        if (field.isMainTable()) {
            Map<String, FieldMapping> mapping = binding.getFieldMapping();
            return mapping != null && mapping.containsKey(field.getField())
                    && mapping.get(field.getField()) != null
                    && StrUtil.isNotBlank(mapping.get(field.getField()).getColumn());
        }
        // 子表字段：解析器正是靠子表映射判定归属的，能走到这里就说明映射存在
        return true;
    }

    private TableBinding parseTableBinding(String tableBindingJson) {
        if (StrUtil.isBlank(tableBindingJson)) {
            return null;
        }
        return JsonUtils.parseObject(tableBindingJson, TableBinding.class);
    }

    private void validateMainTable(Long dataSourceId, String tableName) {
        TableInfo table = metadataCache.get(dataSourceId, tableName);
        if (table == null) {
            throw exception(FORM_DESIGN_TABLE_NOT_EXISTS, tableName);
        }
        // 框架表不可作为可写主表
        if (!SystemTablePolicy.isAllowed(tableName, TableOperation.WRITE)) {
            throw exception(FORM_DESIGN_FRAMEWORK_TABLE_DENY, tableName);
        }
    }

    private Map<String, TableField> buildColumnMap(List<TableField> fields) {
        Map<String, TableField> map = new HashMap<>();
        for (TableField field : fields) {
            map.put(field.getName(), field);
        }
        return map;
    }

    private List<String> validateFieldMapping(Map<String, FieldMapping> fieldMapping,
                                              String tableName,
                                              Map<String, TableField> columns) {
        if (fieldMapping == null || fieldMapping.isEmpty()) {
            return List.of();
        }
        // ⚠️ 逐条收集【并逐条返回】，不拼成一条：一张 50 字段的表单在 DBA 删了 3 列之后，
        // 第一条就抛会让用户「改一处、再发布、又被拦下一处」来回三轮；
        // 而拼成一条长文案，体检面板里会挤成一行、数不清到底有几个问题。
        // 这是本类里最值得做成收集式的一处 —— 字段映射的问题往往成批出现
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, FieldMapping> entry : fieldMapping.entrySet()) {
            FieldMapping mapping = entry.getValue();
            if (mapping == null || StrUtil.isBlank(mapping.getColumn())) {
                continue;
            }
            // 租户列由后端按登录上下文填充，映射到它等于让表单值决定记录归属哪个租户。
            // SQL 构建层已经硬拦（那才是安全边界），这里提前报错是为了给出可读的提示，
            // 而不是让用户在运行时才撞上一个看不懂的错误
            if (BACKEND_MANAGED_COLUMNS.contains(mapping.getColumn().trim().toLowerCase())) {
                problems.add(messageOf(FORM_DESIGN_PROTECTED_COLUMN_MAPPED, mapping.getColumn()));
                continue;
            }
            // 映射的列必须在主表中存在
            if (!columns.containsKey(mapping.getColumn())) {
                problems.add(messageOf(FORM_DESIGN_COLUMN_NOT_EXISTS, tableName, mapping.getColumn()));
            }
        }
        return problems;
    }

    /**
     * 校验子表（1:N 明细，参与写入）
     *
     * 这里的每一条都对应一种「发布能过、运行时静默不生效」的配置错误 ——
     * 明细存不进去、查不出来，但接口全返回成功，用户往往是过了几天发现单据没有明细才察觉。
     * 发布是唯一能给出明确提示的时机。
     *
     * @param widgetFields 表单里实际存在的子表控件 field 集合
     */
    private void validateSubTable(Long dataSourceId, SubTableBinding subTable, Set<String> widgetFields) {
        if (subTable == null || StrUtil.isBlank(subTable.getTable())) {
            return;
        }
        String tableName = subTable.getTable();

        // 子表参与写入，不能是框架表。原实现漏了这条，副表却有 —— 两者写入权限是一样的
        if (!SystemTablePolicy.isAllowed(tableName, TableOperation.WRITE)) {
            throw exception(FORM_DESIGN_FRAMEWORK_TABLE_DENY, tableName);
        }

        // 子表必须存在
        TableInfo subTableInfo = metadataCache.get(dataSourceId, tableName);
        if (subTableInfo == null || subTableInfo.getFields() == null) {
            throw exception(FORM_DESIGN_TABLE_NOT_EXISTS, tableName);
        }
        Map<String, TableField> subColumns = buildColumnMap(subTableInfo.getFields());

        // 控件 field 是「从提交数据里取哪一段明细」的唯一依据，缺了它明细永远是空
        if (StrUtil.isBlank(subTable.getField())) {
            throw exception(FORM_DESIGN_SUB_TABLE_FIELD_REQUIRED, tableName);
        }
        // 配置指向的控件必须真的在表单里。改了控件 field 却忘了同步子表配置时，
        // 提交数据里根本没有这个键，明细静默丢失
        if (!widgetFields.isEmpty() && !widgetFields.contains(subTable.getField())) {
            throw exception(FORM_DESIGN_SUB_TABLE_WIDGET_MISSING, subTable.getField(), tableName);
        }

        // 外键是明细与主表记录的唯一纽带，与副表同等必填。
        // 原实现把它当可选，导致外键为空时明细全部写成孤儿行
        if (StrUtil.isBlank(subTable.getForeignKey())) {
            throw exception(FORM_DESIGN_FK_REQUIRED, tableName);
        }
        if (!subColumns.containsKey(subTable.getForeignKey())) {
            throw exception(FORM_DESIGN_COLUMN_NOT_EXISTS, tableName, subTable.getForeignKey());
        }

        // 主键列必须存在：逐行 diff 全靠它识别新增/修改/删除
        String pkColumn = StrUtil.isNotBlank(subTable.getPk()) ? subTable.getPk() : "id";
        if (!subColumns.containsKey(pkColumn)) {
            throw exception(FORM_DESIGN_COLUMN_NOT_EXISTS, tableName, pkColumn);
        }
        // 明细主键策略与列类型要匹配，理由同主表
        validatePkTypeFor(subTable.getPkStrategy(), tableName, pkColumn, subColumns);

        // 字段映射为空 = 明细只会写进主键与外键，业务字段全丢
        if (subTable.getFieldMapping() == null || subTable.getFieldMapping().isEmpty()) {
            throw exception(FORM_DESIGN_SUB_TABLE_MAPPING_EMPTY, tableName, subTable.getField());
        }
        // ⚠️ 返回值必须消费掉。改成收集式之后这里一度写成了裸调用 ——
        // 那会把明细表的列问题静默丢掉，而「配了但不生效」正是本模块最忌讳的
        throwIfAny(validateFieldMapping(subTable.getFieldMapping(), tableName, subColumns));
    }

    /**
     * 校验表单里的每个子表控件都配了明细表
     *
     * 与 {@link #validateSubTable} 是相反方向：那边查「配置指向的控件在不在」，
     * 这边查「控件有没有对应的配置」。两个方向都必须成立，链路才是通的。
     *
     * 缺了这一条，用户拖个子表控件却忘了在数据绑定步骤加明细表，表单能正常发布、
     * 提交也返回成功，但明细写不进任何表 —— 典型的静默数据丢失。
     */
    private void validateSubTableWidgetsBound(Set<String> widgetFields, TableBinding binding) {
        if (widgetFields.isEmpty()) {
            return;
        }
        Set<String> boundFields = new HashSet<>();
        if (binding.getSubTables() != null) {
            for (SubTableBinding subTable : binding.getSubTables()) {
                if (subTable != null && StrUtil.isNotBlank(subTable.getField())) {
                    boundFields.add(subTable.getField());
                }
            }
        }
        for (String widgetField : widgetFields) {
            if (!boundFields.contains(widgetField)) {
                throw exception(FORM_DESIGN_SUB_TABLE_WIDGET_UNBOUND, widgetField);
            }
        }
    }

    /**
     * 校验联动规则引用的字段都还存在
     *
     * <h3>为什么必须在发布这一关拦</h3>
     *
     * 典型路径是：先配好联动，之后回表单设计把某个字段删了。运行时
     * {@link LogicRuleEvaluator} 取不到这个字段就当条件不成立 / 动作无目标，
     * **把整条规则静默跳过** —— 界面上没有任何异常，用户以为联动还在生效。
     *
     * 这与跨字段校验是同一件事，那边早就有
     * {@link cn.enlev.ainb.module.infra.enums.ErrorCodeConstants#FORM_DESIGN_VALIDATE_RULE_FIELD_NOT_EXISTS}
     * 在发布期拦；联动这边原先只有环形赋值检测，字段存在性从保存到发布全程无人过问，
     * 是个不对称的缺口。前端 {@code LogicRuleEditor.invalidFieldRefs} 现在也会在
     * 保存前拦一次，两道防线的分工与别处一致：前端保即时反馈，发布校验保配置正确。
     *
     * <h3>行作用域规则按明细列解析</h3>
     *
     * {@code rowScope} 规则对子表的每一行求值，条件与动作里的字段名是**明细列的裸名**
     * （见 {@link FormLogicGuard#checkRowScopedRules}，它直接拿 rowData 当上下文）。
     * 而 {@link FormLogicGuard#parseFieldMetas} 只收主表字段与子表控件自身的 field，
     * 不下钻 {@code props.rule}。直接拿主表字段清单去比对，**每一条 rowScope 规则都会被
     * 误判成引用了不存在的字段** —— 那会把一批本来正常的表单卡在发布上，比漏报严重得多。
     * 所以这里对 rowScope 规则单独取该子表的明细列来比对。
     */
    @VisibleForTesting
    void validateLogicRuleFields(FormDesignDO formDesign) {
        List<LogicRule> rules = logicGuard.parseRules(formDesign.getLogicRules());
        if (rules.isEmpty()) {
            return;
        }
        Set<String> mainFields = logicGuard.parseFieldMetas(formDesign.getFormFields()).keySet();
        for (LogicRule rule : rules) {
            if (rule == null || !rule.isEnabled()) {
                // 停用的规则不校验：用户可能是先停用、留着以后再用
                continue;
            }
            // 行作用域规则比对该子表的明细列；常规规则比对主表字段
            Set<String> knownFields = rule.isRowScoped()
                    ? collectSubTableColumns(formDesign.getFormFields(), rule.getRowScope())
                    : mainFields;
            Set<String> referenced = new LinkedHashSet<>();
            collectConditionFields(rule.getWhen(), referenced);
            for (LogicRule.Action action : rule.getThenOrEmpty()) {
                if (action != null && StrUtil.isNotBlank(action.getTarget())) {
                    referenced.add(action.getTarget());
                }
            }
            for (LogicRule.Action action : rule.getOtherwiseOrEmpty()) {
                if (action != null && StrUtil.isNotBlank(action.getTarget())) {
                    referenced.add(action.getTarget());
                }
            }
            String ruleLabel = StrUtil.blankToDefault(rule.getName(), rule.getId());
            for (String field : referenced) {
                if (!knownFields.contains(field)) {
                    throw exception(FORM_DESIGN_LOGIC_RULE_FIELD_NOT_EXISTS, ruleLabel, field);
                }
            }
        }
    }

    /**
     * 取某个子表控件下的明细列名（裸名，不带层级前缀）
     *
     * 子表控件的内部规则挂在 {@code props.rule} 下，而不是 {@code children} ——
     * 这也是 {@link FormLogicGuard#parseFieldMetas} 收不到明细列的原因。
     * 嵌套子表后端不支持，只下钻一层。
     *
     * @param formFields    表单字段 JSON
     * @param subTableField 子表控件的 field
     * @return 该子表的明细列名；找不到该子表时返回空集合
     */
    @SuppressWarnings("unchecked")
    private Set<String> collectSubTableColumns(String formFields, String subTableField) {
        Set<String> columns = new LinkedHashSet<>();
        if (StrUtil.isBlank(subTableField)) {
            return columns;
        }
        for (String rule : FormFieldsCodec.read(formFields)) {
            Map<String, Object> node = JsonUtils.parseObjectQuietly(rule,
                    new TypeReference<Map<String, Object>>() {});
            if (node != null) {
                collectSubTableColumns(node, subTableField, columns);
            }
        }
        return columns;
    }

    @SuppressWarnings("unchecked")
    private void collectSubTableColumns(Map<String, Object> node, String subTableField,
                                        Set<String> columns) {
        if (subTableWidgetType().equals(node.get("type"))
                && subTableField.equals(node.get("field"))
                && node.get("props") instanceof Map<?, ?> propsMap
                && ((Map<String, Object>) propsMap).get("rule") instanceof List<?> innerRules) {
            for (Object inner : innerRules) {
                if (inner instanceof Map<?, ?> innerMap
                        && ((Map<String, Object>) innerMap).get("field") instanceof String column
                        && StrUtil.isNotBlank(column)) {
                    columns.add(column);
                }
            }
            return;
        }
        if (node.get("children") instanceof List<?> list) {
            for (Object child : list) {
                if (child instanceof Map<?, ?> childMap) {
                    collectSubTableColumns((Map<String, Object>) childMap, subTableField, columns);
                }
            }
        }
    }

    /**
     * 校验联动规则没有环形赋值
     *
     * 求值器本身能容忍不收敛（跑满轮数后标记 stable=false，不抛异常），所以环形规则
     * 不会让系统崩掉。但对用户来说那意味着「我配的联动有时生效有时不生效」，
     * 而日志在服务器上他看不到。发布前拦住，是把反馈提前到他还记得自己配了什么的时候。
     */
    @VisibleForTesting
    void validateLogicRuleCycle(FormDesignDO formDesign) {
        List<LogicRule> rules = logicGuard.parseRules(formDesign.getLogicRules());
        if (rules.isEmpty()) {
            return;
        }
        List<String> cycles = cycleDetector.detect(rules);
        if (!cycles.isEmpty()) {
            throw exception(FORM_DESIGN_LOGIC_RULE_CYCLE, String.join("；", cycles));
        }
    }

    /**
     * 校验校验规则本身配得对不对
     *
     * 运行时对配错的校验规则一律「放过 + 记日志」（见 {@link FormValidationGuard}），
     * 因为让一条配歪的校验把整张表单变成不可提交，代价远大于少生效一条约束。
     * 但这个容错有个副作用：**配错的校验在运行时完全看不出来** ——
     * 用户以为「手机号格式」已经在生效，实际上任何输入都能存进去。
     *
     * 所以配置错误必须在发布这一关拦住。这是同一件事的两面：
     * 运行时容错保可用性，发布校验保配置正确。
     */
    @VisibleForTesting
    void validateValidationRules(FormDesignDO formDesign) {
        // 1. 字段级格式规则
        for (FormValidationGuard.ValidatedField field
                : validationGuard.parseValidatedFields(formDesign.getFormFields())) {
            for (FieldValidationRule rule : field.getRules()) {
                if (!rule.isEnabled()) {
                    // 停用的规则不校验：用户可能是先停用、留着以后再用
                    continue;
                }
                if (rule.resolveType() == FieldValidationRule.Type.BUILTIN) {
                    if (patternRegistry.get(rule.getPatternKey()) == null) {
                        throw exception(FORM_DESIGN_VALIDATE_PATTERN_NOT_EXISTS,
                                field.label(), rule.getPatternKey());
                    }
                    continue;
                }
                if (StrUtil.isBlank(rule.getPattern())) {
                    throw exception(FORM_DESIGN_VALIDATE_REGEX_INVALID, field.label(), "正则为空");
                }
                if (patternRegistry.compileCustom(rule.getPattern()) == null) {
                    throw exception(FORM_DESIGN_VALIDATE_REGEX_INVALID,
                            field.label(), rule.getPattern());
                }
            }
        }

        // 2. 跨字段规则
        List<CrossFieldRule> crossRules =
                validationGuard.parseCrossFieldRules(formDesign.getValidateRules());
        if (crossRules.isEmpty()) {
            return;
        }
        // 字段清单只含主表字段 —— 跨字段校验本身也只作用于主表。
        // 明细行内的跨列约束走联动规则的行作用域（B5 已支持），不在这里
        Set<String> knownFields = logicGuard.parseFieldMetas(formDesign.getFormFields()).keySet();
        for (CrossFieldRule rule : crossRules) {
            if (!rule.isEnabled()) {
                continue;
            }
            String ruleLabel = StrUtil.blankToDefault(rule.getName(), rule.getId());
            if (!rule.isUsable()) {
                throw exception(FORM_DESIGN_VALIDATE_RULE_INCOMPLETE, ruleLabel);
            }
            Set<String> referenced = new LinkedHashSet<>();
            collectConditionFields(rule.getWhen(), referenced);
            collectConditionFields(rule.getMust(), referenced);
            for (String field : referenced) {
                if (!knownFields.contains(field)) {
                    throw exception(FORM_DESIGN_VALIDATE_RULE_FIELD_NOT_EXISTS, ruleLabel, field);
                }
            }
        }
    }

    /**
     * 校验超链接控件的地址协议
     *
     * javascript: 与 data: 能在用户点击时执行任意脚本。地址是表单设计者配的、
     * 填表人点的，两者往往不是同一个信任级别，所以这一条必须由服务端把关 ——
     * 前端也有一份等价判断，但那是即时反馈，改一改请求就能绕过。
     */
    @VisibleForTesting
    void validateLinkUrls(FormDesignDO formDesign) {
        for (LinkUrlGuard.UnsafeLink unsafe : LinkUrlGuard.findUnsafeLinks(formDesign.getFormFields())) {
            throw exception(FORM_DESIGN_LINK_URL_UNSAFE, unsafe.getTitle(), unsafe.getUrl());
        }
    }

    /**
     * 递归收集条件树里引用的字段
     *
     * 右值也要收：{@code valueType=FIELD} 时它是另一个字段名，
     * 而「结束日期 ≥ 开始日期」正是把开始日期写在右值上 —— 只查左值等于漏掉了一半。
     */
    private void collectConditionFields(LogicRule.ConditionGroup group, Set<String> result) {
        if (group == null) {
            return;
        }
        for (LogicRule.ConditionNode node : group.getItemsOrEmpty()) {
            if (node == null) {
                continue;
            }
            if (node.isGroup()) {
                collectConditionFields(node.asGroup(), result);
                continue;
            }
            if (StrUtil.isNotBlank(node.getField())) {
                result.add(node.getField());
            }
            if (node.getValueTypeOrDefault() == LogicEnums.ValueType.FIELD
                    && node.getValue() instanceof String refField && StrUtil.isNotBlank(refField)) {
                result.add(refField);
            }
        }
    }

    /**
     * 校验系统值控件
     *
     * 系统值控件展示的是创建人、创建时间这类由后端自动填充的值。它自己不落库 ——
     * 值由 AutoFillConfig 按登录上下文写入。所以要核对两件事：
     *
     * <ol>
     *   <li>若该字段映射了物理列，那一列必须【正是】对应来源的自动填充列。
     *       映射到别的列，控件显示的就不是它声称的那个值 ——
     *       一个写着「创建人」的字段实际显示的是别的内容，比不显示更糟</li>
     *   <li>对应来源的自动填充必须处于 AUTO 模式。若是 MANUAL，那一列交给表单手工填，
     *       而系统值控件是只读的、提交值又被剥离，结果是这一列永远为空</li>
     * </ol>
     *
     * CURRENT_USER / CURRENT_DEPT / NOW 这些来源不落库、只做展示，不参与本校验。
     */
    @VisibleForTesting
    void validateSystemValueFields(FormDesignDO formDesign, TableBinding binding) {
        List<SystemValueGuard.SystemValueField> fields =
                systemValueGuard.parseSystemValueFields(formDesign.getFormFields());
        if (fields.isEmpty()) {
            return;
        }
        AutoFillConfig autoFill = binding.getAutoFill();
        Map<String, FieldMapping> fieldMapping = binding.getFieldMapping();

        for (SystemValueGuard.SystemValueField field : fields) {
            SystemValueGuard.AutoFillSource source =
                    SystemValueGuard.AutoFillSource.parse(field.getSource());
            if (source == null) {
                // 不落库的展示型来源，跳过
                continue;
            }
            FieldMapping mapping = fieldMapping == null ? null : fieldMapping.get(field.getField());
            if (mapping == null || StrUtil.isBlank(mapping.getColumn())) {
                // 没映射列 —— 纯展示用法，合法
                continue;
            }
            String expectedColumn = autoFillColumnOf(autoFill, source);
            String expectedMode = autoFillModeOf(autoFill, source);
            if (StrUtil.isBlank(expectedColumn)) {
                throw exception(FORM_DESIGN_SYSTEM_VALUE_NO_AUTO_FILL,
                        field.getField(), source.name());
            }
            if (!StrUtil.equalsIgnoreCase(expectedColumn, mapping.getColumn())) {
                throw exception(FORM_DESIGN_SYSTEM_VALUE_COLUMN_MISMATCH,
                        field.getField(), mapping.getColumn(), expectedColumn);
            }
            if (AutoFillModeEnum.parse(expectedMode) != AutoFillModeEnum.AUTO) {
                throw exception(FORM_DESIGN_SYSTEM_VALUE_NOT_AUTO,
                        field.getField(), expectedColumn);
            }
        }
    }

    private String autoFillColumnOf(AutoFillConfig config, SystemValueGuard.AutoFillSource source) {
        if (config == null) {
            return null;
        }
        return switch (source) {
            case CREATOR -> config.getCreatorColumn();
            case CREATE_TIME -> config.getCreateTimeColumn();
            case UPDATER -> config.getUpdaterColumn();
            case UPDATE_TIME -> config.getUpdateTimeColumn();
        };
    }

    private String autoFillModeOf(AutoFillConfig config, SystemValueGuard.AutoFillSource source) {
        if (config == null) {
            return null;
        }
        return switch (source) {
            case CREATOR -> config.getCreatorMode();
            case CREATE_TIME -> config.getCreateTimeMode();
            case UPDATER -> config.getUpdaterMode();
            case UPDATE_TIME -> config.getUpdateTimeMode();
        };
    }

    /**
     * 校验关联表单控件
     *
     * 四件事：
     * <ol>
     *   <li>被关联表单必须存在 —— 指向一个已删除的表单，运行时选不出任何数据，
     *       而界面上只是一个点开没有内容的下拉框，用户不会想到是配置问题</li>
     *   <li>被关联表单必须【已发布】—— 未发布的表单没有运行时数据表可查</li>
     *   <li>不得关联自己 —— 自引用会让「选一条记录带出它自己的字段」形成环，
     *       且在带出时递归读同一张表</li>
     *   <li>带出映射的目标字段必须在当前表单里存在</li>
     * </ol>
     *
     * 【不】校验源字段是否存在于被关联表单：那要去解析另一张表单的规则，
     * 而它随时可能被改。源字段读不到时带出值为空并记 warn，比在发布时
     * 用另一张表单的当前状态去卡住本表单的发布更合适。
     */
    @VisibleForTesting
    void validateRelationFields(FormDesignDO formDesign) {
        List<RelationFormResolver.RelationField> relations =
                relationFormResolver.parseRelationFields(formDesign.getFormFields());
        if (relations.isEmpty()) {
            return;
        }
        Set<String> ownFields = logicGuard.parseFieldMetas(formDesign.getFormFields()).keySet();

        for (RelationFormResolver.RelationField relation : relations) {
            String targetCode = relation.getTargetFormCode();
            if (StrUtil.equals(targetCode, formDesign.getCode())) {
                throw exception(FORM_DESIGN_RELATION_SELF_REFERENCE, relation.getField());
            }
            FormDesignDO target = formDesignMapper.selectByCode(targetCode);
            if (target == null) {
                throw exception(FORM_DESIGN_RELATION_FORM_NOT_EXISTS, relation.getField(), targetCode);
            }
            boolean published = target.getPublishedVersion() != null && target.getPublishedVersion() > 0;
            if (!published) {
                throw exception(FORM_DESIGN_RELATION_FORM_NOT_PUBLISHED,
                        relation.getField(), target.getName());
            }
            for (RelationFormResolver.FieldMapping mapping : relation.getFieldMappings()) {
                if (!ownFields.contains(mapping.getTargetField())) {
                    throw exception(FORM_DESIGN_RELATION_TARGET_FIELD_MISSING,
                            relation.getField(), mapping.getTargetField());
                }
            }
            // 间接环：A 关联 B、B 又关联回 A
            validateRelationCycle(formDesign.getCode(), targetCode, relation.getField());
        }
    }

    /**
     * 关联表单的间接环检测
     *
     * <h3>为什么只拦自引用不够</h3>
     *
     * 上面已经拦了 {@code A → A}，但 {@code A → B → A} 拦不住：
     * 发布 A 时 B 还没关联 A，发布 B 时 A 已经发布过了，两次都能过。
     * 形成环之后，「选一条记录带出它的字段」在两张表单之间来回指，
     * 设计器里看不出问题，运行时表现为带出值莫名其妙。
     *
     * <h3>为什么广度优先而不是递归</h3>
     *
     * 递归写法遇到环会自己栈溢出 —— 那正是要检测的东西。
     * 这里用队列 + visited，环只会让它提前终止。
     *
     * ⚠️ 深度上限是防御：关联链理论上可以很长，而发布是同步操作，
     * 不能让它在一条畸形的链上跑太久。
     */
    private void validateRelationCycle(String sourceCode, String targetCode, String field) {
        final int MAX_DEPTH = 32;
        Set<String> visited = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(targetCode);
        int steps = 0;
        while (!queue.isEmpty() && steps++ < MAX_DEPTH) {
            String current = queue.poll();
            if (StrUtil.equals(current, sourceCode)) {
                throw exception(FORM_DESIGN_RELATION_CYCLE, field,
                        String.join(" → ", visited) + " → " + sourceCode);
            }
            if (!visited.add(current)) {
                continue;
            }
            FormDesignDO design = formDesignMapper.selectByCode(current);
            if (design == null) {
                continue;
            }
            relationFormResolver.parseRelationFields(design.getFormFields()).stream()
                    .map(RelationFormResolver.RelationField::getTargetFormCode)
                    .filter(StrUtil::isNotBlank)
                    .filter(code -> !visited.contains(code))
                    .forEach(queue::add);
        }
    }

    /**
     * 校验公式字段
     *
     * 两件事：
     * <ol>
     *   <li>公式引用的字段必须在表单里存在 —— 引用了已删除的字段，公式会永远算出 null，
     *       而用户看到的是「合计栏一直是空的」，很难反推到是引用失效</li>
     *   <li>公式之间不得循环引用 —— 那会让计算顺序不确定，同一份数据两次提交可能算出
     *       不同结果。重算器能容忍环（跳过并记日志），但那是第二道防线，
     *       正主是发布时就拦住</li>
     * </ol>
     */
    @VisibleForTesting
    void validateFormulaFields(FormDesignDO formDesign) {
        List<FormulaRecalculator.FormulaField> formulas =
                formulaRecalculator.parseFormulaFields(formDesign.getFormFields());
        if (formulas.isEmpty()) {
            return;
        }
        // 表单里现有的全部字段名
        Set<String> existingFields = logicGuard.parseFieldMetas(formDesign.getFormFields()).keySet();
        // 只把主表公式放进环检测的图里：子表公式的作用域是一行明细，
        // 与主表公式不在同一个依赖图上。混在一起会让主子表里的同名字段
        // （明细叫 amount、主表也叫 amount）被当成同一个节点，凭空构造出一个环
        Map<String, FormulaRecalculator.FormulaField> byField = new LinkedHashMap<>();
        formulas.stream().filter(f -> f.getSubField() == null)
                .forEach(f -> byField.put(f.getField(), f));

        // ⚠️ 子表里的公式引用的是【同一行明细】上的字段，不是主表字段。
        // 拿主表字段集去校验，会把 {数量} * {单价} 这种最常见的明细行公式
        // 误判成「引用了不存在的字段」而拒绝发布
        Map<String, Set<String>> subTableFields =
                formulaRecalculator.parseSubTableFields(formDesign.getFormFields());

        for (FormulaRecalculator.FormulaField formula : formulas) {
            Set<String> scope = formula.getSubField() != null
                    ? subTableFields.getOrDefault(formula.getSubField(), Set.of())
                    : existingFields;
            for (String dep : formula.getDependencies()) {
                if (!scope.contains(dep)) {
                    throw exception(FORM_DESIGN_FORMULA_FIELD_MISSING, formula.getField(), dep);
                }
            }
        }

        // 节点类型必须是求值器认识的那几个。
        //
        // ⚠️ 这条拦截不是可有可无的：求值器对认不出的节点类型是**返回 null**
        // （见 FormulaEvaluator#eval 的 default 分支，那是为了兼容旧版本 AST 的
        // 有意设计）。于是把字面量的 type 写成 value 而不是 num 时，
        // 整条公式算出 0 —— 发布不报错、运行时不报错、列表上只是那一列永远是 0。
        // 这正是本模块最难查的那类缺陷：配了、存下来了、但不生效且没有提示。
        for (FormulaRecalculator.FormulaField formula : formulas) {
            String unknown = findUnknownNodeType(formula.getAst());
            if (unknown != null) {
                throw exception(FORM_DESIGN_FORMULA_NODE_UNKNOWN, formula.getField(), unknown);
            }
        }

        // 环检测只在主表公式之间做：子表公式的作用域是一行明细，
        // 与主表公式不在同一个依赖图里
        List<String> cycle = findFormulaCycle(byField);
        if (cycle != null) {
            throw exception(FORM_DESIGN_FORMULA_CYCLE, String.join(" → ", cycle));
        }
    }

    /**
     * 脱敏配置必须是求值器认得出的那几个策略名
     *
     * <h3>为什么要在发布时拦</h3>
     *
     * {@code MaskRule.parse} 对认不出的策略名**返回 NONE**（不脱敏）——
     * 那是运行时的有意设计：一个拼错的配置不该把正常字段变成一片星号。
     *
     * 但它带来的后果是：{@code maskRule} 写成对象（{@code {"type":"MOBILE"}}）
     * 或拼错字母时，**脱敏静默失效**。而脱敏失效意味着手机号、身份证
     * 在列表、详情、导出三个出口上全是明文 —— 尤其是导出，一次能带走上万行。
     *
     * 这类「配了、看起来生效了、其实没有」的问题在界面上完全看不出来：
     * 设计器里那一栏填着东西，列表上的值看着也正常（因为本来就该显示值）。
     * 所以必须在发布这一关拦住。
     */
    void validateMaskRules(FormDesignDO formDesign) {
        for (String rule : FormFieldsCodec.read(formDesign.getFormFields())) {
            Map<String, Object> node = JsonUtils.parseObjectQuietly(rule,
                    new TypeReference<Map<String, Object>>() {});
            if (node != null) {
                validateMaskRuleNode(node);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void validateMaskRuleNode(Map<String, Object> node) {
        Object props = node.get("props");
        if (props instanceof Map<?, ?> propsMap) {
            Object maskRule = ((Map<String, Object>) propsMap).get("maskRule");
            if (maskRule != null) {
                String field = node.get("title") instanceof String t && StrUtil.isNotBlank(t)
                        ? t : String.valueOf(node.get("field"));
                if (!(maskRule instanceof String text)) {
                    // 对象形态是最容易踩的：设计器之外的构造方式（脚本、接口）很自然地
                    // 会写成 {"type":"MOBILE"}，而它会被静默当成「不脱敏」
                    throw exception(FORM_DESIGN_MASK_RULE_INVALID, field,
                            JsonUtils.toJsonString(maskRule));
                }
                if (MaskRule.parse(text) == MaskRule.NONE && !"NONE".equalsIgnoreCase(text)) {
                    throw exception(FORM_DESIGN_MASK_RULE_INVALID, field, text);
                }
            }
            // 子表控件的列挂在 props.rule 下
            Object inner = ((Map<String, Object>) propsMap).get("rule");
            if (inner instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> itemMap) {
                        validateMaskRuleNode((Map<String, Object>) itemMap);
                    }
                }
            }
        }
        if (node.get("children") instanceof List<?> children) {
            for (Object child : children) {
                if (child instanceof Map<?, ?> childMap) {
                    validateMaskRuleNode((Map<String, Object>) childMap);
                }
            }
        }
    }

    /**
     * 公式求值器认识的节点类型
     *
     * 与 {@link cn.enlev.ainb.module.infra.framework.formdesign.core.formula.FormulaEvaluator}
     * 的 eval 分支一一对应。那边加了新类型时这里要跟着加 ——
     * 漏了的后果是「新功能发布不上去」，比反过来（认不出却放行）安全得多。
     */
    private static final Set<String> KNOWN_FORMULA_NODE_TYPES = Set.of(
            "num", "str", "bool", "field", "subfield", "unary", "binary", "call");

    /**
     * 递归找第一个求值器认不出的节点类型
     *
     * @return 认不出的类型名；全都认识时返回 null
     */
    private String findUnknownNodeType(FormulaEvaluator.FormulaNode node) {
        if (node == null) {
            return null;
        }
        if (StrUtil.isBlank(node.getType()) || !KNOWN_FORMULA_NODE_TYPES.contains(node.getType())) {
            return StrUtil.isBlank(node.getType()) ? "(空)" : node.getType();
        }
        for (FormulaEvaluator.FormulaNode child : new FormulaEvaluator.FormulaNode[]{
                node.getOperand(), node.getLeft(), node.getRight()}) {
            String unknown = findUnknownNodeType(child);
            if (unknown != null) {
                return unknown;
            }
        }
        for (FormulaEvaluator.FormulaNode arg : node.getArgsOrEmpty()) {
            String unknown = findUnknownNodeType(arg);
            if (unknown != null) {
                return unknown;
            }
        }
        return null;
    }

    /**
     * 找公式之间的循环引用。返回环上的字段，没有环返回 null
     */
    private List<String> findFormulaCycle(Map<String, FormulaRecalculator.FormulaField> byField) {
        Set<String> done = new HashSet<>();
        Set<String> visiting = new LinkedHashSet<>();
        for (String field : byField.keySet()) {
            List<String> cycle = dfsFormula(field, byField, done, visiting);
            if (cycle != null) {
                return cycle;
            }
        }
        return null;
    }

    private List<String> dfsFormula(String field, Map<String, FormulaRecalculator.FormulaField> byField,
                                    Set<String> done, Set<String> visiting) {
        if (done.contains(field)) {
            return null;
        }
        if (visiting.contains(field)) {
            // 从环的起点截出来，报给用户的就是「a → b → a」这样能直接看懂的路径
            List<String> path = new ArrayList<>(visiting);
            int idx = path.indexOf(field);
            List<String> cycle = new ArrayList<>(path.subList(Math.max(idx, 0), path.size()));
            cycle.add(field);
            return cycle;
        }
        FormulaRecalculator.FormulaField formula = byField.get(field);
        if (formula == null) {
            return null;
        }
        visiting.add(field);
        for (String dep : formula.getDependencies()) {
            if (byField.containsKey(dep)) {
                List<String> cycle = dfsFormula(dep, byField, done, visiting);
                if (cycle != null) {
                    return cycle;
                }
            }
        }
        visiting.remove(field);
        done.add(field);
        return null;
    }

    /**
     * 第三道模式错配告警：反向错配直接报错
     *
     * 用户明确选了「服务端扩展点」模式，却没有对应的 FormDesignExtension 实现 ——
     * 这一定是配置错误，不是某种可接受的中间状态，所以直接拦住发布。
     *
     * 与正向错配（有实现但模式是前端脚本）的处理力度不同是有意的：
     * 那种情况只告警，因为那个 Bean 可能是给别的环境准备的。
     */
    @VisibleForTesting
    void validateExtensionModeConsistency(FormDesignDO formDesign) {
        if (!FormExtensionModeEnum.isServerExtension(formDesign.getExtensionMode())) {
            return;
        }
        if (!extensionRegistry.hasExtension(formDesign.getCode())) {
            throw exception(FORM_DESIGN_EXTENSION_MISSING, formDesign.getCode());
        }
    }

    /**
     * 校验表单里没有本模块不支持的控件
     *
     * 为什么需要这一项：设计器内核提供的控件比本模块能正确落库的控件多。
     * 最典型的是「表格表单」(tableForm) —— 它在内核里和子表控件一样是 array 值的
     * subForm，但本模块的子表绑定只认 {@code group}。改造前它在菜单里可拖、发布也不报错，
     * 用户填完明细提交，接口返回成功，数据却一行都没进库。「分组」(subForm) 是 object 值的
     * 嵌套表单，本模块的字段映射按扁平 field 组织，同样存不进去。
     *
     * <p>现在两处兜住：设计器菜单隐藏这些控件（前端 createDesignerConfig），
     * 本校验作为第二道防线 —— 前端配置漂移、或历史表单里已经存了这类控件时，
     * 发布环节必须明确报错，而不是放它进运行时静默丢数据。
     *
     * @param formFields 表单字段规则 JSON
     */
    @VisibleForTesting
    void validateWidgetsSupported(String formFields) {
        List<String> rules = FormFieldsCodec.read(formFields);
        if (rules.isEmpty()) {
            return;
        }
        for (String rule : rules) {
            Map<String, Object> node = JsonUtils.parseObjectQuietly(rule,
                    new TypeReference<Map<String, Object>>() {});
            if (node != null) {
                validateWidgetSupported(node);
            }
        }
    }

    /**
     * 校验字段标识不重复
     *
     * 为什么需要这一项：本类里按钮 key、页签 key、表名都查了重复，唯独表单字段的 field 没查。
     * 设计器拖控件时会自动生成不重名的 field，所以人工操作碰不到；但脚本生成、接口直传、
     * 导入历史配置都可能产生重复，而字段映射（tableBinding.fieldMapping）是按 field 组织的
     * Map —— 重复意味着后写的覆盖先写的：界面上两个输入框，入库只剩一个值，且全程不报错。
     *
     * <p>子表内部单独成域：子表字段与主表字段同名是合法的（落在不同的物理表上），
     * 所以只在同一层级内查重，不跨子表边界。
     *
     * @param formFields 表单字段规则 JSON
     */
    @VisibleForTesting
    void validateFieldKeysUnique(String formFields) {
        List<String> rules = FormFieldsCodec.read(formFields);
        if (rules.isEmpty()) {
            return;
        }
        Map<String, Integer> counter = new LinkedHashMap<>();
        for (String rule : rules) {
            Map<String, Object> node = JsonUtils.parseObjectQuietly(rule,
                    new TypeReference<Map<String, Object>>() {});
            if (node != null) {
                collectFieldKeys(node, counter);
            }
        }
        for (Map.Entry<String, Integer> entry : counter.entrySet()) {
            if (entry.getValue() > 1) {
                throw exception(FORM_DESIGN_FIELD_KEY_DUPLICATE, entry.getKey(), entry.getValue());
            }
        }
    }

    /**
     * 递归收集同一层级的字段标识
     *
     * 遇到子表就停止下探 —— 它内部是独立的字段域，见 {@link #validateFieldKeysUnique} 的说明。
     * 布局容器（栅格、卡片、页签等）不是字段域，它们的 children 与外层同属一个命名空间，必须继续下探。
     */
    @SuppressWarnings("unchecked")
    private void collectFieldKeys(Map<String, Object> node, Map<String, Integer> counter) {
        if (node.get("field") instanceof String field && !field.isBlank()) {
            counter.merge(field, 1, Integer::sum);
        }
        String type = resolveWidgetType(node);
        if (widgetContractRegistry.getSubTableWidgetType().equals(type)) {
            return;
        }
        Object children = node.get("children");
        if (children instanceof List<?> list) {
            for (Object child : list) {
                if (child instanceof Map<?, ?> childMap) {
                    collectFieldKeys((Map<String, Object>) childMap, counter);
                }
            }
        }
    }

    /**
     * 解析控件的真实身份
     *
     * ⚠️ 不能直接用 `type`：「文字」与「间距」两个纯展示控件落库的 `type` 都是 `div`，
     * 靠 `_fc_drag_tag` 区分 —— 设计器与 script/form-demo/coverage.py 都是按它认身份的。
     * 只看 `type` 的话，这两个控件在契约里永远查不到，会被当成未登记类型误拦。
     *
     * @param node 控件节点
     * @return 契约里用于查找的类型；节点上没有类型信息时返回 null
     */
    private String resolveWidgetType(Map<String, Object> node) {
        if (node.get("_fc_drag_tag") instanceof String dragTag && !dragTag.isBlank()) {
            return dragTag;
        }
        return node.get("type") instanceof String type && !type.isBlank() ? type : null;
    }

    @SuppressWarnings("unchecked")
    private void validateWidgetSupported(Map<String, Object> node) {
        String type = resolveWidgetType(node);
        if (type != null) {
            WidgetContract contract = widgetContractRegistry.get(type);
            if (contract == null) {
                // ⚠️ 未登记的类型一律拒绝。
                //
                // 原先这里是放行的，理由是「契约可能尚未覆盖某个新控件，不该把用户卡在发布环节」。
                // 但代价是：只要 type 写错一个字母（手改 schema、脚本生成、接口直传都可能），
                // 表单照样发布成功，运行时既渲染不出来也存不进库，而且全程不报错 ——
                // 正是本子系统最难反查的那类「配了但没生效」。
                //
                // 新控件没登记进契约，本身就是该修的缺陷（有 WidgetContractRegistryTest 与
                // form-widget-contract-sync 两道一致性测试守着），不该靠放宽发布校验来兜。
                throw exception(FORM_DESIGN_WIDGET_UNKNOWN, type);
            }
            if (!contract.isSupported()) {
                throw exception(FORM_DESIGN_WIDGET_UNSUPPORTED,
                        resolveWidgetLabel(node, contract), suggestAlternative(type));
            }
        }
        Object children = node.get("children");
        if (children instanceof List<?> list) {
            for (Object child : list) {
                if (child instanceof Map<?, ?> childMap) {
                    validateWidgetSupported((Map<String, Object>) childMap);
                }
            }
        }
        if (node.get("props") instanceof Map<?, ?> propsMap
                && ((Map<String, Object>) propsMap).get("rule") instanceof List<?> innerRules) {
            for (Object child : innerRules) {
                if (child instanceof Map<?, ?> childMap) {
                    validateWidgetSupported((Map<String, Object>) childMap);
                }
            }
        }
    }

    /**
     * 校验文件类字段映射到的列放得下一个文件 URL
     *
     * <h3>为什么这条要在发布期拦</h3>
     *
     * 手写签名、图片与附件上传的值是文件服务的访问地址，形如
     * {@code http://host:48080/admin-api/infra/file/4/get/20260910/<64位哈希>.png} ——
     * 算上域名、日期目录与哈希文件名，动辄 130 个字符以上。
     *
     * 映射到 {@code varchar(50)} 的话，发布能过、界面能填、签完名点保存的那一刻
     * 才由数据库报一个截断错误；开了宽松模式的库更糟，它会把 URL 截断后照样存进去 ——
     * 那条记录的签名从此指向一个不存在的文件，而且没有任何人会收到通知。
     *
     * 发布是唯一能把这件事说清楚的时机：此时能拿到列的真实长度，也还没有数据。
     *
     * <h3>门槛为什么是 255</h3>
     *
     * 不是精确算出来的，而是取「字符串列的常规长度」这个约定：varchar(255) 放得下
     * 目前所有存储器产出的 URL，而低于它的列基本都是当年为「短文本」开的，
     * 拿来存 URL 一定是配错了。TEXT / JSON 族不受限制。
     */
    private void validateFileColumnLength(FormDesignDO formDesign, TableBinding binding,
                                          Long dataSourceId) {
        Map<String, String> fileFields = collectFileFields(formDesign.getFormFields());
        if (fileFields.isEmpty()) {
            return;
        }
        checkFileColumnLength(fileFields, dataSourceId, binding.getMainTable(),
                binding.getFieldMapping());
        if (binding.getSideTables() != null) {
            for (SideTableBinding side : binding.getSideTables()) {
                if (side != null) {
                    checkFileColumnLength(fileFields, dataSourceId, side.getTable(),
                            side.getFieldMapping());
                }
            }
        }
        if (binding.getSubTables() != null) {
            for (SubTableBinding sub : binding.getSubTables()) {
                if (sub != null) {
                    checkFileColumnLength(fileFields, dataSourceId, sub.getTable(),
                            sub.getFieldMapping());
                }
            }
        }
    }

    /**
     * 逐条核对一张表的字段映射
     *
     * @param fileFields 文件类字段 -> 展示名
     */
    private void checkFileColumnLength(Map<String, String> fileFields, Long dataSourceId,
                                       String tableName, Map<String, FieldMapping> fieldMapping) {
        if (StrUtil.isBlank(tableName) || CollUtil.isEmpty(fieldMapping)) {
            return;
        }
        // 映射里一个文件字段都没有时不去查表结构 —— 绝大多数表单属于这一类
        boolean anyFileField = fieldMapping.keySet().stream().anyMatch(fileFields::containsKey);
        if (!anyFileField) {
            return;
        }
        TableInfo tableInfo = metadataCache.get(dataSourceId, tableName);
        if (tableInfo == null || tableInfo.getFields() == null) {
            // 表不存在由别的校验负责报错，这里不重复
            return;
        }
        Map<String, TableField> columns = buildColumnMap(tableInfo.getFields());
        for (Map.Entry<String, FieldMapping> entry : fieldMapping.entrySet()) {
            String label = fileFields.get(entry.getKey());
            FieldMapping mapping = entry.getValue();
            if (label == null || mapping == null || StrUtil.isBlank(mapping.getColumn())) {
                continue;
            }
            TableField column = columns.get(mapping.getColumn().toLowerCase());
            if (column == null || column.getMetaInfo() == null) {
                continue;
            }
            // 只管字符串族。TEXT / JSON 没有长度上限，其它族的类型不兼容由
            // acceptsColumnType 那条校验负责
            JdbcTypeFamily family = column.getMetaInfo().getJdbcType() == null ? null
                    : widgetContractRegistry.resolveJdbcTypeFamily(
                            column.getMetaInfo().getJdbcType().name());
            if (family != JdbcTypeFamily.STRING) {
                continue;
            }
            int length = column.getMetaInfo().getLength();
            if (length > 0 && length < MIN_FILE_URL_COLUMN_LENGTH) {
                throw exception(FORM_DESIGN_FILE_COLUMN_TOO_SHORT, label, tableName,
                        mapping.getColumn(), length, MIN_FILE_URL_COLUMN_LENGTH);
            }
        }
    }

    /**
     * 收集表单里所有值为文件的字段
     *
     * 判据来自控件契约的 {@code valueType = file}，不在这里列控件名 ——
     * 新增一个文件类控件时忘了改这里，表现会是那个控件照旧能映射到 varchar(50)。
     *
     * @return 字段名 -> 展示名（优先用标题）
     */
    private Map<String, String> collectFileFields(String formFields) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String rule : FormFieldsCodec.read(formFields)) {
            Map<String, Object> node = JsonUtils.parseObjectQuietly(rule,
                    new TypeReference<Map<String, Object>>() {});
            if (node != null) {
                collectFileFields(node, result);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private void collectFileFields(Map<String, Object> node, Map<String, String> result) {
        String type = node.get("type") instanceof String t ? t : null;
        if (widgetContractRegistry.isFileWidget(type)
                && node.get("field") instanceof String field && StrUtil.isNotBlank(field)) {
            result.put(field, node.get("title") instanceof String title && StrUtil.isNotBlank(title)
                    ? title : field);
        }
        if (node.get("children") instanceof List<?> list) {
            for (Object child : list) {
                if (child instanceof Map<?, ?> childMap) {
                    collectFileFields((Map<String, Object>) childMap, result);
                }
            }
        }
        // 子表内部的上传列同样要查：它映射的是明细表的列，一样能被开得太短
        if (node.get("props") instanceof Map<?, ?> propsMap
                && ((Map<String, Object>) propsMap).get("rule") instanceof List<?> innerRules) {
            for (Object inner : innerRules) {
                if (inner instanceof Map<?, ?> innerMap) {
                    collectFileFields((Map<String, Object>) innerMap, result);
                }
            }
        }
    }

    /**
     * 校验乐观锁配置
     *
     * 三条都是「开了但不生效」的来源，而不生效的表现完全一样：并发覆盖照旧发生，
     * 双方都看到保存成功。发布是唯一能把它们拦住的时机。
     *
     * <ol>
     *   <li>列不存在 → 更新时的版本条件无从附加</li>
     *   <li>列不是整数 → 它靠「列 = 列 + 1」自增，非整数列上结果不可预期</li>
     *   <li>列被表单字段映射 → 表单值和后端自增互相覆盖</li>
     * </ol>
     */
    private void validateOptimisticLock(TableBinding binding, Map<String, TableField> mainColumns) {
        OptimisticLockConfig lock = binding.getOptimisticLock();
        if (lock == null || !Boolean.TRUE.equals(lock.getEnabled())) {
            return;
        }
        // 勾了开关却没填列名：等于没开，而界面上开关是亮的
        if (StrUtil.isBlank(lock.getColumn())) {
            throw exception(FORM_DESIGN_VERSION_COLUMN_NOT_EXISTS, binding.getMainTable(), "");
        }
        String column = lock.getColumn().trim();
        TableField field = mainColumns.get(column);
        if (field == null) {
            throw exception(FORM_DESIGN_VERSION_COLUMN_NOT_EXISTS, binding.getMainTable(), column);
        }
        // 类型必须是整数族
        String jdbcType = field.getMetaInfo() != null && field.getMetaInfo().getJdbcType() != null
                ? field.getMetaInfo().getJdbcType().name() : null;
        JdbcTypeFamily family = widgetContractRegistry.resolveJdbcTypeFamily(jdbcType);
        if (family != JdbcTypeFamily.INTEGER && family != JdbcTypeFamily.LONG) {
            throw exception(FORM_DESIGN_VERSION_COLUMN_NOT_INT,
                    binding.getMainTable(), column, jdbcType != null ? jdbcType : "未知");
        }
        // 不允许被表单字段映射
        if (binding.getFieldMapping() != null) {
            for (Map.Entry<String, FieldMapping> entry : binding.getFieldMapping().entrySet()) {
                FieldMapping mapping = entry.getValue();
                if (mapping != null && column.equalsIgnoreCase(StrUtil.trim(mapping.getColumn()))) {
                    throw exception(FORM_DESIGN_VERSION_COLUMN_MAPPED, column, entry.getKey());
                }
            }
        }
    }

    /**
     * 校验自定义按钮
     *
     * 四条，每一条对应一种「发布能过、运行时出问题」的配置：
     *
     * <ol>
     *   <li>标识重复 → 前端按它做列表 key，重复会让渲染错位（删掉一个，消失的是另一个）</li>
     *   <li>位置 / 动作类型不认识 → 按钮画不出来，或者点了没反应，都不报错</li>
     *   <li>跳转地址用了 javascript: / data: → 用户点击时执行任意脚本。
     *       与超链接控件同一套白名单（{@link LinkUrlGuard}）</li>
     *   <li>跳转地址为空 → 点了没反应</li>
     * </ol>
     *
     * 「给工具栏按钮配了可见条件」不拦，只是那条件不生效（工具栏没有当前记录）——
     * 拦下来会让用户以为配错了什么，而实际上只是那一项对这个位置无意义。
     */
    private void validateCustomButtons(FormDesignDO formDesign) {
        List<CustomButton> buttons = parseCustomButtons(formDesign.getCustomButtons());
        if (buttons.isEmpty()) {
            return;
        }
        Set<String> keys = new HashSet<>();
        for (CustomButton button : buttons) {
            String label = StrUtil.isNotBlank(button.getLabel()) ? button.getLabel() : button.getKey();
            if (StrUtil.isBlank(button.getKey())) {
                throw exception(FORM_DESIGN_BUTTON_KEY_REQUIRED, label);
            }
            if (!keys.add(button.getKey().trim())) {
                throw exception(FORM_DESIGN_BUTTON_KEY_DUPLICATE, button.getKey());
            }
            if (CustomButton.Position.parse(button.getPosition()) == null) {
                throw exception(FORM_DESIGN_BUTTON_POSITION_INVALID, label, button.getPosition());
            }
            CustomButton.ButtonAction action = button.getAction();
            CustomButton.ButtonAction.Type type =
                    action == null ? null : CustomButton.ButtonAction.Type.parse(action.getType());
            if (type == null) {
                throw exception(FORM_DESIGN_BUTTON_ACTION_INVALID, label,
                        action == null ? "" : String.valueOf(action.getType()));
            }
            if (type == CustomButton.ButtonAction.Type.INVOKE) {
                validateInvokeAction(formDesign, label, action);
            }
            if (type == CustomButton.ButtonAction.Type.NAVIGATE) {
                if (StrUtil.isBlank(action.getUrl())) {
                    throw exception(FORM_DESIGN_BUTTON_URL_REQUIRED, label);
                }
                // 与超链接控件同一套判据：地址是表单设计者配的、填表人点的，
                // 两者往往不是同一个信任级别
                if (!LinkUrlGuard.isSafe(action.getUrl())) {
                    // 报错参数与超链接控件那条一致：第二个位置放地址原文，
                    // 用户据此知道是哪一段配错了
                    throw exception(FORM_DESIGN_LINK_URL_UNSAFE, label, action.getUrl());
                }
            }
        }
    }

    /**
     * 校验「调数据接口」动作
     *
     * 三条，每一条都是「按钮点下去才发现」的配置：
     *
     * <ol>
     *   <li>没选接口 → 点了没反应</li>
     *   <li>接口不存在或已停用 → 点了报一句用户看不懂的错，而他不知道是接口被删了</li>
     *   <li>入参映射不完整、或引用了不存在的字段 → <b>静默传空</b>。
     *       这一条最值得拦：接口收到一个空的单号照样会执行，
     *       作废单据这类动作作用在了错误的对象上，而界面上一切正常</li>
     * </ol>
     *
     * ⚠️ 这里<b>不校验</b>「接口是不是写型」—— 允许写型正是这个动作存在的理由。
     * 它的权限边界靠「接口编码只能从已发布配置里读」收窄，见
     * {@code FormDesignRuntimeService#invokeButtonInterface}。
     */
    private void validateInvokeAction(FormDesignDO formDesign, String label,
                                      CustomButton.ButtonAction action) {
        if (StrUtil.isBlank(action.getInterfaceCode())) {
            throw exception(FORM_DESIGN_BUTTON_INTERFACE_REQUIRED, label);
        }
        DataInterfaceDO dataInterface = dataInterfaceMapper.selectByCode(action.getInterfaceCode());
        if (dataInterface == null
                || (dataInterface.getStatus() != null && dataInterface.getStatus() != 0)) {
            throw exception(FORM_DESIGN_BUTTON_INTERFACE_NOT_EXISTS, label, action.getInterfaceCode());
        }
        if (action.getParams() == null || action.getParams().isEmpty()) {
            return;
        }
        Set<String> knownFields = logicGuard.parseFieldMetas(formDesign.getFormFields()).keySet();
        for (CustomButton.ButtonAction.ParamMapping param : action.getParams()) {
            if (param == null || StrUtil.isBlank(param.getName()) || StrUtil.isBlank(param.getField())) {
                throw exception(FORM_DESIGN_BUTTON_PARAM_INVALID, label);
            }
            // 主键不是表单字段，但它是最常用的入参（作废单据要的就是它），所以单独放行
            if (Objects.equals(param.getField(), mainPkOf(formDesign))) {
                continue;
            }
            if (!knownFields.contains(param.getField())) {
                throw exception(FORM_DESIGN_BUTTON_PARAM_FIELD_NOT_EXISTS, label, param.getField());
            }
        }
    }

    /**
     * 取主键字段名。解析不出来时返回 null —— 表绑定本身的问题由别的校验负责
     */
    private String mainPkOf(FormDesignDO formDesign) {
        TableBinding binding = parseTableBinding(formDesign.getTableBinding());
        return binding == null ? null : binding.getMainPk();
    }

    /**
     * 解析自定义按钮
     *
     * 解析失败返回空列表 + 记日志：一份坏掉的按钮配置不该让整张表单发布不了，
     * 但也不能静默 —— 配的人以为按钮会出现。
     */
    private List<CustomButton> parseCustomButtons(String json) {
        if (StrUtil.isBlank(json)) {
            return List.of();
        }
        List<CustomButton> buttons = JsonUtils.parseObjectQuietly(json,
                new TypeReference<List<CustomButton>>() {});
        if (buttons == null) {
            log.warn("[parseCustomButtons][自定义按钮 JSON 解析失败，本次发布跳过按钮校验]");
            return List.of();
        }
        return buttons;
    }

    /**
     * 校验详情页页签
     *
     * 三条，每一条都是「配了但页签是空的 / 行为错乱」：
     *
     * <ol>
     *   <li>标识重复 → 前端按它做 el-tabs 的 name，重复会让切换错乱
     *       （点第二个页签，显示的是第三个的内容）</li>
     *   <li>类型不认识 → 页签画出来了但里面什么都没有</li>
     *   <li>配了变更日志页签却没开变更日志 → 页签永远是空的。
     *       这一条最值得拦：两个开关分处两个步骤，很容易只开一个</li>
     * </ol>
     */
    private void validateDetailTabs(FormDesignDO formDesign, TableBinding binding) {
        List<DetailTab> tabs = parseDetailTabs(formDesign.getDetailTabs());
        if (tabs.isEmpty()) {
            return;
        }
        Set<String> keys = new HashSet<>();
        for (DetailTab tab : tabs) {
            String label = StrUtil.isNotBlank(tab.getLabel()) ? tab.getLabel() : tab.getKey();
            if (StrUtil.isBlank(tab.getKey())) {
                throw exception(FORM_DESIGN_TAB_KEY_REQUIRED, label);
            }
            if (!keys.add(tab.getKey().trim())) {
                throw exception(FORM_DESIGN_TAB_KEY_DUPLICATE, tab.getKey());
            }
            DetailTab.Type type = DetailTab.Type.parse(tab.getType());
            if (type == null) {
                throw exception(FORM_DESIGN_TAB_TYPE_INVALID, label, String.valueOf(tab.getType()));
            }
            if (type == DetailTab.Type.CHANGE_LOG) {
                ChangeLogConfig changeLog = binding.getChangeLog();
                if (changeLog == null || !changeLog.isEnabled()) {
                    throw exception(FORM_DESIGN_TAB_CHANGE_LOG_DISABLED, label);
                }
            }
            if (type == DetailTab.Type.RELATION_LIST) {
                validateRelationListTab(label, tab);
            }
        }
    }

    /**
     * 校验关联数据列表页签
     *
     * 三条：
     *
     * <ol>
     *   <li>没配齐目标表单 / 关联字段 → 页签里一片空白</li>
     *   <li>目标表单不存在或没发布 → 运行时调的是它的分页接口，没发布就调不通</li>
     *   <li><b>关联字段不在目标表单上</b> → 这一条最要紧：分页接口对认不出的字段是
     *       <b>静默忽略</b>（那是它刻意的容错，防止历史查询参数把列表打不开），
     *       于是这个页签会列出目标表单的<b>全部</b>数据 ——
     *       看起来像是「关联查询把别人家的单据也带出来了」，而且不报错</li>
     * </ol>
     *
     * ⚠️ 不校验「目标表单是不是表单+列表类型」：纯表单也能被分页接口查，
     * 而且「一条主记录挂着一批明细单」的场景里，那批明细单常常没有自己的列表页。
     */
    private void validateRelationListTab(String label, DetailTab tab) {
        if (StrUtil.isBlank(tab.getTargetFormCode()) || StrUtil.isBlank(tab.getTargetField())) {
            throw exception(FORM_DESIGN_TAB_RELATION_INCOMPLETE, label);
        }
        FormDesignDO target = formDesignMapper.selectByCode(tab.getTargetFormCode());
        if (target == null
                || Objects.equals(target.getStatus(), FormDesignStatusEnum.DRAFT.getStatus())) {
            throw exception(FORM_DESIGN_TAB_RELATION_FORM_INVALID, label, tab.getTargetFormCode());
        }
        Set<String> targetFields = logicGuard.parseFieldMetas(target.getFormFields()).keySet();
        // 目标表单的主键不是它的表单字段，但「用主键关联」是合理的（父子同表的树形结构），
        // 所以单独放行 —— 与自定义按钮入参映射那边的处理一致
        TableBinding targetBinding = parseTableBinding(target.getTableBinding());
        String targetPk = targetBinding == null ? null : targetBinding.getMainPk();
        if (!targetFields.contains(tab.getTargetField())
                && !Objects.equals(tab.getTargetField(), targetPk)) {
            throw exception(FORM_DESIGN_TAB_RELATION_FIELD_NOT_EXISTS, label,
                    tab.getTargetField(), tab.getTargetFormCode());
        }
    }

    /**
     * 解析详情页页签
     *
     * 解析失败返回空列表 + 记日志，理由同自定义按钮。
     */
    private List<DetailTab> parseDetailTabs(String json) {
        if (StrUtil.isBlank(json)) {
            return List.of();
        }
        List<DetailTab> tabs = JsonUtils.parseObjectQuietly(json,
                new TypeReference<List<DetailTab>>() {});
        if (tabs == null) {
            log.warn("[parseDetailTabs][详情页页签 JSON 解析失败，本次发布跳过页签校验]");
            return List.of();
        }
        return tabs;
    }

    /**
     * 校验打印模板的占位符
     *
     * 只有一条：{@code {字段}} 引用的字段必须能在打印时取到值。
     *
     * 这一条值得拦，因为它的失败方式是【没有失败】—— 字段 ID 改过、或者占位符打错一个字，
     * 打印出来的单据上就是一片空白，不报错、也不提示。设计者拿到的反馈是
     * 「这一格印不出来」，而他会先去怀疑模板的样式。
     *
     * <h3>可用的键就是运行时读回来的那些</h3>
     *
     * 插值的数据来自运行时的取详情接口，它返回的键由
     * {@code SqlBuilderSupport#buildSelectColumns} 决定：主键、映射的主表字段、
     * 审计列、乐观锁版本列、关联表的展示列别名。所以允许集合按那一份来拼，
     * 而不是只拿表单字段 —— 只拿表单字段的话，{@code {id}}、{@code {create_time}}
     * 这类完全正常的用法会被拦下来，那就是一次「配对了却发布不了」。
     *
     * <h3>子表明细字段不在允许集合里</h3>
     *
     * {@code parseFieldMetas} 只递归布局容器、不递归子表内部规则，所以明细列天然不在
     * 集合里，会被拦住。这是想要的结果：明细是多行数据，插进模板的一个位置只能得到
     * 一串 {@code [object Object]}。⚠️ 「打印模板里带明细表格」这件事本次没做，
     * 见交接文档第十七节。
     */
    @VisibleForTesting
    void validatePrintTemplate(FormDesignDO formDesign, TableBinding binding) {
        // ⚠️ 循环段必须【先】校验，两个原因都踩到过：
        //
        // 1. 纯循环段的模板（整张模板只有一个明细表格）主表字段集是空的，
        //    落到下面那句提前 return 上，循环段一条都校验不到 ——
        //    引用了不存在的子表也照样发布成功
        // 2. 没闭合的 `{#milestones}` 不会被 collectFields 挖掉，会被当成一个
        //    名叫 `#milestones` 的主表字段报「字段不存在」。拦是拦住了，
        //    但用户拿到的提示是去找一个 `#milestones` 字段 —— 比不拦更费解
        validatePrintLoops(formDesign, binding);

        Set<String> referenced = PrintTemplate.collectFields(formDesign.getPrintTemplate());
        if (referenced.isEmpty()) {
            return;
        }
        Set<String> available = new HashSet<>(
                logicGuard.parseFieldMetas(formDesign.getFormFields()).keySet());
        if (StrUtil.isNotBlank(binding.getMainPk())) {
            available.add(binding.getMainPk());
        }
        AutoFillConfig autoFill = binding.getAutoFill();
        if (autoFill != null) {
            available.addAll(autoFill.getAllColumns());
        }
        OptimisticLockConfig lock = binding.getOptimisticLock();
        if (lock != null && lock.isUsable()) {
            available.add(lock.getColumn());
        }
        if (binding.getJoinTables() != null) {
            for (JoinTableBinding join : binding.getJoinTables()) {
                if (join == null || join.getSelectColumns() == null) {
                    continue;
                }
                for (JoinTableBinding.SelectColumn column : join.getSelectColumns()) {
                    if (column == null || StrUtil.isBlank(column.getColumn())) {
                        continue;
                    }
                    // 有 AS 别名时前端按别名取值，没有时按列名 —— 与 buildSelectColumns 一致
                    available.add(StrUtil.blankToDefault(column.getAs(), column.getColumn()));
                }
            }
        }
        // 副表列由 mergeSideTableData 单独查回来并 putAll 进同一个 Map，键就是
        // fieldMapping 的键（yd_表名_yd_列名）。这些键绝大多数同时也是表单字段、
        // 已经在上面进来了，但配置里允许只映射不放控件 —— 漏掉这一段的话，
        // 那种字段会被拦成「字段不存在」，而它其实是印得出来的
        if (binding.getSideTables() != null) {
            for (SideTableBinding side : binding.getSideTables()) {
                if (side != null && side.getFieldMapping() != null) {
                    available.addAll(side.getFieldMapping().keySet());
                }
            }
        }
        for (String field : referenced) {
            if (!available.contains(field)) {
                throw exception(FORM_DESIGN_PRINT_FIELD_NOT_EXISTS, field);
            }
        }
    }

    /**
     * 校验打印模板里的明细行
     *
     * 三条，都属于「不拦就静默出错」那一类：
     *
     * <ol>
     *   <li>标记没放在表格的一行里 —— 什么都不会发生，明细印不出来</li>
     *   <li>引用的子表不存在 —— 那一行印不出任何明细</li>
     *   <li>行内引用的列不是该子表的字段 —— 那一格留一片空白</li>
     * </ol>
     *
     * 三种都不报错，设计者拿到的反馈是「明细印不出来」，而他会先去怀疑数据。
     */
    private void validatePrintLoops(FormDesignDO formDesign, TableBinding binding) {
        String template = formDesign.getPrintTemplate();
        Set<String> stray = PrintTemplate.collectStrayMarks(template);
        if (!stray.isEmpty()) {
            throw exception(FORM_DESIGN_PRINT_LOOP_STRAY, stray.iterator().next());
        }
        Map<String, Set<String>> loops = PrintTemplate.collectLoopFields(template);
        if (loops.isEmpty()) {
            return;
        }
        // 子表字段 -> 它映射了哪些字段。没配子表时是空 Map，于是任何循环段都会被拦下
        Map<String, Set<String>> subFields = new LinkedHashMap<>();
        if (binding.getSubTables() != null) {
            for (SubTableBinding sub : binding.getSubTables()) {
                if (sub == null || StrUtil.isBlank(sub.getField())) {
                    continue;
                }
                subFields.put(sub.getField(),
                        sub.getFieldMapping() == null ? Set.of() : sub.getFieldMapping().keySet());
            }
        }
        loops.forEach((subField, columns) -> {
            Set<String> allowed = subFields.get(subField);
            if (allowed == null) {
                throw exception(FORM_DESIGN_PRINT_LOOP_SUB_NOT_EXISTS, subField);
            }
            for (String column : columns) {
                if (!allowed.contains(column)) {
                    throw exception(FORM_DESIGN_PRINT_LOOP_FIELD_NOT_EXISTS, subField, column);
                }
            }
        });
    }

    /**
     * 取控件在报错信息里的展示名
     *
     * 优先用用户在设计器里填的标题 —— 一个表单里可能有多个同类型控件，
     * 只报类型名（「表格表单」）用户不知道该删哪一个。
     */
    private String resolveWidgetLabel(Map<String, Object> node, WidgetContract contract) {
        if (node.get("title") instanceof String title && StrUtil.isNotBlank(title)) {
            return title;
        }
        return StrUtil.isNotBlank(contract.getLabel()) ? contract.getLabel() : contract.getType();
    }

    /**
     * 给不支持的控件推荐替代品
     *
     * 报错只说「不支持」用户会卡住，得告诉他该用什么。
     */
    private String suggestAlternative(String type) {
        WidgetContract subTable = widgetContractRegistry.get(subTableWidgetType());
        String subTableLabel = subTable != null && StrUtil.isNotBlank(subTable.getLabel())
                ? subTable.getLabel() : subTableWidgetType();
        return switch (type) {
            // 这两个都是想做明细录入，正解是子表控件
            case "tableForm", "subForm" -> subTableLabel;
            case "upload" -> "文件上传 / 单图上传 / 多图上传";
            case "ApiSelect" -> "远程选择器";
            case "UserSelect", "DeptSelect" -> "组织选择器";
            case "fcEditor" -> "富文本";
            // 内核的 signaturePad 值是 canvas 直出的 base64，自建 Signature 转存文件服务后只存 URL
            case "signaturePad" -> "手写签名";
            default -> "其它受支持的控件";
        };
    }

    /**
     * 收集表单里所有子表控件的 field
     *
     * 子表控件的内部规则挂在 {@code props.rule} 下（类型见控件契约）。
     * 控件本身可能被放进栅格、标签页等布局容器，所以要递归找。
     *
     * <p>解析不出任何规则时返回空集合，调用方据此跳过「控件是否存在」这一项校验 ——
     * 宁可放过，也不能因为解析失败就把正常的表单拦在发布之外。
     */
    private Set<String> collectSubTableWidgetFields(String formFields) {
        List<String> rules = FormFieldsCodec.read(formFields);
        if (rules.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> fields = new HashSet<>();
        for (String rule : rules) {
            Map<String, Object> node = JsonUtils.parseObjectQuietly(rule,
                    new TypeReference<Map<String, Object>>() {});
            if (node != null) {
                collectSubTableWidgetFields(node, fields);
            }
        }
        return fields;
    }

    @SuppressWarnings("unchecked")
    private void collectSubTableWidgetFields(Map<String, Object> node, Set<String> fields) {
        if (subTableWidgetType().equals(node.get("type"))
                && node.get("field") instanceof String field && StrUtil.isNotBlank(field)) {
            fields.add(field);
        }
        Object children = node.get("children");
        if (children instanceof List<?> list) {
            for (Object child : list) {
                if (child instanceof Map<?, ?> childMap) {
                    collectSubTableWidgetFields((Map<String, Object>) childMap, fields);
                }
            }
        }
        // 子表控件可以嵌套子表控件，内部规则同样要扫
        if (node.get("props") instanceof Map<?, ?> propsMap
                && ((Map<String, Object>) propsMap).get("rule") instanceof List<?> innerRules) {
            for (Object child : innerRules) {
                if (child instanceof Map<?, ?> childMap) {
                    collectSubTableWidgetFields((Map<String, Object>) childMap, fields);
                }
            }
        }
    }

    /**
     * 校验表名不跨分类重复，且主表不作为【写入型】从表
     *
     * 同一张表既当副表（1:1）又当子表（1:N），写入语义直接冲突 ——
     * 副表写一行、子表写多行，运行时行为不可预期。
     *
     * <h3>自关联：只读的关联表放开，写入型的仍然禁止</h3>
     *
     * 部门树、物料 BOM 这类「同一张表里父子相认」的建模，需要的是
     * <b>把主表 JOIN 自己一次，显示上级的名称</b> —— 那是只读的 joinTable，
     * SQL 上靠别名区分两侧，机制本来就支持（{@code buildJoinClause} 会拼 AS）。
     *
     * 副表 / 子表的自关联仍然拒绝：它们<b>要往那张表写数据</b>，而那张表就是主表 ——
     * 一次提交会对同一张表发两条互不知情的写入，主记录刚写的值可能被副表那条覆盖。
     * 这不是「暂不支持」，是语义上就不该允许。
     */
    private void validateTableCategories(TableBinding binding) {
        String mainTable = binding.getMainTable();
        Set<String> seen = new HashSet<>();

        // 写入型从表（副表 + 子表）：不得与主表同名
        for (String table : collectWritableSlaveTableNames(binding)) {
            if (Objects.equals(table, mainTable)) {
                throw exception(FORM_DESIGN_SELF_REFERENCE_DENY, mainTable);
            }
            if (!seen.add(table)) {
                throw exception(FORM_DESIGN_TABLE_DUPLICATE_CATEGORY, table);
            }
        }

        // 只读关联表：允许自关联，但要按别名去重 —— 同一张表 JOIN 两次
        // （上级 + 上上级）是合理的，用表名去重会把第二次误判成重复
        for (JoinTableBinding join : nullSafe(binding.getJoinTables())) {
            if (join == null || StrUtil.isBlank(join.getTable())) {
                continue;
            }
            boolean selfJoin = Objects.equals(join.getTable(), mainTable);
            if (selfJoin) {
                validateSelfJoin(mainTable, join);
            }
            String key = StrUtil.isNotBlank(join.getAlias())
                    ? "alias:" + join.getAlias() : join.getTable();
            if (!seen.add(key)) {
                throw exception(FORM_DESIGN_TABLE_DUPLICATE_CATEGORY, join.getTable());
            }
        }
    }

    /**
     * 校验一条自关联
     *
     * 三条，缺任何一条都会让 SQL 要么报错、要么<b>静默算错</b>：
     *
     * <ol>
     *   <li><b>必须有别名</b>。没有的话拼出来是 {@code FROM t LEFT JOIN t}，
     *       数据库直接报 Not unique table/alias</li>
     *   <li><b>别名不能等于表名</b>。等于没起</li>
     *   <li><b>ON 至少有一侧用到这个别名</b>。⚠️ 这一条最要紧：
     *       {@code qualifyOnSide} 是先按「是不是已声明的表名」解析的，
     *       自关联时表名就是主表名 —— 两侧都写表名的话会拼成
     *       「主表.父ID = 主表.ID」，SQL 跑得通，结果却是错的
     *       （要么一行都关联不上，要么每行关联到自己），而且不报错</li>
     * </ol>
     */
    private void validateSelfJoin(String mainTable, JoinTableBinding join) {
        String alias = join.getAlias();
        if (StrUtil.isBlank(alias)) {
            throw exception(FORM_DESIGN_SELF_JOIN_ALIAS_REQUIRED, mainTable);
        }
        if (Objects.equals(alias, mainTable)) {
            throw exception(FORM_DESIGN_SELF_JOIN_ALIAS_CONFLICT, alias);
        }
        String prefix = alias + ".";
        boolean referenced = nullSafe(join.getOn()).stream()
                .filter(Objects::nonNull)
                .anyMatch(on -> StrUtil.startWith(on.getLeft(), prefix)
                        || StrUtil.startWith(on.getRight(), prefix));
        if (!referenced) {
            throw exception(FORM_DESIGN_SELF_JOIN_ON_NOT_ALIASED, mainTable, alias, alias);
        }
    }

    private <T> List<T> nullSafe(List<T> list) {
        return list == null ? List.of() : list;
    }

    /**
     * 收集【写入型】从表的表名（副表 + 子表），保持声明顺序
     *
     * 与 {@link #collectSlaveTableNames} 的区别是不含只读的关联表 ——
     * 自关联对只读关联表是允许的
     */
    private List<String> collectWritableSlaveTableNames(TableBinding binding) {
        List<String> names = new ArrayList<>();
        for (SideTableBinding side : nullSafe(binding.getSideTables())) {
            if (side != null && StrUtil.isNotBlank(side.getTable())) {
                names.add(side.getTable());
            }
        }
        for (SubTableBinding sub : nullSafe(binding.getSubTables())) {
            if (sub != null && StrUtil.isNotBlank(sub.getTable())) {
                names.add(sub.getTable());
            }
        }
        return names;
    }

    /**
     * 清掉本次发布会读到的全部表的元数据缓存
     *
     * 主表 + 三类从表（副表 / 子表 / 关联表）。只清这些而不是 invalidateAll：
     * 清空是全局的，会让正在跑的其它表单的运行时请求一起回源 ——
     * 一次发布不该给别人制造一批 50–80ms 的抖动。
     */
    private void invalidateMetadataCache(Long dataSourceId, TableBinding binding) {
        metadataCache.invalidate(dataSourceId, binding.getMainTable());
        for (String table : collectSlaveTableNames(binding)) {
            metadataCache.invalidate(dataSourceId, table);
        }
    }

    /**
     * 收集三类从表的表名，保持声明顺序
     */
    private List<String> collectSlaveTableNames(TableBinding binding) {
        List<String> names = new ArrayList<>();
        if (binding.getSideTables() != null) {
            for (SideTableBinding side : binding.getSideTables()) {
                if (side != null && StrUtil.isNotBlank(side.getTable())) {
                    names.add(side.getTable());
                }
            }
        }
        if (binding.getSubTables() != null) {
            for (SubTableBinding sub : binding.getSubTables()) {
                if (sub != null && StrUtil.isNotBlank(sub.getTable())) {
                    names.add(sub.getTable());
                }
            }
        }
        if (binding.getJoinTables() != null) {
            for (JoinTableBinding join : binding.getJoinTables()) {
                if (join != null && StrUtil.isNotBlank(join.getTable())) {
                    names.add(join.getTable());
                }
            }
        }
        return names;
    }

    /**
     * 校验副表（1:1，参与写入）
     *
     * 与子表校验的区别：副表必须有外键（子表的外键在现有实现里是可选的），
     * 因为副表的所有读写都按外键定位，缺了它运行时找不到数据。
     */
    private void validateSideTable(Long dataSourceId, TableBinding binding,
                                   SideTableBinding sideTable,
                                   Map<String, TableField> mainColumns) {
        if (sideTable == null || StrUtil.isBlank(sideTable.getTable())) {
            return;
        }
        String tableName = sideTable.getTable();

        // 副表参与写入，不能是框架表
        if (!SystemTablePolicy.isAllowed(tableName, TableOperation.WRITE)) {
            throw exception(FORM_DESIGN_FRAMEWORK_TABLE_DENY, tableName);
        }

        TableInfo sideTableInfo = metadataCache.get(dataSourceId, tableName);
        if (sideTableInfo == null || sideTableInfo.getFields() == null) {
            throw exception(FORM_DESIGN_TABLE_NOT_EXISTS, tableName);
        }
        Map<String, TableField> sideColumns = buildColumnMap(sideTableInfo.getFields());

        // 外键是副表与主表的唯一纽带，必填
        if (StrUtil.isBlank(sideTable.getForeignKey())) {
            throw exception(FORM_DESIGN_FK_REQUIRED, tableName);
        }
        if (!sideColumns.containsKey(sideTable.getForeignKey())) {
            throw exception(FORM_DESIGN_COLUMN_NOT_EXISTS, tableName, sideTable.getForeignKey());
        }

        // 关联到主表的列必须存在于主表
        String relationField = StrUtil.isNotBlank(sideTable.getRelationField())
                ? sideTable.getRelationField() : binding.getMainPk();
        if (StrUtil.isNotBlank(relationField) && !mainColumns.containsKey(relationField)) {
            throw exception(FORM_DESIGN_COLUMN_NOT_EXISTS, binding.getMainTable(), relationField);
        }

        // 副表主键策略与列类型要匹配。副表 INSERT 会显式写入主键（非自增策略时），
        // 类型装不下同样是运行时才暴露的错
        String sidePk = StrUtil.isNotBlank(sideTable.getPk()) ? sideTable.getPk() : "id";
        validatePkTypeFor(sideTable.getPkStrategy(), tableName, sidePk, sideColumns);

        // 字段映射的列必须存在于副表
        // 理由同子表那处：返回值必须消费掉
        throwIfAny(validateFieldMapping(sideTable.getFieldMapping(), tableName, sideColumns));

        // 外键类型必须能容纳主表主键值
        validateForeignKeyType(dataSourceId, binding, tableName, sideTable.getForeignKey(), mainColumns);
    }

    /**
     * 校验只读关联表
     *
     * 关联表只读，所以允许是框架表 —— 「显示业务员姓名」正需要 JOIN system_users。
     * 这与主表/副表/子表的框架表禁令不冲突：禁令是防止绕过业务校验写系统表。
     */
    private void validateJoinTable(Long dataSourceId, JoinTableBinding joinTable) {
        if (joinTable == null || StrUtil.isBlank(joinTable.getTable())) {
            return;
        }
        TableInfo joinTableInfo = metadataCache.get(dataSourceId, joinTable.getTable());
        if (joinTableInfo == null || joinTableInfo.getFields() == null) {
            throw exception(FORM_DESIGN_TABLE_NOT_EXISTS, joinTable.getTable());
        }
        Map<String, TableField> joinColumns = buildColumnMap(joinTableInfo.getFields());

        // SELECT 出来的列必须存在
        if (joinTable.getSelectColumns() != null) {
            for (JoinTableBinding.SelectColumn sc : joinTable.getSelectColumns()) {
                if (sc == null || StrUtil.isBlank(sc.getColumn())) {
                    continue;
                }
                if (!joinColumns.containsKey(sc.getColumn())) {
                    throw exception(FORM_DESIGN_COLUMN_NOT_EXISTS, joinTable.getTable(), sc.getColumn());
                }
            }
        }
    }

    /**
     * 校验关联表展示列的别名不与别处重名
     *
     * <h3>⚠️ 这是自关联放开之后必然会撞上的一条</h3>
     *
     * 展示列的别名默认<b>等于列名</b>（设计器就是这么生成的）。自关联时关联的是同一张表，
     * 于是「显示上级部门的 name」会拼出 {@code parent.name AS name}，
     * 而主表自己的 name 也在 SELECT 里 —— <b>两列同名</b>。
     *
     * {@code queryForList} 返回的 Map 键就是列名，同名列只会留下一个：
     * 表现是「上级名称那一列显示的是本级自己的名字」，SQL 不报错、页面也不报错。
     *
     * 这一条对普通关联表同样成立（关联表有 name / remark 这类常见列时），
     * 所以不限定在自关联上校验。
     */
    private void validateJoinSelectAliases(TableBinding binding) {
        // 已被占用的键：主表与副表映射到的字段名（前端按字段名取值）
        Set<String> taken = new HashSet<>();
        if (binding.getFieldMapping() != null) {
            taken.addAll(binding.getFieldMapping().keySet());
        }
        for (SideTableBinding side : nullSafe(binding.getSideTables())) {
            if (side != null && side.getFieldMapping() != null) {
                taken.addAll(side.getFieldMapping().keySet());
            }
        }
        if (StrUtil.isNotBlank(binding.getMainPk())) {
            taken.add(binding.getMainPk());
        }
        for (JoinTableBinding join : nullSafe(binding.getJoinTables())) {
            if (join == null || join.getSelectColumns() == null) {
                continue;
            }
            for (JoinTableBinding.SelectColumn column : join.getSelectColumns()) {
                if (column == null || StrUtil.isBlank(column.getColumn())) {
                    continue;
                }
                String key = StrUtil.blankToDefault(column.getAs(), column.getColumn());
                if (!taken.add(key)) {
                    // 建议的新别名带上关联表别名前缀，用户照着改就行
                    String suggestion = StrUtil.blankToDefault(join.getAlias(), join.getTable())
                            + "_" + column.getColumn();
                    throw exception(FORM_DESIGN_JOIN_SELECT_ALIAS_CONFLICT,
                            join.getTable(), key, suggestion);
                }
            }
        }
    }

    /**
     * 校验从表外键类型能否容纳主表主键值
     *
     * 主表用雪花（19 位 Long）时，外键是 int 会溢出 —— 插入时报
     * 「Out of range value for column」，且只有真正插入才暴露。
     * 类型元数据缺失时跳过校验，不误伤。
     */
    private void validateForeignKeyType(Long dataSourceId, TableBinding binding,
                                        String slaveTable, String foreignKey,
                                        Map<String, TableField> mainColumns) {
        if (StrUtil.isBlank(slaveTable) || StrUtil.isBlank(foreignKey)) {
            return;
        }
        String pkColumn = StrUtil.isNotBlank(binding.getMainPk()) ? binding.getMainPk() : "id";
        TableField pkField = mainColumns.get(pkColumn);
        if (pkField == null) {
            return;
        }
        String pkType = resolveJdbcType(pkField);

        TableInfo slaveInfo = metadataCache.get(dataSourceId, slaveTable);
        if (slaveInfo == null || slaveInfo.getFields() == null) {
            return;
        }
        TableField fkField = buildColumnMap(slaveInfo.getFields()).get(foreignKey);
        if (fkField == null) {
            return;
        }
        String fkType = resolveJdbcType(fkField);

        if (pkType == null || fkType == null) {
            log.warn("主表主键 {}.{} 或从表外键 {}.{} 的类型元数据缺失，跳过外键类型校验",
                    binding.getMainTable(), pkColumn, slaveTable, foreignKey);
            return;
        }

        if (!isForeignKeyCompatible(pkType, fkType)) {
            throw exception(FORM_DESIGN_FK_TYPE_MISMATCH,
                    slaveTable, foreignKey, fkType, pkColumn, pkType);
        }
    }

    /**
     * 判断外键类型能否容纳主键类型的值
     *
     * 只拦「明确装不下」的组合，不做严格等值比较 ——
     * bigint 主键配 decimal 外键虽然不常见，但能存下，不该阻塞发布。
     */
    private boolean isForeignKeyCompatible(String pkType, String fkType) {
        boolean pkIsBigInt = isBigIntType(pkType);
        boolean pkIsString = isStringType(pkType);
        boolean fkIsBigInt = isBigIntType(fkType);
        boolean fkIsString = isStringType(fkType);

        // 字符串主键（UUID）必须配字符串外键
        if (pkIsString) {
            return fkIsString;
        }
        // bigint 主键：外键必须也是 bigint 级别，int 装不下 19 位
        if (pkIsBigInt) {
            return fkIsBigInt;
        }
        // 主键是 int 等较小的数字类型：数字外键一定装得下；
        // 字符串外键虽然能存但会导致 JOIN 时隐式类型转换、索引失效，这里放过但不推荐
        return true;
    }

    /**
     * 校验主键列类型能否容纳所选的主键策略
     *
     * 雪花 ID 是 19 位 Long，塞进 int 列会溢出；UUID 是 32 位字符串，塞进数字列直接报错。
     * 这两种错配都要等到运行时插入才暴露，所以在发布时拦住。
     * 自增策略不校验 —— 列类型由数据库自己保证。
     */
    private void validatePkType(TableBinding binding, Map<String, TableField> mainColumns) {
        String pkColumn = StrUtil.isNotBlank(binding.getMainPk()) ? binding.getMainPk() : "id";
        if (!PkStrategyEnum.AUTO.getCode().equals(binding.getPkStrategy())
                && StrUtil.isNotBlank(binding.getPkStrategy())
                && !mainColumns.containsKey(pkColumn)) {
            throw exception(FORM_DESIGN_COLUMN_NOT_EXISTS, binding.getMainTable(), pkColumn);
        }
        validatePkTypeFor(binding.getPkStrategy(), binding.getMainTable(), pkColumn, mainColumns);
    }

    /**
     * 校验某张表的主键列类型能否容纳所选的主键策略
     *
     * 主表、副表、子表共用 —— 三类表的主键策略语义完全一致，
     * 各写一遍很容易出现「主表校验了、从表漏了」的不对称。
     *
     * @param strategy 主键策略。为空或自增时不校验，列类型由数据库自己保证
     */
    private void validatePkTypeFor(String strategy, String tableName, String pkColumn,
                                   Map<String, TableField> columns) {
        if (StrUtil.isBlank(strategy) || PkStrategyEnum.AUTO.getCode().equals(strategy)) {
            return;
        }
        TableField pkField = columns.get(pkColumn);
        if (pkField == null) {
            throw exception(FORM_DESIGN_COLUMN_NOT_EXISTS, tableName, pkColumn);
        }
        String jdbcType = resolveJdbcType(pkField);
        if (jdbcType == null) {
            // 拿不到元数据类型时不拦 —— 宁可放过也不误伤，运行时真出错也有兜底报错
            log.warn("主键列 {}.{} 的类型元数据缺失，跳过主键类型校验", tableName, pkColumn);
            return;
        }

        if (PkStrategyEnum.SNOWFLAKE.getCode().equals(strategy) && !isBigIntType(jdbcType)) {
            throw exception(FORM_DESIGN_PK_TYPE_MISMATCH,
                    PkStrategyEnum.SNOWFLAKE.getName(), tableName, pkColumn, jdbcType, "bigint");
        }
        if (PkStrategyEnum.UUID.getCode().equals(strategy) && !isStringType(jdbcType)) {
            throw exception(FORM_DESIGN_PK_TYPE_MISMATCH,
                    PkStrategyEnum.UUID.getName(), tableName, pkColumn,
                    jdbcType, "varchar(32) 及以上");
        }
    }

    /**
     * 读取列的 JDBC 类型名。元数据缺失时返回 null
     */
    private String resolveJdbcType(TableField field) {
        if (field.getMetaInfo() == null || field.getMetaInfo().getJdbcType() == null) {
            return null;
        }
        return field.getMetaInfo().getJdbcType().name();
    }

    /**
     * 是否能容纳 19 位 Long
     *
     * MySQL 的 bigint 在 JDBC 元数据里是 BIGINT；DECIMAL 位数足够也能存，一并放过。
     */
    private boolean isBigIntType(String jdbcType) {
        String upper = jdbcType.toUpperCase();
        return upper.contains("BIGINT") || upper.equals("DECIMAL") || upper.equals("NUMERIC");
    }

    /**
     * 是否为字符串类型（UUID 需要）
     */
    private boolean isStringType(String jdbcType) {
        String upper = jdbcType.toUpperCase();
        return upper.contains("CHAR") || upper.contains("TEXT");
    }

    /**
     * 校验系统自动填充与表单字段映射没有争抢同一列
     *
     * 冲突的后果是 buildInsert 把同一列拼进去两次，生成
     * INSERT INTO t (creator, creator) VALUES (?, ?)，MySQL 直接报错。
     * 在发布时拦住，而不是等运行时 SQL 报错。
     *
     * 只有 AUTO 模式才算冲突：MANUAL 表示这列本来就交给表单填，NONE 表示两边都不管。
     */
    private void validateAutoFillConflict(TableBinding binding) {
        AutoFillConfig autoFill = binding.getAutoFill();
        if (autoFill == null || binding.getFieldMapping() == null) {
            return;
        }
        Set<String> mappedColumns = new HashSet<>();
        for (FieldMapping mapping : binding.getFieldMapping().values()) {
            if (mapping != null && StrUtil.isNotBlank(mapping.getColumn())) {
                mappedColumns.add(mapping.getColumn());
            }
        }
        for (String column : autoFill.getAutoModeColumns()) {
            if (mappedColumns.contains(column)) {
                throw exception(FORM_DESIGN_AUTO_FILL_COLUMN_CONFLICT, column);
            }
        }
    }

    /**
     * 校验结果（备用）
     *
     * 当前实现是遇到第一个错误就抛异常，如果未来需要一次性列出所有错误，
     * 可以改为收集到这个结果对象里返回。
     */
    @Data
    public static class ValidationResult {
        private boolean success;
        private List<String> errors = new ArrayList<>();

        public void addError(String error) {
            this.success = false;
            this.errors.add(error);
        }
    }

}
