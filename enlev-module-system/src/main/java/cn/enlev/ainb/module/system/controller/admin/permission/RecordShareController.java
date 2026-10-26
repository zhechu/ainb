package cn.enlev.ainb.module.system.controller.admin.permission;

import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.common.pojo.CommonResult;
import cn.enlev.ainb.module.system.dal.dataobject.permission.RecordShareDO;
import cn.enlev.ainb.module.system.framework.permission.core.subject.SubjectKey;
import cn.enlev.ainb.module.system.framework.permission.core.subject.SubjectTypeEnum;
import cn.enlev.ainb.module.system.service.permission.RecordShareService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.enlev.ainb.framework.common.exception.util.ServiceExceptionUtil.exception0;
import static cn.enlev.ainb.framework.common.pojo.CommonResult.success;
import static cn.enlev.ainb.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId;
import static cn.enlev.ainb.module.system.enums.ErrorCodeConstants.RECORD_SHARE_SUBJECT_CODE_REQUIRED;
import static cn.enlev.ainb.module.system.enums.ErrorCodeConstants.RECORD_SHARE_SUBJECT_REF_ID_REQUIRED;
import static cn.enlev.ainb.module.system.enums.ErrorCodeConstants.RECORD_SHARE_SUBJECT_TYPE_INVALID;

/**
 * 记录共享接口（S7b-2 补）
 *
 * <h3>⚠️ 它故意不挂 @PreAuthorize 的功能权限点</h3>
 *
 * 分享不是一个「管理员功能」，是<b>每个人对自己的单据</b>都能做的日常动作。
 * 挂上 {@code system:record-share:create} 这类权限点的话，
 * 要么给所有人授（等于没挂），要么普通用户分享不了（功能作废）。
 *
 * 真正的边界在 {@link RecordShareService#checkShareable} ——
 * 按数据集配置的发起权口径逐次判定，默认「仅归属人可分享」。
 *
 * <h3>⚠️ 取消共享 ≠ 禁止查看</h3>
 *
 * {@link #revoke} 只是删掉那条共享行。被取消的人若通过别的途径
 * （是创建人、本部门、有角色授权）本来就看得到，<b>照样看得到</b>。
 * 要真的挡住他得用 {@code sys_grant} 的 DENY。
 * 这一条必须在界面上说清楚 —— 用户会把「取消共享」理解成「禁止查看」，
 * 而这两件事的差别只有在出事时才会被发现。
 *
 * @author 走进新时代
 */
@Tag(name = "管理后台 - 记录共享")
@RestController
@RequestMapping("/system/record-share")
@Validated
public class RecordShareController {

    /**
     * 「无权分享」的错误码
     *
     * 用 {@code exception0} 直接带上 service 给出的具体原因 ——
     * 拒绝分享的原因有五六种（不是归属人 / 数据集禁止 / 判不出归属人 / 数据集没登记…），
     * 合并成一句「无权分享」会让用户完全不知道该改什么
     */
    private static final int SHARE_FORBIDDEN_CODE = 1_002_031_000;

    @Resource
    private RecordShareService recordShareService;

    @PostMapping("/share")
    @Operation(summary = "分享一条记录",
            description = "默认只有记录的创建人能分享；数据集可在 sys_resource.share_policy 上单独放开或禁止")
    public CommonResult<Long> share(@Valid @RequestBody ShareReqVO reqVO) {
        Long userId = getLoginUserId();
        String reason = recordShareService.checkShareable(userId, reqVO.getDatasetCode(), reqVO.getRecordId());
        if (reason != null) {
            throw exception0(SHARE_FORBIDDEN_CODE, reason);
        }
        return success(recordShareService.share(reqVO.getDatasetCode(), reqVO.getRecordId(),
                parseSubject(reqVO), reqVO.getExpireTime(), reqVO.getRemark()));
    }

    @DeleteMapping("/revoke")
    @Operation(summary = "取消分享",
            description = "⚠️ 这不是「禁止查看」：被取消的人若本来就看得到（是创建人、本部门、有角色授权），照样看得到")
    public CommonResult<Boolean> revoke(@Valid @RequestBody ShareReqVO reqVO) {
        Long userId = getLoginUserId();
        // 取消与分享同一道门槛：能分享才能取消。
        // 不加这道的话，任何人都能把别人配好的共享撤掉，而那是静默的
        String reason = recordShareService.checkShareable(userId, reqVO.getDatasetCode(), reqVO.getRecordId());
        if (reason != null) {
            throw exception0(SHARE_FORBIDDEN_CODE, reason);
        }
        return success(recordShareService.revoke(reqVO.getDatasetCode(), reqVO.getRecordId(),
                parseSubject(reqVO)));
    }

    @GetMapping("/list-by-record")
    @Operation(summary = "查某条记录被分享给了谁", description = "含已过期的，便于续期")
    @Parameter(name = "datasetCode", description = "数据集编码", required = true)
    @Parameter(name = "recordId", description = "业务主键", required = true)
    public CommonResult<List<ShareRespVO>> listByRecord(@RequestParam("datasetCode") String datasetCode,
                                                        @RequestParam("recordId") String recordId) {
        List<ShareRespVO> result = new ArrayList<>();
        for (RecordShareDO share : recordShareService.listByRecord(datasetCode, recordId)) {
            ShareRespVO vo = new ShareRespVO();
            vo.setId(share.getId());
            vo.setSubjectType(share.getSubjectType());
            vo.setSubjectRefId(share.getSubjectRefId());
            vo.setSubjectCode(share.getSubjectCode());
            vo.setExpireTime(share.getExpireTime());
            vo.setRemark(share.getRemark());
            // 让界面能直接区分「已过期」而不用自己算 —— 各端各算一遍必然有人算错
            vo.setExpired(share.getExpireTime() != null
                    && LocalDateTime.now().isAfter(share.getExpireTime()));
            result.add(vo);
        }
        return success(result);
    }

    @GetMapping("/check-shareable")
    @Operation(summary = "我能不能分享这条记录",
            description = "给前端用：能分享才显示「分享」按钮。⚠️ 这只是界面提示，真正的拦截在 share 接口里")
    public CommonResult<CheckRespVO> checkShareable(@RequestParam("datasetCode") String datasetCode,
                                                    @RequestParam("recordId") String recordId) {
        String reason = recordShareService.checkShareable(getLoginUserId(), datasetCode, recordId);
        CheckRespVO vo = new CheckRespVO();
        vo.setShareable(reason == null);
        vo.setReason(reason);
        return success(vo);
    }

    /**
     * 解析分享目标的主体标识
     *
     * <p>🔴 三条拒绝都必须是 {@code ServiceException}：全局异常处理器不翻译
     * {@code IllegalArgumentException}，它会变成「系统异常」+ 一条 {@code infra_api_error_log}
     * 堆栈记录。而这三条说的全是「你少给了定位这个主体所需的编号」——
     * 读成「系统异常」之后用户的下一步是原样重试，而不是去补那个字段
     */
    private SubjectKey parseSubject(ShareReqVO reqVO) {
        SubjectTypeEnum type = SubjectTypeEnum.parse(reqVO.getSubjectType());
        if (type == null) {
            throw exception(RECORD_SHARE_SUBJECT_TYPE_INVALID, reqVO.getSubjectType());
        }
        if (type.isRefBased()) {
            if (reqVO.getSubjectRefId() == null) {
                throw exception(RECORD_SHARE_SUBJECT_REF_ID_REQUIRED, type);
            }
            return SubjectKey.of(type, reqVO.getSubjectRefId());
        }
        if (StrUtil.isBlank(reqVO.getSubjectCode())) {
            throw exception(RECORD_SHARE_SUBJECT_CODE_REQUIRED, type);
        }
        return new SubjectKey(type, null, reqVO.getSubjectCode());
    }

    @Data
    public static class ShareReqVO {

        @NotBlank(message = "数据集编码不能为空")
        private String datasetCode;

        @NotBlank(message = "记录主键不能为空")
        private String recordId;

        @NotBlank(message = "被分享的主体类型不能为空")
        private String subjectType;

        private Long subjectRefId;

        private String subjectCode;

        private LocalDateTime expireTime;

        private String remark;
    }

    @Data
    public static class ShareRespVO {
        private Long id;
        private String subjectType;
        private Long subjectRefId;
        private String subjectCode;
        private LocalDateTime expireTime;
        private String remark;
        private Boolean expired;
    }

    @Data
    public static class CheckRespVO {
        private Boolean shareable;
        /**
         * 不能分享的原因。⚠️ 直接展示给用户 ——
         * 拒绝的原因有五六种，合并成一句「无权分享」会让人完全不知道该改什么
         */
        private String reason;
    }

}
