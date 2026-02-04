package com.slb.mining_backend.modules.xmr.service.f2pool;

import com.slb.mining_backend.modules.xmr.config.F2PoolProperties;
import com.slb.mining_backend.modules.xmr.entity.F2PoolAccountOverview;
import com.slb.mining_backend.modules.xmr.mapper.F2PoolAccountOverviewMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Set;

@Service
@Slf4j
public class F2PoolAccountSyncService {

    private static final ZoneId BJT = ZoneId.of("Asia/Shanghai");
    private static final Set<String> HASHRATE_SUPPORTED_COINS = Set.of(
            "bitcoin", "btc",
            "bitcoin-cash", "bitcoincash", "bch",
            "litecoin", "ltc"
    );

    private final F2PoolProperties properties;
    private final F2PoolClient client;
    private final F2PoolParser parser;
    private final F2PoolPayloadService payloadService;
    private final F2PoolAccountOverviewMapper overviewMapper;
    private final F2PoolReconcileService reconcileService;
    private final F2PoolAlertService alertService;

    @Value("${app.f2pool.account-sync-cron-enabled:false}")
    private boolean accountSyncCronEnabled;

    public F2PoolAccountSyncService(F2PoolProperties properties,
                                    F2PoolClient client,
                                    F2PoolParser parser,
                                    F2PoolPayloadService payloadService,
                                    F2PoolAccountOverviewMapper overviewMapper,
                                    F2PoolReconcileService reconcileService,
                                    F2PoolAlertService alertService) {
        this.properties = properties;
        this.client = client;
        this.parser = parser;
        this.payloadService = payloadService;
        this.overviewMapper = overviewMapper;
        this.reconcileService = reconcileService;
        this.alertService = alertService;
    }

    @Scheduled(fixedDelayString = "${app.f2pool.account-sync-interval-ms:90000}")
    public void syncAccountOverview() {
        syncAccountOverviewInternal();
    }

    @Scheduled(cron = "${app.f2pool.account-sync-cron:0 0 * * * ?}", zone = "Asia/Shanghai")
    public void syncAccountOverviewHourlySnapshot() {
        if (!accountSyncCronEnabled) {
            return;
        }
        syncAccountOverviewInternal();
    }

    private void syncAccountOverviewInternal() {
        if (!properties.isEnabled() || CollectionUtils.isEmpty(properties.getAccounts())) {
            return;
        }
        for (F2PoolProperties.Account account : properties.getAccounts()) {
            if (account == null || !StringUtils.hasText(account.getName()) || !StringUtils.hasText(account.getCoin())) {
                continue;
            }
            F2PoolClient.F2PoolRawResponse response = client.fetchAccountOverview(account);
            if (handleHttpError(account, response, "account")) {
                continue;
            }
            if (!StringUtils.hasText(response.body())) {
                alertService.raiseAlert(account.getName(), account.getCoin(), null,
                        "API_ERROR", "WARN", "account", "empty account overview response");
                continue;
            }
            String fingerprint = payloadService.persistPayload(
                    account.getName(),
                    account.getCoin(),
                    "account_overview",
                    response.body(),
                    response.fetchedAt());
            F2PoolParser.ParsedAccountOverview parsed = parser.parseAccountOverview(response.body());
            if (parsed == null) {
                alertService.raiseAlert(account.getName(), account.getCoin(), null,
                        "PARSE_FAILED", "WARN", "account", "failed to parse account overview");
                continue;
            }
            boolean supportsHashrate = supportsHashrate(account.getCoin());
            if (!supportsHashrate) {
                log.debug("F2Pool account overview hashrate not supported for coin={}, skip field checks", account.getCoin());
            } else if (parsed.hashrateHps() == null || parsed.workers() == null || parsed.activeWorkers() == null) {
                String snippet = response.body().length() > 300 ? response.body().substring(0, 300) + "..." : response.body();
                log.warn("F2Pool account overview missing fields: account={}, coin={}, hashrateHps={}, workers={}, activeWorkers={}, bodySnippet={}",
                        account.getName(), account.getCoin(), parsed.hashrateHps(), parsed.workers(), parsed.activeWorkers(), snippet);
            }

            F2PoolAccountOverview overview = new F2PoolAccountOverview();
            overview.setAccount(account.getName());
            overview.setCoin(account.getCoin());
            overview.setHashrateHps(parsed.hashrateHps() == null ? java.math.BigDecimal.ZERO : parsed.hashrateHps());
            overview.setWorkers(parsed.workers() == null ? 0 : parsed.workers());
            overview.setActiveWorkers(parsed.activeWorkers() == null ? 0 : parsed.activeWorkers());
            overview.setFixedValue(parsed.fixedValue() == null ? BigDecimal.ZERO : parsed.fixedValue());
            overview.setFetchedAt(LocalDateTime.now(BJT));
            overview.setPayloadFingerprint(fingerprint);
            overview.setCreatedTime(LocalDateTime.now(BJT));
            overviewMapper.insert(overview);
            reconcileService.reconcileHashrate(account.getName(), account.getCoin(), parsed.hashrateHps());
        }
    }

    private boolean supportsHashrate(String coin) {
        if (!StringUtils.hasText(coin)) {
            return false;
        }
        String normalized = coin.trim().toLowerCase();
        return HASHRATE_SUPPORTED_COINS.contains(normalized);
    }

    private boolean handleHttpError(F2PoolProperties.Account account, F2PoolClient.F2PoolRawResponse response, String refKey) {
        if (response == null || response.isSuccess()) {
            return false;
        }
        Integer status = response.statusCode();
        String alertType = classifyStatus(status);
        String message = buildStatusMessage(status, response.errorBody());
        alertService.raiseAlert(account.getName(), account.getCoin(), null,
                alertType, "WARN", refKey, message);
        return true;
    }

    private String classifyStatus(Integer status) {
        if (status == null) {
            return "REMOTE_ERROR";
        }
        if (status == 401 || status == 403) {
            return "AUTH_FAILED";
        }
        if (status == 429) {
            return "RATE_LIMITED";
        }
        if (status >= 500) {
            return "REMOTE_5XX";
        }
        if (status >= 400) {
            return "REMOTE_4XX";
        }
        return "REMOTE_ERROR";
    }

    private String buildStatusMessage(Integer status, String body) {
        String message = "status=" + (status != null ? status : "unknown");
        if (StringUtils.hasText(body)) {
            message += ", body=" + body;
        }
        return message;
    }
}
