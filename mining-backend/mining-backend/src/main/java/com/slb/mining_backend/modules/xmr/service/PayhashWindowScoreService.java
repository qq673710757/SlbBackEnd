package com.slb.mining_backend.modules.xmr.service;

import com.slb.mining_backend.modules.xmr.config.PayhashTimeseriesProperties;
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
 * 从时间序列数据库聚合 payhash，并按 worker 维度返回窗口期得分。
 */
@Service
@Slf4j
public class PayhashWindowScoreService {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final PayhashTimeseriesProperties properties;

    public PayhashWindowScoreService(NamedParameterJdbcTemplate jdbcTemplate,
                                     PayhashTimeseriesProperties properties) {
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
                WHERE %s >= :windowStart AND %s < :windowEnd
                GROUP BY %s
                HAVING SUM(%s) > 0
                """.formatted(
                properties.getWorkerColumn(),
                properties.getPayhashColumn(),
                properties.getTable(),
                properties.getTimeColumn(),
                properties.getTimeColumn(),
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
            log.warn("聚合 payhash 窗口数据失败: {}", ex.getMessage());
            return Collections.emptyList();
        }
    }
}

