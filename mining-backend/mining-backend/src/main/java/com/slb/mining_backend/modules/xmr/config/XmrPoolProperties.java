package com.slb.mining_backend.modules.xmr.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
@ConfigurationProperties(prefix = "app.xmr.pool")
@Data
public class XmrPoolProperties {

    private String defaultProvider = "c3pool";
    private List<Provider> providers = new ArrayList<>();

    public Provider getDefaultProvider() {
        return findProvider(defaultProvider)
                .orElseThrow(() -> new IllegalStateException("No pool provider configured for id=" + defaultProvider));
    }

    public Optional<Provider> findProvider(String id) {
        if (providers == null) {
            return Optional.empty();
        }
        return providers.stream().filter(p -> p.getId().equalsIgnoreCase(id)).findFirst();
    }

    @Data
    public static class Provider {
        private String id;
        private String type;
        private String base;
        private Endpoints endpoints = new Endpoints();
        private Mapping mapping = new Mapping();
        private Unit unit = new Unit();
        private Limits limits = new Limits();
    }

    @Data
    public static class Endpoints {
        private String stats;
        private String workers;
        private String payments;
        private String pool;
    }

    @Data
    public static class Mapping {
        private String unpaidAtomic;
        private String paidTotalAtomic;
        private String hashrateHps;
        private String timestamp;
        private String workerArray;
        private String workerId;
        private String workerHashNowHps;
        private String workerHashAvgHps;
        private String workerTimestamp;
        private String paymentArray;
        private String paymentAmount;
        private String paymentHash;
        private String paymentTimestamp;
        private String paymentHeight;
    }

    @Data
    public static class Unit {
        private long atomicPerXmr = 1_000_000_000_000L;
    }

    @Data
    public static class Limits {
        private int perHostReqPer15Min = 60;
        private int minRefreshSecPerAddress = 90;
    }
}
