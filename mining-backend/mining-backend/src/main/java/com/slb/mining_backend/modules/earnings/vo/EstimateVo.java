package com.slb.mining_backend.modules.earnings.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import java.math.BigDecimal;

/**
 * 收益预估视图对象
 */
@Data
@Schema(description = "收益预估视图对象 / Earnings estimation view object")
public class EstimateVo {

    @Schema(description = "按小时统计的预估收益。/ Estimated earnings per hour.", implementation = EstimateDetail.class)
    private EstimateDetail hourly;

    @Schema(description = "按天统计的预估收益。/ Estimated earnings per day.", implementation = EstimateDetail.class)
    private EstimateDetail daily;

    @Schema(description = "按月统计的预估收益。/ Estimated earnings per month.", implementation = EstimateDetail.class)
    private EstimateDetail monthly;

    @Schema(description = "CPU 对总收益的贡献百分比（0-1）。/ CPU contribution ratio, 0-1.", example = "0.4")
    private BigDecimal cpuContribution; // CPU贡献百分比

    @Schema(description = "GPU 对总收益的贡献百分比（0-1）。/ GPU contribution ratio, 0-1.", example = "0.6")
    private BigDecimal gpuContribution; // GPU贡献百分比

    @Schema(description = "当前 XMR 价格（例如 CNY）。/ Current XMR price in currency (e.g. CNY).", example = "1200.50")
    private BigDecimal currentXmrPrice;

    @Schema(description = "CAL 对 CNY 汇率。/ CAL to CNY exchange rate.", example = "0.85")
    private BigDecimal calToCnyRate;

    @Data
    @Schema(description = "预估收益细节 / Estimated earnings details")
    public static class EstimateDetail {

        @Schema(description = "预估的 CAL 数量。/ Estimated amount in CAL.", example = "12.34")
        private BigDecimal calAmount;

        @Schema(description = "预估的 CNY 金额。/ Estimated amount in CNY.", example = "100.56")
        private BigDecimal cnyAmount;
    }
}
