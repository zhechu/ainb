package cn.enlev.ainb.module.infra.service.formdesign;

import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.module.infra.api.formdesign.dto.FormDesignMenuPreviewRespDTO;
import cn.enlev.ainb.module.infra.controller.admin.formdesign.vo.FormDesignPageReqVO;
import cn.enlev.ainb.module.infra.controller.admin.formdesign.vo.FormDesignPublishReqVO;
import cn.enlev.ainb.module.infra.controller.admin.formdesign.vo.FormDesignPublishRespVO;
import cn.enlev.ainb.module.infra.controller.admin.formdesign.vo.FormDesignSaveReqVO;
import cn.enlev.ainb.module.infra.controller.admin.formdesign.vo.FormDesignUnpublishRespVO;
import cn.enlev.ainb.module.infra.dal.dataobject.formdesign.FormDesignDO;
import cn.enlev.ainb.module.infra.enums.artifact.ArtifactSourceEnum;
import jakarta.validation.Valid;

import java.util.List;

/**
 * 表单设计 Service 接口
 *
 * 本接口只负责【设计态】：表单配置的 CRUD、复制、状态流转。
 * 发布与版本见 {@link FormDesignVersionService}，运行时读写见 FormDesignRuntimeService。
 *
 * @author 走进新时代
 */
public interface FormDesignService {

    /**
     * 创建表单设计
     *
     * <p>来源记为 {@link ArtifactSourceEnum#MANUAL}。
     *
     * @param createReqVO 创建信息
     * @return 编号
     */
    Long createFormDesign(@Valid FormDesignSaveReqVO createReqVO);

    /**
     * 创建表单设计，并显式指定<b>来源</b>
     *
     * <p>🔴 来源是参数而不是 VO 上的字段：它是「这张表单是谁建的」这一<b>事实</b>，
     * 只能由服务端按入口判定。放进 VO 就变成了调用方自报 ——
     * AI 传个 {@code MANUAL} 就能把自家产物永远洗白成人工创建，
     * 而 {@code manualEditedFlag} 那套治理逻辑随之整体失效，且失效得无声无息。
     * 理由与 {@code DataModelService} 上那个同名重载一致。
     *
     * @param createReqVO 创建信息
     * @param source      来源
     * @return 编号
     */
    Long createFormDesign(@Valid FormDesignSaveReqVO createReqVO, ArtifactSourceEnum source);

    /**
     * 更新表单设计
     *
     * 状态流转：若原状态为「已发布」，更新后会变为「已发布有改动」，
     * 提示用户草稿与线上版本已不一致，需重新发布才能生效。
     *
     * 已发布的表单不允许修改 code —— 运行时与菜单都通过 code 引用本表单。
     *
     * <p>按<b>人工入口</b>处理：若这张表单原本是 AI 建的，本次更新会把它标记为
     * 「已被人工接管」（{@code manualEditedFlag=true}，且此后不再回退）。
     *
     * <p>🔴 {@code updateReqVO.version} <b>必传</b>，取自 {@code GET /infra/form-design/get}。
     * 缺失是硬拒绝而不是「跳过加锁」，理由见 {@code DataModelServiceImpl#validateRecordVersion}。
     *
     * @param updateReqVO 更新信息
     */
    void updateFormDesign(@Valid FormDesignSaveReqVO updateReqVO);

    /**
     * 更新表单设计，并显式指定<b>操作者来源</b>
     *
     * <p>🔴 {@code operator} 只决定「要不要打人工接管标记」，<b>不会</b>改写这条记录的
     * {@code source} —— 来源是出生证明，谁改的都改不掉它。
     *
     * @param updateReqVO 更新信息
     * @param operator    本次操作的入口来源
     */
    void updateFormDesign(@Valid FormDesignSaveReqVO updateReqVO, ArtifactSourceEnum operator);

    /**
     * 删除表单设计
     *
     * <p>⚠️ 已绑定菜单的表单<b>拒绝</b>删除（抛 {@code FORM_DESIGN_DELETE_FAIL_BIND_MENU}）：
     * 菜单与按钮权限只能由「下线」流程（{@link #unpublish}）把编号交回前端、
     * 由前端调 {@code /system/menu/delete-list} 回收，infra 无法反向依赖 system 去删。
     *
     * <p>删除成功后会广播 {@code FormDesignDeletedEvent}，由权限模块注销发布时登记的
     * 数据集资源（含其子资源、槽位映射与授权行）。漏掉这一步会在 {@code sys_resource} 里
     * 留下一条游离锚点，且因为本方法同时把 code 让了出来，新表单重新占用该编码时
     * 会静默继承死表单的 {@code default_effect} 与槽位映射 —— 详见该事件类的注释。
     *
     * @param id 编号
     */
    void deleteFormDesign(Long id);

    /**
     * 获得表单设计
     *
     * @param id 编号
     * @return 表单设计
     */
    FormDesignDO getFormDesign(Long id);

    /**
     * 获得表单设计，不存在时抛出业务异常
     *
     * @param id 编号
     * @return 表单设计
     */
    FormDesignDO validateFormDesignExists(Long id);

    /**
     * 根据编码获得表单设计
     *
     * @param code 表单编码
     * @return 表单设计
     */
    FormDesignDO getFormDesignByCode(String code);

    /**
     * 获得表单设计分页
     *
     * @param pageReqVO 分页查询
     * @return 表单设计分页
     */
    PageResult<FormDesignDO> getFormDesignPage(FormDesignPageReqVO pageReqVO);

    /**
     * 获得表单设计精简列表
     *
     * @return 表单设计列表
     */
    List<FormDesignDO> getFormDesignSimpleList();

    /**
     * 复制表单设计
     *
     * 复制出的新表单为草稿状态，编码追加 _copy 后缀并保证唯一，不继承菜单与版本历史。
     *
     * @param id 被复制的编号
     * @return 新表单编号
     */
    Long copyFormDesign(Long id);

    /**
     * 校验表单编码是否唯一
     *
     * @param id   编号，更新时传入以排除自身
     * @param code 表单编码
     */
    void validateCodeUnique(Long id, String code);

    /**
     * 判断表单编码是否可用
     *
     * 与 {@link #validateCodeUnique} 的区别：本方法返回布尔值供前端做即时校验，
     * 不抛异常。不要用「捕获异常」来实现它 —— 那会把 NPE、DB 故障等意外错误
     * 一并吞掉，误报成「编码已占用」。
     *
     * @param id   编号，更新时传入以排除自身
     * @param code 表单编码
     * @return 可用返回 true
     */
    boolean isCodeAvailable(Long id, String code);

    /**
     * 发布表单
     *
     * 执行：校验配置与元数据 → 存版本快照 → 更新状态与线上版本号 → 返回建菜单所需配置。
     *
     * 注意：本方法【不】创建菜单 —— infra 模块不能反向依赖 system 模块
     * （system 已依赖 infra，反向会造成 Maven 循环依赖）。
     * 菜单由前端拿着返回的 menuConfig 再调 /system/menu/create 创建，
     * 这样能走到真正的 MenuService，@CacheEvict 会正常触发。
     *
     * @param reqVO 发布请求
     * @return 发布结果，含版本号与待创建的菜单配置
     */
    FormDesignPublishRespVO publish(@Valid FormDesignPublishReqVO reqVO);

    /**
     * 记录前端已创建的菜单
     *
     * 前端建完菜单后回调本接口，把菜单编号登记进 infra_form_design_menu，
     * 供后续下线/删除时精确回收。
     *
     * @param designId    表单设计编号
     * @param menuId      菜单编号
     * @param permissionMenuIds 按钮权限的菜单编号列表
     */
    void bindMenu(Long designId, Long menuId, List<Long> permissionMenuIds);

    /**
     * 记录前端已创建的 App 导航节点
     *
     * 与 {@link #bindMenu} 同一条范式：infra 不能反向依赖 system，导航节点由前端
     * 调 /system/nav-node/create 建好后回调本接口登记编号，供下线时精确回收。
     *
     * @param designId   表单设计编号
     * @param navNodeId  导航节点编号
     */
    void bindAppNav(Long designId, Long navNodeId);

    /**
     * 下线表单
     *
     * 清空线上版本号、菜单关联与 App 导航节点编号，状态回到草稿。
     * 菜单与导航节点记录的删除同样由前端完成（infra 不能反向依赖 system），
     * 本方法把需要删除的编号交还调用方。
     *
     * <p>🔴 {@code menuIds} 里<b>父菜单排在第一个</b>（见实现里那段注释）。调用方必须整批交给
     *    {@code /system/menu/delete-list}，不能逐个调 {@code /system/menu/delete}：
     *    单删对「还有子菜单」的菜单直接抛 MENU_EXISTS_CHILDREN，逐个删的结果是父菜单必然残留成孤儿。
     *
     * @param id 编号
     * @return 需要前端回收的菜单编号列表（父菜单在前）与 App 导航节点编号
     */
    FormDesignUnpublishRespVO unpublish(Long id);


    /**
     * 设计期体检：把这张表单的问题一次列全，不改动任何数据
     *
     * 与发布走<b>同一份校验</b>。两条路各写一套的话，「体检说没问题、发布却被拒」
     * 会让用户不知道该信哪个。
     *
     * @param id 表单编号
     * @return 问题清单，空表示可以发布
     */
    List<String> inspect(Long id);

    /**
     * 预演「这次发布会不会建菜单、建成什么样」—— 只读，一行都不写
     *
     * <p>🔴 与 {@link #publish} 的区别不是「少几步」，是<b>有没有副作用</b>：
     * publish 会存版本快照、改 status、在事务内同步广播权限资源登记事件。
     * 本方法只做 publish 的第 2 步判定（{@code menuId == null} 即首发）与第 5 步组装，
     * 不建版本、不改状态、不发事件、不建菜单。
     *
     * <p>存在的理由：变更清单要在用户点同意之前说出「会新建 1 个菜单 + 7 个按钮权限」
     * 还是「不新建」，而这个判据（表单当前有没有绑过菜单）光看 DSL 算不出来。
     *
     * @param id           表单编号，不存在抛 {@code FORM_DESIGN_NOT_EXISTS}
     * @param parentMenuId 上级菜单编号，null 或 0 表示顶层。
     *                     首发时若为 null，本方法<b>不抛</b>，
     *                     而是在结果里置 {@code parentMenuMissing=true} ——
     *                     清单要提前说，而不是等执行时报错
     * @return 预演结果
     */
    FormDesignMenuPreviewRespDTO previewPublishMenu(Long id, Long parentMenuId);

}
