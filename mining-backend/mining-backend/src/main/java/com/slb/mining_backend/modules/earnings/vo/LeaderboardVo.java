package com.slb.mining_backend.modules.earnings.vo;

import com.slb.mining_backend.common.vo.PageVo;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 收益排行榜视图对象
 */
@Data
@Schema(description = "收益排行榜视图对象 / Earnings leaderboard view object")
public class LeaderboardVo {

    @Schema(description = "排行类型：week=周排行，month=月排行。/ Leaderboard type: week|month.", example = "month")
    private String type;

    @Schema(description = "本次统计周期开始时间（北京时间）。/ Ranking period start time (Asia/Shanghai).")
    private LocalDateTime startTime;

    @Schema(description = "本次统计周期结束时间（北京时间）。说明：为保证缓存口径一致，endTime 会按 30 分钟窗口对齐（例如 10:00/10:30）。/ Ranking period end time aligned to 30-min window for cache consistency.")
    private LocalDateTime endTime;

    @Schema(description = "收益排行榜分页列表。/ Paged earnings leaderboard.", implementation = PageVo.class)
    private PageVo<RankItem> leaderboard;

    @Schema(description = "当前用户在排行榜中的信息。/ Current user's rank entry. 注意：当用户在该统计周期内无收益时，myRank 可能为 null。/ NOTE: myRank can be null if user has no earnings in the period.", implementation = RankItem.class)
    private RankItem myRank;

    @Data
    @Schema(description = "排行榜单项记录 / Single leaderboard entry")
    public static class RankItem {

        @Schema(description = "名次，从 1 开始。/ Rank index starting from 1.", example = "1")
        private Long rank;

        @Schema(description = "用户昵称或展示名。/ User display name.", example = "hyperion")
        private String userName;

        @Schema(description = "统计周期内的总收益（CAL）。/ Total earnings in CAL during ranking period.", example = "123.45")
        private BigDecimal calAmount;

        @Schema(description = "统计周期内的总收益（CNY）。/ Total earnings in CNY during ranking period.", example = "100.50")
        private BigDecimal cnyAmount;

        @Schema(description = "当前绑定的设备数量。/ Number of devices bound to the user.", example = "5")
        private Integer deviceCount;
    }
}
