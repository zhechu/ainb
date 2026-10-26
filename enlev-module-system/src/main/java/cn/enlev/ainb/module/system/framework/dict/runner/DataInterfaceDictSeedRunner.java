package cn.enlev.ainb.module.system.framework.dict.runner;

import cn.hutool.core.collection.CollUtil;
import cn.enlev.ainb.framework.common.enums.CommonStatusEnum;
import cn.enlev.ainb.framework.common.util.object.BeanUtils;
import cn.enlev.ainb.module.system.controller.admin.dict.vo.data.DictDataSaveReqVO;
import cn.enlev.ainb.module.system.controller.admin.dict.vo.type.DictTypeSaveReqVO;
import cn.enlev.ainb.module.system.dal.dataobject.dict.DictDataDO;
import cn.enlev.ainb.module.system.dal.dataobject.dict.DictTypeDO;
import cn.enlev.ainb.module.system.enums.dict.DictCacheModeEnum;
import cn.enlev.ainb.module.system.enums.dict.DictTypeNodeEnum;
import cn.enlev.ainb.module.system.service.dict.DictDataService;
import cn.enlev.ainb.module.system.service.dict.DictTypeService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 「接口管理」（原名「数据接口」）两个字典的种子 Runner
 *
 * <h3>为什么是 Runner 而不是 sql/mysql/xpy-*.sql</h3>
 *
 * 前端字典是<b>带版本号的本地缓存</b>：登录后先问后端要 {@code system:dict:version}，
 * 与自己 localStorage 里那份一致就不重新拉。而那个版本号只在字典的增删改<b>接口</b>
 * 里递增（{@code DictCacheRedisDAO#clear}）。
 *
 * 直接 INSERT 进 system_dict_data 的后果是：库里有、后端接口查得到，
 * 但前端永远拿不到 —— 表现为下拉框一个选项都没有，且要清浏览器缓存才会好。
 * 所以这里走 {@link DictTypeService} / {@link DictDataService}，让缓存自己失效。
 * 菜单没有这层缓存，改名仍旧走 SQL（见 xpy-2026-10-15-menu-rename.sql）。
 *
 * <h3>幂等口径：两个字典刻意用不同的判据</h3>
 *
 * <ul>
 *   <li><b>来源</b>是「一次性迁移 + 缺项补齐」。命中旧值说明这个环境还没迁移过，
 *       整组换成 internal/external；迁移过的环境只在缺项时补，管理员后来加的项不动。
 *       判据一旦为假就永久为假，不会每次启动都改一遍。</li>
 *   <li><b>标签</b>是「类型不存在才建，建的时候才撒种子」。类型已存在就完全不碰字典项 ——
 *       标签是业务自己长出来的词表，重启后把管理员删掉的项复活是不可接受的。</li>
 * </ul>
 *
 * <h3>为什么不放在 infra 模块</h3>
 *
 * 字典表（system_dict_type / system_dict_data）与它的缓存都属于 system，
 * 而 infra 不能反向依赖 system（system 已经依赖 infra，再加一条就成环）。
 *
 * <h3>@Order 的取值</h3>
 *
 * 取 7 —— 排在 permission 那族登记/迁移 Runner（1~6）之后、
 * {@code PermissionBenchmarkRunner}（100）之前。
 *
 * <p>⚠️ 与它们<b>没有</b>数据依赖（这里只碰字典两张表），排后面纯粹是为了不撞号：
 * 6 已经被 {@code NativeDatasetRegisterRunner} 占了，两个 Runner 同序时
 * Spring 不保证谁先谁后。这一族 Runner 的序号在本模块里是刻意连号的，
 * 留一个含糊的并列迟早会被下一个人当成「它们之间有先后关系」去读。
 *
 * @author 走进新时代
 */
@Component
@Order(7)
@Slf4j
public class DataInterfaceDictSeedRunner implements ApplicationRunner {

    /** 接口来源。沿用历史 type 串，改串会让存量 infra_data_interface.category 全部失配 */
    static final String CATEGORY_DICT_TYPE = "infra_form_type";
    /** 接口标签 */
    static final String TAG_DICT_TYPE = "infra_data_interface_tag";

    /** infra 系列字典在左树上的归拢分组，按 type 找而不是写死自增 id */
    private static final String INFRA_GROUP_TYPE = "group_infra";

    /**
     * 字典类型在字典管理里显示的名字
     *
     * <p>改这个常量就等于改名：{@link #ensureDictType} 发现库里名字与它不一致时会直接改过来。
     * 副作用是<b>管理员在字典管理里改不动这个名字</b>——下次启动就被改回来。
     * 这是有意的：它属于代码托管的种子字典，名字要跟界面上的「来源」列对齐，
     * 由人随手改会让两边漂开
     */
    private static final String CATEGORY_DICT_NAME = "接口来源";
    private static final String TAG_DICT_NAME = "接口标签";

    /** 迁移前的来源值。命中任意一个即视为「这个环境还没迁移」 */
    private static final Set<String> LEGACY_CATEGORY_VALUES =
            Set.of("default", "order", "user", "product", "finance", "system", "other");

    private static final String CATEGORY_INTERNAL = "internal";
    private static final String CATEGORY_EXTERNAL = "external";

    /**
     * 标签种子：按接口的<b>消费方</b>分，而不是按业务域分。
     * 业务域已经有「来源」和接口名称在表达，标签再表达一遍就没有筛选价值了
     */
    private static final String[][] TAG_SEEDS = {
            {"form", "表单取数", "primary"},
            {"portal", "门户组件", "success"},
            {"agent", "AI 工具", "warning"},
            {"report", "报表统计", "info"},
            {"integration", "外部集成", "danger"},
            {"common", "通用", "default"}
    };

    @Resource
    private DictTypeService dictTypeService;

    @Resource
    private DictDataService dictDataService;

    @Override
    public void run(ApplicationArguments args) {
        Long infraGroupId = resolveInfraGroupId();
        normalizeCategoryDict(infraGroupId);
        seedTagDict(infraGroupId);
    }

    /**
     * 来源：旧的 7 项（默认/订单/用户/商品/财务/系统/其它）收敛成「内部 / 外部」两项
     *
     * <p>存量 infra_data_interface.category 的迁移动作在 SQL 脚本里（那是普通表数据，
     * 没有缓存层，也不需要跨模块访问），这里只管字典本身
     *
     * <h3>加一个新来源（比如 CRM 系统）不需要改这里</h3>
     *
     * 直接在字典管理里给 {@code infra_form_type} 加一项 {@code value=crm}、{@code label=CRM系统}
     * 即可，下拉框与列表的 dict-tag 都是遍历字典项渲染的，自动跟着长出来。
     * 重启后也不会被清掉：下面的判据是「命中旧值」<b>或</b>「internal/external 缺项」，
     * 多出来的第三项两个条件都不触发，走 early return。
     *
     * <p>只有 {@code LEGACY_CATEGORY_VALUES} 里那 7 个串是雷区 —— 拿它们当新 value，
     * 下次启动整组会被判定为「未迁移」而清空重建
     */
    private void normalizeCategoryDict(Long infraGroupId) {
        ensureDictType(CATEGORY_DICT_TYPE, CATEGORY_DICT_NAME, infraGroupId);

        List<DictDataDO> items = dictDataService.getDictDataListByDictType(CATEGORY_DICT_TYPE);
        boolean hasLegacy = items.stream()
                .anyMatch(item -> LEGACY_CATEGORY_VALUES.contains(item.getValue()));
        boolean missingTarget = findValue(items, CATEGORY_INTERNAL) == null
                || findValue(items, CATEGORY_EXTERNAL) == null;
        if (!hasLegacy && !missingTarget) {
            return;
        }

        if (hasLegacy) {
            // 先删后建而不是原地改 value：value 是身份，改它会让引用旧值的快照/日志对不上号，
            // 而删掉一项再建一项在字典树上是同一个效果，代价小得多
            for (DictDataDO item : items) {
                dictDataService.deleteDictData(item.getId(), true);
            }
            log.info("[normalizeCategoryDict][来源字典({})清掉 {} 项历史值]",
                    CATEGORY_DICT_TYPE, items.size());
        }
        createDictDataIfAbsent(CATEGORY_DICT_TYPE, CATEGORY_INTERNAL, "内部", 1, "primary");
        createDictDataIfAbsent(CATEGORY_DICT_TYPE, CATEGORY_EXTERNAL, "外部", 2, "success");
        log.info("[normalizeCategoryDict][来源字典({})已就绪：内部 / 外部]", CATEGORY_DICT_TYPE);
    }

    /** 标签：类型不存在时才建，并撒一次种子 */
    private void seedTagDict(Long infraGroupId) {
        if (dictTypeService.getDictType(TAG_DICT_TYPE) != null) {
            return;
        }
        ensureDictType(TAG_DICT_TYPE, TAG_DICT_NAME, infraGroupId);
        for (int i = 0; i < TAG_SEEDS.length; i++) {
            String[] seed = TAG_SEEDS[i];
            createDictDataIfAbsent(TAG_DICT_TYPE, seed[0], seed[1], i + 1, seed[2]);
        }
        log.info("[seedTagDict][新建标签字典({})，撒入 {} 个种子标签]", TAG_DICT_TYPE, TAG_SEEDS.length);
    }

    private Long resolveInfraGroupId() {
        DictTypeDO group = dictTypeService.getDictType(INFRA_GROUP_TYPE);
        return group != null ? group.getId() : null;
    }

    /**
     * 保证字典类型存在，且名字是我们要的那个
     *
     * <p>{@code infra_form_type} 原本叫「基础设施的表单类型」—— 名实不符，
     * 它从来只被接口的「来源」字段用（那个字段在代码里叫 category，界面上现在叫「来源」）。
     * 顺带改对，否则管理员在字典管理里找不到它
     */
    private void ensureDictType(String type, String name, Long parentId) {
        DictTypeDO existing = dictTypeService.getDictType(type);
        if (existing == null) {
            DictTypeSaveReqVO reqVO = new DictTypeSaveReqVO();
            reqVO.setParentId(parentId);
            reqVO.setNodeType(DictTypeNodeEnum.DICT.getType());
            reqVO.setName(name);
            reqVO.setType(type);
            reqVO.setTreeEnable(0);
            reqVO.setCacheMode(DictCacheModeEnum.FULL.getMode());
            reqVO.setStatus(CommonStatusEnum.ENABLE.getStatus());
            dictTypeService.createDictType(reqVO);
            log.info("[ensureDictType][新建字典类型({}) name={}]", type, name);
            return;
        }
        if (Objects.equals(existing.getName(), name)) {
            return;
        }
        // 用 DO 整体拷一份再改名：updateDictType 会校验 treeEnable 不许切换，
        // 手搓 VO 漏掉哪个字段就会被它拦下来
        DictTypeSaveReqVO reqVO = BeanUtils.toBean(existing, DictTypeSaveReqVO.class);
        reqVO.setName(name);
        dictTypeService.updateDictType(reqVO);
        log.info("[ensureDictType][字典类型({})改名 {} → {}]", type, existing.getName(), name);
    }

    private void createDictDataIfAbsent(String dictType, String value, String label,
                                        Integer sort, String colorType) {
        if (dictDataService.getDictData(dictType, value) != null) {
            return;
        }
        DictDataSaveReqVO reqVO = new DictDataSaveReqVO();
        reqVO.setDictType(dictType);
        reqVO.setValue(value);
        reqVO.setLabel(label);
        reqVO.setSort(sort);
        reqVO.setColorType(colorType);
        reqVO.setStatus(CommonStatusEnum.ENABLE.getStatus());
        dictDataService.createDictData(reqVO);
    }

    private DictDataDO findValue(List<DictDataDO> items, String value) {
        return CollUtil.findOne(items, item -> Objects.equals(item.getValue(), value));
    }

}
