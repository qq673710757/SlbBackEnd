package com.slb.mining_backend.modules.system.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Schema(description = "系统公告实体 / System announcement entity")
public class Announcement {

    @Schema(description = "公告 ID。/ Announcement identifier.", example = "1")
    private Integer id;

    @Schema(description = "公告标题。/ Announcement title.", example = "系统升级维护通知")
    private String title;

    @Schema(description = "公告正文内容。/ Announcement content.", example = "为了提供更好的服务，我们将于今晚 00:00-02:00 进行系统升级维护。")
    private String content;

    @Schema(description = "是否为重要公告。/ Whether the announcement is marked as important.", example = "true")
    private Boolean isImportant;

    @Schema(description = "公告状态，例如 1=生效，0=下线。/ Announcement status, e.g. 1=active, 0=disabled.", example = "1")
    private Integer status;

    @Schema(description = "公告创建时间。/ Announcement creation time.", example = "2025-11-18T09:00:00")
    private LocalDateTime createTime;
}