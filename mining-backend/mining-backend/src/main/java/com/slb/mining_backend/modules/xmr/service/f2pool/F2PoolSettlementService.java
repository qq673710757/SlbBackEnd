package com.slb.mining_backend.modules.xmr.service.f2pool;

import com.slb.mining_backend.modules.xmr.config.F2PoolProperties;
import com.slb.mining_backend.modules.xmr.dto.WorkerPayhashScore;
import com.slb.mining_backend.modules.xmr.entity.F2PoolPayoutDaily;
import com.slb.mining_backend.modules.xmr.entity.F2PoolSettlement;
import com.slb.mining_backend.modules.xmr.entity.F2PoolSettlementItem;
import com.slb.mining_backend.modules.xmr.mapper.F2PoolPayoutDailyMapper;
import com.slb.mining_backend.modules.xmr.mapper.F2PoolSettlementItemMapper;
import com.slb.mining_backend.modules.xmr.mapper.F2PoolSettlementMapper;
import com.slb.mining_backend.modules.system.service.PlatformSettingsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

@Service
@Slf4j
public class F2PoolSettlementService {

    private static final ZoneId BJT = ZoneId.of("Asia/Shanghai");
    private static final int CAL_SCALE = 8;
    private static final int RATIO_SCALE = 18;

    private final F2PoolProperties properties;
    private final F2PoolPayoutDailyMapper payoutMapper;
    private final F2PoolSettlementMapper settlementMapper;
    private final F2PoolSettlementItemMapper itemMapper;
    private final F2PoolPayhashWindowScoreService payhashWindowScoreService;
    private final WorkerOwnershipResolver ownershipResolver;
    private final F2PoolValuationService valuationService;
    private final F2PoolReconcileService reconcileService;
    private final F2PoolAlertService alertService;
    private final PlatformSettingsService platformSettingsService;
    private final long fallbackUnclaimedUserId;
    private final BigDecimal defaultFeeRate;

    public F2PoolSettlementService(F2PoolProperties properties,
                                   F2PoolPayoutDailyMapper payoutMapper,
                                   F2PoolSettlementMapper settlementMapper,
                                   F2PoolSettlementItemMapper itemMapper,
                                   F2PoolPayhashWindowScoreService payhashWindowScoreService,
                                   WorkerOwnershipResolver ownershipResolver,
                                   F2PoolValuationService valuationService,
                                   F2PoolReconcileService reconcileService,
                                   F2PoolAlertService alertService,
                                   PlatformSettingsService platformSettingsService,
                                   @Value("${app.settlement.unclaimed-user-id:1}") long fallbackUnclaimedUserId,
                                   @Value("${app.platform.commission-rate:0.01}") BigDecimal defaultFeeRate) {
        this.properties = properties;
        this.payoutMapper = payoutMapper;
        this.settlementMapper = settlementMapper;
        this.itemMapper = itemMapper;
        this.payhashWindowScoreService = payhashWindowScoreService;
        this.ownershipResolver = ownershipResolver;
        this.valuationService = valuationService;
        this.reconcileService = reconcileService;
        this.alertService = alertService;
        this.platformSettingsService = platformSettingsService;
        this.fallbackUnclaimedUserId = fallbackUnclaimedUserId;
        this.defaultFeeRate = defaultFeeRate;
    }

    @Scheduled(cron = "${app.f2pool.settlement.cron:0 10 * * * ?}", zone = "Asia/Shanghai")
    public void settleDaily() {
        if (!properties.isEnabled() || CollectionUtils.isEmpty(properties.getAccounts())
                || properties.getSettlement() == null || !properties.getSettlement().isEnabled()) {
            return;
        }
        for (F2PoolProperties.Account account : properties.getAccounts()) {
            if (account == null || !StringUtils.hasText(account.getName()) || !StringUtils.hasText(account.getCoin())) {
                continue;
            }
            settleAccount(account);
        }
    }

    private void settleAccount(F2PoolProperties.Account account) {
        Optional<F2PoolPayoutDaily> payoutOpt = payoutMapper.selectEarliestUnsettled(account.getName(), account.getCoin());
        if (payoutOpt.isEmpty()) {
            return;
        }
        F2PoolPayoutDaily payout = payoutOpt.get();
        LocalDate date = payout.getPayoutDate();
        if (date == null) {
            return;
        }
        if (settlementMapper.selectByAccountAndDate(account.getName(), account.getCoin(), date).isPresent()) {
            return;
        }

        LocalDateTime windowStart = date.atStartOfDay();
        LocalDateTime windowEnd = windowStart.plusDays(1);
        List<WorkerPayhashScore> workerScores = payhashWindowScoreService.aggregate(account.getName(), account.getCoin(), windowStart, windowEnd);
        BigDecimal totalScore = workerScores.stream()
                .map(WorkerPayhashScore::payhash)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (totalScore.compareTo(BigDecimal.ZERO) <= 0) {
            alertService.raiseAlert(account.getName(), account.getCoin(), null,
                    "MISSING_PAYHASH", "WARN", date.toString(), "total payhash is zero");
            return;
        }

        Map<Long, BigDecimal> userScores = collapseToUsers(workerScores);
        if (userScores.isEmpty()) {
            Long unclaimed = resolveUnclaimedUserId();
            if (unclaimed != null) {
                userScores = new LinkedHashMap<>();
                userScores.put(unclaimed, totalScore);
            } else {
                alertService.raiseAlert(account.getName(), account.getCoin(), null,
                        "MISSING_PAYHASH", "WARN", date.toString(), "no user mapping for worker scores");
                return;
            }
        }

        BigDecimal grossAmountCoin = payout.getGrossAmount();
        if (grossAmountCoin == null || grossAmountCoin.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }

        F2PoolValuationService.RateSnapshot rateSnapshot = valuationService.resolveRate(account.getCoin());
        if (rateSnapshot == null || rateSnapshot.coinToCalRate() == null
                || rateSnapshot.coinToCalRate().compareTo(BigDecimal.ZERO) <= 0) {
            alertService.raiseAlert(account.getName(), account.getCoin(), null,
                    "RATE_MISSING", "WARN", date.toString(), "coin to CAL rate unavailable");
            return;
        }

        BigDecimal grossCalTotal = grossAmountCoin.multiply(rateSnapshot.coinToCalRate())
                .setScale(CAL_SCALE, RoundingMode.HALF_UP);
        BigDecimal feeRate = resolveFeeRate();

        F2PoolSettlement settlement = new F2PoolSettlement();
        settlement.setAccount(account.getName());
        settlement.setCoin(account.getCoin());
        settlement.setPayoutDate(date);
        settlement.setGrossAmountCoin(grossAmountCoin);
        settlement.setGrossAmountCal(grossCalTotal);
        settlement.setCalRate(rateSnapshot.coinToCalRate());
        settlement.setRateSource(rateSnapshot.source());
        settlement.setPoolScore(totalScore);
        settlement.setFeeRate(feeRate);
        settlement.setStatus(resolveDefaultStatus());
        settlement.setCreatedTime(LocalDateTime.now(BJT));
        settlement.setUpdatedTime(LocalDateTime.now(BJT));

        List<F2PoolSettlementItem> items = allocateItems(account, userScores, totalScore, grossAmountCoin, grossCalTotal, feeRate, rateSnapshot);
        if (items.isEmpty()) {
            return;
        }

        BigDecimal feeCalTotal = items.stream()
                .map(F2PoolSettlementItem::getFeeCal)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(CAL_SCALE, RoundingMode.HALF_UP);
        BigDecimal netCalTotal = items.stream()
                .map(F2PoolSettlementItem::getNetCal)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(CAL_SCALE, RoundingMode.HALF_UP);

        settlement.setFeeCal(feeCalTotal);
        settlement.setNetCal(netCalTotal);

        settlementMapper.insert(settlement);
        for (F2PoolSettlementItem item : items) {
            item.setSettlementId(settlement.getId());
        }
        itemMapper.insertBatch(items);
        payoutMapper.updateStatus(payout.getId(), "SETTLED");

        BigDecimal settlementGrossCoin = items.stream()
                .map(F2PoolSettlementItem::getGrossAmountCoin)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        reconcileService.reconcileRevenue(account.getName(), account.getCoin(), grossAmountCoin, settlementGrossCoin);
    }

    private List<F2PoolSettlementItem> allocateItems(F2PoolProperties.Account account,
                                                     Map<Long, BigDecimal> userScores,
                                                     BigDecimal totalScore,
                                                     BigDecimal grossAmountCoin,
                                                     BigDecimal grossCalTotal,
                                                     BigDecimal feeRate,
                                                     F2PoolValuationService.RateSnapshot rateSnapshot) {
        if (userScores == null || userScores.isEmpty()) {
            return Collections.emptyList();
        }
        List<F2PoolSettlementItem> items = new ArrayList<>();
        BigDecimal allocatedGrossCal = BigDecimal.ZERO;

        List<Map.Entry<Long, BigDecimal>> ordered = new ArrayList<>(userScores.entrySet());
        ordered.sort(Map.Entry.comparingByKey());
        for (Map.Entry<Long, BigDecimal> entry : ordered) {
            if (entry.getValue() == null || entry.getValue().compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            BigDecimal ratio = entry.getValue().divide(totalScore, RATIO_SCALE, RoundingMode.HALF_UP);
            BigDecimal grossCoin = grossAmountCoin.multiply(ratio).setScale(12, RoundingMode.HALF_UP);
            BigDecimal grossCal = grossCoin.multiply(rateSnapshot.coinToCalRate())
                    .setScale(CAL_SCALE, RoundingMode.HALF_UP);
            BigDecimal feeCal = grossCal.multiply(feeRate).setScale(CAL_SCALE, RoundingMode.HALF_UP);
            BigDecimal netCal = grossCal.subtract(feeCal).setScale(CAL_SCALE, RoundingMode.HALF_UP);
            BigDecimal netCny = rateSnapshot.calToCnyRate() != null && rateSnapshot.calToCnyRate().compareTo(BigDecimal.ZERO) > 0
                    ? netCal.multiply(rateSnapshot.calToCnyRate()).setScale(4, RoundingMode.HALF_UP)
                    : null;

            F2PoolSettlementItem item = new F2PoolSettlementItem();
            item.setAccount(account.getName());
            item.setCoin(account.getCoin());
            item.setUserId(entry.getKey());
            item.setWorkerKey("USR-" + entry.getKey());
            item.setUserScore(entry.getValue());
            item.setRevenueRatio(ratio);
            item.setGrossAmountCoin(grossCoin);
            item.setGrossAmountCal(grossCal);
            item.setFeeCal(feeCal);
            item.setNetCal(netCal);
            item.setNetCny(netCny);
            item.setStatus("PENDING");
            item.setCreatedTime(LocalDateTime.now(BJT));
            items.add(item);

            allocatedGrossCal = allocatedGrossCal.add(grossCal);
        }

        BigDecimal diff = grossCalTotal.subtract(allocatedGrossCal);
        if (diff.compareTo(BigDecimal.ZERO) != 0 && !items.isEmpty()) {
            F2PoolSettlementItem tail = items.get(items.size() - 1);
            BigDecimal adjustedGross = tail.getGrossAmountCal().add(diff).setScale(CAL_SCALE, RoundingMode.HALF_UP);
            BigDecimal adjustedFee = adjustedGross.multiply(feeRate).setScale(CAL_SCALE, RoundingMode.HALF_UP);
            BigDecimal adjustedNet = adjustedGross.subtract(adjustedFee).setScale(CAL_SCALE, RoundingMode.HALF_UP);
            tail.setGrossAmountCal(adjustedGross);
            tail.setFeeCal(adjustedFee);
            tail.setNetCal(adjustedNet);
            if (tail.getNetCny() != null && rateSnapshot.calToCnyRate() != null) {
                tail.setNetCny(adjustedNet.multiply(rateSnapshot.calToCnyRate()).setScale(4, RoundingMode.HALF_UP));
            }
        }
        return items;
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
        Map<String, Long> owners = ownershipResolver.resolveOwners(workerIds);
        Map<Long, BigDecimal> distribution = new LinkedHashMap<>();
        for (WorkerPayhashScore score : workerScores) {
            if (score == null || score.payhash() == null || score.payhash().compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            String workerId = score.workerId();
            Long userId = parseSyntheticUserId(workerId);
            if (userId == null) {
                userId = ownershipResolver.resolveUserId(workerId, owners);
            }
            if (userId == null) {
                Long unclaimed = resolveUnclaimedUserId();
                if (unclaimed != null) {
                    userId = unclaimed;
                } else {
                    continue;
                }
            }
            distribution.merge(userId, score.payhash(), BigDecimal::add);
        }
        return distribution;
    }

    private Long parseSyntheticUserId(String workerId) {
        if (!StringUtils.hasText(workerId)) {
            return null;
        }
        String raw = workerId.trim();
        int idx = raw.indexOf("USR-");
        if (idx < 0) {
            idx = raw.indexOf("usr-");
        }
        if (idx < 0) {
            return null;
        }
        int start = idx + 4;
        int end = start;
        while (end < raw.length() && Character.isDigit(raw.charAt(end))) {
            end++;
        }
        if (end <= start) {
            return null;
        }
        try {
            return Long.parseLong(raw.substring(start, end));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private BigDecimal resolveFeeRate() {
        if (properties.getSettlement() != null && properties.getSettlement().getFeeRate() != null) {
            return properties.getSettlement().getFeeRate();
        }
        if (platformSettingsService != null) {
            BigDecimal rate = platformSettingsService.getPlatformCommissionRate();
            if (rate != null) {
                return rate;
            }
        }
        return defaultFeeRate != null ? defaultFeeRate : BigDecimal.ZERO;
    }

    private String resolveDefaultStatus() {
        if (properties.getSettlement() != null && StringUtils.hasText(properties.getSettlement().getDefaultStatus())) {
            return properties.getSettlement().getDefaultStatus();
        }
        return "AUDIT";
    }

    private Long resolveUnclaimedUserId() {
        if (properties.getSettlement() != null && properties.getSettlement().getUnclaimedUserId() != null) {
            return properties.getSettlement().getUnclaimedUserId();
        }
        return fallbackUnclaimedUserId > 0 ? fallbackUnclaimedUserId : null;
    }
}
