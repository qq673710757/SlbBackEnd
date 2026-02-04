package com.slb.mining_backend.modules.device.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.Map;

/**
 * 设备注册请求 DTO
 */
@Data
@Schema(description = "设备注册请求体 / Device registration request body")
public class DeviceRegisterReqDto {

    // 设备名
    @Schema(description = "设备名称（选填），用于在前端展示。/ Device display name, optional.", example = "My Mining Rig #1")
    private String deviceName;

    @NotBlank(message = "设备唯一标识不能为空")
    @Schema(description = "设备硬件唯一指纹 (UUID v5)，用于幂等注册。/ Unique hardware fingerprint (UUID v5), used for idempotent registration.", example = "550e8400-e29b-41d4-a716-446655440000")
    private String uniqueId;

    @NotBlank(message = "设备类型不能为空")
    @Schema(description = "设备类型，必填，例如 PC/Server/Mobile。/ Device type, required.", example = "PC")
    private String deviceType;

    @NotEmpty(message = "设备信息不能为空")
    @Schema(description = "设备静态硬件信息 (OS, CPU, RAM等)。/ Static hardware info map.", example = "{\"os\":\"Windows 11\",\"cpu\":\"i7-10700K\"}")
    private Map<String, String> deviceInfo;
}
