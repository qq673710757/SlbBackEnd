package com.slb.mining_backend.modules.earnings.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 矿池统计快照（用于验收/排查预估口径）
 */
@Data
@Schema(description = "矿池统计快照（用于验收/排查）")
public class PoolStatsVo {

    @Schema(description = "当前用于预估的矿池总算力（MH/s）。优先外部矿池；失败则退化为平台在线设备算力之和。")
    private BigDecimal poolTotalHashrateHps;

    @Schema(description = "已缓存的外部矿池总算力（MH/s）。若外部未成功刷新则为 0。")
    private BigDecimal externalPoolHashrateHps;

    @Schema(description = "矿池 activePortProfit（若可用），建议按 XMR/(MH/s·day) 理解；缺失则为 0。")
    private BigDecimal activePortProfitXmrPerHashDay;

    @Schema(description = "CFX 估算口径：每 MH/s 的日产币（CFX/MH/day）。若未刷新则为 0。")
    private BigDecimal cfxDailyCoinPerMh;

    @Schema(description = "RVN 估算口径：每 MH/s 的日产币（RVN/MH/day）。若未刷新则为 0。")
    private BigDecimal rvnDailyCoinPerMh;

    @Schema(description = "CAL/XMR 固定换算比例 ratio：1 CAL = ratio * XMR")
    private BigDecimal calXmrRatio;

    @Schema(description = "XMR 对 CNY 汇率（CNY/XMR）")
    private BigDecimal xmrToCnyRate;

    @Schema(description = "CAL 对 CNY 汇率（CNY/CAL）")
    private BigDecimal calToCnyRate;

    @Schema(description = "当前 poolTotalHashrate 的来源：external 或 platform_fallback")
    private String poolTotalHashrateSource;
}


