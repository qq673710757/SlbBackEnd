package com.slb.mining_backend.modules.xmr.service.f2pool;

import com.slb.mining_backend.modules.earnings.service.MarketDataService;
import com.slb.mining_backend.modules.exchange.service.ExchangeRateService;
import com.slb.mining_backend.modules.xmr.config.F2PoolProperties;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

@Service
public class F2PoolValuationService {

    private static final int CAL_SCALE = 8;

    private final ExchangeRateService exchangeRateService;
    private final MarketDataService marketDataService;
    private final F2PoolProperties properties;

    public F2PoolValuationService(ExchangeRateService exchangeRateService,
                                  MarketDataService marketDataService,
                                  F2PoolProperties properties) {
        this.exchangeRateService = exchangeRateService;
        this.marketDataService = marketDataService;
        this.properties = properties;
    }

    public RateSnapshot resolveRate(String coin) {
        if (!StringUtils.hasText(coin)) {
            return null;
        }
        String normalized = coin.trim().toUpperCase(Locale.ROOT);
        BigDecimal calToCny = marketDataService.getCalToCnyRate();

        if ("CAL".equals(normalized)) {
            return new RateSnapshot(BigDecimal.ONE, calToCny, "CAL");
        }
        if ("XMR".equals(normalized)) {
            BigDecimal ratio = marketDataService.getCalXmrRatio(); // 1 CAL = ratio * XMR
            if (ratio != null && ratio.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal coinToCal = BigDecimal.ONE.divide(ratio, CAL_SCALE, RoundingMode.HALF_UP);
                return new RateSnapshot(coinToCal, calToCny, "CAL/XMR");
            }
        }

        RateSnapshot viaXmr = resolveViaXmr(normalized, calToCny);
        if (viaXmr != null) {
            return viaXmr;
        }

        BigDecimal manual = properties.getValuation() != null ? properties.getValuation().getManualCoinToCal() : null;
        String symbol = resolveCoinToCnySymbol(normalized);
        BigDecimal coinToCny = resolveCoinToCnyRate(symbol);
        if (coinToCny != null && coinToCny.compareTo(BigDecimal.ZERO) > 0
                && calToCny != null && calToCny.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal coinToCal = coinToCny.divide(calToCny, CAL_SCALE, RoundingMode.HALF_UP);
            return new RateSnapshot(coinToCal, calToCny, symbol + "/CAL");
        }
        if (manual != null && manual.compareTo(BigDecimal.ZERO) > 0) {
            return new RateSnapshot(manual, calToCny, "MANUAL");
        }
        return null;
    }

    private RateSnapshot resolveViaXmr(String coin, BigDecimal calToCny) {
        CoinRate coinToXmr = resolveCoinToXmr(coin);
        if (coinToXmr == null || coinToXmr.rate() == null || coinToXmr.rate().compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        BigDecimal ratio = marketDataService.getCalXmrRatio(); // 1 CAL = ratio * XMR
        if (ratio == null || ratio.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        BigDecimal coinToCal = coinToXmr.rate().divide(ratio, CAL_SCALE, RoundingMode.HALF_UP);
        return new RateSnapshot(coinToCal, calToCny, coinToXmr.source() + "->CAL");
    }

    private CoinRate resolveCoinToXmr(String coin) {
        if (!StringUtils.hasText(coin)) {
            return null;
        }
        F2PoolProperties.Valuation valuation = properties.getValuation();
        BigDecimal manual = valuation != null ? valuation.getManualCoinToXmr() : null;
        if (manual != null && manual.compareTo(BigDecimal.ZERO) > 0) {
            return new CoinRate(manual, "MANUAL_COIN_TO_XMR");
        }
        String symbol = resolveCoinToXmrSymbol(coin);
        BigDecimal direct = resolveCoinToXmrRate(symbol);
        if (direct != null && direct.compareTo(BigDecimal.ZERO) > 0) {
            return new CoinRate(direct, symbol);
        }
        String cnySymbol = resolveCoinToCnySymbol(coin);
        BigDecimal coinToCny = resolveCoinToCnyRate(cnySymbol);
        BigDecimal xmrToCny = exchangeRateService.getLatestRate("XMR/CNY");
        if (coinToCny != null && coinToCny.compareTo(BigDecimal.ZERO) > 0
                && xmrToCny != null && xmrToCny.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal coinToXmr = coinToCny.divide(xmrToCny, CAL_SCALE, RoundingMode.HALF_UP);
            return new CoinRate(coinToXmr, coin + "/XMR(VIA CNY)");
        }
        return null;
    }

    private String resolveCoinToCnySymbol(String coin) {
        if (properties.getValuation() != null && StringUtils.hasText(properties.getValuation().getCoinToCnySymbol())) {
            String override = properties.getValuation().getCoinToCnySymbol();
            if (matchesCoinSymbol(coin, override)) {
                return override;
            }
        }
        return normalizeCoinSymbolForRate(coin) + "/CNY";
    }

    private String resolveCoinToXmrSymbol(String coin) {
        if (properties.getValuation() != null && StringUtils.hasText(properties.getValuation().getCoinToXmrSymbol())) {
            String override = properties.getValuation().getCoinToXmrSymbol();
            if (matchesCoinSymbol(coin, override)) {
                return override;
            }
        }
        return normalizeCoinSymbolForRate(coin) + "/XMR";
    }

    private String normalizeCoinSymbolForRate(String coin) {
        if (!StringUtils.hasText(coin)) {
            return coin;
        }
        String normalized = coin.trim().toUpperCase(Locale.ROOT);
        if ("CONFLUX".equals(normalized) || "CONFLUXTOKEN".equals(normalized)) {
            return "CFX";
        }
        if ("RAVENCOIN".equals(normalized)) {
            return "RVN";
        }
        return normalized;
    }

    private boolean matchesCoinSymbol(String coin, String symbol) {
        if (!StringUtils.hasText(coin) || !StringUtils.hasText(symbol) || !symbol.contains("/")) {
            return false;
        }
        String base = symbol.substring(0, symbol.indexOf('/')).trim().toUpperCase(Locale.ROOT);
        return base.equalsIgnoreCase(normalizeCoinSymbolForRate(coin));
    }

    private BigDecimal resolveCoinToCnyRate(String symbol) {
        if (!StringUtils.hasText(symbol)) {
            return BigDecimal.ZERO;
        }
        return exchangeRateService.getLatestRate(symbol);
    }

    private BigDecimal resolveCoinToXmrRate(String symbol) {
        if (!StringUtils.hasText(symbol)) {
            return BigDecimal.ZERO;
        }
        return exchangeRateService.getLatestRate(symbol);
    }

    public record RateSnapshot(BigDecimal coinToCalRate, BigDecimal calToCnyRate, String source) {
    }

    private record CoinRate(BigDecimal rate, String source) {
    }
}
