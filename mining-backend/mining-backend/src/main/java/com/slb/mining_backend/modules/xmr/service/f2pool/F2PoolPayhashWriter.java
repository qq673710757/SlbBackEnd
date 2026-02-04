package com.slb.mining_backend.modules.xmr.service.f2pool;

import com.slb.mining_backend.modules.xmr.config.F2PoolPayhashTimeseriesProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class F2PoolPayhashWriter {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final F2PoolPayhashTimeseriesProperties properties;

    public F2PoolPayhashWriter(NamedParameterJdbcTemplate jdbcTemplate,
                               F2PoolPayhashTimeseriesProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    public void writeBucket(String account, String coin, LocalDateTime bucketTime, Map<String, Long> payhashByWorker) {
        if (!StringUtils.hasText(account) || !StringUtils.hasText(coin) || bucketTime == null
                || payhashByWorker == null || payhashByWorker.isEmpty()) {
            return;
        }
        List<MapSqlParameterSource> rows = new ArrayList<>();
        for (Map.Entry<String, Long> entry : payhashByWorker.entrySet()) {
            if (!StringUtils.hasText(entry.getKey()) || entry.getValue() == null || entry.getValue() <= 0) {
                continue;
            }
            rows.add(new MapSqlParameterSource()
                    .addValue("account", account)
                    .addValue("coin", coin)
                    .addValue("bucketTime", Timestamp.valueOf(bucketTime))
                    .addValue("workerId", entry.getKey())
                    .addValue("payhash", entry.getValue()));
        }
        if (rows.isEmpty()) {
            return;
        }
        String sql = """
                INSERT INTO %s (%s, %s, %s, %s, %s)
                VALUES (:account, :coin, :bucketTime, :workerId, :payhash)
                ON DUPLICATE KEY UPDATE %s = VALUES(%s)
                """.formatted(
                properties.getTable(),
                properties.getAccountColumn(),
                properties.getCoinColumn(),
                properties.getTimeColumn(),
                properties.getWorkerColumn(),
                properties.getPayhashColumn(),
                properties.getPayhashColumn(),
                properties.getPayhashColumn());
        jdbcTemplate.batchUpdate(sql, rows.toArray(MapSqlParameterSource[]::new));
        log.debug("F2Pool payhash persisted: account={}, coin={}, bucket={}, samples={}",
                account, coin, bucketTime, rows.size());
    }
}
