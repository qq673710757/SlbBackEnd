package com.slb.mining_backend.modules.device.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 单块 GPU 的分钟级算力点（仅展示）。
 */
@Data
@Schema(description = "单块 GPU 分钟级算力点（仅用于展示）")
public class DeviceGpuHashratePointVo {

    @Schema(description = "分钟桶时间（精确到分钟）", example = "2026-01-19T12:34:00")
    private LocalDateTime bucketTime;

    @Schema(description = "GPU 算力（MH/s）", example = "45.30")
    private BigDecimal hashrate;

    @Schema(description = "挖矿算法（可选，例如 octopus/kawpow）", example = "octopus")
    private String algorithm;

    @Schema(description = "GPU 按当前算力估算的每日收益（折合 CNY）", example = "12.34")
    private BigDecimal gpuDailyIncomeCny;
}
