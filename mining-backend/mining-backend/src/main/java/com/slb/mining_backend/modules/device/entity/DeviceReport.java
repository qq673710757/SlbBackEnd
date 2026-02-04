package com.slb.mining_backend.modules.device.entity;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 对应表：device_reports
 *
 * 用于记录客户端矿机上报的运行数据，包含算力、shares、运行时长和软件版本等。
 */
@Data
public class DeviceReport implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private Long userId;
    private String deviceId;
    private BigDecimal hashrate;
    private Long shares;
    private Long uptime;
    private String version;
    private String signature;
    private String status;
    private LocalDateTime reportTime;
    private String workerId;
}