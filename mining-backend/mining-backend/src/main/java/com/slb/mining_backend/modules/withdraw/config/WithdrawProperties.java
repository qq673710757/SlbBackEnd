package com.slb.mining_backend.modules.withdraw.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
@ConfigurationProperties(prefix = "app.withdraw")
@Data
public class WithdrawProperties {
    private BigDecimal minAmount;
    private int dailyLimitCount;
    private BigDecimal feeRate;
}
