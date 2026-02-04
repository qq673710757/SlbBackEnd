package com.slb.mining_backend.modules.device.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 设备更新请求 DTO (目前仅用于修改名称)
 */
@Data
@Schema(description = "设备更新请求体 / Device update request body")
public class DeviceUpdateReqDto {

    @NotBlank(message = "设备名称不能为空")
    @Schema(description = "新的设备名称，必填。/ New device name, required.", example = "My Mining Rig #1")
    private String deviceName;
}