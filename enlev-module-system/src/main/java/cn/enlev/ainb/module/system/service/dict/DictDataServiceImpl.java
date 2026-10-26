package cn.enlev.ainb.module.system.service.dict;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjUtil;
import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.common.enums.CommonStatusEnum;
import cn.enlev.ainb.framework.common.exception.ServiceException;
import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.framework.common.util.collection.CollectionUtils;
import cn.enlev.ainb.framework.common.util.object.BeanUtils;
import cn.enlev.ainb.module.system.controller.admin.dict.vo.data.DictDataExcelVO;
import cn.enlev.ainb.module.system.controller.admin.dict.vo.data.DictDataImportRespVO;
import cn.enlev.ainb.module.system.controller.admin.dict.vo.data.DictDataPageReqVO;
import cn.enlev.ainb.module.system.controller.admin.dict.vo.data.DictDataSaveReqVO;
import cn.enlev.ainb.module.system.dal.dataobject.dict.DictDataDO;
import cn.enlev.ainb.module.system.dal.dataobject.dict.DictTypeDO;
import cn.enlev.ainb.module.system.dal.mysql.dict.DictDataMapper;
import cn.enlev.ainb.module.system.dal.redis.dict.DictCacheRedisDAO;
import cn.enlev.ainb.module.system.dal.redis.dict.DictDataCache;
import cn.enlev.ainb.module.system.enums.dict.DictTypeNodeEnum;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.google.common.annotations.VisibleForTesting;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.framework.common.util.collection.CollectionUtils.convertList;
import static cn.enlev.ainb.framework.common.util.collection.CollectionUtils.convertMap;
import static cn.enlev.ainb.framework.common.util.collection.CollectionUtils.convertSet;
import static cn.enlev.ainb.module.system.enums.ErrorCodeConstants.*;

/**
 * 字典数据 Service 实现类
 *
 * @author ruoyi
 */
@Service
@Slf4j
public class DictDataServiceImpl implements DictDataService {

    /**
     * 树形字典的标记值，对应 {@link DictTypeDO#getTreeEnable()}
     */
    private static final Integer TREE_ENABLED = 1;

    /**
     * 层级上限
     *
     * 树本身不需要这个限制，它防的是误操作：手滑把一棵大树挂到自己下面附近的位置，
     * 或者脚本导入时父子关系错乱，都会让层级失控增长，进而拖垮前端渲染。
     * 真实业务里的层级字典很少超过 5 层。
     */
    private static final int MAX_TREE_LEVEL = 10;

    /**
     * 排序 dictType > sort
     */
    private static final Comparator<DictDataDO> COMPARATOR_TYPE_AND_SORT = Comparator
            .comparing(DictDataDO::getDictType)
            .thenComparingInt(DictDataDO::getSort);

    /**
     * 排序 parentId > sort > id
     *
     * 前端 handleTree 按数组顺序挂子节点，同级顺序全靠这里定。带上 id 兜底，
     * 保证 sort 相同的两个节点在多次请求间顺序稳定，不会跳来跳去
     */
    private static final Comparator<DictDataDO> COMPARATOR_PARENT_AND_SORT = Comparator
            .comparing(DictDataDO::getParentId)
            .thenComparingInt(DictDataDO::getSort)
            .thenComparing(DictDataDO::getId);

    @Resource
    private DictTypeService dictTypeService;

    @Resource
    private DictDataMapper dictDataMapper;

    @Resource
    private DictCacheRedisDAO dictCacheRedisDAO;

    @Override
    public List<DictDataDO> getDictDataList(Integer status, String dictType) {
        List<DictDataDO> list = dictDataMapper.selectListByStatusAndDictType(status, dictType);
        list.sort(COMPARATOR_TYPE_AND_SORT);
        return list;
    }

    @Override
    public List<DictDataDO> getSimpleDictDataList() {
        return getDictDataCache().getList();
    }

    @Override
    public DictDataCache getDictDataCache() {
        // 1. 先读当前版本号。它同时用于校验缓存是否仍然当令，以及回源时给新缓存打戳
        Long version = dictCacheRedisDAO.getVersion();

        // 2. 命中缓存，且版本号仍是当前值，才可以直接返回。
        //    版本对不上说明这份缓存产生之后又发生过写入，当作未命中处理
        DictDataCache cached = dictCacheRedisDAO.getDataCache();
        if (cached != null && version.equals(cached.getVersion())) {
            return cached;
        }

        // 3. 回源。这里不加锁：字典读多写少决定了缓存几乎总是命中，
        //    为极少数并发穿透加锁，代价（锁竞争、死锁风险）比多查几次库更大
        List<DictDataDO> list = getDictDataList(CommonStatusEnum.ENABLE.getStatus(), null);
        // 打的是「查库之前」读到的 version。若查库期间恰好有写入，这份数据带的是偏旧的版本号，
        // 前端下次比对会发现不一致并重新拉取。偏旧只是多刷一次，偏新则会导致永远不刷
        DictDataCache cache = new DictDataCache(version, list);
        dictCacheRedisDAO.setDataCache(cache);
        return cache;
    }

    @Override
    public Long getDictVersion() {
        return dictCacheRedisDAO.getVersion();
    }

    @Override
    public PageResult<DictDataDO> getDictDataPage(DictDataPageReqVO pageReqVO) {
        return dictDataMapper.selectPage(pageReqVO);
    }

    @Override
    public DictDataDO getDictData(Long id) {
        return dictDataMapper.selectById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createDictData(DictDataSaveReqVO createReqVO) {
        // 校验字典类型有效
        DictTypeDO dictType = validateDictTypeExists(createReqVO.getDictType());
        // 校验字典数据的值的唯一性
        validateDictDataValueUnique(null, createReqVO.getDictType(), createReqVO.getValue());
        // 校验父节点有效
        DictDataDO parent = validateParentDictData(null, createReqVO.getDictType(),
                createReqVO.getParentId(), dictType);

        // 插入字典数据
        DictDataDO dictData = BeanUtils.toBean(createReqVO, DictDataDO.class);
        if (dictData.getParentId() == null) {
            dictData.setParentId(DictDataDO.PARENT_ID_ROOT);
        }
        dictData.setTreeLevel(parent == null ? 1 : parent.getTreeLevel() + 1);
        dictDataMapper.insert(dictData);
        // idPath 依赖自增主键，只能在插入拿到 id 之后再补一次。
        // 与其为了省这条 UPDATE 去预取序列（各数据库方言不同、还要处理并发），
        // 不如接受一次额外写入——字典是极低频写。
        // 两条写入必须同事务：只插入而没补上 idPath 的节点，会让后续所有子树操作失准
        dictDataMapper.updateIdPath(dictData.getId(), buildIdPath(parent, dictData.getId()));
        dictCacheRedisDAO.clear();
        return dictData.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateDictData(DictDataSaveReqVO updateReqVO) {
        // 校验自己存在
        DictDataDO oldDictData = validateDictDataExists(updateReqVO.getId());
        // 校验字典类型有效
        DictTypeDO dictType = validateDictTypeExists(updateReqVO.getDictType());
        // 校验字典数据的值的唯一性
        validateDictDataValueUnique(updateReqVO.getId(), updateReqVO.getDictType(), updateReqVO.getValue());
        // 父节点的校验必须赶在任何写入之前。否则「改名 + 挂到非法父级」这种请求会先把名字改掉，
        // 再抛异常——事务虽然会回滚，但校验顺序本身也不该依赖回滚来兜底
        Long newParentId = updateReqVO.getParentId() != null
                ? updateReqVO.getParentId() : DictDataDO.PARENT_ID_ROOT;
        boolean parentChanged = !Objects.equals(oldDictData.getParentId(), newParentId);
        if (parentChanged) {
            validateParentDictData(updateReqVO.getId(), updateReqVO.getDictType(), newParentId, dictType);
        }

        // 更新字典数据。父节点、路径、层级不在这里改：它们的变更会波及整棵子树，
        // 统一交给 moveDictData，免得改树的逻辑散落在两处
        DictDataDO updateObj = BeanUtils.toBean(updateReqVO, DictDataDO.class);
        updateObj.setParentId(null);
        updateObj.setTreeLevel(null);
        updateObj.setIdPath(null);
        // 🔴 remark 要显式写 null：MyBatis-Plus 默认字段策略是 NOT_NULL，实体上为 null 的列
        //    会被【整个从 SET 子句里省掉】—— 那是「别动这列」，不是「清成 null」。
        //    于是「把备注清空」会返回成功、而库里旧备注原样留着。
        //    界面上清空 el-input 发的是空串（空串不是 null，能写进去），所以这个坑从 UI 走不到；
        //    但 API 直调发 null 就会踩上，而上面那三行 setNull 恰恰是<b>靠着</b>这个默认策略
        //    才做到「不改树字段」的 —— 同一个方法里两种意图共用一种机制，只能按列说清楚。
        //    ⚠️ 别改成在 DictDataDO 上打 @TableField(updateStrategy = ALWAYS)：
        //    那会连带改掉 Excel 导入路径（saveImportedDictData 把 remark 原样从表格行拷过来，
        //    没填备注的行会把库里已有的备注清掉），而那与「编辑表单里清空备注」是两件事
        dictDataMapper.update(updateObj, new LambdaUpdateWrapper<DictDataDO>()
                .eq(DictDataDO::getId, updateObj.getId())
                .set(updateObj.getRemark() == null, DictDataDO::getRemark, null));

        if (parentChanged) {
            moveDictData(updateReqVO.getId(), newParentId, updateReqVO.getSort());
            return; // moveDictData 内部已经清过缓存
        }
        dictCacheRedisDAO.clear();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteDictData(Long id, boolean cascade) {
        // 校验是否存在
        DictDataDO dictData = validateDictDataExists(id);

        if (cascade) {
            // 靠 idPath 一次捞出整棵子树，不需要逐层递归
            List<DictDataDO> descendants = dictDataMapper.selectListByIdPathPrefix(dictData.getIdPath());
            if (CollUtil.isNotEmpty(descendants)) {
                dictDataMapper.deleteByIds(convertList(descendants, DictDataDO::getId));
            }
        } else if (dictDataMapper.selectCountByParentId(id) > 0) {
            throw exception(DICT_DATA_HAS_CHILDREN);
        }

        // 删除字典数据。与上面删子孙必须同事务，否则可能只删掉子树、留下一个空壳父节点
        dictDataMapper.deleteById(id);
        dictCacheRedisDAO.clear();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteDictDataList(List<Long> ids) {
        // 子节点若也在本次删除范围内，不算阻塞——否则整棵子树永远删不掉，
        // 只能从最深一层开始一个个删
        List<DictDataDO> dictDataList = dictDataMapper.selectByIds(ids);
        // 一次取回全部候选节点的子级，避免在循环里逐个查库
        Set<Long> idSet = new HashSet<>(ids);
        List<DictDataDO> children = dictDataMapper.selectListByParentIds(ids);
        boolean hasOutsideChild = children.stream().anyMatch(child -> !idSet.contains(child.getId()));
        if (hasOutsideChild) {
            throw exception(DICT_DATA_HAS_CHILDREN);
        }
        if (CollUtil.isEmpty(dictDataList)) {
            return;
        }

        dictDataMapper.deleteByIds(ids);
        dictCacheRedisDAO.clear();
    }

    @Override
    public long getDictDataCountByDictType(String dictType) {
        return dictDataMapper.selectCountByDictType(dictType);
    }

    @VisibleForTesting
    public void validateDictDataValueUnique(Long id, String dictType, String value) {
        DictDataDO dictData = dictDataMapper.selectByDictTypeAndValue(dictType, value);
        if (dictData == null) {
            return;
        }
        // 如果 id 为空，说明不用比较是否为相同 id 的字典数据
        if (id == null) {
            throw exception(DICT_DATA_VALUE_DUPLICATE);
        }
        if (!dictData.getId().equals(id)) {
            throw exception(DICT_DATA_VALUE_DUPLICATE);
        }
    }

    @VisibleForTesting
    public DictDataDO validateDictDataExists(Long id) {
        if (id == null) {
            return null;
        }
        DictDataDO dictData = dictDataMapper.selectById(id);
        if (dictData == null) {
            throw exception(DICT_DATA_NOT_EXISTS);
        }
        return dictData;
    }

    @VisibleForTesting
    public DictTypeDO validateDictTypeExists(String type) {
        DictTypeDO dictType = dictTypeService.getDictType(type);
        if (dictType == null) {
            throw exception(DICT_TYPE_NOT_EXISTS);
        }
        if (!CommonStatusEnum.ENABLE.getStatus().equals(dictType.getStatus())) {
            throw exception(DICT_TYPE_NOT_ENABLE);
        }
        // 分组只用来在左树上归拢字典，本身不挂数据。
        // 不拦的话会出现「挂在分组下的字典数据」，前端左树选中分组时不知道该不该展示它们
        if (DictTypeNodeEnum.isGroup(dictType.getNodeType())) {
            throw exception(DICT_TYPE_IS_GROUP);
        }
        return dictType;
    }

    @Override
    public void validateDictDataList(String dictType, Collection<String> values) {
        if (CollUtil.isEmpty(values)) {
            return;
        }
        Map<String, DictDataDO> dictDataMap = CollectionUtils.convertMap(
                dictDataMapper.selectByDictTypeAndValues(dictType, values), DictDataDO::getValue);
        // 校验
        values.forEach(value -> {
            DictDataDO dictData = dictDataMap.get(value);
            if (dictData == null) {
                throw exception(DICT_DATA_NOT_EXISTS);
            }
            if (!CommonStatusEnum.ENABLE.getStatus().equals(dictData.getStatus())) {
                throw exception(DICT_DATA_NOT_ENABLE, dictData.getLabel());
            }
        });
    }

    @Override
    public DictDataDO getDictData(String dictType, String value) {
        return dictDataMapper.selectByDictTypeAndValue(dictType, value);
    }

    @Override
    public DictDataDO parseDictData(String dictType, String label) {
        return dictDataMapper.selectByDictTypeAndLabel(dictType, label);
    }

    @Override
    public List<DictDataDO> getDictDataListByDictType(String dictType) {
        List<DictDataDO> list = dictDataMapper.selectList(DictDataDO::getDictType, dictType);
        list.sort(Comparator.comparing(DictDataDO::getSort));
        return list;
    }

    // ========== 树形 ==========

    @Override
    public List<DictDataDO> getDictDataTreeList(String dictType, String keyword) {
        if (StrUtil.isEmpty(keyword)) {
            return dictDataMapper.selectListByDictTypeOrderBySort(dictType);
        }
        // 1. 命中项
        List<DictDataDO> hits = dictDataMapper.selectListByKeyword(dictType, keyword);
        if (CollUtil.isEmpty(hits)) {
            return hits;
        }
        // 2. 补齐祖先。祖先编号全都写在 idPath 里，切分字符串就能拿到，
        //    不需要逐级查库，也不会有 N+1。少了祖先，树在界面上会断掉
        Set<Long> hitIds = convertSet(hits, DictDataDO::getId);
        Set<Long> ancestorIds = new LinkedHashSet<>();
        hits.forEach(hit -> ancestorIds.addAll(parseIdPath(hit.getIdPath())));
        ancestorIds.removeAll(hitIds);
        List<DictDataDO> result = new ArrayList<>(hits);
        if (CollUtil.isNotEmpty(ancestorIds)) {
            result.addAll(dictDataMapper.selectByIds(ancestorIds));
        }
        // 3. 重排。补进来的祖先是乱序追加的，而前端 handleTree 依赖同级顺序
        result.sort(COMPARATOR_PARENT_AND_SORT);
        return result;
    }

    @Override
    public List<DictDataDO> getDictDataChildren(String dictType, Long parentId) {
        return dictDataMapper.selectListByParentId(dictType,
                parentId != null ? parentId : DictDataDO.PARENT_ID_ROOT);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void moveDictData(Long id, Long parentId, Integer sort) {
        DictDataDO self = validateDictDataExists(id);
        DictTypeDO dictType = validateDictTypeExists(self.getDictType());
        DictDataDO parent = validateParentDictData(id, self.getDictType(), parentId, dictType);
        Long newParentId = parent == null ? DictDataDO.PARENT_ID_ROOT : parent.getId();

        // 先把旧路径记下来，后面要用它匹配子孙
        String oldPath = self.getIdPath();
        String newPath = buildIdPath(parent, id);
        int newLevel = parent == null ? 1 : parent.getTreeLevel() + 1;
        int levelGap = newLevel - self.getTreeLevel();

        // 1. 更新自身的父级与路径
        dictDataMapper.updateTreeFields(id, newParentId, newPath, newLevel);
        // 2. 一条 SQL 重写整棵子树的路径与层级，耗时与子树大小无关
        if (!Objects.equals(oldPath, newPath)) {
            dictDataMapper.updateSubTreePath(oldPath, newPath, levelGap, id);
        }
        // 3. 重排同级
        resortSiblings(self.getDictType(), newParentId, id, sort);
        dictCacheRedisDAO.clear();
    }

    /**
     * 把某个父节点下的同级节点重新编号为 0、1、2……并把 movedId 放到 targetIndex 位置
     *
     * <h3>为什么必须整体重排，而不是只改被移动节点的 sort</h3>
     * 新建节点的 sort 默认都是 0，同级节点普遍并列。此时若只把被拖动节点的 sort 设成目标下标，
     * 它多半仍与其它节点并列，最终顺序退化成按 id 排——用户拖完看不到任何变化。
     * 整体重排之后，同级的 sort 一定两两不同，拖到哪就停在哪。
     */
    private void resortSiblings(String dictType, Long parentId, Long movedId, Integer targetIndex) {
        List<DictDataDO> siblings = dictDataMapper.selectListByParentId(dictType, parentId);
        Map<Long, Integer> currentSorts = convertMap(siblings, DictDataDO::getId, DictDataDO::getSort);
        // 被移动的节点在上一步已经落到新父级下，先摘出来，再按目标下标插回去
        List<Long> ids = siblings.stream()
                .map(DictDataDO::getId)
                .filter(sid -> !Objects.equals(sid, movedId))
                .collect(Collectors.toList());
        int index = targetIndex == null ? ids.size() : Math.max(0, Math.min(targetIndex, ids.size()));
        ids.add(index, movedId);
        for (int i = 0; i < ids.size(); i++) {
            // 只更新真正发生变化的行。同级很多时，这能把一次拖拽的写入量
            // 从「整个同级列表」压到「实际移动的那一段」
            if (!Objects.equals(currentSorts.get(ids.get(i)), i)) {
                dictDataMapper.updateSort(ids.get(i), i);
            }
        }
    }

    /**
     * 拼装节点的物化路径
     *
     * 首尾都带分隔符，这样 {@code LIKE ',45,%'} 不会误命中 {@code ',145,'} 这类前缀
     */
    private String buildIdPath(DictDataDO parent, Long id) {
        String parentPath = parent == null ? DictDataDO.PATH_SEPARATOR : parent.getIdPath();
        return parentPath + id + DictDataDO.PATH_SEPARATOR;
    }

    /**
     * 从物化路径里解析出祖先编号（含自身）
     */
    private static List<Long> parseIdPath(String idPath) {
        if (StrUtil.isEmpty(idPath)) {
            return List.of();
        }
        return Arrays.stream(StrUtil.splitToArray(idPath, DictDataDO.PATH_SEPARATOR.charAt(0)))
                .filter(StrUtil::isNotBlank)
                .map(Long::valueOf)
                .collect(Collectors.toList());
    }

    // ========== 导入导出 ==========

    @Override
    public List<DictDataExcelVO> exportDictDataList(String dictType) {
        List<DictDataDO> list = dictDataMapper.selectListByDictTypeOrderBySort(dictType);
        // 编号 -> 键值，用来把 parentId 翻译成人能看懂、且跨环境稳定的「上级键值」
        Map<Long, String> valueMap = convertMap(list, DictDataDO::getId, DictDataDO::getValue);
        // 按「父在前、子在后」排列。导出的文件常被人直接拿来读，父子挨着才看得出层级；
        // 顺带也让导入端的依赖解析在绝大多数情况下一趟就能跑完
        return sortByHierarchy(list).stream()
                .map(data -> DictDataExcelVO.builder()
                        .label(data.getLabel())
                        .value(data.getValue())
                        .parentValue(valueMap.get(data.getParentId()))
                        .sort(data.getSort())
                        .status(data.getStatus())
                        .colorType(data.getColorType())
                        .cssClass(data.getCssClass())
                        .remark(data.getRemark())
                        .build())
                .collect(Collectors.toList());
    }

    /** 把扁平列表重排成「父节点紧跟其子孙」的先序顺序 */
    private List<DictDataDO> sortByHierarchy(List<DictDataDO> list) {
        Map<Long, List<DictDataDO>> childrenMap = list.stream()
                .collect(Collectors.groupingBy(DictDataDO::getParentId, LinkedHashMap::new, Collectors.toList()));
        List<DictDataDO> result = new ArrayList<>(list.size());
        Deque<DictDataDO> stack = new ArrayDeque<>();
        // 逆序压栈，保证弹出顺序与原列表一致
        List<DictDataDO> roots = childrenMap.getOrDefault(DictDataDO.PARENT_ID_ROOT, List.of());
        for (int i = roots.size() - 1; i >= 0; i--) {
            stack.push(roots.get(i));
        }
        while (!stack.isEmpty()) {
            DictDataDO node = stack.pop();
            result.add(node);
            List<DictDataDO> children = childrenMap.getOrDefault(node.getId(), List.of());
            for (int i = children.size() - 1; i >= 0; i--) {
                stack.push(children.get(i));
            }
        }
        // 兜底：父节点被手工删掉导致的孤儿节点不能凭空消失，否则导出的文件会悄悄少几行
        if (result.size() < list.size()) {
            Set<Long> visited = convertSet(result, DictDataDO::getId);
            list.stream().filter(data -> !visited.contains(data.getId())).forEach(result::add);
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DictDataImportRespVO importDictDataList(String dictType, List<DictDataExcelVO> list,
                                                   boolean isUpdateSupport) {
        if (CollUtil.isEmpty(list)) {
            throw exception(DICT_DATA_IMPORT_LIST_IS_EMPTY);
        }
        DictTypeDO type = validateDictTypeExists(dictType);
        boolean treeEnabled = Objects.equals(TREE_ENABLED, type.getTreeEnable());

        DictDataImportRespVO respVO = DictDataImportRespVO.builder()
                .createValues(new ArrayList<>())
                .updateValues(new ArrayList<>())
                .failureValues(new LinkedHashMap<>())
                .build();

        // 键值 -> 已存在的节点。导入过程中新建的节点也会补进来，
        // 这样同一个文件里「子行引用刚建出来的父行」才能解析得到
        Map<String, DictDataDO> existsMap = convertMap(
                dictDataMapper.selectListByDictTypeOrderBySort(dictType), DictDataDO::getValue);

        // 逐行做与父子关系无关的校验，能过的才进入依赖解析
        List<DictDataExcelVO> pending = new ArrayList<>();
        Set<String> seenValues = new HashSet<>();
        for (int i = 0; i < list.size(); i++) {
            DictDataExcelVO item = list.get(i);
            String key = StrUtil.blankToDefault(item.getValue(), "第 " + (i + 1) + " 行");
            if (StrUtil.isBlank(item.getLabel()) || StrUtil.isBlank(item.getValue())) {
                respVO.getFailureValues().put(key, DICT_DATA_IMPORT_LABEL_VALUE_REQUIRED.getMsg());
                continue;
            }
            // 同一个文件里出现两行相同键值，无法判断哪一行才是想要的，直接拒绝后一行
            if (!seenValues.add(item.getValue())) {
                respVO.getFailureValues().put(key, DICT_DATA_IMPORT_VALUE_REPEATED.getMsg());
                continue;
            }
            if (!treeEnabled && StrUtil.isNotBlank(item.getParentValue())) {
                respVO.getFailureValues().put(key, DICT_DATA_TREE_NOT_ENABLE.getMsg());
                continue;
            }
            if (Objects.equals(item.getParentValue(), item.getValue())) {
                respVO.getFailureValues().put(key, DICT_DATA_PARENT_IS_SELF.getMsg());
                continue;
            }
            pending.add(item);
        }

        // 依赖解析：反复扫描，每轮处理掉「父节点已就绪」的行，直到某一轮什么都处理不了。
        //
        // 不能顺序处理：Excel 里子行完全可能排在父行前面（用户自己排的序、或者只改了几行就导回来）。
        // 也不能只做一次两趟：链条有多深就要多少趟。
        // 剩下的行要么父节点根本不存在，要么互相引用成环——两种都不该悄悄挂到根上，
        // 那会让层级关系被静默改错，比直接报错难查得多。
        boolean progressed = true;
        while (progressed && !pending.isEmpty()) {
            progressed = false;
            Iterator<DictDataExcelVO> iterator = pending.iterator();
            while (iterator.hasNext()) {
                DictDataExcelVO item = iterator.next();
                String parentValue = item.getParentValue();
                DictDataDO parent = null;
                if (StrUtil.isNotBlank(parentValue)) {
                    parent = existsMap.get(parentValue);
                    if (parent == null) {
                        continue; // 父节点还没就绪，留到下一轮
                    }
                }
                iterator.remove();
                progressed = true;
                try {
                    DictDataDO saved = saveImportedDictData(dictType, item, parent,
                            existsMap.get(item.getValue()), isUpdateSupport, respVO);
                    if (saved != null) {
                        existsMap.put(saved.getValue(), saved);
                    }
                } catch (ServiceException ex) {
                    respVO.getFailureValues().put(item.getValue(), ex.getMessage());
                }
            }
        }
        // 还没处理完的，都是父节点找不到或者成环
        pending.forEach(item -> respVO.getFailureValues().put(item.getValue(),
                DICT_DATA_IMPORT_PARENT_UNRESOLVED.getMsg()));

        dictCacheRedisDAO.clear();
        return respVO;
    }

    /**
     * 落库一行导入数据
     *
     * @return 落库后的节点；跳过时返回 null
     */
    private DictDataDO saveImportedDictData(String dictType, DictDataExcelVO item, DictDataDO parent,
                                            DictDataDO exists, boolean isUpdateSupport,
                                            DictDataImportRespVO respVO) {
        int level = parent == null ? 1 : parent.getTreeLevel() + 1;
        if (level > MAX_TREE_LEVEL) {
            throw exception(DICT_DATA_LEVEL_EXCEED, MAX_TREE_LEVEL);
        }
        DictDataDO dictData = new DictDataDO();
        dictData.setDictType(dictType);
        dictData.setLabel(item.getLabel());
        dictData.setValue(item.getValue());
        dictData.setSort(ObjUtil.defaultIfNull(item.getSort(), 0));
        dictData.setStatus(ObjUtil.defaultIfNull(item.getStatus(), CommonStatusEnum.ENABLE.getStatus()));
        dictData.setColorType(StrUtil.nullToEmpty(item.getColorType()));
        dictData.setCssClass(StrUtil.nullToEmpty(item.getCssClass()));
        dictData.setRemark(item.getRemark());
        dictData.setParentId(parent == null ? DictDataDO.PARENT_ID_ROOT : parent.getId());
        dictData.setTreeLevel(level);

        if (exists == null) {
            dictDataMapper.insert(dictData);
            dictDataMapper.updateIdPath(dictData.getId(), buildIdPath(parent, dictData.getId()));
            dictData.setIdPath(buildIdPath(parent, dictData.getId()));
            respVO.getCreateValues().add(item.getValue());
            return dictData;
        }
        if (!isUpdateSupport) {
            respVO.getFailureValues().put(item.getValue(), DICT_DATA_VALUE_DUPLICATE.getMsg());
            return null;
        }
        // 更新时不能把节点挂到自己的子孙下面
        Long newParentId = parent == null ? DictDataDO.PARENT_ID_ROOT : parent.getId();
        if (parent != null && parseIdPath(parent.getIdPath()).contains(exists.getId())) {
            throw exception(DICT_DATA_PARENT_IS_CHILD);
        }
        dictData.setId(exists.getId());
        dictData.setParentId(null);
        dictData.setTreeLevel(null);
        dictDataMapper.updateById(dictData);
        // 父级变了才动树，避免对没改层级的行做无谓的子树重写
        if (!Objects.equals(exists.getParentId(), newParentId)) {
            moveDictData(exists.getId(), newParentId, item.getSort());
        }
        respVO.getUpdateValues().add(item.getValue());
        DictDataDO merged = dictDataMapper.selectById(exists.getId());
        return merged != null ? merged : exists;
    }

    /**
     * 校验父节点的有效性
     *
     * @param id       自身编号，新增时为 null
     * @param dictType 字典类型
     * @param parentId 目标父节点编号
     * @param type     所属字典类型，用于判断是否开启了树形
     * @return 父节点；挂在根下时返回 null
     */
    @VisibleForTesting
    DictDataDO validateParentDictData(Long id, String dictType, Long parentId, DictTypeDO type) {
        if (parentId == null || DictDataDO.PARENT_ID_ROOT.equals(parentId)) {
            return null;
        }
        // 1. 平铺字典不允许有父节点。否则一个字典既有层级、又被当成平铺渲染，
        //    下拉框会把子节点和根节点铺成一列，层级语义完全丢失
        if (!Objects.equals(TREE_ENABLED, type.getTreeEnable())) {
            throw exception(DICT_DATA_TREE_NOT_ENABLE);
        }
        // 2. 不能设置自己为父节点
        if (Objects.equals(id, parentId)) {
            throw exception(DICT_DATA_PARENT_IS_SELF);
        }
        // 3. 父节点必须存在
        DictDataDO parent = dictDataMapper.selectById(parentId);
        if (parent == null) {
            throw exception(DICT_DATA_PARENT_NOT_EXISTS);
        }
        // 4. 父节点必须属于同一个字典。跨字典挂父会让 value 的唯一性范围失去意义
        if (!Objects.equals(parent.getDictType(), dictType)) {
            throw exception(DICT_DATA_PARENT_NOT_SAME_TYPE);
        }
        // 5. 层级上限
        if (parent.getTreeLevel() + 1 > MAX_TREE_LEVEL) {
            throw exception(DICT_DATA_LEVEL_EXCEED, MAX_TREE_LEVEL);
        }
        // 6. 防环。父节点的 idPath 里若出现自己，说明它是自己的子孙。
        //    物化路径让这一步变成一次字符串判断，不用像部门那样逐级向上查库
        if (id != null && parseIdPath(parent.getIdPath()).contains(id)) {
            throw exception(DICT_DATA_PARENT_IS_CHILD);
        }
        return parent;
    }

}
