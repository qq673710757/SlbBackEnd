package com.slb.mining_backend.modules.earnings.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 收益历史记录列表中的单项视图对象
 */
@Data
@Schema(description = "收益历史记录单项视图对象 / Earnings history item view object")
public class EarningsHistoryItemVo {

    @Schema(description = "收益记录 ID。为避免前端精度丢失，建议按字符串处理。/ Earnings record ID; JS clients should handle as string.", example = "10001")
    private Long id;

    @Schema(description = "产生该笔收益的设备 ID。/ Device ID that produced this earning.", example = "device-123456")
    private String deviceId;

    @Schema(description = "设备名称。/ Device display name.", example = "My Mining Rig #1")
    private String deviceName;

    @Schema(description = "本次收益的 CAL 数量。/ Amount earned in CAL.", example = "1.2345")
    private BigDecimal amountCal;

    @Schema(description = "本次收益折算的 CNY 金额。/ Amount earned converted to CNY.", example = "10.50")
    private BigDecimal amountCny;

    @Schema(description = "收益产生时间。/ Timestamp when the earning was generated.", example = "2025-11-18T10:15:00")
    private LocalDateTime earningTime;

    @Schema(description = "收益类型，例如 block_reward、commission 等。/ Earning type, e.g. block_reward or commission.", example = "block_reward")
    private String earningType;

    @Schema(
            description = """
                    收益类型分组（用于消除前端对 INVITE_CPU/INVITE_GPU 的误解）：
                    - INVITE_CPU / INVITE_GPU / INVITE 都归为 INVITE（表示“邀请返佣”这一大类）
                    - 其他类型保持不变（CPU/GPU/COMPENSATION/INCENTIVE/...）
                    """,
            example = "INVITE"
    )
    private String earningTypeGroup;

    @Schema(
            description = """
                    当 earningTypeGroup=INVITE 时的来源细分：
                    - CPU：来自被邀请者 CPU 收益贡献的返佣（earningType=INVITE_CPU）
                    - GPU：来自被邀请者 GPU 收益贡献的返佣（earningType=INVITE_GPU）
                    - UNKNOWN：历史旧数据或无法判断（earningType=INVITE）
                    - 其他类型为 null
                    """,
            example = "CPU"
    )
    private String inviteSourceType;

    @Schema(description = "本条收益实际结算入账币种（以当次结算分支为准）。/ Actual settlement currency for this record.", example = "CAL")
    private String settleCurrency;
}
