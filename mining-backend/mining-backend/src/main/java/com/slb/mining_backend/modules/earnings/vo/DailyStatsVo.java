package com.slb.mining_backend.modules.earnings.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 每日收益统计列表中的单项视图对象
 */
@Data
@Schema(description = "每日收益统计单项视图对象 / Daily earnings statistics item view object")
public class DailyStatsVo {

    @Schema(description = "统计日期。/ Date of the statistics.", example = "2025-11-18")
    private LocalDate date;

    @Schema(description = "设备 ID（仅 dailyByDevice 返回）。/ Device ID (dailyByDevice only).", example = "device-123456")
    private String deviceId;

    @Schema(description = "设备名称（仅 dailyByDevice 返回）。/ Device name (dailyByDevice only).", example = "My Mining Rig #1")
    private String deviceName;

    @Schema(description = "当日收益（CAL）。/ Earnings in CAL for this day.", example = "12.345")
    private BigDecimal calAmount;

    @Schema(description = "当日收益折算的 CNY 金额。/ Earnings converted to CNY for this day.", example = "56.78")
    private BigDecimal cnyAmount;

    @Schema(description = "当日来源于 CPU 的收益（CAL）。/ Earnings contributed by CPU in CAL.", example = "7.89")
    private BigDecimal cpuEarnings;

    @Schema(description = "当日来源于 GPU 的收益（CAL）。/ Earnings contributed by GPU in CAL.", example = "4.45")
    private BigDecimal gpuEarnings;
}
