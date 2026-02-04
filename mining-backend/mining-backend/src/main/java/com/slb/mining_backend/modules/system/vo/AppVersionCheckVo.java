package com.slb.mining_backend.modules.system.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Schema(description = "检查更新响应 / App version check response")
public class AppVersionCheckVo {

    @Schema(description = "平台：windows/mac/linux/android/ios", example = "windows")
    private String platform;

    @Schema(description = "渠道：stable/beta", example = "stable")
    private String channel;

    @Schema(description = "客户端当前版本", example = "1.0.0")
    private String currentVersion;

    @Schema(description = "服务端配置的最新版本（展示用）", example = "1.2.3", nullable = true)
    private String latestVersion;

    @Schema(description = "最低可用版本（低于该版本视为强更）", example = "1.1.0", nullable = true)
    private String minSupportedVersion;

    @Schema(description = "是否需要更新（current < latest）", example = "true")
    private Boolean needUpdate;

    @Schema(description = "是否强制更新（current < minSupported 或 forceUpdate=true）", example = "false")
    private Boolean forceUpdate;

    @Schema(description = "下载地址", example = "https://example.com/download", nullable = true)
    private String downloadUrl;

    @Schema(description = "更新说明", example = "修复若干已知问题，提升稳定性", nullable = true)
    private String releaseNotes;

    @Schema(description = "配置更新时间（便于客户端缓存/排障）", nullable = true)
    private LocalDateTime configUpdateTime;
}


