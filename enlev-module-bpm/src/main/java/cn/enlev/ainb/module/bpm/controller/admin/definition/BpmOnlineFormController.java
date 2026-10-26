package cn.enlev.ainb.module.bpm.controller.admin.definition;

import cn.enlev.ainb.framework.common.pojo.CommonResult;
import cn.enlev.ainb.framework.common.util.object.BeanUtils;
import cn.enlev.ainb.module.bpm.controller.admin.definition.vo.form.BpmOnlineFormRespVO;
import cn.enlev.ainb.module.infra.api.formdesign.FormDesignApi;
import cn.enlev.ainb.module.infra.api.formdesign.dto.FormDesignRespDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static cn.enlev.ainb.framework.common.pojo.CommonResult.success;

/**
 * 流程「在线表单」的查询接口
 *
 * 在线表单即 infra「表单设计」模块设计的表单。这里不做增删改 —— 表单的维护在表单设计模块，
 * 流程侧只负责选用。
 *
 * 为什么不直接让前端调 infra 的表单列表接口：流程侧需要的是「带流程就绪状态与不可用原因」的列表，
 * 语义与 infra 的通用列表不同（那里不关心 process_instance_id / status 两列是否齐备）。
 *
 * @author 走进新时代
 */
@Tag(name = "管理后台 - 流程在线表单")
@RestController
@RequestMapping("/bpm/online-form")
@Validated
public class BpmOnlineFormController {

    @Resource
    private FormDesignApi formDesignApi;

    @GetMapping("/list")
    @Operation(summary = "获得流程可用的在线表单列表",
            description = "返回全部已发布表单并逐个标注就绪状态。不可用的表单不做过滤而是标注原因，" +
                    "直接过滤掉会让用户不知道自己的表单为何不出现在列表里")
    @Parameter(name = "mainForm", description = "是否作为主表单校验。主表单要求业务表同时有 process_instance_id 与 status 两列；" +
            "节点表单只要求 process_instance_id")
    @PreAuthorize("@ss.hasPermission('bpm:model:query')")
    public CommonResult<List<BpmOnlineFormRespVO>> getOnlineFormList(
            @RequestParam(value = "mainForm", defaultValue = "true") Boolean mainForm) {
        List<FormDesignRespDTO> list = formDesignApi.getFlowFormList(Boolean.TRUE.equals(mainForm));
        return success(BeanUtils.toBean(list, BpmOnlineFormRespVO.class));
    }

    @GetMapping("/get")
    @Operation(summary = "获得在线表单")
    @Parameter(name = "code", description = "表单编码", required = true)
    @PreAuthorize("@ss.hasPermission('bpm:model:query')")
    public CommonResult<BpmOnlineFormRespVO> getOnlineForm(@RequestParam("code") String code) {
        // ⚠️ 这里刻意用【设计态】那一份，不做字段权限裁剪。
        //
        // 本接口服务于流程设计器（权限点是 bpm:model:query）：设计者要在表单的全部字段上
        // 配节点字段权限、变量映射。裁剪后设计者只能看到自己有权限的字段，
        // 会静默漏配 —— 而渲染给最终用户的两条路径（流程定义补齐、节点表单）
        // 走的都是 getRuntimeFormDesign，那里是裁的
        FormDesignRespDTO formDesign = formDesignApi.getFormDesign(code);
        return success(BeanUtils.toBean(formDesign, BpmOnlineFormRespVO.class));
    }

}
