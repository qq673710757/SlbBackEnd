package com.slb.mining_backend.modules.device.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 单块 GPU 的算力趋势（分钟级）。
 */
@Data
@Schema(description = "单块 GPU 算力趋势（分钟级，仅用于展示）")
public class DeviceGpuHashrateSeriesVo {

    @Schema(description = "GPU 索引（从 0 开始）", example = "0")
    private Integer index;

    @Schema(description = "GPU 名称（可选）", example = "RTX3080")
    private String name;

    @Schema(description = "分钟级算力点列表")
    private List<DeviceGpuHashratePointVo> points;
}
