package cn.enlev.ainb.module.system.controller.admin.permission;

import cn.enlev.ainb.framework.common.pojo.CommonResult;
import cn.enlev.ainb.framework.security.core.util.SecurityFrameworkUtils;
import cn.enlev.ainb.module.system.controller.admin.permission.vo.resource.ResourceRespVO;
import cn.enlev.ainb.module.system.dal.dataobject.permission.GrantDO;
import cn.enlev.ainb.module.system.dal.dataobject.permission.ResourceDO;
import cn.enlev.ainb.module.system.dal.mysql.permission.GrantMapper;
import cn.enlev.ainb.module.system.framework.permission.core.subject.SubjectKey;
import cn.enlev.ainb.module.system.service.permission.GrantDecisionService;
import cn.enlev.ainb.module.system.service.permission.SubjectClosureService;
import cn.enlev.ainb.framework.common.util.object.BeanUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.Data;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import static cn.enlev.ainb.framework.common.pojo.CommonResult.success;

/**
 * 管理后台 - 权限诊断（S8d）
 *
 * 回答「这个人为什么有/没有这个权限」。
 * 在鉴权引擎完全切到 sys_grant 之后，这是排查权限问题的唯一入口。
 * 不挂复杂权限点 —— 看诊断结果的往往是超管或运维，挂太细反而自己把自己堵住。
 *
 * @author 走进新时代
 */
@Tag(name = "管理后台 - 权限诊断")
@RestController
@RequestMapping("/system/permission-diag")
@Validated
public class PermissionDiagController {

    @Resource
    private SubjectClosureService subjectClosureService;
    @Resource
    private GrantDecisionService grantDecisionService;
    @Resource
    private GrantMapper grantMapper;

    /**
     * 诊断某用户对某权限点的决策路径
     *
     * <p>返回的信息足以回答：
     * <ul>
     *   <li>用户的主体闭包（角色继承路径）</li>
     *   <li>权限点对应的资源链（祖先链，决定「继承哪些父资源的授权」）</li>
     *   <li>命中的授权行，及最终决策结果</li>
     *   <li>最终没有命中授权时回落到的 default_effect</li>
     * </ul>
     */
    @GetMapping("/check")
    @Operation(summary = "诊断用户是否对指定权限点有权限",
            description = "返回决策路径，包含主体闭包、资源链、命中授权行、最终结论")
    @Parameter(name = "userId", description = "被诊断的用户 id，不填时诊断当前登录用户", required = false)
    @Parameter(name = "permission", description = "权限标识，如 system:user:query", required = true)
    @Parameter(name = "action", description = "动作，默认 VIEW", required = false)
    @PreAuthorize("@ss.hasPermission('system:permission-diag:query')")
    public CommonResult<DiagRespVO> check(
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestParam("permission") String permission,
            @RequestParam(value = "action", defaultValue = "VIEW") String action) {

        Long targetUserId = userId != null ? userId
                : SecurityFrameworkUtils.getLoginUserId();

        DiagRespVO resp = new DiagRespVO();
        resp.setUserId(targetUserId);
        resp.setPermission(permission);
        resp.setAction(action);

        // 1. 主体闭包
        Set<SubjectKey> subjects = subjectClosureService.getSubjectKeysByUserId(targetUserId);
        resp.setSubjects(subjects.stream()
                .map(s -> s.type().name() + ":" + (s.refId() != null ? s.refId() : s.code()))
                .sorted().collect(Collectors.toList()));

        // 2. 资源链
        List<ResourceDO> chain = grantDecisionService.resolveResourceChain(permission);
        resp.setResourceChain(BeanUtils.toBean(chain, ResourceRespVO.class));

        if (chain.isEmpty()) {
            resp.setConclusion("DENIED");
            resp.setReason("资源表里没有 permission=" + permission + " 的记录（严格模式：找不到资源 = 无权限）");
            return success(resp);
        }

        // 3. 命中授权行
        Set<Long> chainIds = chain.stream().map(ResourceDO::getId).collect(Collectors.toCollection(LinkedHashSet::new));
        List<GrantDO> grants = grantMapper.selectListByResourceIds(chainIds);

        List<DiagRespVO.GrantRow> matchedRows = new ArrayList<>();
        for (GrantDO g : grants) {
            DiagRespVO.GrantRow row = new DiagRespVO.GrantRow();
            row.setId(g.getId());
            row.setSubjectType(g.getSubjectType());
            row.setSubjectRefId(g.getSubjectRefId());
            row.setSubjectCode(g.getSubjectCode());
            row.setResourceId(g.getResourceId());
            row.setAction(g.getAction());
            row.setEffect(g.getEffect());
            row.setExpireTime(g.getExpireTime());
            row.setActive(g.isActive());
            boolean subjectHit = subjects.stream().anyMatch(g::subjectMatches);
            boolean actionHit = g.actionMatches(action);
            row.setSubjectHit(subjectHit);
            row.setActionHit(actionHit);
            row.setEffective(subjectHit && actionHit && g.isActive());
            matchedRows.add(row);
        }
        resp.setAllGrants(matchedRows);

        // 4. 最终决策
        boolean finalResult = grantDecisionService.hasPermission(targetUserId, permission, null, action);
        resp.setConclusion(finalResult ? "ALLOWED" : "DENIED");

        List<DiagRespVO.GrantRow> effective = matchedRows.stream()
                .filter(DiagRespVO.GrantRow::isEffective).collect(Collectors.toList());
        if (effective.isEmpty()) {
            String defaultEffect = chain.get(0).getDefaultEffect();
            resp.setReason("没有命中任何有效授权，回落到资源的 default_effect=" + defaultEffect);
        } else {
            boolean hasDeny = effective.stream().anyMatch(r -> "DENY".equals(r.getEffect()));
            resp.setReason(hasDeny
                    ? "存在有效的 DENY 授权（id=" + effective.stream()
                    .filter(r -> "DENY".equals(r.getEffect()))
                    .map(r -> String.valueOf(r.getId()))
                    .collect(Collectors.joining(",")) + "），DENY 优先级高于所有 ALLOW"
                    : "存在有效的 ALLOW 授权（id=" + effective.stream()
                    .filter(r -> "ALLOW".equals(r.getEffect()))
                    .map(r -> String.valueOf(r.getId()))
                    .collect(Collectors.joining(",")) + "）");
        }
        return success(resp);
    }

    @Data
    public static class DiagRespVO {
        private Long userId;
        private String permission;
        private String action;
        private List<String> subjects;
        private List<ResourceRespVO> resourceChain;
        private List<GrantRow> allGrants;
        private String conclusion;  // ALLOWED | DENIED
        private String reason;

        @Data
        public static class GrantRow {
            private Long id;
            private String subjectType;
            private Long subjectRefId;
            private String subjectCode;
            private Long resourceId;
            private String action;
            private String effect;
            private LocalDateTime expireTime;
            private boolean active;
            private boolean subjectHit;
            private boolean actionHit;
            private boolean effective;
        }
    }

}
