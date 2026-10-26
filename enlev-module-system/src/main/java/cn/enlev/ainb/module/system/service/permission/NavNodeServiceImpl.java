package cn.enlev.ainb.module.system.service.permission;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.common.enums.CommonStatusEnum;
import cn.enlev.ainb.framework.common.util.object.BeanUtils;
import cn.enlev.ainb.module.system.controller.admin.permission.vo.navnode.NavNodeListReqVO;
import cn.enlev.ainb.module.system.controller.admin.permission.vo.navnode.NavNodeSaveVO;
import cn.enlev.ainb.module.system.controller.admin.permission.vo.navnode.NavNodeSortReqVO;
import cn.enlev.ainb.module.system.controller.admin.permission.vo.navnode.NavNodeTreeRespVO;
import cn.enlev.ainb.module.system.dal.dataobject.permission.NavNodeDO;
import cn.enlev.ainb.module.system.dal.mysql.permission.NavNodeMapper;
import cn.enlev.ainb.module.system.enums.permission.NavNodeTypeEnum;
import cn.enlev.ainb.module.system.enums.permission.NavTerminalEnum;
import com.google.common.annotations.VisibleForTesting;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.module.system.dal.dataobject.permission.NavNodeDO.ID_ROOT;
import static cn.enlev.ainb.module.system.enums.ErrorCodeConstants.*;

/**
 * 分端导航节点 Service 实现
 *
 * <p>仿 {@link MenuServiceImpl}：同样的 @TenantIgnore 树、同样的自引用父子校验、同样的级联过滤思路。
 * 差别在于本树<b>不承载授权</b>——它只是一棵「按端展示什么入口」的导航树，
 * 通过 {@link NavNodeDO#getPermissions()} 命中共享授权集合来决定可见（见「移动端优化方案」§4.4.3）
 *
 * @author 走进新时代
 */
@Service
@Slf4j
public class NavNodeServiceImpl implements NavNodeService {

    @Resource
    private NavNodeMapper navNodeMapper;

    @Override
    public Long createNavNode(NavNodeSaveVO createReqVO) {
        NavNodeDO node = BeanUtils.toBean(createReqVO, NavNodeDO.class);
        node.setTerminal(resolveTerminal(createReqVO.getTerminal()));
        initNavNodeProperty(node);
        // 校验父节点、同级重名
        validateParent(node.getTerminal(), node.getParentId(), null);
        validateName(node.getTerminal(), node.getParentId(), node.getName(), null);
        navNodeMapper.insert(node);
        return node.getId();
    }

    @Override
    public void updateNavNode(NavNodeSaveVO updateReqVO) {
        // 校验存在
        if (navNodeMapper.selectById(updateReqVO.getId()) == null) {
            throw exception(NAV_NODE_NOT_EXISTS);
        }
        NavNodeDO node = BeanUtils.toBean(updateReqVO, NavNodeDO.class);
        node.setTerminal(resolveTerminal(updateReqVO.getTerminal()));
        initNavNodeProperty(node);
        // 校验父节点（含防环）、同级重名
        validateParent(node.getTerminal(), node.getParentId(), node.getId());
        validateName(node.getTerminal(), node.getParentId(), node.getName(), node.getId());
        navNodeMapper.updateById(node);
    }

    @Override
    public void deleteNavNode(Long id) {
        // 校验是否还有子节点
        if (navNodeMapper.selectCountByParentId(id) > 0) {
            throw exception(NAV_NODE_EXISTS_CHILDREN);
        }
        // 校验存在
        if (navNodeMapper.selectById(id) == null) {
            throw exception(NAV_NODE_NOT_EXISTS);
        }
        navNodeMapper.deleteById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteNavNodeList(List<Long> ids) {
        if (CollUtil.isEmpty(ids)) {
            return;
        }
        // 🔴 判据必须排除「同批一起被选中」的子节点：批量删一整棵子树时父子会同时被勾上，
        //    若照单删的口径判「有子节点就拒」，这个最常见的批量场景反而永远删不掉。
        //    真正要防的是留下 parentId 指向已删节点的孤儿，故只拦「不在本次选中集合里」的子节点。
        //    与 MenuServiceImpl#deleteMenuList 同口径
        Set<Long> idSet = new HashSet<>(ids);
        for (NavNodeDO child : navNodeMapper.selectListByParentIds(idSet)) {
            if (!idSet.contains(child.getId())) {
                throw exception(NAV_NODE_EXISTS_CHILDREN);
            }
        }
        navNodeMapper.deleteByIds(ids);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateNavNodeSort(List<NavNodeSortReqVO> list) {
        if (CollUtil.isEmpty(list)) {
            return;
        }
        for (NavNodeSortReqVO item : list) {
            NavNodeDO node = navNodeMapper.selectById(item.getId());
            if (node == null) {
                throw exception(NAV_NODE_NOT_EXISTS);
            }
            // 拖拽可能跨分组移动，故仍要过一遍父节点校验（含防环），避免把节点挪进自己的子树
            validateParent(node.getTerminal(), item.getParentId(), item.getId());
            NavNodeDO update = new NavNodeDO();
            update.setId(item.getId());
            update.setParentId(item.getParentId());
            update.setSort(item.getSort());
            navNodeMapper.updateById(update);
        }
    }

    @Override
    public NavNodeDO getNavNode(Long id) {
        return navNodeMapper.selectById(id);
    }

    @Override
    public List<NavNodeDO> getNavNodeList(NavNodeListReqVO reqVO) {
        return navNodeMapper.selectList(reqVO);
    }

    @Override
    public List<NavNodeTreeRespVO> getNavTree(String terminal, Set<String> allowedPermissions) {
        String effectiveTerminal = resolveTerminal(terminal);
        List<NavNodeDO> nodes = navNodeMapper.selectListByTerminal(effectiveTerminal);
        if (CollUtil.isEmpty(nodes)) {
            return new ArrayList<>();
        }
        // 按 parentId 归拢子节点；nodes 已按 (sort, id) 升序，故每个子列表天然有序
        Map<Long, List<NavNodeDO>> childrenMap = new LinkedHashMap<>();
        for (NavNodeDO node : nodes) {
            Long parentId = node.getParentId() == null ? ID_ROOT : node.getParentId();
            childrenMap.computeIfAbsent(parentId, k -> new ArrayList<>()).add(node);
        }
        return buildVisibleChildren(ID_ROOT, childrenMap, allowedPermissions);
    }

    /**
     * 递归构建「可见」子树
     *
     * <p>两条规则叠加（见 {@link NavNodeService#getNavTree} 的 javadoc）：
     * <ol>
     *   <li>节点自身门槛：{@code status} 正常 + {@code visible=1} + {@code permissions} 命中</li>
     *   <li>分组 / 子分组：自身门槛通过后，还须<b>至少有一个可见子节点</b>，否则整枝隐藏；
     *       叶子（{@link NavNodeTypeEnum#ITEM}）只看自身门槛</li>
     * </ol>
     * 因为「自身门槛」里已经含了 {@code status}，停用的分组会在这一层被 {@code continue} 掉、
     * 不再递归其子节点，于是「停用则整枝不展示」自然成立，无需再单独级联一次
     */
    private List<NavNodeTreeRespVO> buildVisibleChildren(Long parentId,
                                                         Map<Long, List<NavNodeDO>> childrenMap,
                                                         Set<String> allowedPermissions) {
        List<NavNodeDO> children = childrenMap.get(parentId);
        List<NavNodeTreeRespVO> result = new ArrayList<>();
        if (children == null) {
            return result;
        }
        for (NavNodeDO node : children) {
            if (!passOwnGate(node, allowedPermissions)) {
                continue;
            }
            if (NavNodeTypeEnum.ITEM.getType().equals(node.getNodeType())) {
                // 叶子：直接收，不递归
                result.add(BeanUtils.toBean(node, NavNodeTreeRespVO.class));
            } else {
                // 分组 / 子分组：无可见子节点则整枝隐藏
                List<NavNodeTreeRespVO> sub = buildVisibleChildren(node.getId(), childrenMap, allowedPermissions);
                if (sub.isEmpty()) {
                    continue;
                }
                NavNodeTreeRespVO vo = BeanUtils.toBean(node, NavNodeTreeRespVO.class);
                vo.setChildren(sub);
                result.add(vo);
            }
        }
        return result;
    }

    /**
     * 节点自身是否通过展示门槛（不含「分组是否有可见子节点」那一条）
     */
    private boolean passOwnGate(NavNodeDO node, Set<String> allowedPermissions) {
        // 1. 停用则不展示（并因不再递归而级联隐藏整枝）
        if (CommonStatusEnum.isDisable(node.getStatus())) {
            return false;
        }
        // 2. visible=false 不展示（null 视为可见，与 DDL 默认 b'1' 一致）
        if (Boolean.FALSE.equals(node.getVisible())) {
            return false;
        }
        // 3. 权限门槛
        return permissionsMatch(node.getPermissions(), allowedPermissions);
    }

    /**
     * 权限命中判定：{@code permissions} 为空 = 不设门槛；否则任一命中即可见
     *
     * <p>沿用移动端 {@code MenuItem.permissions} 的「任一命中」语义（{@code index.ts:90-96}），
     * 故这里是 OR 而非 AND
     */
    private boolean permissionsMatch(String permissions, Set<String> allowedPermissions) {
        if (StrUtil.isBlank(permissions)) {
            return true;
        }
        if (CollUtil.isEmpty(allowedPermissions)) {
            return false;
        }
        for (String raw : permissions.split(",")) {
            String code = raw.trim();
            if (!code.isEmpty() && allowedPermissions.contains(code)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 解析 + 校验端标识：空则默认 {@link NavTerminalEnum#APP}，非法则拒绝
     *
     * <p>打错的 terminal 不会报错、只会让那棵树永远查不出来（静默的空工作台），故必须在这里挡住
     */
    private String resolveTerminal(String terminal) {
        String effective = StrUtil.isBlank(terminal) ? NavTerminalEnum.APP.getTerminal() : terminal.trim();
        if (!NavTerminalEnum.isValid(effective)) {
            throw exception(NAV_NODE_TERMINAL_INVALID, effective);
        }
        return effective;
    }

    /**
     * 初始化 NOT NULL 列的缺省值 —— 这些列在 DDL 里是 NOT NULL，
     * 若 VO 传 null 又不兜底，MyBatis-Plus 会把 null 塞进 insert 从而违反约束
     */
    private void initNavNodeProperty(NavNodeDO node) {
        if (node.getParentId() == null) {
            node.setParentId(ID_ROOT);
        }
        if (node.getIsTabbar() == null) {
            node.setIsTabbar(false);
        }
        if (node.getOnlyPc() == null) {
            node.setOnlyPc(false);
        }
        if (node.getVisible() == null) {
            node.setVisible(true);
        }
        if (node.getSort() == null) {
            node.setSort(0);
        }
        if (node.getStatus() == null) {
            node.setStatus(CommonStatusEnum.ENABLE.getStatus());
        }
        // 可空字符串列统一置空串，避免 null 与 '' 两种「无值」在权限 split / 前端渲染里分叉
        if (node.getRoute() == null) {
            node.setRoute("");
        }
        if (node.getPermissions() == null) {
            node.setPermissions("");
        }
        if (node.getIcon() == null) {
            node.setIcon("");
        }
        if (node.getIconColor() == null) {
            node.setIconColor("");
        }
        if (node.getQuery() == null) {
            node.setQuery("");
        }
    }

    /**
     * 校验父节点是否合法：不能是自己、必须存在且同端、且（改父时）不能是自己的子孙（防环）
     */
    @VisibleForTesting
    void validateParent(String terminal, Long parentId, Long childId) {
        if (parentId == null || ID_ROOT.equals(parentId)) {
            return;
        }
        if (parentId.equals(childId)) {
            throw exception(NAV_NODE_PARENT_ERROR);
        }
        NavNodeDO parent = navNodeMapper.selectById(parentId);
        if (parent == null || !parent.getTerminal().equals(terminal)) {
            throw exception(NAV_NODE_PARENT_NOT_EXISTS);
        }
        // 改父时防环：若新父是当前节点的子孙，挪过去就成环
        if (childId != null && isDescendantOf(parentId, childId)) {
            throw exception(NAV_NODE_PARENT_IS_CHILD);
        }
    }

    /**
     * 从 {@code startId} 沿 parentId 向上走，能否走到 {@code ancestorId}
     *
     * <p>带 {@code seen} 兜底：万一库里已存在环，也不会让这里无限递归
     */
    private boolean isDescendantOf(Long startId, Long ancestorId) {
        Long cur = startId;
        Set<Long> seen = new HashSet<>();
        while (cur != null && !ID_ROOT.equals(cur)) {
            if (cur.equals(ancestorId)) {
                return true;
            }
            if (!seen.add(cur)) {
                break;
            }
            NavNodeDO node = navNodeMapper.selectById(cur);
            if (node == null) {
                break;
            }
            cur = node.getParentId();
        }
        return false;
    }

    /**
     * 校验同级下是否重名
     */
    @VisibleForTesting
    void validateName(String terminal, Long parentId, String name, Long id) {
        NavNodeDO exist = navNodeMapper.selectByParentIdAndName(terminal, parentId, name);
        if (exist == null) {
            return;
        }
        if (id == null || !exist.getId().equals(id)) {
            throw exception(NAV_NODE_NAME_DUPLICATE);
        }
    }

}
