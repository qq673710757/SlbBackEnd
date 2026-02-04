package com.slb.mining_backend.modules.xmr.service.f2pool;

import com.slb.mining_backend.modules.xmr.entity.F2PoolRawPayload;
import com.slb.mining_backend.modules.xmr.mapper.F2PoolRawPayloadMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

@Service
public class F2PoolPayloadService {

    private static final ZoneId BJT = ZoneId.of("Asia/Shanghai");
    private final F2PoolRawPayloadMapper rawPayloadMapper;

    public F2PoolPayloadService(F2PoolRawPayloadMapper rawPayloadMapper) {
        this.rawPayloadMapper = rawPayloadMapper;
    }

    public String persistPayload(String account, String coin, String endpoint, String payload, Instant fetchedAt) {
        if (!StringUtils.hasText(payload)) {
            return null;
        }
        String fingerprint = sha256Hex(payload);
        F2PoolRawPayload record = new F2PoolRawPayload();
        record.setAccount(account);
        record.setCoin(coin);
        record.setEndpoint(endpoint);
        record.setFingerprint(fingerprint);
        record.setPayload(payload);
        record.setFetchedAt(toLocalDateTime(fetchedAt));
        record.setCreatedTime(LocalDateTime.now(BJT));
        rawPayloadMapper.insertIgnore(record);
        return fingerprint;
    }

    private LocalDateTime toLocalDateTime(Instant instant) {
        if (instant == null) {
            return LocalDateTime.now(BJT);
        }
        return LocalDateTime.ofInstant(instant, BJT);
    }

    private static String sha256Hex(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to hash payload", ex);
        }
    }
}
