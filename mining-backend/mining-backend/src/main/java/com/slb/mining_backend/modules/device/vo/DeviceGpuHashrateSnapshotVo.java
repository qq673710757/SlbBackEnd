package com.slb.mining_backend.modules.device.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 用户所有设备的 GPU 顺时算力快照（每块 GPU 最近一条分钟桶记录）。
 */
@Data
@Schema(description = "GPU 顺时算力快照（按设备 + GPU）")
public class DeviceGpuHashrateSnapshotVo {

    @Schema(description = "设备唯一标识", example = "device-123456")
    private String deviceId;

    @Schema(description = "设备名称", example = "My Mining Rig #1")
    private String deviceName;

    @Schema(description = "GPU 索引（从 0 开始）", example = "0")
    private Integer gpuIndex;

    @Schema(description = "GPU 名称（可选）", example = "RTX3080")
    private String gpuName;

    @Schema(description = "GPU 算力（MH/s）", example = "45.30")
    private BigDecimal hashrate;

    @Schema(description = "挖矿算法（可选，例如 octopus/kawpow）", example = "octopus")
    private String algorithm;

    @Schema(description = "分钟桶时间（精确到分钟）", example = "2026-01-19T12:34:00")
    private LocalDateTime bucketTime;

    @Schema(description = "GPU 按当前算力估算的每日收益（折合 CNY）", example = "12.34")
    private BigDecimal gpuDailyIncomeCny;
}
