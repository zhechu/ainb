package cn.enlev.ainb.module.system.controller.admin.portal;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

@Data
@Accessors(chain = true)
@Schema(description = "管理后台 - 门户公告列表项")
public class PortalNoticeRespVO {

    private Long id;
    private String url;
    private String title;
    private String category;
    private String content;
    private LocalDateTime time;
    private String creator;
}
