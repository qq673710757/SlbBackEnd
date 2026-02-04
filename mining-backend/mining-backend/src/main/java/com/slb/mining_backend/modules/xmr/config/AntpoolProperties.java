package com.slb.mining_backend.modules.xmr.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.antpool")
@Data
public class AntpoolProperties {

    private boolean enabled = false;
    private String baseUrl = "https://antpool.com/api";
    private String userId = "suanlibao";
    private String clientUserId;
    private String clientId;
    private String coin = "RVN";
    private String apiKey;
    private String apiSecret;
    private String stripPrefix = "suanlibao.";
    private long workerSyncIntervalMs = 60_000L;
    private long payoutSyncIntervalMs = 300_000L;
    private int workerPageSize = 50;
    private int payoutPageSize = 50;
    private Limits limits = new Limits();
    private Valuation valuation = new Valuation();

    @Data
    public static class Limits {
        /**
         * Antpool 公共限制：600 req / 10 min => ~1 req/s。
         */
        private double perHostQps = 1.0d;
        private int maxRetries = 2;
        private long timeoutMs = 5_000L;
    }

    @Data
    public static class Valuation {
        private java.math.BigDecimal manualCoinToXmr;
        private String coinToXmrSymbol;
        private java.math.BigDecimal manualCoinToCal;
        private String coinToCnySymbol;
    }
}
