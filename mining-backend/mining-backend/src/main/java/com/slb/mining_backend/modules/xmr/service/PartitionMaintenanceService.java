package com.slb.mining_backend.modules.xmr.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 每日维护 miner_payhash_stats 的分区：
 * - 创建后天的新分区
 * - 删除 8 天前的旧分区，仅保留 7 天数据
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PartitionMaintenanceService {

    private static final DateTimeFormatter PARTITION_NAME = DateTimeFormatter.BASIC_ISO_DATE;

    private final JdbcTemplate jdbcTemplate;

    @Scheduled(cron = "0 30 2 * * ?")
    public void rotatePartitions() {
        LocalDate today = LocalDate.now();
        createFuturePartition(today.plusDays(2));
        dropOldPartition(today.minusDays(8));
    }

    private void createFuturePartition(LocalDate targetDay) {
        String partitionName = "p_" + PARTITION_NAME.format(targetDay);
        String upperBound = targetDay.plusDays(1).toString();
        String sql = """
                ALTER TABLE miner_payhash_stats
                REORGANIZE PARTITION p_future INTO (
                    PARTITION %s VALUES LESS THAN (TO_DAYS('%s')),
                    PARTITION p_future VALUES LESS THAN MAXVALUE
                )
                """.formatted(partitionName, upperBound);
        try {
            jdbcTemplate.execute(sql);
        } catch (Exception ex) {
            log.debug("Skip creating partition {}: {}", partitionName, ex.getMessage());
        }
    }

    private void dropOldPartition(LocalDate targetDay) {
        String partitionName = "p_" + PARTITION_NAME.format(targetDay);
        String sql = "ALTER TABLE miner_payhash_stats DROP PARTITION " + partitionName;
        try {
            jdbcTemplate.execute(sql);
        } catch (Exception ex) {
            log.debug("Skip dropping partition {}: {}", partitionName, ex.getMessage());
        }
    }
}

