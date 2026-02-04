package com.slb.mining_backend.modules.device.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 设备分钟级算力趋势点（仅展示）。
 */
@Data
@Schema(description = "设备分钟级算力趋势点（仅用于展示）/ Device minutely hashrate point (display-only)")
public class DeviceHashratePointVo {

    @Schema(description = "分钟桶时间（精确到分钟）", example = "2025-12-15T11:40:00")
    private LocalDateTime bucketTime;

    @Schema(description = "CPU 算力（H/s）", example = "5000000.00")
    private BigDecimal cpuHashrate;

    @Schema(description = "GPU 算力（MH/s）", example = "20.00")
    private BigDecimal gpuHashrate;

    @Schema(description = "总算力（MH/s）", example = "25.00")
    private BigDecimal totalHashrate;

    @Schema(description = "挖矿算法（可选，例如 octopus/kawpow）", example = "octopus")
    private String algorithm;

    @Schema(description = "CPU 按当前算力估算的每日收益（折合 CNY）", example = "3.21")
    private BigDecimal cpuDailyIncomeCny;

    @Schema(description = "GPU 按当前算力估算的每日收益（折合 CNY）", example = "9.13")
    private BigDecimal gpuDailyIncomeCny;
}


