package com.slb.mining_backend.modules.admin.controller;

import com.slb.mining_backend.common.api.ApiResponse;
import com.slb.mining_backend.modules.admin.dto.AppVersionUpdateDto;
import com.slb.mining_backend.modules.system.service.AppVersionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/app-version")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "管理员/版本配置", description = "管理员在线更新客户端版本号与强更策略")
public class AdminAppVersionController {

    private final AppVersionService appVersionService;

    public AdminAppVersionController(AppVersionService appVersionService) {
        this.appVersionService = appVersionService;
    }

    @PutMapping
    @Operation(
            summary = "在线更新版本配置",
            description = """
                    管理员在线更新各平台的版本配置（最新版本、最低可用版本、下载地址、更新说明等）。

                    示例请求 (cURL):
                    curl -X PUT "http://localhost:8080/api/v1/admin/app-version" \\
                      -H "Authorization: Bearer <admin-token>" \\
                      -H "Content-Type: application/json" \\
                      -d '{
                        "platform": "windows",
                        "channel": "stable",
                        "latestVersion": "1.2.3",
                        "minSupportedVersion": "1.1.0",
                        "forceUpdate": false,
                        "downloadUrl": "https://example.com/download",
                        "releaseNotes": "修复若干已知问题",
                        "status": 1
                      }'
                    """
    )
    public ApiResponse<Void> upsert(
            @Parameter(description = "版本配置请求体", required = true)
            @Valid @RequestBody AppVersionUpdateDto dto) {
        appVersionService.upsert(dto);
        return ApiResponse.ok();
    }
}


