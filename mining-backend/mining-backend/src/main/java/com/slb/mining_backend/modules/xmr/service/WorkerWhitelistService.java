package com.slb.mining_backend.modules.xmr.service;

import com.slb.mining_backend.modules.users.mapper.UserMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 将数据库中的有效 workerId 刷新到 Redis set，供 Share 写入校验使用。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorkerWhitelistService {

    private static final String KEY_VALID_WORKERS = "pool:valid_workers";

    private final UserMapper userMapper;
    private final StringRedisTemplate redisTemplate;

    @PostConstruct
    public void init() {
        log.info("System startup: synchronizing worker whitelist...");
        refreshWhitelist();
    }

    @Scheduled(fixedDelayString = "PT5M")
    public void refreshWhitelist() {
        List<String> workerIds = userMapper.selectActiveWorkerIds();
        redisTemplate.delete(KEY_VALID_WORKERS);
        if (!CollectionUtils.isEmpty(workerIds)) {
            redisTemplate.opsForSet().add(KEY_VALID_WORKERS, workerIds.toArray(String[]::new));
        }
        log.info("Refreshed worker whitelist, size={}", workerIds.size());
    }

    public boolean isValid(String workerId) {
        if (!StringUtils.hasText(workerId)) {
            return false;
        }
        return Boolean.TRUE.equals(redisTemplate.opsForSet().isMember(KEY_VALID_WORKERS, workerId));
    }
}
