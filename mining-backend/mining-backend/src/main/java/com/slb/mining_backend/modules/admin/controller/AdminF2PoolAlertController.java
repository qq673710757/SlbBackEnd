package com.slb.mining_backend.modules.admin.controller;

import com.slb.mining_backend.common.api.ApiResponse;
import com.slb.mining_backend.common.vo.PageVo;
import com.slb.mining_backend.modules.admin.service.AdminF2PoolAlertService;
import com.slb.mining_backend.modules.xmr.entity.F2PoolAlert;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/f2pool/alerts")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "管理员/F2Pool 告警", description = "用于查看/处理 F2Pool 风控告警")
public class AdminF2PoolAlertController {

    private final AdminF2PoolAlertService alertService;

    public AdminF2PoolAlertController(AdminF2PoolAlertService alertService) {
        this.alertService = alertService;
    }

    @GetMapping
    @Operation(summary = "分页查询告警")
    public ApiResponse<PageVo<F2PoolAlert>> listAlerts(
            @Parameter(description = "状态：OPEN/RESOLVED", example = "OPEN")
            @RequestParam(defaultValue = "OPEN") String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        int offset = Math.max(0, (page - 1) * size);
        long total = alertService.countByStatus(status);
        List<F2PoolAlert> list = alertService.listByStatus(status, offset, size);
        return ApiResponse.ok(new PageVo<>(total, page, size, list));
    }

    @PostMapping("/{id}/resolve")
    @Operation(summary = "确认/处理告警")
    public ApiResponse<Void> resolve(@PathVariable Long id) {
        alertService.resolveAlert(id);
        return ApiResponse.ok();
    }
}
