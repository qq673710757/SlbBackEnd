package com.slb.mining_backend.modules.admin.controller;

import com.slb.mining_backend.common.api.ApiResponse;
import com.slb.mining_backend.modules.admin.service.AdminDashboardService;
import com.slb.mining_backend.modules.admin.vo.DeviceSummaryVo;
import com.slb.mining_backend.modules.admin.vo.UserAssetsSummaryVo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/dashboard")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "管理员/仪表盘", description = "管理员仪表盘统计汇总")
public class AdminDashboardController {

    private final AdminDashboardService dashboardService;

    public AdminDashboardController(AdminDashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping("/device-summary")
    @Operation(summary = "设备数量 + 上报算力汇总")
    public ApiResponse<DeviceSummaryVo> deviceSummary() {
        return ApiResponse.ok(dashboardService.getDeviceSummary());
    }

    @GetMapping("/user-assets")
    @Operation(summary = "用户持有 CAL / CNY 总额")
    public ApiResponse<UserAssetsSummaryVo> userAssets() {
        return ApiResponse.ok(dashboardService.getUserAssetsSummary());
    }
}
