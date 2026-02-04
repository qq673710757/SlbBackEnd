package com.slb.mining_backend.modules.xmr.service.f2pool;

import com.google.common.util.concurrent.RateLimiter;
import com.slb.mining_backend.modules.xmr.config.F2PoolProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.util.retry.Retry;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

@Component
@Slf4j
public class F2PoolClient {

    private final F2PoolProperties properties;
    private final WebClient http;
    private final RateLimiter limiter;
    private static final int ERROR_BODY_MAX = 300;

    public F2PoolClient(WebClient.Builder builder, F2PoolProperties properties) {
        this.properties = properties;
        this.http = builder
                .defaultHeader(HttpHeaders.USER_AGENT, "MiningBackend/F2PoolClient")
                .build();
        double permitsPerSecond = Math.max(0.1d, properties.getLimits().getPerHostQps());
        this.limiter = RateLimiter.create(permitsPerSecond);
    }

    public F2PoolRawResponse fetchWorkers(F2PoolProperties.Account account) {
        return fetch(account, properties.getEndpoints().getWorkers());
    }

    public F2PoolRawResponse fetchWorkersV2(F2PoolProperties.Account account, Map<String, Object> payload) {
        return postJson(account, properties.getEndpoints().getWorkers(), payload);
    }

    public F2PoolRawResponse fetchAccountOverview(F2PoolProperties.Account account) {
        return fetch(account, properties.getEndpoints().getAccount());
    }

    public F2PoolRawResponse fetchPayoutHistory(F2PoolProperties.Account account) {
        return fetch(account, properties.getEndpoints().getPayoutHistory());
    }

    public F2PoolRawResponse fetchPayoutHistoryV2(F2PoolProperties.Account account, Map<String, Object> payload) {
        return postJson(account, properties.getEndpoints().getPayoutHistory(), payload);
    }

    public F2PoolRawResponse fetchValueLastDay(F2PoolProperties.Account account) {
        return fetch(account, properties.getEndpoints().getValueLastDay());
    }

    public F2PoolRawResponse fetchAssetsBalanceV2(F2PoolProperties.Account account, Map<String, Object> payload) {
        return postJson(account, properties.getEndpoints().getAssetsBalance(), payload);
    }

    private F2PoolRawResponse fetch(F2PoolProperties.Account account, String endpointTemplate) {
        if (account == null || !StringUtils.hasText(account.getName()) || !StringUtils.hasText(endpointTemplate)) {
            return F2PoolRawResponse.empty();
        }
        limiter.acquire();
        URI uri = buildUri(account, endpointTemplate);
        Duration timeout = Duration.ofMillis(Math.max(1000L, properties.getLimits().getTimeoutMs()));
        Retry retry = Retry.backoff(Math.max(0, properties.getLimits().getMaxRetries()), Duration.ofMillis(200))
                .maxBackoff(Duration.ofSeconds(3))
                .filter(this::isRetryable);
        try {
            String body = http.get()
                    .uri(uri)
                    .headers(headers -> applyAuthHeaders(headers, account))
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(timeout)
                    .retryWhen(retry)
                    .block();
            return F2PoolRawResponse.success(body, Instant.now());
        } catch (WebClientResponseException ex) {
            logRequestError(endpointTemplate, ex);
            return F2PoolRawResponse.error(ex.getStatusCode().value(), trimBody(ex.getResponseBodyAsString()));
        } catch (Exception ex) {
            logRequestError(endpointTemplate, ex);
            return F2PoolRawResponse.error(null, trimBody(ex.getMessage()));
        }
    }

    private F2PoolRawResponse postJson(F2PoolProperties.Account account, String endpointTemplate, Map<String, Object> payload) {
        if (account == null || !StringUtils.hasText(account.getName()) || !StringUtils.hasText(endpointTemplate)) {
            return F2PoolRawResponse.empty();
        }
        limiter.acquire();
        URI uri = buildUri(account, endpointTemplate);
        Duration timeout = Duration.ofMillis(Math.max(1000L, properties.getLimits().getTimeoutMs()));
        Retry retry = Retry.backoff(Math.max(0, properties.getLimits().getMaxRetries()), Duration.ofMillis(200))
                .maxBackoff(Duration.ofSeconds(3))
                .filter(this::isRetryable);
        try {
            String body = http.post()
                    .uri(uri)
                    .headers(headers -> {
                        applyAuthHeaders(headers, account);
                        headers.setContentType(MediaType.APPLICATION_JSON);
                    })
                    .bodyValue(payload == null ? Map.of() : payload)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(timeout)
                    .retryWhen(retry)
                    .block();
            return F2PoolRawResponse.success(body, Instant.now());
        } catch (WebClientResponseException ex) {
            logRequestError(endpointTemplate, ex);
            return F2PoolRawResponse.error(ex.getStatusCode().value(), trimBody(ex.getResponseBodyAsString()));
        } catch (Exception ex) {
            logRequestError(endpointTemplate, ex);
            return F2PoolRawResponse.error(null, trimBody(ex.getMessage()));
        }
    }

    private void applyAuthHeaders(HttpHeaders headers, F2PoolProperties.Account account) {
        if (account == null) {
            return;
        }
        if (StringUtils.hasText(account.getApiKey())) {
            headers.add("X-API-KEY", account.getApiKey());
            headers.add("F2P-API-KEY", account.getApiKey());
        }
        if (StringUtils.hasText(account.getApiSecret())) {
            headers.add("X-API-SECRET", account.getApiSecret());
            headers.add("F2P-API-SECRET", account.getApiSecret());
        }
    }

    private URI buildUri(F2PoolProperties.Account account, String endpointTemplate) {
        String base = properties.getBaseUrl();
        String endpoint = resolveEndpoint(account, endpointTemplate);
        String full;
        if (StringUtils.hasText(endpoint) && endpoint.startsWith("http")) {
            full = endpoint;
        } else {
            if (!StringUtils.hasText(base)) {
                base = "";
            }
            String normalizedBase = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
            String normalizedEndpoint = endpoint.startsWith("/") ? endpoint : "/" + endpoint;
            full = normalizedBase + normalizedEndpoint;
        }
        String apiKey = account != null ? account.getApiKey() : null;
        String apiSecret = account != null ? account.getApiSecret() : null;
        if ((StringUtils.hasText(apiKey) || StringUtils.hasText(apiSecret)) && !full.contains("/v2/")) {
            StringBuilder sb = new StringBuilder(full);
            sb.append(full.contains("?") ? "&" : "?");
            if (StringUtils.hasText(apiKey)) {
                sb.append("api_key=").append(apiKey);
            }
            if (StringUtils.hasText(apiSecret)) {
                if (StringUtils.hasText(apiKey)) {
                    sb.append("&");
                }
                sb.append("api_secret=").append(apiSecret);
            }
            full = sb.toString();
        }
        return URI.create(full);
    }

    private String resolveEndpoint(F2PoolProperties.Account account, String template) {
        if (!StringUtils.hasText(template)) {
            return "";
        }
        String result = template;
        if (account != null) {
            if (StringUtils.hasText(account.getName())) {
                result = result.replace("{account}", account.getName());
            }
            if (StringUtils.hasText(account.getCoin())) {
                result = result.replace("{coin}", account.getCoin());
            }
        }
        return result;
    }

    private boolean isRetryable(Throwable err) {
        if (err instanceof WebClientResponseException wcre) {
            int status = wcre.getStatusCode().value();
            return status >= 500 || status == 429;
        }
        return true;
    }

    private void logRequestError(String endpoint, Throwable err) {
        Integer statusCode = null;
        if (err instanceof WebClientResponseException wcre) {
            statusCode = wcre.getStatusCode().value();
        } else if (err.getCause() instanceof WebClientResponseException wcre) {
            statusCode = wcre.getStatusCode().value();
        }
        log.warn("F2Pool API request failed (endpoint={}, statusCode={}, message={})",
                endpoint, statusCode, err.getMessage());
    }

    private String trimBody(String body) {
        if (!StringUtils.hasText(body)) {
            return null;
        }
        String trimmed = body.trim();
        if (trimmed.length() <= ERROR_BODY_MAX) {
            return trimmed;
        }
        return trimmed.substring(0, ERROR_BODY_MAX) + "...";
    }

    public record F2PoolRawResponse(String body, Instant fetchedAt, Integer statusCode, String errorBody) {
        static F2PoolRawResponse empty() {
            return new F2PoolRawResponse(null, null, null, null);
        }

        static F2PoolRawResponse success(String body, Instant fetchedAt) {
            return new F2PoolRawResponse(StringUtils.hasText(body) ? body : null,
                    fetchedAt != null ? fetchedAt : Instant.now(), 200, null);
        }

        static F2PoolRawResponse error(Integer statusCode, String errorBody) {
            return new F2PoolRawResponse(null, Instant.now(), statusCode, errorBody);
        }

        public boolean isSuccess() {
            return statusCode != null && statusCode >= 200 && statusCode < 300;
        }
    }
}
