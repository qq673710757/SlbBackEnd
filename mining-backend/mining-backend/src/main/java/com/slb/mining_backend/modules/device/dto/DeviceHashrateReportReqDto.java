package com.slb.mining_backend.modules.device.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 设备算力上报请求（分钟级）。
 *
 * 说明：
 * - 该接口仅用于给用户展示参考算力，不用于结算。
 * - 设备建议每分钟上报一次；服务端会将上报时间落入“分钟桶”并做幂等 upsert。
 */
@Data
@Schema(description = "设备算力上报请求体（分钟级，仅用于展示）/ Device hashrate report request (minutely, display-only)")
public class DeviceHashrateReportReqDto {

    @NotNull(message = "CPU算力不能为空")
    @Schema(description = "CPU 算力（H/s）", example = "5000000")
    private Double cpuHashrate;

    @NotNull(message = "GPU算力不能为空")
    @Schema(description = "GPU 算力（MH/s）", example = "20")
    private Double gpuHashrate;

    @Schema(description = "累计 Shares（可选）", example = "1024")
    private Long shares;

    @Schema(description = "运行时间秒数（可选）", example = "3600")
    private Long uptime;

    @Schema(description = "客户端版本（可选）", example = "1.2.3")
    private String version;

    @Schema(description = "挖矿算法（可选，例如 octopus/kawpow）", example = "octopus")
    private String algorithm;
}


