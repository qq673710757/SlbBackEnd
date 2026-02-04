package com.slb.mining_backend.modules.xmr.entity;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class AntpoolAccountBalance implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;
    private String account;
    private String coin;
    private BigDecimal earn24Hours;
    private BigDecimal earnTotal;
    private BigDecimal paidOut;
    private BigDecimal balance;
    private String settleTime;
    private LocalDateTime fetchedAt;
    private LocalDateTime createdTime;
}
