package com.slb.mining_backend.modules.xmr.entity;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
public class F2PoolPayoutDaily implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;
    private String account;
    private String coin;
    private LocalDate payoutDate;
    private BigDecimal grossAmount;
    private String txId;
    private String source;
    private String status;
    private String payloadFingerprint;
    private LocalDateTime createdTime;
}
