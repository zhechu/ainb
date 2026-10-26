package cn.enlev.ainb.module.system.controller.admin.permission;

import cn.enlev.ainb.framework.common.pojo.CommonResult;
import cn.enlev.ainb.module.system.controller.admin.permission.vo.subject.SubjectRespVO;
import cn.enlev.ainb.module.system.dal.dataobject.permission.SubjectDO;
import cn.enlev.ainb.module.system.dal.mysql.permission.SubjectMapper;
import cn.enlev.ainb.module.system.framework.permission.core.subject.SubjectTypeEnum;
import cn.enlev.ainb.framework.common.util.object.BeanUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static cn.enlev.ainb.framework.common.pojo.CommonResult.success;

/**
 * 管理后台 - 授权主体（S8a）
 *
 * 只提供查询。主体按需创建，由 SubjectService 的写路径维护，
 * 这里负责让授权配置页能选「把权限授给谁」。
 * 角色、用户、部门本身的 CRUD 走各自的管理页，不在这里重复。
 */
@Tag(name = "管理后台 - 授权主体")
@RestController
@RequestMapping("/system/subject")
@Validated
public class SubjectAdminController {

    @Resource
    private SubjectMapper subjectMapper;

    @GetMapping("/list-by-type")
    @Operation(summary = "按主体类型查询主体列表", description = "授权配置页的主体下拉框使用")
    @Parameter(name = "type", description = "主体类型：ROLE/USER/DEPT/POST/GROUP/SYSTEM/ALL", required = true)
    @PreAuthorize("@ss.hasPermission('system:subject:query')")
    public CommonResult<List<SubjectRespVO>> listByType(@RequestParam("type") String type) {
        SubjectTypeEnum typeEnum = SubjectTypeEnum.parse(type);
        if (typeEnum == null) {
            return success(List.of());
        }
        List<SubjectDO> list = subjectMapper.selectListByType(typeEnum);
        return success(BeanUtils.toBean(list, SubjectRespVO.class));
    }

}
