package com.slb.mining_backend.modules.xmr.service.antpool;

import com.slb.mining_backend.modules.earnings.service.MarketDataService;
import com.slb.mining_backend.modules.users.dto.WorkerUserBinding;
import com.slb.mining_backend.modules.users.mapper.UserMapper;
import com.slb.mining_backend.modules.xmr.config.AntpoolProperties;
import com.slb.mining_backend.modules.xmr.dto.WorkerPayhashScore;
import com.slb.mining_backend.modules.xmr.entity.AntpoolAccountBalance;
import com.slb.mining_backend.modules.xmr.entity.AntpoolPayhashHourlySettlement;
import com.slb.mining_backend.modules.xmr.mapper.AntpoolAccountBalanceMapper;
import com.slb.mining_backend.modules.xmr.mapper.AntpoolPayhashHourlySettlementMapper;
import com.slb.mining_backend.modules.xmr.service.XmrWalletSettlementService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@Slf4j
public class AntpoolPayhashHourlySettlementService {

    private static final ZoneId BJT = ZoneId.of("Asia/Shanghai");
    private static final int CAL_SCALE = 8;
    private static final int XMR_SCALE = 12;
    private static final int SNAPSHOT_INTERVAL_MINUTES = 5;
    private static final int SNAPSHOT_EXPECTED_SAMPLES = 12;
    private static final String ALLOCATION_SOURCE_POOL = "POOL";
    private static final String ALLOCATION_SOURCE_ADMIN = "ADMIN";
    private static final String AMOUNT_SOURCE_ACCOUNT_BALANCE = "ACCOUNT_BALANCE_DELTA";
    private static final String REMARK_NO_INCREMENT = "NO_POOL_INCREMENT";
    private static final String REMARK_PAYHASH_UNAVAILABLE = "PAYHASH_UNAVAILABLE_ADMIN";

    private final AntpoolProperties properties;
    private final AntpoolPayhashWindowScoreService payhashWindowScoreService;
    private final AntpoolPayhashHourlySettlementMapper settlementMapper;
    private final AntpoolAccountBalanceMapper accountBalanceMapper;
    private final AntpoolValuationService valuationService;
    private final MarketDataService marketDataService;
    private final UserMapper userMapper;
    private final XmrWalletSettlementService walletSettlementService;
    private final AntpoolSyncStatus syncStatus;
    private final Long unclaimedUserId;
    private final boolean enabled;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public AntpoolPayhashHourlySettlementService(AntpoolProperties properties,
                                                 AntpoolPayhashWindowScoreService payhashWindowScoreService,
                                                 AntpoolPayhashHourlySettlementMapper settlementMapper,
                                                 AntpoolAccountBalanceMapper accountBalanceMapper,
                                                 AntpoolValuationService valuationService,
                                                 MarketDataService marketDataService,
                                                 UserMapper userMapper,
                                                 XmrWalletSettlementService walletSettlementService,
                                                 AntpoolSyncStatus syncStatus,
                                                 @Value("${app.settlement.unclaimed-user-id:1}") long unclaimedUserId,
                                                 @Value("${app.antpool.hourly-settlement.enabled:false}") boolean enabled) {
        this.properties = properties;
        this.payhashWindowScoreService = payhashWindowScoreService;
        this.settlementMapper = settlementMapper;
        this.accountBalanceMapper = accountBalanceMapper;
        this.valuationService = valuationService;
        this.marketDataService = marketDataService;
        this.userMapper = userMapper;
        this.walletSettlementService = walletSettlementService;
        this.syncStatus = syncStatus;
        this.unclaimedUserId = unclaimedUserId > 0 ? unclaimedUserId : null;
        this.enabled = enabled;
    }

    @Scheduled(cron = "${app.antpool.hourly-settlement.cron:0 5 * * * ?}", zone = "Asia/Shanghai")
    public void settleHourlyByPayhash() {
        if (!enabled || properties == null || !properties.isEnabled()) {
            return;
        }
        String account = properties.getUserId();
        String coin = properties.getCoin();
        if (!StringUtils.hasText(account) || !StringUtils.hasText(coin)) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            LocalDateTime now = LocalDateTime.now(BJT);
            LocalDateTime windowEnd = truncateToHour(now);
            LocalDateTime windowStart = windowEnd.minusHours(1);
            settleAccountHour(account.trim(), coin.trim(), windowStart, windowEnd);
        } finally {
            running.set(false);
        }
    }

    private void settleAccountHour(String account, String coin, LocalDateTime windowStart, LocalDateTime windowEnd) {
        if (!StringUtils.hasText(account) || !StringUtils.hasText(coin)) {
            return;
        }
        if (settlementMapper.selectByAccountAndWindowStart(account, coin, windowStart).isPresent()) {
            return;
        }
        int snapshotCount = payhashWindowScoreService.countSnapshotBuckets(windowStart, windowEnd);
        if (snapshotCount > 0 && snapshotCount < SNAPSHOT_EXPECTED_SAMPLES) {
            log.warn("Antpool hourly payhash settlement: insufficient snapshots (account={}, coin={}, windowStart={}, windowEnd={}, count={}, expected={}, intervalMinutes={})",
                    account, coin, windowStart, windowEnd, snapshotCount, SNAPSHOT_EXPECTED_SAMPLES, SNAPSHOT_INTERVAL_MINUTES);
        }
        List<WorkerPayhashScore> workerScores = payhashWindowScoreService.aggregate(windowStart, windowEnd);
        if (workerScores == null) {
            workerScores = Collections.emptyList();
        }
        int workerScoreCount = workerScores.size();
        String payhashReason = null;
        Map<Long, BigDecimal> userScores = Collections.emptyMap();
        BigDecimal totalScore = BigDecimal.ZERO;
        if (CollectionUtils.isEmpty(workerScores)) {
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
        BigDecimal totalCoinHour = resolveHourlyCoinFromAccountBalance(account, coin, windowStart, windowEnd);
        boolean incrementUsed = totalCoinHour.compareTo(BigDecimal.ZERO) > 0;
        AntpoolSyncStatus.SyncStatus lastSync = syncStatus != null ? syncStatus.getLastWorkerSync() : null;
        if (!incrementUsed) {
            String remark = payhashReason == null ? REMARK_NO_INCREMENT : REMARK_NO_INCREMENT + ":" + payhashReason;
            log.info("Antpool hourly settlement: no account increment, skip settlement (account={}, coin={}, windowStart={}, windowEnd={}, payhashReason={}, remark={}, snapshotCount={}, workerScores={}, totalPayhash={}, totalCoin={}, lastWorkerSyncStatus={}, lastWorkerSyncAt={}, lastWorkerSyncDetail={})",
                    account, coin, windowStart, windowEnd, payhashReason, remark, snapshotCount, workerScoreCount, totalScore, totalCoinHour,
                    lastSync != null ? lastSync.status() : null,
                    lastSync != null ? lastSync.at() : null,
                    lastSync != null ? lastSync.detail() : null);
            insertAdminPlaceholder(account, coin, windowStart, windowEnd, remark);
            return;
        }
        if (payhashReason != null) {
            String remark = REMARK_PAYHASH_UNAVAILABLE + ":" + payhashReason;
            log.info("Antpool hourly settlement: payhash unavailable, allocate increment to admin (account={}, coin={}, windowStart={}, windowEnd={}, reason={}, totalCoin={}, remark={}, snapshotCount={}, workerScores={}, totalPayhash={}, lastWorkerSyncStatus={}, lastWorkerSyncAt={}, lastWorkerSyncDetail={})",
                    account, coin, windowStart, windowEnd, payhashReason, totalCoinHour, remark, snapshotCount, workerScoreCount, totalScore,
                    lastSync != null ? lastSync.status() : null,
                    lastSync != null ? lastSync.at() : null,
                    lastSync != null ? lastSync.detail() : null);
            settleAdminOnlyByCoin(account, coin, windowStart, windowEnd, totalCoinHour, remark);
            return;
        }

        AntpoolValuationService.RateSnapshot rateSnapshot = valuationService.resolveRate(coin);
        if (rateSnapshot == null || rateSnapshot.coinToCalRate() == null
                || rateSnapshot.coinToCalRate().compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("Antpool hourly payhash settlement skipped: coin->CAL rate unavailable (account={}, coin={}, windowStart={})",
                    account, coin, windowStart);
            return;
        }
        BigDecimal calXmrRatio = marketDataService.getCalXmrRatio();
        if (calXmrRatio == null || calXmrRatio.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("Antpool hourly payhash settlement skipped: CAL/XMR ratio unavailable (account={}, coin={}, windowStart={})",
                    account, coin, windowStart);
            return;
        }

        BigDecimal totalCalHour = totalCoinHour.multiply(rateSnapshot.coinToCalRate())
                .setScale(CAL_SCALE, RoundingMode.HALF_UP);
        BigDecimal totalXmrHour = totalCalHour.multiply(calXmrRatio)
                .setScale(XMR_SCALE, RoundingMode.HALF_UP);
        log.info("Antpool hourly settlement: using account balance total (account={}, coin={}, windowStart={}, windowEnd={}, totalCoin={})",
                account, coin, windowStart, windowEnd, totalCoinHour);
        if (totalXmrHour.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("Antpool hourly payhash settlement skipped: hourly XMR amount is zero (account={}, coin={}, windowStart={})",
                    account, coin, windowStart);
            return;
        }
        Map<Long, BigDecimal> shareMap = allocateByRatio(totalXmrHour, totalScore, userScores);
        if (shareMap.isEmpty()) {
            log.warn("Antpool hourly payhash settlement skipped: empty share map (account={}, coin={}, windowStart={})",
                    account, coin, windowStart);
            return;
        }

        String gpuEarningTypeOverride = resolveGpuEarningTypeOverrideByCoin(coin);
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

        AntpoolPayhashHourlySettlement record = new AntpoolPayhashHourlySettlement();
        record.setAccount(account);
        record.setCoin(coin);
        record.setWindowStart(windowStart);
        record.setWindowEnd(windowEnd);
        record.setTotalCoin(totalCoinHour);
        record.setTotalCal(totalCalHour);
        record.setTotalXmr(totalXmrHour);
        record.setRateSource(rateSnapshot.source());
        record.setAllocationSource(ALLOCATION_SOURCE_POOL);
        record.setFallbackReason(AMOUNT_SOURCE_ACCOUNT_BALANCE);
        record.setCpuTotalCal(cpuTotalCal.setScale(CAL_SCALE, RoundingMode.HALF_UP));
        record.setGpuCfxTotalCal(gpuCfxTotalCal.setScale(CAL_SCALE, RoundingMode.HALF_UP));
        record.setGpuRvnTotalCal(gpuRvnTotalCal.setScale(CAL_SCALE, RoundingMode.HALF_UP));
        record.setStatus("PROCESSING");
        record.setCreatedTime(LocalDateTime.now(BJT));
        record.setUpdatedTime(LocalDateTime.now(BJT));
        int inserted = settlementMapper.insertIgnore(record);
        if (inserted == 0 || record.getId() == null) {
            return;
        }

        String txHash = buildTxHash(account, coin, windowStart);
        try {
            for (Map.Entry<Long, BigDecimal> entry : shareMap.entrySet()) {
                walletSettlementService.distributeXmrShare(entry.getKey(), entry.getValue(), txHash, windowStart,
                        gpuEarningTypeOverride, forceGpuWhenOverride);
            }
            settlementMapper.updateStatus(record.getId(), "SUCCESS", LocalDateTime.now(BJT));
        } catch (Exception ex) {
            settlementMapper.updateStatus(record.getId(), "FAILED", LocalDateTime.now(BJT));
            log.error("Antpool hourly payhash settlement failed (account={}, coin={}, windowStart={}): {}",
                    account, coin, windowStart, ex.getMessage());
        }
    }

    private BigDecimal resolveHourlyCoinFromAccountBalance(String account,
                                                           String coin,
                                                           LocalDateTime windowStart,
                                                           LocalDateTime windowEnd) {
        if (!StringUtils.hasText(account) || !StringUtils.hasText(coin)
                || windowStart == null || windowEnd == null) {
            return BigDecimal.ZERO;
        }
        Optional<AntpoolAccountBalance> endOpt = accountBalanceMapper
                .selectLatestBefore(account, coin, windowEnd);
        Optional<AntpoolAccountBalance> startOpt = accountBalanceMapper
                .selectLatestBefore(account, coin, windowStart);
        if (endOpt.isEmpty() || startOpt.isEmpty()) {
            log.info("Antpool hourly settlement: missing account balance snapshots (account={}, coin={}, windowStart={}, windowEnd={}, hasStart={}, hasEnd={})",
                    account, coin, windowStart, windowEnd, startOpt.isPresent(), endOpt.isPresent());
            return BigDecimal.ZERO;
        }
        LocalDateTime endFetchedAt = endOpt.get().getFetchedAt();
        LocalDateTime startFetchedAt = startOpt.get().getFetchedAt();
        if (endFetchedAt == null || startFetchedAt == null) {
            log.info("Antpool hourly settlement: account balance snapshot timestamps missing (account={}, coin={}, windowStart={}, windowEnd={}, startAt={}, endAt={})",
                    account, coin, windowStart, windowEnd, startFetchedAt, endFetchedAt);
            return BigDecimal.ZERO;
        }
        BigDecimal endValue = safe(endOpt.get().getEarnTotal());
        BigDecimal startValue = safe(startOpt.get().getEarnTotal());
        BigDecimal delta = endValue.subtract(startValue);
        if (delta.compareTo(BigDecimal.ZERO) <= 0) {
            log.info("Antpool hourly settlement: earnTotal delta not positive (account={}, coin={}, windowStart={}, start={}, end={}, delta={})",
                    account, coin, windowStart, startValue, endValue, delta);
            return BigDecimal.ZERO;
        }
        log.info("Antpool hourly settlement: earnTotal delta applied (account={}, coin={}, windowStart={}, windowEnd={}, start={}, end={}, delta={})",
                account, coin, windowStart, windowEnd, startValue, endValue, delta);
        return delta;
    }


    private void settleAdminOnlyByCoin(String account,
                                       String coin,
                                       LocalDateTime windowStart,
                                       LocalDateTime windowEnd,
                                       BigDecimal totalCoinHour,
                                       String remark) {
        if (!StringUtils.hasText(account) || !StringUtils.hasText(coin)) {
            return;
        }
        if (totalCoinHour == null || totalCoinHour.compareTo(BigDecimal.ZERO) <= 0) {
            insertAdminPlaceholder(account, coin, windowStart, windowEnd, remark);
            return;
        }
        if (unclaimedUserId == null) {
            log.warn("Antpool hourly settlement admin hold skipped: admin user not configured (account={}, coin={}, windowStart={})",
                    account, coin, windowStart);
            insertAdminPlaceholder(account, coin, windowStart, windowEnd, remark);
            return;
        }
        AntpoolValuationService.RateSnapshot rateSnapshot = valuationService.resolveRate(coin);
        if (rateSnapshot == null || rateSnapshot.coinToCalRate() == null
                || rateSnapshot.coinToCalRate().compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("Antpool hourly settlement admin hold skipped: coin->CAL rate unavailable (account={}, coin={}, windowStart={})",
                    account, coin, windowStart);
            insertAdminPlaceholder(account, coin, windowStart, windowEnd, remark);
            return;
        }
        BigDecimal totalCalHour = totalCoinHour.multiply(rateSnapshot.coinToCalRate())
                .setScale(CAL_SCALE, RoundingMode.HALF_UP);
        BigDecimal calXmrRatio = marketDataService.getCalXmrRatio();
        if (calXmrRatio == null || calXmrRatio.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("Antpool hourly settlement admin hold skipped: CAL/XMR ratio unavailable (account={}, coin={}, windowStart={})",
                    account, coin, windowStart);
            insertAdminPlaceholder(account, coin, windowStart, windowEnd, remark);
            return;
        }
        BigDecimal totalXmrHour = totalCalHour.multiply(calXmrRatio).setScale(XMR_SCALE, RoundingMode.HALF_UP);
        if (totalXmrHour.compareTo(BigDecimal.ZERO) <= 0) {
            insertAdminPlaceholder(account, coin, windowStart, windowEnd, remark);
            return;
        }
        recordAdminSettlement(account, coin, windowStart, windowEnd, totalCoinHour, totalCalHour, totalXmrHour,
                rateSnapshot.source(), remark);
    }

    private void recordAdminSettlement(String account,
                                       String coin,
                                       LocalDateTime windowStart,
                                       LocalDateTime windowEnd,
                                       BigDecimal totalCoinHour,
                                       BigDecimal totalCalHour,
                                       BigDecimal totalXmrHour,
                                       String rateSource,
                                       String remark) {
        if (totalXmrHour == null || totalXmrHour.compareTo(BigDecimal.ZERO) <= 0) {
            insertAdminPlaceholder(account, coin, windowStart, windowEnd, remark);
            return;
        }
        String gpuEarningTypeOverride = resolveGpuEarningTypeOverrideByCoin(coin);
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
        AntpoolPayhashHourlySettlement record = new AntpoolPayhashHourlySettlement();
        record.setAccount(account);
        record.setCoin(coin);
        record.setWindowStart(windowStart);
        record.setWindowEnd(windowEnd);
        record.setTotalCoin(totalCoinHour);
        record.setTotalCal(totalCalHour);
        record.setTotalXmr(totalXmrHour);
        record.setRateSource(rateSource);
        record.setAllocationSource(ALLOCATION_SOURCE_ADMIN);
        record.setFallbackReason(null);
        record.setRemark(remark);
        record.setCpuTotalCal(cpuTotalCal.setScale(CAL_SCALE, RoundingMode.HALF_UP));
        record.setGpuCfxTotalCal(gpuCfxTotalCal.setScale(CAL_SCALE, RoundingMode.HALF_UP));
        record.setGpuRvnTotalCal(gpuRvnTotalCal.setScale(CAL_SCALE, RoundingMode.HALF_UP));
        record.setStatus("PROCESSING");
        record.setCreatedTime(LocalDateTime.now(BJT));
        record.setUpdatedTime(LocalDateTime.now(BJT));
        int inserted = settlementMapper.insertIgnore(record);
        if (inserted == 0 || record.getId() == null) {
            return;
        }
        String txHash = buildTxHash(account, coin, windowStart);
        try {
            walletSettlementService.distributeXmrShare(unclaimedUserId, totalXmrHour, txHash, windowStart,
                    gpuEarningTypeOverride, forceGpuWhenOverride);
            settlementMapper.updateStatus(record.getId(), "SUCCESS", LocalDateTime.now(BJT));
        } catch (Exception ex) {
            settlementMapper.updateStatus(record.getId(), "FAILED", LocalDateTime.now(BJT));
            log.error("Antpool hourly settlement admin hold failed (account={}, coin={}, windowStart={}): {}",
                    account, coin, windowStart, ex.getMessage());
        }
    }

    private void insertAdminPlaceholder(String account,
                                        String coin,
                                        LocalDateTime windowStart,
                                        LocalDateTime windowEnd,
                                        String remark) {
        if (!StringUtils.hasText(account) || !StringUtils.hasText(coin)) {
            return;
        }
        if (!StringUtils.hasText(remark)) {
            return;
        }
        AntpoolPayhashHourlySettlement record = new AntpoolPayhashHourlySettlement();
        record.setAccount(account);
        record.setCoin(coin);
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
        settlementMapper.insertIgnore(record);
    }

    private String resolveGpuEarningTypeOverrideByCoin(String coin) {
        if (!StringUtils.hasText(coin)) {
            return null;
        }
        String normalized = coin.trim().toUpperCase(Locale.ROOT);
        if ("RAVENCOIN".equals(normalized) || "RVN".equals(normalized) || "KAWPOW".equals(normalized)) {
            return "GPU_KAWPOW";
        }
        if ("CONFLUX".equals(normalized) || "CONFLUXTOKEN".equals(normalized) || "CFX".equals(normalized)) {
            return "GPU_OCTOPUS";
        }
        return null;
    }

    private BigDecimal safe(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
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
        if (reward == null || reward.compareTo(BigDecimal.ZERO) <= 0
                || totalScore == null || totalScore.compareTo(BigDecimal.ZERO) <= 0
                || userScores == null || userScores.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Map.Entry<Long, BigDecimal>> entries = new ArrayList<>(userScores.entrySet());
        entries.sort((a, b) -> b.getValue().compareTo(a.getValue()));
        Map<Long, BigDecimal> shareMap = new HashMap<>();
        BigDecimal remaining = reward;
        for (Map.Entry<Long, BigDecimal> entry : entries) {
            if (entry.getKey() == null || entry.getValue() == null || entry.getValue().compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
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

    private String buildTxHash(String account, String coin, LocalDateTime windowStart) {
        return "antpool:payhash-hourly:" + coin.toLowerCase(Locale.ROOT)
                + ":" + account + ":" + windowStart;
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
