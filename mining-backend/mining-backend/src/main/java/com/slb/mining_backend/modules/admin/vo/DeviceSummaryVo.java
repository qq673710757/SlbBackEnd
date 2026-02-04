package com.slb.mining_backend.modules.admin.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class DeviceSummaryVo {
    private Long deviceTotal;
    private Long deviceOnline;
    private BigDecimal cpuKh;
    private BigDecimal cfxMh;
    private BigDecimal rvnMh;
    private LocalDateTime asOf;
}
