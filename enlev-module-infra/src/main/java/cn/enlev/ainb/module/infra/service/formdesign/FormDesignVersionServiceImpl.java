package cn.enlev.ainb.module.infra.service.formdesign;

import cn.enlev.ainb.framework.common.util.json.JsonUtils;
import cn.enlev.ainb.module.infra.framework.formdesign.core.schema.FormDesignSchema;
import cn.enlev.ainb.module.infra.dal.dataobject.formdesign.FormDesignDO;
import cn.enlev.ainb.module.infra.dal.dataobject.formdesign.FormDesignVersionDO;
import cn.enlev.ainb.module.infra.dal.mysql.formdesign.FormDesignMapper;
import cn.enlev.ainb.module.infra.dal.mysql.formdesign.FormDesignVersionMapper;
import cn.enlev.ainb.module.infra.enums.formdesign.FormDesignStatusEnum;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.module.infra.enums.ErrorCodeConstants.FORM_DESIGN_NOT_EXISTS;
import static cn.enlev.ainb.module.infra.enums.ErrorCodeConstants.FORM_DESIGN_RECORD_VERSION_CONFLICT;
import static cn.enlev.ainb.module.infra.enums.ErrorCodeConstants.FORM_DESIGN_VERSION_NOT_EXISTS;

/**
 * 表单设计版本 Service 实现类
 *
 * @author 走进新时代
 */
@Service
@Validated
@Slf4j
public class FormDesignVersionServiceImpl implements FormDesignVersionService {

    @Resource
    private FormDesignVersionMapper formDesignVersionMapper;
    @Resource
    private FormDesignMapper formDesignMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Integer createVersion(FormDesignDO formDesign, String changeSummary) {
        Integer nextVersion = formDesignVersionMapper.selectMaxVersionByDesignId(formDesign.getId()) + 1;

        FormDesignVersionDO version = new FormDesignVersionDO();
        version.setDesignId(formDesign.getId());
        version.setVersion(nextVersion);
        version.setSnapshot(buildSnapshot(formDesign));
        version.setChangeSummary(changeSummary);
        formDesignVersionMapper.insert(version);

        // 只保留最新 N 个版本，超出则物理删除最老的
        trimOldVersions(formDesign.getId());
        return nextVersion;
    }

    /**
     * 构建版本快照
     *
     * 只存配置相关字段，不存 status / publishedVersion / menuId 这类运行时状态 ——
     * 回滚时不该把「当时是否已发布」「当时挂在哪个菜单」一起还原。
     */
    private String buildSnapshot(FormDesignDO formDesign) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("name", formDesign.getName());
        snapshot.put("code", formDesign.getCode());
        snapshot.put("category", formDesign.getCategory());
        snapshot.put("formType", formDesign.getFormType());
        snapshot.put("formConf", formDesign.getFormConf());
        snapshot.put("formFields", formDesign.getFormFields());
        snapshot.put("listConf", formDesign.getListConf());
        snapshot.put("dataSourceConfigId", formDesign.getDataSourceConfigId());
        snapshot.put("mainTable", formDesign.getMainTable());
        snapshot.put("mainPk", formDesign.getMainPk());
        snapshot.put("tableBinding", formDesign.getTableBinding());
        // 联动规则、跨字段校验规则、脚本与扩展模式也必须进快照。
        //
        // ⚠️ 这几项原先漏了（B1 加 logic_rules 时只改了 DO 与保存接口）。漏掉的后果是：
        // 回滚会把 formFields 还原到旧版本，而规则仍是新版本的 ——
        // 规则里引用的字段可能已经不存在了，表现为「回滚之后联动莫名失效」，
        // 或者反过来「明明没配规则却有字段被置灰」。规则与字段必须同进同出
        snapshot.put("logicRules", formDesign.getLogicRules());
        snapshot.put("validateRules", formDesign.getValidateRules());
        snapshot.put("formScript", formDesign.getFormScript());
        snapshot.put("extensionMode", formDesign.getExtensionMode());
        snapshot.put("submitHookTimeoutAction", formDesign.getSubmitHookTimeoutAction());
        // 快照必须记下它是按哪一版结构写的。少了它，回滚一份旧结构的快照会把旧数据
        // 原样塞回主表，而主表已经按新结构在读了 —— 表现为回滚后表单打不开
        snapshot.put(FormDesignSchema.SNAPSHOT_KEY, formDesign.getSchemaVersion());
        return JsonUtils.toJsonString(snapshot);
    }

    /**
     * 裁剪超出保留上限的旧版本
     */
    private void trimOldVersions(Long designId) {
        List<FormDesignVersionDO> versions = formDesignVersionMapper.selectListByDesignId(designId);
        int excess = versions.size() - FormDesignVersionDO.MAX_KEEP_VERSIONS;
        if (excess > 0) {
            formDesignVersionMapper.deleteOldestVersions(designId, excess);
        }
    }

    @Override
    public List<FormDesignVersionDO> getVersionList(Long designId) {
        return formDesignVersionMapper.selectListByDesignId(designId);
    }

    @Override
    public FormDesignVersionDO getVersion(Long designId, Integer version) {
        FormDesignVersionDO result = formDesignVersionMapper.selectByDesignIdAndVersion(designId, version);
        if (result == null) {
            throw exception(FORM_DESIGN_VERSION_NOT_EXISTS);
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void rollback(Long designId, Integer version) {
        FormDesignDO formDesign = formDesignMapper.selectById(designId);
        if (formDesign == null) {
            throw exception(FORM_DESIGN_NOT_EXISTS);
        }
        FormDesignVersionDO target = getVersion(designId, version);

        // 用 parseObjectQuietly 而不是 parseObject：后者遇到非法 JSON 会直接抛
        // JsonParseException，下面那句 null 判断就成了永远走不到的死代码，
        // 用户拿到的是一个 500 而不是「该版本快照已损坏」这样能看懂的提示。
        // 快照是几个月前写进库的一份文本，字段结构随迭代变化、也可能被手工改过，
        // 解析不出来是一种要正常处理的情况，不是意外
        Map<String, Object> snapshot = JsonUtils.parseObjectQuietly(target.getSnapshot(), Map.class);
        if (snapshot == null) {
            throw exception(FORM_DESIGN_VERSION_NOT_EXISTS);
        }

        FormDesignDO updateObj = new FormDesignDO();
        updateObj.setId(designId);
        // code 不回滚：运行时与菜单都通过它引用本表单，改了会让已发布菜单失效
        updateObj.setName(asString(snapshot.get("name")));
        updateObj.setFormType(asInteger(snapshot.get("formType")));
        // 🔴 category / formConf / formFields / listConf / dataSourceConfigId /
        //    mainTable / mainPk / tableBinding 这 8 列【刻意不挂在这里】，
        //    连同规则类列一起交给下面的 rollbackClearableColumns 显式写回，理由见它的注释
        // 回滚 Schema 版本号：回滚后主表里躺的是那一版结构的数据，版本号必须跟着回去，
        // 否则迁移器会以为它已经是当前结构而跳过它。
        // 老快照没有这个键（本功能之前建的），按第 1 版处理 —— 那正是它们写入时的结构版本
        Integer snapshotSchemaVersion = asInteger(snapshot.get(FormDesignSchema.SNAPSHOT_KEY));
        updateObj.setSchemaVersion(snapshotSchemaVersion != null ? snapshotSchemaVersion : 1);
        // 回滚只改草稿，线上版本不动，需用户再点发布才生效
        updateObj.setStatus(formDesign.getPublishedVersion() != null && formDesign.getPublishedVersion() > 0
                ? FormDesignStatusEnum.PUBLISHED_MODIFIED.getStatus()
                : FormDesignStatusEnum.DRAFT.getStatus());
        // 🔴 带乐观锁写回：回滚是「读一份几个月前的快照 → 整份盖回主表」，
        //    覆盖范围比任何一次编辑都大。若这期间有人改过这张表单，
        //    不加锁的结果是他的改动被一次回滚静默抹掉，而两边都收到「成功」
        updateObj.setVersion(formDesign.getVersion());
        if (formDesignMapper.updateById(updateObj) == 0) {
            throw exception(FORM_DESIGN_RECORD_VERSION_CONFLICT);
        }

        rollbackClearableColumns(designId, snapshot);
    }

    /**
     * 回滚所有<b>可空</b>的配置列：表单本体、绑表配置、规则类列
     *
     * <h3>为什么这些列不能挂在 {@code updateObj} 上走 {@code updateById}</h3>
     *
     * 🔴 MyBatis-Plus 的默认字段策略是 {@code NOT_NULL}：实体上为 null 的字段会被
     * <b>整个从 SET 子句里省掉</b>，那在 SQL 里的含义是「别动这一列」，不是「清成 null」。
     * 于是「从绑了表的版本回滚到没绑表的版本」用 {@code updateById} 根本做不到 ——
     * mainTable / mainPk / tableBinding 会原样留在库里，运行时继续往主表写数据，
     * 而用户以为已经回滚干净了。这正是本方法存在的理由。
     *
     * <p>⚠️ 这个坑在本文件里只被修掉过一半：原先只有 logicRules / validateRules / formScript
     * 三列走了 wrapper（当时的方法名叫 {@code rollbackRuleColumns}），而同样可空的
     * category / listConf / dataSourceConfigId / mainTable / mainPk / tableBinding
     * 仍然挂在 {@code updateObj} 上 —— 症状一模一样，只是换了几列。
     * 现在统一收进这一个方法，<b>别再往 updateObj 上加可空列</b>。
     *
     * <h3>区分两种「空」</h3>
     *
     * <ul>
     *   <li>快照里【没有这个键】—— 本功能之前建的老快照。保持现状不动，
     *       因为那时的值没被记下来，清空等于凭空删掉用户的配置</li>
     *   <li>快照里【有这个键但值为 null】—— 那一版确实没有这项配置，如实清空</li>
     * </ul>
     *
     * {@code buildSnapshot} 对这些键一律 {@code put}（值可能是 null），所以新快照永远走第二支。
     * ⚠️ 第二支能成立有个前提：序列化时<b>不能</b>丢掉值为 null 的键。
     * 运行时用的是 Spring 那个 mapper（{@code ALWAYS}），键在、值是 null，成立；
     * 但 {@code JsonUtils} 自己的静态 mapper 是 {@code NON_NULL} —— 任何未经 Spring 初始化的
     * 使用（比如单测里直接调 {@code buildSnapshot}）写出来的快照会丢掉这些键，
     * 回滚就退化成第一支「保持现状」。这条差异不改，只是记在这里。
     *
     * <p>🔴 这里的 {@code update(null, wrapper)} <b>刻意不带版本条件</b>，
     * 而且拦截器也<b>结构上带不了</b>（3.5.16 的 {@code doOptimisticLocker} 取不到 {@code et} 时，
     * 只在 {@code wrapperMode} 下才动手，而默认构造器把它置为 false）。
     * 不加是安全的：本方法与上面那次 {@code updateById} 同处一个
     * {@code @Transactional} 的 {@code rollback} 里，那次 UPDATE 已经对这一行取了排他锁、
     * 持锁到提交，中间没有别的事务能改它。
     *
     * <p>⚠️ 于是这里有一条<b>顺序依赖</b>：本方法必须在那次带锁的
     * {@code updateById} <b>之后</b>调用，且两者必须留在同一个事务里。
     * 把它挪到前面、或者给 {@code rollback} 去掉 {@code @Transactional}，
     * 这条 UPDATE 就真的没有并发保护了 —— 而它清空的正是绑表配置、联动规则与前端脚本。
     */
    private void rollbackClearableColumns(Long designId, Map<String, Object> snapshot) {
        LambdaUpdateWrapper<FormDesignDO> wrapper = new LambdaUpdateWrapper<FormDesignDO>()
                .eq(FormDesignDO::getId, designId);
        boolean any = false;
        // ---- 表单本体 ----
        if (snapshot.containsKey("category")) {
            wrapper.set(FormDesignDO::getCategory, asString(snapshot.get("category")));
            any = true;
        }
        if (snapshot.containsKey("formConf")) {
            wrapper.set(FormDesignDO::getFormConf, asString(snapshot.get("formConf")));
            any = true;
        }
        if (snapshot.containsKey("formFields")) {
            wrapper.set(FormDesignDO::getFormFields, asString(snapshot.get("formFields")));
            any = true;
        }
        if (snapshot.containsKey("listConf")) {
            wrapper.set(FormDesignDO::getListConf, asString(snapshot.get("listConf")));
            any = true;
        }
        // ---- 绑表配置：这三列残留的后果最重，运行时会继续往旧主表写数据 ----
        if (snapshot.containsKey("dataSourceConfigId")) {
            wrapper.set(FormDesignDO::getDataSourceConfigId, asLong(snapshot.get("dataSourceConfigId")));
            any = true;
        }
        if (snapshot.containsKey("mainTable")) {
            wrapper.set(FormDesignDO::getMainTable, asString(snapshot.get("mainTable")));
            any = true;
        }
        if (snapshot.containsKey("mainPk")) {
            wrapper.set(FormDesignDO::getMainPk, asString(snapshot.get("mainPk")));
            any = true;
        }
        if (snapshot.containsKey("tableBinding")) {
            wrapper.set(FormDesignDO::getTableBinding, asString(snapshot.get("tableBinding")));
            any = true;
        }
        // ---- 规则类列 ----
        if (snapshot.containsKey("logicRules")) {
            wrapper.set(FormDesignDO::getLogicRules, asString(snapshot.get("logicRules")));
            any = true;
        }
        if (snapshot.containsKey("validateRules")) {
            wrapper.set(FormDesignDO::getValidateRules, asString(snapshot.get("validateRules")));
            any = true;
        }
        if (snapshot.containsKey("formScript")) {
            wrapper.set(FormDesignDO::getFormScript, asString(snapshot.get("formScript")));
            any = true;
        }
        // 这两列在库里是 NOT NULL，快照值为空时不能写进去，否则整次回滚会因约束失败
        Integer extensionMode = asInteger(snapshot.get("extensionMode"));
        if (extensionMode != null) {
            wrapper.set(FormDesignDO::getExtensionMode, extensionMode);
            any = true;
        }
        Integer timeoutAction = asInteger(snapshot.get("submitHookTimeoutAction"));
        if (timeoutAction != null) {
            wrapper.set(FormDesignDO::getSubmitHookTimeoutAction, timeoutAction);
            any = true;
        }
        if (any) {
            formDesignMapper.update(null, wrapper);
        }
    }

    @Override
    public void deleteByDesignId(Long designId) {
        List<FormDesignVersionDO> versions = formDesignVersionMapper.selectListByDesignId(designId);
        versions.forEach(v -> formDesignVersionMapper.deleteById(v.getId()));
    }

    private String asString(Object value) {
        return value != null ? value.toString() : null;
    }

    private Integer asInteger(Object value) {
        if (value == null) return null;
        return value instanceof Number ? ((Number) value).intValue() : Integer.valueOf(value.toString());
    }

    private Long asLong(Object value) {
        if (value == null) return null;
        return value instanceof Number ? ((Number) value).longValue() : Long.valueOf(value.toString());
    }

}
