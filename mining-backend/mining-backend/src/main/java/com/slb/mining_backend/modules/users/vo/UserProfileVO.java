package com.slb.mining_backend.modules.users.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
public class UserProfileVO {

    private long uid;
    private String userName;
    private String alipayAccount;
    private String alipayName;
    private String reInto;
    private LocalDateTime createTime;
    private String inviteCode;

    private BigDecimal calBalance;
    private BigDecimal cashBalance;
    private BigDecimal totalEarnings;
    private BigDecimal totalWithdrawn;
    private String settlementCurrency;

    private Integer deviceCount;
    private Integer onlineDeviceCount;

    private String workerId;
    private BigDecimal xmrBalance;
    private BigDecimal frozenXmr;
    private BigDecimal totalEarnedXmr;
    private BigDecimal xmrBalanceCny;
    private BigDecimal totalEarnedCny;
    private BigDecimal unpaidXmr;
    private BigDecimal paidXmrTotal;
    private Long poolHashrate;
    private Long poolShares;
    private LocalDateTime poolLastUpdateTime;
    private Integer statusFrozen;
}
