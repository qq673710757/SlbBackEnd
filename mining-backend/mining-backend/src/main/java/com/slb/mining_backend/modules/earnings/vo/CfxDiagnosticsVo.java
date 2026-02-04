package com.slb.mining_backend.modules.earnings.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

/**
 * CFX 口径诊断快照（用于验收/排查收益口径）
 */
@Data
@Schema(description = "CFX 口径诊断快照（用于验收/排查）")
public class CfxDiagnosticsVo {

    @Schema(description = "CFX 网络算力（MH/s）")
    private BigDecimal networkHashrateMh;

    @Schema(description = "CFX 出块时间（秒）")
    private BigDecimal blockTimeSeconds;

    @Schema(description = "CFX 估算口径：每 MH/s 的日产币（CFX/MH/day）")
    private BigDecimal dailyCoinPerMh;

    @Schema(description = "CFX 对 CNY 汇率（CNY/CFX）")
    private BigDecimal cfxToCny;

    @Schema(description = "最近一次 CFX 口径刷新时间（毫秒时间戳）")
    private Long lastRefreshedAt;

    @Schema(description = "最近一次 CFX 口径刷新错误（为空表示成功）")
    private String lastError;
}
