package com.slb.mining_backend.modules.admin.controller;

import com.slb.mining_backend.common.api.ApiResponse;
import com.slb.mining_backend.common.vo.PageVo;
import com.slb.mining_backend.modules.admin.service.AdminF2PoolSettlementService;
import com.slb.mining_backend.modules.admin.service.AdminWithdrawalService;
import com.slb.mining_backend.modules.withdraw.entity.Withdrawal;
import com.slb.mining_backend.modules.xmr.entity.F2PoolSettlement;
import com.slb.mining_backend.modules.xmr.entity.F2PoolSettlementItem;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/admin/review")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "管理员/审核", description = "结算审核与提现审核统一入口")
public class AdminReviewController {

    private final AdminF2PoolSettlementService settlementService;
    private final AdminWithdrawalService withdrawalService;

    public AdminReviewController(AdminF2PoolSettlementService settlementService,
                                 AdminWithdrawalService withdrawalService) {
        this.settlementService = settlementService;
        this.withdrawalService = withdrawalService;
    }

    @GetMapping("/settlements")
    @Operation(
            summary = "分页查询结算单",
            description = """
                    示例请求 (cURL):
                    curl -X GET "http://localhost:8080/api/v1/admin/review/settlements?status=AUDIT&page=1&size=20" \
                      -H "Authorization: Bearer <token>"
                    """
    )
    public ApiResponse<PageVo<F2PoolSettlement>> listSettlements(
            @Parameter(description = "状态：AUDIT/PAID/REJECTED", example = "AUDIT")
            @RequestParam(defaultValue = "AUDIT") String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        int offset = Math.max(0, (page - 1) * size);
        long total = settlementService.countSettlementsByStatus(status);
        List<F2PoolSettlement> list = settlementService.listSettlementsByStatus(status, offset, size);
        return ApiResponse.ok(new PageVo<>(total, page, size, list));
    }

    @GetMapping("/settlements/{id}")
    @Operation(
            summary = "获取结算单详情",
            description = """
                    示例请求 (cURL):
                    curl -X GET "http://localhost:8080/api/v1/admin/review/settlements/10001" \
                      -H "Authorization: Bearer <token>"
                    """
    )
    public ApiResponse<F2PoolSettlement> getSettlement(@PathVariable Long id) {
        Optional<F2PoolSettlement> settlement = settlementService.getSettlement(id);
        return settlement.map(ApiResponse::ok)
                .orElseGet(() -> ApiResponse.of(404, "Settlement not found", null));
    }

    @GetMapping("/settlements/{id}/items")
    @Operation(
            summary = "获取结算单明细",
            description = """
                    示例请求 (cURL):
                    curl -X GET "http://localhost:8080/api/v1/admin/review/settlements/10001/items" \
                      -H "Authorization: Bearer <token>"
                    """
    )
    public ApiResponse<List<F2PoolSettlementItem>> getItems(@PathVariable Long id) {
        return ApiResponse.ok(settlementService.getSettlementItems(id));
    }

    @PostMapping("/settlements/{id}/approve")
    @Operation(
            summary = "审核通过结算单",
            description = """
                    示例请求 (cURL):
                    curl -X POST "http://localhost:8080/api/v1/admin/review/settlements/10001/approve" \
                      -H "Authorization: Bearer <token>" \
                      -H "Content-Type: text/plain" \
                      -d "审核通过"
                    """
    )
    public ApiResponse<Void> approveSettlement(@PathVariable Long id,
                                               @RequestBody(required = false) String remark) {
        settlementService.approveSettlement(id, remark);
        return ApiResponse.ok();
    }

    @PostMapping("/settlements/{id}/reject")
    @Operation(
            summary = "拒绝结算单",
            description = """
                    示例请求 (cURL):
                    curl -X POST "http://localhost:8080/api/v1/admin/review/settlements/10001/reject" \
                      -H "Authorization: Bearer <token>" \
                      -H "Content-Type: text/plain" \
                      -d "资料不完整"
                    """
    )
    public ApiResponse<Void> rejectSettlement(@PathVariable Long id,
                                              @RequestBody(required = false) String remark) {
        settlementService.rejectSettlement(id, remark);
        return ApiResponse.ok();
    }

    @GetMapping("/withdrawals/pending")
    @Operation(
            summary = "分页查询待审核提现单",
            description = """
                    示例请求 (cURL):
                    curl -X GET "http://localhost:8080/api/v1/admin/review/withdrawals/pending?page=1&size=10" \
                      -H "Authorization: Bearer <token>"
                    """
    )
    public ApiResponse<PageVo<Withdrawal>> getPendingWithdrawals(
            @Parameter(description = "页码，从 1 开始", example = "1")
            @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "每页数量", example = "10")
            @RequestParam(defaultValue = "10") int size) {
        PageVo<Withdrawal> result = withdrawalService.getPendingWithdrawals(page, size);
        return ApiResponse.ok(result);
    }

    @PostMapping("/withdrawals/{id}/approve")
    @Operation(
            summary = "审核通过提现申请",
            description = """
                    示例请求 (cURL):
                    curl -X POST "http://localhost:8080/api/v1/admin/review/withdrawals/10001/approve" \
                      -H "Authorization: Bearer <token>" \
                      -H "Content-Type: text/plain" \
                      -d "审核通过，已打款。"
                    """
    )
    public ApiResponse<Void> approveWithdrawal(
            @Parameter(description = "提现单 ID", required = true, example = "10001")
            @PathVariable Long id,
            @Parameter(description = "审核备注信息，可选，用于记录审核说明")
            @RequestBody(required = false) String remark) {
        withdrawalService.approveWithdrawal(id, remark);
        return ApiResponse.ok();
    }

    @PostMapping("/withdrawals/{id}/reject")
    @Operation(
            summary = "拒绝提现申请",
            description = """
                    示例请求 (cURL):
                    curl -X POST "http://localhost:8080/api/v1/admin/review/withdrawals/10001/reject" \
                      -H "Authorization: Bearer <token>" \
                      -H "Content-Type: text/plain" \
                      -d "账户信息不匹配"
                    """
    )
    public ApiResponse<Void> rejectWithdrawal(
            @Parameter(description = "提现单 ID", required = true, example = "10001")
            @PathVariable Long id,
            @Parameter(description = "拒绝原因或备注信息", required = true)
            @RequestBody String remark) {
        withdrawalService.rejectWithdrawal(id, remark);
        return ApiResponse.ok();
    }
}
