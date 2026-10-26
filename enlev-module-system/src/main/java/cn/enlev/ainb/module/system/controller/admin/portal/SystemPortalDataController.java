package cn.enlev.ainb.module.system.controller.admin.portal;

import cn.enlev.ainb.framework.common.enums.CommonStatusEnum;
import cn.enlev.ainb.framework.common.pojo.CommonResult;
import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.module.system.controller.admin.notice.vo.NoticePageReqVO;
import cn.enlev.ainb.module.system.dal.dataobject.notice.NoticeDO;
import cn.enlev.ainb.module.system.enums.notice.NoticeTypeEnum;
import cn.enlev.ainb.module.system.service.notice.NoticeService;
import io.swagger.v3.oas.annotations.Operation;
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

@Tag(name = "管理后台 - 门户公告数据")
@RestController
@RequestMapping("/system/portal")
@Validated
public class SystemPortalDataController {

    @Resource
    private NoticeService noticeService;

    @GetMapping("/notices")
    @Operation(summary = "获得门户公告列表")
    @PreAuthorize("@ss.hasPermission('infra:portal:run')")
    public CommonResult<List<PortalNoticeRespVO>> getNotices(
            @RequestParam(defaultValue = "5") Integer count) {
        NoticePageReqVO request = new NoticePageReqVO();
        request.setPageNo(1);
        request.setPageSize(Math.max(1, Math.min(50, count == null ? 5 : count)));
        request.setStatus(CommonStatusEnum.ENABLE.getStatus());
        PageResult<NoticeDO> page = noticeService.getNoticePage(request);
        return success(page.getList().stream().map(this::toResp).toList());
    }

    private PortalNoticeRespVO toResp(NoticeDO notice) {
        return new PortalNoticeRespVO()
                .setId(notice.getId())
                .setUrl("/system/notice?id=" + notice.getId())
                .setTitle(notice.getTitle())
                .setCategory(NoticeTypeEnum.ANNOUNCEMENT.getType().equals(notice.getType()) ? "公告" : "通知")
                .setContent(notice.getContent())
                .setTime(notice.getCreateTime())
                .setCreator(notice.getCreator());
    }

}
