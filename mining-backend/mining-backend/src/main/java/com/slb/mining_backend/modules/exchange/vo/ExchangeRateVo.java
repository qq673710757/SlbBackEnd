package com.slb.mining_backend.modules.exchange.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 汇率展示 VO
 * 用于返回各种币种对法币的汇率信息
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "汇率展示视图对象 / Exchange rate view object")
public class ExchangeRateVo {

    /**
     * XMR 对 CNY 汇率
     */
    @Schema(description = "XMR 对 CNY 汇率。/ Exchange rate of XMR to CNY.", example = "1200.50")
    private BigDecimal xmrToCny;

    /**
     * XMR 对 USDT 汇率
     */
    @Schema(description = "XMR 对 USDT 汇率。/ Exchange rate of XMR to USDT.", example = "150.25")
    private BigDecimal xmrToUsdt;

    /**
     * USDT 对 CNY 汇率
     */
    @Schema(description = "USDT 对 CNY 汇率。/ Exchange rate of USDT to CNY.", example = "8.00")
    private BigDecimal usdtToCny;

    /**
     * CAL 对 CNY 汇率
     */
    @Schema(description = "CAL 对 CNY 汇率。/ Exchange rate of CAL to CNY.", example = "0.85")
    private BigDecimal calToCny;

    /**
     * CFX 对 CNY 汇率
     */
    @Schema(description = "CFX 对 CNY 汇率。/ Exchange rate of CFX to CNY.", example = "1.23")
    private BigDecimal cfxToCny;

    /**
     * CFX 对 USDT 汇率
     */
    @Schema(description = "CFX 对 USDT 汇率。/ Exchange rate of CFX to USDT.", example = "0.17")
    private BigDecimal cfxToUsdt;

    /**
     * CFX 对 XMR 汇率
     */
    @Schema(description = "CFX 对 XMR 汇率（XMR per CFX）。/ Exchange rate of CFX to XMR.", example = "0.00012")
    private BigDecimal cfxToXmr;

    /**
     * 数据源
     */
    @Schema(description = "汇率数据源，例如 CoinGecko。/ Data source of the exchange rates, e.g. CoinGecko.", example = "CoinGecko")
    private String source;

    /**
     * 最后更新时间戳（毫秒）
     */
    @Schema(description = "最后一次更新汇率的时间戳（毫秒）。/ Timestamp in milliseconds when the rates were last updated.", example = "1731916800000")
    private Long lastUpdatedTime;
}
