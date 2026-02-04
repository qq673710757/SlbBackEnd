package com.slb.mining_backend.modules.admin.controller;

import com.slb.mining_backend.common.api.ApiResponse;
import com.slb.mining_backend.common.security.CustomUserDetails;
import com.slb.mining_backend.modules.admin.dto.CommissionRateUpdateDto;
import com.slb.mining_backend.modules.admin.vo.CommissionRateVo;
import com.slb.mining_backend.modules.system.entity.PlatformCommissionRate;
import com.slb.mining_backend.modules.system.service.PlatformSettingsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/settings")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "管理员/系统设置", description = "平台抽佣比例配置")
public class AdminSettingsController {

    private final PlatformSettingsService platformSettingsService;

    public AdminSettingsController(PlatformSettingsService platformSettingsService) {
        this.platformSettingsService = platformSettingsService;
    }

    @GetMapping("/commission-rate")
    @Operation(summary = "查询抽佣比例设置")
    public ApiResponse<CommissionRateVo> getCommissionRate() {
        PlatformCommissionRate setting = platformSettingsService.getCommissionRateSetting();
        CommissionRateVo vo = new CommissionRateVo();
        vo.setRatePercent(setting.getRatePercent());
        vo.setUpdatedAt(setting.getUpdatedTime());
        vo.setUpdatedBy(setting.getUpdatedBy());
        return ApiResponse.ok(vo);
    }

    @PutMapping("/commission-rate")
    @Operation(summary = "更新抽佣比例设置")
    public ApiResponse<CommissionRateVo> updateCommissionRate(
            @Parameter(description = "抽佣比例（百分比）", required = true)
            @Valid @RequestBody CommissionRateUpdateDto dto,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        String updatedBy = userDetails != null ? userDetails.getUsername() : "admin";
        PlatformCommissionRate setting = platformSettingsService.updateCommissionRate(dto.getRatePercent(), updatedBy);
        CommissionRateVo vo = new CommissionRateVo();
        vo.setRatePercent(setting.getRatePercent());
        vo.setUpdatedAt(setting.getUpdatedTime());
        vo.setUpdatedBy(setting.getUpdatedBy());
        return ApiResponse.ok(vo);
    }
}
