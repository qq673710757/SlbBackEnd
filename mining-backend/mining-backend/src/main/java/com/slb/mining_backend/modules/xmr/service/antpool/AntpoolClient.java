package com.slb.mining_backend.modules.xmr.service.antpool;

import com.google.common.util.concurrent.RateLimiter;
import com.slb.mining_backend.modules.xmr.config.AntpoolProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

@Component
@Slf4j
public class AntpoolClient {

    private final AntpoolProperties properties;
    private final WebClient http;
    private final RateLimiter limiter;

    public AntpoolClient(WebClient.Builder builder, AntpoolProperties properties) {
        this.properties = properties;
        this.http = builder
                .defaultHeader(HttpHeaders.USER_AGENT, "MiningBackend/AntpoolClient")
                .build();
        double permitsPerSecond = Math.max(0.1d, properties.getLimits().getPerHostQps());
        this.limiter = RateLimiter.create(permitsPerSecond);
    }

    public AntpoolRawResponse fetchWorkers(int page, int pageSize) {
        if (!StringUtils.hasText(properties.getUserId()) || !StringUtils.hasText(properties.getCoin())) {
            return AntpoolRawResponse.empty();
        }
        Map<String, String> params = new java.util.LinkedHashMap<>();
        params.put("coinType", properties.getCoin());
        params.put("userId", properties.getUserId());
        params.put("workerStatus", "0");
        params.put("page", String.valueOf(page));
        params.put("pageSize", String.valueOf(pageSize));
        return post("userWorkerList.htm", params);
    }

    public AntpoolRawResponse fetchAccountBalance() {
        if (!StringUtils.hasText(properties.getUserId()) || !StringUtils.hasText(properties.getCoin())) {
            return AntpoolRawResponse.empty();
        }
        Map<String, String> params = new java.util.LinkedHashMap<>();
        params.put("coin", properties.getCoin());
        params.put("userId", properties.getUserId());
        return post("account.htm", params);
    }

    public AntpoolRawResponse fetchPayouts(int page, int pageSize) {
        Map<String, String> params = new java.util.LinkedHashMap<>();
        if (StringUtils.hasText(properties.getCoin())) {
            params.put("coin", properties.getCoin());
        }
        params.put("type", "payout");
        params.put("pageEnable", "1");
        params.put("page", String.valueOf(page));
        params.put("pageSize", String.valueOf(pageSize));
        return post("paymentHistoryV2.htm", params);
    }

    public AntpoolRawResponse fetchCoinCalculator(String coinType, String hashInput, String networkDiff, String feePercent) {
        Map<String, String> params = new java.util.LinkedHashMap<>();
        if (StringUtils.hasText(coinType)) {
            params.put("coinType", coinType);
        }
        if (StringUtils.hasText(hashInput)) {
            params.put("hashInput", hashInput);
        }
        if (StringUtils.hasText(networkDiff)) {
            params.put("networkDiff", networkDiff);
        }
        if (StringUtils.hasText(feePercent)) {
            params.put("feePercent", feePercent);
        }
        return postCalculator("coinCalculator.htm", params);
    }

    private AntpoolRawResponse post(String endpoint, Map<String, String> extraParams) {
        if (!StringUtils.hasText(endpoint) || !StringUtils.hasText(properties.getApiKey())
                || !StringUtils.hasText(properties.getApiSecret())) {
            return AntpoolRawResponse.empty();
        }
        limiter.acquire();
        String base = StringUtils.hasText(properties.getBaseUrl()) ? properties.getBaseUrl().trim() : "";
        String normalizedBase = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        String normalizedEndpoint = endpoint.startsWith("/") ? endpoint : "/" + endpoint;
        String url = normalizedBase + normalizedEndpoint;
        long nonce = System.currentTimeMillis();
        String signUserId = resolveSignatureUserId();
        String signature = sign(signUserId, properties.getApiKey(), properties.getApiSecret(), nonce);
        Retry retry = Retry.backoff(Math.max(0, properties.getLimits().getMaxRetries()), Duration.ofMillis(200))
                .maxBackoff(Duration.ofSeconds(3))
                .filter(this::isRetryable);
        try {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("key", properties.getApiKey());
            form.add("nonce", String.valueOf(nonce));
            form.add("signature", signature);
            if (extraParams != null && !extraParams.isEmpty()) {
                extraParams.forEach(form::add);
            }
            String body = http.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(BodyInserters.fromFormData(form))
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofMillis(Math.max(1000L, properties.getLimits().getTimeoutMs())))
                    .retryWhen(retry)
                    .doOnError(err -> logRequestError(endpoint, err))
                    .onErrorResume(err -> Mono.empty())
                    .block();
            if (!StringUtils.hasText(body)) {
                return AntpoolRawResponse.empty();
            }
            return new AntpoolRawResponse(body, Instant.now());
        } catch (Exception ex) {
            logRequestError(endpoint, ex);
            return AntpoolRawResponse.empty();
        }
    }

    private AntpoolRawResponse postCalculator(String endpoint, Map<String, String> params) {
        if (!StringUtils.hasText(endpoint) || !StringUtils.hasText(properties.getApiKey())
                || !StringUtils.hasText(properties.getApiSecret())) {
            return AntpoolRawResponse.empty();
        }
        limiter.acquire();
        String base = StringUtils.hasText(properties.getBaseUrl()) ? properties.getBaseUrl().trim() : "";
        String normalizedBase = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        String normalizedEndpoint = endpoint.startsWith("/") ? endpoint : "/" + endpoint;
        String url = normalizedBase + normalizedEndpoint;
        long nonce = System.currentTimeMillis();
        String signUserId = resolveSignatureUserId();
        String signature = sign(signUserId, properties.getApiKey(), properties.getApiSecret(), nonce);
        Retry retry = Retry.backoff(Math.max(0, properties.getLimits().getMaxRetries()), Duration.ofMillis(200))
                .maxBackoff(Duration.ofSeconds(3))
                .filter(this::isRetryable);
        try {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("key", properties.getApiKey());
            form.add("nonce", String.valueOf(nonce));
            form.add("signature", signature);
            if (params != null && !params.isEmpty()) {
                params.forEach(form::add);
            }
            String body = http.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(BodyInserters.fromFormData(form))
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofMillis(Math.max(1000L, properties.getLimits().getTimeoutMs())))
                    .retryWhen(retry)
                    .doOnError(err -> logRequestError(endpoint, err))
                    .onErrorResume(err -> Mono.empty())
                    .block();
            if (!StringUtils.hasText(body)) {
                return AntpoolRawResponse.empty();
            }
            return new AntpoolRawResponse(body, Instant.now());
        } catch (Exception ex) {
            logRequestError(endpoint, ex);
            return AntpoolRawResponse.empty();
        }
    }

    private boolean isRetryable(Throwable err) {
        if (err instanceof WebClientResponseException wcre) {
            int status = wcre.getRawStatusCode();
            return status >= 500 || status == 429;
        }
        return true;
    }

    private void logRequestError(String endpoint, Throwable err) {
        Integer statusCode = null;
        if (err instanceof WebClientResponseException wcre) {
            statusCode = wcre.getRawStatusCode();
        } else if (err.getCause() instanceof WebClientResponseException wcre) {
            statusCode = wcre.getRawStatusCode();
        }
        log.warn("Antpool API request failed (endpoint={}, statusCode={}, message={})",
                endpoint, statusCode, err.getMessage());
    }

    private String resolveSignatureUserId() {
        if (StringUtils.hasText(properties.getUserId())) {
            return properties.getUserId().trim();
        }
        if (StringUtils.hasText(properties.getClientUserId())) {
            return properties.getClientUserId().trim();
        }
        if (StringUtils.hasText(properties.getClientId())) {
            return properties.getClientId().trim();
        }
        return "";
    }

    private String sign(String userId, String apiKey, String apiSecret, long nonce) {
        String message = String.valueOf(userId) + apiKey + nonce;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(apiSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format(Locale.ROOT, "%02X", b));
            }
            return sb.toString();
        } catch (Exception ex) {
            log.warn("Failed to sign Antpool request: {}", ex.getMessage());
            return "";
        }
    }

    public record AntpoolRawResponse(String body, Instant fetchedAt) {
        static AntpoolRawResponse empty() {
            return new AntpoolRawResponse(null, null);
        }
    }
}
