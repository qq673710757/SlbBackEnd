package com.slb.mining_backend.modules.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "管理员在线更新版本配置请求体 / Admin app version update request")
public class AppVersionUpdateDto {

    @NotBlank(message = "platform 不能为空")
    @Schema(description = "平台：windows/mac/linux/android/ios", example = "windows", requiredMode = Schema.RequiredMode.REQUIRED)
    private String platform;

    @Schema(description = "渠道：stable/beta，默认 stable", example = "stable")
    private String channel;

    @NotBlank(message = "latestVersion 不能为空")
    @Schema(description = "最新版本号（展示用）", example = "1.2.3", requiredMode = Schema.RequiredMode.REQUIRED)
    private String latestVersion;

    @Schema(description = "最低可用版本号（低于该版本视为强制更新）", example = "1.1.0")
    private String minSupportedVersion;

    @Schema(description = "是否强制更新（也可由 minSupportedVersion 推导）", example = "false")
    private Boolean forceUpdate;

    @Schema(description = "下载地址", example = "https://example.com/download")
    private String downloadUrl;

    @Schema(description = "Tauri updater 下载地址（Windows：*.msi；用于客户端内更新；要求精确指向该文件）",
            example = "https://example.com/updates/windows-x86_64/stable/app_0.1.3.msi")
    private String updaterUrl;

    @Schema(description = """
            Tauri updater signature（最终入库/下发均为：*.sig 文件内容本身（minisign 文本））。
            
            允许两种输入：
            - 直接传 *.sig 文件内容（推荐，minisign 多行原文）
            - 传 “*.sig 文件内容的 Base64”（兼容旧数据；服务端会先解码成 minisign 文本再入库）
            """,
            example = "SIG_FILE_CONTENTS")
    private String updaterSignature;

    @Schema(description = "更新说明", example = "修复若干已知问题，提升稳定性")
    private String releaseNotes;

    @Schema(description = "状态：1=启用 0=停用；默认 1", example = "1")
    private Integer status;
}


