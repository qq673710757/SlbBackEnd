package com.slb.mining_backend.modules.device.entity;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 对应表：device_gpu_hashrate_reports
 *
 * 仅用于用户展示参考算力：设备每分钟上报每块 GPU 一条，服务端按分钟桶做幂等 upsert。
 */
@Data
public class DeviceGpuHashrateReport implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private Long userId;
    private String deviceId;
    private Integer gpuIndex;
    private String gpuName;
    private BigDecimal hashrateMhs;
    private String algorithm;
    private LocalDateTime bucketTime;
    private LocalDateTime createdTime;
}
