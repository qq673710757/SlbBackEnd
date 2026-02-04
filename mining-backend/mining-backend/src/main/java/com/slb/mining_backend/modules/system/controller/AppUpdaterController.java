package com.slb.mining_backend.modules.system.controller;

import com.slb.mining_backend.modules.system.service.AppVersionService;
import com.slb.mining_backend.modules.system.vo.TauriUpdaterManifestVo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

/**
 * Tauri plugin-updater 专用：返回 manifest（裸 JSON，不包装 ApiResponse）。
 */
@RestController
@RequestMapping("/api/v1/system/app-updater")
@Tag(name = "客户端/更新", description = "为 @tauri-apps/plugin-updater 提供 manifest")
public class AppUpdaterController {

    private final AppVersionService appVersionService;

    public AppUpdaterController(AppVersionService appVersionService) {
        this.appVersionService = appVersionService;
    }

    @GetMapping("/manifest")
    @Operation(
            summary = "获取 Tauri Updater manifest（v1 compatible）",
            description = """
                    返回 Tauri plugin-updater 所需的 manifest JSON（裸 JSON，不包装 ApiResponse）。
                    
                    - 若无配置：返回 204 No Content
                    - 若需要更新但缺少 updaterUrl/updaterSignature：返回 409（触发客户端兜底 downloadUrl）
                    
                    示例：
                    GET /api/v1/system/app-updater/manifest?target=windows-x86_64&current_version=0.1.2&channel=stable
                    """
    )
    public ResponseEntity<?> manifest(
            @Parameter(description = "Tauri target，例如 windows-x86_64 / darwin-aarch64 / linux-x86_64", required = true)
            @RequestParam String target,
            @Parameter(description = "CPU 架构（可选）。若传入且 target 不含 '-'，将拼接为 target-arch，例如 windows + x86_64 => windows-x86_64",
                    example = "x86_64")
            @RequestParam(required = false) String arch,
            @Parameter(description = "当前客户端版本号（可选），用于判断是否需要更新", example = "0.1.2")
            @RequestParam(name = "current_version", required = false) String currentVersion,
            @Parameter(description = "渠道：stable/beta，默认 stable", example = "stable")
            @RequestParam(required = false, defaultValue = "stable") String channel
    ) {
        String platformKey = buildPlatformKey(target, arch);
        Optional<TauriUpdaterManifestVo> vo = appVersionService.buildTauriUpdaterManifest(platformKey, channel, currentVersion);
        return vo.<ResponseEntity<?>>map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    private String buildPlatformKey(String target, String arch) {
        if (target == null) return "";
        String t = target.trim();
        if (t.contains("-")) {
            return t;
        }
        if (arch == null || arch.trim().isEmpty()) {
            return t;
        }
        return t + "-" + arch.trim();
    }
}


