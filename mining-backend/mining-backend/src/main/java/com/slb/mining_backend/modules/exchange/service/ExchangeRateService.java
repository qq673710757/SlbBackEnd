package com.slb.mining_backend.modules.exchange.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.slb.mining_backend.modules.exchange.entity.ExchangeRate;
import com.slb.mining_backend.modules.exchange.mapper.ExchangeRateMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Map;

/**
 * 服务：定时刷新并缓存 XMR、USDT 等汇率。
 *
 * 本服务会周期性调用第三方公开 API 获取 XMR 对 CNY、USD 的价格，以及 USDT 对 CNY 的价格。
 * 如调用失败，将保留最近一次成功获取的值。每次刷新也会将最新汇率快照存入数据库。
 */
@Service
@Slf4j
public class ExchangeRateService {

    private final ExchangeRateMapper exchangeRateMapper;
    private final WebClient webClient;

    // 缓存的价格
    private final AtomicReference<BigDecimal> xmrToCny = new AtomicReference<>(BigDecimal.ZERO);
    private final AtomicReference<BigDecimal> xmrToUsdt = new AtomicReference<>(BigDecimal.ZERO);
    private final AtomicReference<BigDecimal> usdtToCny = new AtomicReference<>(BigDecimal.ONE);
    private final AtomicReference<BigDecimal> cfxToCny = new AtomicReference<>(BigDecimal.ZERO);
    private final AtomicReference<BigDecimal> cfxToUsdt = new AtomicReference<>(BigDecimal.ZERO);
    private final AtomicLong coinGeckoCooldownUntil = new AtomicLong(0L);
    private final AtomicInteger coinGecko429Count = new AtomicInteger(0);

    private static final long COINGECKO_BASE_COOLDOWN_MS = 30_000L;
    private static final long COINGECKO_MAX_COOLDOWN_MS = 10 * 60_000L;

    @Value("${app.external-api.cfx-rate-url:https://api.coingecko.com/api/v3/simple/price?ids=conflux-token&vs_currencies=cny,usd}")
    private String cfxRateUrl;

    public ExchangeRateService(ExchangeRateMapper exchangeRateMapper) {
        this.exchangeRateMapper = exchangeRateMapper;
        this.webClient = WebClient.builder()
                .defaultHeader("User-Agent", "MiningBackend/1.0")
                .build();
    }

    /**
     * 每 5 分钟刷新一次汇率。
     * 如果获取失败，则保持上一次的值。
     */
    @Scheduled(fixedRate = 300000)
    public void refreshRates() {
        refreshXmrRates();
        refreshUsdtRates();
        refreshCfxRates();
    }

    private void refreshXmrRates() {
        JsonNode node = fetchCoinGeckoJson(
                "https://api.coingecko.com/api/v3/simple/price?ids=monero&vs_currencies=cny,usd",
                "XMR");
        if (node != null && node.has("monero")) {
            JsonNode monero = node.get("monero");
            if (monero.has("cny")) {
                BigDecimal cny = new BigDecimal(monero.get("cny").asText());
                xmrToCny.set(cny);
                // 存入数据库
                ExchangeRate rate = new ExchangeRate();
                rate.setSymbol("XMR/CNY");
                rate.setPx(cny);
                rate.setSource("CoinGecko");
                exchangeRateMapper.insert(rate);
            }
            if (monero.has("usd")) {
                BigDecimal usd = new BigDecimal(monero.get("usd").asText());
                // will compute xmr/usdt later after usdt/usd available
                // temporarily store in atomic
                // we compute xmrToUsdt in refreshUsdtRates where we know usdt/usd
                xmrToUsdt.set(usd);
            }
        }
    }

    private void refreshUsdtRates() {
        JsonNode node = fetchCoinGeckoJson(
                "https://api.coingecko.com/api/v3/simple/price?ids=tether&vs_currencies=cny,usd",
                "USDT");
        if (node != null && node.has("tether")) {
            JsonNode tether = node.get("tether");
            BigDecimal cny = tether.has("cny") ? new BigDecimal(tether.get("cny").asText()) : BigDecimal.ZERO;
            BigDecimal usd = tether.has("usd") ? new BigDecimal(tether.get("usd").asText()) : BigDecimal.ONE;
            if (cny.compareTo(BigDecimal.ZERO) > 0) {
                usdtToCny.set(cny);
                ExchangeRate rate = new ExchangeRate();
                rate.setSymbol("USDT/CNY");
                rate.setPx(cny);
                rate.setSource("CoinGecko");
                exchangeRateMapper.insert(rate);
            }
            // 计算 XMR/USDT：先获取之前缓存的 XMR/USD，再除以 USDT/USD
            BigDecimal xmrUsd = xmrToUsdt.get();
            if (xmrUsd != null && usd.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal xmrUsdt = xmrUsd.divide(usd, 8, RoundingMode.HALF_UP);
                xmrToUsdt.set(xmrUsdt);
                ExchangeRate rate = new ExchangeRate();
                rate.setSymbol("XMR/USDT");
                rate.setPx(xmrUsdt);
                rate.setSource("CoinGecko");
                exchangeRateMapper.insert(rate);
            }
        }
    }

    private void refreshCfxRates() {
        if (!StringUtils.hasText(cfxRateUrl)) {
            return;
        }
        JsonNode node = fetchCoinGeckoJson(cfxRateUrl, "CFX");
        JsonNode cfx = resolveCfxNode(node);
        if (cfx == null) {
            return;
        }
        if (cfx.has("cny")) {
            BigDecimal cny = new BigDecimal(cfx.get("cny").asText());
            cfxToCny.set(cny);
            ExchangeRate rate = new ExchangeRate();
            rate.setSymbol("CFX/CNY");
            rate.setPx(cny);
            rate.setSource("CoinGecko");
            exchangeRateMapper.insert(rate);
        }
        BigDecimal cnyRate = cfxToCny.get();
        BigDecimal usdtCny = usdtToCny.get();
        if (isPositive(cnyRate) && isPositive(usdtCny)) {
            BigDecimal cfxUsdt = cnyRate.divide(usdtCny, 8, RoundingMode.HALF_UP);
            cfxToUsdt.set(cfxUsdt);
            ExchangeRate rate = new ExchangeRate();
            rate.setSymbol("CFX/USDT");
            rate.setPx(cfxUsdt);
            rate.setSource("CoinGecko");
            exchangeRateMapper.insert(rate);
        }
        BigDecimal xmrCny = xmrToCny.get();
        if (isPositive(cnyRate) && isPositive(xmrCny)) {
            BigDecimal cfxXmr = cnyRate.divide(xmrCny, 8, RoundingMode.HALF_UP);
            ExchangeRate rate = new ExchangeRate();
            rate.setSymbol("CFX/XMR");
            rate.setPx(cfxXmr);
            rate.setSource("Derived(CNY)");
            exchangeRateMapper.insert(rate);
        }
    }

    private JsonNode fetchCoinGeckoJson(String url, String label) {
        if (!StringUtils.hasText(url)) {
            return null;
        }
        long now = System.currentTimeMillis();
        long cooldownUntil = coinGeckoCooldownUntil.get();
        if (cooldownUntil > now) {
            log.info("Skip {} rates refresh: CoinGecko cooldown until {}", label, Instant.ofEpochMilli(cooldownUntil));
            return null;
        }
        try {
            JsonNode node = webClient.get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();
            coinGecko429Count.set(0);
            return node;
        } catch (WebClientResponseException ex) {
            if (ex.getStatusCode().value() == 429) {
                long retryAfterMs = resolveRetryAfterMs(ex);
                long backoffMs = computeBackoffMs();
                long cooldownMs = Math.max(retryAfterMs, backoffMs);
                long until = System.currentTimeMillis() + cooldownMs;
                coinGeckoCooldownUntil.set(until);
                log.warn("CoinGecko rate limited for {}. Cooldown {} ms (until {}).", label, cooldownMs,
                        Instant.ofEpochMilli(until));
                return null;
            }
            log.warn("Failed to refresh {} rates: {}", label, ex.getMessage());
            return null;
        } catch (Exception ex) {
            log.warn("Failed to refresh {} rates: {}", label, ex.getMessage());
            return null;
        }
    }

    private long computeBackoffMs() {
        int attempt = Math.max(1, Math.min(6, coinGecko429Count.incrementAndGet()));
        long backoff = COINGECKO_BASE_COOLDOWN_MS * (1L << (attempt - 1));
        return Math.min(backoff, COINGECKO_MAX_COOLDOWN_MS);
    }

    private long resolveRetryAfterMs(WebClientResponseException ex) {
        String header = ex.getHeaders().getFirst("Retry-After");
        if (!StringUtils.hasText(header)) {
            return 0L;
        }
        String value = header.trim();
        try {
            long seconds = Long.parseLong(value);
            return Math.max(0L, seconds) * 1000L;
        } catch (NumberFormatException ignored) {
            // fall through
        }
        try {
            ZonedDateTime date = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME);
            long delay = date.toInstant().toEpochMilli() - System.currentTimeMillis();
            return Math.max(0L, delay);
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private JsonNode resolveCfxNode(JsonNode root) {
        if (root == null || root.isMissingNode()) {
            return null;
        }
        JsonNode node = root.get("conflux-token");
        if (node != null && !node.isMissingNode()) {
            return node;
        }
        node = root.get("conflux");
        if (node != null && !node.isMissingNode()) {
            return node;
        }
        java.util.Iterator<String> fields = root.fieldNames();
        if (fields.hasNext()) {
            String name = fields.next();
            JsonNode fallback = root.get(name);
            if (fallback != null && !fallback.isMissingNode()) {
                return fallback;
            }
        }
        return null;
    }

    /**
     * 获取最新 XMR 对 CNY 的汇率
     */
    public BigDecimal getXmrToCnyRate() {
        return xmrToCny.get();
    }

    /**
     * 获取最新 XMR 对 USDT 的汇率
     */
    public BigDecimal getXmrToUsdtRate() {
        return xmrToUsdt.get();
    }

    /**
     * 获取最新 USDT 对 CNY 的汇率
     */
    public BigDecimal getUsdtToCnyRate() {
        return usdtToCny.get();
    }

    /**
     * 获取最新 CFX 对 CNY 的汇率
     */
    public BigDecimal getCfxToCnyRate() {
        return cfxToCny.get();
    }

    /**
     * 获取最新 CFX 对 USDT 的汇率
     */
    public BigDecimal getCfxToUsdtRate() {
        return cfxToUsdt.get();
    }

    /**
     * 获取 CFX 对 XMR 的汇率（XMR per CFX）
     */
    public BigDecimal getCfxToXmrRate() {
        return getLatestRate("CFX/XMR");
    }

    /**
     * 根据 symbol 获取最近一条汇率（实时口径）。
     *
     * @param symbol 比如 "XMR/CNY"
     */
    public BigDecimal getLatestRate(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return BigDecimal.ZERO;
        }
        BigDecimal latest;
        switch (symbol) {
            case "XMR/CNY" -> latest = xmrToCny.get();
            case "XMR/USDT" -> latest = xmrToUsdt.get();
            case "USDT/CNY" -> latest = usdtToCny.get();
            case "CFX/CNY" -> latest = cfxToCny.get();
            case "CFX/USDT" -> latest = cfxToUsdt.get();
            default -> latest = null;
        }
        BigDecimal cached = isPositive(latest) ? latest : null;
        if (cached != null) {
            return cached;
        }
        java.util.Optional<com.slb.mining_backend.modules.exchange.entity.ExchangeRate> latestRecord =
                exchangeRateMapper.selectLatestBySymbol(symbol);
        if (latestRecord.isPresent() && isPositive(latestRecord.get().getPx())) {
            return latestRecord.get().getPx();
        }
        return BigDecimal.ZERO;
    }

    /**
     * 获取所有汇率的快照（原子操作）
     * 用于展示页面，保证所有汇率在同一时刻被读取
     */
    public Map<String, BigDecimal> getAllExchangeRates() {
        Map<String, BigDecimal> rates = new java.util.LinkedHashMap<>();
        rates.put("XMR/CNY", xmrToCny.get());
        rates.put("XMR/USDT", xmrToUsdt.get());
        rates.put("USDT/CNY", usdtToCny.get());
        rates.put("CFX/CNY", cfxToCny.get());
        rates.put("CFX/USDT", cfxToUsdt.get());
        return rates;
    }

    private boolean isPositive(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) > 0;
    }
}
