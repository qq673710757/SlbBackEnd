package com.slb.mining_backend.modules.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class AnnouncementCreateDto {
    @NotBlank(message = "公告标题不能为空")
    private String title;

    @NotBlank(message = "公告内容不能为空")
    private String content;

    @NotNull(message = "必须指定是否为重要公告")
    private Boolean isImportant;

    @NotNull(message = "必须指定公告状态")
    private Integer status; // 1: 直接发布, 0: 保存为草稿
}