package com.slb.mining_backend.modules.xmr.service;

import com.slb.mining_backend.common.exception.BizException;
import com.slb.mining_backend.modules.exchange.service.ExchangeRateService;
import com.slb.mining_backend.modules.users.entity.User;
import com.slb.mining_backend.modules.users.mapper.UserMapper;
import com.slb.mining_backend.modules.xmr.domain.PoolClient;
import com.slb.mining_backend.modules.xmr.domain.PoolStats;
import com.slb.mining_backend.modules.xmr.domain.WorkerHash;
import com.slb.mining_backend.modules.xmr.entity.XmrPoolStats;
import com.slb.mining_backend.modules.xmr.mapper.XmrPoolStatsMapper;
import com.slb.mining_backend.modules.xmr.mapper.XmrWalletIncomingMapper;
import com.slb.mining_backend.modules.xmr.vo.WorkerHashrateVo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@Slf4j
public class XmrPoolStatsService {

    private final XmrPoolStatsMapper statsMapper;
    private final UserMapper userMapper;
    private final PoolClient poolClient;
    private final XmrWalletIncomingMapper walletIncomingMapper;
    private final XmrWorkerHashSnapshotService workerHashSnapshotService;
    private final XmrWorkerEarningsService workerEarningsService;
    private final ExchangeRateService exchangeRateService;
    private final long atomicPerXmr;
    /**
     * worker 最后一次 share（lts）距离当前超过该阈值，则认为“陈旧/离线”，不参与 delta 分摊权重。
     *
     * 背景：
     * - C3Pool 的 allWorkers 会返回 inactiveWorkers，且其 hash/hash2 可能在停机后仍长时间维持非 0；
     * - distributeToWorkers 目前优先用 hashAvg，会放大“停机后仍被分摊”的现象；
     * - 因此用 lts（最后 share）加一层阈值过滤。
     */
    private final int workerStaleSeconds;

    public XmrPoolStatsService(XmrPoolStatsMapper statsMapper,
                               UserMapper userMapper,
                               PoolClient poolClient,
                               XmrWalletIncomingMapper walletIncomingMapper,
                               XmrWorkerHashSnapshotService workerHashSnapshotService,
                               XmrWorkerEarningsService workerEarningsService,
                               ExchangeRateService exchangeRateService,
                               com.slb.mining_backend.modules.xmr.config.XmrPoolProperties properties,
                               @Value("${app.xmr.pool.worker-stale-seconds:600}") int workerStaleSeconds) {
        this.statsMapper = statsMapper;
        this.userMapper = userMapper;
        this.poolClient = poolClient;
        this.walletIncomingMapper = walletIncomingMapper;
        this.workerHashSnapshotService = workerHashSnapshotService;
        this.workerEarningsService = workerEarningsService;
        this.exchangeRateService = exchangeRateService;
        this.atomicPerXmr = properties.getDefaultProvider().getUnit().getAtomicPerXmr();
        this.workerStaleSeconds = Math.max(0, workerStaleSeconds);
    }

    @Scheduled(fixedRate = 90_000)
    @Transactional
    public void refreshPoolStats() {
        List<XmrPoolStats> records = statsMapper.selectAll();
        // 同一母地址只处理一次，避免重复分配
        Map<String, XmrPoolStats> uniqueBySubaddress = new HashMap<>();
        for (XmrPoolStats record : records) {
            if (record.getSubaddress() == null) {
                continue;
            }
            uniqueBySubaddress.putIfAbsent(record.getSubaddress(), record);
        }
        Collection<XmrPoolStats> uniqueRecords = uniqueBySubaddress.values();
        for (XmrPoolStats record : uniqueRecords) {
            try {
                processRecord(record);
            } catch (Exception ex) {
                log.warn("Failed to refresh pool stats for user {}: {}", record.getUserId(), ex.getMessage());
            }
        }
    }

    private void processRecord(XmrPoolStats record) {
        Optional<PoolStats> statsOptional = poolClient.fetchStats(record.getSubaddress());
        if (statsOptional.isEmpty()) {
            return;
        }
        PoolStats stats = statsOptional.get();

        long previousPaidAtomic = toAtomic(record.getPaidXmrTotal());
        long previousUnpaidAtomic = toAtomic(record.getUnpaidXmr());
        long newPaidAtomic = Math.max(stats.paidTotalAtomic(), previousPaidAtomic);
        long newUnpaidAtomic = stats.unpaidAtomic();

        long deltaAtomic = Math.max(0L, (newPaidAtomic - previousPaidAtomic) + (newUnpaidAtomic - previousUnpaidAtomic));


        List<WorkerHash> workerHashes = poolClient.fetchWorkers(record.getSubaddress());
        Map<String, Long> ownerMap = resolveWorkerOwners(workerHashes);
        writeSnapshotsPerOwner(record.getUserId(), ownerMap, workerHashes);
        if (deltaAtomic > 0 && !workerHashes.isEmpty()) {
            distributeToWorkers(record.getUserId(), ownerMap, workerHashes, deltaAtomic, record.getLastUpdateTime(), stats.fetchedAt());
        }

        record.setLastHashrate((long) stats.hashrateHps());
        record.setUnpaidXmr(toXmr(newUnpaidAtomic));
        record.setPaidXmrTotal(toXmr(newPaidAtomic));
        record.setSource(poolClient.name());
        record.setLastUpdateTime(LocalDateTime.ofInstant(stats.fetchedAt(), ZoneOffset.UTC));
        statsMapper.update(record);

        BigDecimal totalEarned = toXmr(newPaidAtomic + newUnpaidAtomic);
        updateUserTotals(record.getUserId(), totalEarned);
        sanityCheckWallet(record.getUserId(), toXmr(newPaidAtomic));
    }

    public WorkerHashrateVo getRealtimeHashrate(Long userId) {
        XmrPoolStats record = statsMapper.selectByUserId(userId)
                .orElseThrow(() -> new BizException("未找到该用户的矿池算力记录"));

        Optional<PoolStats> statsOptional;
        try {
            statsOptional = poolClient.fetchStats(record.getSubaddress());
        } catch (Exception ex) {
            log.warn("Failed to fetch realtime pool stats for user {}: {}", userId, ex.getMessage());
            statsOptional = Optional.empty();
        }

        Double fallbackHashrate = record.getLastHashrate() != null ? record.getLastHashrate().doubleValue() : 0d;
        double realtimeHashrate = statsOptional.map(PoolStats::hashrateHps).orElse(fallbackHashrate);
        BigDecimal hashrateDecimal = BigDecimal.valueOf(realtimeHashrate).setScale(2, RoundingMode.HALF_UP);

        LocalDateTime fetchedAt = statsOptional
                .map(stats -> LocalDateTime.ofInstant(stats.fetchedAt(), ZoneOffset.UTC))
                .orElse(record.getLastUpdateTime() != null ? record.getLastUpdateTime() : LocalDateTime.now());

        BigDecimal xmrToCny = safe(exchangeRateService.getXmrToCnyRate());

        WorkerHashrateVo vo = new WorkerHashrateVo();
        vo.setUserId(record.getUserId());
        vo.setWorkerId(record.getWorkerId());
        vo.setHashrateHps(hashrateDecimal);
        vo.setXmrToCnyRate(xmrToCny.setScale(2, RoundingMode.HALF_UP));
        vo.setFetchedAt(fetchedAt);
        vo.setSource(statsOptional.isPresent() ? poolClient.name() : record.getSource());
        return vo;
    }

    private void distributeToWorkers(Long defaultUserId,
                                     Map<String, Long> ownerMap,
                                     List<WorkerHash> workers,
                                     long deltaAtomic,
                                     LocalDateTime previousUpdate,
                                     Instant fetchedAt) {
        LocalDateTime windowEnd = LocalDateTime.ofInstant(fetchedAt, ZoneOffset.UTC);
        LocalDateTime windowStart = previousUpdate != null ? previousUpdate : windowEnd.minusSeconds(90);
        long durationSeconds = Math.max(60L, Duration.between(windowStart, windowEnd).getSeconds());

        List<WorkerShare> shares = new ArrayList<>();
        double totalWeight = 0d;
        Instant now = Instant.now();
        for (WorkerHash worker : workers) {
            if (workerStaleSeconds > 0 && worker != null && worker.fetchedAt() != null) {
                long ageSec = Duration.between(worker.fetchedAt(), now).getSeconds();
                if (ageSec > workerStaleSeconds) {
                    continue;
                }
            }
            // 这里更贴近“实时停机即停止计入”的口径：优先 hashNow，其次 hashAvg
            double h = worker.hashNowHps() > 0 ? worker.hashNowHps() : worker.hashAvgHps();
            double weight = Math.max(0d, h) * durationSeconds;
            if (weight <= 0d) {
                continue;
            }
            WorkerShare share = new WorkerShare(worker.workerId(), weight);
            shares.add(share);
            totalWeight += weight;
        }
        if (shares.isEmpty() || totalWeight <= 0d) {
            return;
        }

        long allocated = 0L;
        for (WorkerShare share : shares) {
            double portion = deltaAtomic * (share.weight / totalWeight);
            share.assignedAtomic = (long) Math.floor(portion);
            share.fraction = portion - share.assignedAtomic;
            allocated += share.assignedAtomic;
        }
        long remaining = deltaAtomic - allocated;
        shares.sort(Comparator.comparingDouble((WorkerShare s) -> s.fraction).reversed());
        for (int i = 0; i < shares.size() && remaining > 0; i++) {
            shares.get(i).assignedAtomic += 1;
            remaining--;
        }

        LocalDateTime windowStartFinal = windowStart;
        LocalDateTime windowEndFinal = windowEnd;
        shares.stream()
                .filter(share -> share.assignedAtomic > 0)
                .forEach(share -> {
                    // Resolve real user ID from Worker ID
                    Long realUserId = ownerMap.getOrDefault(share.workerId, defaultUserId);
                    if (realUserId == null) {
                        realUserId = defaultUserId;
                    }

                    workerEarningsService.recordShare(
                            realUserId,
                            share.workerId,
                            share.assignedAtomic,
                            windowStartFinal,
                            windowEndFinal);
                });
    }

    private void sanityCheckWallet(Long userId, BigDecimal paidFromPool) {
        try {
            BigDecimal walletPaid = safe(walletIncomingMapper.sumAmountByUserId(userId));
            if (paidFromPool.subtract(walletPaid).abs().compareTo(BigDecimal.valueOf(0.01)) > 0) {
                log.debug("Pool paid {} XMR for user {} but wallet records show {}", paidFromPool, userId, walletPaid);
            }
        } catch (Exception ex) {
            log.debug("Wallet cross-check failed for user {}: {}", userId, ex.getMessage());
        }
    }

    private void updateUserTotals(Long userId, BigDecimal totalEarned) {
        try {
            User user = new User();
            user.setId(userId);
            user.setTotalEarnedXmr(totalEarned);
            userMapper.update(user);
        } catch (Exception ex) {
            log.debug("Failed to persist total earned XMR for user {}: {}", userId, ex.getMessage());
        }
    }

    private long toAtomic(BigDecimal value) {
        if (value == null) {
            return 0L;
        }
        return value.multiply(BigDecimal.valueOf(atomicPerXmr))
                .setScale(0, RoundingMode.HALF_UP)
                .longValue();
    }

    private BigDecimal toXmr(long atomic) {
        return BigDecimal.valueOf(atomic)
                .divide(BigDecimal.valueOf(atomicPerXmr), 12, RoundingMode.HALF_UP);
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private Map<String, Long> resolveWorkerOwners(List<WorkerHash> workers) {
        List<String> workerIds = workers.stream()
                .map(WorkerHash::workerId)
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
        if (workerIds.isEmpty()) {
            return Map.of();
        }
        return userMapper.selectByWorkerIds(workerIds).stream()
                .filter(binding -> binding.getWorkerId() != null && binding.getUserId() != null)
                .collect(Collectors.toMap(
                        binding -> binding.getWorkerId(),
                        binding -> binding.getUserId(),
                        (a, b) -> a
                ));
    }

    private void writeSnapshotsPerOwner(Long fallbackUserId,
                                        Map<String, Long> ownerMap,
                                        List<WorkerHash> workerHashes) {
        if (workerHashes == null || workerHashes.isEmpty()) {
            return;
        }
        Map<Long, List<WorkerHash>> grouped = workerHashes.stream()
                .collect(Collectors.groupingBy(hash -> {
                    Long owner = ownerMap.get(hash.workerId());
                    return owner != null ? owner : fallbackUserId;
                }));
        grouped.forEach((userId, hashes) -> workerHashSnapshotService.replaceSnapshot(userId, null, hashes));
    }

    private static final class WorkerShare {
        private final String workerId;
        private final double weight;
        private long assignedAtomic;
        private double fraction;

        private WorkerShare(String workerId, double weight) {
            this.workerId = workerId;
            this.weight = weight;
        }
    }
}
