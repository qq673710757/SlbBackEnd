package com.slb.mining_backend.modules.xmr.service;

import com.google.common.util.concurrent.RateLimiter;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.slb.mining_backend.modules.xmr.config.XmrPoolProperties;
import com.slb.mining_backend.modules.xmr.domain.PoolClient;
import com.slb.mining_backend.modules.xmr.domain.PoolClientException;
import com.slb.mining_backend.modules.xmr.domain.PoolPayment;
import com.slb.mining_backend.modules.xmr.domain.PoolStats;
import com.slb.mining_backend.modules.xmr.domain.WorkerHash;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.util.retry.Retry;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Component
@Slf4j
public class NodejsPoolClient implements PoolClient {

    private final XmrPoolProperties.Provider provider;
    private final WebClient http;
    private final RateLimiter limiter;
    private final Duration requestTimeout;
    private final int requestMaxRetries;
    private final long retryBackoffMs;
    private final long retryMaxBackoffMs;

    public NodejsPoolClient(
            WebClient.Builder builder,
            XmrPoolProperties properties,
            @Value("${app.external-api.pool-stats-timeout-ms:5000}") long poolStatsTimeoutMs,
            @Value("${app.xmr.pool.request-max-retries:2}") int requestMaxRetries,
            @Value("${app.xmr.pool.request-retry-backoff-ms:200}") long retryBackoffMs,
            @Value("${app.xmr.pool.request-retry-max-backoff-ms:3000}") long retryMaxBackoffMs
    ) {
        this.provider = properties.getDefaultProvider();
        this.http = builder
                .defaultHeader(HttpHeaders.USER_AGENT, "MiningBackend/NodejsPoolClient")
                .build();
        double permitsPerSecond = Math.max(0.1d, provider.getLimits().getPerHostReqPer15Min() / 900.0d);
        this.limiter = RateLimiter.create(permitsPerSecond);
        this.requestTimeout = Duration.ofMillis(Math.max(1000L, poolStatsTimeoutMs));
        this.requestMaxRetries = Math.max(0, requestMaxRetries);
        this.retryBackoffMs = Math.max(50L, retryBackoffMs);
        this.retryMaxBackoffMs = Math.max(this.retryBackoffMs, retryMaxBackoffMs);
    }

    @Override
    public Optional<PoolStats> fetchStats(String address) throws PoolClientException {
        String endpoint = provider.getEndpoints().getStats();
        if (!StringUtils.hasText(endpoint)) {
            throw new PoolClientException("Stats endpoint not configured for provider " + provider.getId());
        }
        String json = execute(endpoint, address);
        if (!StringUtils.hasText(json)) {
            return Optional.empty();
        }
        try {
            DocumentContext ctx = JsonPath.parse(json);
            Long unpaid = readLong(ctx, provider.getMapping().getUnpaidAtomic());
            Long paid = readLong(ctx, provider.getMapping().getPaidTotalAtomic());
            Double hash = readDouble(ctx, provider.getMapping().getHashrateHps());
            Instant ts = readInstant(ctx, provider.getMapping().getTimestamp()).orElse(Instant.now());
            if (unpaid == null || paid == null) {
                return Optional.empty();
            }
            return Optional.of(new PoolStats(address, unpaid, paid, hash != null ? hash : 0d, ts));
        } catch (Exception ex) {
            throw new PoolClientException("Failed to parse stats response", ex);
        }
    }

    @Override
    public List<WorkerHash> fetchWorkers(String address) throws PoolClientException {
        String endpoint = provider.getEndpoints().getWorkers();
        if (!StringUtils.hasText(endpoint)) {
            return Collections.emptyList();
        }
        String json = execute(endpoint, address);
        if (!StringUtils.hasText(json)) {
            return Collections.emptyList();
        }
        try {
            DocumentContext ctx = JsonPath.parse(json);
            String workerArrayPath = provider.getMapping().getWorkerArray();
            if (!StringUtils.hasText(workerArrayPath)) {
                return Collections.emptyList();
            }

            Object raw;
            try {
                raw = ctx.read(workerArrayPath);
            } catch (com.jayway.jsonpath.PathNotFoundException e) {
                // If the path doesn't exist (e.g. no workers yet), return empty list instead of erroring
                return Collections.emptyList();
            }

            // C3Pool: allWorkers 返回结构通常是 { activeWorkers: {...}, inactiveWorkers: {...} }
            // 只采 activeWorkers 会在“全部暂时 inactive”时导致 0 样本，从而引发 payhash 断档与结算 MISSING_PAYHASH。
            // 因此这里把 inactiveWorkers 一并合并（按 workerId 去重，取 fetchedAt 更新的那条）。
            List<WorkerHash> workers = new ArrayList<>(parseWorkersRaw(address, raw));
            Object rawInactive = null;
            try {
                rawInactive = ctx.read("$.inactiveWorkers");
            } catch (com.jayway.jsonpath.PathNotFoundException ignored) {
                // ignore
            }
            if (rawInactive != null) {
                workers.addAll(parseWorkersRaw(address, rawInactive));
            }
            return dedupeWorkersByIdKeepLatest(workers);
        } catch (Exception ex) {
            throw new PoolClientException("Failed to parse worker response", ex);
        }
    }

    private List<WorkerHash> parseWorkersRaw(String address, Object raw) {
        if (raw instanceof List<?> list) {
            if (list.isEmpty()) {
                return Collections.emptyList();
            }
            return list.stream()
                    .map(node -> mapWorker(address, node, null))
                    .flatMap(Optional::stream)
                    .collect(Collectors.toList());
        } else if (raw instanceof Map<?, ?> map) {
            if (map.isEmpty()) {
                return Collections.emptyList();
            }
            // C3Pool/MoneroOcean: workerId 往往在 Map 的 key 上，而 value 里不一定有显式的 id/name 字段
            return map.entrySet().stream()
                    .map(entry -> mapWorker(address, entry.getValue(), entry.getKey() != null ? entry.getKey().toString() : null))
                    .flatMap(Optional::stream)
                    .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }

    private List<WorkerHash> dedupeWorkersByIdKeepLatest(List<WorkerHash> workers) {
        if (workers == null || workers.isEmpty()) {
            return Collections.emptyList();
        }
        Map<String, WorkerHash> merged = new LinkedHashMap<>();
        for (WorkerHash w : workers) {
            if (w == null || !StringUtils.hasText(w.workerId())) {
                continue;
            }
            merged.merge(w.workerId(), w, (a, b) -> {
                if (a.fetchedAt() == null) return b;
                if (b.fetchedAt() == null) return a;
                return a.fetchedAt().isAfter(b.fetchedAt()) ? a : b;
            });
        }
        return new ArrayList<>(merged.values());
    }

    @Override
    public List<PoolPayment> fetchPayments(String address) throws PoolClientException {
        String endpoint = provider.getEndpoints().getPayments();
        if (!StringUtils.hasText(endpoint)) {
            return Collections.emptyList();
        }
        String json = execute(endpoint, address);
        if (!StringUtils.hasText(json)) {
            return Collections.emptyList();
        }
        try {
            DocumentContext ctx = JsonPath.parse(json);
            String arrayPath = provider.getMapping().getPaymentArray();
            Object raw;
            if (StringUtils.hasText(arrayPath)) {
                raw = ctx.read(arrayPath);
            } else {
                raw = ctx.json();
            }
            List<Object> nodes;
            if (raw instanceof List<?> list) {
                nodes = new ArrayList<>(list);
            } else if (raw instanceof Map<?, ?> map) {
                nodes = map.values().stream().collect(Collectors.toList());
            } else {
                nodes = List.of(raw);
            }
            return nodes.stream()
                    .map(node -> mapPayment(address, node))
                    .flatMap(Optional::stream)
                    .collect(Collectors.toList());
        } catch (Exception ex) {
            throw new PoolClientException("Failed to parse payments response", ex);
        }
    }

    @Override
    public String name() {
        return provider.getId();
    }

    private Optional<WorkerHash> mapWorker(String address, Object node, String fallbackWorkerId) {
        try {
            DocumentContext ctx = JsonPath.parse(node);
            String workerId = readString(ctx, provider.getMapping().getWorkerId());
            if (!StringUtils.hasText(workerId) && StringUtils.hasText(fallbackWorkerId)) {
                workerId = fallbackWorkerId;
            }
            if (!StringUtils.hasText(workerId)) {
                return Optional.empty();
            }
            double hashNow = Optional.ofNullable(readDouble(ctx, provider.getMapping().getWorkerHashNowHps())).orElse(0d);
            double hashAvg = Optional.ofNullable(readDouble(ctx, provider.getMapping().getWorkerHashAvgHps())).orElse(0d);
            Instant ts = readInstant(ctx, provider.getMapping().getWorkerTimestamp()).orElse(Instant.now());
            return Optional.of(new WorkerHash(address, workerId, hashNow, hashAvg, ts));
        } catch (Exception ex) {
            log.debug("Failed to parse worker node for address {}: {}", address, ex.getMessage());
            return Optional.empty();
        }
    }

    private Optional<PoolPayment> mapPayment(String address, Object node) {
        try {
            DocumentContext ctx = JsonPath.parse(node);
            Long amountAtomic = readLong(ctx, provider.getMapping().getPaymentAmount());
            if ((amountAtomic == null || amountAtomic <= 0) && StringUtils.hasText(provider.getMapping().getPaymentAmount())) {
                Double amountXmr = readDouble(ctx, provider.getMapping().getPaymentAmount());
                if (amountXmr != null && amountXmr > 0d) {
                    amountAtomic = toAtomic(amountXmr);
                }
            }
            String txHash = readString(ctx, provider.getMapping().getPaymentHash());
            if (amountAtomic == null || amountAtomic <= 0 || !StringUtils.hasText(txHash)) {
                return Optional.empty();
            }
            Long height = readLong(ctx, provider.getMapping().getPaymentHeight());
            Instant ts = readInstant(ctx, provider.getMapping().getPaymentTimestamp()).orElse(Instant.now());
            return Optional.of(new PoolPayment(address, amountAtomic, txHash, height, ts));
        } catch (Exception ex) {
            log.debug("Failed to parse payment node for address {}: {}", address, ex.getMessage());
            return Optional.empty();
        }
    }

    private String execute(String template, String address) {
        limiter.acquire();
        String url = resolveUrl(template, address);
        Retry retry = Retry.backoff(requestMaxRetries, Duration.ofMillis(retryBackoffMs))
                .maxBackoff(Duration.ofMillis(retryMaxBackoffMs))
                .filter(this::isRetryable);
        try {
            return http.get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(requestTimeout)
                    .retryWhen(retry)
                    // 关键：不要吞掉异常，否则上游会把“请求失败”误判为“矿池无 worker”，最终导致 payhash 断档。
                    .doOnError(err -> logPoolRequestError(url, err))
                    .onErrorMap(err -> (err instanceof PoolClientException)
                            ? err
                            : new PoolClientException("Pool request failed: " + url, err))
                    .block();
        } catch (PoolClientException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new PoolClientException("Failed to call pool endpoint " + url, ex);
        }
    }

    private boolean isRetryable(Throwable err) {
        if (err instanceof WebClientResponseException wcre) {
            int status = wcre.getStatusCode().value();
            return status >= 500 || status == 429;
        }
        Throwable cause = err.getCause();
        if (cause instanceof WebClientResponseException wcre) {
            int status = wcre.getStatusCode().value();
            return status >= 500 || status == 429;
        }
        return true;
    }

    private void logPoolRequestError(String url, Throwable err) {
        Integer statusCode = null;
        if (err instanceof WebClientResponseException wcre) {
            statusCode = wcre.getStatusCode().value();
        } else if (err.getCause() instanceof WebClientResponseException wcre) {
            statusCode = wcre.getStatusCode().value();
        }
        log.error("NodejsPoolClient request failed (provider={}, url={}, statusCode={}, message={})",
                provider.getId(), url, statusCode, err.getMessage(), err);
    }

    private String resolveUrl(String template, String address) {
        String encoded = URLEncoder.encode(address, StandardCharsets.UTF_8);
        String replaced = template.replace("${address}", encoded);
        if (replaced.startsWith("http")) {
            return replaced;
        }
        String base = provider.getBase();
        if (!base.endsWith("/") && !replaced.startsWith("/")) {
            return base + '/' + replaced;
        }
        if (base.endsWith("/") && replaced.startsWith("/")) {
            return base + replaced.substring(1);
        }
        return base + replaced;
    }

    private Long readLong(DocumentContext ctx, String path) {
        if (!StringUtils.hasText(path)) {
            return null;
        }
        try {
            Object value = ctx.read(path);
            if (value instanceof Number number) {
                return number.longValue();
            }
            if (value instanceof String str && StringUtils.hasText(str)) {
                return Long.parseLong(str.trim());
            }
            return null;
        } catch (Exception ex) {
            return null;
        }
    }

    private Double readDouble(DocumentContext ctx, String path) {
        if (!StringUtils.hasText(path)) {
            return null;
        }
        try {
            Object value = ctx.read(path);
            if (value instanceof Number number) {
                return number.doubleValue();
            }
            if (value instanceof String str && StringUtils.hasText(str)) {
                return Double.parseDouble(str.trim());
            }
            return null;
        } catch (Exception ex) {
            return null;
        }
    }

    private String readString(DocumentContext ctx, String path) {
        if (!StringUtils.hasText(path)) {
            return null;
        }
        try {
            Object value = ctx.read(path);
            if (value == null) {
                return null;
            }
            if (value instanceof String str) {
                return str;
            }
            return String.valueOf(value);
        } catch (Exception ex) {
            return null;
        }
    }

    private Optional<Instant> readInstant(DocumentContext ctx, String path) {
        if (!StringUtils.hasText(path)) {
            return Optional.empty();
        }
        try {
            Object value = ctx.read(path);
            if (value == null) {
                return Optional.empty();
            }
            long epoch;
            if (value instanceof Number number) {
                epoch = number.longValue();
            } else if (value instanceof String str && StringUtils.hasText(str)) {
                epoch = Long.parseLong(str.trim());
            } else {
                return Optional.empty();
            }
            if (Math.abs(epoch) > 10_000_000_000L) {
                return Optional.of(Instant.ofEpochMilli(epoch));
            }
            return Optional.of(Instant.ofEpochSecond(epoch));
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    private long toAtomic(double amountXmr) {
        return BigDecimal.valueOf(amountXmr)
                .multiply(BigDecimal.valueOf(provider.getUnit().getAtomicPerXmr()))
                .setScale(0, RoundingMode.HALF_UP)
                .longValue();
    }
}
