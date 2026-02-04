package com.slb.mining_backend.modules.xmr.service.f2pool;

import com.slb.mining_backend.modules.users.dto.WorkerUserBinding;
import com.slb.mining_backend.modules.users.mapper.UserMapper;
import com.slb.mining_backend.modules.xmr.service.WorkerIdNormalizationHelper;
import com.slb.mining_backend.modules.xmr.service.WorkerWhitelistService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.*;

@Service
@Slf4j
public class WorkerOwnershipResolver {

    private static final String USER_PREFIX = "USR-";

    private final UserMapper userMapper;
    private final WorkerWhitelistService workerWhitelistService;
    private final WorkerIdNormalizationHelper normalizationHelper;
    private final F2PoolConfigBridge configBridge;

    public WorkerOwnershipResolver(UserMapper userMapper,
                                   WorkerWhitelistService workerWhitelistService,
                                   WorkerIdNormalizationHelper normalizationHelper,
                                   F2PoolConfigBridge configBridge) {
        this.userMapper = userMapper;
        this.workerWhitelistService = workerWhitelistService;
        this.normalizationHelper = normalizationHelper;
        this.configBridge = configBridge;
    }

    public Map<String, Long> resolveOwners(Collection<String> rawWorkerIds) {
        if (rawWorkerIds == null || rawWorkerIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Set<String> candidates = new HashSet<>();
        for (String raw : rawWorkerIds) {
            if (!StringUtils.hasText(raw)) {
                continue;
            }
            String trimmed = raw.trim();
            candidates.add(trimmed);
            String normalized = normalizationHelper.stripPlatformPrefix(trimmed);
            if (StringUtils.hasText(normalized)) {
                candidates.add(normalized);
            }
            String base = extractBaseWorkerId(trimmed);
            if (StringUtils.hasText(base)) {
                candidates.add(base);
            }
        }
        Map<String, Long> directMap = new HashMap<>();
        List<WorkerUserBinding> bindings = userMapper.selectByWorkerIds(new ArrayList<>(candidates));
        if (bindings != null) {
            for (WorkerUserBinding binding : bindings) {
                if (binding == null || binding.getUserId() == null || !StringUtils.hasText(binding.getWorkerId())) {
                    continue;
                }
                directMap.put(binding.getWorkerId().trim(), binding.getUserId());
            }
        }
        Map<String, Long> resolved = new HashMap<>();
        for (String raw : rawWorkerIds) {
            if (!StringUtils.hasText(raw)) {
                continue;
            }
            String trimmed = raw.trim();
            Long userId = resolveUserId(trimmed, directMap);
            if (userId != null) {
                resolved.put(trimmed, userId);
            }
        }
        return resolved;
    }

    public Long resolveUserId(String rawWorkerId, Map<String, Long> knownBindings) {
        if (!StringUtils.hasText(rawWorkerId)) {
            return null;
        }
        String trimmed = rawWorkerId.trim();

        String normalized = normalizationHelper.stripPlatformPrefix(trimmed);
        if (StringUtils.hasText(normalized) && !normalized.equals(trimmed)) {
            if (knownBindings != null) {
                Long exact = knownBindings.get(normalized);
                if (exact != null) {
                    return exact;
                }
            }
            return null;
        }

        Long parsed = parseUserIdFromRawWorkerIdIfAllowed(trimmed);
        if (parsed != null) {
            return parsed;
        }

        if (knownBindings != null) {
            Long exact = knownBindings.get(trimmed);
            if (exact != null) {
                return exact;
            }
            String base = extractBaseWorkerId(trimmed);
            if (StringUtils.hasText(base)) {
                Long baseOwner = knownBindings.get(base);
                if (baseOwner != null) {
                    return baseOwner;
                }
            }
        }
        return null;
    }

    private Long parseUserIdFromRawWorkerIdIfAllowed(String rawWorkerId) {
        if (!configBridge.isAllowSyntheticUsrFromRawWorkerId()) {
            return null;
        }
        String base = extractBaseWorkerId(rawWorkerId);
        String check = StringUtils.hasText(base) ? base : rawWorkerId;
        if (workerWhitelistService == null || !workerWhitelistService.isValid(check)) {
            log.debug("Reject synthetic USR mapping from raw workerId={} (base={}, not in whitelist)", rawWorkerId, base);
            return null;
        }
        return parseSyntheticUserId(rawWorkerId);
    }

    private String extractBaseWorkerId(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String s = raw.trim();
        int dot = s.indexOf('.');
        int colon = s.indexOf(':');
        int slash = s.indexOf('/');
        int cut = -1;
        if (dot >= 0) cut = dot;
        if (colon >= 0) cut = (cut < 0) ? colon : Math.min(cut, colon);
        if (slash >= 0) cut = (cut < 0) ? slash : Math.min(cut, slash);
        if (cut <= 0) {
            return null;
        }
        return s.substring(0, cut);
    }

    private Long parseSyntheticUserId(String workerId) {
        if (!StringUtils.hasText(workerId)) {
            return null;
        }
        String raw = workerId.trim();
        int idx = raw.indexOf(USER_PREFIX);
        if (idx < 0) {
            idx = raw.indexOf(USER_PREFIX.toLowerCase(Locale.ROOT));
        }
        if (idx < 0) {
            return null;
        }
        int start = idx + USER_PREFIX.length();
        int end = start;
        while (end < raw.length() && Character.isDigit(raw.charAt(end))) {
            end++;
        }
        if (end <= start) {
            return null;
        }
        try {
            return Long.parseLong(raw.substring(start, end));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
