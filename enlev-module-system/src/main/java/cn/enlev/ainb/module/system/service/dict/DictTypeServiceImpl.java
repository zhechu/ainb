package cn.enlev.ainb.module.system.service.dict;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjUtil;
import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.common.enums.CommonStatusEnum;
import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.framework.common.util.date.LocalDateTimeUtils;
import cn.enlev.ainb.framework.common.util.object.BeanUtils;
import cn.enlev.ainb.module.system.controller.admin.dict.vo.data.DictDataExcelVO;
import cn.enlev.ainb.module.system.controller.admin.dict.vo.data.DictDataImportRespVO;
import cn.enlev.ainb.module.system.controller.admin.dict.vo.transfer.DictDataTransferVO;
import cn.enlev.ainb.module.system.controller.admin.dict.vo.transfer.DictImportRespVO;
import cn.enlev.ainb.module.system.controller.admin.dict.vo.transfer.DictImportRespVO.TypeResult;
import cn.enlev.ainb.module.system.controller.admin.dict.vo.transfer.DictTransferVO;
import cn.enlev.ainb.module.system.controller.admin.dict.vo.transfer.DictTypeTransferVO;
import cn.enlev.ainb.module.system.controller.admin.dict.vo.type.DictTypePageReqVO;
import cn.enlev.ainb.module.system.controller.admin.dict.vo.type.DictTypeSaveReqVO;
import cn.enlev.ainb.module.system.dal.dataobject.dict.DictTypeDO;
import cn.enlev.ainb.module.system.dal.mysql.dict.DictTypeMapper;
import cn.enlev.ainb.module.system.dal.redis.dict.DictCacheRedisDAO;
import cn.enlev.ainb.module.system.enums.dict.DictCacheModeEnum;
import cn.enlev.ainb.module.system.enums.dict.DictImportPolicyEnum;
import cn.enlev.ainb.module.system.enums.dict.DictTypeNodeEnum;
import com.google.common.annotations.VisibleForTesting;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
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
 * 字典类型 Service 实现类
 *
 * @author 芋道源码
 */
@Service
public class DictTypeServiceImpl implements DictTypeService {

    /**
     * 树的遍历深度上限
     *
     * 用来给向上追溯的循环兜底：数据被手工改坏形成环时，不能把线程卡死在这里。
     * 分类树的真实深度远小于它，正常路径不会触及。
     */
    private static final int MAX_TREE_DEPTH = 64;

    /**
     * 副本后缀的最大尝试次数
     *
     * 同一份包被反复以副本方式导入几十次已经很反常了，再往上多半是脚本跑飞。
     * 到顶就报错，好过在这里一直扫下去
     */
    private static final int MAX_COPY_SUFFIX = 50;

    @Resource
    private DictDataService dictDataService;

    @Resource
    private DictTypeMapper dictTypeMapper;

    @Resource
    private DictCacheRedisDAO dictCacheRedisDAO;

    @Override
    public PageResult<DictTypeDO> getDictTypePage(DictTypePageReqVO pageReqVO) {
        return dictTypeMapper.selectPage(pageReqVO);
    }

    @Override
    public DictTypeDO getDictType(Long id) {
        return dictTypeMapper.selectById(id);
    }

    @Override
    public DictTypeDO getDictType(String type) {
        return dictTypeMapper.selectByType(type);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createDictType(DictTypeSaveReqVO createReqVO) {
        // 校验父分类的有效性
        validateParentDictType(null, createReqVO.getParentId());
        // 校验字典类型的名字的唯一性
        validateDictTypeNameUnique(null, createReqVO.getName());
        // 校验字典类型的类型的唯一性
        validateDictTypeUnique(null, createReqVO.getType());

        // 插入字典类型
        DictTypeDO dictType = BeanUtils.toBean(createReqVO, DictTypeDO.class);
        if (dictType.getParentId() == null) {
            dictType.setParentId(DictTypeDO.PARENT_ID_ROOT);
        }
        dictType.setDeletedTime(LocalDateTimeUtils.EMPTY); // 唯一索引，避免 null 值
        dictTypeMapper.insert(dictType);
        dictCacheRedisDAO.clear();
        return dictType.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateDictType(DictTypeSaveReqVO updateReqVO) {
        // 校验自己存在
        DictTypeDO dictType = validateDictTypeExists(updateReqVO.getId());
        // 校验父分类的有效性
        validateParentDictType(updateReqVO.getId(), updateReqVO.getParentId());
        // 校验字典类型的名字的唯一性
        validateDictTypeNameUnique(updateReqVO.getId(), updateReqVO.getName());
        // 校验字典类型的类型的唯一性
        validateDictTypeUnique(updateReqVO.getId(), updateReqVO.getType());
        // 校验树形开关未被切换
        validateTreeEnableNotChanged(dictType, updateReqVO.getTreeEnable());

        // 更新字典类型
        DictTypeDO updateObj = BeanUtils.toBean(updateReqVO, DictTypeDO.class);
        if (updateObj.getParentId() == null) {
            updateObj.setParentId(DictTypeDO.PARENT_ID_ROOT);
        }
        dictTypeMapper.updateById(updateObj);
        dictCacheRedisDAO.clear();
    }

    /**
     * 校验树形开关没有被切换
     *
     * 树形改平铺会让已存在的子节点变成孤儿（parentId 指向的父级不再有树的语义）；
     * 平铺改树形本身安全，但为了让这条规则简单可预期，两个方向都不允许。
     * 确有需要走「导出 → 新建 → 导入」。
     */
    private void validateTreeEnableNotChanged(DictTypeDO dictType, Integer treeEnable) {
        if (treeEnable == null || Objects.equals(dictType.getTreeEnable(), treeEnable)) {
            return;
        }
        throw exception(DICT_TYPE_TREE_ENABLE_CANNOT_UPDATE);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteDictType(Long id) {
        // 校验是否存在
        DictTypeDO dictType = validateDictTypeExists(id);
        // 校验是否有子分类
        if (dictTypeMapper.selectCountByParentId(id) > 0) {
            throw exception(DICT_TYPE_HAS_CHILDREN_TYPE);
        }
        // 校验是否有字典数据
        if (dictDataService.getDictDataCountByDictType(dictType.getType()) > 0) {
            throw exception(DICT_TYPE_HAS_CHILDREN);
        }
        // 删除字典类型
        dictTypeMapper.updateToDelete(id, LocalDateTime.now());
        dictCacheRedisDAO.clear();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteDictTypeList(List<Long> ids) {
        List<DictTypeDO> dictTypes = dictTypeMapper.selectByIds(ids);
        if (CollUtil.isEmpty(dictTypes)) {
            return;
        }
        // 1. 校验子分类。子分类若也在本次删除范围内，不算阻塞——否则整棵子树永远删不掉，
        //    只能从最深一层开始一个个删。一次批量取回全部子级，避免在循环里逐个查库
        Set<Long> idSet = new HashSet<>(ids);
        List<DictTypeDO> children = dictTypeMapper.selectListByParentIds(ids);
        if (children.stream().anyMatch(child -> !idSet.contains(child.getId()))) {
            throw exception(DICT_TYPE_HAS_CHILDREN_TYPE);
        }
        // 2. 校验字典数据
        dictTypes.forEach(dictType -> {
            if (dictDataService.getDictDataCountByDictType(dictType.getType()) > 0) {
                throw exception(DICT_TYPE_HAS_CHILDREN);
            }
        });

        // 3. 批量删除字典类型
        LocalDateTime now = LocalDateTime.now();
        ids.forEach(id -> dictTypeMapper.updateToDelete(id, now));
        dictCacheRedisDAO.clear();
    }

    @Override
    public List<DictTypeDO> getDictTypeList() {
        return dictTypeMapper.selectList();
    }

    @Override
    public List<DictTypeDO> getDictTypeList(String keyword) {
        if (StrUtil.isEmpty(keyword)) {
            return dictTypeMapper.selectListOrderBySort();
        }
        // 1. 命中项
        List<DictTypeDO> hits = dictTypeMapper.selectListByKeyword(keyword);
        if (CollUtil.isEmpty(hits)) {
            return hits;
        }
        // 2. 补齐祖先。分类只有 parentId 没有物化路径，所以从全量列表在内存里向上追溯——
        //    分类的数量级是几百，一次全取比逐级查库划算，也避免了 N+1
        Map<Long, DictTypeDO> allMap = convertMap(dictTypeMapper.selectListOrderBySort(), DictTypeDO::getId);
        Map<Long, DictTypeDO> result = new LinkedHashMap<>();
        hits.forEach(hit -> {
            result.put(hit.getId(), hit);
            DictTypeDO node = hit;
            // 加循环上限而不是 while(true)：数据被手工改坏形成环时，这里不能把线程卡死
            for (int i = 0; i < MAX_TREE_DEPTH; i++) {
                Long parentId = node.getParentId();
                if (parentId == null || DictTypeDO.PARENT_ID_ROOT.equals(parentId)) {
                    break;
                }
                DictTypeDO parent = allMap.get(parentId);
                if (parent == null || result.containsKey(parent.getId())) {
                    break; // 父级不存在（脏数据）或已收录，都不必再往上
                }
                result.put(parent.getId(), parent);
                node = parent;
            }
        });
        return new ArrayList<>(result.values());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void moveDictType(Long id, Long parentId, Integer sort) {
        validateDictTypeExists(id);
        validateParentDictType(id, parentId);
        Long newParentId = parentId != null ? parentId : DictTypeDO.PARENT_ID_ROOT;

        dictTypeMapper.updateParentId(id, newParentId);
        resortSiblings(newParentId, id, sort);
        dictCacheRedisDAO.clear();
    }

    /**
     * 把某个父分类下的同级分类重新编号为 0、1、2……并把 movedId 放到 targetIndex 位置
     *
     * 只改被拖动节点的 sort 是不够的：新建分类的 sort 默认都是 0，同级普遍并列，
     * 此时设成目标下标多半仍与别人并列，顺序退化成按 id 排，用户拖完看不到变化。
     * 整体重排后同级的 sort 两两不同，拖到哪就停在哪。
     */
    private void resortSiblings(Long parentId, Long movedId, Integer targetIndex) {
        List<DictTypeDO> siblings = dictTypeMapper.selectListByParentId(parentId);
        Map<Long, Integer> currentSorts = convertMap(siblings, DictTypeDO::getId, DictTypeDO::getSort);
        List<Long> ids = siblings.stream()
                .map(DictTypeDO::getId)
                .filter(sid -> !Objects.equals(sid, movedId))
                .collect(Collectors.toList());
        int index = targetIndex == null ? ids.size() : Math.max(0, Math.min(targetIndex, ids.size()));
        ids.add(index, movedId);
        for (int i = 0; i < ids.size(); i++) {
            // 只更新真正变化的行，把一次拖拽的写入量压到实际移动的那一段
            if (!Objects.equals(currentSorts.get(ids.get(i)), i)) {
                dictTypeMapper.updateSort(ids.get(i), i);
            }
        }
    }

    /**
     * 校验父分类的有效性
     *
     * @param id       自身编号，新增时为 null
     * @param parentId 目标父分类编号
     */
    @VisibleForTesting
    void validateParentDictType(Long id, Long parentId) {
        if (parentId == null || DictTypeDO.PARENT_ID_ROOT.equals(parentId)) {
            return;
        }
        // 1. 不能设置自己为父分类
        if (Objects.equals(id, parentId)) {
            throw exception(DICT_TYPE_PARENT_IS_SELF);
        }
        // 2. 父分类必须存在
        DictTypeDO parent = dictTypeMapper.selectById(parentId);
        if (parent == null) {
            throw exception(DICT_TYPE_PARENT_NOT_EXISTS);
        }
        // 3. 父级只能是分组。字典节点底下挂的是字典数据，不能再挂分类，
        //    否则左树上「这个节点到底该展开子分类还是展示字典数据」就没有确定答案
        if (!DictTypeNodeEnum.isGroup(parent.getNodeType())) {
            throw exception(DICT_TYPE_PARENT_NOT_GROUP);
        }
        // 4. 新增没有环路问题
        if (id == null) {
            return;
        }
        // 5. 向上追溯，父分类不能是自己的子孙
        for (int i = 0; i < MAX_TREE_DEPTH; i++) {
            Long grandParentId = parent.getParentId();
            if (Objects.equals(id, grandParentId)) {
                throw exception(DICT_TYPE_PARENT_IS_CHILD);
            }
            if (grandParentId == null || DictTypeDO.PARENT_ID_ROOT.equals(grandParentId)) {
                break;
            }
            parent = dictTypeMapper.selectById(grandParentId);
            if (parent == null) {
                break;
            }
        }
    }

    @VisibleForTesting
    void validateDictTypeNameUnique(Long id, String name) {
        DictTypeDO dictType = dictTypeMapper.selectByName(name);
        if (dictType == null) {
            return;
        }
        // 如果 id 为空，说明不用比较是否为相同 id 的字典类型
        if (id == null) {
            throw exception(DICT_TYPE_NAME_DUPLICATE);
        }
        if (!dictType.getId().equals(id)) {
            throw exception(DICT_TYPE_NAME_DUPLICATE);
        }
    }

    @VisibleForTesting
    void validateDictTypeUnique(Long id, String type) {
        if (StrUtil.isEmpty(type)) {
            return;
        }
        DictTypeDO dictType = dictTypeMapper.selectByType(type);
        if (dictType == null) {
            return;
        }
        // 如果 id 为空，说明不用比较是否为相同 id 的字典类型
        if (id == null) {
            throw exception(DICT_TYPE_TYPE_DUPLICATE);
        }
        if (!dictType.getId().equals(id)) {
            throw exception(DICT_TYPE_TYPE_DUPLICATE);
        }
    }

    @VisibleForTesting
    DictTypeDO validateDictTypeExists(Long id) {
        if (id == null) {
            return null;
        }
        DictTypeDO dictType = dictTypeMapper.selectById(id);
        if (dictType == null) {
            throw exception(DICT_TYPE_NOT_EXISTS);
        }
        return dictType;
    }

    // ========== 迁移包导入导出 ==========

    @Override
    public DictTransferVO exportDictTypes(Long id) {
        DictTypeDO root = validateDictTypeExists(id);
        List<DictTypeDO> subTree = collectSubTree(root);

        DictTransferVO transfer = new DictTransferVO();
        transfer.setVersion(DictTransferVO.CURRENT_VERSION);
        transfer.setExportTime(LocalDateTime.now());
        transfer.setRootType(root.getType());

        // 1. 分类：把 parentId 翻译成父分类的编码。
        //    根节点的 parentType 特意留空——包里若记下它在导出环境的位置，
        //    换个环境导入时那个位置多半不存在；留空之后挂到哪里由导入方决定
        Map<Long, String> typeCodeMap = convertMap(subTree, DictTypeDO::getId, DictTypeDO::getType);
        transfer.setTypes(convertList(subTree, item -> {
            DictTypeTransferVO vo = BeanUtils.toBean(item, DictTypeTransferVO.class);
            vo.setParentType(Objects.equals(item.getId(), root.getId())
                    ? null : typeCodeMap.get(item.getParentId()));
            return vo;
        }));

        // 2. 数据：直接复用字典数据的导出，它已经处理好了「上级键值」翻译和父子先序排列，
        //    这里只需要补一个 dictType 说明每条数据属于哪个字典
        List<DictDataTransferVO> datas = new ArrayList<>();
        subTree.stream().filter(item -> DictTypeNodeEnum.isDict(item.getNodeType())).forEach(item ->
                dictDataService.exportDictDataList(item.getType()).forEach(data -> {
                    DictDataTransferVO vo = BeanUtils.toBean(data, DictDataTransferVO.class);
                    vo.setDictType(item.getType());
                    datas.add(vo);
                }));
        transfer.setDatas(datas);
        return transfer;
    }

    /**
     * 取出以 root 为根的整棵子树，按「父在前、子在后」排列
     *
     * 先序而不是随便什么顺序：导入端要保证父分类先落库子分类才有地方挂，
     * 包内顺序本身就对了，导入时的依赖解析绝大多数情况下一趟就能跑完。
     */
    private List<DictTypeDO> collectSubTree(DictTypeDO root) {
        Map<Long, List<DictTypeDO>> childrenMap = dictTypeMapper.selectListOrderBySort().stream()
                .collect(Collectors.groupingBy(DictTypeDO::getParentId, LinkedHashMap::new, Collectors.toList()));
        List<DictTypeDO> result = new ArrayList<>();
        // 记录已访问的节点：数据被手工改坏形成环时，不能让这里无限展开
        Set<Long> visited = new HashSet<>();
        Deque<DictTypeDO> stack = new ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            DictTypeDO node = stack.pop();
            if (!visited.add(node.getId())) {
                continue;
            }
            result.add(node);
            List<DictTypeDO> children = childrenMap.getOrDefault(node.getId(), List.of());
            // 逆序压栈，保证弹出顺序与同级排序一致
            for (int i = children.size() - 1; i >= 0; i--) {
                stack.push(children.get(i));
            }
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DictImportRespVO importDictTypes(DictTransferVO transfer, Integer policy, Long parentId) {
        // 1. 校验包本身
        if (transfer == null || CollUtil.isEmpty(transfer.getTypes())) {
            throw exception(DICT_TRANSFER_TYPES_EMPTY);
        }
        Integer version = ObjUtil.defaultIfNull(transfer.getVersion(), DictTransferVO.CURRENT_VERSION);
        if (version > DictTransferVO.CURRENT_VERSION) {
            throw exception(DICT_TRANSFER_VERSION_UNSUPPORTED, version, DictTransferVO.CURRENT_VERSION);
        }
        if (transfer.getTypes().stream().anyMatch(
                item -> StrUtil.isBlank(item.getType()) || StrUtil.isBlank(item.getName()))) {
            throw exception(DICT_TRANSFER_TYPE_INVALID);
        }
        // 2. 校验落点：只能挂在分组下面，与手工新增分类走同一套规则
        Long rootParentId = ObjUtil.defaultIfNull(parentId, DictTypeDO.PARENT_ID_ROOT);
        validateParentDictType(null, rootParentId);
        Integer finalPolicy = ObjUtil.defaultIfNull(policy, DictImportPolicyEnum.SKIP.getPolicy());

        // 3. 数据按所属字典归堆，落库时按分类取用
        Map<String, List<DictDataTransferVO>> dataMap = CollUtil.isEmpty(transfer.getDatas())
                ? Map.of()
                : transfer.getDatas().stream().filter(data -> StrUtil.isNotBlank(data.getDictType()))
                        .collect(Collectors.groupingBy(DictDataTransferVO::getDictType));

        // 4. 依赖解析：反复扫描，每轮处理掉「父分类已就位」的节点，直到某一轮什么都处理不了。
        //
        //    不能直接按包内顺序处理：包有可能被人手工编辑过，顺序不再是先序；
        //    也不能把父级找不到的节点默默挂到根上——那会让层级被静默改错，
        //    用户看到「导入成功」，却发现分类跑到了别的地方。
        DictImportRespVO respVO = new DictImportRespVO();
        Set<String> packageTypes = convertSet(transfer.getTypes(), DictTypeTransferVO::getType);
        Map<String, Long> savedIdMap = new HashMap<>();
        List<DictTypeTransferVO> pending = new ArrayList<>(transfer.getTypes());
        boolean progressed = true;
        while (progressed && !pending.isEmpty()) {
            progressed = false;
            Iterator<DictTypeTransferVO> iterator = pending.iterator();
            while (iterator.hasNext()) {
                DictTypeTransferVO item = iterator.next();
                Long targetParentId = resolveParentId(item, rootParentId, packageTypes, savedIdMap);
                if (targetParentId == null) {
                    continue; // 父分类还没就位，留到下一轮
                }
                iterator.remove();
                progressed = true;
                TypeResult result = importOneType(item, targetParentId, finalPolicy, dataMap, savedIdMap);
                respVO.getTypes().add(result);
            }
        }
        // 剩下的要么父分类根本不存在，要么互相引用成环
        pending.forEach(item -> respVO.getTypes().add(TypeResult.of(item)
                .action(TypeResult.ACTION_FAILED)
                .reason(DICT_TRANSFER_PARENT_UNRESOLVED.getMsg())
                .build()));

        dictCacheRedisDAO.clear();
        return respVO;
    }

    /**
     * 算出一个分类节点该挂到哪个父分类下
     *
     * @return 父分类编号；返回 null 表示父分类尚未就位，本轮先跳过
     */
    private Long resolveParentId(DictTypeTransferVO item, Long rootParentId,
                                 Set<String> packageTypes, Map<String, Long> savedIdMap) {
        String parentType = item.getParentType();
        if (StrUtil.isBlank(parentType)) {
            return rootParentId; // 包的根节点，挂到用户指定的位置
        }
        Long savedId = savedIdMap.get(parentType);
        if (savedId != null) {
            return savedId;
        }
        // 父分类也在包里但还没落库：必须等它，不能退而求其次去用库里的同名分类，
        // 否则同一次导入会把子分类挂到包外的另一棵树上
        if (packageTypes.contains(parentType)) {
            return null;
        }
        DictTypeDO exists = dictTypeMapper.selectByType(parentType);
        return exists != null ? exists.getId() : null;
    }

    /**
     * 落库一个分类节点，连同它下面的字典数据
     */
    private TypeResult importOneType(DictTypeTransferVO item, Long parentId, Integer policy,
                                     Map<String, List<DictDataTransferVO>> dataMap,
                                     Map<String, Long> savedIdMap) {
        TypeResult.TypeResultBuilder builder = TypeResult.of(item);
        DictTypeDO exists = dictTypeMapper.selectByType(item.getType());

        String savedType;
        Long savedId;
        String action;
        // 是否允许覆盖已存在的字典数据。这个开关直接决定了字典数据那条导入通道的行为，
        // 三种策略在分类和数据两层保持同一个语义
        boolean dataUpdateSupport;

        if (DictImportPolicyEnum.isRename(policy)) {
            // 副本策略：一律新建。编码和名称一起找同一个可用后缀，
            // 让「xxx_2 / 名称（副本2）」看得出是成对的一份
            String[] naming = nextAvailableNaming(item.getType(), item.getName());
            savedType = naming[0];
            savedId = insertDictType(item, parentId, savedType, naming[1]);
            savedIdMap.put(item.getType(), savedId);
            action = Objects.equals(savedType, item.getType())
                    ? TypeResult.ACTION_CREATED : TypeResult.ACTION_RENAMED;
            builder.savedType(savedType).name(naming[1]);
            dataUpdateSupport = true;
        } else if (exists == null) {
            // 编码没被占用，但名称有独立的唯一约束，可能被另一个分类拿走了
            DictTypeDO byName = dictTypeMapper.selectByName(item.getName());
            if (byName != null) {
                return builder.action(TypeResult.ACTION_FAILED)
                        .reason(DICT_TRANSFER_NAME_OCCUPIED.getMsg()).build();
            }
            savedType = item.getType();
            savedId = insertDictType(item, parentId, savedType, item.getName());
            savedIdMap.put(item.getType(), savedId);
            action = TypeResult.ACTION_CREATED;
            builder.savedType(savedType);
            dataUpdateSupport = true;
        } else if (DictImportPolicyEnum.isSkip(policy)) {
            // 分类保持原样，但它下面缺的数据还是要补上——
            // 「跳过」针对的是冲突项，不是整个分类
            savedType = exists.getType();
            savedId = exists.getId();
            savedIdMap.put(item.getType(), savedId);
            action = TypeResult.ACTION_SKIPPED;
            builder.savedType(savedType).name(exists.getName());
            dataUpdateSupport = false;
        } else {
            // 覆盖策略。节点类型和树形开关不允许被导入改掉：
            // 分组改成字典会让底下的子分类无处安放，树形改平铺会让已有的子节点变成孤儿，
            // 这两种破坏都不是「更新一下内容」该干的事
            if (!Objects.equals(nodeTypeOf(item), exists.getNodeType())
                    || !Objects.equals(treeEnableOf(item), exists.getTreeEnable())) {
                return builder.action(TypeResult.ACTION_FAILED)
                        .reason(DICT_TRANSFER_TREE_ENABLE_CONFLICT.getMsg()).build();
            }
            DictTypeDO byName = dictTypeMapper.selectByName(item.getName());
            if (byName != null && !Objects.equals(byName.getId(), exists.getId())) {
                return builder.action(TypeResult.ACTION_FAILED)
                        .reason(DICT_TRANSFER_NAME_OCCUPIED.getMsg()).build();
            }
            // 不动 parentId：分类在这个环境里的位置可能是管理员特意调过的，
            // 导入是为了同步内容，不是把它搬回导出环境的位置
            DictTypeDO updateObj = new DictTypeDO();
            updateObj.setId(exists.getId());
            updateObj.setName(item.getName());
            updateObj.setSort(ObjUtil.defaultIfNull(item.getSort(), exists.getSort()));
            updateObj.setCacheMode(ObjUtil.defaultIfNull(item.getCacheMode(), exists.getCacheMode()));
            updateObj.setStatus(ObjUtil.defaultIfNull(item.getStatus(), exists.getStatus()));
            updateObj.setRemark(item.getRemark());
            dictTypeMapper.updateById(updateObj);
            savedType = exists.getType();
            savedId = exists.getId();
            savedIdMap.put(item.getType(), savedId);
            action = TypeResult.ACTION_UPDATED;
            builder.savedType(savedType);
            dataUpdateSupport = true;
        }

        importTypeData(item, savedType, dataMap, dataUpdateSupport, builder);
        return builder.action(action).build();
    }

    /**
     * 把包里属于某个分类的字典数据导进去
     *
     * 直接复用字典数据那条导入通道：层级解析、成环检测、逐行失败原因它都已经做好了，
     * 在这里重写一遍只会多出一份需要同步维护的实现
     */
    private void importTypeData(DictTypeTransferVO item, String savedType,
                                Map<String, List<DictDataTransferVO>> dataMap,
                                boolean dataUpdateSupport, TypeResult.TypeResultBuilder builder) {
        List<DictDataTransferVO> datas = dataMap.get(item.getType());
        // 分组节点不挂字典数据；包里若带了，说明包被改坏了，忽略比报错更合适
        if (CollUtil.isEmpty(datas) || !DictTypeNodeEnum.isDict(nodeTypeOf(item))) {
            return;
        }
        DictDataImportRespVO dataResp = dictDataService.importDictDataList(savedType,
                convertList(datas, data -> DictDataExcelVO.builder()
                        .label(data.getLabel())
                        .value(data.getValue())
                        .parentValue(data.getParentValue())
                        .sort(data.getSort())
                        .status(data.getStatus())
                        .colorType(data.getColorType())
                        .cssClass(data.getCssClass())
                        .remark(data.getRemark())
                        .build()),
                dataUpdateSupport);
        builder.dataCreateCount(dataResp.getCreateValues().size())
                .dataUpdateCount(dataResp.getUpdateValues().size());

        // 「跳过」策略下，键值已存在会被数据导入判为失败。那在这里是预期行为而不是错误，
        // 原样透出去会让界面标一片红，用户以为导坏了。把这类摘出来单独计数
        Map<String, String> failures = new LinkedHashMap<>(dataResp.getFailureValues());
        if (!dataUpdateSupport) {
            Set<String> skipped = failures.entrySet().stream()
                    .filter(entry -> Objects.equals(entry.getValue(), DICT_DATA_VALUE_DUPLICATE.getMsg()))
                    .map(Map.Entry::getKey).collect(Collectors.toSet());
            skipped.forEach(failures::remove);
            builder.dataSkipCount(skipped.size());
        }
        builder.dataFailures(failures);
    }

    /**
     * 新增一个分类节点
     *
     * @return 新分类的编号
     */
    private Long insertDictType(DictTypeTransferVO item, Long parentId, String type, String name) {
        DictTypeDO dictType = new DictTypeDO();
        dictType.setParentId(parentId);
        dictType.setNodeType(nodeTypeOf(item));
        dictType.setName(name);
        dictType.setType(type);
        dictType.setSort(ObjUtil.defaultIfNull(item.getSort(), 0));
        dictType.setTreeEnable(treeEnableOf(item));
        dictType.setCacheMode(ObjUtil.defaultIfNull(item.getCacheMode(), DictCacheModeEnum.FULL.getMode()));
        dictType.setStatus(ObjUtil.defaultIfNull(item.getStatus(), CommonStatusEnum.ENABLE.getStatus()));
        dictType.setRemark(item.getRemark());
        dictType.setDeletedTime(LocalDateTimeUtils.EMPTY); // 唯一索引，避免 null 值
        dictTypeMapper.insert(dictType);
        return dictType.getId();
    }

    /**
     * 为副本找一组还没被占用的「编码 + 名称」
     *
     * 编码和名称各有唯一约束，必须一起找同一个后缀：只避开编码冲突的话，
     * 名称照样会撞上，落库时抛出的是一个用户看不懂的数据库异常。
     *
     * @return 长度为 2 的数组，依次是编码与名称
     */
    private String[] nextAvailableNaming(String type, String name) {
        if (dictTypeMapper.selectByType(type) == null && dictTypeMapper.selectByName(name) == null) {
            return new String[]{type, name};
        }
        for (int i = 2; i <= MAX_COPY_SUFFIX; i++) {
            String candidateType = type + "_" + i;
            String candidateName = name + "（副本" + i + "）";
            if (dictTypeMapper.selectByType(candidateType) == null
                    && dictTypeMapper.selectByName(candidateName) == null) {
                return new String[]{candidateType, candidateName};
            }
        }
        throw exception(DICT_TRANSFER_COPY_SUFFIX_EXHAUSTED);
    }

    private Integer nodeTypeOf(DictTypeTransferVO item) {
        return ObjUtil.defaultIfNull(item.getNodeType(), DictTypeNodeEnum.DICT.getType());
    }

    private Integer treeEnableOf(DictTypeTransferVO item) {
        return ObjUtil.defaultIfNull(item.getTreeEnable(), 0);
    }

}
