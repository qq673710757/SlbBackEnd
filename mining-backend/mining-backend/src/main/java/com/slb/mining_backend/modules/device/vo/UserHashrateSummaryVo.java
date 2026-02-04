package com.slb.mining_backend.modules.device.vo;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class UserHashrateSummaryVo {
    private Long userId;
    private BigDecimal cpuHashrate;
    private BigDecimal gpuHashrate;
}
