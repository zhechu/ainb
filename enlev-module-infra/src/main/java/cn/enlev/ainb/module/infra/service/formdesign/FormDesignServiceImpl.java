package cn.enlev.ainb.module.infra.service.formdesign;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.framework.common.util.object.BeanUtils;
import cn.enlev.ainb.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.enlev.ainb.module.infra.controller.admin.formdesign.vo.FormDesignPageReqVO;
import cn.enlev.ainb.module.infra.controller.admin.formdesign.vo.FormDesignPublishReqVO;
import cn.enlev.ainb.module.infra.controller.admin.formdesign.vo.FormDesignPublishRespVO;
import cn.enlev.ainb.module.infra.controller.admin.formdesign.vo.FormDesignSaveReqVO;
import cn.enlev.ainb.module.infra.controller.admin.formdesign.vo.FormDesignUnpublishRespVO;
import cn.enlev.ainb.module.infra.dal.dataobject.formdesign.FormDesignDO;
import cn.enlev.ainb.module.infra.dal.dataobject.formdesign.FormDesignMenuDO;
import cn.enlev.ainb.module.infra.dal.dataobject.formdesign.FormDesignVersionDO;
import cn.enlev.ainb.module.infra.dal.mysql.formdesign.FormDesignMapper;
import cn.enlev.ainb.module.infra.dal.mysql.formdesign.FormDesignMenuMapper;
import cn.enlev.ainb.module.infra.dal.mysql.formdesign.FormDesignVersionMapper;
import cn.enlev.ainb.module.infra.enums.artifact.ArtifactSourceEnum;
import cn.enlev.ainb.module.infra.enums.formdesign.FormDesignStatusEnum;
import cn.enlev.ainb.module.infra.framework.formdesign.core.FormFieldsCodec;
import cn.enlev.ainb.module.infra.framework.formdesign.core.file.RichContentGuard;
import cn.enlev.ainb.module.infra.framework.formdesign.core.resource.FormResourceDescriptorBuilder;
import cn.enlev.ainb.module.infra.api.formdesign.dto.FormDesignMenuPreviewRespDTO;
import cn.enlev.ainb.module.infra.api.formdesign.dto.FormDesignPublishRespDTO;
import cn.enlev.ainb.module.infra.api.formdesign.dto.FormResourceDescriptorDTO;
import cn.enlev.ainb.module.infra.api.formdesign.event.FormDesignDeletedEvent;
import cn.enlev.ainb.module.infra.api.formdesign.event.FormDesignPublishedEvent;
import cn.enlev.ainb.module.infra.framework.formdesign.core.schema.FormDesignSchema;
import cn.enlev.ainb.module.infra.enums.formdesign.FormExtensionModeEnum;
import cn.enlev.ainb.module.infra.enums.formdesign.SubmitHookTimeoutActionEnum;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.module.infra.enums.ErrorCodeConstants.*;

/**
 * 表单设计 Service 实现类
 *
 * 只负责设计态：配置 CRUD、复制、状态流转。
 *
 * 状态流转是本类的核心约定：
 * - 新建 → 草稿(0)
 * - 草稿修改 → 仍是草稿(0)
 * - 已发布(1) 被修改 → 已发布有改动(2)，提示用户需重新发布才生效
 * - 已发布有改动(2) 再修改 → 仍是 2
 *
 * @author 走进新时代
 */
@Service
@Validated
@Slf4j
public class FormDesignServiceImpl implements FormDesignService {

    /**
     * 复制表单时编码追加的后缀
     */
    private static final String COPY_CODE_SUFFIX = "_copy";
    /**
     * 复制表单时为保证编码唯一的最大重试次数
     */
    private static final int COPY_CODE_MAX_RETRY = 100;
    /**
     * 软删时给墓碑行编码追加的后缀，后接 id
     */
    private static final String TOMBSTONE_CODE_SUFFIX = "_del_";
    /**
     * 与 {@code infra_form_design.code} 的列宽一致
     */
    private static final int CODE_MAX_LENGTH = 100;
    /**
     * 运行时菜单的路由前缀
     */
    private static final String MENU_PATH_PREFIX = "form-design-run/";
    /**
     * 运行时渲染页的组件路径
     */
    private static final String RUNTIME_COMPONENT = "infra/formDesign/render/index";
    /**
     * 生成菜单的默认图标
     */
    private static final String DEFAULT_MENU_ICON = "ep:document";

    /**
     * App 端在线表单运行页路径
     *
     * 移动端 pages-infra/form-design/run/index，配置驱动：列表列、搜索项、表单 rule
     * 全部来自 GET /infra/form-design/runtime/{code}/config。code 走 nav_node 的 query 列
     * （移动端跳转是 `${route}?${query}` 拼接），与 web 端 path 末段兜底解析同一条思路。
     */
    private static final String APP_NAV_ROUTE = "/pages-infra/form-design/run/index";

    /** App 导航节点类型：3 = 导航项（叶子）。发布产物恒为叶子，分组由用户在弹窗里选 */
    private static final int NAV_NODE_TYPE_ITEM = 3;

    private static final String APP_NAV_ICON = "list";
    /**
     * 菜单类型：菜单。与 system_menu 的 type 含义一致
     */
    private static final int MENU_TYPE_MENU = 2;
    /**
     * 菜单类型：按钮权限
     */
    private static final int MENU_TYPE_BUTTON = 3;
    /**
     * 登记行的初始乐观锁版本
     *
     * <p>与 DDL 里 {@code version int NOT NULL DEFAULT 0} 的默认值是一对。
     */
    private static final int INITIAL_VERSION = 0;

    @Resource
    private FormDesignMapper formDesignMapper;
    @Resource
    private FormDesignVersionMapper formDesignVersionMapper;
    @Resource
    private FormDesignMenuMapper formDesignMenuMapper;
    @Resource
    private FormDesignVersionService versionService;
    @Resource
    private FormDesignPublishValidator publishValidator;
    /**
     * 打印模板的白名单清洗。与富文本字段共用同一个守卫 ——
     * 另起一份白名单的话，两处迟早会漂，而漂掉的那一处就是一个 XSS 入口
     */
    @Resource
    private RichContentGuard richContentGuard;
    /**
     * 可授权对象描述的构建器。发布钩子用它把三份 JSON 解析成扁平清单
     */
    @Resource
    private FormResourceDescriptorBuilder resourceDescriptorBuilder;
    /**
     * 发布事件的广播口
     *
     * 权限模块（system）监听它来登记数据集/敏感列/字段资源与语义槽位。
     * 之所以是事件而不是直接调权限模块的 API：模块依赖是单向的
     * （system → infra），infra 调 system 会造成循环依赖
     */
    @Resource
    private ApplicationEventPublisher eventPublisher;

    @Override
    public Long createFormDesign(FormDesignSaveReqVO createReqVO) {
        return createFormDesign(createReqVO, ArtifactSourceEnum.MANUAL);
    }

    @Override
    public Long createFormDesign(FormDesignSaveReqVO createReqVO, ArtifactSourceEnum source) {
        validateCodeUnique(null, createReqVO.getCode());

        FormDesignDO formDesign = convertToDO(createReqVO);
        formDesign.setId(null);
        formDesign.setStatus(FormDesignStatusEnum.DRAFT.getStatus());
        formDesign.setPublishedVersion(0);
        // 🔴 治理字段由服务端盖，不从 VO 拷。version 尤其要显式归零：
        //    VO 上现在有 version 字段（改表要回传它），convertToDO 会按名字整份搬过来，
        //    于是一个「新建」请求可以自带版本号 —— 那条记录从出生起就与别人手里的基线错位
        formDesign.setSource(source.getSource());
        formDesign.setManualEditedFlag(false);
        formDesign.setSchemaFingerprint(null);
        formDesign.setVersion(INITIAL_VERSION);
        formDesignMapper.insert(formDesign);
        return formDesign.getId();
    }

    @Override
    public void updateFormDesign(FormDesignSaveReqVO updateReqVO) {
        updateFormDesign(updateReqVO, ArtifactSourceEnum.MANUAL);
    }

    @Override
    public void updateFormDesign(FormDesignSaveReqVO updateReqVO, ArtifactSourceEnum operator) {
        FormDesignDO oldFormDesign = validateFormDesignExists(updateReqVO.getId());

        // 已发布的表单不允许改编码：运行时与菜单都通过 code 引用本表单，
        // 改了会导致已发布的菜单指向失效
        boolean published = !Objects.equals(oldFormDesign.getStatus(), FormDesignStatusEnum.DRAFT.getStatus());
        if (published && !StrUtil.equals(oldFormDesign.getCode(), updateReqVO.getCode())) {
            throw exception(FORM_DESIGN_CODE_CANNOT_UPDATE);
        }
        validateCodeUnique(updateReqVO.getId(), updateReqVO.getCode());
        // 🔴 乐观锁：版本必须带上、且必须与库里一致。
        //    AI 走 FormUpdateTool 时是「读全量 → 改几处 → 整体回写」，
        //    读与写之间隔着好几轮模型调用（几十秒到几分钟）——
        //    这正是并发覆盖最容易发生的形态，也是 B4-4 点名要走乐观锁的原因
        validateRecordVersion(updateReqVO.getVersion(), oldFormDesign);

        FormDesignDO updateObj = convertToDO(updateReqVO);
        // 状态流转：已发布的表单被改动后标记为「已发布有改动」，草稿保持草稿
        updateObj.setStatus(nextStatusOnUpdate(oldFormDesign.getStatus()));
        // 线上版本号与菜单编号不由更新接口维护，交给发布/菜单流程
        updateObj.setPublishedVersion(null);
        updateObj.setMenuId(null);
        stampGovernanceOnUpdate(updateObj, oldFormDesign, operator);
        // 🔴 必须查影响行数：拦截器在版本对不上时【不抛异常】，只是让 UPDATE 命中 0 行
        if (formDesignMapper.update(updateObj, clearableColumnsWrapper(updateObj)) == 0) {
            throw exception(FORM_DESIGN_RECORD_VERSION_CONFLICT);
        }
    }

    /**
     * 记录级乐观锁的两道检查
     *
     * <p>🔴 <b>「没带版本」必须是硬拒绝，不能退化成「那就不加锁」。</b>
     * MyBatis-Plus 的拦截器在实体的 version 为 null 时<b>静默跳过</b>版本条件
     * （3.5.16 的 {@code doOptimisticLocker} 取到 null 就 return），
     * 于是漏传版本的调用会把乐观锁悄悄关掉，而且<b>不报错</b> ——
     * 这正是「加了 {@code @Version} 只算 10%」里那 90% 的失效形态。
     *
     * <p>拦截器有 {@code setException} 能把这种情况变成失败，但那是<b>单例级、跨全仓</b>的开关，
     * 装在一个连编译都参与不了的模块也在共用的 framework 里，
     * 且共享的异常实例堆栈指向 bean 构造处、会把排查引到错误的位置。
     * 所以绊线设在这里：按路径显式判，用本模块自己的错误码报。
     * 数据模型那边有一份同形的 {@code DataModelServiceImpl#validateRecordVersion}，
     * 刻意不抽公共方法 —— 两边报的是各自的错误码，抽出来就得把错误码当参数传，
     * 反而看不出这条路径失败时用户会读到什么。
     */
    private void validateRecordVersion(Integer reqVersion, FormDesignDO current) {
        if (reqVersion == null) {
            throw exception(FORM_DESIGN_RECORD_VERSION_REQUIRED);
        }
        if (!reqVersion.equals(current.getVersion())) {
            throw exception(FORM_DESIGN_RECORD_VERSION_CONFLICT);
        }
    }

    /**
     * 带乐观锁写回一条<b>局部更新</b>，并检查影响行数
     *
     * <p>给 publish / bindMenu 这两条路径用。它们的共同形态是
     * 「{@code new FormDesignDO()} → 只 set 两三个字段 → {@code updateById}」——
     * 也正是<b>最容易漏带 version</b> 的形态：DO 是新建的，没人会想到还要去填版本号，
     * 而漏填的表现是拦截器静默跳过、乐观锁在这条路径上等于没开。
     *
     * <p>🔴 这两条路径与「整份保存」不同，<b>版本号不来自调用方</b>，
     * 而取自本方法内已经读到的那一行。它们都是「读一行 → 按读到的内容决定改成什么」的
     * 服务端内部流转，调用方手里并没有一份可回传的基线；
     * 让它们各自读一次、拿读到的版本做 CAS，得到的正是需要的语义：
     * 「从我看见它到现在，没有别人动过它」。
     *
     * <p>⚠️ 拦截器在冲突时<b>不抛异常</b>，只是让 UPDATE 命中 0 行 ——
     *    所以这里必须查行数。不查的话表现是「接口返回成功、别人的改动被静默丢弃」。
     *
     * <p>🔴 <b>需要把某一列置 NULL 的路径不能用本方法</b>：它走 {@code updateById}，
     *    实体上为 null 的列会被 MP 的 NOT_NULL 策略整列跳过，没有 wrapper 可以补 set。
     *    unpublish 要清 {@code menu_id}，因此改成了 {@code update(entity, wrapper)} 的写法，
     *    见该方法里那段注释。
     *
     * @param updateObj 只 set 了待改字段的 DO，id 必填
     * @param current   本次请求内刚从库里读到的那一行
     */
    private void updateByIdWithLock(FormDesignDO updateObj, FormDesignDO current) {
        updateObj.setVersion(current.getVersion());
        if (formDesignMapper.updateById(updateObj) == 0) {
            throw exception(FORM_DESIGN_RECORD_VERSION_CONFLICT);
        }
    }

    /**
     * 改表单时盖上治理字段
     *
     * @param updateObj 待更新的 DO
     * @param current   改动前从库里读到的那一份（提供旧来源与已核过的版本）
     * @param operator  本次操作的入口来源
     */
    private void stampGovernanceOnUpdate(FormDesignDO updateObj, FormDesignDO current,
                                         ArtifactSourceEnum operator) {
        // 来源是出生证明：人工改 AI 建的表单，它仍然是 AI 建的，只是多了「被人接管过」的标记。
        // 置 null 而不是回填旧值 —— MP 的字段策略是 NOT_NULL，null 就不进 SET 子句
        updateObj.setSource(null);
        // 用【核过的那一份】而不是 VO 里的那一份：validateRecordVersion 已断言两者相等，
        // 但断言写在别处，这里再依赖它就等于把正确性押在「将来没人放宽那道校验」上
        updateObj.setVersion(current.getVersion());
        // 🔴 接管标记是【单调】的：一旦为真就不再回退，见 FormDesignDO 上那一列的注释
        if (operator == ArtifactSourceEnum.MANUAL
                && ArtifactSourceEnum.AI.getSource().equals(current.getSource())) {
            updateObj.setManualEditedFlag(true);
        } else {
            updateObj.setManualEditedFlag(null);
        }
    }

    /**
     * 让「清空」真的清得掉
     *
     * <p>MyBatis-Plus 的字段更新策略默认是 NOT_NULL：DO 上为 null 的字段
     * 根本不会进 SET 子句。对设计器上那几项<b>可以整体删光</b>的配置来说，
     * 这等于「删不掉」—— 用户把详情页页签一条条删完、保存，接口返回成功，
     * 重新打开页签又全回来了（库里那份从来没被动过），
     * 而界面上完全看不出是哪一步没生效。自定义按钮、打印模板同理。
     *
     * <p>没有直接给 DO 上那几列打 {@code updateStrategy = ALWAYS}：那会作用到
     * <b>所有</b>更新路径上，包括发布、下线这些只 set 两三个字段的局部更新 ——
     * 那些路径一旦被 ALWAYS 波及，会把整张表单的设计产物清空。
     * 只在「整份保存」这一条路径上按需补 set，影响面才是收敛的。
     *
     * @param updateObj 待更新的 DO
     * @return 带着「把这些列显式置 NULL」的条件构造器
     */
    private LambdaUpdateWrapper<FormDesignDO> clearableColumnsWrapper(FormDesignDO updateObj) {
        // ⚠️ 这里【刻意不写】版本条件。updateObj 上有非空 version 时，
        //    OptimisticLockerInnerInterceptor 会自己往这个 wrapper 上 apply 一条 "version = {0}"
        //    （3.5.16 的 doOptimisticLocker：msId 以 ".update" 结尾且 ew 非 null 时走这一支），
        //    手写一遍只会让 WHERE 里出现两条同样的条件。
        //    版本为空的情形由 validateRecordVersion 在更早的地方硬拒绝，轮不到这里兜。
        LambdaUpdateWrapper<FormDesignDO> wrapper = new LambdaUpdateWrapper<FormDesignDO>()
                .eq(FormDesignDO::getId, updateObj.getId());
        // 条件都是「这一次没传值」——传了值的走 updateObj 自己那一支，不重复 set
        wrapper.set(updateObj.getListConf() == null, FormDesignDO::getListConf, null);
        wrapper.set(updateObj.getLogicRules() == null, FormDesignDO::getLogicRules, null);
        wrapper.set(updateObj.getValidateRules() == null, FormDesignDO::getValidateRules, null);
        wrapper.set(updateObj.getCustomButtons() == null, FormDesignDO::getCustomButtons, null);
        wrapper.set(updateObj.getDetailTabs() == null, FormDesignDO::getDetailTabs, null);
        wrapper.set(updateObj.getPrintTemplate() == null, FormDesignDO::getPrintTemplate, null);
        wrapper.set(updateObj.getFormScript() == null, FormDesignDO::getFormScript, null);
        wrapper.set(updateObj.getRemark() == null, FormDesignDO::getRemark, null);
        return wrapper;
    }

    /**
     * 计算更新后的状态
     *
     * 草稿仍是草稿；已发布及已发布有改动，统一变为「已发布有改动」。
     */
    private Integer nextStatusOnUpdate(Integer oldStatus) {
        if (Objects.equals(oldStatus, FormDesignStatusEnum.DRAFT.getStatus())) {
            return FormDesignStatusEnum.DRAFT.getStatus();
        }
        return FormDesignStatusEnum.PUBLISHED_MODIFIED.getStatus();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteFormDesign(Long id) {
        FormDesignDO formDesign = validateFormDesignExists(id);
        // 已关联菜单的表单不允许直接删除：system_menu 里的菜单与按钮权限只能由「下线」流程
        // （unpublish）把编号交回前端、由前端调 /system/menu/delete-list 回收，
        // infra 无法反向依赖 system 去删。放行删除的话，菜单会残留并指向一个已不存在的表单
        if (formDesign.getMenuId() != null
                || !formDesignMenuMapper.selectListByDesignId(id).isEmpty()) {
            throw exception(FORM_DESIGN_DELETE_FAIL_BIND_MENU);
        }
        // 删除本模块自己的数据：版本快照与菜单关联记录
        formDesignVersionMapper.delete(new LambdaQueryWrapperX<FormDesignVersionDO>()
                .eq(FormDesignVersionDO::getDesignId, id));
        formDesignMenuMapper.deleteByDesignId(id);
        // 🔴 软删的同时把 code 让出来。uk_code 是<b>不含 deleted</b> 的全局唯一索引，
        //    只置 deleted=1 的话这条墓碑会一直占着编码，而页面上查不到它
        //   （selectByCode 自带 deleted=0）—— 于是「删掉表单、用同一个编码重建」必然撞
        //    Duplicate entry，AI 建表流程要走到 form_save_draft 才炸，用户看到的是一串 SQL 异常
        // 带上 version：上面那道「没绑菜单」的判据是拿刚读到的那一行判的，
        // 版本条件保证「我看到的」与「我要删的」是同一行，见 mapper 上的注释
        if (formDesignMapper.updateToDelete(id, tombstoneCode(formDesign.getCode(), id),
                formDesign.getVersion()) == 0) {
            throw exception(FORM_DESIGN_RECORD_VERSION_CONFLICT);
        }
        // 7. 注销发布时登记的数据集资源。必须在 updateToDelete 成功之后广播，
        //    并且用的是<b>改写前的原编码</b> —— 锚点的权限串是拿原编码建的，
        //    传墓碑编码会一条都找不到，于是静默地什么都不做。
        //    漏掉这一步的后果（游离锚点 + 编码被重新占用时继承死表单的权限配置）
        //    见 FormDesignDeletedEvent 的类注释
        eventPublisher.publishEvent(new FormDesignDeletedEvent(formDesign.getCode()));
    }

    /**
     * 生成墓碑编码：{@code <原编码>_del_<id>}，超出列宽时截原编码
     *
     * <p>带 id 而不是只加个固定后缀：同一个编码可能被反复「建了删、删了建」，
     * 固定后缀会让第二个墓碑撞唯一索引 —— 而那正是 AI 反复重试时的路径。
     */
    static String tombstoneCode(String code, Long id) {
        String suffix = TOMBSTONE_CODE_SUFFIX + id;
        int keep = CODE_MAX_LENGTH - suffix.length();
        return (code.length() > keep ? code.substring(0, keep) : code) + suffix;
    }

    @Override
    public FormDesignDO getFormDesign(Long id) {
        return formDesignMapper.selectById(id);
    }

    @Override
    public FormDesignDO validateFormDesignExists(Long id) {
        FormDesignDO formDesign = formDesignMapper.selectById(id);
        if (formDesign == null) {
            throw exception(FORM_DESIGN_NOT_EXISTS);
        }
        return formDesign;
    }

    @Override
    public FormDesignDO getFormDesignByCode(String code) {
        if (StrUtil.isBlank(code)) {
            return null;
        }
        return formDesignMapper.selectByCode(code);
    }

    @Override
    public PageResult<FormDesignDO> getFormDesignPage(FormDesignPageReqVO pageReqVO) {
        return formDesignMapper.selectPage(pageReqVO);
    }

    @Override
    public List<FormDesignDO> getFormDesignSimpleList() {
        return formDesignMapper.selectList(new LambdaQueryWrapperX<FormDesignDO>()
                .orderByDesc(FormDesignDO::getId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long copyFormDesign(Long id) {
        FormDesignDO source = validateFormDesignExists(id);

        FormDesignDO target = BeanUtils.toBean(source, FormDesignDO.class);
        target.setId(null);
        target.setName(source.getName() + "（副本）");
        target.setCode(generateCopyCode(source.getCode()));
        // 副本是全新的草稿：不继承线上版本号、菜单与版本历史
        target.setStatus(FormDesignStatusEnum.DRAFT.getStatus());
        target.setPublishedVersion(0);
        target.setMenuId(null);
        // 清空 BaseDO 的审计字段，让自动填充重新赋值
        target.setCreator(null);
        target.setCreateTime(null);
        target.setUpdater(null);
        target.setUpdateTime(null);
        // schemaVersion 有意随 BeanUtils 从源表单原样继承，【不】盖成当前版本：
        // 副本里的 formFields / tableBinding 是源表单那一版结构的数据，
        // 盖上当前版本号等于对数据结构撒谎，迁移器会跳过它，反而留下一张读不了的表单
        // 🔴 治理字段则【必须】重置：BeanUtils 会把源表单那一行的 version / source /
        //    manualEditedFlag 一并搬过来。version 不重置的话，副本从出生起就带着别人的版本号，
        //    而它自己是全新的一行 —— 第一个改它的人会撞上一次莫名其妙的版本冲突；
        //    source 不重置的话，「复制一张 AI 建的表单」会得到一张仍然标着 AI 的表单，
        //    而它其实是一个人在界面上点出来的
        target.setSource(ArtifactSourceEnum.MANUAL.getSource());
        target.setManualEditedFlag(false);
        target.setSchemaFingerprint(null);
        target.setVersion(INITIAL_VERSION);
        formDesignMapper.insert(target);
        return target.getId();
    }

    /**
     * 生成不冲突的副本编码
     *
     * 依次尝试 xxx_copy、xxx_copy2、xxx_copy3…… 直到不冲突。
     */
    private String generateCopyCode(String sourceCode) {
        String candidate = sourceCode + COPY_CODE_SUFFIX;
        if (formDesignMapper.selectByCode(candidate) == null) {
            return candidate;
        }
        for (int i = 2; i < COPY_CODE_MAX_RETRY; i++) {
            candidate = sourceCode + COPY_CODE_SUFFIX + i;
            if (formDesignMapper.selectByCode(candidate) == null) {
                return candidate;
            }
        }
        throw exception(FORM_DESIGN_CODE_DUPLICATE, candidate);
    }

    @Override
    public void validateCodeUnique(Long id, String code) {
        if (!isCodeAvailable(id, code)) {
            throw exception(FORM_DESIGN_CODE_DUPLICATE, code);
        }
    }

    @Override
    public boolean isCodeAvailable(Long id, String code) {
        if (StrUtil.isBlank(code)) {
            return true;
        }
        FormDesignDO exists = formDesignMapper.selectByCode(code);
        if (exists == null) {
            return true;
        }
        // 更新场景下，命中自身不算重复
        return id != null && Objects.equals(exists.getId(), id);
    }

    /**
     * VO 转 DO
     *
     * formFields 在 VO 里是 List<String>（form-create 逐条序列化的规则），
     * 在 DO 里存为整份 JSON 字符串，因此需要显式转换。
     */
    private FormDesignDO convertToDO(FormDesignSaveReqVO reqVO) {
        FormDesignDO formDesign = BeanUtils.toBean(reqVO, FormDesignDO.class);
        formDesign.setFormFields(FormFieldsCodec.write(reqVO.getFormFields()));
        // 保存动作产出的就是当前结构的数据，因此盖上当前 Schema 版本号。
        // 放在这里而不是各个调用点：新建与更新都走本方法，是唯一的收口位置
        formDesign.setSchemaVersion(FormDesignSchema.CURRENT_VERSION);
        // 打印模板是设计者写的一段 HTML，落库前按白名单清洗一遍，理由与富文本字段一样。
        // 放在这里同样是因为它是唯一的收口位置 —— 漏在某条保存路径上的话，
        // 那条路径存进去的模板会带着未清洗的内容，而打印预览时它就在页面上执行了
        formDesign.setPrintTemplate(richContentGuard.purifyHtml(reqVO.getPrintTemplate()));
        // 扩展模式与超时动作补默认值。前端可能不传这两个字段（老页面、或只改了别的东西），
        // 而列是 NOT NULL —— 不补会直接写库失败。
        // 默认值取自枚举而不是这里再写一遍字面量，避免与 DDL 的 DEFAULT 各自漂移
        if (formDesign.getExtensionMode() == null) {
            formDesign.setExtensionMode(FormExtensionModeEnum.DEFAULT.getMode());
        }
        if (formDesign.getSubmitHookTimeoutAction() == null) {
            formDesign.setSubmitHookTimeoutAction(SubmitHookTimeoutActionEnum.DEFAULT.getAction());
        }
        return formDesign;
    }

    @Override
    public List<String> inspect(Long id) {
        FormDesignDO formDesign = validateFormDesignExists(id);
        // 走发布校验的同一份逻辑，只是不抛异常。
        // ⚠️ 它会清一次表结构元数据缓存（发布校验的既有行为）——
        // 体检是用户手点的低频动作，这个代价换的是「看到的是最新结构」
        return publishValidator.inspect(formDesign);
    }

    // ==================== 发布与下线 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FormDesignPublishRespVO publish(FormDesignPublishReqVO reqVO) {
        FormDesignDO formDesign = validateFormDesignExists(reqVO.getId());

        // 1. 校验配置与数据库元数据。「只绑定已有表」方案下表结构由 DBA 掌控，
        //    必须真去核实表与列存在，否则错误会推迟到运行时才暴露
        publishValidator.validate(formDesign);

        // 2. 首次发布必须指定上级菜单
        boolean firstPublish = formDesign.getMenuId() == null;
        if (firstPublish && reqVO.getParentMenuId() == null) {
            throw exception(FORM_DESIGN_MENU_PARENT_REQUIRED);
        }

        // 2.5 首次发布到 App 端必须指定上级分组。判据与菜单同构（各自的 id 是否为 null），
        //     且同样放在任何写操作之前：校验失败就该一行不落，而不是靠事务回滚兜底
        boolean publishToApp = Boolean.TRUE.equals(reqVO.getPublishToApp());
        boolean firstAppPublish = formDesign.getAppNavNodeId() == null;
        if (publishToApp && firstAppPublish && reqVO.getAppParentNavNodeId() == null) {
            throw exception(FORM_DESIGN_APP_NAV_PARENT_REQUIRED);
        }

        // 3. 存版本快照
        Integer newVersion = versionService.createVersion(formDesign, reqVO.getChangeSummary());

        // 4. 更新状态与线上版本号
        FormDesignDO updateObj = new FormDesignDO();
        updateObj.setId(formDesign.getId());
        updateObj.setStatus(FormDesignStatusEnum.PUBLISHED.getStatus());
        updateObj.setPublishedVersion(newVersion);
        updateByIdWithLock(updateObj, formDesign);

        // 5. 组装建菜单所需配置，交给前端去调 /system/menu/create
        FormDesignPublishRespVO respVO = new FormDesignPublishRespVO();
        respVO.setVersion(newVersion);
        respVO.setNeedCreateMenu(firstPublish);
        if (firstPublish) {
            respVO.setMenuConfig(buildMenuConfig(formDesign, reqVO));
        }

        // 5.5 组装 App 导航节点配置，交给前端去调 /system/nav-node/create 或 update。
        //     已有节点时也给配置：表单改名后 App 入口的名字要跟着变，前端拿它走更新
        respVO.setNeedCreateNavNode(publishToApp && firstAppPublish);
        if (publishToApp) {
            respVO.setNavNodeConfig(buildNavNodeConfig(formDesign, reqVO));
        }

        // 6. 广播发布事件：权限模块据此登记数据集/敏感列/字段资源与语义槽位
        //
        // ⚠️ 监听方是同步的、跑在本事务内，失败会让整个发布回滚。这是刻意的 ——
        // 「表单发布成功了、但它的权限资源没登记」属于本项目最贵的缺陷形态
        //    （页面一切正常，只有行级/字段级权限悄悄全都不生效，没有任何提示）。
        // 宁可发布失败得明明白白。详见 FormDesignPublishedEvent 的类注释
        publishResourceEvent(formDesign);
        return respVO;
    }

    @Override
    public FormDesignMenuPreviewRespDTO previewPublishMenu(Long id, Long parentMenuId) {
        FormDesignDO formDesign = validateFormDesignExists(id);

        FormDesignMenuPreviewRespDTO resp = new FormDesignMenuPreviewRespDTO();
        resp.setCode(formDesign.getCode());
        resp.setName(formDesign.getName());
        resp.setExistingMenuId(formDesign.getMenuId());

        // 🔴 与 publish 第 2 步同一个判据、同一行写法。两处判据一旦分叉，
        //    表现就是「预演说会建菜单、真发布没建」（或反过来），
        //    而这两句话是并排出现在同一个审批详情里的
        boolean needCreateMenu = formDesign.getMenuId() == null;
        resp.setNeedCreateMenu(needCreateMenu);
        if (!needCreateMenu) {
            return resp;
        }

        // publish 在这种情况下抛 FORM_DESIGN_MENU_PARENT_REQUIRED；预演刻意不抛 ——
        // 清单要做的恰恰是在用户点同意之前把这句说出来，而不是让他执行之后看见报错。
        // ⚠️ 只判 null，不判 0：publish 的判据是 reqVO.getParentMenuId() == null，
        //    而 0L 是「挂到顶层菜单」的合法写法（buildMenuPath 把 null 与 0 同等对待）。
        //    把 0 也算成「没给」的话，清单会对一个合法输入喊「执行时会被拒」——
        //    误报和漏报一样会让用户不再相信清单
        resp.setParentMenuMissing(parentMenuId == null);

        FormDesignPublishReqVO reqVO = new FormDesignPublishReqVO();
        reqVO.setId(id);
        reqVO.setParentMenuId(parentMenuId);
        resp.setMenuConfig(BeanUtils.toBean(buildMenuConfig(formDesign, reqVO),
                FormDesignPublishRespDTO.MenuConfig.class));
        return resp;
    }

    /**
     * 广播「表单的可授权对象清单」
     *
     * 主表未绑定时不广播：没有物理表就没有数据集可言，也就没有可登记的资源。
     * 正常路径走不到这里（发布校验会先拦下），留这个判断是为了让本方法
     * 在被别的入口复用时也安全
     */
    private void publishResourceEvent(FormDesignDO formDesign) {
        FormResourceDescriptorDTO descriptor = resourceDescriptorBuilder.build(formDesign);
        if (descriptor == null) {
            log.warn("[publishResourceEvent][表单({})没有绑定主表，跳过权限资源登记]", formDesign.getCode());
            return;
        }
        eventPublisher.publishEvent(new FormDesignPublishedEvent(descriptor));
    }

    /**
     * 组装菜单配置
     *
     * 路由指向运行时渲染页，用 code 而非 id 作为路径参数 ——
     * code 对用户可读，且导出导入到别的环境时 id 会变、code 不变。
     */
    private FormDesignPublishRespVO.MenuConfig buildMenuConfig(FormDesignDO formDesign,
                                                               FormDesignPublishReqVO reqVO) {
        FormDesignPublishRespVO.MenuConfig config = new FormDesignPublishRespVO.MenuConfig();
        config.setName(formDesign.getName());
        config.setPath(buildMenuPath(formDesign.getCode(), reqVO.getParentMenuId()));
        config.setComponent(RUNTIME_COMPONENT);
        config.setComponentName(buildComponentName(formDesign.getCode()));
        config.setParentId(reqVO.getParentMenuId());
        config.setSort(reqVO.getMenuSort() != null ? reqVO.getMenuSort() : formDesign.getSort());
        config.setIcon(DEFAULT_MENU_ICON);

        // 八个按钮权限：查询/创建/更新/删除/导出/导入/被引用/查看明文
        //
        // ⚠️ 导出与导入这两个不是「可选按钮」，运行时接口硬性要求它们：
        // FormDesignRuntimeController 的 export / import 端点校验的权限串就是
        // infra:form-design-run:{code}:export 与 :import。这里不建，
        // 超级管理员走「全部权限」语义看不出问题，而普通管理员点导出必然 403 ——
        // 表现是「按钮点了没反应」，看不出是权限问题。
        //
        // ⚠️ reference（被引用）也必须在这里建，理由是同一条但更隐蔽：
        // GrantDecisionService.hasPermission 对【资源行不存在】直接返回 false，
        // 所以不建这一行的话，管理员想收紧或放开「本表单能不能被别的表单关联」
        // 时**根本没有可授权的对象**，而 S8 之前也没有界面能现场补建。
        // 它不是一个用户点得到的按钮，但它是一个要能被授权的东西
        //
        // ⚠️ view-plain（查看明文）同理，但它的方向是反的 —— 它不是「不建就没法收紧」，
        // 而是「不建就人人可看」。plain-field 端点的全部意义就是绕过脱敏，
        // 只挂 update 的话，任何能改这条数据的人都能还原真手机号，
        // 而「能改」与「能看明文」在真实组织里往往不是同一批人
        // （客服能改联系方式，但明文号码只给风控主管）。
        //
        // 🔴 它是 BUSINESS_VERB 档（PermissionStringResolver：末段动词不在映射表里），
        //    所以【不塌缩】、自成一行资源、动作维度恒为 VIEW。
        //    这是刻意的：塌缩会把它并进 (表单身份, VIEW) —— 于是「能查列表」就等于「能看明文」，
        //    恰好是本次要拆开的那件事。别把它加进 VERB_TO_ACTION「顺手规整」。
        //
        // ⚠️ 本改动只对【此后首次发布】的表单生效：菜单只在 firstPublish 时建。
        //    存量已发布表单没有这一行，其「查看明文」按钮会被前端 v-hasPermi 隐藏
        //    （不是点了报 403）。要恢复能力需重新走一次发布建菜单，或手工补按钮资源
        List<FormDesignPublishRespVO.PermissionConfig> permissions = new ArrayList<>();
        String[][] actions = {
                {"query", "查询"}, {"create", "新增"}, {"update", "修改"}, {"delete", "删除"},
                {"export", "导出"}, {"import", "导入"}, {"reference", "被引用"},
                {"view-plain", "查看明文"}
        };
        for (int i = 0; i < actions.length; i++) {
            FormDesignPublishRespVO.PermissionConfig perm = new FormDesignPublishRespVO.PermissionConfig();
            perm.setName(formDesign.getName() + actions[i][1]);
            perm.setPermission(String.format("infra:form-design-run:%s:%s",
                    formDesign.getCode(), actions[i][0]));
            perm.setSort(i + 1);
            permissions.add(perm);
        }
        config.setPermissions(permissions);
        return config;
    }

    /**
     * 组装 App 导航节点配置
     *
     * <p>permissions 只给 {@code :query} 一个串，而不是把八个按钮权限都塞进去：
     * nav_node.permissions 的语义是「逗号分隔、任一命中即可见」，塞八个会变成
     * 「有任一权限就显示」—— 而我们要的恰恰是「能查列表的人才看得到入口」。
     * 新增/修改/删除按钮在运行页里再按各自的权限串单独判显隐。
     */
    private FormDesignPublishRespVO.NavNodeConfig buildNavNodeConfig(FormDesignDO formDesign,
                                                                     FormDesignPublishReqVO reqVO) {
        FormDesignPublishRespVO.NavNodeConfig config = new FormDesignPublishRespVO.NavNodeConfig();
        config.setName(formDesign.getName());
        config.setParentId(reqVO.getAppParentNavNodeId());
        config.setNodeType(NAV_NODE_TYPE_ITEM);
        config.setRoute(APP_NAV_ROUTE);
        config.setQuery("code=" + formDesign.getCode());
        config.setPermissions(String.format("infra:form-design-run:%s:query", formDesign.getCode()));
        config.setSort(reqVO.getAppMenuSort() != null ? reqVO.getAppMenuSort() : formDesign.getSort());
        config.setIcon(APP_NAV_ICON);
        config.setIconColor("");
        return config;
    }

    /**
     * 生成运行时菜单的路由 path
     *
     * <p>🔴 顶层与嵌套的路径形态<b>必须不同</b>，否则菜单在库里、点进去却白屏：
     * <ul>
     *   <li>挂在某个目录（parentId 非空且非 0）下时，用<b>相对</b>路径 {@code form-design-run/{code}}，
     *       前端 routerHelper 会把它拼到父目录 path 后面（{@code /父目录/form-design-run/{code}}）。</li>
     *   <li>挂在顶层（parentId 为空或 0）时，前端 routerHelper.generateRoute 会把这条
     *       「parentId=0 且带 component 的非目录菜单」包成一个 {@code Layout} 根路由，
     *       并<b>直接拿 path 当根路由地址</b>。vue-router 要求根路由必须以 {@code /} 开头，
     *       相对路径会抛 {@code Route paths should start with a "/"}，
     *       表现是「菜单在库里但界面点不开」。所以顶层必须给<b>绝对</b>路径 {@code /form-design-run/{code}}。</li>
     * </ul>
     * 运行时渲染页（render/index.vue）从路由末段兜底解析 code，绝对/相对都能取到，无需区分。
     *
     * @param code         表单编码
     * @param parentMenuId 上级菜单编号；null 或 0 表示挂到顶层
     * @return 顶层返回绝对路径，嵌套返回相对路径
     */
    private String buildMenuPath(String code, Long parentMenuId) {
        boolean topLevel = parentMenuId == null || parentMenuId == 0L;
        return topLevel ? "/" + MENU_PATH_PREFIX + code : MENU_PATH_PREFIX + code;
    }

    /**
     * 由 code 生成组件名，如 customer_order → FormDesignRunCustomerOrder
     *
     * 组件名要求唯一且符合 PascalCase，前端 keep-alive 依赖它。
     */
    private String buildComponentName(String code) {
        StringBuilder sb = new StringBuilder("FormDesignRun");
        for (String part : code.split("[_-]")) {
            if (StrUtil.isNotBlank(part)) {
                sb.append(StrUtil.upperFirst(part.toLowerCase()));
            }
        }
        return sb.toString();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void bindMenu(Long designId, Long menuId, List<Long> permissionMenuIds) {
        FormDesignDO formDesign = validateFormDesignExists(designId);

        // 主表记录菜单编号，便于判断是否已发布过
        FormDesignDO updateObj = new FormDesignDO();
        updateObj.setId(designId);
        updateObj.setMenuId(menuId);
        updateByIdWithLock(updateObj, formDesign);

        // 关联表登记所有创建的菜单记录，供下线时精确回收
        FormDesignMenuDO menuRecord = new FormDesignMenuDO();
        menuRecord.setDesignId(designId);
        menuRecord.setMenuId(menuId);
        menuRecord.setMenuType(MENU_TYPE_MENU);
        formDesignMenuMapper.insert(menuRecord);

        if (permissionMenuIds != null) {
            for (Long permMenuId : permissionMenuIds) {
                FormDesignMenuDO permRecord = new FormDesignMenuDO();
                permRecord.setDesignId(designId);
                permRecord.setMenuId(permMenuId);
                permRecord.setMenuType(MENU_TYPE_BUTTON);
                formDesignMenuMapper.insert(permRecord);
            }
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void bindAppNav(Long designId, Long navNodeId) {
        FormDesignDO formDesign = validateFormDesignExists(designId);

        // 登记的是非空值，走 updateByIdWithLock 即可（NOT_NULL 策略只吞 null）
        FormDesignDO updateObj = new FormDesignDO();
        updateObj.setId(designId);
        updateObj.setAppNavNodeId(navNodeId);
        updateByIdWithLock(updateObj, formDesign);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FormDesignUnpublishRespVO unpublish(Long id) {
        FormDesignDO formDesign = validateFormDesignExists(id);
        if (formDesign.getPublishedVersion() == null || formDesign.getPublishedVersion() == 0) {
            throw exception(FORM_DESIGN_NOT_PUBLISHED);
        }

        // 收集需要前端回收的菜单编号。
        // ⚠️ 这个列表的顺序**不是**任意的，调用方必须知道：bindMenu 先插父菜单再插按钮，
        //    selectListByDesignId 不带 ORDER BY，InnoDB 走 design_id 的二级索引 ⟹ 按主键升序返回，
        //    所以 menu_type=2 的父菜单永远排在第一个。
        //    前端因此只能整批交给 /system/menu/delete-list（它的判据排除了同批选中的子菜单），
        //    绝不能逐个调 /system/menu/delete —— 单删见「还有子菜单」就抛 MENU_EXISTS_CHILDREN，
        //    父菜单第一个必然失败，7 个按钮随后删光，侧边栏里留下一棵孤儿父菜单且不报错。
        List<FormDesignMenuDO> menuRecords = formDesignMenuMapper.selectListByDesignId(id);
        List<Long> menuIds = menuRecords.stream().map(FormDesignMenuDO::getMenuId).toList();
        // 导航节点是叶子、没有子节点，前端可以单删；这里只把编号交还出去
        Long appNavNodeId = formDesign.getAppNavNodeId();

        // 清空本模块的菜单关联与线上版本号
        formDesignMenuMapper.deleteByDesignId(id);
        FormDesignDO updateObj = new FormDesignDO();
        updateObj.setId(id);
        updateObj.setStatus(FormDesignStatusEnum.DRAFT.getStatus());
        updateObj.setPublishedVersion(0);
        updateObj.setVersion(formDesign.getVersion());
        // 🔴 menu_id 只能用 wrapper 显式置 NULL，写成 setMenuId(null) 是【什么都不做】：
        //    MP 的字段更新策略是 NOT_NULL，实体上为 null 的列根本不进 SET 子句。
        //    本类 updateFormDesign 里那句 setMenuId(null) 表达的正是「这一列别动」——
        //    同一个写法在这条路径上被当成「清空」用，于是下线之后 menu_id 一直挂着一个
        //    已经删掉的菜单编号，形不成任何报错。
        //
        //    后果是一个用户走不出来的死循环，实测复现：
        //      删除 → deleteFormDesign 的判据含 menu_id != null ⟹ 抛 BIND_MENU，
        //             前端据此提示「请先在更多中执行下线」；
        //      下线 → published_version 已被上一次下线归 0 ⟹ 抛 NOT_PUBLISHED。
        //    两条路互相指对方，这张表单永远删不掉。库里就是这么留下 12 行悬挂的。
        //    同一个悬挂还会让 publish 判错 firstPublish（它用 menuId == null 判定），
        //    于是「重新发布」也不再建菜单，而库里那些菜单早已 deleted=1。
        //
        //    不给 DO 上这一列打 updateStrategy = ALWAYS：clearableColumnsWrapper 的注释
        //    已说明那会波及所有局部更新路径，把整份设计产物清空。
        //
        // 🔴 app_nav_node_id 与 menu_id 是同一条红线、同一个写法：漏掉这句 .set 的话，
        //    下线之后 App 入口还挂在移动端导航树上，点进去是一个已下线的表单。
        //    回归守卫见 FormDesignUnpublishTest。
        //
        // ⚠️ 版本条件【刻意不写进 wrapper】：updateObj 上带了非空 version，
        //    OptimisticLockerInnerInterceptor 会自己往这个 wrapper 上 apply "version = {0}"
        //    （msId 以 .update 结尾且 ew 非 null 时走这一支），手写一遍 WHERE 里会出现两条。
        //    这也是这里不能再用 updateByIdWithLock 的原因：它走 updateById，够不着 wrapper。
        //    影响行数照旧必须查 —— 拦截器在冲突时不抛异常，只是让 UPDATE 命中 0 行。
        if (formDesignMapper.update(updateObj, new LambdaUpdateWrapper<FormDesignDO>()
                .eq(FormDesignDO::getId, id)
                .set(FormDesignDO::getMenuId, null)
                .set(FormDesignDO::getAppNavNodeId, null)) == 0) {
            throw exception(FORM_DESIGN_RECORD_VERSION_CONFLICT);
        }

        FormDesignUnpublishRespVO respVO = new FormDesignUnpublishRespVO();
        respVO.setMenuIds(menuIds);
        respVO.setAppNavNodeId(appNavNodeId);
        return respVO;
    }

}
