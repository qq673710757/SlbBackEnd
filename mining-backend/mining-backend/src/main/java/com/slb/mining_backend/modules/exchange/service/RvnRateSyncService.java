package com.slb.mining_backend.modules.exchange.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.slb.mining_backend.modules.exchange.entity.ExchangeRate;
import com.slb.mining_backend.modules.exchange.mapper.ExchangeRateMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
@Slf4j
public class RvnRateSyncService {

    private final ExchangeRateMapper exchangeRateMapper;
    private final WebClient webClient;

    @Value("${app.external-api.rvn-xmr-rate-url:https://api.coingecko.com/api/v3/simple/price?ids=ravencoin&vs_currencies=xmr}")
    private String rvnXmrRateUrl;

    public RvnRateSyncService(ExchangeRateMapper exchangeRateMapper) {
        this.exchangeRateMapper = exchangeRateMapper;
        this.webClient = WebClient.builder()
                .defaultHeader("User-Agent", "MiningBackend/RvnRateSync")
                .build();
    }

    @Scheduled(cron = "${app.exchange.rvn-xmr-cron:0 2 * * * ?}")
    public void refreshRvnXmrRate() {
        if (!StringUtils.hasText(rvnXmrRateUrl)) {
            return;
        }
        try {
            JsonNode node = webClient.get()
                    .uri(rvnXmrRateUrl)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();
            BigDecimal rate = extractRate(node);
            if (rate == null || rate.compareTo(BigDecimal.ZERO) <= 0) {
                return;
            }
            ExchangeRate record = new ExchangeRate();
            record.setSymbol("RVN/XMR");
            record.setPx(rate);
            record.setSource("CoinGecko");
            exchangeRateMapper.insert(record);
            log.info("RVN/XMR rate refreshed: {}", rate);
        } catch (Exception ex) {
            log.warn("Failed to refresh RVN/XMR rate: {}", ex.getMessage());
        }
    }

    private BigDecimal extractRate(JsonNode node) {
        if (node == null || node.isMissingNode()) {
            return null;
        }
        JsonNode ravencoin = node.get("ravencoin");
        if (ravencoin != null && !ravencoin.isMissingNode()) {
            JsonNode direct = ravencoin.get("xmr");
            if (direct != null && !direct.isMissingNode()) {
                try {
                    return new BigDecimal(direct.asText());
                } catch (NumberFormatException ignored) {
                    // fall through
                }
            }
        }
        BigDecimal rvnUsd = readPrice(node, "ravencoin", "usd");
        BigDecimal xmrUsd = readPrice(node, "monero", "usd");
        if (rvnUsd == null || xmrUsd == null || rvnUsd.compareTo(BigDecimal.ZERO) <= 0 || xmrUsd.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        return rvnUsd.divide(xmrUsd, 12, RoundingMode.HALF_UP);
    }

    private BigDecimal readPrice(JsonNode root, String coinId, String vs) {
        if (root == null || root.isMissingNode()) {
            return null;
        }
        JsonNode coin = root.get(coinId);
        if (coin == null || coin.isMissingNode()) {
            return null;
        }
        JsonNode priceNode = coin.get(vs);
        if (priceNode == null || priceNode.isMissingNode()) {
            return null;
        }
        try {
            return new BigDecimal(priceNode.asText());
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
