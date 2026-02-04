package com.slb.mining_backend.modules.xmr.service;

import com.slb.mining_backend.modules.exchange.service.ExchangeRateService;
import com.slb.mining_backend.modules.users.entity.User;
import com.slb.mining_backend.modules.users.mapper.UserMapper;
import com.slb.mining_backend.modules.xmr.entity.XmrDailyValuation;
import com.slb.mining_backend.modules.xmr.mapper.XmrDailyValuationMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/**
 * 每日定时生成 XMR 资产估值快照，便于审计与追踪。
 */
@Service
@Slf4j
public class XmrValuationScheduler {

    private final UserMapper userMapper;
    private final XmrDailyValuationMapper dailyValuationMapper;
    private final ExchangeRateService exchangeRateService;

    public XmrValuationScheduler(UserMapper userMapper,
                                 XmrDailyValuationMapper dailyValuationMapper,
                                 ExchangeRateService exchangeRateService) {
        this.userMapper = userMapper;
        this.dailyValuationMapper = dailyValuationMapper;
        this.exchangeRateService = exchangeRateService;
    }

    /** 每日 00:00 生成快照。 */
    @Scheduled(cron = "0 0 0 * * ?")
    public void snapshotDailyBalances() {
        BigDecimal rate = exchangeRateService.getLatestRate("XMR/CNY");
        if (rate == null || rate.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("Skip daily valuation snapshot because XMR/CNY rate is unavailable");
            return;
        }
        List<User> users = userMapper.selectAllForXmr();
        if (users.isEmpty()) {
            return;
        }
        LocalDate today = LocalDate.now();
        for (User user : users) {
            XmrDailyValuation record = new XmrDailyValuation();
            record.setUserId(user.getId());
            record.setWorkerId(user.getWorkerId());
            BigDecimal balance = safe(user.getXmrBalance());
            BigDecimal frozen = safe(user.getFrozenXmr());
            BigDecimal totalEarned = safe(user.getTotalEarnedXmr());
            record.setBalanceXmr(balance);
            record.setFrozenXmr(frozen);
            record.setTotalEarnedXmr(totalEarned);
            record.setBalanceCny(balance.add(frozen).multiply(rate).setScale(2, RoundingMode.HALF_UP));
            record.setTotalEarnedCny(totalEarned.multiply(rate).setScale(2, RoundingMode.HALF_UP));
            record.setRate(rate);
            record.setSnapshotDate(today);
            try {
                dailyValuationMapper.insert(record);
            } catch (Exception ex) {
                log.warn("Failed to insert valuation snapshot for user {}: {}", user.getId(), ex.getMessage());
            }
        }
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
