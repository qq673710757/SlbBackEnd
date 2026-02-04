package com.slb.mining_backend.modules.xmr.service;

import com.slb.mining_backend.modules.xmr.config.PayhashTimeseriesProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 周期性地把 Redis 中已完成的分钟桶写入 MySQL 分区表。
 */
@Service
@Slf4j
public class PayhashIngestionService {

    private static final String KEY_TEMPLATE = "pool:payhash:%d";
    private static final int MAX_BUCKET_LOOKBACK = 10;

    private final StringRedisTemplate redisTemplate;
    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final PayhashTimeseriesProperties properties;

    public PayhashIngestionService(StringRedisTemplate redisTemplate,
                                   NamedParameterJdbcTemplate jdbcTemplate,
                                   PayhashTimeseriesProperties properties) {
        this.redisTemplate = redisTemplate;
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    /**
     * 每 30 秒尝试冲刷上一分钟的数据，避免与正在写入的当前分钟冲突。
     */
    @Scheduled(fixedDelay = 30_000)
    public void flushPastBuckets() {
        long currentBucket = (System.currentTimeMillis() / 60_000) * 60_000;
        for (int i = 1; i <= MAX_BUCKET_LOOKBACK; i++) {
            long targetBucket = currentBucket - (i * 60_000L);
            if (targetBucket <= 0) {
                continue;
            }
            String redisKey = KEY_TEMPLATE.formatted(targetBucket);
            if (Boolean.TRUE.equals(redisTemplate.hasKey(redisKey))) {
                flushSingleBucket(targetBucket, redisKey);
            }
        }
    }

    private void flushSingleBucket(long bucketMillis, String redisKey) {
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(redisKey);
        if (entries.isEmpty()) {
            redisTemplate.delete(redisKey);
            return;
        }
        List<MapSqlParameterSource> batch = entries.entrySet().stream()
                .map(entry -> new MapSqlParameterSource()
                        .addValue("bucketTime", new Timestamp(bucketMillis))
                        .addValue("workerId", entry.getKey().toString())
                        .addValue("payhash", Long.parseLong(entry.getValue().toString())))
                .collect(Collectors.toList());

        String sql = """
                INSERT INTO %s (%s, %s, %s)
                VALUES (:bucketTime, :workerId, :payhash)
                ON DUPLICATE KEY UPDATE %s = %s + VALUES(%s)
                """.formatted(
                properties.getTable(),
                properties.getTimeColumn(),
                properties.getWorkerColumn(),
                properties.getPayhashColumn(),
                properties.getPayhashColumn(),
                properties.getPayhashColumn(),
                properties.getPayhashColumn());

        try {
            jdbcTemplate.batchUpdate(sql, batch.toArray(MapSqlParameterSource[]::new));
            redisTemplate.delete(redisKey);
            log.debug("Flushed [{}] miner stats records to DB for time {}", batch.size(), new Timestamp(bucketMillis));
        } catch (DataAccessException ex) {
            log.warn("Failed to persist payhash bucket {}: {}", bucketMillis, ex.getMessage());
        }
    }
}
