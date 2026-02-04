package com.slb.mining_backend.modules.xmr.service.f2pool;

import com.slb.mining_backend.modules.xmr.config.F2PoolProperties;
import com.slb.mining_backend.modules.xmr.domain.F2PoolWorkerSample;
import com.slb.mining_backend.modules.xmr.entity.F2PoolWorkerSnapshot;
import com.slb.mining_backend.modules.xmr.mapper.F2PoolWorkerSnapshotMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

@Service
@Slf4j
public class F2PoolWorkerSyncService {

    private static final ZoneId BJT = ZoneId.of("Asia/Shanghai");
    private static final int MAX_WORKER_ID_LEN = 128;

    private final F2PoolProperties properties;
    private final F2PoolClient client;
    private final F2PoolParser parser;
    private final F2PoolPayloadService payloadService;
    private final WorkerOwnershipResolver ownershipResolver;
    private final F2PoolWorkerSnapshotMapper snapshotMapper;
    private final F2PoolPayhashWriter payhashWriter;
    private final F2PoolAlertService alertService;
    private final int deriveSeconds;
    private final boolean preferHashNowForPayhash;

    private record WorkerFetchResult(List<F2PoolWorkerSample> workers, String fingerprint) {
    }

    public F2PoolWorkerSyncService(F2PoolProperties properties,
                                   F2PoolClient client,
                                   F2PoolParser parser,
                                   F2PoolPayloadService payloadService,
                                   WorkerOwnershipResolver ownershipResolver,
                                   F2PoolWorkerSnapshotMapper snapshotMapper,
                                   F2PoolPayhashWriter payhashWriter,
                                   F2PoolAlertService alertService,
                                   @Value("${app.f2pool.payhash.derive-seconds:${app.payhash.derive-seconds:60}}") int deriveSeconds,
                                   @Value("${app.f2pool.payhash.prefer-hash-now-for-payhash:${app.payhash.prefer-hash-now-for-payhash:true}}") boolean preferHashNowForPayhash) {
        this.properties = properties;
        this.client = client;
        this.parser = parser;
        this.payloadService = payloadService;
        this.ownershipResolver = ownershipResolver;
        this.snapshotMapper = snapshotMapper;
        this.payhashWriter = payhashWriter;
        this.alertService = alertService;
        this.deriveSeconds = Math.max(1, deriveSeconds);
        this.preferHashNowForPayhash = preferHashNowForPayhash;
    }

    @Scheduled(fixedDelayString = "${app.f2pool.worker-sync-interval-ms:60000}")
    public void syncWorkers() {
        if (!properties.isEnabled()) {
            return;
        }
        if (!properties.isV2()) {
            log.warn("F2Pool worker sync skipped: apiVersion={} requires v2", properties.getApiVersion());
            return;
        }
        if (CollectionUtils.isEmpty(properties.getAccounts())) {
            return;
        }
        LocalDateTime bucketTime = floorToMinute(LocalDateTime.now(BJT));
        Instant now = Instant.now();
        for (F2PoolProperties.Account account : properties.getAccounts()) {
            if (account == null || !StringUtils.hasText(account.getName()) || !StringUtils.hasText(account.getCoin())) {
                continue;
            }
            WorkerFetchResult fetched;
            if (!properties.isHashrateCoinSupported(account.getCoin())) {
                log.info("F2Pool worker sync fallback: coin not supported for v2 hashrate, use account overview workers (account={}, coin={})",
                        account.getName(), account.getCoin());
                fetched = fetchWorkersFromAccountOverview(account);
            } else {
                fetched = fetchWorkersV2(account);
            }
            if (fetched == null || fetched.workers() == null || fetched.workers().isEmpty()) {
                continue;
            }
            List<F2PoolWorkerSample> workers = fetched.workers();
            String fingerprint = fetched.fingerprint();

            Set<String> workerIds = new HashSet<>();
            for (F2PoolWorkerSample sample : workers) {
                if (sample != null && StringUtils.hasText(sample.workerId())) {
                    workerIds.add(sample.workerId().trim());
                }
            }
            Map<String, Long> owners = ownershipResolver.resolveOwners(workerIds);

            List<F2PoolWorkerSnapshot> snapshots = new ArrayList<>();
            Map<String, Long> payhashByWorker = new HashMap<>();
            int unclaimed = 0;
            String sampleUnclaimed = null;

            for (F2PoolWorkerSample sample : workers) {
                if (sample == null || !StringUtils.hasText(sample.workerId())) {
                    continue;
                }
                String rawWorkerId = truncate(sample.workerId().trim(), MAX_WORKER_ID_LEN);
                Long userId = ownershipResolver.resolveUserId(rawWorkerId, owners);
                boolean stale = isStale(sample.lastShareAt(), now, properties.getWorkerStaleSeconds());
                String status = stale ? "STALE" : "ACTIVE";

                if (userId == null) {
                    unclaimed++;
                    if (sampleUnclaimed == null) {
                        sampleUnclaimed = rawWorkerId;
                    }
                }

                F2PoolWorkerSnapshot snapshot = new F2PoolWorkerSnapshot();
                snapshot.setAccount(account.getName());
                snapshot.setCoin(account.getCoin());
                snapshot.setWorkerId(rawWorkerId);
                snapshot.setUserId(userId);
                snapshot.setHashNowHps(BigDecimal.valueOf(sample.hashNowHps()));
                snapshot.setHashAvgHps(BigDecimal.valueOf(sample.hashAvgHps()));
                snapshot.setLastShareTime(toLocalDateTime(sample.lastShareAt()));
                snapshot.setBucketTime(bucketTime);
                snapshot.setStatus(status);
                snapshot.setPayloadFingerprint(fingerprint);
                snapshot.setCreatedTime(LocalDateTime.now(BJT));
                snapshots.add(snapshot);

                if (!stale) {
                    long payhash = estimatePayhash(sample);
                    if (payhash > 0) {
                        String key = (userId != null) ? ("USR-" + userId) : rawWorkerId;
                        key = truncate(key, MAX_WORKER_ID_LEN);
                        payhashByWorker.merge(key, payhash, (existing, value) -> existing + value);
                    }
                }
            }

            if (!snapshots.isEmpty()) {
                try {
                    snapshotMapper.insertBatch(snapshots);
                } catch (DataAccessException ex) {
                    log.warn("F2Pool worker snapshot insert failed (account={}, coin={}, error={})",
                            account.getName(), account.getCoin(), ex.getMessage());
                }
            }
            if (!payhashByWorker.isEmpty()) {
                payhashWriter.writeBucket(account.getName(), account.getCoin(), bucketTime, payhashByWorker);
            }
            if (unclaimed > 0) {
                alertService.raiseAlert(account.getName(), account.getCoin(), null,
                        "UNCLAIMED_WORKER", "WARN", sampleUnclaimed,
                        "unclaimed workers=" + unclaimed);
            }
        }
    }

    private WorkerFetchResult fetchWorkersV2(F2PoolProperties.Account account) {
        List<F2PoolWorkerSample> workers = new ArrayList<>();
        String fingerprint = null;
        int pageSize = Math.max(1, properties.getWorkersPageSize());
        int maxPages = Math.max(1, properties.getWorkersMaxPages());
        for (int page = 1; page <= maxPages; page++) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("currency", account.getCoin());
            payload.put("mining_user_name", account.getName());
            payload.put("page", page);
            payload.put("page_size", pageSize);
            F2PoolClient.F2PoolRawResponse response = client.fetchWorkersV2(account, payload);
            if (handleHttpError(account, response, "workers")) {
                break;
            }
            if (!StringUtils.hasText(response.body())) {
                alertService.raiseAlert(account.getName(), account.getCoin(), null,
                        "API_ERROR", "WARN", "workers", "empty worker response");
                break;
            }
            fingerprint = payloadService.persistPayload(
                    account.getName(),
                    account.getCoin(),
                    "workers",
                    response.body(),
                    response.fetchedAt());
            Optional<F2PoolParser.V2Error> v2Error = parser.detectV2Error(response.body());
            if (v2Error.isPresent()) {
                F2PoolParser.V2Error err = v2Error.get();
                alertService.raiseAlert(account.getName(), account.getCoin(), null,
                        "REMOTE_ERROR", "WARN", "workers",
                        "code=" + err.code() + ", msg=" + err.msg());
                break;
            }
            List<F2PoolWorkerSample> pageWorkers = parser.parseWorkersV2(response.body(), account);
            if (pageWorkers.isEmpty()) {
                java.util.OptionalInt count = parser.resolveWorkersCountV2(response.body());
                if (count.isPresent() && count.getAsInt() == 0) {
                    log.info("F2Pool workers empty (account={}, coin={}, page={})",
                            account.getName(), account.getCoin(), page);
                    break;
                }
                if (page == 1) {
                    alertService.raiseAlert(account.getName(), account.getCoin(), null,
                            "PARSE_FAILED", "WARN", "workers", "no worker samples parsed");
                }
                break;
            }
            workers.addAll(pageWorkers);
            if (pageWorkers.size() < pageSize) {
                break;
            }
        }
        return new WorkerFetchResult(workers, fingerprint);
    }

    private WorkerFetchResult fetchWorkersFromAccountOverview(F2PoolProperties.Account account) {
        F2PoolClient.F2PoolRawResponse response = client.fetchAccountOverview(account);
        if (handleHttpError(account, response, "account_overview")) {
            return new WorkerFetchResult(Collections.emptyList(), null);
        }
        if (response == null || !StringUtils.hasText(response.body())) {
            alertService.raiseAlert(account.getName(), account.getCoin(), null,
                    "API_ERROR", "WARN", "account_overview", "empty account overview response");
            return new WorkerFetchResult(Collections.emptyList(), null);
        }
        String fingerprint = payloadService.persistPayload(
                account.getName(),
                account.getCoin(),
                "account_overview_workers",
                response.body(),
                response.fetchedAt());
        List<F2PoolWorkerSample> workers = parser.parseWorkersFromAccountOverview(response.body(), account);
        if (workers.isEmpty()) {
            alertService.raiseAlert(account.getName(), account.getCoin(), null,
                    "PARSE_FAILED", "WARN", "account_overview", "no worker samples parsed");
        }
        return new WorkerFetchResult(workers, fingerprint);
    }

    private boolean isStale(Instant lastShareAt, Instant now, int staleSeconds) {
        if (staleSeconds <= 0 || lastShareAt == null || now == null) {
            return false;
        }
        long ageSec = Duration.between(lastShareAt, now).getSeconds();
        return ageSec > staleSeconds;
    }

    private long estimatePayhash(F2PoolWorkerSample worker) {
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
        return Math.round(hashrate * deriveSeconds);
    }

    private LocalDateTime toLocalDateTime(Instant instant) {
        if (instant == null) {
            return null;
        }
        return LocalDateTime.ofInstant(instant, BJT);
    }

    private LocalDateTime floorToMinute(LocalDateTime time) {
        return time.withSecond(0).withNano(0);
    }

    private String truncate(String value, int maxLen) {
        if (value == null || value.length() <= maxLen) {
            return value;
        }
        return value.substring(0, maxLen);
    }

    private boolean handleHttpError(F2PoolProperties.Account account, F2PoolClient.F2PoolRawResponse response, String refKey) {
        if (response == null || response.isSuccess()) {
            return false;
        }
        Integer status = response.statusCode();
        String alertType = classifyStatus(status);
        String message = buildStatusMessage(status, response.errorBody());
        alertService.raiseAlert(account.getName(), account.getCoin(), null,
                alertType, "WARN", refKey, message);
        return true;
    }

    private String classifyStatus(Integer status) {
        if (status == null) {
            return "REMOTE_ERROR";
        }
        if (status == 401 || status == 403) {
            return "AUTH_FAILED";
        }
        if (status == 429) {
            return "RATE_LIMITED";
        }
        if (status >= 500) {
            return "REMOTE_5XX";
        }
        if (status >= 400) {
            return "REMOTE_4XX";
        }
        return "REMOTE_ERROR";
    }

    private String buildStatusMessage(Integer status, String body) {
        String message = "status=" + (status != null ? status : "unknown");
        if (StringUtils.hasText(body)) {
            message += ", body=" + body;
        }
        return message;
    }
}
