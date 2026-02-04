package com.slb.mining_backend.modules.xmr.service.f2pool;

import com.slb.mining_backend.modules.earnings.service.MarketDataService;
import com.slb.mining_backend.modules.users.dto.WorkerUserBinding;
import com.slb.mining_backend.modules.users.mapper.UserMapper;
import com.slb.mining_backend.modules.xmr.config.F2PoolProperties;
import com.slb.mining_backend.modules.xmr.dto.WorkerPayhashScore;
import com.slb.mining_backend.modules.xmr.entity.F2PoolAssetsBalance;
import com.slb.mining_backend.modules.xmr.entity.F2PoolPayhashHourlySettlement;
import com.slb.mining_backend.modules.xmr.mapper.F2PoolAssetsBalanceMapper;
import com.slb.mining_backend.modules.xmr.mapper.F2PoolPayhashHourlySettlementMapper;
import com.slb.mining_backend.modules.xmr.service.XmrWalletSettlementService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@Slf4j
public class F2PoolPayhashHourlySettlementService {

    private static final ZoneId BJT = ZoneId.of("Asia/Shanghai");
    private static final int CAL_SCALE = 8;
    private static final int XMR_SCALE = 12;
    private static final String ALLOCATION_SOURCE_POOL = "POOL";
    private static final String ALLOCATION_SOURCE_ADMIN = "ADMIN";
    private static final String REMARK_NO_INCREMENT = "NO_POOL_INCREMENT";
    private static final String REMARK_PAYHASH_UNAVAILABLE = "PAYHASH_UNAVAILABLE_ADMIN";

    private record DeltaSnapshot(BigDecimal delta,
                                 F2PoolAssetsBalance start,
                                 F2PoolAssetsBalance end,
                                 boolean reusedStart) {
        static DeltaSnapshot empty() {
            return new DeltaSnapshot(BigDecimal.ZERO, null, null, false);
        }
    }

    private final F2PoolProperties properties;
    private final F2PoolPayhashWindowScoreService payhashWindowScoreService;
    private final F2PoolPayhashHourlySettlementMapper settlementMapper;
    private final F2PoolAssetsBalanceMapper assetsBalanceMapper;
    private final F2PoolValuationService valuationService;
    private final MarketDataService marketDataService;
    private final UserMapper userMapper;
    private final XmrWalletSettlementService walletSettlementService;
    private final F2PoolAlertService alertService;
    private final Long unclaimedUserId;
    private final boolean enabled;
    private final BigDecimal anomalyMultiplier;
    private final int anomalyLookbackDays;
    private final int anomalyMinSamples;
    private final int accountOverviewLagMinutes;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public F2PoolPayhashHourlySettlementService(F2PoolProperties properties,
                                                F2PoolPayhashWindowScoreService payhashWindowScoreService,
                                                F2PoolPayhashHourlySettlementMapper settlementMapper,
                                                F2PoolAssetsBalanceMapper assetsBalanceMapper,
                                                F2PoolValuationService valuationService,
                                                MarketDataService marketDataService,
                                                UserMapper userMapper,
                                                XmrWalletSettlementService walletSettlementService,
                                                F2PoolAlertService alertService,
                                                @Value("${app.settlement.unclaimed-user-id:1}") long unclaimedUserId,
                                                @Value("${app.f2pool.hourly-settlement.enabled:false}") boolean enabled,
                                                @Value("${app.f2pool.hourly-settlement.anomaly-multiplier:10}") BigDecimal anomalyMultiplier,
                                                @Value("${app.f2pool.hourly-settlement.anomaly-lookback-days:7}") int anomalyLookbackDays,
                                                @Value("${app.f2pool.hourly-settlement.anomaly-min-samples:24}") int anomalyMinSamples,
                                                @Value("${app.f2pool.hourly-settlement.account-overview-lag-minutes:10}") int accountOverviewLagMinutes) {
        this.properties = properties;
        this.payhashWindowScoreService = payhashWindowScoreService;
        this.settlementMapper = settlementMapper;
        this.assetsBalanceMapper = assetsBalanceMapper;
        this.valuationService = valuationService;
        this.marketDataService = marketDataService;
        this.userMapper = userMapper;
        this.walletSettlementService = walletSettlementService;
        this.alertService = alertService;
        this.unclaimedUserId = unclaimedUserId > 0 ? unclaimedUserId : null;
        this.enabled = enabled;
        this.anomalyMultiplier = anomalyMultiplier != null ? anomalyMultiplier : BigDecimal.ZERO;
        this.anomalyLookbackDays = Math.max(1, anomalyLookbackDays);
        this.anomalyMinSamples = Math.max(1, anomalyMinSamples);
        this.accountOverviewLagMinutes = Math.max(0, accountOverviewLagMinutes);
    }

    @Scheduled(cron = "${app.f2pool.hourly-settlement.cron:0 5 * * * ?}", zone = "Asia/Shanghai")
    public void settleHourlyByPayhash() {
        if (!enabled || !properties.isEnabled() || CollectionUtils.isEmpty(properties.getAccounts())) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            LocalDateTime now = LocalDateTime.now(BJT);
            LocalDateTime windowEnd = truncateToHour(now);
            LocalDateTime windowStart = windowEnd.minusHours(1);
            for (F2PoolProperties.Account account : properties.getAccounts()) {
                if (account == null || !StringUtils.hasText(account.getName()) || !StringUtils.hasText(account.getCoin())) {
                    continue;
                }
                settleAccountHour(account, windowStart, windowEnd);
            }
        } finally {
            running.set(false);
        }
    }

    private void settleAccountHour(F2PoolProperties.Account account, LocalDateTime windowStart, LocalDateTime windowEnd) {
        Optional<F2PoolPayhashHourlySettlement> existing = settlementMapper
                .selectByAccountAndWindowStart(account.getName(), account.getCoin(), windowStart);
        if (existing.isPresent()) {
            return;
        }
        List<WorkerPayhashScore> workerScores = payhashWindowScoreService.aggregate(
                account.getName(), account.getCoin(), windowStart, windowEnd);
        String payhashReason = null;
        Map<Long, BigDecimal> userScores = Collections.emptyMap();
        BigDecimal totalScore = BigDecimal.ZERO;
        if (workerScores.isEmpty()) {
            payhashReason = "EMPTY_PAYHASH_WINDOW";
        } else {
            totalScore = workerScores.stream()
                    .map(WorkerPayhashScore::payhash)
                    .filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (totalScore.compareTo(BigDecimal.ZERO) <= 0) {
                payhashReason = "TOTAL_PAYHASH_ZERO";
            } else {
                userScores = collapseToUsers(workerScores);
                if (userScores.isEmpty()) {
                    payhashReason = "NO_USER_MAPPING";
                }
            }
        }

        DeltaSnapshot deltaSnapshot = resolveHourlyCoinAmount(account, windowStart, windowEnd);
        BigDecimal totalCoinHour = deltaSnapshot != null ? deltaSnapshot.delta() : BigDecimal.ZERO;
        if (totalCoinHour == null || totalCoinHour.compareTo(BigDecimal.ZERO) <= 0) {
            String remark = payhashReason == null ? REMARK_NO_INCREMENT : REMARK_NO_INCREMENT + ":" + payhashReason;
            log.info("F2Pool hourly settlement: no pool increment, skip settlement (account={}, coin={}, windowStart={}, windowEnd={}, payhashReason={}, remark={})",
                    account.getName(), account.getCoin(), windowStart, windowEnd, payhashReason, remark);
            insertAdminPlaceholder(account, windowStart, windowEnd, remark, deltaSnapshot);
            return;
        }
        if (payhashReason != null) {
            String remark = REMARK_PAYHASH_UNAVAILABLE + ":" + payhashReason;
            log.info("F2Pool hourly settlement: payhash unavailable, allocate increment to admin (account={}, coin={}, windowStart={}, windowEnd={}, reason={}, totalCoin={}, remark={})",
                    account.getName(), account.getCoin(), windowStart, windowEnd, payhashReason, totalCoinHour, remark);
            alertService.raiseAlert(account.getName(), account.getCoin(), null,
                    "PAYHASH_MISSING", "WARN", windowStart.toString(), "reason=" + payhashReason);
            settleAdminOnly(account, windowStart, windowEnd, totalCoinHour, remark, deltaSnapshot);
            return;
        }
        raiseHourlyEarningSpikeAlertIfNeeded(account, windowStart, totalCoinHour);

        F2PoolValuationService.RateSnapshot rateSnapshot = valuationService.resolveRate(account.getCoin());
        if (rateSnapshot == null || rateSnapshot.coinToCalRate() == null
                || rateSnapshot.coinToCalRate().compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("F2Pool hourly payhash settlement skipped: coin->CAL rate unavailable (account={}, coin={}, windowStart={})",
                    account.getName(), account.getCoin(), windowStart);
            return;
        }

        BigDecimal totalCalHour = totalCoinHour.multiply(rateSnapshot.coinToCalRate())
                .setScale(CAL_SCALE, RoundingMode.HALF_UP);
        BigDecimal calXmrRatio = marketDataService.getCalXmrRatio();
        if (calXmrRatio == null || calXmrRatio.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("F2Pool hourly payhash settlement skipped: CAL/XMR ratio unavailable (account={}, coin={}, windowStart={})",
                    account.getName(), account.getCoin(), windowStart);
            return;
        }
        BigDecimal totalXmrHour = totalCalHour.multiply(calXmrRatio).setScale(XMR_SCALE, RoundingMode.HALF_UP);
        if (totalXmrHour.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("F2Pool hourly payhash settlement skipped: hourly XMR amount is zero (account={}, coin={}, windowStart={})",
                    account.getName(), account.getCoin(), windowStart);
            return;
        }
        Map<Long, BigDecimal> shareMap = allocateByRatio(totalXmrHour, totalScore, userScores);
        if (shareMap.isEmpty()) {
            log.warn("F2Pool hourly payhash settlement skipped: empty share map (account={}, coin={}, windowStart={})",
                    account.getName(), account.getCoin(), windowStart);
            return;
        }

        String gpuEarningTypeOverride = resolveGpuEarningTypeOverrideByCoin(account.getCoin());
        boolean forceGpuWhenOverride = StringUtils.hasText(gpuEarningTypeOverride);
        BigDecimal cpuTotalCal = BigDecimal.ZERO;
        BigDecimal gpuCfxTotalCal = BigDecimal.ZERO;
        BigDecimal gpuRvnTotalCal = BigDecimal.ZERO;
        for (Map.Entry<Long, BigDecimal> entry : shareMap.entrySet()) {
            XmrWalletSettlementService.MiningSplitAmount split = walletSettlementService.previewMiningSplit(
                    entry.getKey(), entry.getValue(), gpuEarningTypeOverride, forceGpuWhenOverride, false);
            if (split != null) {
                cpuTotalCal = cpuTotalCal.add(safe(split.cpuCal()));
                if ("GPU_OCTOPUS".equalsIgnoreCase(split.gpuType())) {
                    gpuCfxTotalCal = gpuCfxTotalCal.add(safe(split.gpuCal()));
                } else if ("GPU_KAWPOW".equalsIgnoreCase(split.gpuType())) {
                    gpuRvnTotalCal = gpuRvnTotalCal.add(safe(split.gpuCal()));
                }
            }
        }

        F2PoolPayhashHourlySettlement record = new F2PoolPayhashHourlySettlement();
        record.setAccount(account.getName());
        record.setCoin(account.getCoin());
        record.setWindowStart(windowStart);
        record.setWindowEnd(windowEnd);
        record.setTotalCoin(totalCoinHour);
        record.setTotalCal(totalCalHour);
        record.setTotalXmr(totalXmrHour);
        record.setRateSource(rateSnapshot.source());
        record.setAllocationSource(ALLOCATION_SOURCE_POOL);
        record.setFallbackReason(null);
        record.setCpuTotalCal(cpuTotalCal.setScale(CAL_SCALE, RoundingMode.HALF_UP));
        record.setGpuCfxTotalCal(gpuCfxTotalCal.setScale(CAL_SCALE, RoundingMode.HALF_UP));
        record.setGpuRvnTotalCal(gpuRvnTotalCal.setScale(CAL_SCALE, RoundingMode.HALF_UP));
        record.setStatus("PROCESSING");
        record.setCreatedTime(LocalDateTime.now(BJT));
        record.setUpdatedTime(LocalDateTime.now(BJT));
        applySnapshotInfo(record, deltaSnapshot);
        int inserted = settlementMapper.insertIgnore(record);
        if (inserted == 0 || record.getId() == null) {
            return;
        }

        String txHash = buildTxHash(account, windowStart);
        try {
            for (Map.Entry<Long, BigDecimal> entry : shareMap.entrySet()) {
                walletSettlementService.distributeXmrShare(entry.getKey(), entry.getValue(), txHash, windowStart,
                        gpuEarningTypeOverride, forceGpuWhenOverride);
            }
            settlementMapper.updateStatus(record.getId(), "SUCCESS", LocalDateTime.now(BJT));
        } catch (Exception ex) {
            settlementMapper.updateStatus(record.getId(), "FAILED", LocalDateTime.now(BJT));
            log.error("F2Pool hourly payhash settlement failed (account={}, coin={}, windowStart={}): {}",
                    account.getName(), account.getCoin(), windowStart, ex.getMessage());
        }
    }

    private DeltaSnapshot resolveHourlyCoinAmount(F2PoolProperties.Account account,
                                                  LocalDateTime windowStart,
                                                  LocalDateTime windowEnd) {
        return resolveHourlyFromEstimatedIncomeDelta(account, windowStart, windowEnd);
    }

    private DeltaSnapshot resolveHourlyFromEstimatedIncomeDelta(F2PoolProperties.Account account,
                                                                LocalDateTime windowStart,
                                                                LocalDateTime windowEnd) {
        if (account == null || !StringUtils.hasText(account.getName()) || !StringUtils.hasText(account.getCoin())
                || windowStart == null || windowEnd == null) {
            return DeltaSnapshot.empty();
        }
        Optional<F2PoolAssetsBalance> endOpt = assetsBalanceMapper
                .selectLatestBefore(account.getName(), account.getCoin(), windowEnd);
        Optional<F2PoolAssetsBalance> startOpt = Optional.empty();
        boolean reusedStart = false;
        Optional<F2PoolPayhashHourlySettlement> prevSettlement = settlementMapper
                .selectByAccountAndWindowEnd(account.getName(), account.getCoin(), windowStart);
        if (prevSettlement.isPresent() && prevSettlement.get().getEndSnapshotId() != null) {
            Optional<F2PoolAssetsBalance> prevEndOpt = assetsBalanceMapper
                    .selectById(prevSettlement.get().getEndSnapshotId());
            if (prevEndOpt.isPresent()) {
                startOpt = prevEndOpt;
                reusedStart = true;
            } else {
                log.info("F2Pool hourly settlement: previous end snapshot not found, fallback to windowStart lookup " +
                                "(account={}, coin={}, windowStart={}, prevSettlementId={}, endSnapshotId={})",
                        account.getName(), account.getCoin(), windowStart,
                        prevSettlement.get().getId(), prevSettlement.get().getEndSnapshotId());
            }
        }
        if (startOpt.isEmpty()) {
            startOpt = assetsBalanceMapper.selectLatestBefore(account.getName(), account.getCoin(), windowStart);
        }
        if (endOpt.isEmpty() || startOpt.isEmpty()) {
            log.info("F2Pool hourly settlement: missing assets balance snapshots for estimated_today_income delta (account={}, coin={}, windowStart={}, windowEnd={}, hasStart={}, hasEnd={})",
                    account.getName(), account.getCoin(), windowStart, windowEnd, startOpt.isPresent(), endOpt.isPresent());
            return new DeltaSnapshot(BigDecimal.ZERO, startOpt.orElse(null), endOpt.orElse(null), reusedStart);
        }
        LocalDateTime endFetchedAt = endOpt.get().getFetchedAt();
        LocalDateTime startFetchedAt = startOpt.get().getFetchedAt();
        if (endFetchedAt == null || startFetchedAt == null) {
            log.info("F2Pool hourly settlement: assets balance snapshot timestamps missing, skip estimated_today_income delta (account={}, coin={}, windowStart={}, windowEnd={}, startAt={}, endAt={})",
                    account.getName(), account.getCoin(), windowStart, windowEnd, startFetchedAt, endFetchedAt);
            return new DeltaSnapshot(BigDecimal.ZERO, startOpt.get(), endOpt.get(), reusedStart);
        }
        if (accountOverviewLagMinutes > 0) {
            long startLagMinutes = Math.abs(Duration.between(startFetchedAt, windowStart).toMinutes());
            long endLagMinutes = Math.abs(Duration.between(endFetchedAt, windowEnd).toMinutes());
            if (startLagMinutes > accountOverviewLagMinutes || endLagMinutes > accountOverviewLagMinutes) {
                log.info("F2Pool hourly settlement: assets balance snapshots too old, skip estimated_today_income delta " +
                                "(account={}, coin={}, windowStart={}, windowEnd={}, startAt={}, endAt={}, startLagMinutes={}, endLagMinutes={}, maxLagMinutes={})",
                        account.getName(), account.getCoin(), windowStart, windowEnd,
                        startFetchedAt, endFetchedAt, startLagMinutes, endLagMinutes, accountOverviewLagMinutes);
                return new DeltaSnapshot(BigDecimal.ZERO, startOpt.get(), endOpt.get(), reusedStart);
            }
        }
        BigDecimal endValue = safe(endOpt.get().getEstimatedTodayIncome());
        BigDecimal startValue = safe(startOpt.get().getEstimatedTodayIncome());
        BigDecimal delta = endValue.subtract(startValue);
        if (delta.compareTo(BigDecimal.ZERO) <= 0) {
            log.info("F2Pool hourly settlement: estimated_today_income delta not positive (account={}, coin={}, windowStart={}, start={}, end={}, delta={})",
                    account.getName(), account.getCoin(), windowStart, startValue, endValue, delta);
            return new DeltaSnapshot(delta, startOpt.get(), endOpt.get(), reusedStart);
        }
        log.info("F2Pool hourly settlement: estimated_today_income delta applied (account={}, coin={}, windowStart={}, windowEnd={}, start={}, end={}, delta={})",
                account.getName(), account.getCoin(), windowStart, windowEnd, startValue, endValue, delta);
        return new DeltaSnapshot(delta, startOpt.get(), endOpt.get(), reusedStart);
    }


    private void settleAdminOnly(F2PoolProperties.Account account,
                                 LocalDateTime windowStart,
                                 LocalDateTime windowEnd,
                                 BigDecimal totalCoinHour,
                                 String remark,
                                 DeltaSnapshot deltaSnapshot) {
        if (account == null || !StringUtils.hasText(account.getName()) || !StringUtils.hasText(account.getCoin())) {
            return;
        }
        if (totalCoinHour == null || totalCoinHour.compareTo(BigDecimal.ZERO) <= 0) {
            insertAdminPlaceholder(account, windowStart, windowEnd, remark, deltaSnapshot);
            return;
        }
        if (unclaimedUserId == null) {
            log.warn("F2Pool hourly settlement admin hold skipped: admin user not configured (account={}, coin={}, windowStart={})",
                    account.getName(), account.getCoin(), windowStart);
            insertAdminPlaceholder(account, windowStart, windowEnd, remark, deltaSnapshot);
            return;
        }
        F2PoolValuationService.RateSnapshot rateSnapshot = valuationService.resolveRate(account.getCoin());
        if (rateSnapshot == null || rateSnapshot.coinToCalRate() == null
                || rateSnapshot.coinToCalRate().compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("F2Pool hourly settlement admin hold skipped: coin->CAL rate unavailable (account={}, coin={}, windowStart={})",
                    account.getName(), account.getCoin(), windowStart);
            insertAdminPlaceholder(account, windowStart, windowEnd, remark, deltaSnapshot);
            return;
        }
        BigDecimal totalCalHour = totalCoinHour.multiply(rateSnapshot.coinToCalRate())
                .setScale(CAL_SCALE, RoundingMode.HALF_UP);
        BigDecimal calXmrRatio = marketDataService.getCalXmrRatio();
        if (calXmrRatio == null || calXmrRatio.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("F2Pool hourly settlement admin hold skipped: CAL/XMR ratio unavailable (account={}, coin={}, windowStart={})",
                    account.getName(), account.getCoin(), windowStart);
            insertAdminPlaceholder(account, windowStart, windowEnd, remark, deltaSnapshot);
            return;
        }
        BigDecimal totalXmrHour = totalCalHour.multiply(calXmrRatio).setScale(XMR_SCALE, RoundingMode.HALF_UP);
        if (totalXmrHour.compareTo(BigDecimal.ZERO) <= 0) {
            insertAdminPlaceholder(account, windowStart, windowEnd, remark, deltaSnapshot);
            return;
        }

        String gpuEarningTypeOverride = resolveGpuEarningTypeOverrideByCoin(account.getCoin());
        boolean forceGpuWhenOverride = StringUtils.hasText(gpuEarningTypeOverride);
        BigDecimal cpuTotalCal = BigDecimal.ZERO;
        BigDecimal gpuCfxTotalCal = BigDecimal.ZERO;
        BigDecimal gpuRvnTotalCal = BigDecimal.ZERO;
        XmrWalletSettlementService.MiningSplitAmount split = walletSettlementService.previewMiningSplit(
                unclaimedUserId, totalXmrHour, gpuEarningTypeOverride, forceGpuWhenOverride, false);
        if (split != null) {
            cpuTotalCal = safe(split.cpuCal());
            if ("GPU_OCTOPUS".equalsIgnoreCase(split.gpuType())) {
                gpuCfxTotalCal = safe(split.gpuCal());
            } else if ("GPU_KAWPOW".equalsIgnoreCase(split.gpuType())) {
                gpuRvnTotalCal = safe(split.gpuCal());
            }
        }

        F2PoolPayhashHourlySettlement record = new F2PoolPayhashHourlySettlement();
        record.setAccount(account.getName());
        record.setCoin(account.getCoin());
        record.setWindowStart(windowStart);
        record.setWindowEnd(windowEnd);
        record.setTotalCoin(totalCoinHour);
        record.setTotalCal(totalCalHour);
        record.setTotalXmr(totalXmrHour);
        record.setRateSource(rateSnapshot.source());
        record.setAllocationSource(ALLOCATION_SOURCE_ADMIN);
        record.setFallbackReason(null);
        record.setRemark(remark);
        record.setCpuTotalCal(cpuTotalCal.setScale(CAL_SCALE, RoundingMode.HALF_UP));
        record.setGpuCfxTotalCal(gpuCfxTotalCal.setScale(CAL_SCALE, RoundingMode.HALF_UP));
        record.setGpuRvnTotalCal(gpuRvnTotalCal.setScale(CAL_SCALE, RoundingMode.HALF_UP));
        record.setStatus("PROCESSING");
        record.setCreatedTime(LocalDateTime.now(BJT));
        record.setUpdatedTime(LocalDateTime.now(BJT));
        applySnapshotInfo(record, deltaSnapshot);
        int inserted = settlementMapper.insertIgnore(record);
        if (inserted == 0 || record.getId() == null) {
            return;
        }
        String txHash = buildTxHash(account, windowStart);
        try {
            walletSettlementService.distributeXmrShare(unclaimedUserId, totalXmrHour, txHash, windowStart,
                    gpuEarningTypeOverride, forceGpuWhenOverride);
            settlementMapper.updateStatus(record.getId(), "SUCCESS", LocalDateTime.now(BJT));
        } catch (Exception ex) {
            settlementMapper.updateStatus(record.getId(), "FAILED", LocalDateTime.now(BJT));
            log.error("F2Pool hourly settlement admin hold failed (account={}, coin={}, windowStart={}): {}",
                    account.getName(), account.getCoin(), windowStart, ex.getMessage());
        }
    }

    private void insertAdminPlaceholder(F2PoolProperties.Account account,
                                        LocalDateTime windowStart,
                                        LocalDateTime windowEnd,
                                        String remark,
                                        DeltaSnapshot deltaSnapshot) {
        if (account == null || !StringUtils.hasText(account.getName()) || !StringUtils.hasText(account.getCoin())) {
            return;
        }
        if (!StringUtils.hasText(remark)) {
            return;
        }
        F2PoolPayhashHourlySettlement record = new F2PoolPayhashHourlySettlement();
        record.setAccount(account.getName());
        record.setCoin(account.getCoin());
        record.setWindowStart(windowStart);
        record.setWindowEnd(windowEnd);
        record.setTotalCoin(BigDecimal.ZERO);
        record.setTotalCal(BigDecimal.ZERO);
        record.setTotalXmr(BigDecimal.ZERO);
        record.setRateSource(null);
        record.setAllocationSource(ALLOCATION_SOURCE_ADMIN);
        record.setFallbackReason(null);
        record.setRemark(remark);
        record.setCpuTotalCal(BigDecimal.ZERO);
        record.setGpuCfxTotalCal(BigDecimal.ZERO);
        record.setGpuRvnTotalCal(BigDecimal.ZERO);
        record.setStatus("SKIPPED");
        record.setCreatedTime(LocalDateTime.now(BJT));
        record.setUpdatedTime(LocalDateTime.now(BJT));
        applySnapshotInfo(record, deltaSnapshot);
        settlementMapper.insertIgnore(record);
    }

    private String resolveGpuEarningTypeOverrideByCoin(String coin) {
        if (!StringUtils.hasText(coin)) {
            return null;
        }
        String normalized = coin.trim().toUpperCase(Locale.ROOT);
        if ("CONFLUX".equals(normalized) || "CONFLUXTOKEN".equals(normalized) || "CFX".equals(normalized)) {
            return "GPU_OCTOPUS";
        }
        if ("RAVENCOIN".equals(normalized) || "RVN".equals(normalized) || "KAWPOW".equals(normalized)) {
            return "GPU_KAWPOW";
        }
        return null;
    }

    private void raiseHourlyEarningSpikeAlertIfNeeded(F2PoolProperties.Account account,
                                                      LocalDateTime windowStart,
                                                      BigDecimal hourlyCoin) {
        if (account == null || !StringUtils.hasText(account.getName()) || !StringUtils.hasText(account.getCoin())) {
            return;
        }
        if (hourlyCoin == null || hourlyCoin.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        if (anomalyMultiplier == null || anomalyMultiplier.compareTo(BigDecimal.ONE) <= 0) {
            return;
        }
        if (windowStart == null) {
            return;
        }
        LocalDateTime end = windowStart;
        LocalDateTime start = windowStart.minusDays(anomalyLookbackDays);
        int sampleCount = settlementMapper.countByAccountAndWindowStartRange(account.getName(), account.getCoin(), start, end);
        if (sampleCount < anomalyMinSamples) {
            return;
        }
        BigDecimal avg = settlementMapper.selectAverageTotalCoin(account.getName(), account.getCoin(), start, end);
        if (avg == null || avg.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        BigDecimal threshold = avg.multiply(anomalyMultiplier);
        if (hourlyCoin.compareTo(threshold) <= 0) {
            return;
        }
        String refKey = windowStart.toString();
        String message = "hourlyCoin=" + hourlyCoin
                + ", avg" + anomalyLookbackDays + "d=" + avg
                + ", multiplier=" + anomalyMultiplier
                + ", threshold=" + threshold
                + ", samples=" + sampleCount;
        alertService.raiseAlert(account.getName(), account.getCoin(), null,
                "HOURLY_EARNING_SPIKE", "WARN", refKey, message);
    }

    private BigDecimal safe(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    private void applySnapshotInfo(F2PoolPayhashHourlySettlement record, DeltaSnapshot deltaSnapshot) {
        if (record == null || deltaSnapshot == null) {
            return;
        }
        F2PoolAssetsBalance start = deltaSnapshot.start();
        if (start != null) {
            record.setStartSnapshotId(start.getId());
            record.setStartSnapshotAt(start.getFetchedAt());
        }
        F2PoolAssetsBalance end = deltaSnapshot.end();
        if (end != null) {
            record.setEndSnapshotId(end.getId());
            record.setEndSnapshotAt(end.getFetchedAt());
        }
    }

    private Map<Long, BigDecimal> collapseToUsers(List<WorkerPayhashScore> workerScores) {
        if (workerScores == null || workerScores.isEmpty()) {
            return Collections.emptyMap();
        }
        Set<String> workerIds = new HashSet<>();
        for (WorkerPayhashScore score : workerScores) {
            if (score != null && StringUtils.hasText(score.workerId())) {
                workerIds.add(score.workerId());
            }
        }
        List<WorkerUserBinding> bindings = workerIds.isEmpty()
                ? Collections.emptyList()
                : userMapper.selectByWorkerIds(new ArrayList<>(workerIds));
        Map<String, Long> owners = new HashMap<>();
        for (WorkerUserBinding binding : bindings) {
            if (binding.getWorkerId() != null && binding.getUserId() != null) {
                owners.put(binding.getWorkerId(), binding.getUserId());
            }
        }
        Map<Long, BigDecimal> distribution = new HashMap<>();
        for (WorkerPayhashScore score : workerScores) {
            if (score == null || score.payhash() == null || score.payhash().compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            Long userId = owners.get(score.workerId());
            if (userId == null) {
                userId = parseSyntheticUserId(score.workerId());
            }
            if (userId == null) {
                if (unclaimedUserId == null) {
                    continue;
                }
                userId = unclaimedUserId;
            }
            distribution.merge(userId, score.payhash(), BigDecimal::add);
        }
        if (unclaimedUserId != null) {
            distribution.putIfAbsent(unclaimedUserId, BigDecimal.ZERO);
        }
        return distribution;
    }

    private Map<Long, BigDecimal> allocateByRatio(BigDecimal reward,
                                                  BigDecimal totalScore,
                                                  Map<Long, BigDecimal> userScores) {
        if (reward == null || reward.compareTo(BigDecimal.ZERO) <= 0) {
            return Collections.emptyMap();
        }
        if (totalScore == null || totalScore.compareTo(BigDecimal.ZERO) <= 0) {
            return Collections.emptyMap();
        }
        List<Map.Entry<Long, BigDecimal>> entries = userScores.entrySet().stream()
                .filter(entry -> entry.getValue() != null && entry.getValue().compareTo(BigDecimal.ZERO) > 0)
                .sorted(Map.Entry.comparingByKey())
                .toList();
        Map<Long, BigDecimal> shareMap = new LinkedHashMap<>();
        BigDecimal remaining = reward;
        for (Map.Entry<Long, BigDecimal> entry : entries) {
            if (unclaimedUserId != null && unclaimedUserId.equals(entry.getKey())) {
                continue;
            }
            BigDecimal ratio = entry.getValue().divide(totalScore, 18, RoundingMode.HALF_UP);
            BigDecimal portion = reward.multiply(ratio).setScale(XMR_SCALE, RoundingMode.DOWN);
            if (portion.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            if (portion.compareTo(remaining) > 0) {
                portion = remaining;
            }
            remaining = remaining.subtract(portion);
            shareMap.put(entry.getKey(), portion);
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
                remaining = BigDecimal.ZERO;
                break;
            }
        }
        if (remaining.compareTo(BigDecimal.ZERO) > 0) {
            if (unclaimedUserId != null) {
                shareMap.merge(unclaimedUserId, remaining, BigDecimal::add);
            } else if (!entries.isEmpty()) {
                Long last = entries.get(entries.size() - 1).getKey();
                shareMap.merge(last, remaining, BigDecimal::add);
            }
        }
        return shareMap;
    }

    private String buildTxHash(F2PoolProperties.Account account, LocalDateTime windowStart) {
        return "f2pool:payhash-hourly:" + account.getCoin().toLowerCase(Locale.ROOT)
                + ":" + account.getName() + ":" + windowStart;
    }

    private Long parseSyntheticUserId(String workerId) {
        if (!StringUtils.hasText(workerId)) {
            return null;
        }
        String raw = workerId.trim();
        if (!(raw.startsWith("USR-") || raw.startsWith("usr-"))) {
            return null;
        }
        try {
            String digits = raw.substring(4);
            if (digits.isEmpty()) return null;
            for (int i = 0; i < digits.length(); i++) {
                if (!Character.isDigit(digits.charAt(i))) {
                    return null;
                }
            }
            return Long.parseLong(digits);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private LocalDateTime truncateToHour(LocalDateTime time) {
        if (time == null) {
            return null;
        }
        return time.withMinute(0).withSecond(0).withNano(0);
    }
}
