package com.slb.mining_backend.modules.xmr.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;

/**
 * 将矿工上报的 difficulty 累积到 Redis，按分钟分桶，作为写入数据库之前的缓冲层。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ShareAggregatorService {

    private static final String KEY_TEMPLATE = "pool:payhash:%d";
    private static final Duration BUCKET_TTL = Duration.ofHours(1);

    private final StringRedisTemplate redisTemplate;
    private final WorkerWhitelistService whitelistService;

    /**
     * 把单次 share 累积到当前分钟的哈希桶中。
     */
    public void accumulateShare(String workerId, long difficulty) {
        if (!StringUtils.hasText(workerId) || difficulty <= 0) {
            return;
        }
        if (!whitelistService.isValid(workerId)) {
            log.debug("Drop share from invalid worker {}", workerId);
            return;
        }
        long bucketMillis = (System.currentTimeMillis() / 60_000) * 60_000;
        String key = KEY_TEMPLATE.formatted(bucketMillis);
        redisTemplate.opsForHash().increment(key, workerId, difficulty);
        redisTemplate.expire(key, BUCKET_TTL);
    }
}

