package com.slb.mining_backend.modules.admin.controller;

import com.slb.mining_backend.common.api.ApiResponse;
import com.slb.mining_backend.common.vo.PageVo;
import com.slb.mining_backend.modules.admin.service.AdminF2PoolHourlySettlementService;
import com.slb.mining_backend.modules.xmr.entity.F2PoolPayhashHourlySettlement;
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
@RequestMapping("/api/v1/admin/f2pool/hourly-settlements")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "管理员/F2Pool 小时结算", description = "查看 F2Pool 小时 payhash 结算与保底标记")
public class AdminF2PoolHourlySettlementController {

    private final AdminF2PoolHourlySettlementService settlementService;

    public AdminF2PoolHourlySettlementController(AdminF2PoolHourlySettlementService settlementService) {
        this.settlementService = settlementService;
    }

    @GetMapping
    @Operation(summary = "分页查询小时结算")
    public ApiResponse<PageVo<F2PoolPayhashHourlySettlement>> list(
            @Parameter(description = "F2Pool 账号", example = "suanlibaovip")
            @RequestParam(required = false) String account,
            @Parameter(description = "币种", example = "conflux")
            @RequestParam(required = false) String coin,
            @Parameter(description = "开始时间（yyyy-MM-dd HH:mm:ss）", example = "2026-01-28 13:00:00")
            @RequestParam(required = false) String startTime,
            @Parameter(description = "结束时间（yyyy-MM-dd HH:mm:ss）", example = "2026-01-28 15:00:00")
            @RequestParam(required = false) String endTime,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        int offset = Math.max(0, (page - 1) * size);
        long total = settlementService.countByFilters(account, coin, startTime, endTime);
        List<F2PoolPayhashHourlySettlement> list =
                settlementService.listByFilters(account, coin, startTime, endTime, offset, size);
        return ApiResponse.ok(new PageVo<>(total, page, size, list));
    }
}
