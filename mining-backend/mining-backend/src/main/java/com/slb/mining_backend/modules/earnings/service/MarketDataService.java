package com.slb.mining_backend.modules.earnings.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slb.mining_backend.modules.device.mapper.DeviceMapper;
import com.slb.mining_backend.modules.exchange.service.ExchangeRateService;
import com.slb.mining_backend.modules.xmr.service.antpool.AntpoolClient;
import lombok.extern.slf4j.Slf4j;
import com.jayway.jsonpath.JsonPath;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpHeaders;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
public class MarketDataService {

    private static final Map<String, BigDecimal> HASHRATE_UNITS;
    private static final Pattern F2POOL_HTML_PROFIT_PATTERN = Pattern.compile("data-profit=\"([0-9.]+)\"");
    private static final Pattern F2POOL_HTML_USD_PATTERN = Pattern.compile("data-usd-per=\"([0-9.]+)\"");
    /**
     * CAL->CNY 汇率本身建议保留更多小数位，避免先把“汇率”四舍五入到 2 位后再乘 CAL 导致精度损失。
     */
    private static final int CAL_TO_CNY_RATE_SCALE = 8;
    // activePortProfit 口径的合理上限（XMR / (MH/s · day)），超过认为异常并忽略
    @Value("${app.external-api.active-port-profit-max:0.1}")
    private BigDecimal activePortProfitMax;

    static {
        HASHRATE_UNITS = new LinkedHashMap<>();
        // 统一口径：MH/s
        HASHRATE_UNITS.put("TH/S", BigDecimal.valueOf(1_000_000L));
        HASHRATE_UNITS.put("GH/S", BigDecimal.valueOf(1_000L));
        HASHRATE_UNITS.put("MH/S", BigDecimal.ONE);
        HASHRATE_UNITS.put("KH/S", BigDecimal.valueOf(0.001d));
        HASHRATE_UNITS.put("H/S", BigDecimal.valueOf(0.000001d));
    }

    private final ExchangeRateService exchangeRateService;
    private final DeviceMapper deviceMapper;
    private final AntpoolClient antpoolClient;
    private final WebClient.Builder webClientBuilder;
    private WebClient poolStatsClient;
    private final ObjectMapper objectMapper;
    private final AtomicReference<BigDecimal> externalPoolHashrateHps = new AtomicReference<>(BigDecimal.ZERO);
    /**
     * c3pool /pool/stats 返回的 activePortProfit（若存在）：
     * 原始口径约定按“XMR / (H/s · day)”理解；此处会统一换算为 MH/s 口径。
     * 若字段缺失或解析失败，则保持为 0，业务侧需 fallback 到旧公式。
     */
    private final AtomicReference<BigDecimal> externalPoolActivePortProfitXmrPerHashDay = new AtomicReference<>(BigDecimal.ZERO);
    private final AtomicReference<BigDecimal> cfxDailyCoinPerMh = new AtomicReference<>(BigDecimal.ZERO);
    private final AtomicReference<String> cfxProfitSource = new AtomicReference<>("unknown");
    private final AtomicReference<BigDecimal> rvnDailyCoinPerMh = new AtomicReference<>(BigDecimal.ZERO);
    private final AtomicReference<BigDecimal> cfxNetworkHashrateMh = new AtomicReference<>(BigDecimal.ZERO);
    private final AtomicReference<BigDecimal> cfxBlockTimeSecondsCache = new AtomicReference<>(BigDecimal.ZERO);
    private final java.util.concurrent.atomic.AtomicLong cfxStatsLastRefreshAt = new java.util.concurrent.atomic.AtomicLong(0L);
    private final AtomicReference<String> cfxStatsLastError = new AtomicReference<>(null);

    @Value("${app.rates.cal-xmr-ratio:1.0}")
    private BigDecimal calXmrRatio;

    @Value("${app.external-api.c3pool-stats-url:}")
    private String poolStatsUrl;

    @Value("${app.external-api.pool-stats-timeout-ms:5000}")
    private long poolStatsTimeoutMs;

    @Value("${app.external-api.coin-stats-refresh-ms:300000}")
    private long coinStatsRefreshMs;

    @Value("${app.external-api.webclient-buffer-size-mb:10}")
    private int webclientBufferSizeMb;

    @Value("${app.external-api.cfx-f2pool-api-url:}")
    private String cfxF2PoolApiUrl;
    
    @Value("${app.external-api.cfx-f2pool-api-token:}")
    private String cfxF2PoolApiToken;
    
    @Value("${app.external-api.cfx-f2pool-api-type:web}")
    private String cfxF2PoolApiType; // "web" (网页 XHR) 或 "v2" (官方 v2 API)

    @Value("${app.external-api.cfx-f2pool-api-method:POST}")
    private String cfxF2PoolApiMethod;

    @Value("${app.external-api.cfx-f2pool-api-request-body:}")
    private String cfxF2PoolApiRequestBody;

    @Value("${app.external-api.cfx-f2pool-api-content-type:}")
    private String cfxF2PoolApiContentType;

    @Value("${app.external-api.cfx-f2pool-api-cookie:}")
    private String cfxF2PoolApiCookie;

    @Value("${app.external-api.cfx-f2pool-api-referer:}")
    private String cfxF2PoolApiReferer;

    @Value("${app.external-api.cfx-f2pool-api-origin:}")
    private String cfxF2PoolApiOrigin;

    @Value("${app.external-api.cfx-f2pool-api-user-agent:}")
    private String cfxF2PoolApiUserAgent;

    @Value("${app.external-api.cfx-nanopool-earnings-url:}")
    private String cfxNanopoolEarningsUrl;

    @Value("${app.external-api.cfx-nanopool-earnings-multiplier:1.0}")
    private BigDecimal cfxNanopoolEarningsMultiplier;

    @Value("${app.external-api.cfx-stats-url:}")
    private String cfxStatsUrl;

    @Value("${app.external-api.cfx-profit-per-mh-path:}")
    private String cfxProfitPerMhPath;

    @Value("${app.external-api.cfx-network-hashrate-path:}")
    private String cfxNetworkHashratePath;

    @Value("${app.external-api.cfx-network-hashrate-unit:}")
    private String cfxNetworkHashrateUnit;

    @Value("${app.external-api.cfx-block-reward:0}")
    private BigDecimal cfxBlockReward;

    @Value("${app.external-api.cfx-block-time-seconds:0}")
    private BigDecimal cfxBlockTimeSeconds;

    @Value("${app.external-api.cfx-block-time-seconds-url:}")
    private String cfxBlockTimeSecondsUrl;

    @Value("${app.external-api.cfx-block-time-seconds-path:}")
    private String cfxBlockTimeSecondsPath;

    @Value("${app.external-api.cfx-rpc-url:}")
    private String cfxRpcUrl;

    @Value("${app.external-api.rvn-stats-url:}")
    private String rvnStatsUrl;

    @Value("${app.external-api.rvn-profit-per-mh-path:}")
    private String rvnProfitPerMhPath;

    @Value("${app.external-api.rvn-network-hashrate-path:}")
    private String rvnNetworkHashratePath;

    @Value("${app.external-api.rvn-network-hashrate-unit:}")
    private String rvnNetworkHashrateUnit;

    @Value("${app.external-api.rvn-block-reward:0}")
    private BigDecimal rvnBlockReward;

    @Value("${app.external-api.rvn-block-time-seconds:0}")
    private BigDecimal rvnBlockTimeSeconds;

    @Value("${app.external-api.rvn-block-time-seconds-url:}")
    private String rvnBlockTimeSecondsUrl;

    @Value("${app.external-api.rvn-block-time-seconds-path:}")
    private String rvnBlockTimeSecondsPath;

    @Value("${app.external-api.rvn-antpool-calculator-enabled:false}")
    private boolean rvnAntpoolCalculatorEnabled;

    @Value("${app.external-api.rvn-antpool-calculator-coin-type:RVN}")
    private String rvnAntpoolCalculatorCoinType;

    @Value("${app.external-api.rvn-antpool-calculator-hash-input:1000000}")
    private BigDecimal rvnAntpoolCalculatorHashInput;

    @Value("${app.external-api.rvn-antpool-calculator-network-diff:}")
    private String rvnAntpoolCalculatorNetworkDiff;

    @Value("${app.external-api.rvn-antpool-calculator-fee-percent:}")
    private String rvnAntpoolCalculatorFeePercent;

    public MarketDataService(ExchangeRateService exchangeRateService,
                             DeviceMapper deviceMapper,
                             AntpoolClient antpoolClient,
                             WebClient.Builder webClientBuilder,
                             ObjectMapper objectMapper) {
        this.exchangeRateService = exchangeRateService;
        this.deviceMapper = deviceMapper;
        this.antpoolClient = antpoolClient;
        this.webClientBuilder = webClientBuilder;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    private void initWebClient() {
        // 增加缓冲区大小以支持大型 JSON 响应（CFX API 响应可能超过 256KB）
        // 默认 10MB，可通过 app.external-api.webclient-buffer-size-mb 配置
        int bufferSizeBytes = Math.max(1, webclientBufferSizeMb) * 1024 * 1024;
        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(bufferSizeBytes))
                .build();
        this.poolStatsClient = webClientBuilder
                .defaultHeader(HttpHeaders.USER_AGENT, "MiningBackend/MarketDataService")
                .exchangeStrategies(strategies)
                .build();
        log.info("MarketDataService WebClient 缓冲区大小: {} MB ({} bytes)", webclientBufferSizeMb, bufferSizeBytes);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmUpMarketData() {
        try {
            refreshExternalPoolHashrate();
        } catch (Exception ex) {
            log.warn("MarketDataService 启动预热失败: 刷新矿池算力异常: {}", ex.getMessage());
        }
        try {
            refreshCoinProfitability();
        } catch (Exception ex) {
            log.warn("MarketDataService 启动预热失败: 刷新币种收益异常: {}", ex.getMessage());
        }
    }

    /**
     * 获取 CAL 对 CNY 的汇率。
     * 策略：1 CAL = ratio * XMR，因此返回 XMR/CNY 汇率 * ratio。
     */
    public BigDecimal getCalToCnyRate() {
        BigDecimal xmrToCny = exchangeRateService.getXmrToCnyRate();
        if (xmrToCny == null) {
            return BigDecimal.ZERO;
        }
        return xmrToCny.multiply(calXmrRatio).setScale(CAL_TO_CNY_RATE_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 获取 XMR 对 CNY 的汇率（来自 ExchangeRateService）。
     */
    public BigDecimal getXmrToCnyRate() {
        BigDecimal v = exchangeRateService.getXmrToCnyRate();
        return v == null ? BigDecimal.ZERO : v;
    }

    /**
     * 获取 CFX 对 XMR 的汇率（XMR per CFX）。
     */
    public BigDecimal getCfxToXmrRate() {
        BigDecimal v = exchangeRateService.getLatestRate("CFX/XMR");
        return v == null ? BigDecimal.ZERO : v;
    }

    /**
     * 获取 CFX 对 CNY 的汇率（CNY per CFX）。
     */
    public BigDecimal getCfxToCnyRate() {
        BigDecimal v = exchangeRateService.getCfxToCnyRate();
        return v == null ? BigDecimal.ZERO : v;
    }

    /**
     * 获取 RVN 对 XMR 的汇率（XMR per RVN）。
     */
    public BigDecimal getRvnToXmrRate() {
        BigDecimal v = exchangeRateService.getLatestRate("RVN/XMR");
        return v == null ? BigDecimal.ZERO : v;
    }

    public BigDecimal getCfxDailyCoinPerMh() {
        BigDecimal v = cfxDailyCoinPerMh.get();
        return v == null ? BigDecimal.ZERO : v;
    }

    public String getCfxProfitSource() {
        return cfxProfitSource.get();
    }

    public boolean isCfxProfitFromF2Pool() {
        String source = cfxProfitSource.get();
        return source != null && source.startsWith("f2pool");
    }

    public BigDecimal getRvnDailyCoinPerMh() {
        BigDecimal v = rvnDailyCoinPerMh.get();
        return v == null ? BigDecimal.ZERO : v;
    }

    public BigDecimal getCfxNetworkHashrateMh() {
        BigDecimal v = cfxNetworkHashrateMh.get();
        return v == null ? BigDecimal.ZERO : v;
    }

    public BigDecimal getCfxBlockTimeSeconds() {
        BigDecimal v = cfxBlockTimeSecondsCache.get();
        return v == null ? BigDecimal.ZERO : v;
    }

    public Long getCfxStatsLastRefreshAt() {
        return cfxStatsLastRefreshAt.get();
    }

    public String getCfxStatsLastError() {
        return cfxStatsLastError.get();
    }

    /**
     * 获取 CAL/XMR 固定换算比例：1 CAL = ratio * XMR。
     */
    public BigDecimal getCalXmrRatio() {
        return calXmrRatio == null ? BigDecimal.ZERO : calXmrRatio;
    }

    /**
     * 获取外部矿池 activePortProfit（若可用），单位按 XMR / (MH/s · day) 理解。
     * 若不可用返回 0。
     */
    public BigDecimal getExternalPoolActivePortProfitXmrPerHashDay() {
        BigDecimal v = externalPoolActivePortProfitXmrPerHashDay.get();
        return v == null ? BigDecimal.ZERO : v;
    }

    /**
     * 获取矿池总算力（MH/s）。优先使用外部矿池统计，失败时退化为平台在线设备总算力。
     */
    public BigDecimal getPoolTotalHashrate() {
        BigDecimal external = externalPoolHashrateHps.get();
        if (isPositive(external)) {
            return external.setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal cpu = safe(deviceMapper.sumTotalCpuHashrate());
        BigDecimal gpu = safe(deviceMapper.sumTotalGpuHashrate());
        return cpu.add(gpu).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 获取已缓存的“外部矿池总算力（MH/s）”。若外部未成功刷新则返回 0。
     * 用于排查/验收：判断当前是否命中外部矿池统计。
     */
    public BigDecimal getExternalPoolHashrateHps() {
        BigDecimal v = externalPoolHashrateHps.get();
        return v == null ? BigDecimal.ZERO : v;
    }

    /**
     * 定时刷新外部矿池算力，避免每次请求都访问三方 API。
     */
    @Scheduled(initialDelay = 10_000, fixedDelayString = "${app.external-api.pool-stats-refresh-ms:300000}")
    public void refreshExternalPoolHashrate() {
        if (!StringUtils.hasText(poolStatsUrl)) {
            return;
        }
        try {
            String body = poolStatsClient.get()
                    .uri(poolStatsUrl)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofMillis(poolStatsTimeoutMs))
                    .block();
            if (!StringUtils.hasText(body)) {
                return;
            }
            JsonNode root = objectMapper.readTree(body);
            BigDecimal parsedHashrate = extractHashrate(root);
            if (isPositive(parsedHashrate)) {
                externalPoolHashrateHps.set(parsedHashrate.setScale(2, RoundingMode.HALF_UP));
            }
            BigDecimal parsedProfit = extractActivePortProfit(root);
            if (isPositive(parsedProfit)) {
                // 外部口径可能是 XMR/(H/s·day) 或 XMR/(MH/s·day)，这里做一次量级识别
                BigDecimal mhThreshold = new BigDecimal("0.000001");
                BigDecimal normalizedProfit = parsedProfit;
                if (parsedProfit.compareTo(mhThreshold) < 0) {
                    normalizedProfit = parsedProfit.multiply(BigDecimal.valueOf(1_000_000L));
                } else {
                    log.warn("activePortProfit seems already in MH/s unit, skip scaling (value={})", parsedProfit);
                }
                if (activePortProfitMax != null && normalizedProfit.compareTo(activePortProfitMax) > 0) {
                    log.warn("activePortProfit out of range, ignore (value={})", normalizedProfit);
                } else {
                    externalPoolActivePortProfitXmrPerHashDay.set(normalizedProfit.setScale(12, RoundingMode.HALF_UP));
                }
            }
        } catch (Exception ex) {
            log.debug("Unable to refresh pool hashrate: {}", ex.getMessage());
        }
    }

    @Scheduled(initialDelay = 15_000, fixedDelayString = "${app.external-api.coin-stats-refresh-ms:300000}")
    public void refreshCoinProfitability() {
        // 优先使用 Nanopool 获取 CFX 收益数据（倍率可配置）
        // 若 Nanopool 失败，再尝试 F2Pool，最后回退到 Conflux 官方 API
        boolean cfxResolved = false;
        if (StringUtils.hasText(cfxNanopoolEarningsUrl)) {
            try {
                cfxResolved = refreshCfxProfitabilityFromNanopool();
            } catch (Exception ex) {
                log.warn("CFX Nanopool API 调用失败，将尝试其他数据源: {}", ex.getMessage());
            }
        }

        if (!cfxResolved && StringUtils.hasText(cfxF2PoolApiUrl)) {
            try {
                refreshCfxProfitabilityFromF2Pool();
                // 检查是否成功（通过检查 dailyCoinPerMh 是否被设置）
                BigDecimal currentValue = cfxDailyCoinPerMh.get();
                if (isPositive(currentValue)) {
                    cfxResolved = true;
                }
            } catch (Exception ex) {
                log.warn("CFX F2Pool API 调用失败，回退到 Conflux 官方 API: {}", ex.getMessage());
            }
        }

        // 如果 F2Pool/Nanopool 未配置或失败，使用 Conflux 官方 API
        if (!cfxResolved) {
            refreshCoinProfitability("CFX", cfxStatsUrl, cfxProfitPerMhPath, cfxNetworkHashratePath,
                    cfxNetworkHashrateUnit, cfxBlockReward, resolveCfxBlockTimeSeconds(),
                    cfxDailyCoinPerMh);
        }
        
        boolean rvnResolved = false;
        if (rvnAntpoolCalculatorEnabled) {
            try {
                rvnResolved = refreshRvnProfitabilityFromAntpool();
            } catch (Exception ex) {
                log.warn("RVN Antpool 计算器调用失败，将回退到默认 API: {}", ex.getMessage());
            }
        }
        if (!rvnResolved) {
            refreshCoinProfitability("RVN", rvnStatsUrl, rvnProfitPerMhPath, rvnNetworkHashratePath,
                    rvnNetworkHashrateUnit,
                    rvnBlockReward, resolveBlockTimeSeconds(rvnBlockTimeSeconds, rvnBlockTimeSecondsUrl, rvnBlockTimeSecondsPath),
                    rvnDailyCoinPerMh);
        }
    }

    /**
     * 从 F2Pool API 获取 CFX 收益数据
     * 支持两种方式：
     * 1. 网页 XHR 接口（web）：POST 请求，从 DevTools 获取的真实 URL
     * 2. 官方 v2 API（v2）：POST 请求，需要 API token
     */
    private void refreshCfxProfitabilityFromF2Pool() {
        if (!StringUtils.hasText(cfxF2PoolApiUrl)) {
            markCfxRefreshError("cfx-f2pool-api-url is empty");
            log.warn("CFX F2Pool API URL 为空，跳过刷新");
            return;
        }
        try {
            String apiType = StringUtils.hasText(cfxF2PoolApiType) ? cfxF2PoolApiType.trim() : "";
            boolean isV2Api = "v2".equalsIgnoreCase(apiType);
            String method = StringUtils.hasText(cfxF2PoolApiMethod)
                    ? cfxF2PoolApiMethod.trim().toUpperCase(Locale.ROOT)
                    : "POST";
            boolean useGet = !isV2Api && "GET".equals(method);
            String requestBody = useGet ? "" : resolveF2PoolRequestBody(isV2Api);
            String contentType = resolveF2PoolContentType(isV2Api, requestBody);

            WebClient.RequestHeadersSpec<?> requestSpec;
            if (useGet) {
                requestSpec = poolStatsClient.get()
                        .uri(cfxF2PoolApiUrl);
            } else {
                WebClient.RequestBodySpec bodySpec = poolStatsClient.post()
                        .uri(cfxF2PoolApiUrl);
                requestSpec = StringUtils.hasText(requestBody) ? bodySpec.bodyValue(requestBody) : bodySpec;
            }

            if (!useGet && StringUtils.hasText(contentType)) {
                requestSpec.headers(headers -> headers.set(HttpHeaders.CONTENT_TYPE, contentType));
            }
            if (StringUtils.hasText(cfxF2PoolApiUserAgent)) {
                requestSpec.headers(headers -> headers.set(HttpHeaders.USER_AGENT, cfxF2PoolApiUserAgent));
            }

            if (StringUtils.hasText(cfxF2PoolApiCookie)) {
                requestSpec.header(HttpHeaders.COOKIE, cfxF2PoolApiCookie);
            }
            if (!isV2Api) {
                String referer = resolveF2PoolReferer();
                if (StringUtils.hasText(referer)) {
                    requestSpec.header(HttpHeaders.REFERER, referer);
                }
                requestSpec.header("X-Requested-With", "XMLHttpRequest");
                String origin = resolveF2PoolOrigin();
                if (StringUtils.hasText(origin)) {
                    requestSpec.header(HttpHeaders.ORIGIN, origin);
                }
            }
            if (isV2Api) {
                // 官方 v2 API：POST 请求，需要 token
                if (!StringUtils.hasText(cfxF2PoolApiToken)) {
                    markCfxRefreshError("cfx-f2pool-api-token is required for v2 API");
                    log.warn("CFX F2Pool v2 API 需要 token，但未配置");
                    throw new RuntimeException("F2Pool v2 API requires token");
                }
                requestSpec.header("F2P-API-SECRET", cfxF2PoolApiToken);
            }

            String body = requestSpec
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofMillis(Math.max(1000L, poolStatsTimeoutMs)))
                    .block();
            if (!StringUtils.hasText(body)) {
                markCfxRefreshError("cfx-f2pool-api-url returned empty body");
                log.warn("CFX F2Pool API 返回空响应, url={}", cfxF2PoolApiUrl);
                return;
            }

            if (!isV2Api && !isJsonLike(body)) {
                if (tryParseF2PoolProfitFromHtml(body)) {
                    return;
                }
                markCfxRefreshError("F2Pool HTML response missing data-profit");
                log.warn("CFX F2Pool HTML 响应解析失败, url={}", cfxF2PoolApiUrl);
                return;
            }

            JsonNode root = objectMapper.readTree(body);
            
            // 检查响应状态（web 模式通常有 status=ok，v2 可能没有）
            if (root.has("status") && !"ok".equalsIgnoreCase(root.path("status").asText())) {
                String errorMsg = "F2Pool API status is not ok: " + root.path("status").asText();
                markCfxRefreshError(errorMsg);
                log.warn("CFX F2Pool API 返回错误状态: {}", errorMsg);
                return;
            }
            
            JsonNode dataNode = resolveF2PoolDataNode(root, isV2Api);
            if (dataNode == null || dataNode.isMissingNode() || dataNode.isNull()) {
                markCfxRefreshError("F2Pool API data node is missing");
                log.warn("CFX F2Pool API 响应中缺少有效 data 节点, apiType={}", cfxF2PoolApiType);
                return;
            }

            // 提取 estimated_profit_usd (每 MH/s 的美元日收益)
            JsonNode estimatedProfitUsdNode = dataNode.path("estimated_profit_usd");
            if (estimatedProfitUsdNode.isMissingNode() || estimatedProfitUsdNode.isNull()) {
                markCfxRefreshError("F2Pool API estimated_profit_usd field is missing");
                log.warn("CFX F2Pool API 响应中缺少 estimated_profit_usd 字段");
                return;
            }
            
            BigDecimal estimatedProfitUsd = new BigDecimal(estimatedProfitUsdNode.asText());
            if (!isPositive(estimatedProfitUsd)) {
                markCfxRefreshError("F2Pool API estimated_profit_usd is not positive: " + estimatedProfitUsd);
                log.warn("CFX F2Pool API estimated_profit_usd 无效: {}", estimatedProfitUsd);
                return;
            }
            
            // 获取 USD/CNY 汇率
            BigDecimal usdToCnyRate = exchangeRateService.getUsdtToCnyRate();
            if (!isPositive(usdToCnyRate)) {
                markCfxRefreshError("USD/CNY rate is not available");
                log.warn("CFX F2Pool 收益计算失败: USD/CNY 汇率为0, estimatedProfitUsd={}", estimatedProfitUsd);
                return;
            }
            
            // 计算每 MH/s 的 CNY 日收益
            BigDecimal dailyCnyPerMh = estimatedProfitUsd.multiply(usdToCnyRate);
            
            // 获取 CFX/CNY 汇率，将 CNY 收益转换为 CFX 币数
            BigDecimal cfxToCnyRate = exchangeRateService.getCfxToCnyRate();
            if (!isPositive(cfxToCnyRate)) {
                markCfxRefreshError("CFX/CNY rate is not available");
                log.warn("CFX F2Pool 收益计算失败: CFX/CNY 汇率为0, dailyCnyPerMh={}", dailyCnyPerMh);
                return;
            }
            
            // 转换为每 MH/s 日产币数（CFX）
            BigDecimal dailyCoinPerMh = dailyCnyPerMh.divide(cfxToCnyRate, 12, RoundingMode.HALF_UP);
            
            // 更新缓存
            cfxDailyCoinPerMh.set(dailyCoinPerMh.setScale(12, RoundingMode.HALF_UP));
            cfxProfitSource.set(isV2Api ? "f2pool-v2" : "f2pool-web");
            
            // 更新诊断信息（从 F2Pool 响应中提取网络算力等）
            // F2Pool 返回的 network_hashrate 是 H/s，需要转换为 MH/s
            JsonNode networkHashrateNode = dataNode.path("network_hashrate");
            if (networkHashrateNode.isMissingNode() || networkHashrateNode.isNull()) {
                // 尝试其他可能的字段名
                networkHashrateNode = dataNode.path("network_hashrate_double");
            }
            if (!networkHashrateNode.isMissingNode() && !networkHashrateNode.isNull()) {
                try {
                    BigDecimal networkHashrate = new BigDecimal(networkHashrateNode.asText());
                    // network_hashrate_double 已经是科学计数法，直接使用；network_hashrate 是 H/s，需要转换
                    // 根据用户提供的示例，network_hashrate_double = 5.2750540379549716e+20 (H/s)
                    // 转换为 MH/s: 除以 1,000,000
                    BigDecimal networkHashrateMh = networkHashrate.divide(BigDecimal.valueOf(1_000_000L), 6, RoundingMode.HALF_UP);
                    updateCfxDiagnostics(networkHashrateMh, null);
                } catch (Exception ex) {
                    log.debug("CFX F2Pool 网络算力解析失败: {}", ex.getMessage());
                    // 忽略网络算力解析错误，不影响收益计算
                }
            }
            
            markCfxRefreshStatus(dailyCoinPerMh, null, null);
            log.info("CFX F2Pool 市场数据刷新成功: estimatedProfitUsd={}, usdToCnyRate={}, dailyCnyPerMh={}, cfxToCnyRate={}, dailyCoinPerMh={}",
                    estimatedProfitUsd, usdToCnyRate, dailyCnyPerMh, cfxToCnyRate, dailyCoinPerMh);
            
        } catch (org.springframework.web.reactive.function.client.WebClientResponseException.NotFound notFoundEx) {
            // 404 错误：API 端点可能不正确，抛出异常以便回退到 Conflux API
            markCfxRefreshError("cfx-f2pool-api-url returned 404: " + notFoundEx.getMessage());
            log.warn("CFX F2Pool API 端点不存在 (404): url={}, 将回退到 Conflux 官方 API", cfxF2PoolApiUrl);
            throw new RuntimeException("F2Pool API endpoint not found, will fallback to Conflux API", notFoundEx);
        } catch (Exception ex) {
            markCfxRefreshError("cfx-f2pool-api-url fetch failed: " + ex.getMessage());
            log.warn("CFX F2Pool 市场数据刷新失败: url={}, error={}, 将回退到 Conflux 官方 API", cfxF2PoolApiUrl, ex.getMessage());
            throw new RuntimeException("F2Pool API failed, will fallback to Conflux API", ex);
        }
    }

    private boolean refreshCfxProfitabilityFromNanopool() {
        if (!StringUtils.hasText(cfxNanopoolEarningsUrl)) {
            return false;
        }
        try {
            String body = poolStatsClient.get()
                    .uri(cfxNanopoolEarningsUrl)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofMillis(Math.max(1000L, poolStatsTimeoutMs)))
                    .block();
            if (!StringUtils.hasText(body)) {
                markCfxRefreshError("cfx-nanopool-earnings-url returned empty body");
                log.warn("CFX Nanopool API 返回空响应, url={}", cfxNanopoolEarningsUrl);
                return false;
            }
            JsonNode root = objectMapper.readTree(body);
            if (root.has("status") && !root.path("status").asBoolean(false)) {
                markCfxRefreshError("Nanopool API status is false");
                log.warn("CFX Nanopool API 返回错误状态: {}", root.path("status").asText());
                return false;
            }
            JsonNode coinsNode = root.path("data").path("day").path("coins");
            if (coinsNode.isMissingNode() || coinsNode.isNull()) {
                markCfxRefreshError("Nanopool API data.day.coins is missing");
                log.warn("CFX Nanopool API 响应中缺少 day.coins 字段");
                return false;
            }
            BigDecimal dailyCoinPerMh = new BigDecimal(coinsNode.asText());
            if (!isPositive(dailyCoinPerMh)) {
                markCfxRefreshError("Nanopool API day.coins is not positive: " + dailyCoinPerMh);
                log.warn("CFX Nanopool API day.coins 无效: {}", dailyCoinPerMh);
                return false;
            }
            BigDecimal multiplier = resolveNanopoolEarningsMultiplier();
            BigDecimal adjusted = dailyCoinPerMh.multiply(multiplier);
            cfxDailyCoinPerMh.set(adjusted.setScale(12, RoundingMode.HALF_UP));
            cfxProfitSource.set("nanopool");
            markCfxRefreshStatus(adjusted, null, null);
            log.info("CFX Nanopool 市场数据刷新成功: dailyCoinPerMh={}, multiplier={}", adjusted, multiplier);
            return true;
        } catch (Exception ex) {
            markCfxRefreshError("cfx-nanopool-earnings-url fetch failed: " + ex.getMessage());
            log.warn("CFX Nanopool 市场数据刷新失败: url={}, error={}", cfxNanopoolEarningsUrl, ex.getMessage());
            return false;
        }
    }

    private BigDecimal resolveNanopoolEarningsMultiplier() {
        BigDecimal multiplier = safe(cfxNanopoolEarningsMultiplier);
        if (multiplier.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ONE;
        }
        return multiplier;
    }

    private boolean refreshRvnProfitabilityFromAntpool() {
        if (!rvnAntpoolCalculatorEnabled) {
            return false;
        }
        BigDecimal hashInput = rvnAntpoolCalculatorHashInput;
        if (!isPositive(hashInput)) {
            log.warn("RVN Antpool 计算器 hashInput 无效: {}", hashInput);
            return false;
        }
        String coinType = StringUtils.hasText(rvnAntpoolCalculatorCoinType)
                ? rvnAntpoolCalculatorCoinType.trim()
                : "RVN";
        String hashInputText = hashInput.stripTrailingZeros().toPlainString();
        AntpoolClient.AntpoolRawResponse response = antpoolClient.fetchCoinCalculator(
                coinType, hashInputText, rvnAntpoolCalculatorNetworkDiff, rvnAntpoolCalculatorFeePercent);
        if (response == null || !StringUtils.hasText(response.body())) {
            log.warn("RVN Antpool 计算器返回空响应, coinType={}, hashInput={}", coinType, hashInputText);
            return false;
        }
        try {
            JsonNode root = objectMapper.readTree(response.body());
            int code = root.path("code").asInt(-1);
            if (code != 0) {
                log.warn("RVN Antpool 计算器返回错误码: code={}, message={}", code, root.path("message").asText());
                return false;
            }
            JsonNode coinMountNode = root.path("data").path("coinMount");
            if (coinMountNode.isMissingNode() || coinMountNode.isNull()) {
                log.warn("RVN Antpool 计算器响应缺少 coinMount 字段");
                return false;
            }
            BigDecimal coinMount = new BigDecimal(coinMountNode.asText());
            if (!isPositive(coinMount)) {
                log.warn("RVN Antpool 计算器 coinMount 无效: {}", coinMount);
                return false;
            }
            BigDecimal hashInputMh = hashInput.divide(BigDecimal.valueOf(1_000_000L), 12, RoundingMode.HALF_UP);
            if (!isPositive(hashInputMh)) {
                log.warn("RVN Antpool 计算器 hashInput 转换 MH/s 失败: {}", hashInputMh);
                return false;
            }
            BigDecimal dailyCoinPerMh = coinMount.divide(hashInputMh, 12, RoundingMode.HALF_UP);
            rvnDailyCoinPerMh.set(dailyCoinPerMh.setScale(12, RoundingMode.HALF_UP));
            log.info("RVN Antpool 计算器刷新成功: dailyCoinPerMh={}, coinMount={}, hashInput={}",
                    dailyCoinPerMh, coinMount, hashInputText);
            return true;
        } catch (Exception ex) {
            log.warn("RVN Antpool 计算器解析失败: {}", ex.getMessage());
            return false;
        }
    }

    private String resolveF2PoolRequestBody(boolean isV2Api) throws Exception {
        if (StringUtils.hasText(cfxF2PoolApiRequestBody)) {
            return cfxF2PoolApiRequestBody;
        }
        if (isV2Api) {
            // 默认请求体：{"currency": "conflux"}
            Map<String, String> requestBody = new LinkedHashMap<>();
            requestBody.put("currency", "conflux");
            return objectMapper.writeValueAsString(requestBody);
        }
        // web 模式默认使用 form-urlencoded
        return "currency=conflux";
    }

    private String resolveF2PoolContentType(boolean isV2Api, String requestBody) {
        if (StringUtils.hasText(cfxF2PoolApiContentType)) {
            return cfxF2PoolApiContentType;
        }
        if (isV2Api) {
            return "application/json";
        }
        if (StringUtils.hasText(requestBody)) {
            String trimmed = requestBody.trim();
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                return "application/json";
            }
        }
        return "application/x-www-form-urlencoded; charset=UTF-8";
    }

    private String resolveF2PoolOrigin() {
        if (StringUtils.hasText(cfxF2PoolApiOrigin)) {
            return cfxF2PoolApiOrigin;
        }
        String candidate = StringUtils.hasText(cfxF2PoolApiReferer) ? cfxF2PoolApiReferer : cfxF2PoolApiUrl;
        if (!StringUtils.hasText(candidate)) {
            return null;
        }
        try {
            java.net.URI uri = java.net.URI.create(candidate);
            if (!StringUtils.hasText(uri.getScheme()) || !StringUtils.hasText(uri.getHost())) {
                return null;
            }
            return uri.getScheme() + "://" + uri.getHost();
        } catch (Exception ex) {
            return null;
        }
    }

    private String resolveF2PoolReferer() {
        if (StringUtils.hasText(cfxF2PoolApiReferer)) {
            return cfxF2PoolApiReferer;
        }
        String origin = resolveF2PoolOrigin();
        if (StringUtils.hasText(origin)) {
            return origin.endsWith("/") ? origin : origin + "/";
        }
        return null;
    }

    private JsonNode resolveF2PoolDataNode(JsonNode root, boolean isV2Api) {
        if (root == null || root.isMissingNode()) {
            return root;
        }
        JsonNode dataNode;
        // web 模式通常是 root.data
        if (!isV2Api) {
            dataNode = root.path("data");
        } else {
            // v2 模式优先尝试 root.data，其次 root 本身
            JsonNode data = root.path("data");
            dataNode = (!data.isMissingNode() && !data.isNull()) ? data : root;
        }
        if (dataNode.isArray()) {
            for (JsonNode node : dataNode) {
                String currency = node.path("currency").asText();
                String code = node.path("code").asText();
                if ("conflux".equalsIgnoreCase(currency) || "cfx".equalsIgnoreCase(code)) {
                    return node;
                }
            }
            return dataNode.size() > 0 ? dataNode.get(0) : dataNode;
        }
        return dataNode;
    }

    private boolean isJsonLike(String body) {
        if (!StringUtils.hasText(body)) {
            return false;
        }
        String trimmed = body.trim();
        return trimmed.startsWith("{") || trimmed.startsWith("[");
    }

    private boolean tryParseF2PoolProfitFromHtml(String html) {
        if (!StringUtils.hasText(html)) {
            return false;
        }
        Matcher profitMatcher = F2POOL_HTML_PROFIT_PATTERN.matcher(html);
        if (!profitMatcher.find()) {
            markCfxRefreshError("F2Pool HTML missing data-profit");
            log.warn("CFX F2Pool HTML 响应中缺少 data-profit 字段");
            return false;
        }
        BigDecimal dailyCoinPerMh = new BigDecimal(profitMatcher.group(1));
        if (!isPositive(dailyCoinPerMh)) {
            markCfxRefreshError("F2Pool HTML data-profit is not positive: " + dailyCoinPerMh);
            log.warn("CFX F2Pool HTML data-profit 无效: {}", dailyCoinPerMh);
            return false;
        }
        BigDecimal estimatedProfitUsd = null;
        Matcher usdMatcher = F2POOL_HTML_USD_PATTERN.matcher(html);
        if (usdMatcher.find()) {
            try {
                estimatedProfitUsd = new BigDecimal(usdMatcher.group(1));
            } catch (Exception ex) {
                log.debug("CFX F2Pool HTML USD 解析失败: {}", ex.getMessage());
            }
        }
        cfxDailyCoinPerMh.set(dailyCoinPerMh.setScale(12, RoundingMode.HALF_UP));
        cfxProfitSource.set("f2pool-page");
        markCfxRefreshStatus(dailyCoinPerMh, null, null);
        log.info("CFX F2Pool 页面数据刷新成功: dailyCoinPerMh={}, estimatedProfitUsd={}",
                dailyCoinPerMh, estimatedProfitUsd);
        return true;
    }

    private void refreshCoinProfitability(String coin, String statsUrl, String profitPerMhPath, String networkHashratePath,
                                          String networkHashrateUnit, BigDecimal blockReward, BigDecimal blockTimeSeconds,
                                          AtomicReference<BigDecimal> target) {
        boolean isCfx = "CFX".equalsIgnoreCase(coin);
        if (!StringUtils.hasText(statsUrl)) {
            if (isCfx) {
                markCfxRefreshError("cfx-stats-url is empty");
                log.warn("CFX市场数据刷新失败: statsUrl为空");
            }
            return;
        }
        try {
            String body = poolStatsClient.get()
                    .uri(statsUrl)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofMillis(Math.max(1000L, poolStatsTimeoutMs)))
                    .block();
            if (!StringUtils.hasText(body)) {
                if (isCfx) {
                    markCfxRefreshError("cfx-stats-url returned empty body");
                    log.warn("CFX市场数据刷新失败: API返回空响应, url={}", statsUrl);
                }
                return;
            }
            JsonNode root = objectMapper.readTree(body);
            BigDecimal profitPerMh = readDecimalByPath(body, root, profitPerMhPath);
            BigDecimal networkHashrate = readHashrateByPath(body, root, networkHashratePath, networkHashrateUnit);
            if (!isPositive(profitPerMh)) {
                BigDecimal dailyCoins = calculateDailyCoins(blockReward, blockTimeSeconds);
                if (isPositive(networkHashrate) && isPositive(dailyCoins)) {
                    profitPerMh = dailyCoins.divide(networkHashrate, 12, RoundingMode.HALF_UP);
                    if (isCfx) {
                        log.debug("CFX收益计算: blockReward={}, blockTimeSeconds={}, dailyCoins={}, networkHashrate={}, profitPerMh={}",
                                blockReward, blockTimeSeconds, dailyCoins, networkHashrate, profitPerMh);
                    }
                } else {
                    if (isCfx) {
                        log.warn("CFX收益计算失败: networkHashrate={}, dailyCoins={}, blockReward={}, blockTimeSeconds={}",
                                networkHashrate, dailyCoins, blockReward, blockTimeSeconds);
                    }
                }
            }
            if ("CFX".equalsIgnoreCase(coin)) {
                updateCfxDiagnostics(networkHashrate, blockTimeSeconds);
            }
            if (isPositive(profitPerMh)) {
                target.set(profitPerMh.setScale(12, RoundingMode.HALF_UP));
                if (isCfx) {
                    cfxProfitSource.set("conflux");
                    log.info("CFX市场数据刷新成功: dailyCoinPerMh={}", profitPerMh);
                }
            } else {
                if (isCfx) {
                    log.warn("CFX市场数据刷新失败: profitPerMh为0, profitPerMhPath={}, networkHashratePath={}, networkHashrate={}",
                            profitPerMhPath, networkHashratePath, networkHashrate);
                }
            }
            if (isCfx) {
                updateCfxDiagnostics(networkHashrate, blockTimeSeconds);
                markCfxRefreshStatus(profitPerMh, networkHashrate, blockTimeSeconds);
            }
        } catch (Exception ex) {
            if (isCfx) {
                markCfxRefreshError("cfx-stats-url fetch failed: " + ex.getMessage());
                log.warn("CFX市场数据刷新失败: url={}, error={}", statsUrl, ex.getMessage(), ex);
            } else {
                log.warn("Unable to refresh {} profitability: url={}, error={}", coin, statsUrl, ex.getMessage());
            }
        }
    }

    private void updateCfxDiagnostics(BigDecimal networkHashrate, BigDecimal blockTimeSeconds) {
        if (isPositive(networkHashrate)) {
            cfxNetworkHashrateMh.set(networkHashrate.setScale(6, RoundingMode.HALF_UP));
        }
        if (isPositive(blockTimeSeconds)) {
            cfxBlockTimeSecondsCache.set(blockTimeSeconds.setScale(6, RoundingMode.HALF_UP));
        }
    }

    private void markCfxRefreshStatus(BigDecimal profitPerMh, BigDecimal networkHashrate, BigDecimal blockTimeSeconds) {
        if (isPositive(profitPerMh) || isPositive(networkHashrate) || isPositive(blockTimeSeconds)) {
            cfxStatsLastRefreshAt.set(System.currentTimeMillis());
            cfxStatsLastError.set(null);
            return;
        }
        markCfxRefreshError("cfx stats missing profitability/networkHashrate/blockTime");
    }

    private void markCfxRefreshError(String message) {
        cfxStatsLastRefreshAt.set(System.currentTimeMillis());
        cfxStatsLastError.set(message);
    }

    private BigDecimal extractHashrate(JsonNode root) {
        if (root == null || root.isMissingNode()) {
            return null;
        }
        // c3pool 现网结构示例：
        // { "pool_statistics": { "hashRate": 388187818.1, "activePortProfit": ... } }
        BigDecimal v;
        v = readHashrate(root.at("/pool_statistics/hashRate"));
        if (isPositive(v)) return v;
        v = readHashrate(root.at("/pool_statistics/hashrate"));
        if (isPositive(v)) return v;
        v = readHashrate(root.at("/pool_statistics/hash"));
        if (isPositive(v)) return v;

        // 兼容：hashRate / hashrate / hash（root 级别）
        v = readHashrate(root.get("hashRate"));
        if (isPositive(v)) return v;
        v = readHashrate(root.get("hashrate"));
        if (isPositive(v)) return v;
        v = readHashrate(root.get("hash"));
        if (isPositive(v)) return v;

        JsonNode nestedPool = root.get("pool");
        if (nestedPool != null && !nestedPool.isMissingNode()) {
            BigDecimal nested = extractHashrate(nestedPool);
            if (isPositive(nested)) {
                return nested;
            }
        }

        // 兼容：部分实现可能直接返回 pool_statistics 对象
        JsonNode poolStatistics = root.get("pool_statistics");
        if (poolStatistics != null && !poolStatistics.isMissingNode()) {
            BigDecimal nested = extractHashrate(poolStatistics);
            if (isPositive(nested)) {
                return nested;
            }
        }
        return null;
    }

    private BigDecimal extractActivePortProfit(JsonNode root) {
        if (root == null || root.isMissingNode()) {
            return null;
        }
        BigDecimal v;
        v = readDecimal(root.at("/pool_statistics/activePortProfit"));
        if (isPositive(v)) return v;
        v = readDecimal(root.get("activePortProfit"));
        if (isPositive(v)) return v;

        JsonNode nestedPool = root.get("pool");
        if (nestedPool != null && !nestedPool.isMissingNode()) {
            BigDecimal nested = extractActivePortProfit(nestedPool);
            if (isPositive(nested)) {
                return nested;
            }
        }
        JsonNode poolStatistics = root.get("pool_statistics");
        if (poolStatistics != null && !poolStatistics.isMissingNode()) {
            BigDecimal nested = extractActivePortProfit(poolStatistics);
            if (isPositive(nested)) {
                return nested;
            }
        }
        return null;
    }

    private BigDecimal readHashrate(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            // 外部未带单位时，默认按 H/s
            return BigDecimal.valueOf(node.asDouble()).divide(BigDecimal.valueOf(1_000_000L), 8, RoundingMode.HALF_UP);
        }
        if (node.isTextual()) {
            return parseHashrateText(node.asText());
        }
        return null;
    }

    private BigDecimal readDecimal(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return BigDecimal.valueOf(node.asDouble());
        }
        if (node.isTextual()) {
            String t = node.asText();
            if (!StringUtils.hasText(t)) {
                return null;
            }
            try {
                return new BigDecimal(t.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private BigDecimal readDecimalByPath(String body, JsonNode root, String path) {
        if (!StringUtils.hasText(path) || root == null) {
            return null;
        }
        if (path.startsWith("$")) {
            try {
                Object value = JsonPath.read(body, path);
                return parseDecimal(value);
            } catch (Exception ignored) {
                return null;
            }
        }
        JsonNode node = path.startsWith("/") ? root.at(path) : root.get(path);
        return readDecimal(node);
    }

    private BigDecimal readHashrateByPath(String body, JsonNode root, String path, String unitOverride) {
        if (!StringUtils.hasText(path) || root == null) {
            return null;
        }
        if (path.startsWith("$")) {
            try {
                Object value = JsonPath.read(body, path);
                return toHashrateMhs(value, unitOverride);
            } catch (Exception ignored) {
                return null;
            }
        }
        JsonNode node = path.startsWith("/") ? root.at(path) : root.get(path);
        return readHashrate(node, unitOverride);
    }

    private BigDecimal resolveBlockTimeSeconds(BigDecimal fallbackSeconds, String url, String path) {
        if (isPositive(fallbackSeconds)) {
            return fallbackSeconds;
        }
        BigDecimal fetched = fetchDecimalFromUrl(url, path);
        return isPositive(fetched) ? fetched : BigDecimal.ZERO;
    }

    private BigDecimal resolveCfxBlockTimeSeconds() {
        if (isPositive(cfxBlockTimeSeconds)) {
            return cfxBlockTimeSeconds;
        }
        BigDecimal fromRpc = fetchCfxBlockTimeFromRpc();
        if (isPositive(fromRpc)) {
            return fromRpc;
        }
        return resolveBlockTimeSeconds(BigDecimal.ZERO, cfxBlockTimeSecondsUrl, cfxBlockTimeSecondsPath);
    }

    private BigDecimal fetchCfxBlockTimeFromRpc() {
        if (!StringUtils.hasText(cfxRpcUrl)) {
            return null;
        }
        try {
            JsonNode epochNode = fetchJsonRpcResult(cfxRpcUrl, "cfx_epochNumber", List.of());
            Long epoch = parseHexLong(epochNode);
            if (epoch == null || epoch < 2) {
                return null;
            }
            JsonNode latestBlock = fetchJsonRpcResult(
                    cfxRpcUrl,
                    "cfx_getBlockByEpochNumber",
                    List.of(toHex(epoch), Boolean.FALSE));
            JsonNode prevBlock = fetchJsonRpcResult(
                    cfxRpcUrl,
                    "cfx_getBlockByEpochNumber",
                    List.of(toHex(epoch - 1), Boolean.FALSE));
            Long latestTs = extractBlockTimestamp(latestBlock);
            Long prevTs = extractBlockTimestamp(prevBlock);
            if (latestTs == null || prevTs == null) {
                return null;
            }
            long diff = latestTs - prevTs;
            if (diff <= 0) {
                return null;
            }
            return BigDecimal.valueOf(diff);
        } catch (Exception ex) {
            return null;
        }
    }

    private JsonNode fetchJsonRpcResult(String url, String method, List<Object> params) {
        try {
            String requestBody = objectMapper.writeValueAsString(Map.of(
                    "jsonrpc", "2.0",
                    "id", 1,
                    "method", method,
                    "params", params == null ? List.of() : params
            ));
            String body = poolStatsClient.post()
                    .uri(url)
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofMillis(Math.max(1000L, poolStatsTimeoutMs)))
                    .block();
            if (!StringUtils.hasText(body)) {
                return null;
            }
            JsonNode root = objectMapper.readTree(body);
            return root.get("result");
        } catch (Exception ex) {
            return null;
        }
    }

    private Long extractBlockTimestamp(JsonNode blockNode) {
        if (blockNode == null || blockNode.isMissingNode()) {
            return null;
        }
        JsonNode tsNode = blockNode.get("timestamp");
        Long ts = parseHexLong(tsNode);
        if (ts == null) {
            return null;
        }
        // 如果是毫秒级时间戳，转换为秒
        if (ts > 1_000_000_000_000L) {
            return ts / 1000L;
        }
        return ts;
    }

    private Long parseHexLong(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        String text = node.isTextual() ? node.asText() : node.asText();
        if (!StringUtils.hasText(text)) {
            return null;
        }
        String trimmed = text.trim();
        try {
            if (trimmed.startsWith("0x") || trimmed.startsWith("0X")) {
                return Long.parseLong(trimmed.substring(2), 16);
            }
            return Long.parseLong(trimmed);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String toHex(long value) {
        return "0x" + Long.toHexString(value);
    }

    private BigDecimal fetchDecimalFromUrl(String url, String path) {
        if (!StringUtils.hasText(url)) {
            return null;
        }
        try {
            String body = poolStatsClient.get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofMillis(Math.max(1000L, poolStatsTimeoutMs)))
                    .block();
            if (!StringUtils.hasText(body)) {
                return null;
            }
            JsonNode root = objectMapper.readTree(body);
            return readDecimalByPath(body, root, path);
        } catch (Exception ex) {
            return null;
        }
    }

    private BigDecimal toHashrateMhs(Object value, String unitOverride) {
        if (value == null) {
            return null;
        }
        if (StringUtils.hasText(unitOverride)) {
            BigDecimal raw = parseDecimal(value);
            BigDecimal multiplier = resolveHashrateUnitMultiplier(unitOverride);
            if (raw != null && multiplier != null) {
                return raw.multiply(multiplier);
            }
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue())
                    .divide(BigDecimal.valueOf(1_000_000L), 8, RoundingMode.HALF_UP);
        }
        String text = value.toString();
        return parseHashrateText(text);
    }

    private BigDecimal readHashrate(JsonNode node, String unitOverride) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (StringUtils.hasText(unitOverride)) {
            BigDecimal raw = readDecimal(node);
            BigDecimal multiplier = resolveHashrateUnitMultiplier(unitOverride);
            if (raw != null && multiplier != null) {
                return raw.multiply(multiplier);
            }
        }
        return readHashrate(node);
    }

    private BigDecimal resolveHashrateUnitMultiplier(String unit) {
        if (!StringUtils.hasText(unit)) {
            return null;
        }
        String normalized = unit.trim().toUpperCase(Locale.ROOT);
        return HASHRATE_UNITS.get(normalized);
    }

    private BigDecimal parseDecimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        String text = value.toString();
        if (!StringUtils.hasText(text)) {
            return null;
        }
        try {
            return new BigDecimal(text.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private BigDecimal parseHashrateText(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        String normalized = text.trim().toUpperCase(Locale.ROOT);
        for (Map.Entry<String, BigDecimal> entry : HASHRATE_UNITS.entrySet()) {
            if (normalized.endsWith(entry.getKey())) {
                String numericPart = normalized.substring(0, normalized.length() - entry.getKey().length()).trim();
                if (!StringUtils.hasText(numericPart)) {
                    return null;
                }
                try {
                    return new BigDecimal(numericPart).multiply(entry.getValue());
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        try {
            return new BigDecimal(normalized);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private BigDecimal calculateDailyCoins(BigDecimal blockReward, BigDecimal blockTimeSeconds) {
        if (!isPositive(blockReward) || blockTimeSeconds == null || blockTimeSeconds.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal blocksPerDay = BigDecimal.valueOf(86_400)
                .divide(blockTimeSeconds, 8, RoundingMode.HALF_UP);
        return blockReward.multiply(blocksPerDay);
    }

    private boolean isPositive(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) > 0;
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
