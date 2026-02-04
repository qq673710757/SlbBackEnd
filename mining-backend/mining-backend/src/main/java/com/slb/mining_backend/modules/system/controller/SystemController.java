package com.slb.mining_backend.modules.system.controller;

import com.slb.mining_backend.common.api.ApiResponse;
import com.slb.mining_backend.common.security.CustomUserDetails;
import com.slb.mining_backend.common.vo.PageVo;
import com.slb.mining_backend.modules.system.dto.FeedbackSubmitDto;
import com.slb.mining_backend.modules.system.entity.Announcement;
import com.slb.mining_backend.modules.system.service.AppVersionService;
import com.slb.mining_backend.modules.system.service.SystemService;
import com.slb.mining_backend.modules.system.vo.AndroidAppVersionVo;
import com.slb.mining_backend.modules.system.vo.AppVersionCheckVo;
import com.slb.mining_backend.modules.system.vo.SystemStatusVo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.lang.Nullable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.util.StringUtils;

@RestController
@RequestMapping("/api/v1/system")
@Tag(name = "公共/系统", description = "提供公告查询、系统状态查询及用户反馈提交等系统级接口")
public class SystemController {

    private final SystemService systemService;
    private final AppVersionService appVersionService;

    public SystemController(SystemService systemService, AppVersionService appVersionService) {
        this.systemService = systemService;
        this.appVersionService = appVersionService;
    }

    @GetMapping("/announcements")
    @Operation(
            summary = "分页查询系统公告",
            description = """
                    按时间倒序分页查询平台发布的公告信息。
                    
                    示例请求 (cURL):
                    curl -X GET "http://localhost:8080/api/v1/system/announcements?page=1&size=5"
                    
                    示例响应 (JSON):
                    {
                      "code": 0,
                      "message": "ok",
                      "data": {
                        "total": 1,
                        "page": 1,
                        "size": 5,
                        "list": [
                          {
                            "id": 1,
                            "title": "系统升级维护通知",
                            "content": "为了提供更好的服务，我们将于今晚 00:00-02:00 进行系统升级维护。",
                            "isImportant": true,
                            "status": 1,
                            "createTime": "2025-11-18T09:00:00"
                          }
                        ]
                      },
                      "traceId": "b3f7e6c9a1d24c31"
                    }
                    """
    )
    public ApiResponse<PageVo<Announcement>> getAnnouncements(
            @Parameter(description = "页码，从 1 开始", example = "1")
            @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "每页数量", example = "5")
            @RequestParam(defaultValue = "5") int size) {
        return ApiResponse.ok(systemService.getAnnouncements(page, size));
    }

    @GetMapping("/status")
    @Operation(
            summary = "获取系统运行状态",
            description = """
                    查询当前系统的核心服务状态、算力概况等，用于健康检查和展示。
                    
                    示例请求 (cURL):
                    curl -X GET "http://localhost:8080/api/v1/system/status"
                    
                    示例响应 (JSON):
                    {
                      "code": 0,
                      "message": "ok",
                      "data": {
                        "totalDevices": 120,
                        "onlineDevices": 80,
                        "totalUsers": 1000,
                        "activeUsers": 200,
                        "totalCpuHashrate": 500000,
                        "totalGpuHashrate": 2000000,
                        "calToCnyRate": 0.85,
                        "serverStatus": "OK",
                        "maintenancePlanned": false
                      },
                      "traceId": "b3f7e6c9a1d24c31"
                    }
                    """
    )
    public ApiResponse<SystemStatusVo> getSystemStatus() {
        return ApiResponse.ok(systemService.getSystemStatus());
    }

    @PostMapping("/feedback")
    @Operation(
            summary = "提交用户反馈",
            description = """
                    用户提交意见反馈或问题描述，登录用户会自动携带用户信息，未登录用户也可匿名提交。
                    
                    示例请求 (cURL):
                    curl -X POST "http://localhost:8080/api/v1/system/feedback" \
                      -H "Content-Type: application/json" \
                      -d '{
                        "type": "bug",
                        "content": "Windows 11 客户端在登录后白屏，控制台报错 ...",
                        "contact": "user@example.com"
                      }'
                    
                    示例响应 (JSON):
                    {
                      "code": 0,
                      "message": "ok",
                      "data": null,
                      "traceId": "b3f7e6c9a1d24c31"
                    }
                    """
    )
    public ApiResponse<Void> submitFeedback(
            @Parameter(description = "反馈内容请求体，包含标题、内容等信息", required = true)
            @Valid @RequestBody FeedbackSubmitDto dto,
            @Parameter(description = "当前登录用户信息，可为空；未登录时为 null")
            @AuthenticationPrincipal @Nullable CustomUserDetails userDetails) {
        systemService.submitFeedback(dto, userDetails);
        return ApiResponse.ok();
    }

    @GetMapping("/app-version/check")
    @Operation(
            summary = "检查客户端更新",
            description = """
                    客户端检查是否有新版本以及是否强制更新。

                    - 未登录也允许调用（需在 SecurityConfig 放行该路径）。
                    - 若未配置版本信息，将返回 needUpdate=false, forceUpdate=false。

                    示例请求 (cURL):
                    curl -X GET "http://localhost:8080/api/v1/system/app-version/check?platform=windows&channel=stable&version=1.0.0"

                    示例响应 (JSON):
                    {
                      "code": 0,
                      "message": "ok",
                      "data": {
                        "platform": "windows",
                        "channel": "stable",
                        "currentVersion": "1.0.0",
                        "latestVersion": "1.2.3",
                        "minSupportedVersion": "1.1.0",
                        "needUpdate": true,
                        "forceUpdate": false,
                        "downloadUrl": "https://example.com/download",
                        "releaseNotes": "修复若干已知问题",
                        "configUpdateTime": "2025-12-30T10:00:00"
                      },
                      "traceId": "b3f7e6c9a1d24c31"
                    }
                    """
    )
    public ApiResponse<AppVersionCheckVo> checkAppVersion(
            @Parameter(description = "平台：windows/mac/linux/android/ios", required = true, example = "windows")
            @RequestParam String platform,
            @Parameter(description = "渠道：stable/beta，默认 stable", example = "stable")
            @RequestParam(required = false, defaultValue = "stable") String channel,
            @Parameter(description = "客户端当前版本号（可选，不传则只返回配置）", example = "1.0.0")
            @RequestParam(required = false) String version) {
        String safeVersion = StringUtils.hasText(version) ? version.trim() : null;
        return ApiResponse.ok(appVersionService.check(platform, channel, safeVersion));
    }

    @GetMapping("/app-version")
    @Operation(
            summary = "获取 Android 更新信息",
            description = """
                    返回 Android 应用的最新版本信息（无需登录）。

                    返回字段：
                    - version：版本号（如 1.2.0）
                    - buildNumber：构建号（versionCode）
                    - forceUpdate：是否强制更新
                    - downloadUrl：APK 下载地址
                    - description：更新说明（支持 \\n）
                    """
    )
    public ApiResponse<AndroidAppVersionVo> getAndroidAppVersion() {
        return ApiResponse.ok(appVersionService.getAndroidAppVersion().orElse(null));
    }

}
