package com.slb.mining_backend.modules.device.vo;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class GpuAlgorithmHashrateVo {
    private String algorithm;
    private BigDecimal totalHashrate;
}
