package com.slb.mining_backend.modules.device.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 设备 GPU 明细算力上报请求（分钟级）。
 */
@Data
@Schema(description = "设备 GPU 明细算力上报请求体（分钟级，仅用于展示）")
public class DeviceGpuHashrateReportReqDto {

    @Schema(description = "分钟桶时间（可选，不传则用服务端当前时间）", example = "2026-01-19T12:34:00")
    private LocalDateTime bucketTime;

    @NotEmpty(message = "GPU 列表不能为空")
    @Valid
    @Schema(description = "GPU 明细列表")
    private List<DeviceGpuHashrateItemDto> gpus;
}
