package com.slb.mining_backend.modules.invite.service;

import com.slb.mining_backend.common.vo.PageVo;
import com.slb.mining_backend.modules.invite.config.InviteProperties;
import com.slb.mining_backend.modules.invite.mapper.InviteMapper;
import com.slb.mining_backend.modules.invite.mapper.PlatformCommissionMapper;
import com.slb.mining_backend.modules.invite.vo.InviteCodeVo;
import com.slb.mining_backend.modules.invite.vo.InviteLeaderboardVo;
import com.slb.mining_backend.modules.invite.vo.InviteRecordsVo;
import com.slb.mining_backend.modules.invite.vo.InviteStatsVo;
import com.slb.mining_backend.modules.users.entity.User;
import com.slb.mining_backend.modules.users.service.EmailService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
@Slf4j
public class InviteService {

    private static final ZoneId BJT = ZoneId.of("Asia/Shanghai");

    private final InviteMapper inviteMapper;
    private final PlatformCommissionMapper platformCommissionMapper;
    private final EmailService emailService;
    private final InviteProperties inviteProperties;

    @Value("${app.invite.base-url}")
    private String inviteBaseUrl;

    @Value("${app.platform.alert.enabled}")
    private boolean alertEnabled;

    @Value("${app.platform.alert.to-email}")
    private String alertToEmail;

    @Autowired
    public InviteService(InviteMapper inviteMapper, PlatformCommissionMapper platformCommissionMapper, EmailService emailService, InviteProperties inviteProperties) {
        this.inviteMapper = inviteMapper;
        this.platformCommissionMapper = platformCommissionMapper;
        this.emailService = emailService;
        this.inviteProperties = inviteProperties;
    }

    /**
     * [新增] 根据用户ID，获取其当前适用的邀请佣金率
     * (原 CommissionService 的核心逻辑)
     */
    public BigDecimal getCommissionRateForUser(Long userId) {
        if (userId == null) {
            return BigDecimal.ZERO;
        }
        long inviteeCount = inviteMapper.countInviteesByUserId(userId);
        List<InviteProperties.CommissionTier> tiers = inviteProperties.getCommissionTiers();
        for (int i = tiers.size() - 1; i >= 0; i--) {
            InviteProperties.CommissionTier tier = tiers.get(i);
            if (inviteeCount >= tier.getMin()) {
                return tier.getRate();
            }
        }
        return BigDecimal.ZERO;
    }

    /**
     * [新增] [定时任务] 每小时执行一次，监控平台佣金池健康状况
     * (原 CommissionService 的核心逻辑)
     */
    @Scheduled(cron = "0 0 * * * ?")
    public void monitorCommissionPool() {
        if (!alertEnabled) {
            log.info("Commission pool monitoring is disabled.");
            return;
        }
        log.info("Starting hourly commission pool health check...");
        LocalDateTime now = LocalDateTime.now(BJT);
        LocalDateTime twentyFourHoursAgo = now.minusHours(24);
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        String endTime = now.format(formatter);
        String startTime = twentyFourHoursAgo.format(formatter);

        // 平台收入：按“结算币种”分开汇总，再合并为 CAL 等值口径（避免 CAL/CNY 混加误报）
        BigDecimal platformIncomeFromCalSettle = platformCommissionMapper.sumCommissionByDateRangeAndCurrency(startTime, endTime, "CAL");
        BigDecimal platformIncomeFromCnySettle = platformCommissionMapper.sumCommissionByDateRangeAndCurrency(startTime, endTime, "CNY");
        BigDecimal platformIncome = safe(platformIncomeFromCalSettle).add(safe(platformIncomeFromCnySettle));

        BigDecimal invitationPayout = inviteMapper.sumTotalInvitationCommissionByDateRange(startTime, endTime);

        log.info("Commission Pool Check (CAL eq): Platform Income (24h) = {}, Invitation Payout (24h) = {}", platformIncome, invitationPayout);

        if (invitationPayout.compareTo(platformIncome) > 0) {
            String subject = "【严重警报】平台佣金池出现赤字！";
            String text = String.format(
                    "警报时间: %s\n\n" +
                            "平台佣金池在过去24小时内出现赤字，请立即检查邀请佣金策略！\n\n" +
                            "过去24小时平台总收入(CAL等值): %s\n" +
                            "过去24小时邀请总支出: %s CAL\n" +
                            "赤字金额(CAL等值): %s\n\n" +
                            "请尽快登录后台调整佣金费率。",
                    now.format(formatter),
                    platformIncome,
                    invitationPayout,
                    invitationPayout.subtract(platformIncome)
            );
            try {
                emailService.sendEmail(alertToEmail, subject, text);
                log.error("CRITICAL: Commission pool deficit detected! Alert email sent to {}.", alertToEmail);
            } catch (Exception e) {
                log.error("CRITICAL: Commission pool deficit detected, but FAILED to send alert email to {}.", alertToEmail, e);
            }
        } else {
            log.info("Commission pool health check passed.");
        }
    }

    // --- 以下是您原有的方法，已进行适配性修改 ---

    /**
     * 获取邀请码和邀请链接 (保持不变)
     */
    public InviteCodeVo getInviteCode(User user) {
        InviteCodeVo vo = new InviteCodeVo();
        vo.setInviteCode(user.getInviteCode());
        vo.setInviteLink(String.format("%s?code=%s", inviteBaseUrl, user.getInviteCode()));
        return vo;
    }

    /**
     * 获取邀请记录 (已修改)
     */
    public InviteRecordsVo getInviteRecords(Long userId, int page, int size) {
        long totalInvites = inviteMapper.countInviteesByUserId(userId);

        // 修正：调用新的方法获取当前用户的动态佣金率
        BigDecimal currentUserCommissionRate = this.getCommissionRateForUser(userId);

        if (totalInvites == 0) {
            InviteRecordsVo.Summary summary = new InviteRecordsVo.Summary(0L, BigDecimal.ZERO, currentUserCommissionRate);
            PageVo<InviteRecordsVo.RecordItem> pageVo = new PageVo<>(0L, page, size, List.of());
            InviteRecordsVo vo = new InviteRecordsVo();
            vo.setRecords(pageVo);
            vo.setSummary(summary);
            return vo;
        }

        int offset = (page - 1) * size;
        List<InviteRecordsVo.RecordItem> records = inviteMapper.findInviteRecordsPaginated(userId, offset, size);
        PageVo<InviteRecordsVo.RecordItem> pageVo = new PageVo<>(totalInvites, page, size, records);

        BigDecimal totalCommission = inviteMapper.sumTotalCommissionByUserId(userId);
        InviteRecordsVo.Summary summary = new InviteRecordsVo.Summary(totalInvites, totalCommission, currentUserCommissionRate);

        InviteRecordsVo vo = new InviteRecordsVo();
        vo.setRecords(pageVo);
        vo.setSummary(summary);
        return vo;
    }

    /**
     * 获取邀请统计数据 (已修改)
     */
    public InviteStatsVo getInviteStats(Long userId) {
        InviteStatsVo vo = new InviteStatsVo();
        LocalDate today = LocalDate.now(BJT);
        LocalDate yesterday = today.minusDays(1);
        LocalDate firstDayOfMonth = today.withDayOfMonth(1);
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        LocalDateTime now = LocalDateTime.now(BJT);

        // 修正：调用新的方法获取当前用户的动态佣金率
        BigDecimal currentUserCommissionRate = this.getCommissionRateForUser(userId);

        vo.setTotalInvites(inviteMapper.countInviteesByUserId(userId));
        vo.setActiveInvites(inviteMapper.countActiveInviteesByUserId(userId));
        vo.setTotalCommission(inviteMapper.sumTotalCommissionByUserId(userId));
        vo.setCommissionRate(currentUserCommissionRate);

        // 关键修复：今日/昨日/本月佣金必须按当前用户统计（不是全站汇总）
        String todayStart = today.atStartOfDay().format(formatter);
        String todayEndExclusive = now.format(formatter);
        vo.setTodayCommission(inviteMapper.sumCommissionByUserIdAndDateRange(userId, todayStart, todayEndExclusive));

        String yesterdayStart = yesterday.atStartOfDay().format(formatter);
        String yesterdayEndExclusive = today.atStartOfDay().format(formatter);
        vo.setYesterdayCommission(inviteMapper.sumCommissionByUserIdAndDateRange(userId, yesterdayStart, yesterdayEndExclusive));

        String monthStart = firstDayOfMonth.atStartOfDay().format(formatter);
        vo.setThisMonthCommission(inviteMapper.sumCommissionByUserIdAndDateRange(userId, monthStart, todayEndExclusive));

        return vo;
    }

    /**
     * 获取邀请收益排行榜（仅统计邀请人获得的佣金：commission_records.user_id 的 commission_amount 汇总）
     *
     * @param range all / today / yesterday / month
     * @param limit TopN（1-100）
     */
    public InviteLeaderboardVo getInviteLeaderboard(String range, int limit) {
        String normalizedRange = normalizeRange(range);
        int normalizedLimit = normalizeLimit(limit);

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        LocalDate today = LocalDate.now(BJT);
        LocalDate yesterday = today.minusDays(1);
        LocalDate firstDayOfMonth = today.withDayOfMonth(1);

        String startTime = null;
        String endTime = null;
        LocalDateTime now = LocalDateTime.now(BJT);

        switch (normalizedRange) {
            case "today" -> {
                startTime = today.atStartOfDay().format(formatter);
                endTime = now.format(formatter);
            }
            case "yesterday" -> {
                startTime = yesterday.atStartOfDay().format(formatter);
                endTime = today.atStartOfDay().format(formatter);
            }
            case "month" -> {
                startTime = firstDayOfMonth.atStartOfDay().format(formatter);
                endTime = now.format(formatter);
            }
            case "all" -> {
                // no-op
            }
            default -> {
                // normalizeRange 已保证不会走到这里
            }
        }

        List<InviteLeaderboardVo.Item> items = inviteMapper.findInviteCommissionLeaderboard(startTime, endTime, normalizedLimit);
        for (int i = 0; i < items.size(); i++) {
            items.get(i).setRank(i + 1);
        }

        InviteLeaderboardVo vo = new InviteLeaderboardVo();
        vo.setRange(normalizedRange);
        vo.setLimit(normalizedLimit);
        vo.setList(items);
        return vo;
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private int normalizeLimit(int limit) {
        if (limit <= 0) return 20;
        return Math.min(limit, 100);
    }

    private String normalizeRange(String range) {
        if (range == null) return "all";
        String r = range.trim().toLowerCase();
        return switch (r) {
            case "all", "today", "yesterday", "month" -> r;
            default -> "all";
        };
    }
}