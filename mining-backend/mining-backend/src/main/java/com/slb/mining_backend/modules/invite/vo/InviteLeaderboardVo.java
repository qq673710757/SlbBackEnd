package com.slb.mining_backend.modules.invite.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 邀请收益排行榜（只统计邀请人获得的佣金：commission_records.user_id 的 commission_amount 汇总）
 */
@Data
@Schema(description = "邀请收益排行榜（仅邀请收益，不含被邀请收益）/ Invite commission leaderboard (inviter commission only)")
public class InviteLeaderboardVo {

    @Schema(description = "榜单统计范围：all/today/yesterday/month", example = "all")
    private String range;

    @Schema(description = "返回条数上限（TopN）", example = "20")
    private Integer limit;

    @Schema(description = "排行榜列表（按邀请佣金降序）")
    private List<Item> list;

    @Data
    @Schema(description = "排行榜单项 / Leaderboard item")
    public static class Item {

        @Schema(description = "名次（从 1 开始）", example = "1")
        private Integer rank;

        @Schema(description = "邀请人用户ID", example = "10001")
        private Long userId;

        @Schema(description = "邀请人用户名", example = "inviter001")
        private String userName;

        @Schema(description = "邀请佣金总额（CAL）", example = "123.45")
        private BigDecimal totalCommission;

        @Schema(description = "产生过佣金的被邀请用户数（去重）", example = "12")
        private Long inviteeCount;
    }
}


