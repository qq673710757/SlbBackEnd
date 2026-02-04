package com.slb.mining_backend.modules.invite.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 邀请佣金统计视图对象
 */
@Data
@Schema(description = "邀请佣金统计视图对象 / Invite commission statistics view object")
public class InviteStatsVo {

    // 总计邀请人数
    @Schema(description = "累计邀请用户数。/ Total number of invited users.", example = "20")
    private Long totalInvites;

    // 活跃的邀请人数
    @Schema(description = "当前活跃的邀请用户数（例如有在线设备或有算力）。/ Number of active invitees.", example = "8")
    private Long activeInvites;

    // 总计收益
    @Schema(description = "通过邀请获得的累计佣金总额。/ Total commission earned via invites.", example = "256.78")
    private BigDecimal totalCommission;

    // 今日收益
    @Schema(description = "今日邀请佣金。/ Commission earned today.", example = "3.21")
    private BigDecimal todayCommission;

    // 昨日收益
    @Schema(description = "昨日邀请佣金。/ Commission earned yesterday.", example = "2.34")
    private BigDecimal yesterdayCommission;

    // 本月收益
    @Schema(description = "本月截至当前的邀请佣金。/ Invite commission earned in current month.", example = "45.67")
    private BigDecimal thisMonthCommission;

    // 收益率
    @Schema(description = "佣金收益率，例如 0.1 代表 10%。/ Commission rate, e.g. 0.1 means 10%.", example = "0.1")
    private BigDecimal commissionRate;
}
