package com.slb.mining_backend.modules.invite.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "app.invite")
@Data
public class InviteProperties {

    private List<CommissionTier> commissionTiers;

    /**
     * 被邀请者折扣（从平台抽成中让利），用于双向激励且可控成本。
     */
    private InviteeDiscount inviteeDiscount = new InviteeDiscount();

    /**
     * 被邀请者激活阈值：单次结算金额（XMR 等值）>= 阈值则视为激活。
     * 激活后，邀请者才开始计佣。
     */
    private BigDecimal activationThresholdXmr = new BigDecimal("0.000001");

    /**
     * 邀请者每月佣金封顶（CAL 等值；当前实现按“结算中使用的 CAL 等值”计量）。
     */
    private BigDecimal inviterMonthlyCapCal = new BigDecimal("200");

    @Data
    public static class CommissionTier {
        private int min;
        private int max;
        private BigDecimal rate;
    }

    @Data
    public static class InviteeDiscount {
        /** 是否启用 */
        private boolean enabled = false;
        /** 平台费率乘数：0.9 表示平台抽成打 9 折（用户多得的部分来自平台抽成） */
        private BigDecimal platformFeeMultiplier = new BigDecimal("0.9");
        /** 折扣持续天数：从用户注册 create_time 起算 */
        private int durationDays = 30;
        /** 折扣累计上限（CAL 等值；当前实现按“结算中使用的 CAL 等值”计量） */
        private BigDecimal capCal = new BigDecimal("10");
    }
}
