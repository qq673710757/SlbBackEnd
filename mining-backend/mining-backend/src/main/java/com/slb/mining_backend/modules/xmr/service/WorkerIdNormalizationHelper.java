package com.slb.mining_backend.modules.xmr.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 统一的 workerId 归一化规则。
 *
 * 目前只做“去平台前缀”处理，避免误用 base 截断导致错误归属。
 */
@Component
public class WorkerIdNormalizationHelper {

    private final String stripPrefix;

    public WorkerIdNormalizationHelper(
            @Value("${app.worker-id.strip-prefix:suanlibao.}") String stripPrefix) {
        this.stripPrefix = stripPrefix;
    }

    public String stripPlatformPrefix(String rawWorkerId) {
        if (!StringUtils.hasText(rawWorkerId)) {
            return null;
        }
        String trimmed = rawWorkerId.trim();
        if (StringUtils.hasText(stripPrefix) && trimmed.startsWith(stripPrefix)) {
            String normalized = trimmed.substring(stripPrefix.length());
            return StringUtils.hasText(normalized) ? normalized : null;
        }
        return trimmed;
    }
}
