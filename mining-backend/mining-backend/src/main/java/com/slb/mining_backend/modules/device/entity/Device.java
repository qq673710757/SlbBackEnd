package com.slb.mining_backend.modules.device.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 设备实体类，对应数据库中的 'devices' 表
 */
@Data
public class Device {
    private String id;
    private Long userId;
    private String deviceName;
    private String deviceType;
    private String deviceInfo; // 存储为 JSON 字符串
    private String deviceSecret;
    private Integer status;
    private Double cpuHashrate;
    private Double gpuHashrate;
    private Double gpuHashrateOctopus;
    private Double gpuHashrateKawpow;
    private BigDecimal gpuDailyIncomeCny;
    private BigDecimal gpuDailyIncomeCnyOctopus;
    private BigDecimal gpuDailyIncomeCnyKawpow;
    private LocalDateTime lastOnlineTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private Boolean isDeleted;
}
