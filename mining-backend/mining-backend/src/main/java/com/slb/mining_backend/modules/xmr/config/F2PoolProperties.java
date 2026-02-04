package com.slb.mining_backend.modules.xmr.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "app.f2pool")
@Data
public class F2PoolProperties {

    private boolean enabled = false;
    private String apiVersion = "v2";
    private String baseUrl = "https://api.f2pool.com";
    private List<Account> accounts = new ArrayList<>();
    private Endpoints endpoints = new Endpoints();
    private V1 v1 = new V1();
    private V2 v2 = new V2();
    private Limits limits = new Limits();
    private int workerStaleSeconds = 600;
    private boolean allowSyntheticUsrFromRawWorkerId = false;
    private long workerSyncIntervalMs = 60_000L;
    private long accountSyncIntervalMs = 90_000L;
    private long payoutSyncIntervalMs = 300_000L;
    private int payoutHistoryLookbackDays = 7;
    private int workersPageSize = 200;
    private int workersMaxPages = 10;
    /**
     * 是否纳入 value_last_day（未支付收益）作为结算来源。
     * 关闭后只使用 payout_history（已打款到钱包的记录）。
     */
    private boolean includeValueLastDay = true;
    private Settlement settlement = new Settlement();
    private Reconcile reconcile = new Reconcile();
    private Valuation valuation = new Valuation();

    public boolean isV2() {
        return !"v1".equalsIgnoreCase(apiVersion);
    }

    public boolean isHashrateCoinSupported(String coin) {
        if (!StringUtils.hasText(coin)) {
            return false;
        }
        String normalized = normalizeCoin(coin);
        if (v2 == null || v2.getHashrateSupportedCoins() == null || v2.getHashrateSupportedCoins().isEmpty()) {
            return true;
        }
        for (String supported : v2.getHashrateSupportedCoins()) {
            if (normalized.equals(normalizeCoin(supported))) {
                return true;
            }
        }
        return false;
    }

    private String normalizeCoin(String coin) {
        return StringUtils.hasText(coin) ? coin.trim().toLowerCase() : "";
    }

    @Data
    public static class Account {
        private String name;
        private String coin;
        private String apiKey;
        private String apiSecret;
        private String label;
    }

    @Data
    public static class Endpoints {
        private String workers = "/v2/hash_rate/worker/list";
        private String account = "/{coin}/{account}";
        private String payoutHistory = "/{coin}/{account}/payout_history";
        private String valueLastDay = "/{coin}/{account}/value_last_day";
        private String assetsBalance = "/v2/assets/balance";
    }

    @Data
    public static class V1 {
        private Mapping mapping = new Mapping();
    }

    @Data
    public static class V2 {
        private V2Mapping mapping = new V2Mapping();
        private List<String> hashrateSupportedCoins = new ArrayList<>(List.of(
                "bitcoin", "bitcoin-cash", "litecoin", "btc", "bch", "ltc"
        ));
    }

    @Data
    public static class Mapping {
        private String workersPath = "$.workers";
        private String workerId = "$.worker_name";
        private String workerHashrate = "$.hashrate";
        private String workerHashrateUnit = "$.hashrate_unit";
        private String workerLastShare = "$.last_share_time";
        private Integer workerArrayIdIndex = 0;
        private Integer workerArrayHashIndex = 1;
        private Integer workerArrayUnitIndex = 2;
        private Integer workerArrayLastShareIndex = 3;
        private String accountHashrate = "$.hashrate";
        private String accountHashrateUnit = "$.hashrate_unit";
        private String accountWorkers = "$.workers";
        private String accountActiveWorkers = "$.active_workers";
        private String accountFixedValue = "$.fixed_value";
        private String payoutArray = "$.payout_history";
        private String payoutAmount = "$.amount";
        private String payoutTimestamp = "$.timestamp";
        private String payoutDate = "$.date";
        private String payoutTxId = "$.txid";
        private String valueLastDay = "$.value_last_day";
    }

    @Data
    public static class V2Mapping {
        private String workersPath = "$.workers";
        private String workerId = "$.hash_rate_info.name";
        private String workerHashrate = "$.hash_rate_info.hash_rate";
        private String workerHashrateH1 = "$.hash_rate_info.h1_hash_rate";
        private String workerHashrateH24 = "$.hash_rate_info.h24_hash_rate";
        private String workerLastShare = "$.last_share_at";
        private String payoutArray = "$.transactions";
        private String payoutAmount = "$.payout_extra.value";
        private String payoutTimestamp = "$.payout_extra.paid_time";
        private String payoutTxId = "$.payout_extra.tx_id";
    }

    @Data
    public static class Limits {
        private double perHostQps = 1.0d;
        private int maxRetries = 3;
        private long timeoutMs = 5_000L;
    }

    @Data
    public static class Settlement {
        private boolean enabled = true;
        private String cron = "0 10 * * * ?";
        private BigDecimal feeRate;
        private Long unclaimedUserId;
        private String defaultStatus = "AUDIT";
    }

    @Data
    public static class Reconcile {
        private BigDecimal hashrateDiffThreshold = new BigDecimal("0.05");
        private BigDecimal revenueDiffThreshold = new BigDecimal("0.02");
    }

    @Data
    public static class Valuation {
        private BigDecimal manualCoinToXmr;
        private String coinToXmrSymbol;
        private BigDecimal manualCoinToCal;
        private String coinToCnySymbol;
    }
}
