package com.slb.mining_backend.modules.device.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 用于前端展示的设备视图对象
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL) // 仅包含非空字段
public class DeviceVo {
    private String deviceId;
    private String deviceName;
    private String deviceType;
    private Integer status;
    private Double cpuHashrate;
    private Double gpuHashrate;
    private Double gpuHashrateOctopus;
    private Double gpuHashrateKawpow;
    private java.math.BigDecimal cpuDailyIncomeCny;
    private java.math.BigDecimal gpuDailyIncomeCny;
    private java.math.BigDecimal gpuDailyIncomeCnyOctopus;
    private java.math.BigDecimal gpuDailyIncomeCnyKawpow;
    private LocalDateTime lastOnlineTime;
    private LocalDateTime createTime;
    private Map<String, String> deviceInfo; // 详情接口会包含此字段
    private String deviceSecret;
}
