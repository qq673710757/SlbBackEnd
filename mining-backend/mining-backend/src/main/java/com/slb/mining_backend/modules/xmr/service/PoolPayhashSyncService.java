package com.slb.mining_backend.modules.xmr.service;

import com.slb.mining_backend.modules.xmr.config.PayhashTimeseriesProperties;
import com.slb.mining_backend.modules.xmr.domain.PoolClient;
import com.slb.mining_backend.modules.xmr.domain.PoolClientException;
import com.slb.mining_backend.modules.xmr.domain.WorkerHash;
import com.slb.mining_backend.modules.xmr.entity.XmrPoolStats;
import com.slb.mining_backend.modules.xmr.mapper.XmrPoolStatsMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 直接从矿池 API 采集 worker hashrate，并推断 payhash 写入时间序列表。
 */
@Service
@Slf4j
public class PoolPayhashSyncService {

    private final PoolClient poolClient;
    private final XmrPoolStatsMapper poolStatsMapper;
    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final PayhashTimeseriesProperties properties;
    private final int deriveSeconds;
    private final WorkerWhitelistService workerWhitelistService;
    /**
     * 是否允许从“原始矿池 workerId 文本”中解析 USR-<uid> 作为归属。
     *
     * 风险说明：
     * - 若开启且不做校验，攻击者可伪造 workerId=USR-17 造成收益错归属；
     * - 因此这里即使开启，也要求该 workerId（或其 base）必须在 Redis 白名单中（来源于 users.worker_id）。
     */
    private final boolean allowSyntheticUsrFromRawWorkerId;
    /**
     * payhash 估算时是否优先使用“当前算力 hashNowHps”。
     *
     * 背景：
     * - 多数矿池的 hashAvgHps 是一个滑动窗口平均值，停机后可能在较长时间内维持不为 0；
     * - 若用于收益分摊，会出现“用户停机了但仍持续计入 payhash”的现象；
     * - 因此默认优先用 hashNowHps（更贴近实时），hashNow 无数据时再回退 hashAvgHps。
     */
    private final boolean preferHashNowForPayhash;
    /**
     * worker 最后一次 share（lts）距离当前超过该阈值，则认为是“陈旧/离线”，不计入当分钟 payhash。
     *
     * 说明：
     * - C3Pool 的 allWorkers 会返回 inactiveWorkers，且其 hash/hash2 可能在停机后仍长时间维持非 0；
     * - 若不做 lts 过滤，会出现“明明停机了仍持续计入 payhash/收益分摊”的现象；
     * - 因此这里提供一层时间阈值，优先用 lts（最后 share）判断是否应计入。
     */
    private final int workerStaleSeconds;
    /**
     * miner_payhash_stats.worker_id 的长度上限（按常见表结构取保守值）。
     * 如你的表 worker_id 更长，可适当调大；如更短必须调小，否则会导致插入失败、进而触发“缺 payhash”兜底。
     */
    private static final int MAX_WORKER_ID_LEN = 64;
    /**
     * 连续“采集到 0 行”的次数。用于在日志里做降噪（避免每分钟刷屏），同时保留长期断档的可观测性。
     */
    private final AtomicInteger consecutiveEmptyRuns = new AtomicInteger(0);
    /**
     * 上游失败时的“短期缓存兜底”有效期（秒）。
     */
    private final int workerCacheTtlSeconds;
    private final Map<String, CachedWorkers> workerCache = new ConcurrentHashMap<>();

    private record FetchWorkersResult(List<WorkerHash> workers, boolean upstreamFailed, boolean usedCache, long cacheAgeSeconds) {
    }

    private record CachedWorkers(List<WorkerHash> workers, Instant fetchedAt) {
    }

    public PoolPayhashSyncService(PoolClient poolClient,
                                  XmrPoolStatsMapper poolStatsMapper,
                                  NamedParameterJdbcTemplate jdbcTemplate,
                                  PayhashTimeseriesProperties properties,
                                  WorkerWhitelistService workerWhitelistService,
                                  @Value("${app.payhash.derive-seconds:60}") int deriveSeconds,
                                  @Value("${app.payhash.allow-synthetic-usr-from-raw-worker-id:false}") boolean allowSyntheticUsrFromRawWorkerId,
                                  @Value("${app.payhash.prefer-hash-now-for-payhash:true}") boolean preferHashNowForPayhash,
                                  @Value("${app.payhash.worker-stale-seconds:600}") int workerStaleSeconds,
                                  @Value("${app.payhash.worker-cache-ttl-seconds:120}") int workerCacheTtlSeconds) {
        this.poolClient = poolClient;
        this.poolStatsMapper = poolStatsMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
        this.deriveSeconds = Math.max(1, deriveSeconds);
        this.workerWhitelistService = workerWhitelistService;
        this.allowSyntheticUsrFromRawWorkerId = allowSyntheticUsrFromRawWorkerId;
        this.preferHashNowForPayhash = preferHashNowForPayhash;
        this.workerStaleSeconds = Math.max(0, workerStaleSeconds);
        this.workerCacheTtlSeconds = Math.max(0, workerCacheTtlSeconds);
    }

    @Scheduled(fixedDelayString = "${app.payhash.sync-interval-ms:60000}")
    public void syncFromPool() {
        Instant now = Instant.now();
        List<XmrPoolStats> poolStats = poolStatsMapper.selectAll();
        if (CollectionUtils.isEmpty(poolStats)) {
            int n = consecutiveEmptyRuns.incrementAndGet();
            if (n == 1 || n % 60 == 0) {
                log.warn("PoolPayhashSyncService: xmr_pool_stats is empty, skip payhash sync (consecutiveEmptyRuns={})", n);
            }
            return;
        }

        // 重要：你的库结构允许“同一 subaddress 对应多个 user_id”（你已验证 du>1）。
        // 因此不能用 subaddress->userId 给所有 worker 强行加同一个 user 前缀，否则会错分收益。
        // 正确做法是：用 xmr_pool_stats.worker_id（每个用户唯一工人 ID）来反查 user_id。
        Map<String, Long> ownerByWorkerId = new HashMap<>();
        for (XmrPoolStats s : poolStats) {
            if (s == null || s.getUserId() == null || !StringUtils.hasText(s.getWorkerId())) {
                continue;
            }
            ownerByWorkerId.putIfAbsent(s.getWorkerId().trim(), s.getUserId());
        }
        // xmr_pool_stats 里可能存在“同一个 subaddress 多行重复”的历史数据。
        // payhash 采集按 subaddress 维度拉取 worker 列表即可，多次重复请求只会浪费配额并拉长单次任务耗时，进而造成分钟桶断档。
        Map<String, XmrPoolStats> uniqueBySubaddress = new LinkedHashMap<>();
        for (XmrPoolStats stats : poolStats) {
            if (stats == null || !StringUtils.hasText(stats.getSubaddress())) {
                continue;
            }
            uniqueBySubaddress.putIfAbsent(stats.getSubaddress(), stats);
        }
        List<XmrPoolStats> uniqueStats = new ArrayList<>(uniqueBySubaddress.values());
        if (uniqueStats.isEmpty()) {
            int n = consecutiveEmptyRuns.incrementAndGet();
            if (n == 1 || n % 60 == 0) {
                log.warn("PoolPayhashSyncService: no valid subaddress in xmr_pool_stats, skip payhash sync (totalRecords={}, consecutiveEmptyRuns={})",
                        poolStats.size(), n);
            }
            return;
        }
        long bucketMillis = floorToMinuteEpochMillis(System.currentTimeMillis());
        Timestamp bucketTime = new Timestamp(bucketMillis);

        // 全局聚合：同一分钟内同一用户（USR-<uid>）可能出现在多个 subaddress 的 worker 列表里，
        // 这里统一先聚合再落库，避免后续 INSERT 的 overwrite 语义导致丢数。
        Map<String, Long> aggregatedAll = new HashMap<>();
        List<MapSqlParameterSource> rows = new ArrayList<>();
        int emptyWorkersSubaddresses = 0;
        for (XmrPoolStats stats : uniqueStats) {
            FetchWorkersResult fetched = fetchWorkers(stats, now);
            List<WorkerHash> workers = fetched.workers();
            if (fetched.usedCache()) {
                log.warn("PoolPayhashSyncService: fallback to cached workers (subaddress={}, provider={}, cacheAgeSec={})",
                        stats != null ? stats.getSubaddress() : null, poolClient.name(), fetched.cacheAgeSeconds());
            }
            if (workers.isEmpty()) {
                emptyWorkersSubaddresses++;
                // 关键：区分“矿池真没 worker”（EMPTY_RESPONSE） vs “上游请求失败被吞/网络异常”（UPSTREAM_FAILED）
                // 这里用 WARN，方便你在生产用 grep 直接定位具体 subaddress 的失败模式。
                String subaddress = (stats != null ? stats.getSubaddress() : null);
                log.warn("PoolPayhashSyncService: collected 0 worker samples (subaddress={}, provider={}, reason={})",
                        subaddress, poolClient.name(), fetched.upstreamFailed() ? "UPSTREAM_FAILED" : "EMPTY_RESPONSE");
                continue;
            }
            for (WorkerHash worker : workers) {
                String rawWorkerId = (worker != null && StringUtils.hasText(worker.workerId()))
                        ? worker.workerId().trim()
                        : null;
                if (workerStaleSeconds > 0 && worker != null && worker.fetchedAt() != null) {
                    long ageSec = Duration.between(worker.fetchedAt(), now).getSeconds();
                    if (ageSec > workerStaleSeconds) {
                        log.debug("Skip stale worker sample (rawWorkerId={}, ageSec={}, workerStaleSeconds={})",
                                rawWorkerId, ageSec, workerStaleSeconds);
                        continue;
                    }
                }
                long payhash = estimatePayhash(worker);
                if (!StringUtils.hasText(rawWorkerId) || payhash <= 0) {
                    continue;
                }
                Long userId = resolveUserIdFromWorkerId(rawWorkerId, ownerByWorkerId);
                String key = (userId != null) ? ("USR-" + userId) : rawWorkerId;
                key = truncate(key, MAX_WORKER_ID_LEN);
                aggregatedAll.merge(key, payhash, Long::sum);
            }
        }

        for (Map.Entry<String, Long> e : aggregatedAll.entrySet()) {
            if (!StringUtils.hasText(e.getKey()) || e.getValue() == null || e.getValue() <= 0) {
                continue;
            }
            rows.add(new MapSqlParameterSource()
                    .addValue("bucketTime", bucketTime)
                    .addValue("workerId", e.getKey())
                    .addValue("payhash", e.getValue()));
        }
        if (rows.isEmpty()) {
            int n = consecutiveEmptyRuns.incrementAndGet();
            if (n == 1 || n % 60 == 0) {
                log.warn("PoolPayhashSyncService: collected 0 worker samples for bucket {} (subaddresses={}, emptyWorkersSubaddresses={}, consecutiveEmptyRuns={}). " +
                                "Likely causes: pool API returns empty/zero workers, subaddress misconfig, or transient network issues.",
                        bucketTime, uniqueStats.size(), emptyWorkersSubaddresses, n);
            }
            return;
        }
        consecutiveEmptyRuns.set(0);

        overwriteBucket(bucketTime);
        String sql = """
                INSERT INTO %s (%s, %s, %s)
                VALUES (:bucketTime, :workerId, :payhash)
                ON DUPLICATE KEY UPDATE %s = VALUES(%s)
                """.formatted(
                properties.getTable(),
                properties.getTimeColumn(),
                properties.getWorkerColumn(),
                properties.getPayhashColumn(),
                properties.getPayhashColumn(),
                properties.getPayhashColumn());
        jdbcTemplate.batchUpdate(sql, rows.toArray(MapSqlParameterSource[]::new));
        log.debug("Synced {} worker payhash samples for bucket {} (subaddresses={}, emptyWorkersSubaddresses={})",
                rows.size(), bucketTime, uniqueStats.size(), emptyWorkersSubaddresses);
    }

    private FetchWorkersResult fetchWorkers(XmrPoolStats stats, Instant now) {
        if (stats == null || !StringUtils.hasText(stats.getSubaddress())) {
            return new FetchWorkersResult(List.of(), false, false, -1L);
        }
        try {
            List<WorkerHash> workers = poolClient.fetchWorkers(stats.getSubaddress());
            List<WorkerHash> resolved = workers != null ? workers : List.of();
            if (!resolved.isEmpty()) {
                cacheWorkers(stats.getSubaddress(), resolved, now);
            }
            return new FetchWorkersResult(resolved, false, false, -1L);
        } catch (PoolClientException ex) {
            // 这里不重复打 ERROR 栈：NodejsPoolClient 已经会记录 url/status/message + stacktrace
            // 在此仅标记“上游失败”，让 syncFromPool 能把 0 样本归因到 UPSTREAM_FAILED。
            CachedWorkers cached = resolveCachedWorkers(stats.getSubaddress(), now);
            if (cached != null && cached.workers() != null && !cached.workers().isEmpty()) {
                long ageSec = Math.max(0, Duration.between(cached.fetchedAt(), now).getSeconds());
                return new FetchWorkersResult(cached.workers(), true, true, ageSec);
            }
            return new FetchWorkersResult(List.of(), true, false, -1L);
        }
    }

    private void cacheWorkers(String subaddress, List<WorkerHash> workers, Instant now) {
        if (!StringUtils.hasText(subaddress) || CollectionUtils.isEmpty(workers)) {
            return;
        }
        Instant ts = (now != null) ? now : Instant.now();
        workerCache.put(subaddress, new CachedWorkers(workers, ts));
    }

    private CachedWorkers resolveCachedWorkers(String subaddress, Instant now) {
        if (!StringUtils.hasText(subaddress) || workerCacheTtlSeconds <= 0) {
            return null;
        }
        CachedWorkers cached = workerCache.get(subaddress);
        if (cached == null || cached.workers() == null || cached.workers().isEmpty() || cached.fetchedAt() == null) {
            return null;
        }
        Instant base = (now != null) ? now : Instant.now();
        long ageSec = Duration.between(cached.fetchedAt(), base).getSeconds();
        if (ageSec > workerCacheTtlSeconds) {
            workerCache.remove(subaddress);
            return null;
        }
        return cached;
    }

    private String resolveWorkerId(XmrPoolStats stats, WorkerHash worker) {
        String rawWorkerId = null;
        if (worker != null && StringUtils.hasText(worker.workerId())) {
            rawWorkerId = worker.workerId().trim();
        } else if (stats != null && StringUtils.hasText(stats.getWorkerId())) {
            rawWorkerId = stats.getWorkerId().trim();
        }

        return StringUtils.hasText(rawWorkerId) ? rawWorkerId : null;
    }

    private Long resolveUserIdFromWorkerId(String rawWorkerId, Map<String, Long> ownerByWorkerId) {
        if (!StringUtils.hasText(rawWorkerId)) {
            return null;
        }
        if (ownerByWorkerId == null || ownerByWorkerId.isEmpty()) {
            // 没有任何权属映射时，不允许用“USR-<uid>”猜测归属（除非显式开启 + 白名单校验通过）
            return parseUserIdFromRawWorkerIdIfAllowed(rawWorkerId);
        }
        // 1) 原始 workerId 中出现 USR-<uid>：默认不信任（防蹭算力）；仅当开启开关且白名单校验通过才允许解析
        Long parsed = parseUserIdFromRawWorkerIdIfAllowed(rawWorkerId);
        if (parsed != null) return parsed;
        // 2) 精确匹配 xmr_pool_stats.worker_id
        Long exact = ownerByWorkerId.get(rawWorkerId);
        if (exact != null) {
            return exact;
        }
        // 3) 常见形态：<base>.<device> / <base>:<device>，取 base 再匹配
        String base = extractBaseWorkerId(rawWorkerId);
        if (base != null) {
            return ownerByWorkerId.get(base);
        }
        return null;
    }

    private Long parseUserIdFromRawWorkerIdIfAllowed(String rawWorkerId) {
        if (!allowSyntheticUsrFromRawWorkerId) {
            return null;
        }
        if (!StringUtils.hasText(rawWorkerId)) {
            return null;
        }
        String base = extractBaseWorkerId(rawWorkerId);
        String check = (base != null) ? base : rawWorkerId.trim();
        boolean whitelisted = workerWhitelistService != null && workerWhitelistService.isValid(check);
        if (!whitelisted) {
            // 重要：这里不打 ERROR，避免被恶意刷屏；需要排查时可临时调 DEBUG
            log.debug("Reject synthetic USR mapping from raw workerId={} (base={}, not in whitelist)", rawWorkerId, base);
            return null;
        }
        return parseSyntheticUserId(rawWorkerId);
    }

    private String extractBaseWorkerId(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String s = raw.trim();
        int dot = s.indexOf('.');
        int colon = s.indexOf(':');
        int slash = s.indexOf('/');
        int cut = -1;
        if (dot >= 0) cut = dot;
        if (colon >= 0) cut = (cut < 0) ? colon : Math.min(cut, colon);
        if (slash >= 0) cut = (cut < 0) ? slash : Math.min(cut, slash);
        if (cut <= 0) {
            return null;
        }
        return s.substring(0, cut);
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

    private boolean containsUserPrefix(String workerId, String expectedPrefix) {
        if (!StringUtils.hasText(workerId) || !StringUtils.hasText(expectedPrefix)) {
            return false;
        }
        String w = workerId.trim();
        String p = expectedPrefix.trim();
        // 常见形态：USR-123 / USR-123.xxx / xxxUSR-123yyy
        return w.contains(p) || w.contains(p.toLowerCase());
    }

    private String sanitizeSuffix(String raw) {
        // 保留可读性：把空白压缩成 '_'，避免拼接后出现不必要的空格
        String s = raw.trim().replaceAll("\\s+", "_");
        // 避免极端情况下出现超长/特殊字符：这里不做强清洗，只做长度截断（truncate 统一处理）
        return s;
    }

    private String truncate(String s, int maxLen) {
        if (s == null) {
            return null;
        }
        if (maxLen <= 0 || s.length() <= maxLen) {
            return s;
        }
        return s.substring(0, maxLen);
    }

    private long estimatePayhash(WorkerHash worker) {
        if (worker == null) {
            return 0L;
        }
        double hashrate;
        if (preferHashNowForPayhash) {
            hashrate = worker.hashNowHps() > 0 ? worker.hashNowHps() : worker.hashAvgHps();
        } else {
            hashrate = worker.hashAvgHps() > 0 ? worker.hashAvgHps() : worker.hashNowHps();
        }
        if (hashrate <= 0d) {
            return 0L;
        }
        double payhash = hashrate * deriveSeconds;
        return Math.round(payhash);
    }

    private void overwriteBucket(Timestamp bucketTime) {
        String sql = """
                DELETE FROM %s
                WHERE %s = :bucketTime
                """.formatted(properties.getTable(), properties.getTimeColumn());
        jdbcTemplate.update(sql, new MapSqlParameterSource("bucketTime", bucketTime));
    }

    private long floorToMinuteEpochMillis(long epochMillis) {
        return (epochMillis / 60_000) * 60_000;
    }
}


