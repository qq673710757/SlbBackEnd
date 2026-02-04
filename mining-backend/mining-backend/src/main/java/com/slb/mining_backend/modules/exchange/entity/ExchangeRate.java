package com.slb.mining_backend.modules.exchange.entity;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 对应表：exchange_rates
 *
 * 用于记录数字货币汇率快照，例如 XMR/CNY, XMR/USDT, USDT/CNY 等。
 */
@Data
public class ExchangeRate implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private String symbol;
    private BigDecimal px;
    private String source;
    private LocalDateTime createdTime;
}