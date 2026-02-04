package com.slb.mining_backend.modules.xmr.service;

import com.slb.mining_backend.modules.asset.service.AssetLedgerService;
import com.slb.mining_backend.modules.earnings.entity.EarningsHistory;
import com.slb.mining_backend.modules.earnings.mapper.EarningsHistoryMapper;
import com.slb.mining_backend.modules.earnings.service.MarketDataService;
import com.slb.mining_backend.modules.exchange.service.ExchangeRateService;
import com.slb.mining_backend.modules.invite.config.InviteProperties;
import com.slb.mining_backend.modules.invite.entity.PlatformCommission;
import com.slb.mining_backend.modules.invite.mapper.PlatformCommissionMapper;
import com.slb.mining_backend.modules.invite.mapper.CommissionRecordMapper;
import com.slb.mining_backend.modules.invite.service.InviteService;
import com.slb.mining_backend.modules.device.mapper.DeviceGpuHashrateReportMapper;
import com.slb.mining_backend.modules.device.mapper.DeviceMapper;
import com.slb.mining_backend.modules.device.vo.GpuAlgorithmHashrateVo;
import com.slb.mining_backend.modules.device.vo.UserHashrateSummaryVo;
import com.slb.mining_backend.modules.users.dto.WorkerUserBinding;
import com.slb.mining_backend.modules.users.entity.User;
import com.slb.mining_backend.modules.users.enums.SettlementCurrency;
import com.slb.mining_backend.modules.users.mapper.UserMapper;
import com.slb.mining_backend.modules.xmr.config.F2PoolProperties;
import com.slb.mining_backend.modules.xmr.dto.WorkerPayhashScore;
import com.slb.mining_backend.modules.xmr.entity.XmrWalletIncoming;
import com.slb.mining_backend.modules.xmr.mapper.XmrWalletIncomingMapper;
import com.slb.mining_backend.modules.xmr.service.antpool.AntpoolPayhashWindowScoreService;
import com.slb.mining_backend.modules.xmr.service.f2pool.F2PoolPayhashWindowScoreService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@Slf4j
public class XmrWalletSettlementService {

    private static final BigDecimal USER_RATE = new BigDecimal("0.7");
    private static final BigDecimal PLATFORM_RATE = BigDecimal.ONE.subtract(USER_RATE);
    private static final ZoneId BJT = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    // 兼容 asset_ledger.ref_type 的 ENUM：使用 bonus，再用 remark 前缀区分子类型
    private static final String LEDGER_REF_TYPE_BONUS = "bonus";
    private static final String LEDGER_REMARK_INVITEE_DISCOUNT = "invitee_discount:";
    private static final String LEDGER_REMARK_INVITEE_ACTIVATION = "invitee_activation:";
    private static final String LEDGER_REMARK_UNCLAIMED_HASHRATE = "unclaimed_hashrate";
    private static final String LEDGER_REMARK_UNCLAIMED_MISSING_PAYHASH = "unclaimed_missing_payhash";
    private static final String LEDGER_REMARK_PAYHASH_ADJUSTMENT = "payhash_adjustment";
    private static final String LEDGER_REMARK_PAYHASH_ADJUSTMENT_PLATFORM = "payhash_adjustment_platform";
    private static final String SUBADDRESS_PREFIX_F2POOL = "f2pool:";
    private static final String SUBADDRESS_PREFIX_ANTPOOL = "antpool:";
    private static final String SUBADDRESS_PREFIX_C3POOL = "c3pool:";
    private static final int DEFAULT_PAYHASH_ALERT_THROTTLE_MINUTES = 10;

    private final XmrWalletIncomingMapper walletIncomingMapper;
    private final PayhashWindowScoreService payhashWindowScoreService;
    private final AntpoolPayhashWindowScoreService antpoolPayhashWindowScoreService;
    private final F2PoolPayhashWindowScoreService f2poolPayhashWindowScoreService;
    private final F2PoolProperties f2poolProperties;
    private final UserMapper userMapper;
    private final ExchangeRateService exchangeRateService;
    private final MarketDataService marketDataService;
    private final AssetLedgerService assetLedgerService;
    private final EarningsHistoryMapper earningsHistoryMapper;
    private final com.slb.mining_backend.modules.earnings.mapper.EarningsMapper earningsMapper; // Added
    private final DeviceMapper deviceMapper;
    private final DeviceGpuHashrateReportMapper deviceGpuHashrateReportMapper;
    private final PlatformCommissionMapper platformCommissionMapper;
    private final CommissionRecordMapper commissionRecordMapper;
    private final InviteService inviteService;
    private final InviteProperties inviteProperties;
    private final TransactionTemplate transactionTemplate;
    private final long adminUserId;
    private final int batchSize;
    private final int maxItemsPerRun;
    private final long maxRunMs;
    private final boolean f2poolHourlySettlementEnabled;
    private final boolean antpoolHourlySettlementEnabled;
    private final MissingPayhashPolicy missingPayhashPolicy;
    private final int missingPayhashRetryMinutes;
    private final Map<String, LocalDateTime> payhashRetryAfter = new ConcurrentHashMap<>();
    private final Long unclaimedUserId;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Map<String, LocalDateTime> payhashAlertLastLog = new ConcurrentHashMap<>();

    @Value("${app.devices.offline-threshold-minutes:5}")
    private long deviceOfflineThresholdMinutes;
    @Value("${app.settlement.payhash-alert-throttle-minutes:10}")
    private int payhashAlertThrottleMinutes;

    private static final int XMR_SCALE = 12;
    private static final int CAL_SCALE = 8;
    // CNY 金额统一保留 4 位小数
    private static final int CNY_SCALE = 4;

    private enum MissingPayhashPolicy {
        FALLBACK_ADMIN,
        FALLBACK_UNCLAIMED,
        SKIP;

        static MissingPayhashPolicy from(String raw) {
            if (raw == null) {
                return FALLBACK_ADMIN;
            }
            String normalized = raw.trim().toUpperCase(Locale.ROOT);
            for (MissingPayhashPolicy v : values()) {
                if (v.name().equals(normalized)) {
                    return v;
                }
            }
            return FALLBACK_ADMIN;
        }
    }

    private enum PayhashSource {
        DEFAULT,
        C3POOL,
        ANTPOOL,
        F2POOL
    }

    private record PayhashSnapshot(BigDecimal totalScore, Map<Long, BigDecimal> userScores) {
        private static PayhashSnapshot empty() {
            return new PayhashSnapshot(BigDecimal.ZERO, Collections.emptyMap());
        }
    }

    private record DeviceFallbackScores(Map<Long, BigDecimal> userScores, BigDecimal totalScore) {
    }

    private record PayhashKey(PayhashSource source, String account, String coin) {
    }

    private record EarningSplitContext(String gpuEarningTypeOverride,
                                       boolean forceGpuWhenOverride,
                                       boolean forceCpuOnly) {
    }

    private record MappingStats(int totalWorkers,
                                int mappedWorkers,
                                int unmappedWorkers,
                                BigDecimal totalPayhash,
                                BigDecimal unmappedPayhash) {
    }

    public XmrWalletSettlementService(XmrWalletIncomingMapper walletIncomingMapper,
                                      PayhashWindowScoreService payhashWindowScoreService,
                                      AntpoolPayhashWindowScoreService antpoolPayhashWindowScoreService,
                                      F2PoolPayhashWindowScoreService f2poolPayhashWindowScoreService,
                                      F2PoolProperties f2poolProperties,
                                      UserMapper userMapper,
                                      ExchangeRateService exchangeRateService,
                                      MarketDataService marketDataService,
                                      AssetLedgerService assetLedgerService,
                                      EarningsHistoryMapper earningsHistoryMapper,
                                      com.slb.mining_backend.modules.earnings.mapper.EarningsMapper earningsMapper, // Added
                                      DeviceMapper deviceMapper,
                                      DeviceGpuHashrateReportMapper deviceGpuHashrateReportMapper,
                                      PlatformCommissionMapper platformCommissionMapper,
                                      CommissionRecordMapper commissionRecordMapper,
                                      InviteService inviteService,
                                      InviteProperties inviteProperties,
                                      TransactionTemplate transactionTemplate,
                                      @Value("${app.settlement.admin-user-id:1}") long adminUserId,
                                      @Value("${app.settlement.batch-size:300}") int batchSize,
                                      @Value("${app.settlement.max-items-per-run:5000}") int maxItemsPerRun,
                                      @Value("${app.settlement.max-run-ms:600000}") long maxRunMs,
                                      @Value("${app.f2pool.hourly-settlement.enabled:false}") boolean f2poolHourlySettlementEnabled,
                                      @Value("${app.antpool.hourly-settlement.enabled:false}") boolean antpoolHourlySettlementEnabled,
                                      @Value("${app.settlement.missing-payhash-policy:FALLBACK_ADMIN}") String missingPayhashPolicy,
                                      @Value("${app.settlement.missing-payhash-retry-minutes:60}") int missingPayhashRetryMinutes,
                                      @Value("${app.settlement.unclaimed-user-id:1}") long unclaimedUserId) {
        this.walletIncomingMapper = walletIncomingMapper;
        this.payhashWindowScoreService = payhashWindowScoreService;
        this.antpoolPayhashWindowScoreService = antpoolPayhashWindowScoreService;
        this.f2poolPayhashWindowScoreService = f2poolPayhashWindowScoreService;
        this.f2poolProperties = f2poolProperties;
        this.userMapper = userMapper;
        this.exchangeRateService = exchangeRateService;
        this.marketDataService = marketDataService;
        this.assetLedgerService = assetLedgerService;
        this.earningsHistoryMapper = earningsHistoryMapper;
        this.earningsMapper = earningsMapper; // Added
        this.deviceMapper = deviceMapper;
        this.deviceGpuHashrateReportMapper = deviceGpuHashrateReportMapper;
        this.platformCommissionMapper = platformCommissionMapper;
        this.commissionRecordMapper = commissionRecordMapper;
        this.inviteService = inviteService;
        this.inviteProperties = inviteProperties;
        this.transactionTemplate = transactionTemplate;
        this.adminUserId = adminUserId;
        this.batchSize = batchSize;
        this.maxItemsPerRun = Math.max(1, maxItemsPerRun);
        this.maxRunMs = Math.max(1L, maxRunMs);
        this.f2poolHourlySettlementEnabled = f2poolHourlySettlementEnabled;
        this.antpoolHourlySettlementEnabled = antpoolHourlySettlementEnabled;
        this.missingPayhashPolicy = MissingPayhashPolicy.from(missingPayhashPolicy);
        this.missingPayhashRetryMinutes = Math.max(1, missingPayhashRetryMinutes);
        this.unclaimedUserId = unclaimedUserId > 0 ? unclaimedUserId : null;
    }

    /**
     * 按小时整点结算：只处理“上一小时 [hourStart, hourEnd) 内入账”的记录。
     *
     * 说明：
     * - 以 xmr_wallet_incoming.ts（到账时间）为准分桶；
     * - 本轮只结算上一小时的入账，并将 earnings_history.earning_time 写为该入账 ts，便于前端按“到账时间小时”展示；
     * - 受预算限制（maxItemsPerRun/maxRunMs），若上一小时入账量极大可能需要下一轮继续处理（极端场景）。
     */
    @Scheduled(cron = "${app.settlement.job-cron:0 0 * * * ?}", zone = "Asia/Shanghai")
    public void settleWalletIncomingHourly() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            long startedAt = System.currentTimeMillis();
            int processed = 0;

            LocalDateTime now = LocalDateTime.now(BJT);
            LocalDateTime currentHourStart = truncateToHour(now);
            BigDecimal xmrToCny = exchangeRateService.getXmrToCnyRate();
            BigDecimal calToCny = marketDataService.getCalToCnyRate();

            // 全局去重（历史可能存在重复 tx_hash 行）
            Set<String> seenTx = new HashSet<>();

            // 关键：优先处理“最早的未结算小时窗口”，避免当某个小时因缺 payhash 被 SKIP 后永远无法被后续整点任务捞到。
            while (processed < maxItemsPerRun && !isTimeBudgetExceeded(startedAt)) {
                HourWindow window = resolveNextUnsettledHourWindow(currentHourStart);
                if (window == null) {
                    break;
                }

                Map<PayhashKey, PayhashSnapshot> payhashCache = new HashMap<>();
                Map<Long, String> algorithmCache = new HashMap<>();
                boolean anySettledInWindow = false;
                while (processed < maxItemsPerRun && !isTimeBudgetExceeded(startedAt)) {
                    List<XmrWalletIncoming> records = walletIncomingMapper.selectUnsettledInRange(window.start, window.end, batchSize);
                    if (records == null || records.isEmpty()) {
                        break;
                    }
                    boolean anyAttempted = false;
                    for (XmrWalletIncoming income : records) {
                        if (processed >= maxItemsPerRun || isTimeBudgetExceeded(startedAt)) {
                            break;
                        }
                        if (income == null || !StringUtils.hasText(income.getTxHash())) {
                            continue;
                        }
                        if (!seenTx.add(income.getTxHash())) {
                            continue;
                        }
                        LocalDateTime retryAt = payhashRetryAfter.get(income.getTxHash());
                        if (retryAt != null && retryAt.isAfter(now)) {
                            continue;
                        }
                        anyAttempted = true;
                        try {
                            PayhashSnapshot snapshot = resolvePayhashSnapshot(income, window.start, window.end, payhashCache, algorithmCache);
                            Boolean settled = transactionTemplate.execute(status ->
                                    processIncomeInHourWindow(income, window.start, window.end, snapshot, xmrToCny, calToCny, algorithmCache));
                            if (Boolean.TRUE.equals(settled)) {
                                processed++;
                                anySettledInWindow = true;
                            }
                        } catch (Exception ex) {
                            log.warn("Failed to settle wallet income {}: {}", income.getTxHash(), ex.getMessage());
                        }
                    }
                    // 如果本批次完全没有尝试（都在冷却期），避免 while 热循环
                    if (!anyAttempted) {
                        break;
                    }
                }

                // 若该窗口没有任何记录被结算（可能 payhash 缺失导致全部 SKIP），避免在同一轮任务里反复卡在这个窗口
                if (!anySettledInWindow) {
                    break;
                }
            }

            long cost = System.currentTimeMillis() - startedAt;
            log.info("Hourly settlement finished (processed={}, costMs={})", processed, cost);
        } finally {
            running.set(false);
        }
    }

    private static final class HourWindow {
        private final LocalDateTime start;
        private final LocalDateTime end;

        private HourWindow(LocalDateTime start, LocalDateTime end) {
            this.start = start;
            this.end = end;
        }
    }

    /**
     * 找到下一段需要结算的“完整小时窗口”（start/end），优先选取最早的未结算记录所属小时。
     * @param currentHourStart 当前小时起点（不结算未完成的当前小时）
     */
    private HourWindow resolveNextUnsettledHourWindow(LocalDateTime currentHourStart) {
        // 只看 ts 非空的记录，避免异常数据（ts=NULL）把整点任务“窗口推进”卡死
        List<XmrWalletIncoming> oldest = walletIncomingMapper.selectOldestUnsettledWithTs(1);
        if (oldest == null || oldest.isEmpty()) {
            return null;
        }
        XmrWalletIncoming first = oldest.get(0);
        if (first == null || first.getTs() == null) {
            return null;
        }
        LocalDateTime start = truncateToHour(first.getTs());
        LocalDateTime end = start.plusHours(1);
        // 不处理当前未完成小时
        if (currentHourStart != null && end.isAfter(currentHourStart)) {
            return null;
        }
        return new HourWindow(start, end);
    }

    private boolean isTimeBudgetExceeded(long startedAt) {
        return (System.currentTimeMillis() - startedAt) >= maxRunMs;
    }

    private LocalDateTime truncateToHour(LocalDateTime time) {
        if (time == null) {
            return null;
        }
        return time.withMinute(0).withSecond(0).withNano(0);
    }

    private PayhashSnapshot resolvePayhashSnapshot(XmrWalletIncoming income,
                                                   LocalDateTime windowStart,
                                                   LocalDateTime windowEnd,
                                                   Map<PayhashKey, PayhashSnapshot> cache,
                                                   Map<Long, String> algorithmCache) {
        PayhashKey key = resolvePayhashKey(income, algorithmCache);
        if (key == null) {
            return PayhashSnapshot.empty();
        }
        if (cache == null) {
            return loadPayhashSnapshot(key, windowStart, windowEnd);
        }
        return cache.computeIfAbsent(key, k -> loadPayhashSnapshot(k, windowStart, windowEnd));
    }

    private PayhashSnapshot loadPayhashSnapshot(PayhashKey key,
                                                LocalDateTime windowStart,
                                                LocalDateTime windowEnd) {
        if (windowStart == null || windowEnd == null || !windowStart.isBefore(windowEnd)) {
            return PayhashSnapshot.empty();
        }
        List<WorkerPayhashScore> workerScores;
        if (key.source() == PayhashSource.F2POOL) {
            if (!StringUtils.hasText(key.account()) || !StringUtils.hasText(key.coin())) {
                return PayhashSnapshot.empty();
            }
            workerScores = f2poolPayhashWindowScoreService.aggregate(key.account(), key.coin(), windowStart, windowEnd);
            if (workerScores == null || workerScores.isEmpty()) {
                logWarnThrottled("f2pool-empty:" + key.account() + ":" + key.coin(),
                        "F2Pool payhash empty (account={}, coin={}, windowStart={}, windowEnd={}). Possible causes: unsupported coin, stale workers, or API fallback returned 0.",
                        key.account(), key.coin(), windowStart, windowEnd);
            }
        } else if (key.source() == PayhashSource.ANTPOOL) {
            workerScores = antpoolPayhashWindowScoreService.aggregate(windowStart, windowEnd);
        } else {
            workerScores = payhashWindowScoreService.aggregate(windowStart, windowEnd);
        }
        if (workerScores == null || workerScores.isEmpty()) {
            return PayhashSnapshot.empty();
        }
        Map<String, Long> owners = resolveWorkerOwners(workerScores);
        MappingStats mappingStats = computeMappingStats(workerScores, owners);
        BigDecimal totalScore = mappingStats.totalPayhash();
        Map<Long, BigDecimal> userScores = collapseToUsers(workerScores, owners);
        if (mappingStats.unmappedWorkers() > 0 && totalScore.compareTo(BigDecimal.ZERO) > 0) {
            log.info("METRIC payhash_mapping_missing source={} account={} coin={} windowStart={} windowEnd={} totalWorkers={} unmappedWorkers={} totalPayhash={} unmappedPayhash={}",
                    key.source(),
                    StringUtils.hasText(key.account()) ? key.account() : "NA",
                    StringUtils.hasText(key.coin()) ? key.coin() : "NA",
                    windowStart,
                    windowEnd,
                    mappingStats.totalWorkers(),
                    mappingStats.unmappedWorkers(),
                    totalScore,
                    mappingStats.unmappedPayhash());
        }
        if ((userScores == null || userScores.isEmpty()) && totalScore.compareTo(BigDecimal.ZERO) > 0) {
            logWarnThrottled("payhash-unmapped:" + key.source(),
                    "Payhash has data but no user mapping (source={}, account={}, coin={}, windowStart={}, windowEnd={})",
                    key.source(), key.account(), key.coin(), windowStart, windowEnd);
        }
        return new PayhashSnapshot(totalScore, userScores);
    }

    private PayhashKey resolvePayhashKey(XmrWalletIncoming income, Map<Long, String> algorithmCache) {
        if (income == null) {
            return null;
        }
        PayhashSource metaSource = resolvePayhashSourceFromMeta(income);
        String algorithm = resolveDominantGpuAlgorithm(income.getUserId(), algorithmCache);
        PayhashSource algoSource = resolvePayhashSourceByAlgorithm(algorithm);
        PayhashSource finalSource = metaSource != PayhashSource.DEFAULT
                ? metaSource
                : (algoSource != null ? algoSource : PayhashSource.DEFAULT);
        F2PoolAccountKey accountKey = finalSource == PayhashSource.F2POOL ? resolveF2PoolAccountKey(income) : null;
        String algoKey = normalizeAlgorithm(algorithm);
        if (!StringUtils.hasText(algoKey)) {
            algoKey = "NA";
        }
        String accountKeyValue = accountKey != null && StringUtils.hasText(accountKey.account())
                ? accountKey.account()
                : "NA";
        String coinKeyValue = accountKey != null && StringUtils.hasText(accountKey.coin())
                ? accountKey.coin()
                : "NA";
        if (metaSource != PayhashSource.DEFAULT && algoSource != null && metaSource != algoSource) {
            logWarnThrottled("payhash-mismatch:" + metaSource + ":" + algoSource + ":" + algoKey + ":" + accountKeyValue + ":" + coinKeyValue,
                    "Payhash pool mismatch: txHash={}, userId={}, metaSource={}, algoSource={}, algorithm={}, account={}, coin={}",
                    income.getTxHash(), income.getUserId(), metaSource, algoSource, algorithm, accountKeyValue, coinKeyValue);
        }
        if (metaSource == PayhashSource.DEFAULT && algoSource == null && income.getUserId() != null
                && !Objects.equals(income.getUserId(), adminUserId)) {
            logWarnThrottled("payhash-unresolved:" + income.getUserId(),
                    "Payhash pool unresolved by meta/algorithm (txHash={}, userId={})",
                    income.getTxHash(), income.getUserId());
        }
        if (finalSource == PayhashSource.F2POOL) {
            if (accountKey == null) {
                return new PayhashKey(finalSource, null, null);
            }
            return new PayhashKey(finalSource, accountKey.account(), accountKey.coin());
        }
        return new PayhashKey(finalSource, null, null);
    }

    private PayhashSource resolvePayhashSourceFromMeta(XmrWalletIncoming income) {
        if (income == null) {
            return PayhashSource.DEFAULT;
        }
        String subaddress = income.getSubaddress();
        String txHash = income.getTxHash();
        if (startsWithIgnoreCase(subaddress, SUBADDRESS_PREFIX_F2POOL)
                || startsWithIgnoreCase(txHash, SUBADDRESS_PREFIX_F2POOL)) {
            return PayhashSource.F2POOL;
        }
        if (startsWithIgnoreCase(subaddress, SUBADDRESS_PREFIX_ANTPOOL)
                || startsWithIgnoreCase(txHash, SUBADDRESS_PREFIX_ANTPOOL)) {
            return PayhashSource.ANTPOOL;
        }
        if (startsWithIgnoreCase(subaddress, SUBADDRESS_PREFIX_C3POOL)
                || startsWithIgnoreCase(txHash, SUBADDRESS_PREFIX_C3POOL)) {
            return PayhashSource.C3POOL;
        }
        return PayhashSource.DEFAULT;
    }

    private PayhashSource resolvePayhashSourceByAlgorithm(String algorithm) {
        if (isOctopusAlgorithm(algorithm)) {
            return PayhashSource.F2POOL;
        }
        if (isKawpowAlgorithm(algorithm)) {
            return PayhashSource.ANTPOOL;
        }
        return null;
    }

    private PayhashSource resolveFinalPayhashSource(XmrWalletIncoming income, Map<Long, String> algorithmCache) {
        PayhashSource metaSource = resolvePayhashSourceFromMeta(income);
        if (metaSource != PayhashSource.DEFAULT) {
            return metaSource;
        }
        String algorithm = resolveDominantGpuAlgorithm(income != null ? income.getUserId() : null, algorithmCache);
        PayhashSource algoSource = resolvePayhashSourceByAlgorithm(algorithm);
        return algoSource != null ? algoSource : PayhashSource.DEFAULT;
    }

    private boolean shouldSkipPoolPayout(XmrWalletIncoming income) {
        if (income == null) {
            return false;
        }
        PayhashSource source = resolvePayhashSourceFromMeta(income);
        if (source == PayhashSource.F2POOL) {
            return f2poolHourlySettlementEnabled;
        }
        if (source == PayhashSource.ANTPOOL) {
            return antpoolHourlySettlementEnabled;
        }
        return false;
    }

    private boolean isCpuOnlySource(PayhashSource source) {
        return source == PayhashSource.C3POOL || source == PayhashSource.DEFAULT;
    }

    private String resolveGpuEarningTypeOverrideBySource(PayhashSource source) {
        if (source == PayhashSource.F2POOL) {
            return "GPU_OCTOPUS";
        }
        if (source == PayhashSource.ANTPOOL) {
            return "GPU_KAWPOW";
        }
        return null;
    }

    private EarningSplitContext resolveEarningSplitContext(XmrWalletIncoming income, Map<Long, String> algorithmCache) {
        PayhashSource source = resolveFinalPayhashSource(income, algorithmCache);
        String gpuOverride = resolveGpuEarningTypeOverrideBySource(source);
        boolean forceGpuWhenOverride = StringUtils.hasText(gpuOverride);
        boolean forceCpuOnly = isCpuOnlySource(source);
        return new EarningSplitContext(gpuOverride, forceGpuWhenOverride, forceCpuOnly);
    }

    private String resolveDominantGpuAlgorithm(Long userId, Map<Long, String> cache) {
        if (userId == null) {
            return null;
        }
        if (cache != null && cache.containsKey(userId)) {
            return cache.get(userId);
        }
        LocalDateTime cutoffTime = LocalDateTime.now().minusMinutes(deviceOfflineThresholdMinutes);
        List<GpuAlgorithmHashrateVo> stats =
                deviceGpuHashrateReportMapper.sumLatestHashrateByAlgorithm(userId, cutoffTime);
        if (stats == null || stats.isEmpty()) {
            if (cache != null) {
                cache.put(userId, null);
            }
            return null;
        }
        GpuAlgorithmHashrateVo best = null;
        for (GpuAlgorithmHashrateVo item : stats) {
            if (item == null || item.getTotalHashrate() == null || item.getTotalHashrate().compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            if (best == null || item.getTotalHashrate().compareTo(best.getTotalHashrate()) > 0) {
                best = item;
            }
        }
        String algorithm = best != null ? best.getAlgorithm() : null;
        if (cache != null) {
            cache.put(userId, algorithm);
        }
        return algorithm;
    }

    private boolean isOctopusAlgorithm(String raw) {
        String normalized = normalizeAlgorithm(raw);
        return "OCTOPUS".equals(normalized) || "CFX".equals(normalized);
    }

    private boolean isKawpowAlgorithm(String raw) {
        String normalized = normalizeAlgorithm(raw);
        return "KAWPOW".equals(normalized) || "RVN".equals(normalized);
    }

    private record F2PoolAccountKey(String account, String coin) {
    }

    private F2PoolAccountKey resolveF2PoolAccountKey(XmrWalletIncoming income) {
        String subaddress = (income != null && StringUtils.hasText(income.getSubaddress()))
                ? income.getSubaddress().trim()
                : "";
        if (startsWithIgnoreCase(subaddress, SUBADDRESS_PREFIX_F2POOL)) {
            String[] parts = subaddress.split(":", 3);
            if (parts.length >= 3) {
                String coin = parts[1] != null ? parts[1].trim() : null;
                String account = parts[2] != null ? parts[2].trim() : null;
                if (StringUtils.hasText(coin) && StringUtils.hasText(account)) {
                    return new F2PoolAccountKey(account, coin);
                }
            }
        }
        List<F2PoolProperties.Account> accounts = f2poolProperties != null ? f2poolProperties.getAccounts() : null;
        if (accounts == null || accounts.isEmpty()) {
            return null;
        }
        if (accounts.size() == 1) {
            F2PoolProperties.Account account = accounts.get(0);
            if (account != null && StringUtils.hasText(account.getName()) && StringUtils.hasText(account.getCoin())) {
                return new F2PoolAccountKey(account.getName().trim(), account.getCoin().trim());
            }
            return null;
        }
        logErrorThrottled("f2pool-account-ambiguous",
                "F2Pool account ambiguous for txHash={}, subaddress={}; multiple accounts configured and no account info in income",
                income != null ? income.getTxHash() : null, subaddress);
        return null;
    }

    private boolean startsWithIgnoreCase(String value, String prefix) {
        if (!StringUtils.hasText(value) || !StringUtils.hasText(prefix)) {
            return false;
        }
        return value.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    private void logWarnThrottled(String key, String message, Object... args) {
        if (shouldLog(key)) {
            log.warn(message, args);
        }
    }

    private void logErrorThrottled(String key, String message, Object... args) {
        if (shouldLog(key)) {
            log.error(message, args);
        }
    }

    private boolean shouldLog(String key) {
        if (!StringUtils.hasText(key)) {
            return true;
        }
        int throttleMinutes = payhashAlertThrottleMinutes > 0
                ? payhashAlertThrottleMinutes
                : DEFAULT_PAYHASH_ALERT_THROTTLE_MINUTES;
        LocalDateTime now = LocalDateTime.now(BJT);
        LocalDateTime last = payhashAlertLastLog.get(key);
        if (last == null || last.isBefore(now.minusMinutes(throttleMinutes))) {
            payhashAlertLastLog.put(key, now);
            return true;
        }
        return false;
    }

    protected Boolean processIncomeInHourWindow(XmrWalletIncoming income,
                                                LocalDateTime windowStart,
                                                LocalDateTime windowEnd,
                                                PayhashSnapshot snapshot,
                                                BigDecimal xmrToCny,
                                                BigDecimal calToCny,
                                                Map<Long, String> algorithmCache) {
        if (income.getAmountXmr() == null || income.getAmountXmr().compareTo(BigDecimal.ZERO) <= 0) {
            walletIncomingMapper.markSettledByTxHash(income.getTxHash());
            return Boolean.TRUE;
        }
        // 方案 1：当 F2Pool 小时增量结算启用时，跳过 F2Pool payout 入账的再次落账
        if (shouldSkipPoolPayout(income)) {
            walletIncomingMapper.markSettledByTxHash(income.getTxHash());
            PayhashSource source = resolvePayhashSourceFromMeta(income);
            log.info("Skip {} wallet payout settlement (hourly delta enabled). txHash={}, subaddress={}",
                    source, income.getTxHash(), income.getSubaddress());
            return Boolean.TRUE;
        }

        PayhashSnapshot safeSnapshot = (snapshot != null) ? snapshot : PayhashSnapshot.empty();
        BigDecimal totalScore = safeSnapshot.totalScore();
        Map<Long, BigDecimal> userScores = safeSnapshot.userScores();

        // 分摊比例缺失：兜底处理（按配置可能发给 unclaimed/admin 或 skip）
        if (userScores == null || userScores.isEmpty()) {
            // 注意：在当前实现里 userScores 为空几乎等价于“窗口内没有 payhash 数据”；
            // 旧文案容易误导为“worker->user 映射缺失”，这里按 totalScore 进一步区分。
            String missingReason = (totalScore == null || totalScore.compareTo(BigDecimal.ZERO) <= 0)
                    ? "no payhash records found"
                    : "worker samples have no known user mapping";
            EarningSplitContext splitCtx = resolveEarningSplitContext(income, algorithmCache);
            return handleMissingPayhash(income, windowStart, windowEnd, missingReason, splitCtx);
        }
        if (totalScore == null || totalScore.compareTo(BigDecimal.ZERO) <= 0) {
            EarningSplitContext splitCtx = resolveEarningSplitContext(income, algorithmCache);
            return handleMissingPayhash(income, windowStart, windowEnd, "total payhash is zero", splitCtx);
        }

        Map<Long, BigDecimal> shareMap = allocateByRatio(income.getAmountXmr(), totalScore, userScores);
        if (shareMap.isEmpty()) {
            return Boolean.FALSE;
        }

        if (unclaimedUserId != null) {
            BigDecimal unclaimedPortion = shareMap.get(unclaimedUserId);
            if (unclaimedPortion != null && unclaimedPortion.compareTo(BigDecimal.ZERO) > 0) {
                log.info("Unclaimed hashrate portion {} XMR assigned to user {} for tx {}",
                        unclaimedPortion, unclaimedUserId, income.getTxHash());
            }
        }

        // 以到账时间为准写入收益明细的 earning_time（统一口径：事件时间=income.ts；入库时间=DB NOW）
        LocalDateTime earningTime = income.getTs();
        if (earningTime == null) {
            // 不写伪造时间：直接跳过并报警（避免“时间穿越”与对账困难）
            log.error("CRITICAL: xmr_wallet_incoming.ts is NULL for tx {}. Skip settlement and keep unsettled for manual fix.",
                    income.getTxHash());
            return Boolean.FALSE;
        }

        EarningSplitContext splitCtx = resolveEarningSplitContext(income, algorithmCache);
        for (Map.Entry<Long, BigDecimal> entry : shareMap.entrySet()) {
            Long userId = entry.getKey();
            BigDecimal portion = entry.getValue();
            if (portion == null || portion.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            // 若 payhash 解析出的 userId 在系统中不存在（如测试库无 id=1），将该份归入 unclaimed，避免整笔结算失败
            if (userMapper.selectById(userId).isEmpty()) {
                if (unclaimedUserId != null) {
                    log.warn("User {} not found, redirecting portion {} XMR to unclaimed user {} for tx {}",
                            userId, portion, unclaimedUserId, income.getTxHash());
                    distributeToUser(unclaimedUserId, portion, income.getTxHash(), xmrToCny, calToCny, null,
                            splitCtx.gpuEarningTypeOverride(), splitCtx.forceGpuWhenOverride(), splitCtx.forceCpuOnly(), earningTime);
                } else {
                    log.warn("User {} not found and unclaimed-user-id not configured, skipping portion {} XMR for tx {}",
                            userId, portion, income.getTxHash());
                }
                continue;
            }
            distributeToUser(userId, portion, income.getTxHash(), xmrToCny, calToCny, null,
                    splitCtx.gpuEarningTypeOverride(), splitCtx.forceGpuWhenOverride(), splitCtx.forceCpuOnly(), earningTime);
        }

        // 成功后按 tx_hash 一次性结算，连同重复行一起置 settled，避免重复结算
        walletIncomingMapper.markSettledByTxHash(income.getTxHash());
        return Boolean.TRUE;
    }

    private Boolean handleMissingPayhash(XmrWalletIncoming income,
                                         LocalDateTime windowStart,
                                         LocalDateTime windowEnd,
                                         String reason,
                                         EarningSplitContext splitCtx) {
        if (income == null || !StringUtils.hasText(income.getTxHash())) {
            return Boolean.FALSE;
        }
        if (income.getTs() == null) {
            // 不写伪造时间：异常数据留待人工修复，避免错误入账且避免“时间穿越”
            log.error("CRITICAL: Missing ts for xmr_wallet_incoming tx {} (reason={}). Skip settlement and keep unsettled.",
                    income.getTxHash(), reason);
            return Boolean.FALSE;
        }
        String txHash = income.getTxHash();

        String gpuEarningTypeOverride = splitCtx != null ? splitCtx.gpuEarningTypeOverride() : null;
        boolean forceGpuWhenOverride = splitCtx != null && splitCtx.forceGpuWhenOverride();
        boolean forceCpuOnly = splitCtx != null && splitCtx.forceCpuOnly();

        if (shouldFallbackByDeviceForOctopus(income, gpuEarningTypeOverride)) {
            DeviceFallbackScores fallbackScores = resolveOctopusFallbackScoresByDevice();
            if (fallbackScores != null && fallbackScores.userScores() != null && fallbackScores.totalScore() != null) {
                Map<Long, BigDecimal> shareMap = allocateByRatio(
                        income.getAmountXmr(), fallbackScores.totalScore(), fallbackScores.userScores());
                if (!shareMap.isEmpty()) {
                    BigDecimal xmrToCny = exchangeRateService.getXmrToCnyRate();
                    BigDecimal calToCny = marketDataService.getCalToCnyRate();
                    for (Map.Entry<Long, BigDecimal> entry : shareMap.entrySet()) {
                        distributeToUser(entry.getKey(), entry.getValue(), txHash, xmrToCny, calToCny,
                                null, gpuEarningTypeOverride, forceGpuWhenOverride, forceCpuOnly, income.getTs());
                    }
                    walletIncomingMapper.markSettledByTxHash(txHash);
                    log.warn("MISSING_PAYHASH: Fallback to device octopus hashrate for income {} due to {} (window {} - {}).",
                            txHash, reason, windowStart, windowEnd);
                    return Boolean.TRUE;
                }
            }
        }

        if (shouldFallbackByDeviceForKawpow(income, gpuEarningTypeOverride)) {
            DeviceFallbackScores fallbackScores = resolveKawpowFallbackScoresByDevice();
            if (fallbackScores != null && fallbackScores.userScores() != null && fallbackScores.totalScore() != null) {
                Map<Long, BigDecimal> shareMap = allocateByRatio(
                        income.getAmountXmr(), fallbackScores.totalScore(), fallbackScores.userScores());
                if (!shareMap.isEmpty()) {
                    BigDecimal xmrToCny = exchangeRateService.getXmrToCnyRate();
                    BigDecimal calToCny = marketDataService.getCalToCnyRate();
                    for (Map.Entry<Long, BigDecimal> entry : shareMap.entrySet()) {
                        distributeToUser(entry.getKey(), entry.getValue(), txHash, xmrToCny, calToCny,
                                null, gpuEarningTypeOverride, forceGpuWhenOverride, forceCpuOnly, income.getTs());
                    }
                    walletIncomingMapper.markSettledByTxHash(txHash);
                    log.warn("MISSING_PAYHASH: Fallback to device kawpow hashrate for income {} due to {} (window {} - {}).",
                            txHash, reason, windowStart, windowEnd);
                    return Boolean.TRUE;
                }
            }
        }

        // 注意：当窗口内完全没有 payhash 数据时，是否允许兜底属于业务选择。
        // - 若 missingPayhashPolicy=SKIP：不结算、不置 settled，等待后续补数/修复后重试（配合冷却避免刷屏）。
        // - 若 missingPayhashPolicy=FALLBACK_*：按配置兜底入账并置 settled（避免队列被“最早窗口”永久卡死）。
        if (reason != null) {
            String r = reason.toLowerCase(Locale.ROOT);
            if ((r.contains("no payhash") || r.contains("total payhash is zero"))
                    && missingPayhashPolicy == MissingPayhashPolicy.SKIP) {
                LocalDateTime retryAt = LocalDateTime.now(BJT).plusMinutes(missingPayhashRetryMinutes);
                payhashRetryAfter.put(txHash, retryAt);
                log.warn("MISSING_PAYHASH: Skip settling income {} due to {} (window {} - {}). Next retry after {}.",
                        txHash, reason, windowStart, windowEnd, retryAt.format(DT));
                return Boolean.FALSE;
            }
        }

        if (missingPayhashPolicy == MissingPayhashPolicy.SKIP) {
            // 不结算、不置 settled，避免把历史入账“错发给 admin”；同时做冷却，避免每分钟刷屏/反复打 DB
            LocalDateTime retryAt = LocalDateTime.now(BJT).plusMinutes(missingPayhashRetryMinutes);
            payhashRetryAfter.put(txHash, retryAt);
            log.warn("MISSING_PAYHASH: Skip settling income {} due to {} (window {} - {}). Next retry after {}.",
                    txHash, reason, windowStart, windowEnd, retryAt.format(DT));
            return Boolean.FALSE;
        }

        Long fallbackUserId = (missingPayhashPolicy == MissingPayhashPolicy.FALLBACK_UNCLAIMED)
                ? unclaimedUserId
                : adminUserId;
        String fallbackLabel = (missingPayhashPolicy == MissingPayhashPolicy.FALLBACK_UNCLAIMED)
                ? "unclaimed user"
                : "admin user";
        String remarkOverride = (missingPayhashPolicy == MissingPayhashPolicy.FALLBACK_UNCLAIMED)
                ? LEDGER_REMARK_UNCLAIMED_MISSING_PAYHASH
                : null;

        if (missingPayhashPolicy == MissingPayhashPolicy.FALLBACK_UNCLAIMED) {
            log.warn("MISSING_PAYHASH: Income {} due to {} (window {} - {}). Credited to {} {} and marked settled (ledger.remark={}).",
                    txHash, reason, windowStart, windowEnd, fallbackLabel, fallbackUserId, LEDGER_REMARK_UNCLAIMED_MISSING_PAYHASH);
        } else {
            // FALLBACK_ADMIN：依然按“严重”对待，方便你及时发现历史数据缺失导致的收益口径偏差
            log.error("CRITICAL: Missing payhash for income {} due to {} (window {} - {}). Fallback to {} {}.",
                    txHash, reason, windowStart, windowEnd, fallbackLabel, fallbackUserId);
        }
        distributeToUser(fallbackUserId, income.getAmountXmr(), txHash,
                exchangeRateService.getXmrToCnyRate(), marketDataService.getCalToCnyRate(),
                remarkOverride, gpuEarningTypeOverride, forceGpuWhenOverride, forceCpuOnly, income.getTs());
        walletIncomingMapper.markSettledByTxHash(txHash);
        return Boolean.TRUE;
    }

    private boolean shouldFallbackByDeviceForKawpow(XmrWalletIncoming income, String gpuEarningTypeOverride) {
        if (StringUtils.hasText(gpuEarningTypeOverride)
                && "GPU_KAWPOW".equalsIgnoreCase(gpuEarningTypeOverride.trim())) {
            return true;
        }
        if (income == null) {
            return false;
        }
        return resolvePayhashSourceFromMeta(income) == PayhashSource.ANTPOOL;
    }

    private boolean shouldFallbackByDeviceForOctopus(XmrWalletIncoming income, String gpuEarningTypeOverride) {
        if (StringUtils.hasText(gpuEarningTypeOverride)
                && "GPU_OCTOPUS".equalsIgnoreCase(gpuEarningTypeOverride.trim())) {
            return true;
        }
        if (income == null) {
            return false;
        }
        return resolvePayhashSourceFromMeta(income) == PayhashSource.F2POOL;
    }

    private DeviceFallbackScores resolveKawpowFallbackScoresByDevice() {
        List<UserHashrateSummaryVo> summaries = deviceMapper.sumGpuHashrateKawpowByUser();
        if (summaries == null || summaries.isEmpty()) {
            return null;
        }
        Map<Long, BigDecimal> scores = new HashMap<>();
        BigDecimal totalScore = BigDecimal.ZERO;
        for (UserHashrateSummaryVo summary : summaries) {
            if (summary == null || summary.getUserId() == null) {
                continue;
            }
            BigDecimal kawpowMh = safe(summary.getGpuHashrate());
            if (kawpowMh.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            scores.put(summary.getUserId(), kawpowMh);
            totalScore = totalScore.add(kawpowMh);
        }
        if (scores.isEmpty() || totalScore.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        return new DeviceFallbackScores(scores, totalScore);
    }

    private DeviceFallbackScores resolveOctopusFallbackScoresByDevice() {
        List<UserHashrateSummaryVo> summaries = deviceMapper.sumGpuHashrateOctopusByUser();
        if (summaries == null || summaries.isEmpty()) {
            return null;
        }
        Map<Long, BigDecimal> scores = new HashMap<>();
        BigDecimal totalScore = BigDecimal.ZERO;
        for (UserHashrateSummaryVo summary : summaries) {
            if (summary == null || summary.getUserId() == null) {
                continue;
            }
            BigDecimal octopusMh = safe(summary.getGpuHashrate());
            if (octopusMh.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            scores.put(summary.getUserId(), octopusMh);
            totalScore = totalScore.add(octopusMh);
        }
        if (scores.isEmpty() || totalScore.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        return new DeviceFallbackScores(scores, totalScore);
    }

    // 移除“now-2h 时间穿越兜底”：income.ts 应为 NOT NULL；若异常为 NULL，直接 SKIP 并告警（见上层逻辑）

    private Map<Long, BigDecimal> collapseToUsers(List<WorkerPayhashScore> workerScores,
                                                  Map<String, Long> owners) {
        Map<String, Long> resolvedOwners = owners != null ? owners : Collections.emptyMap();
        Map<Long, BigDecimal> distribution = new HashMap<>();
        for (WorkerPayhashScore score : workerScores) {
            if (score.payhash() == null || score.payhash().compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            Long userId = resolvedOwners.get(score.workerId());
            if (userId == null) {
                userId = parseSyntheticUserId(score.workerId());
            }
            if (userId == null) {
                // 无法映射到用户：计入“兜底认领用户”
                if (unclaimedUserId == null) {
                    log.debug("Worker {} has no owner mapping, skip {} payhash sample (unclaimed-user-id not configured)", score.workerId(), score.payhash());
                    continue;
                }
                userId = unclaimedUserId;
            }
            distribution.merge(userId, score.payhash(), BigDecimal::add);
        }
        // 即使本窗口内全部可映射，也让兜底用户存在以接收舍入残差，避免落到某个普通用户身上
        if (unclaimedUserId != null) {
            distribution.putIfAbsent(unclaimedUserId, BigDecimal.ZERO);
        }
        return distribution;
    }

    private MappingStats computeMappingStats(List<WorkerPayhashScore> workerScores,
                                             Map<String, Long> owners) {
        if (workerScores == null || workerScores.isEmpty()) {
            return new MappingStats(0, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO);
        }
        Map<String, Long> resolvedOwners = owners != null ? owners : Collections.emptyMap();
        int total = 0;
        int mapped = 0;
        int unmapped = 0;
        BigDecimal totalPayhash = BigDecimal.ZERO;
        BigDecimal unmappedPayhash = BigDecimal.ZERO;
        for (WorkerPayhashScore score : workerScores) {
            if (score == null || score.payhash() == null || score.payhash().compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            total++;
            totalPayhash = totalPayhash.add(score.payhash());
            Long userId = resolvedOwners.get(score.workerId());
            if (userId == null) {
                userId = parseSyntheticUserId(score.workerId());
            }
            if (userId == null) {
                unmapped++;
                unmappedPayhash = unmappedPayhash.add(score.payhash());
            } else {
                mapped++;
            }
        }
        return new MappingStats(total, mapped, unmapped, totalPayhash, unmappedPayhash);
    }

    private Map<String, Long> resolveWorkerOwners(List<WorkerPayhashScore> workerScores) {
        Set<String> workerIds = workerScores.stream()
                .map(WorkerPayhashScore::workerId)
                .filter(StringUtils::hasText)
                .collect(Collectors.toSet());
        if (workerIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<WorkerUserBinding> bindings = userMapper.selectByWorkerIds(new ArrayList<>(workerIds));
        Map<String, Long> owners = new HashMap<>();
        for (WorkerUserBinding binding : bindings) {
            if (binding.getWorkerId() != null && binding.getUserId() != null) {
                owners.put(binding.getWorkerId(), binding.getUserId());
            }
        }
        return owners;
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
        // 兜底认领用户：接收“未映射占比”与“舍入残差”
        Long unclaimed = this.unclaimedUserId;

        Map<Long, BigDecimal> shareMap = new LinkedHashMap<>();
        BigDecimal remaining = reward;

        // 先给“可映射用户”按比例分（向下取整，保证 remaining 非负）
        for (Map.Entry<Long, BigDecimal> entry : entries) {
            if (unclaimed != null && unclaimed.equals(entry.getKey())) {
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

        // 剩余部分（= 未映射占比 + 舍入残差）统一给 unclaimedUserId；若未配置则给最后一个普通用户（兼容旧行为）
        if (remaining.compareTo(BigDecimal.ZERO) > 0) {
            if (unclaimed != null) {
                shareMap.merge(unclaimed, remaining, BigDecimal::add);
            } else if (!entries.isEmpty()) {
                Long last = entries.get(entries.size() - 1).getKey();
                shareMap.merge(last, remaining, BigDecimal::add);
            }
        }
        return shareMap;
    }

    private Long parseSyntheticUserId(String workerId) {
        if (!StringUtils.hasText(workerId)) {
            return null;
        }
        // 只允许严格格式：USR-<digits>
        // 说明：在本项目中 “USR-<uid>” 属于服务端生成的聚合 key（由 PoolPayhashSyncService 写入时间序列表），
        // 不应对任意原始 workerId 做宽松解析，否则可能被伪造蹭算力。
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

    private void distributeToUser(Long userId,
                                  BigDecimal shareXmr,
                                  String txHash,
                                  BigDecimal xmrToCny,
                                  BigDecimal calToCny,
                                  String userLedgerRemarkOverride,
                                  String gpuEarningTypeOverride,
                                  boolean forceGpuWhenOverride,
                                  boolean forceCpuOnly,
                                  LocalDateTime earningTime) {
        User user = userMapper.selectById(userId)
                .orElseThrow(() -> new IllegalStateException("User not found: " + userId));
        SettlementCurrency preference = SettlementCurrency.fromCode(user.getSettlementCurrency());

        // 结算拆分顺序（按你的新口径）：
        // 1) 先固定按 70/30 拆分（用户 70%，平台 30%）
        // 2) 再基于“用户 70%（不含折扣）”计算邀请佣金（从平台 30% 中支付）
        // 3) 最后再应用被邀请者“平台费 9 折”折扣（从平台剩余额度返还给用户）
        BigDecimal userXmrBase = shareXmr.multiply(USER_RATE).setScale(XMR_SCALE, RoundingMode.HALF_UP);
        BigDecimal platformFeeXmrPortion = shareXmr.subtract(userXmrBase); // 固定平台费份额（约等于 30%）
        BigDecimal platformXmrPortion = platformFeeXmrPortion;            // 可支配平台份额（会被佣金/折扣扣减）
        // 被邀请者奖励（让利/折扣）：不并入 CPU/GPU 明细，单独记为 earning_type=INVITED
        BigDecimal inviteeBonusXmr = BigDecimal.ZERO;
        BigDecimal inviteeBonusCal = BigDecimal.ZERO;

        Long inviterId = user.getInviterId();
        LocalDateTime now = LocalDateTime.now(BJT);

        // 1) 激活门槛：被邀请者达到阈值后，邀请者才开始计佣（激活标记持久化到 asset_ledger）
        boolean activated = inviterId != null && assetLedgerService.hasRefTypeWithRemarkPrefix(
                user.getId(), LEDGER_REF_TYPE_BONUS, LEDGER_REMARK_INVITEE_ACTIVATION);
        BigDecimal threshold = inviteProperties != null ? safe(inviteProperties.getActivationThresholdXmr()) : BigDecimal.ZERO;
        if (inviterId != null && !activated && threshold.compareTo(BigDecimal.ZERO) > 0 && shareXmr.compareTo(threshold) >= 0) {
            assetLedgerService.recordInviteeActivation(user.getId(), txHash);
            activated = true;
        }

        // 2) 邀请者佣金：按现有阶梯，且要求激活；基于“用户 70%（不含折扣）”计算；从平台份额中支付；并做每月封顶
        BigDecimal inviteXmrPortion = BigDecimal.ZERO;
        if (inviterId != null && activated) {
            BigDecimal inviterRate = inviteService.getCommissionRateForUser(inviterId);
            if (inviterRate != null && inviterRate.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal commissionBaseXmr = userXmrBase; // 70% 基数（折扣之前）
                BigDecimal wantedXmr = commissionBaseXmr.multiply(inviterRate).setScale(XMR_SCALE, RoundingMode.HALF_UP);
                BigDecimal wantedCal = xmrToCal(wantedXmr).setScale(CAL_SCALE, RoundingMode.HALF_UP);

                BigDecimal monthlyCap = inviteProperties != null ? safe(inviteProperties.getInviterMonthlyCapCal()) : BigDecimal.ZERO;
                if (monthlyCap.compareTo(BigDecimal.ZERO) > 0) {
                    LocalDate today = now.toLocalDate();
                    LocalDateTime monthStart = today.withDayOfMonth(1).atStartOfDay();
                    LocalDateTime nextMonthStart = monthStart.plusMonths(1);
                    BigDecimal monthUsed = safe(commissionRecordMapper.sumCommissionByUserIdAndDateRange(inviterId, monthStart.format(DT), nextMonthStart.format(DT)));
                    BigDecimal capRemaining = monthlyCap.subtract(monthUsed);
                    if (capRemaining.compareTo(BigDecimal.ZERO) <= 0) {
                        wantedCal = BigDecimal.ZERO;
                    } else {
                        wantedCal = min(wantedCal, capRemaining);
                    }
                }
                // 佣金从平台份额中支付：先按平台份额上限截断（CAL 口径），再转换回 XMR
                BigDecimal platformCalMax = xmrToCal(platformXmrPortion).setScale(CAL_SCALE, RoundingMode.HALF_UP);
                BigDecimal inviteCal = min(wantedCal, platformCalMax).setScale(CAL_SCALE, RoundingMode.HALF_UP);
                inviteXmrPortion = calToXmr(inviteCal).setScale(XMR_SCALE, RoundingMode.HALF_UP);
                // 保护：由于 CAL<->XMR 换算舍入，inviteXmrPortion 可能极小概率略大于 platformXmrPortion
                if (inviteXmrPortion.compareTo(platformXmrPortion) > 0) {
                    inviteXmrPortion = platformXmrPortion;
                }
            }
        }

        // 从平台份额扣除邀请佣金
        if (inviteXmrPortion.compareTo(BigDecimal.ZERO) > 0) {
            platformXmrPortion = platformXmrPortion.subtract(inviteXmrPortion);
            if (platformXmrPortion.compareTo(BigDecimal.ZERO) < 0) {
                platformXmrPortion = BigDecimal.ZERO;
            }
        }

        // 3) 被邀请者新手折扣：平台费率打 9 折（让利部分返还给用户），累计不超过 cap 且仅持续 durationDays
        // 折扣金额按“固定平台费份额（30%）”计算，但必须受限于“当前平台剩余额度（已扣除佣金）”
        BigDecimal discountXmr = BigDecimal.ZERO;
        if (inviterId != null && inviteProperties != null && inviteProperties.getInviteeDiscount() != null
                && inviteProperties.getInviteeDiscount().isEnabled()) {
            InviteProperties.InviteeDiscount cfg = inviteProperties.getInviteeDiscount();
            if (isWithinDiscountWindow(user, now, cfg.getDurationDays())) {
                BigDecimal capCal = safe(cfg.getCapCal());
                BigDecimal multiplier = safe(cfg.getPlatformFeeMultiplier());
                // multiplier=0.9 => discountRate=0.1
                BigDecimal discountRate = BigDecimal.ONE.subtract(multiplier);
                if (capCal.compareTo(BigDecimal.ZERO) > 0 && discountRate.compareTo(BigDecimal.ZERO) > 0) {
                    LocalDateTime start = user.getCreateTime() != null ? user.getCreateTime() : now.minusDays(cfg.getDurationDays());
                    LocalDateTime end = start.plusDays(cfg.getDurationDays());
                    BigDecimal usedCal = safe(assetLedgerService.sumAmountCalByRefTypeAndRemarkPrefixBetween(
                            user.getId(), LEDGER_REF_TYPE_BONUS, LEDGER_REMARK_INVITEE_DISCOUNT, start, end));
                    BigDecimal remainingCal = capCal.subtract(usedCal);
                    if (remainingCal.compareTo(BigDecimal.ZERO) > 0 && platformXmrPortion.compareTo(BigDecimal.ZERO) > 0) {
                        // 按固定平台费份额（30%）计算潜在折扣
                        BigDecimal potentialXmr = platformFeeXmrPortion.multiply(discountRate).setScale(XMR_SCALE, RoundingMode.HALF_UP);
                        BigDecimal potentialCal = xmrToCal(potentialXmr).setScale(CAL_SCALE, RoundingMode.HALF_UP);
                        BigDecimal discountCal = min(potentialCal, remainingCal).setScale(CAL_SCALE, RoundingMode.HALF_UP);
                        // 受限于平台剩余额度（佣金之后）
                        BigDecimal platformRemainCalMax = xmrToCal(platformXmrPortion).setScale(CAL_SCALE, RoundingMode.HALF_UP);
                        discountCal = min(discountCal, platformRemainCalMax).setScale(CAL_SCALE, RoundingMode.HALF_UP);
                        discountXmr = calToXmr(discountCal).setScale(XMR_SCALE, RoundingMode.HALF_UP);
                        // 再做一次 XMR 口径保护
                        if (discountXmr.compareTo(platformXmrPortion) > 0) {
                            discountXmr = platformXmrPortion;
                        }
                        if (discountXmr.compareTo(BigDecimal.ZERO) > 0) {
                            // 让利从平台份额转移给用户，但不并入 CPU/GPU 挖矿明细，改为单独记录 INVITED
                            inviteeBonusXmr = discountXmr;
                            inviteeBonusCal = discountCal;
                            platformXmrPortion = platformXmrPortion.subtract(discountXmr);
                            if (platformXmrPortion.compareTo(BigDecimal.ZERO) < 0) {
                                platformXmrPortion = BigDecimal.ZERO;
                            }
                            BigDecimal discountCny = (xmrToCny != null && xmrToCny.compareTo(BigDecimal.ZERO) > 0)
                                    ? discountXmr.multiply(xmrToCny).setScale(CNY_SCALE, RoundingMode.HALF_UP)
                                    : null;
                            // 仅用于统计与审计：记录本次折扣消耗（CAL + XMR 双字段）
                            assetLedgerService.recordInviteeDiscount(
                                    user.getId(),
                                    discountXmr,
                                    discountCal,
                                    discountCny,
                                    txHash,
                                    "新手手续费折扣"
                            );
                        }
                    }
                }
            }
        }

        BigDecimal adminXmrPortion = platformXmrPortion;

        switch (preference) {
            case CNY -> settleAsCny(user, userXmrBase, inviteeBonusXmr, inviteeBonusCal, adminXmrPortion, inviteXmrPortion, inviterId,
                    xmrToCny, calToCny, txHash, userLedgerRemarkOverride, gpuEarningTypeOverride, forceGpuWhenOverride, forceCpuOnly, earningTime);
            case CAL -> settleAsCal(user, userXmrBase, inviteeBonusXmr, inviteeBonusCal, adminXmrPortion, inviteXmrPortion, inviterId,
                    calToCny, txHash, userLedgerRemarkOverride, gpuEarningTypeOverride, forceGpuWhenOverride, forceCpuOnly, earningTime);
            default -> settleAsCal(user, userXmrBase, inviteeBonusXmr, inviteeBonusCal, adminXmrPortion, inviteXmrPortion, inviterId,
                    calToCny, txHash, userLedgerRemarkOverride, gpuEarningTypeOverride, forceGpuWhenOverride, forceCpuOnly, earningTime);
        }
    }

    public void distributeXmrShare(Long userId, BigDecimal shareXmr, String txHash, LocalDateTime earningTime) {
        if (userId == null || shareXmr == null || shareXmr.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        BigDecimal xmrToCny = exchangeRateService.getXmrToCnyRate();
        BigDecimal calToCny = marketDataService.getCalToCnyRate();
        distributeToUser(userId, shareXmr, txHash, xmrToCny, calToCny, null, null, false, false, earningTime);
    }

    public void distributeXmrShare(Long userId,
                                   BigDecimal shareXmr,
                                   String txHash,
                                   LocalDateTime earningTime,
                                   String gpuEarningTypeOverride,
                                   boolean forceGpuWhenOverride) {
        if (userId == null || shareXmr == null || shareXmr.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        BigDecimal xmrToCny = exchangeRateService.getXmrToCnyRate();
        BigDecimal calToCny = marketDataService.getCalToCnyRate();
        distributeToUser(userId, shareXmr, txHash, xmrToCny, calToCny,
                null, gpuEarningTypeOverride, forceGpuWhenOverride, false, earningTime);
    }

    /**
     * 按 payhash 纠偏的差额入账（允许正负，默认不触发邀请佣金/折扣逻辑）。
     */
    public void applyPayhashAdjustment(Long userId,
                                       BigDecimal deltaXmr,
                                       String txHash,
                                       LocalDateTime earningTime,
                                       String gpuEarningTypeOverride,
                                       boolean forceGpuWhenOverride) {
        if (userId == null || deltaXmr == null || deltaXmr.compareTo(BigDecimal.ZERO) == 0) {
            return;
        }
        User user = userMapper.selectById(userId)
                .orElseThrow(() -> new IllegalStateException("User not found: " + userId));
        SettlementCurrency preference = SettlementCurrency.fromCode(user.getSettlementCurrency());
        BigDecimal userXmrBase = deltaXmr.multiply(USER_RATE).setScale(XMR_SCALE, RoundingMode.HALF_UP);
        BigDecimal platformXmrPortion = deltaXmr.subtract(userXmrBase);
        BigDecimal userCalBase = xmrToCal(userXmrBase).setScale(CAL_SCALE, RoundingMode.HALF_UP);
        BigDecimal platformCal = xmrToCal(platformXmrPortion).setScale(CAL_SCALE, RoundingMode.HALF_UP);
        BigDecimal calToCny = marketDataService.getCalToCnyRate();
        BigDecimal xmrToCny = exchangeRateService.getXmrToCnyRate();

        if (preference == SettlementCurrency.CNY) {
            if (xmrToCny == null || xmrToCny.compareTo(BigDecimal.ZERO) <= 0) {
                log.warn("Payhash adjustment skipped: XMR/CNY rate unavailable (userId={}, txHash={})", userId, txHash);
                return;
            }
            BigDecimal userCny = userXmrBase.multiply(xmrToCny).setScale(CNY_SCALE, RoundingMode.HALF_UP);
            BigDecimal platformCny = platformXmrPortion.multiply(xmrToCny).setScale(CNY_SCALE, RoundingMode.HALF_UP);
            userMapper.updateCashBalances(user.getId(), userCny, BigDecimal.ZERO, BigDecimal.ZERO);
            if (platformCny.compareTo(BigDecimal.ZERO) != 0) {
                userMapper.updateCashBalances(adminUserId, platformCny, BigDecimal.ZERO, BigDecimal.ZERO);
            }
            insertCompensationEarningsHistory(user, userCalBase, userCny, earningTime);
            assetLedgerService.recordMiningPayout(
                    user.getId(),
                    "CNY",
                    userXmrBase,
                    null,
                    userCny,
                    null,
                    txHash,
                    LEDGER_REMARK_PAYHASH_ADJUSTMENT,
                    earningTime);
            assetLedgerService.recordMiningPayout(
                    adminUserId,
                    "CNY",
                    platformXmrPortion,
                    null,
                    platformCny,
                    null,
                    txHash,
                    LEDGER_REMARK_PAYHASH_ADJUSTMENT_PLATFORM,
                    earningTime);
            return;
        }

        BigDecimal userCny = (calToCny != null && calToCny.compareTo(BigDecimal.ZERO) > 0)
                ? userCalBase.multiply(calToCny).setScale(CNY_SCALE, RoundingMode.HALF_UP)
                : null;
        BigDecimal platformCny = (calToCny != null && calToCny.compareTo(BigDecimal.ZERO) > 0)
                ? platformCal.multiply(calToCny).setScale(CNY_SCALE, RoundingMode.HALF_UP)
                : null;
        userMapper.updateUserWallet(user.getId(), userCalBase);
        if (platformCal.compareTo(BigDecimal.ZERO) != 0) {
            userMapper.updateUserWallet(adminUserId, platformCal);
        }
        insertCompensationEarningsHistory(user, userCalBase, userCny, earningTime);
        assetLedgerService.recordMiningPayout(
                user.getId(),
                "CAL",
                userXmrBase,
                userCalBase,
                userCny,
                null,
                txHash,
                LEDGER_REMARK_PAYHASH_ADJUSTMENT,
                earningTime);
        assetLedgerService.recordMiningPayout(
                adminUserId,
                "CAL",
                platformXmrPortion,
                platformCal,
                platformCny,
                null,
                txHash,
                LEDGER_REMARK_PAYHASH_ADJUSTMENT_PLATFORM,
                earningTime);
    }

    private boolean isWithinDiscountWindow(User user, LocalDateTime now, int durationDays) {
        if (durationDays <= 0) {
            return false;
        }
        LocalDateTime start = user.getCreateTime();
        if (start == null) {
            return false;
        }
        return !now.isAfter(start.plusDays(durationDays));
    }

    private BigDecimal safe(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private BigDecimal min(BigDecimal a, BigDecimal b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.compareTo(b) <= 0 ? a : b;
    }

    private BigDecimal xmrToCal(BigDecimal xmr) {
        BigDecimal ratio = safe(marketDataService.getCalXmrRatio()); // 1 CAL = ratio * XMR
        if (ratio.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalStateException("CAL/XMR ratio unavailable");
        }
        if (xmr == null) {
            return BigDecimal.ZERO;
        }
        return xmr.divide(ratio, CAL_SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal calToXmr(BigDecimal cal) {
        BigDecimal ratio = safe(marketDataService.getCalXmrRatio()); // 1 CAL = ratio * XMR
        if (ratio.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalStateException("CAL/XMR ratio unavailable");
        }
        if (cal == null) {
            return BigDecimal.ZERO;
        }
        return cal.multiply(ratio).setScale(XMR_SCALE, RoundingMode.HALF_UP);
    }

    private void settleAsCny(User user,
                             BigDecimal userXmrBase,
                             BigDecimal inviteeBonusXmr,
                             BigDecimal inviteeBonusCal,
                             BigDecimal platformXmr,
                             BigDecimal inviteXmr,
                             Long inviterId,
                             BigDecimal xmrToCny,
                             BigDecimal calToCny,
                             String txHash,
                             String userLedgerRemarkOverride,
                             String gpuEarningTypeOverride,
                             boolean forceGpuWhenOverride,
                             boolean forceCpuOnly,
                             LocalDateTime earningTime) {
        if (xmrToCny == null || xmrToCny.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalStateException("XMR/CNY rate unavailable");
        }
        BigDecimal userCnyBase = userXmrBase.multiply(xmrToCny).setScale(CNY_SCALE, RoundingMode.HALF_UP);
        BigDecimal bonusCny = (inviteeBonusXmr != null && inviteeBonusXmr.compareTo(BigDecimal.ZERO) > 0)
                ? inviteeBonusXmr.multiply(xmrToCny).setScale(CNY_SCALE, RoundingMode.HALF_UP)
                : BigDecimal.ZERO.setScale(CNY_SCALE, RoundingMode.HALF_UP);
        BigDecimal userCny = userCnyBase.add(bonusCny).setScale(CNY_SCALE, RoundingMode.HALF_UP);
        BigDecimal platformCny = platformXmr.multiply(xmrToCny).setScale(CNY_SCALE, RoundingMode.HALF_UP);
        BigDecimal inviteCny = inviteXmr.multiply(xmrToCny).setScale(CNY_SCALE, RoundingMode.HALF_UP);

        userMapper.updateCashBalances(user.getId(), userCny, BigDecimal.ZERO, BigDecimal.ZERO);
        if (platformCny.compareTo(BigDecimal.ZERO) > 0) {
            userMapper.updateCashBalances(adminUserId, platformCny, BigDecimal.ZERO, BigDecimal.ZERO);
        }
        // 先写入收益历史拿到主键，用于佣金记录的 source_earning_id（DB 约束不可为 NULL）
        // 记录时仍然保留本次用户应得的 XMR 数量，避免金额过小时 CNY 进位到 0 后丢失明细
        BigDecimal userCalEqBase = xmrToCal(userXmrBase).setScale(CAL_SCALE, RoundingMode.HALF_UP);
        MiningHistorySplitResult split = recordMiningEarningsHistorySplitCpuGpu(
                user, userCnyBase, userCalEqBase, gpuEarningTypeOverride, forceGpuWhenOverride, forceCpuOnly, earningTime);
        Long historyId = split.primaryId;
        // 平台佣金池监控以 CAL 等值口径更稳：这里统一把 platform_commissions.*amount 写为 CAL 等值，
        // currency 字段仅表示“本次结算分支”（CAL/CNY），供前端展示用。
        BigDecimal platformCalEq = xmrToCal(platformXmr).setScale(CAL_SCALE, RoundingMode.HALF_UP);
        BigDecimal inviteCalEq = xmrToCal(inviteXmr).setScale(CAL_SCALE, RoundingMode.HALF_UP);
        BigDecimal bonusCalEq = (inviteeBonusCal != null ? inviteeBonusCal : BigDecimal.ZERO).setScale(CAL_SCALE, RoundingMode.HALF_UP);
        BigDecimal originalCalEq = userCalEqBase.add(bonusCalEq).add(platformCalEq).add(inviteCalEq).setScale(CAL_SCALE, RoundingMode.HALF_UP);
        recordPlatformCommission(historyId, user, originalCalEq, platformCalEq, "CNY");
        // 给拆分出的“另一条”写入 0 金额的币种标记，避免 settleCurrency 推断错误
        recordSettlementCurrencyMarkerIfNeeded(split, user, "CNY");

        if (inviteXmr.compareTo(BigDecimal.ZERO) > 0 && inviterId != null) {
            // 邀请佣金以 CAL 入账（1 CAL = 0.001 XMR）
            BigDecimal inviteCal = xmrToCal(inviteXmr).setScale(CAL_SCALE, RoundingMode.HALF_UP);
            userMapper.updateUserWallet(inviterId, inviteCal);
            recordInvitationCommission(user, inviterId, split, inviteCal, inviteCny, txHash, earningTime);
        }

        // 被邀请者奖励（让利/折扣）单独落账到 earnings_history：earning_type=INVITED
        if (inviteeBonusCal != null && inviteeBonusCal.compareTo(BigDecimal.ZERO) > 0) {
            insertInvitedBonusEarningsHistory(user, inviteeBonusCal, bonusCny, earningTime);
        }

        String userRemark = (userLedgerRemarkOverride != null && !userLedgerRemarkOverride.isBlank())
                ? userLedgerRemarkOverride
                : (unclaimedUserId != null && Objects.equals(user.getId(), unclaimedUserId))
                ? LEDGER_REMARK_UNCLAIMED_HASHRATE
                : "猫池结算（CNY）";
        assetLedgerService.recordMiningPayout(
                user.getId(),
                "CNY",
                userXmrBase.add(inviteeBonusXmr != null ? inviteeBonusXmr : BigDecimal.ZERO),
                null,
                userCny,
                historyId,
                txHash,
                userRemark,
                earningTime);
        assetLedgerService.recordMiningPayout(
                adminUserId,
                "CNY",
                platformXmr,
                null,
                platformCny,
                historyId,
                txHash,
                "猫池结算抽成",
                earningTime);
    }

    private void settleAsCal(User user,
                             BigDecimal userXmrBase,
                             BigDecimal inviteeBonusXmr,
                             BigDecimal inviteeBonusCal,
                             BigDecimal platformXmr,
                             BigDecimal inviteXmr,
                             Long inviterId,
                             BigDecimal calToCny,
                             String txHash,
                             String userLedgerRemarkOverride,
                             String gpuEarningTypeOverride,
                             boolean forceGpuWhenOverride,
                             boolean forceCpuOnly,
                             LocalDateTime earningTime) {
        BigDecimal userCalBase = xmrToCal(userXmrBase).setScale(CAL_SCALE, RoundingMode.HALF_UP);
        BigDecimal bonusCal = (inviteeBonusCal != null ? inviteeBonusCal : BigDecimal.ZERO).setScale(CAL_SCALE, RoundingMode.HALF_UP);
        BigDecimal userCal = userCalBase.add(bonusCal).setScale(CAL_SCALE, RoundingMode.HALF_UP);
        BigDecimal platformCal = xmrToCal(platformXmr).setScale(CAL_SCALE, RoundingMode.HALF_UP);
        BigDecimal inviteCal = xmrToCal(inviteXmr).setScale(CAL_SCALE, RoundingMode.HALF_UP);
        userMapper.updateUserWallet(user.getId(), userCal);
        if (platformCal.compareTo(BigDecimal.ZERO) > 0) {
            userMapper.updateUserWallet(adminUserId, platformCal);
        }
        BigDecimal userCalCnyBase = calToCny != null ? userCalBase.multiply(calToCny).setScale(CNY_SCALE, RoundingMode.HALF_UP) : null;
        BigDecimal bonusCalCny = calToCny != null ? bonusCal.multiply(calToCny).setScale(CNY_SCALE, RoundingMode.HALF_UP) : null;
        BigDecimal userCalCny = (userCalCnyBase != null && bonusCalCny != null) ? userCalCnyBase.add(bonusCalCny).setScale(CNY_SCALE, RoundingMode.HALF_UP) : null;
        BigDecimal platformCalCny = calToCny != null ? platformCal.multiply(calToCny).setScale(CNY_SCALE, RoundingMode.HALF_UP) : null;
        BigDecimal inviteCalCny = calToCny != null ? inviteCal.multiply(calToCny).setScale(CNY_SCALE, RoundingMode.HALF_UP) : null;

        MiningHistorySplitResult split = recordMiningEarningsHistorySplitCpuGpu(
                user, userCalCnyBase, userCalBase, gpuEarningTypeOverride, forceGpuWhenOverride, forceCpuOnly, earningTime);
        Long historyId = split.primaryId;
        recordPlatformCommission(historyId, user, userCal.add(platformCal).add(inviteCal), platformCal, "CAL");
        recordSettlementCurrencyMarkerIfNeeded(split, user, "CAL");

        if (inviteCal.compareTo(BigDecimal.ZERO) > 0 && inviterId != null) {
            userMapper.updateUserWallet(inviterId, inviteCal);
            recordInvitationCommission(user, inviterId, split, inviteCal, inviteCalCny, txHash, earningTime);
        }

        // 被邀请者奖励（让利/折扣）单独落账到 earnings_history：earning_type=INVITED
        if (bonusCal.compareTo(BigDecimal.ZERO) > 0) {
            insertInvitedBonusEarningsHistory(user, bonusCal, bonusCalCny, earningTime);
        }

        String userRemark = (userLedgerRemarkOverride != null && !userLedgerRemarkOverride.isBlank())
                ? userLedgerRemarkOverride
                : (unclaimedUserId != null && Objects.equals(user.getId(), unclaimedUserId))
                ? LEDGER_REMARK_UNCLAIMED_HASHRATE
                : "猫池结算（CAL）";
        assetLedgerService.recordMiningPayout(
                user.getId(),
                "CAL",
                userXmrBase.add(inviteeBonusXmr != null ? inviteeBonusXmr : BigDecimal.ZERO),
                userCal,
                userCalCny,
                historyId,
                txHash,
                userRemark,
                earningTime);
        assetLedgerService.recordMiningPayout(
                adminUserId,
                "CAL",
                platformXmr,
                platformCal,
                platformCalCny,
                historyId,
                txHash,
                "猫池结算抽成",
                earningTime);
    }

    private Long recordEarningsHistory(User user, BigDecimal amountCny, BigDecimal amountCal, String earningType, LocalDateTime earningTime) {
        EarningsHistory history = new EarningsHistory();
        history.setUserId(user.getId());
        // 结算来源于矿池整体，不绑定具体设备，使用用户 workerId 或 'POOL' 兜底避免 NULL 约束
        history.setDeviceId(Optional.ofNullable(user.getWorkerId()).orElse("POOL"));
        // bonus_cal_amount 不允许为 NULL，兜底为 0
        history.setBonusCalAmount(BigDecimal.ZERO);
        // earnings_history.amount_cny 在库中通常为 NOT NULL，兜底为 0 避免严格模式报错
        history.setAmountCny(amountCny != null ? amountCny : BigDecimal.ZERO);
        history.setAmountCal(amountCal);
        // earning_type：优先使用调用方传入的类型；为空时兜底为 CPU
        String normalizedType = (earningType == null || earningType.isBlank()) ? "CPU" : earningType.trim().toUpperCase();
        history.setEarningType(normalizedType);
        history.setEarningTime(earningTime);
        earningsHistoryMapper.insert(history);

        // Also update daily stats
        LocalDate statDate = earningTime != null ? earningTime.toLocalDate() : java.time.LocalDate.now();
        earningsMapper.upsertDailyStats(
                user.getId(),
                statDate,
                amountCal,
                amountCny,
                earningType
        );

        return history.getId();
    }

    /**
     * 被邀请者奖励（让利/折扣）落账：单独记一条 earnings_history（earning_type=INVITED）。
     * 说明：
     * - 该金额不并入 CPU/GPU 明细，避免“挖矿收益”与“被邀请奖励”重复计入；
     * - 仍会写入 daily_earnings_stats.total_*，以便总收益能正确汇总。
     */
    private Long insertInvitedBonusEarningsHistory(User user, BigDecimal amountCal, BigDecimal amountCny, LocalDateTime earningTime) {
        if (user == null) return null;
        if (amountCal == null || amountCal.compareTo(BigDecimal.ZERO) <= 0) return null;
        EarningsHistory h = new EarningsHistory();
        h.setUserId(user.getId());
        h.setDeviceId("INVITED");
        h.setBonusCalAmount(BigDecimal.ZERO);
        h.setAmountCal(amountCal);
        h.setAmountCny(amountCny != null ? amountCny : BigDecimal.ZERO);
        h.setEarningType("INVITED");
        h.setEarningTime(earningTime);
        earningsHistoryMapper.insert(h);

        LocalDate statDate = earningTime != null ? earningTime.toLocalDate() : java.time.LocalDate.now();
        earningsMapper.upsertDailyStats(user.getId(), statDate, amountCal, amountCny, "INVITED");
        return h.getId();
    }

    /**
     * payhash 纠偏补差：单独记一条 earnings_history（earning_type=COMPENSATION）。
     * 说明：不参与邀请佣金/折扣逻辑，仅用于补差追溯。
     */
    private Long insertCompensationEarningsHistory(User user, BigDecimal amountCal, BigDecimal amountCny, LocalDateTime earningTime) {
        if (user == null) return null;
        if ((amountCal == null || amountCal.compareTo(BigDecimal.ZERO) == 0)
                && (amountCny == null || amountCny.compareTo(BigDecimal.ZERO) == 0)) {
            return null;
        }
        EarningsHistory h = new EarningsHistory();
        h.setUserId(user.getId());
        h.setDeviceId("COMPENSATION");
        h.setBonusCalAmount(BigDecimal.ZERO);
        h.setAmountCal(amountCal != null ? amountCal : BigDecimal.ZERO);
        h.setAmountCny(amountCny != null ? amountCny : BigDecimal.ZERO);
        h.setEarningType("COMPENSATION");
        h.setEarningTime(earningTime);
        earningsHistoryMapper.insert(h);

        LocalDate statDate = earningTime != null ? earningTime.toLocalDate() : java.time.LocalDate.now();
        earningsMapper.upsertDailyStats(user.getId(), statDate, h.getAmountCal(), h.getAmountCny(), "COMPENSATION");
        return h.getId();
    }

    /**
     * 将“挖矿收益”按 CPU/GPU 在线算力占比拆成两条 earnings_history 明细：
     * - earning_type=CPU
     * - earning_type=GPU
     *
     * 说明：
     * - 若 forceGpuWhenOverride=true，则不看设备算力，全部记为 GPU（用于矿池算力口径）；
     * - 若 forceCpuOnly=true，则不看设备算力，全部记为 CPU（用于 C3pool 口径）；
     * - 否则若无法获取算力占比（总算力=0），则全部记为 CPU；
     * - 为保证 platform_commissions / commission_records 的外键引用一致，这里返回“源记录ID”：
     *   优先返回 CPU 那条（若 CPU 为 0 则返回 GPU）。
     */
    private static class MiningHistorySplitResult {
        final Long primaryId;
        final Long cpuId;
        final List<Long> gpuIds;

        private MiningHistorySplitResult(Long primaryId, Long cpuId, List<Long> gpuIds) {
            this.primaryId = primaryId;
            this.cpuId = cpuId;
            this.gpuIds = gpuIds != null ? gpuIds : Collections.emptyList();
        }
    }

    public record MiningSplitAmount(BigDecimal cpuCal, BigDecimal gpuCal, String gpuType) {
    }

    public MiningSplitAmount previewMiningSplit(Long userId,
                                                BigDecimal shareXmr,
                                                String gpuEarningTypeOverride,
                                                boolean forceGpuWhenOverride,
                                                boolean forceCpuOnly) {
        if (userId == null || shareXmr == null || shareXmr.compareTo(BigDecimal.ZERO) <= 0) {
            return new MiningSplitAmount(BigDecimal.ZERO, BigDecimal.ZERO, null);
        }
        BigDecimal userXmrBase = shareXmr.multiply(USER_RATE).setScale(XMR_SCALE, RoundingMode.HALF_UP);
        BigDecimal totalCal = xmrToCal(userXmrBase).setScale(CAL_SCALE, RoundingMode.HALF_UP);
        CpuGpuRatio ratio = resolveCpuGpuRatio(userId, forceGpuWhenOverride, forceCpuOnly);

        BigDecimal cpuCal = totalCal.multiply(ratio.cpuRatio).setScale(CAL_SCALE, RoundingMode.HALF_UP);
        BigDecimal gpuCal = totalCal.multiply(ratio.gpuRatio).setScale(CAL_SCALE, RoundingMode.HALF_UP);
        String gpuType = (gpuCal.compareTo(BigDecimal.ZERO) > 0)
                ? (StringUtils.hasText(gpuEarningTypeOverride) ? gpuEarningTypeOverride : "GPU")
                : null;
        return new MiningSplitAmount(cpuCal, gpuCal, gpuType);
    }

    private MiningHistorySplitResult recordMiningEarningsHistorySplitCpuGpu(User user,
                                                                            BigDecimal totalAmountCny,
                                                                            BigDecimal totalAmountCal,
                                                                            String gpuEarningTypeOverride,
                                                                            boolean forceGpuWhenOverride,
                                                                            boolean forceCpuOnly,
                                                                            LocalDateTime earningTime) {
        CpuGpuRatio ratio = resolveCpuGpuRatio(user.getId(), forceGpuWhenOverride, forceCpuOnly);

        BigDecimal totalCal = totalAmountCal != null ? totalAmountCal : BigDecimal.ZERO;
        BigDecimal totalCny = totalAmountCny != null ? totalAmountCny : BigDecimal.ZERO;

        BigDecimal cpuCal = totalCal.multiply(ratio.cpuRatio).setScale(CAL_SCALE, RoundingMode.HALF_UP);
        BigDecimal gpuCal = totalCal.multiply(ratio.gpuRatio).setScale(CAL_SCALE, RoundingMode.HALF_UP);

        BigDecimal cpuCny = totalCny.multiply(ratio.cpuRatio).setScale(CNY_SCALE, RoundingMode.HALF_UP);
        BigDecimal gpuCny = totalCny.multiply(ratio.gpuRatio).setScale(CNY_SCALE, RoundingMode.HALF_UP);

        Long cpuId = null;
        List<Long> gpuIds = new ArrayList<>();

        if (cpuCal.compareTo(BigDecimal.ZERO) > 0 || cpuCny.compareTo(BigDecimal.ZERO) > 0) {
            cpuId = recordEarningsHistory(user, cpuCny, cpuCal, "CPU", earningTime);
        }
        if (gpuCal.compareTo(BigDecimal.ZERO) > 0 || gpuCny.compareTo(BigDecimal.ZERO) > 0) {
            if (StringUtils.hasText(gpuEarningTypeOverride)) {
                Long id = recordEarningsHistory(user, gpuCny, gpuCal, gpuEarningTypeOverride, earningTime);
                if (id != null) {
                    gpuIds.add(id);
                }
            } else {
                List<GpuAlgorithmRatio> ratios = resolveGpuAlgorithmRatios(user.getId());
                if (ratios.isEmpty()) {
                    Long id = recordEarningsHistory(user, gpuCny, gpuCal, "GPU", earningTime);
                    if (id != null) {
                        gpuIds.add(id);
                    }
                } else {
                    BigDecimal remainCal = gpuCal;
                    BigDecimal remainCny = gpuCny;
                    for (int i = 0; i < ratios.size(); i++) {
                        GpuAlgorithmRatio r = ratios.get(i);
                        boolean last = (i == ratios.size() - 1);
                        BigDecimal partCal = last ? remainCal : gpuCal.multiply(r.ratio).setScale(CAL_SCALE, RoundingMode.HALF_UP);
                        BigDecimal partCny = last ? remainCny : gpuCny.multiply(r.ratio).setScale(CNY_SCALE, RoundingMode.HALF_UP);
                        remainCal = remainCal.subtract(partCal);
                        remainCny = remainCny.subtract(partCny);
                        String normalized = normalizeAlgorithm(r.algorithm);
                        String earningType = StringUtils.hasText(normalized) ? "GPU_" + normalized : "GPU";
                        Long id = recordEarningsHistory(user, partCny, partCal, earningType, earningTime);
                        if (id != null) {
                            gpuIds.add(id);
                        }
                    }
                }
            }
        }

        // 极端兜底：如果由于舍入导致两边都为 0，则把原金额全记为 CPU
        if (cpuId == null && gpuIds.isEmpty()) {
            Long id = recordEarningsHistory(user, totalCny, totalCal, "CPU", earningTime);
            return new MiningHistorySplitResult(id, id, null);
        }
        Long primary = cpuId != null ? cpuId : gpuIds.get(0);
        return new MiningHistorySplitResult(primary, cpuId, gpuIds);
    }

    private static class CpuGpuRatio {
        final BigDecimal cpuRatio;
        final BigDecimal gpuRatio;

        private CpuGpuRatio(BigDecimal cpuRatio, BigDecimal gpuRatio) {
            this.cpuRatio = cpuRatio;
            this.gpuRatio = gpuRatio;
        }
    }

    /**
     * 使用当前在线设备的 cpu_hashrate / gpu_hashrate 汇总来估算 CPU/GPU 占比。
     * 注意：这是“结算时快照”，用于收益按类型拆分展示；若未来需要更精确，可改为按 device_hashrate_reports 做小时均值。
     */
    private CpuGpuRatio resolveCpuGpuRatio(Long userId, boolean forceGpuWhenOverride, boolean forceCpuOnly) {
        if (forceCpuOnly) {
            return new CpuGpuRatio(BigDecimal.ONE, BigDecimal.ZERO);
        }
        if (forceGpuWhenOverride) {
            return new CpuGpuRatio(BigDecimal.ZERO, BigDecimal.ONE);
        }
        BigDecimal cpuHps = safeHashrate(deviceMapper.sumCpuHashrateByUserId(userId));
        BigDecimal gpuMh = safeHashrate(deviceMapper.sumGpuHashrateByUserId(userId));
        BigDecimal cpuMh = toMhFromHps(cpuHps);
        BigDecimal total = cpuMh.add(gpuMh);
        if (total.compareTo(BigDecimal.ZERO) <= 0) {
            return new CpuGpuRatio(BigDecimal.ONE, BigDecimal.ZERO);
        }
        // 用较高精度算比例，避免小数误差；最终金额再按 CAL/CNY 的 scale 处理
        BigDecimal cpuRatio = cpuMh.divide(total, 12, RoundingMode.HALF_UP);
        BigDecimal gpuRatio = BigDecimal.ONE.subtract(cpuRatio);
        return new CpuGpuRatio(cpuRatio, gpuRatio);
    }

    private String normalizeAlgorithm(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "";
        }
        String cleaned = raw.trim().toUpperCase(Locale.ROOT);
        cleaned = cleaned.replaceAll("[^A-Z0-9]+", "_");
        cleaned = cleaned.replaceAll("^_+|_+$", "");
        return cleaned;
    }

    private static class GpuAlgorithmRatio {
        final String algorithm;
        final BigDecimal ratio;

        private GpuAlgorithmRatio(String algorithm, BigDecimal ratio) {
            this.algorithm = algorithm;
            this.ratio = ratio;
        }
    }

    private List<GpuAlgorithmRatio> resolveGpuAlgorithmRatios(Long userId) {
        if (userId == null) {
            return List.of();
        }
        LocalDateTime cutoffTime = LocalDateTime.now().minusMinutes(deviceOfflineThresholdMinutes);
        List<GpuAlgorithmHashrateVo> stats =
                deviceGpuHashrateReportMapper.sumLatestHashrateByAlgorithm(userId, cutoffTime);
        if (stats == null || stats.isEmpty()) {
            return List.of();
        }
        BigDecimal total = BigDecimal.ZERO;
        for (GpuAlgorithmHashrateVo item : stats) {
            if (item != null && item.getTotalHashrate() != null && item.getTotalHashrate().compareTo(BigDecimal.ZERO) > 0) {
                total = total.add(item.getTotalHashrate());
            }
        }
        if (total.compareTo(BigDecimal.ZERO) <= 0) {
            return List.of();
        }
        List<GpuAlgorithmRatio> ratios = new ArrayList<>();
        for (GpuAlgorithmHashrateVo item : stats) {
            if (item == null || !StringUtils.hasText(item.getAlgorithm())
                    || item.getTotalHashrate() == null || item.getTotalHashrate().compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            BigDecimal ratio = item.getTotalHashrate().divide(total, 12, RoundingMode.HALF_UP);
            ratios.add(new GpuAlgorithmRatio(item.getAlgorithm(), ratio));
        }
        return ratios;
    }

    private BigDecimal safeHashrate(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private BigDecimal toMhFromHps(BigDecimal value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        return value.divide(BigDecimal.valueOf(1_000_000L), 6, RoundingMode.HALF_UP);
    }

    /**
     * 由于 settleCurrency 的展示逻辑依赖 platform_commissions.currency（按 source_earning_id 关联），
     * 拆分为 CPU/GPU 两条 earnings_history 后，若只给其中一条写平台佣金记录，
     * 另一条会因 COALESCE 默认值而显示成 CAL。
     *
     * 这里对“非 primary 的那条”补一条 0 金额的 platform_commissions 记录作为币种标记：
     * - 不影响平台佣金汇总（SUM(platform_commission_amount)）；
     * - 仅用于 settleCurrency 展示与 hourly 聚合中的 DISTINCT 计算。
     */
    private void recordSettlementCurrencyMarkerIfNeeded(MiningHistorySplitResult split, User user, String currency) {
        if (split == null || user == null) return;
        List<Long> markers = new ArrayList<>();
        if (split.cpuId != null && !split.cpuId.equals(split.primaryId)) {
            markers.add(split.cpuId);
        }
        if (split.gpuIds != null) {
            for (Long id : split.gpuIds) {
                if (id != null && !id.equals(split.primaryId)) {
                    markers.add(id);
                }
            }
        }
        if (markers.isEmpty()) return;
        for (Long other : markers) {
            recordPlatformCommission(other, user, BigDecimal.ZERO, BigDecimal.ZERO, currency);
        }
    }

    private void recordPlatformCommission(Long sourceEarningId,
                                          User user,
                                          BigDecimal originalAmount,
                                          BigDecimal platformAmount,
                                          String currency) {
        PlatformCommission commission = new PlatformCommission();
        commission.setSourceEarningId(sourceEarningId);
        commission.setUserId(user.getId());
        // 兜底填充 device_id，避免 NULL 约束
        commission.setDeviceId(Optional.ofNullable(user.getWorkerId()).orElse("POOL"));
        commission.setOriginalEarningAmount(originalAmount);
        commission.setPlatformRate(PLATFORM_RATE);
        commission.setPlatformCommissionAmount(platformAmount);
        commission.setCurrency(currency);
        platformCommissionMapper.insert(commission);
    }

    private void recordInvitationCommission(User earningUser,
                                            Long inviterId,
                                            MiningHistorySplitResult sourceSplit,
                                            BigDecimal inviteAmountCal,
                                            BigDecimal inviteAmountCny,
                                            String txHash,
                                            LocalDateTime earningTime) {
        if (earningUser == null || inviterId == null) {
            return;
        }
        if (inviteAmountCal == null || inviteAmountCal.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        BigDecimal inviterRate = inviteService.getCommissionRateForUser(inviterId);
        if (inviterRate == null) {
            inviterRate = BigDecimal.ZERO;
        }
        // 需求变更：邀请返佣暂不区分 CPU/GPU（避免未来 GPU 币种切换导致拆分口径漂移）。
        // 返佣统一按“CAL 等值”记一条 earnings_history（earning_type=INVITE），并写一条 commission_records。
        Long sourceEarningId = (sourceSplit != null) ? sourceSplit.primaryId : null;
        if (sourceEarningId == null) {
            // 极端兜底：没有源收益 ID 时不写 commission_records（避免脏数据），但仍允许写收益明细用于对账。
            // 这里保持静默，避免影响主结算链路；如需排障可再加日志。
        } else {
            insertCommissionRecord(inviterId, earningUser.getId(), sourceEarningId, null, inviteAmountCal, inviterRate);
        }

        BigDecimal cny = inviteAmountCny != null ? inviteAmountCny : BigDecimal.ZERO;
        Long inviteHistoryId = insertInviteEarningsHistory(inviterId, inviteAmountCal, cny, "INVITE", earningTime);
        LocalDate statDate = earningTime != null ? earningTime.toLocalDate() : java.time.LocalDate.now();
        earningsMapper.upsertDailyStats(inviterId, statDate, inviteAmountCal, cny, "INVITE");
        assetLedgerService.recordMiningPayout(
                inviterId,
                "CAL",
                calToXmr(inviteAmountCal),
                inviteAmountCal,
                inviteAmountCny,
                inviteHistoryId,
                txHash,
                "邀请佣金入账",
                earningTime
        );
    }

    private void insertCommissionRecord(Long inviterId,
                                        Long inviteeId,
                                        Long sourceEarningId,
                                        String sourceEarningType,
                                        BigDecimal commissionCal,
                                        BigDecimal commissionRate) {
        if (inviterId == null || inviteeId == null) return;
        if (commissionCal == null || commissionCal.compareTo(BigDecimal.ZERO) <= 0) return;
        if (sourceEarningId == null) return;
        com.slb.mining_backend.modules.invite.entity.CommissionRecord cr = new com.slb.mining_backend.modules.invite.entity.CommissionRecord();
        cr.setUserId(inviterId);
        cr.setInviteeId(inviteeId);
        cr.setSourceEarningId(sourceEarningId);
        cr.setSourceEarningType(sourceEarningType);
        cr.setCommissionAmount(commissionCal);
        cr.setCommissionRate(commissionRate != null ? commissionRate : BigDecimal.ZERO);
        commissionRecordMapper.insert(cr);
    }

    private Long insertInviteEarningsHistory(Long inviterId,
                                             BigDecimal amountCal,
                                             BigDecimal amountCny,
                                             String earningType,
                                             LocalDateTime earningTime) {
        EarningsHistory inviteHistory = new EarningsHistory();
        inviteHistory.setUserId(inviterId);
        // 邀请收益不绑定设备，使用固定 device_id 便于前端筛选/展示
        inviteHistory.setDeviceId("INVITE");
        inviteHistory.setBonusCalAmount(BigDecimal.ZERO);
        inviteHistory.setAmountCal(amountCal != null ? amountCal : BigDecimal.ZERO);
        // earnings_history.amount_cny 在部分库为 NOT NULL；兜底为 0 避免严格模式报错
        inviteHistory.setAmountCny(amountCny != null ? amountCny : BigDecimal.ZERO);
        // 邀请返佣统一记为 INVITE（不再拆 INVITE_CPU/INVITE_GPU）
        String normalized = (earningType == null || earningType.isBlank()) ? "INVITE" : earningType.trim().toUpperCase();
        // 兼容：即使上游误传 INVITE_CPU/INVITE_GPU，也统一落为 INVITE
        if ("INVITE_CPU".equals(normalized) || "INVITE_GPU".equals(normalized)) {
            normalized = "INVITE";
        }
        inviteHistory.setEarningType(normalized);
        inviteHistory.setEarningTime(earningTime);
        earningsHistoryMapper.insert(inviteHistory);
        return inviteHistory.getId();
    }
}
