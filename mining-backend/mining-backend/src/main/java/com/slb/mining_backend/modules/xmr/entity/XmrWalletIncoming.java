package com.slb.mining_backend.modules.xmr.entity;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 实体：主钱包入账记录
 *
 * 对应表 xmr_wallet_incoming，用于记录主钱包的转入流水，按照用户和子地址划分。
 */
@Data
public class XmrWalletIncoming implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private Long userId;
    private String subaddress;
    private String txHash;
    private BigDecimal amountXmr;
    private Long blockHeight;
    private LocalDateTime ts;
    private Boolean settled;
    private LocalDateTime settledTime;
}
