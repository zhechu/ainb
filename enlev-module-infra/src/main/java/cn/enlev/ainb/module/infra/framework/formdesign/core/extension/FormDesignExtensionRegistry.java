package cn.enlev.ainb.module.infra.framework.formdesign.core.extension;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.module.infra.dal.dataobject.formdesign.FormDesignDO;
import cn.enlev.ainb.module.infra.dal.mysql.formdesign.FormDesignMapper;
import cn.enlev.ainb.module.infra.enums.formdesign.FormExtensionModeEnum;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 服务端扩展点注册表与模式路由
 *
 * 负责两件事：
 * <ol>
 *   <li>按 formCode 把提交路由到对应的 {@link FormDesignExtension}，且只在表单处于
 *       「服务端扩展点」模式时才调用 —— 这是互斥语义的执行点</li>
 *   <li>模式错配告警</li>
 * </ol>
 *
 * <h3>为什么模式错配必须有告警</h3>
 *
 * 新建表单的默认模式是「前端脚本」。于是会出现这样一条路径：开发者写好一个
 * {@code FormDesignExtension} 实现、部署上去、以为生效了 —— 而因为那张表单不在
 * SERVER_EXTENSION 模式，钩子一次都不会被调用，并且不会有任何报错。
 *
 * 「静默不执行」是最难排查的一类问题：代码在、Bean 也注册了、日志一片干净，
 * 只是业务逻辑没跑。所以三道告警一道都不能省：
 *
 * <ol>
 *   <li><b>启动期</b>（本类）：扫所有 Bean，逐个查对应表单的模式，不匹配就打 WARN</li>
 *   <li><b>设计期</b>：设计器加载表单时查「是否存在服务端扩展点实现」，
 *       存在但模式不匹配就在属性面板提示并给一键切换（见 Controller 的 extension-status 接口）</li>
 *   <li><b>发布期</b>：选了 SERVER_EXTENSION 却找不到 Bean → 发布直接报错
 *       （见 FormDesignPublishValidator）</li>
 * </ol>
 *
 * 两个方向的处理力度不同是有意的：正向错配（有实现、模式不对）只告警，
 * 因为那个 Bean 可能是给别的环境准备的；反向错配（选了模式、没有实现）直接报错，
 * 因为它一定是配置错误。
 *
 * @author 走进新时代
 */
@Component
@Slf4j
public class FormDesignExtensionRegistry {

    /**
     * 所有扩展点实现
     *
     * ⚠️ 这里必须用 {@code @Autowired(required = false)}，不能用项目惯用的 {@code @Resource}：
     * {@code @Resource} 作用在 List 上是【按名字】查找一个名为 extensions 的 Bean，
     * 而不是按类型收集所有实现，结果是启动直接失败
     * （A component required a bean of type 'java.util.List'）。
     *
     * required = false 同样不能省：绝大多数项目一个扩展点实现都没有，
     * 那是正常状态而不是配置错误，此时字段保持初始的空列表。
     *
     * 用 List 而不是 Map：同一个 formCode 允许有多个实现，按 @Order 串行执行。
     */
    @Value("${enlev.mode:public}")
    private String enlevMode;

    @Autowired(required = false)
    private List<FormDesignExtension> extensions = Collections.emptyList();

    @Resource
    private FormDesignMapper formDesignMapper;

    /**
     * formCode → 该表单的扩展点，已按 @Order 排序
     */
    private Map<String, List<FormDesignExtension>> byFormCode = Collections.emptyMap();

    @PostConstruct
    public void init() {
        if (!"professional".equals(enlevMode)) {
            log.info("[init][enlev.mode={} 非 professional，跳过服务端扩展点加载]", enlevMode);
            return;
        }
        Map<String, List<FormDesignExtension>> map = new LinkedHashMap<>();
        for (FormDesignExtension extension : extensions) {
            String code = extension.formCode();
            if (StrUtil.isBlank(code)) {
                // 不抛异常让应用起不来：一个第三方模块里配错的扩展点，
                // 不该导致整个系统无法启动。但必须让它显眼
                log.error("[init][扩展点 {} 的 formCode() 返回空，它永远不会被调用]",
                        extension.getClass().getName());
                continue;
            }
            map.computeIfAbsent(code, k -> new ArrayList<>()).add(extension);
        }
        // 同一表单的多个实现按 @Order 排序，保证执行顺序确定
        map.values().forEach(list -> list.sort(AnnotationAwareOrderComparator.INSTANCE));
        this.byFormCode = Collections.unmodifiableMap(map);

        if (!byFormCode.isEmpty()) {
            log.info("[init][表单服务端扩展点加载完成，覆盖 {} 张表单：{}]",
                    byFormCode.size(), byFormCode.keySet());
        }
        warnModeMismatch();
    }

    /**
     * 第一道告警：启动期扫描模式错配
     *
     * 有扩展点实现、但表单不在服务端扩展点模式 → WARN。
     *
     * 查库失败时只记日志不抛异常：启动期数据库可能还没就绪（比如容器编排里
     * 应用先起来了），为了一条告警把应用拖挂是不划算的。
     */
    private void warnModeMismatch() {
        if (byFormCode.isEmpty()) {
            return;
        }
        try {
            Set<String> codes = byFormCode.keySet();
            List<FormDesignDO> forms = formDesignMapper.selectListByCodes(codes);
            Map<String, FormDesignDO> formByCode = forms.stream()
                    .collect(Collectors.toMap(FormDesignDO::getCode, f -> f, (a, b) -> a));

            for (String code : codes) {
                FormDesignDO form = formByCode.get(code);
                if (form == null) {
                    log.warn("[warnModeMismatch][扩展点声明的表单编码({})在库里不存在，"
                            + "该扩展点不会被调用。请核对 formCode() 的拼写]", code);
                    continue;
                }
                if (!FormExtensionModeEnum.isServerExtension(form.getExtensionMode())) {
                    FormExtensionModeEnum current =
                            FormExtensionModeEnum.modeOf(form.getExtensionMode());
                    log.warn("[warnModeMismatch][⚠️ 表单({})存在扩展点实现{}，"
                                    + "但当前扩展模式是「{}」，这些扩展点不会被执行。"
                                    + "如需生效，请在表单属性里把扩展模式改为「服务端扩展点」]",
                            code,
                            byFormCode.get(code).stream()
                                    .map(e -> e.getClass().getSimpleName()).toList(),
                            current != null ? current.getName() : form.getExtensionMode());
                }
            }
        } catch (Exception e) {
            log.warn("[warnModeMismatch][扫描扩展点模式错配时出错，跳过。这只影响告警，不影响功能]", e);
        }
    }

    // ==================== 查询 ====================

    /**
     * 某张表单是否存在扩展点实现
     *
     * 给设计期提示用（第二道告警）。
     */
    public boolean hasExtension(String formCode) {
        return formCode != null && byFormCode.containsKey(formCode);
    }

    /**
     * 某张表单的扩展点实现类名，给设计期提示展示用
     */
    public List<String> extensionNames(String formCode) {
        List<FormDesignExtension> list = byFormCode.get(formCode);
        if (list == null) {
            return Collections.emptyList();
        }
        return list.stream().map(e -> e.getClass().getSimpleName()).toList();
    }

    // ==================== 执行 ====================

    /**
     * 执行某个钩子
     *
     * <b>互斥语义的执行点</b>：只有表单处于服务端扩展点模式时才真的调用。
     * 前端脚本模式下即便存在 Bean 也一律跳过 —— 这正是「同一表单只有一种扩展生效」
     * 这条约定落地的地方。
     *
     * @param extensionMode 表单当前的扩展模式
     * @param ctx           提交上下文
     * @param hook          要执行的钩子
     */
    public void invoke(Integer extensionMode, FormSubmitContext ctx,
                       Consumer<FormDesignExtension> hook) {
        if (!FormExtensionModeEnum.isServerExtension(extensionMode)) {
            return;
        }
        List<FormDesignExtension> list = byFormCode.get(ctx.getFormCode());
        if (list == null || list.isEmpty()) {
            return;
        }
        for (FormDesignExtension extension : list) {
            hook.accept(extension);
        }
    }

    /**
     * 是否会有扩展点被真正执行
     *
     * 供调用方决定要不要构造上下文 —— 构造上下文要拷一份数据，没有扩展点时是白拷。
     */
    public boolean willInvoke(Integer extensionMode, String formCode) {
        return FormExtensionModeEnum.isServerExtension(extensionMode) && hasExtension(formCode);
    }

}
