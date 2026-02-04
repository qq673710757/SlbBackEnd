package com.slb.mining_backend.modules.xmr.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Schema(description = "C3Pool 实时算力信息")
public class WorkerHashrateVo {

    @Schema(description = "用户ID")
    private Long userId;

    @Schema(description = "唯一 worker 标识")
    private String workerId;

    @Schema(description = "实时总算力，单位 H/s")
    private BigDecimal hashrateHps;

    @Schema(description = "XMR/CNY 实时汇率")
    private BigDecimal xmrToCnyRate;

    @Schema(description = "算力抓取时间（UTC）")
    private LocalDateTime fetchedAt;

    @Schema(description = "数据来源（矿池）")
    private String source;
}

