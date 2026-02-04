package com.slb.mining_backend.modules.earnings.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 按小时汇总的收益明细（用于列表展示，一小时一条）。
 *
 * 说明：
 * - 数据来自 earnings_history 明细表的聚合，不改变审计明细的写入方式。
 * - earningTime 表示小时桶起始时间（HH:00:00）。
 */
@Data
@Schema(description = "按小时汇总的收益记录（1小时=1条，仅用于展示）/ Hourly aggregated earnings record (display-only)")
public class EarningsHistoryHourlyItemVo {

    @Schema(description = "小时桶起始时间（HH:00:00）", example = "2025-12-12T22:00:00")
    private LocalDateTime earningTime;

    @Schema(
            description = """
                    收益类型标识：
                    - 默认（groupBy=hour）：不传 earningType 时返回 ALL（表示该小时桶聚合了所有类型）；传入 earningType 时回显该类型
                    - groupBy=earningType：返回真实的 earningType（CPU/GPU/INVITE/INVITED/COMPENSATION/INCENTIVE/SYSTEM_INCENTIVE）
                    """,
            example = "INVITE"
    )
    private String earningType;

    @Schema(
            description = """
                    收益类型分组（用于消除前端对 INVITE_CPU/INVITE_GPU 的误解）：
                    - groupBy=earningType 时：INVITE_CPU / INVITE_GPU / INVITE 统一返回 earningTypeGroup=INVITE
                    - 其他类型保持不变
                    - groupBy=hour 且 earningType=ALL 时返回 ALL
                    """,
            example = "INVITE"
    )
    private String earningTypeGroup;

    @Schema(
            description = """
                    当 earningTypeGroup=INVITE 时的来源细分（按小时桶聚合口径）：
                    - CPU：该聚合桶内仅出现 INVITE_CPU（或 INVITE_CPU + INVITE）
                    - GPU：该聚合桶内仅出现 INVITE_GPU（或 INVITE_GPU + INVITE）
                    - MIXED：该聚合桶内同时出现 INVITE_CPU 与 INVITE_GPU
                    - UNKNOWN：仅出现旧的 INVITE 或无法判断
                    - 其他类型为 null
                    """,
            example = "MIXED"
    )
    private String inviteSourceType;

    @Schema(description = "该小时内汇总的 CAL 金额", example = "0.01234567")
    private BigDecimal amountCal;

    @Schema(description = "该小时内汇总的 CNY 金额", example = "0.03")
    private BigDecimal amountCny;

    @Schema(description = "该小时内合并的明细条数", example = "4")
    private Long recordCount;

    @Schema(description = "结算币种（若该聚合桶内混合则为 MIXED）", example = "CAL")
    private String settleCurrency;
}


