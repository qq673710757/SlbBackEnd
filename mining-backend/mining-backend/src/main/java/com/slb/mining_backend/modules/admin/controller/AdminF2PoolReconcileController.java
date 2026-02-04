package com.slb.mining_backend.modules.admin.controller;

import com.slb.mining_backend.common.api.ApiResponse;
import com.slb.mining_backend.modules.admin.service.AdminF2PoolReconcileService;
import com.slb.mining_backend.modules.xmr.entity.F2PoolReconcileReport;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/f2pool/reconcile")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "管理员/F2Pool 对账", description = "用于查询 F2Pool 对账/偏差报表")
public class AdminF2PoolReconcileController {

    private final AdminF2PoolReconcileService reconcileService;

    public AdminF2PoolReconcileController(AdminF2PoolReconcileService reconcileService) {
        this.reconcileService = reconcileService;
    }

    @GetMapping
    @Operation(summary = "查询对账报告")
    public ApiResponse<List<F2PoolReconcileReport>> listReports(
            @Parameter(description = "账户名", required = true) @RequestParam String account,
            @Parameter(description = "币种", required = true) @RequestParam String coin,
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok(reconcileService.listLatest(account, coin, limit));
    }
}
