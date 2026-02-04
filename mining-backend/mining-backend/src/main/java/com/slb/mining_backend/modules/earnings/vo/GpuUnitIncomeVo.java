package com.slb.mining_backend.modules.earnings.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

/**
 * GPU 单位收益（人民币）：
 * - 每 1 MH/s · 天 的收益（CNY/day）
 */
@Data
@Schema(description = "GPU 单位收益（人民币结算）")
public class GpuUnitIncomeVo {

    @Schema(description = "GPU 单位收益：每 1 MH/s · 天 的收益（CNY）", example = "220.00")
    private BigDecimal gpuDailyIncomeCnyPer1Mh;

    @Schema(description = "本次计算使用的 bonusFactor（乘数）；1.0 表示无加成", example = "1.0")
    private BigDecimal bonusFactor;

    @Schema(description = "本次计算使用的矿池总算力（MH/s），外部优先，失败退化为平台在线设备和", example = "388.18")
    private BigDecimal poolTotalHashrateHps;

    @Schema(description = "本次计算是否命中 activePortProfit（更贴近真实矿池收益）", example = "true")
    private Boolean usingActivePortProfit;

    @Schema(description = "activePortProfit（若命中），建议按 XMR/(MH/s·day) 理解；未命中则为 0")
    private BigDecimal activePortProfitXmrPerHashDay;

    @Schema(description = "CAL/XMR 固定换算比例 ratio：1 CAL = ratio * XMR")
    private BigDecimal calXmrRatio;

    @Schema(description = "CAL/CNY 汇率（CNY/CAL）")
    private BigDecimal calToCnyRate;

    @Schema(description = "XMR/CNY 价格（CNY/XMR）")
    private BigDecimal xmrToCnyRate;
}
