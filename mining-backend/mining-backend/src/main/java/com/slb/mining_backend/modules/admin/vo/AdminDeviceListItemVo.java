package com.slb.mining_backend.modules.admin.vo;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class AdminDeviceListItemVo {
    private String deviceId;
    private String deviceName;
    private Long ownerUid;
    private String ownerName;
    private BigDecimal cpuKh;
    private BigDecimal cfxMh;
    private BigDecimal rvnMh;
    private String status;
    private LocalDateTime createdAt;

    @JsonIgnore
    private BigDecimal cpuHashrate;
    @JsonIgnore
    private BigDecimal cfxHashrate;
    @JsonIgnore
    private BigDecimal rvnHashrate;
    @JsonIgnore
    private Integer statusCode;
}
