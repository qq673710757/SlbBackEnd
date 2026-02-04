package com.slb.mining_backend.modules.device.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Schema(description = "GPU 算法维度日收益估算")
public class GpuAlgorithmDailyIncomeVo {

    @Schema(description = "挖矿算法", example = "kawpow")
    private String algorithm;

    @Schema(description = "算法对应币种", example = "RVN")
    private String coinSymbol;

    @Schema(description = "该算法的 GPU 算力（MH/s）", example = "120.5")
    private BigDecimal hashrateMh;

    @Schema(description = "该算法对应的日收益（币种数量）", example = "123.456789")
    private BigDecimal dailyCoin;

    @Schema(description = "币种到 XMR 汇率（XMR per coin）", example = "0.00000042")
    private BigDecimal coinToXmrRate;

    @Schema(description = "折合 CNY 的日收益", example = "12.34")
    private BigDecimal dailyIncomeCny;
}
