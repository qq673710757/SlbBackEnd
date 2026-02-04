package com.slb.mining_backend.modules.admin.controller;

import com.slb.mining_backend.common.api.ApiResponse;
import com.slb.mining_backend.common.exception.BizException;
import com.slb.mining_backend.common.security.CustomUserDetails;
import com.slb.mining_backend.common.vo.PageVo;
import com.slb.mining_backend.modules.admin.dto.WithdrawalAuditDto;
import com.slb.mining_backend.modules.admin.service.AdminWithdrawalService;
import com.slb.mining_backend.modules.admin.vo.AdminWithdrawalAuditResultVo;
import com.slb.mining_backend.modules.admin.vo.AdminWithdrawalListItemVo;
import com.slb.mining_backend.modules.withdraw.entity.Withdrawal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

@RestController
@RequestMapping("/api/v1/admin/withdrawals")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "管理员/提现", description = "提现审核与记录查询")
public class AdminWithdrawalController {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AdminWithdrawalService withdrawalService;

    public AdminWithdrawalController(AdminWithdrawalService withdrawalService) {
        this.withdrawalService = withdrawalService;
    }

    @GetMapping
    @Operation(summary = "提现列表")
    public ApiResponse<PageVo<AdminWithdrawalListItemVo>> listWithdrawals(
            @Parameter(description = "页码，从 1 开始", example = "1")
            @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "每页数量", example = "10")
            @RequestParam(defaultValue = "10") int size,
            @Parameter(description = "状态（PENDING/APPROVED/REJECTED 或 0/1/2）")
            @RequestParam(required = false) String status,
            @Parameter(description = "用户ID", example = "1001")
            @RequestParam(required = false) Long uid,
            @Parameter(description = "开始日期（yyyy-MM-dd）", example = "2026-02-01")
            @RequestParam(required = false) String dateStart,
            @Parameter(description = "结束日期（yyyy-MM-dd）", example = "2026-02-03")
            @RequestParam(required = false) String dateEnd) {
        Integer statusCode = parseStatus(status);
        DateRange range = parseDateRange(dateStart, dateEnd);
        return ApiResponse.ok(withdrawalService.getWithdrawals(
                statusCode,
                uid,
                range != null ? range.startTime() : null,
                range != null ? range.endTime() : null,
                page,
                size
        ));
    }

    @PostMapping("/audit")
    @Operation(summary = "提现审核")
    public ApiResponse<AdminWithdrawalAuditResultVo> auditWithdrawal(
            @Parameter(description = "审核请求体", required = true)
            @Valid @RequestBody WithdrawalAuditDto dto,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        Long adminId = userDetails != null ? userDetails.getUser().getId() : null;
        String auditor = userDetails != null ? userDetails.getUsername() : "admin";
        String action = dto.getAction() != null ? dto.getAction().trim().toUpperCase() : "";
        if ("APPROVE".equals(action)) {
            withdrawalService.approveWithdrawal(dto.getWithdrawId(), dto.getRemark(), adminId);
        } else if ("REJECT".equals(action)) {
            withdrawalService.rejectWithdrawal(dto.getWithdrawId(), dto.getRemark(), adminId);
        } else {
            throw new BizException("action 仅支持 APPROVE 或 REJECT");
        }
        Withdrawal updated = withdrawalService.getWithdrawal(dto.getWithdrawId());
        AdminWithdrawalAuditResultVo vo = new AdminWithdrawalAuditResultVo();
        vo.setWithdrawId(dto.getWithdrawId());
        vo.setStatus(toStatusText(updated != null ? updated.getStatus() : null));
        vo.setAuditTime(updated != null ? updated.getReviewTime() : LocalDateTime.now());
        vo.setAuditor(auditor);
        return ApiResponse.ok(vo);
    }

    private Integer parseStatus(String status) {
        if (!StringUtils.hasText(status)) {
            return null;
        }
        String value = status.trim().toUpperCase();
        if ("PENDING".equals(value)) {
            return 0;
        }
        if ("APPROVED".equals(value)) {
            return 1;
        }
        if ("REJECTED".equals(value)) {
            return 2;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String toStatusText(Integer status) {
        if (status == null) {
            return "UNKNOWN";
        }
        return switch (status) {
            case 0 -> "PENDING";
            case 1 -> "APPROVED";
            case 2 -> "REJECTED";
            default -> "UNKNOWN";
        };
    }

    private DateRange parseDateRange(String dateStart, String dateEnd) {
        if (!StringUtils.hasText(dateStart) && !StringUtils.hasText(dateEnd)) {
            return null;
        }
        LocalDate start = parseDate(dateStart, "dateStart");
        LocalDate end = parseDate(dateEnd, "dateEnd");
        if (start != null && end != null && end.isBefore(start)) {
            throw new BizException("dateEnd 不能早于 dateStart");
        }
        LocalDateTime startTime = start != null ? start.atStartOfDay() : null;
        LocalDateTime endTime = end != null ? end.plusDays(1).atStartOfDay() : null;
        String startValue = startTime != null ? startTime.format(DATE_TIME) : null;
        String endValue = endTime != null ? endTime.format(DATE_TIME) : null;
        return new DateRange(startValue, endValue);
    }

    private LocalDate parseDate(String value, String field) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim(), DATE);
        } catch (DateTimeParseException ex) {
            throw new BizException(field + " 格式错误，应为 yyyy-MM-dd");
        }
    }

    private record DateRange(String startTime, String endTime) {
    }
}
