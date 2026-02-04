package com.slb.mining_backend.modules.admin.controller;

import com.slb.mining_backend.common.api.ApiResponse;
import com.slb.mining_backend.modules.admin.service.AdminF2PoolWorkerService;
import com.slb.mining_backend.modules.xmr.entity.F2PoolWorkerSnapshot;
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
@RequestMapping("/api/v1/admin/f2pool/workers")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "管理员/F2Pool Worker", description = "用于查看未归属 worker 列表")
public class AdminF2PoolWorkerController {

    private final AdminF2PoolWorkerService workerService;

    public AdminF2PoolWorkerController(AdminF2PoolWorkerService workerService) {
        this.workerService = workerService;
    }

    @GetMapping("/unclaimed")
    @Operation(summary = "查询未归属 worker")
    public ApiResponse<List<F2PoolWorkerSnapshot>> listUnclaimed(
            @Parameter(description = "账户名", required = true) @RequestParam String account,
            @Parameter(description = "币种", required = true) @RequestParam String coin,
            @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.ok(workerService.listLatestUnclaimed(account, coin, limit));
    }
}
