package com.slb.mining_backend.modules.device.entity;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 对应表：device_hashrate_reports
 *
 * 仅用于用户展示参考算力：设备每分钟上报一条，服务端按分钟桶做幂等 upsert。
 */
@Data
public class DeviceHashrateReport implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private Long userId;
    private String deviceId;
    private LocalDateTime bucketTime;
    private BigDecimal cpuHashrate;
    private BigDecimal gpuHashrate;
    private Long shares;
    private Long uptime;
    private String version;
    private String algorithm;
    private LocalDateTime createdTime;
}


