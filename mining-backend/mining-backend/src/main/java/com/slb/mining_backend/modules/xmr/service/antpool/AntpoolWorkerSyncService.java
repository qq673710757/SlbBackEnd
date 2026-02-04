package com.slb.mining_backend.modules.xmr.service.antpool;

import com.slb.mining_backend.modules.users.dto.WorkerUserBinding;
import com.slb.mining_backend.modules.users.mapper.UserMapper;
import com.slb.mining_backend.modules.xmr.config.AntpoolProperties;
import com.slb.mining_backend.modules.xmr.config.AntpoolPayhashTimeseriesProperties;
import com.slb.mining_backend.modules.xmr.entity.XmrWorkerHashSnapshot;
import com.slb.mining_backend.modules.xmr.mapper.XmrWorkerHashSnapshotMapper;
import com.slb.mining_backend.modules.xmr.service.WorkerIdNormalizationHelper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class AntpoolWorkerSyncService {

    private static final ZoneId BJT = ZoneId.of("Asia/Shanghai");
    private static final int MAX_WORKER_ID_LEN = 128;

    private final AntpoolProperties properties;
    private final AntpoolClient client;
    private final AntpoolParser parser;
    private final WorkerIdNormalizationHelper normalizationHelper;
    private final UserMapper userMapper;
    private final XmrWorkerHashSnapshotMapper snapshotMapper;
    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final AntpoolPayhashTimeseriesProperties payhashProperties;
    private final int deriveSeconds;
    private final AntpoolSyncStatus syncStatus;

    public AntpoolWorkerSyncService(AntpoolProperties properties,
                                    AntpoolClient client,
                                    AntpoolParser parser,
                                    WorkerIdNormalizationHelper normalizationHelper,
                                    UserMapper userMapper,
                                    XmrWorkerHashSnapshotMapper snapshotMapper,
                                    NamedParameterJdbcTemplate jdbcTemplate,
                                    AntpoolPayhashTimeseriesProperties payhashProperties,
                                    AntpoolSyncStatus syncStatus,
                                    @Value("${app.payhash.derive-seconds:60}") int deriveSeconds) {
        this.properties = properties;
        this.client = client;
        this.parser = parser;
        this.normalizationHelper = normalizationHelper;
        this.userMapper = userMapper;
        this.snapshotMapper = snapshotMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.payhashProperties = payhashProperties;
        this.syncStatus = syncStatus;
        this.deriveSeconds = Math.max(1, deriveSeconds);
    }

    @Scheduled(fixedDelayString = "${app.antpool.worker-sync-interval-ms:60000}")
    public void syncWorkers() {
        if (!properties.isEnabled()) {
            return;
        }
        LocalDateTime bucketTime = floorToFiveMinutes(LocalDateTime.now(BJT));
        int page = 1;
        int totalPages = Integer.MAX_VALUE;
        List<XmrWorkerHashSnapshot> snapshots = new ArrayList<>();
        List<MapSqlParameterSource> payhashRows = new ArrayList<>();
        Map<String, Long> ownerByWorkerId = new HashMap<>();
        boolean apiEmptyResponse = false;
        boolean parsedEmpty = false;

        while (page <= totalPages) {
            AntpoolClient.AntpoolRawResponse response = client.fetchWorkers(page, properties.getWorkerPageSize());
            if (!StringUtils.hasText(response.body())) {
                log.warn("Antpool workers empty response (page={})", page);
                apiEmptyResponse = true;
                break;
            }
            AntpoolParser.ParsedWorkers parsed = parser.parseWorkers(response.body());
            if (parsed == null) {
                parsedEmpty = true;
                break;
            }
            List<AntpoolParser.WorkerItem> items = parsed.items();
            if (items.isEmpty()) {
                parsedEmpty = true;
                break;
            }
            if (parsed.totalPages() > 0) {
                totalPages = parsed.totalPages();
            }
            if (ownerByWorkerId.isEmpty()) {
                ownerByWorkerId = loadOwners(items);
            }
            LocalDateTime reportedAt = LocalDateTime.now(BJT);
            for (AntpoolParser.WorkerItem item : items) {
                if (item == null || !StringUtils.hasText(item.workerId())) {
                    continue;
                }
                String normalized = normalizationHelper.stripPlatformPrefix(item.workerId());
                if (!StringUtils.hasText(normalized)) {
                    log.warn("Antpool workerId normalized empty (rawWorkerId={})", item.workerId());
                    continue;
                }
                normalized = truncate(normalized, MAX_WORKER_ID_LEN);
                BigDecimal hashrateMhs = toMhs(item.last10m());
                if (hashrateMhs == null || hashrateMhs.compareTo(BigDecimal.ZERO) <= 0) {
                    continue;
                }
                long payhash = Math.round(hashrateMhs.doubleValue() * deriveSeconds);
                if (payhash <= 0) {
                    continue;
                }
                payhashRows.add(new MapSqlParameterSource()
                        .addValue("bucketTime", Timestamp.valueOf(bucketTime))
                        .addValue("workerId", normalized)
                        .addValue("payhash", payhash));

                XmrWorkerHashSnapshot snapshot = new XmrWorkerHashSnapshot();
                snapshot.setUserId(ownerByWorkerId.get(normalized));
                snapshot.setWorkerId(normalized);
                snapshot.setHashNowHps(hashrateMhs);
                snapshot.setHashAvgHps(hashrateMhs);
                snapshot.setReportedAt(reportedAt);
                snapshots.add(snapshot);
            }
            page++;
        }

        if (!snapshots.isEmpty()) {
            snapshotMapper.insertBatch(snapshots);
        }
        if (!payhashRows.isEmpty()) {
            String sql = """
                    INSERT INTO %s (%s, %s, %s)
                    VALUES (:bucketTime, :workerId, :payhash)
                    ON DUPLICATE KEY UPDATE %s = VALUES(%s)
                    """.formatted(
                    payhashProperties.getTable(),
                    payhashProperties.getTimeColumn(),
                    payhashProperties.getWorkerColumn(),
                    payhashProperties.getPayhashColumn(),
                    payhashProperties.getPayhashColumn(),
                    payhashProperties.getPayhashColumn());
            jdbcTemplate.batchUpdate(sql, payhashRows.toArray(MapSqlParameterSource[]::new));
        }

        if (syncStatus != null) {
            String status;
            String detail = "snapshots=" + snapshots.size() + ", payhashRows=" + payhashRows.size();
            if (!snapshots.isEmpty() || !payhashRows.isEmpty()) {
                status = "OK";
            } else if (apiEmptyResponse) {
                status = "API_EMPTY_RESPONSE";
            } else if (parsedEmpty) {
                status = "NO_WORKERS";
            } else {
                status = "NO_DATA";
            }
            syncStatus.recordWorkerSync(status, detail);
        }
    }

    private Map<String, Long> loadOwners(List<AntpoolParser.WorkerItem> items) {
        List<String> workerIds = new ArrayList<>();
        for (AntpoolParser.WorkerItem item : items) {
            if (item != null && StringUtils.hasText(item.workerId())) {
                String normalized = normalizationHelper.stripPlatformPrefix(item.workerId());
                if (StringUtils.hasText(normalized)) {
                    workerIds.add(truncate(normalized, MAX_WORKER_ID_LEN));
                }
            }
        }
        if (workerIds.isEmpty()) {
            return new HashMap<>();
        }
        List<WorkerUserBinding> bindings = userMapper.selectByWorkerIds(workerIds);
        if (CollectionUtils.isEmpty(bindings)) {
            return new HashMap<>();
        }
        Map<String, Long> owners = new HashMap<>();
        for (WorkerUserBinding binding : bindings) {
            if (binding != null && binding.getUserId() != null && StringUtils.hasText(binding.getWorkerId())) {
                owners.put(binding.getWorkerId().trim(), binding.getUserId());
            }
        }
        return owners;
    }

    private LocalDateTime floorToFiveMinutes(LocalDateTime time) {
        if (time == null) {
            return null;
        }
        int minute = time.getMinute();
        int floored = (minute / 5) * 5;
        return time.withMinute(floored).withSecond(0).withNano(0);
    }

    private BigDecimal toMhs(BigDecimal last10m) {
        if (last10m == null) {
            return null;
        }
        // 统一口径：MH/s
        return last10m;
    }

    private String truncate(String value, int maxLen) {
        if (value == null || value.length() <= maxLen) {
            return value;
        }
        return value.substring(0, maxLen);
    }
}
