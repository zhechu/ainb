package cn.enlev.ainb.module.system.controller.admin.portal;

import cn.enlev.ainb.framework.common.enums.CommonStatusEnum;
import cn.enlev.ainb.framework.common.pojo.CommonResult;
import cn.enlev.ainb.framework.common.pojo.PageResult;
import cn.enlev.ainb.framework.test.core.ut.BaseMockitoUnitTest;
import cn.enlev.ainb.module.system.controller.admin.notice.vo.NoticePageReqVO;
import cn.enlev.ainb.module.system.dal.dataobject.notice.NoticeDO;
import cn.enlev.ainb.module.system.enums.notice.NoticeTypeEnum;
import cn.enlev.ainb.module.system.service.notice.NoticeService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SystemPortalDataControllerTest extends BaseMockitoUnitTest {

    @InjectMocks
    private SystemPortalDataController controller;

    @Mock
    private NoticeService noticeService;

    @Test
    void getNotices_shouldMapNoticeDataAndClampCount() {
        LocalDateTime createTime = LocalDateTime.of(2026, 9, 3, 10, 30);
        NoticeDO notice = new NoticeDO();
        notice.setId(10L);
        notice.setTitle("系统维护");
        notice.setType(NoticeTypeEnum.ANNOUNCEMENT.getType());
        notice.setContent("<p>维护内容</p>");
        notice.setStatus(CommonStatusEnum.ENABLE.getStatus());
        notice.setCreator("100");
        notice.setCreateTime(createTime);
        when(noticeService.getNoticePage(any(NoticePageReqVO.class)))
                .thenReturn(new PageResult<>(List.of(notice), 1L));

        CommonResult<List<PortalNoticeRespVO>> result = controller.getNotices(100);

        assertTrue(result.isSuccess());
        assertEquals(1, result.getData().size());
        PortalNoticeRespVO response = result.getData().get(0);
        assertEquals(10L, response.getId());
        assertEquals("公告", response.getCategory());
        assertEquals("/system/notice?id=10", response.getUrl());
        assertEquals(createTime, response.getTime());

        ArgumentCaptor<NoticePageReqVO> requestCaptor = ArgumentCaptor.forClass(NoticePageReqVO.class);
        verify(noticeService).getNoticePage(requestCaptor.capture());
        assertEquals(1, requestCaptor.getValue().getPageNo());
        assertEquals(50, requestCaptor.getValue().getPageSize());
        assertEquals(CommonStatusEnum.ENABLE.getStatus(), requestCaptor.getValue().getStatus());
    }
}
