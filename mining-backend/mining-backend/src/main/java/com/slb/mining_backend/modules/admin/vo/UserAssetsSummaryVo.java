package com.slb.mining_backend.modules.admin.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class UserAssetsSummaryVo {
    private BigDecimal calTotal;
    private BigDecimal cnyTotal;
    private LocalDateTime asOf;
}
