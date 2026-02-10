package com.slb.mining_backend.modules.admin.controller;

import com.slb.mining_backend.common.api.ApiResponse;
import com.slb.mining_backend.common.exception.BizException;
import com.slb.mining_backend.common.vo.PageVo;
import com.slb.mining_backend.modules.admin.service.AdminEarningsGrantService;
import com.slb.mining_backend.modules.admin.vo.EarningsGrantDetailVo;
import com.slb.mining_backend.modules.admin.vo.EarningsGrantVo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

@RestController
@RequestMapping("/api/v1/admin/earnings")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "管理员/收益发放", description = "收益发放记录与抽成汇总")
public class AdminEarningsController {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    private final AdminEarningsGrantService grantService;

    public AdminEarningsController(AdminEarningsGrantService grantService) {
        this.grantService = grantService;
    }

    @GetMapping("/grants")
    @Operation(summary = "收益发放记录（发放量 + 实际抽成）")
    public ApiResponse<PageVo<EarningsGrantVo>> listGrants(
            @Parameter(description = "页码，从 1 开始", example = "1")
            @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "每页数量", example = "10")
            @RequestParam(defaultValue = "10") int size,
            @Parameter(description = "开始日期（yyyy-MM-dd）", example = "2026-02-01")
            @RequestParam(required = false) String dateStart,
            @Parameter(description = "结束日期（yyyy-MM-dd）", example = "2026-02-03")
            @RequestParam(required = false) String dateEnd) {
        LocalDate start = parseDate(dateStart, "dateStart");
        LocalDate end = parseDate(dateEnd, "dateEnd");
        return ApiResponse.ok(grantService.listGrants(page, size, start, end));
    }

    @GetMapping("/grant-details")
    @Operation(summary = "收益发放明细（每一笔发放记录）")
    public ApiResponse<PageVo<EarningsGrantDetailVo>> listGrantDetails(
            @Parameter(description = "页码，从 1 开始", example = "1")
            @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "每页数量", example = "20")
            @RequestParam(defaultValue = "20") int size,
            @Parameter(description = "开始日期（yyyy-MM-dd）", example = "2026-02-01")
            @RequestParam(required = false) String dateStart,
            @Parameter(description = "结束日期（yyyy-MM-dd）", example = "2026-02-03")
            @RequestParam(required = false) String dateEnd,
            @Parameter(description = "币种筛选（XMR/CFX/RVN）", example = "XMR")
            @RequestParam(required = false) String coin,
            @Parameter(description = "矿池来源（C3POOL/F2POOL/ANTPOOL）", example = "C3POOL")
            @RequestParam(required = false) String poolSource) {
        LocalDate start = parseDate(dateStart, "dateStart");
        LocalDate end = parseDate(dateEnd, "dateEnd");
        return ApiResponse.ok(grantService.listGrantDetails(page, size, start, end, coin, poolSource));
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
}
