package com.slb.mining_backend.modules.system.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@Schema(description = "应用版本配置实体 / App version configuration entity")
public class AppVersionConfig implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键 ID", example = "1")
    private Long id;

    @Schema(description = "平台：windows/mac/linux/android/ios", example = "windows")
    private String platform;

    @Schema(description = "渠道：stable/beta", example = "stable")
    private String channel;

    @Schema(description = "最新版本号（展示用）", example = "1.2.3")
    private String latestVersion;

    @Schema(description = "最低可用版本号（低于该版本视为强制更新）", example = "1.1.0")
    private String minSupportedVersion;

    @Schema(description = "是否强制更新（也可由 minSupportedVersion 推导）", example = "false")
    private Boolean forceUpdate;

    @Schema(description = "下载地址", example = "https://example.com/download")
    private String downloadUrl;

    @Schema(description = "Tauri updater 下载地址（Windows：*.msi；要求精确指向该文件）", example = "https://example.com/updates/windows-x86_64/stable/app_0.1.3.msi")
    private String updaterUrl;

    @Schema(description = "Tauri updater signature（最终存储为：*.sig 文件内容本身，minisign 文本）", example = "SIG_FILE_CONTENTS")
    private String updaterSignature;

    @Schema(description = "更新说明", example = "修复若干已知问题，提升稳定性")
    private String releaseNotes;

    @Schema(description = "状态：1=启用 0=停用", example = "1")
    private Integer status;

    @Schema(description = "更新时间")
    private LocalDateTime updateTime;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}


