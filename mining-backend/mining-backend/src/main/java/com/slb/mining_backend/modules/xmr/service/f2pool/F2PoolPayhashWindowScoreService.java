package com.slb.mining_backend.modules.xmr.service.f2pool;

import com.slb.mining_backend.modules.xmr.config.F2PoolPayhashTimeseriesProperties;
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

@Service
@Slf4j
public class F2PoolPayhashWindowScoreService {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final F2PoolPayhashTimeseriesProperties properties;

    public F2PoolPayhashWindowScoreService(NamedParameterJdbcTemplate jdbcTemplate,
                                           F2PoolPayhashTimeseriesProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    public List<WorkerPayhashScore> aggregate(String account,
                                              String coin,
                                              LocalDateTime windowStart,
                                              LocalDateTime windowEnd) {
        if (windowStart == null || windowEnd == null || !windowStart.isBefore(windowEnd)) {
            return Collections.emptyList();
        }
        String sql = """
                SELECT %s AS worker_id, SUM(%s) AS total_payhash
                FROM %s
                WHERE %s = :account
                  AND %s = :coin
                  AND %s >= :windowStart
                  AND %s < :windowEnd
                GROUP BY %s
                HAVING SUM(%s) > 0
                """.formatted(
                properties.getWorkerColumn(),
                properties.getPayhashColumn(),
                properties.getTable(),
                properties.getAccountColumn(),
                properties.getCoinColumn(),
                properties.getTimeColumn(),
                properties.getTimeColumn(),
                properties.getWorkerColumn(),
                properties.getPayhashColumn()
        );
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("account", account)
                .addValue("coin", coin)
                .addValue("windowStart", Timestamp.valueOf(windowStart))
                .addValue("windowEnd", Timestamp.valueOf(windowEnd));
        try {
            return jdbcTemplate.query(sql, params, (rs, rowNum) ->
                    new WorkerPayhashScore(
                            rs.getString("worker_id"),
                            rs.getBigDecimal("total_payhash")));
        } catch (DataAccessException ex) {
            log.warn("Failed to aggregate F2Pool payhash window: {}", ex.getMessage());
            return Collections.emptyList();
        }
    }
}
