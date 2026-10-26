package cn.enlev.ainb.module.system.controller.admin.permission;

import cn.enlev.ainb.framework.common.pojo.CommonResult;
import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.framework.common.util.object.BeanUtils;
import cn.enlev.ainb.module.system.controller.admin.permission.vo.rule.RulePageReqVO;
import cn.enlev.ainb.module.system.controller.admin.permission.vo.rule.RuleRespVO;
import cn.enlev.ainb.module.system.controller.admin.permission.vo.rule.RuleSaveReqVO;
import cn.enlev.ainb.module.system.controller.admin.permission.vo.rule.RuleTemplateMetaRespVO;
import cn.enlev.ainb.module.system.dal.dataobject.permission.RuleDO;
import cn.enlev.ainb.module.system.dal.mysql.permission.RuleMapper;
import cn.enlev.ainb.module.system.enums.permission.PermissionSlotEnum;
import cn.enlev.ainb.module.system.enums.permission.RuleTemplateEnum;
import cn.enlev.ainb.module.system.enums.permission.RuleTemplateMetaRegistry;
import cn.enlev.ainb.module.system.framework.permission.core.rule.BuiltInVariableProvider;
import cn.enlev.ainb.module.system.framework.permission.core.rule.ConditionOperatorEnum;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import cn.enlev.ainb.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.enlev.ainb.module.system.dal.dataobject.permission.GrantDO;
import cn.enlev.ainb.module.system.dal.mysql.permission.GrantMapper;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.framework.common.pojo.CommonResult.success;
import static cn.enlev.ainb.module.system.enums.ErrorCodeConstants.*;

/**
 * 管理后台 - 行过滤规则（S8a 查询 / S8b 补全编辑）
 */
@Tag(name = "管理后台 - 行过滤规则")
@RestController
@RequestMapping("/system/rule")
@Validated
public class RuleAdminController {

    @Resource
    private RuleMapper ruleMapper;
    @Resource
    private GrantMapper grantMapper;

    @GetMapping("/simple-list")
    @Operation(summary = "查询启用的规则列表（授权配置页下拉）")
    @PreAuthorize("@ss.hasPermission('system:rule:query')")
    public CommonResult<List<RuleRespVO>> getSimpleRuleList() {
        List<RuleDO> list = ruleMapper.selectList(
                new LambdaQueryWrapper<RuleDO>()
                        .eq(RuleDO::getStatus, 0)
                        .orderByAsc(RuleDO::getId));
        return success(BeanUtils.toBean(list, RuleRespVO.class));
    }

    @GetMapping("/list")
    @Operation(summary = "查询全部规则列表（含停用）")
    @PreAuthorize("@ss.hasPermission('system:rule:query')")
    public CommonResult<List<RuleRespVO>> getRuleList() {
        List<RuleDO> list = ruleMapper.selectList(
                new LambdaQueryWrapper<RuleDO>().orderByAsc(RuleDO::getId));
        return success(BeanUtils.toBean(list, RuleRespVO.class));
    }

    @GetMapping("/page")
    @Operation(summary = "获得规则分页")
    @PreAuthorize("@ss.hasPermission('system:rule:query')")
    public CommonResult<PageResult<RuleRespVO>> getRulePage(@Valid RulePageReqVO pageReqVO) {
        PageResult<RuleDO> pageResult = ruleMapper.selectPage(pageReqVO);
        return success(BeanUtils.toBean(pageResult, RuleRespVO.class));
    }

    @GetMapping("/get")
    @Operation(summary = "查询单条规则")
    @Parameter(name = "id", description = "规则编号", required = true)
    @PreAuthorize("@ss.hasPermission('system:rule:query')")
    public CommonResult<RuleRespVO> getRule(@RequestParam("id") Long id) {
        RuleDO rule = ruleMapper.selectById(id);
        return success(rule == null ? null : BeanUtils.toBean(rule, RuleRespVO.class));
    }

    @GetMapping("/template-meta")
    @Operation(summary = "查询规则模板元数据",
            description = "返回模板/槽位/操作符/变量词表，供规则编辑器动态渲染，避免前端硬编码与后端枚举漂移")
    @PreAuthorize("@ss.hasPermission('system:rule:query')")
    public CommonResult<RuleTemplateMetaRespVO> getRuleTemplateMeta() {
        RuleTemplateMetaRespVO vo = new RuleTemplateMetaRespVO();

        // 模板：枚举提供 code/displayName，注册表提供分组/白话说明/槽位/参数规格
        List<RuleTemplateMetaRespVO.TemplateMeta> templates = new ArrayList<>();
        for (RuleTemplateEnum t : RuleTemplateEnum.values()) {
            RuleTemplateMetaRespVO.TemplateMeta tm = new RuleTemplateMetaRespVO.TemplateMeta();
            tm.setCode(t.getCode());
            tm.setDisplayName(t.getDisplayName());
            RuleTemplateMetaRegistry.Meta meta = RuleTemplateMetaRegistry.get(t);
            if (meta != null) {
                tm.setGroup(meta.group());
                tm.setBusinessDescription(meta.businessDescription());
                tm.setRequiredSlots(meta.requiredSlots());
                tm.setParamSpecs(meta.paramSpecs().stream().map(ps -> {
                    RuleTemplateMetaRespVO.ParamSpec p = new RuleTemplateMetaRespVO.ParamSpec();
                    p.setKey(ps.key());
                    p.setKind(ps.kind());
                    p.setLabel(ps.label());
                    p.setRequired(ps.required());
                    p.setValueType(ps.valueType());
                    return p;
                }).toList());
            } else {
                // 理论上不会发生（有穷尽性单测兜底）；兜底成「高级、无参数」，让编辑器退回 JSON 模式
                tm.setGroup(RuleTemplateMetaRegistry.GROUP_ADVANCED);
                tm.setRequiredSlots(List.of());
                tm.setParamSpecs(List.of());
            }
            templates.add(tm);
        }
        vo.setTemplates(templates);

        // 槽位
        vo.setSlots(Arrays.stream(PermissionSlotEnum.values()).map(s -> {
            RuleTemplateMetaRespVO.SlotMeta sm = new RuleTemplateMetaRespVO.SlotMeta();
            sm.setSlot(s.getSlot());
            sm.setDescription(s.getDescription());
            return sm;
        }).toList());

        // 操作符
        vo.setOperators(Arrays.stream(ConditionOperatorEnum.values()).map(o -> {
            RuleTemplateMetaRespVO.OperatorMeta om = new RuleTemplateMetaRespVO.OperatorMeta();
            om.setCode(o.getCode());
            om.setLabel(o.getLabel());
            om.setUnary(o.isUnary());
            om.setMultiValue(o.isMultiValue());
            return om;
        }).toList());

        // 变量
        vo.setVariables(BuiltInVariableProvider.VARIABLE_METADATA.stream().map(v -> {
            RuleTemplateMetaRespVO.VariableMeta vm = new RuleTemplateMetaRespVO.VariableMeta();
            vm.setName(v.name());
            vm.setLabel(v.label());
            vm.setCollection(v.collection());
            return vm;
        }).toList());

        return success(vo);
    }

    @PostMapping("/create")
    @Operation(summary = "新增规则")
    @PreAuthorize("@ss.hasPermission('system:rule:create')")
    public CommonResult<Long> createRule(@Valid @RequestBody RuleSaveReqVO reqVO) {
        RuleDO rule = BeanUtils.toBean(reqVO, RuleDO.class);
        rule.setId(null); // 新增不能带 id
        normalizeBlankParams(rule);
        ruleMapper.insert(rule);
        return success(rule.getId());
    }

    @PutMapping("/update")
    @Operation(summary = "修改规则")
    @PreAuthorize("@ss.hasPermission('system:rule:update')")
    public CommonResult<Boolean> updateRule(@Valid @RequestBody RuleSaveReqVO reqVO) {
        if (reqVO.getId() == null) {
            throw exception(RULE_UPDATE_ID_REQUIRED);
        }
        // 内置规则只读：与删除保护同源，避免界面上改坏种子语义（前端只读模式只是体验，这里才是硬约束）
        RuleDO exists = ruleMapper.selectById(reqVO.getId());
        if (exists == null) {
            throw exception(RULE_NOT_EXISTS);
        }
        if (Boolean.TRUE.equals(exists.getBuiltIn())) {
            throw exception(RULE_BUILT_IN_UNEDITABLE);
        }
        RuleDO rule = BeanUtils.toBean(reqVO, RuleDO.class);
        normalizeBlankParams(rule);
        // 🔴 不能走 updateById：MyBatis-Plus 默认字段策略是 NOT_NULL，实体上为 null 的列会被
        //    【整个从 SET 子句里省掉】—— 那在 SQL 里的含义是「别动这一列」，不是「清成 null」。
        //    而 normalizeBlankParams 干的全部工作就是把空串抹成 null，于是它抹完等于没抹：
        //    把一条参数化规则改成无参模板（如 OWNER_ONLY），旧的 params JSON 会原样留在库里，
        //    DataRowFilterResolver 继续按旧参数求值 —— 管理员看到「保存成功」，
        //    但行过滤范围不是他刚配的那个。description 清空同理。
        //    实体仍然带上（name / template / status 这些非空列由它写进 SET），
        //    wrapper 只负责补上「这次要清成 null」的那两列。
        //    ⚠️ builtIn 不在 RuleSaveReqVO 里，BeanUtils 之后恒为 null，
        //    正因为默认策略会跳过 null 列，它才不会被这条 UPDATE 抹掉 —— 别给它加 set
        ruleMapper.update(rule, new LambdaUpdateWrapper<RuleDO>()
                .eq(RuleDO::getId, rule.getId())
                .set(rule.getParams() == null, RuleDO::getParams, null)
                .set(rule.getDescription() == null, RuleDO::getDescription, null));
        return success(true);
    }

    /**
     * params 空串归一为 null。
     *
     * ⚠️ sys_rule.params 是 MySQL JSON 列，空字符串不是合法 JSON 文档，
     * 直接 insert/update 会抛 Data truncation: Invalid JSON text: "The document is empty."。
     * 无参数模板（如 OWNER_ONLY）序列化结果恰为空串，必须落成 NULL（与内置种子一致）。
     */
    private void normalizeBlankParams(RuleDO rule) {
        if (rule.getParams() != null && rule.getParams().isBlank()) {
            rule.setParams(null);
        }
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除规则",
            description = "系统内置规则（built_in=1）不能删除，可将其停用；被授权引用的规则也不能删")
    @Parameter(name = "id", description = "规则编号", required = true)
    @PreAuthorize("@ss.hasPermission('system:rule:delete')")
    public CommonResult<Boolean> deleteRule(@RequestParam("id") Long id) {
        RuleDO rule = ruleMapper.selectById(id);
        if (rule == null) {
            throw exception(RULE_NOT_EXISTS);
        }
        // 内置保护改用显式字段。
        //
        // ⚠️ 原来这里判的是「id <= 13」，有两个毛病：
        //   ① 判据脆弱：注释自己写着「后续种子新增 id>13 的规则必须同步提高阈值」，
        //      而 @Operation 文档当时已经漂成了「id ≤ 100」，与实现对不上
        //   ② 阈值就是错的：实际种子最大 id 是 12，id=13 是管理员在界面上建的规则，
        //      却被当成内置删不掉 —— 用户报的正是这个
        if (Boolean.TRUE.equals(rule.getBuiltIn())) {
            throw exception(RULE_BUILT_IN_UNDELETABLE);
        }
        // 被授权引用的规则不能删：删了之后 DataRowFilterResolver 会查到一条
        // 指向不存在规则的授权，那条路径是【整表拒绝】—— 影响面比报个错大得多
        Long used = grantMapper.selectCount(new LambdaQueryWrapperX<GrantDO>().eq(GrantDO::getRuleId, id));
        if (used != null && used > 0) {
            throw exception(RULE_IN_USE, used);
        }
        ruleMapper.deleteById(id);
        return success(true);
    }

    @DeleteMapping("/delete-list")
    @Operation(summary = "批量删除规则",
            description = "校验与单条删除一致：系统内置规则（built_in=1）不能删除；被授权引用的规则不能删")
    @Parameter(name = "ids", description = "规则编号列表", required = true)
    @PreAuthorize("@ss.hasPermission('system:rule:delete')")
    public CommonResult<Boolean> deleteRuleList(@RequestParam("ids") List<Long> ids) {
        List<RuleDO> rules = ruleMapper.selectByIds(ids);
        if (rules.size() != ids.size()) {
            throw exception(RULE_NOT_EXISTS);
        }
        if (rules.stream().anyMatch(rule -> Boolean.TRUE.equals(rule.getBuiltIn()))) {
            throw exception(RULE_BUILT_IN_UNDELETABLE);
        }
        Long used = grantMapper.selectCount(new LambdaQueryWrapperX<GrantDO>().in(GrantDO::getRuleId, ids));
        if (used != null && used > 0) {
            throw exception(RULE_IN_USE, used);
        }
        ruleMapper.deleteByIds(ids);
        return success(true);
    }

}
