package com.slb.mining_backend.modules.device.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 用户当前算力与收益估算视图
 */
@Data
@Schema(description = "算力汇总信息")
public class HashrateSummaryVo {

    @Schema(description = "当前在线 CPU 算力总和，单位 H/s")
    private BigDecimal cpuHashrate;

    @Schema(description = "当前在线 GPU 算力总和，单位 MH/s")
    private BigDecimal gpuHashrate;

    @Schema(description = "总算力（CPU+GPU），单位 MH/s（CPU 已换算为 MH/s）")
    private BigDecimal totalHashrate;

    @Schema(description = "按当前算力估算的每小时收益（折合 CNY）")
    private BigDecimal hourlyIncomeCny;

    @Schema(description = "按当前算力估算的每日收益（折合 CNY）")
    private BigDecimal dailyIncomeCny;

    @Schema(description = "CPU 按当前算力估算的每日收益（折合 CNY）")
    private BigDecimal cpuDailyIncomeCny;

    @Schema(description = "GPU 按当前算力估算的每日收益（折合 CNY）")
    private BigDecimal gpuDailyIncomeCny;

    @Schema(description = "GPU-CFX 按当前算力估算的每日收益（折合 CNY）")
    private BigDecimal gpuDailyIncomeCnyCfx;

    @Schema(description = "GPU-RVN 按当前算力估算的每日收益（折合 CNY）")
    private BigDecimal gpuDailyIncomeCnyRvn;

    @Schema(description = "GPU-其他算法按当前算力估算的每日收益（折合 CNY）")
    private BigDecimal gpuDailyIncomeCnyOther;

    @Schema(description = "GPU 算法维度的日收益估算明细")
    private List<GpuAlgorithmDailyIncomeVo> gpuAlgorithmDailyIncomes;

    @Schema(description = "按当前算力估算的每月收益（折合 CNY）")
    private BigDecimal monthlyIncomeCny;
}

