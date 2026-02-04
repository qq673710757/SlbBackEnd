package com.slb.mining_backend.modules.device.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 单块 GPU 算力上报条目。
 */
@Data
@Schema(description = "单块 GPU 算力上报条目")
public class DeviceGpuHashrateItemDto {

    @NotNull(message = "GPU index 不能为空")
    @Min(value = 0, message = "GPU index 不能为负数")
    @Schema(description = "GPU 索引（从 0 开始）", example = "0")
    private Integer index;

    @Schema(description = "GPU 名称（可选）", example = "RTX3080")
    private String name;

    @NotNull(message = "GPU 算力不能为空")
    @Schema(description = "GPU 算力（MH/s）", example = "45.3")
    private Double hashrate;

    @Schema(description = "挖矿算法（可选，例如 octopus/kawpow）", example = "octopus")
    private String algorithm;
}
