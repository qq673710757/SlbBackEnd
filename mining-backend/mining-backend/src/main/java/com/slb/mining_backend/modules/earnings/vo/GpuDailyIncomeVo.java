package com.slb.mining_backend.modules.earnings.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

/**
 * GPU 日收益估算（CFX / XMR）。
 */
@Data
@Schema(description = "GPU 日收益估算（CFX / XMR）")
public class GpuDailyIncomeVo {

    @Schema(description = "输入 GPU 算力（MH/s）", example = "100.0")
    private BigDecimal hashrateMh;

    @Schema(description = "输入 GPU 算力（H/s，派生展示）", example = "100000000.0")
    private BigDecimal hashrateHps;

    @Schema(description = "估算日收益（XMR）", example = "0.01234567")
    private BigDecimal dailyXmr;

    @Schema(description = "估算日收益（CFX）", example = "123.456789")
    private BigDecimal dailyCfx;

    @Schema(description = "CFX/XMR 汇率（XMR per CFX）", example = "0.0001")
    private BigDecimal cfxToXmrRate;

    @Schema(description = "是否命中 activePortProfit（更贴近真实矿池收益）", example = "true")
    private Boolean usingActivePortProfit;

    @Schema(description = "activePortProfit（若命中），建议按 XMR/(MH/s·day) 理解；未命中则为 0")
    private BigDecimal activePortProfitXmrPerHashDay;

    @Schema(description = "本次计算使用的矿池总算力（MH/s），外部优先，失败退化为平台在线设备和", example = "388.18")
    private BigDecimal poolTotalHashrateHps;
}
