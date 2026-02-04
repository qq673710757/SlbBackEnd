package com.slb.mining_backend.modules.xmr.service.antpool;

import com.slb.mining_backend.modules.xmr.config.AntpoolPayhashTimeseriesProperties;
import com.slb.mining_backend.modules.xmr.dto.WorkerPayhashScore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

/**
 * 从 Antpool 独立 payhash 表中聚合窗口得分。
 */
@Service
@Slf4j
public class AntpoolPayhashWindowScoreService {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final AntpoolPayhashTimeseriesProperties properties;
    private static final int SNAPSHOT_MINUTES = 5;

    public AntpoolPayhashWindowScoreService(NamedParameterJdbcTemplate jdbcTemplate,
                                            AntpoolPayhashTimeseriesProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    public List<WorkerPayhashScore> aggregate(LocalDateTime windowStart, LocalDateTime windowEnd) {
        if (windowStart == null || windowEnd == null || !windowStart.isBefore(windowEnd)) {
            return Collections.emptyList();
        }
        String sql = """
                SELECT %s AS worker_id, SUM(%s) AS total_payhash
                FROM %s
                WHERE %s > :windowStart AND %s <= :windowEnd
                  AND MOD(MINUTE(%s), %d) = 0
                GROUP BY %s
                HAVING SUM(%s) > 0
                """.formatted(
                properties.getWorkerColumn(),
                properties.getPayhashColumn(),
                properties.getTable(),
                properties.getTimeColumn(),
                properties.getTimeColumn(),
                properties.getTimeColumn(),
                SNAPSHOT_MINUTES,
                properties.getWorkerColumn(),
                properties.getPayhashColumn()
        );
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("windowStart", Timestamp.valueOf(windowStart))
                .addValue("windowEnd", Timestamp.valueOf(windowEnd));
        try {
            return jdbcTemplate.query(sql, params, (rs, rowNum) ->
                    new WorkerPayhashScore(
                            rs.getString("worker_id"),
                            rs.getBigDecimal("total_payhash")));
        } catch (DataAccessException ex) {
            log.warn("Failed to aggregate Antpool payhash window: {}", ex.getMessage());
            return Collections.emptyList();
        }
    }

    public int countSnapshotBuckets(LocalDateTime windowStart, LocalDateTime windowEnd) {
        if (windowStart == null || windowEnd == null || !windowStart.isBefore(windowEnd)) {
            return 0;
        }
        String sql = """
                SELECT COUNT(DISTINCT %s) AS bucket_count
                FROM %s
                WHERE %s > :windowStart AND %s <= :windowEnd
                  AND MOD(MINUTE(%s), %d) = 0
                """.formatted(
                properties.getTimeColumn(),
                properties.getTable(),
                properties.getTimeColumn(),
                properties.getTimeColumn(),
                properties.getTimeColumn(),
                SNAPSHOT_MINUTES
        );
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("windowStart", Timestamp.valueOf(windowStart))
                .addValue("windowEnd", Timestamp.valueOf(windowEnd));
        try {
            Integer count = jdbcTemplate.queryForObject(sql, params, Integer.class);
            return count != null ? count : 0;
        } catch (DataAccessException ex) {
            log.warn("Failed to count Antpool snapshot buckets: {}", ex.getMessage());
            return 0;
        }
    }
}
