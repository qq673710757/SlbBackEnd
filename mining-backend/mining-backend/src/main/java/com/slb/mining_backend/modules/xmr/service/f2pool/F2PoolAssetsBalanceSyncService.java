package com.slb.mining_backend.modules.xmr.service.f2pool;

import com.slb.mining_backend.modules.xmr.config.F2PoolProperties;
import com.slb.mining_backend.modules.xmr.entity.F2PoolAssetsBalance;
import com.slb.mining_backend.modules.xmr.mapper.F2PoolAssetsBalanceMapper;
import com.slb.mining_backend.modules.xmr.mapper.F2PoolPayhashHourlySettlementMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Service
@Slf4j
public class F2PoolAssetsBalanceSyncService {

    private static final ZoneId BJT = ZoneId.of("Asia/Shanghai");
    private static final BigDecimal CFX_ESTIMATED_INCOME_SCALE = BigDecimal.valueOf(10_000_000_000L);

    private final F2PoolProperties properties;
    private final F2PoolClient client;
    private final F2PoolParser parser;
    private final F2PoolPayloadService payloadService;
    private final F2PoolAssetsBalanceMapper balanceMapper;
    private final F2PoolPayhashHourlySettlementMapper hourlySettlementMapper;
    private final F2PoolReconcileService reconcileService;
    private final F2PoolAlertService alertService;
    private final boolean enabled;
    private final boolean calculateEstimatedIncome;
    private final boolean historicalTotalIncomeOutcome;
    private final boolean preResetSnapshotEnabled;
    private final int estimatedResetHour;
    private final int estimatedResetMinute;
    private final int estimatedResetWindowMinutes;

    public F2PoolAssetsBalanceSyncService(F2PoolProperties properties,
                                          F2PoolClient client,
                                          F2PoolParser parser,
                                          F2PoolPayloadService payloadService,
                                          F2PoolAssetsBalanceMapper balanceMapper,
                                          F2PoolPayhashHourlySettlementMapper hourlySettlementMapper,
                                          F2PoolReconcileService reconcileService,
                                          F2PoolAlertService alertService,
                                          @Value("${app.f2pool.assets-balance.enabled:true}") boolean enabled,
                                          @Value("${app.f2pool.assets-balance.calculate-estimated-income:true}") boolean calculateEstimatedIncome,
                                          @Value("${app.f2pool.assets-balance.historical-total-income-outcome:true}") boolean historicalTotalIncomeOutcome,
                                          @Value("${app.f2pool.assets-balance.pre-reset-cron-enabled:false}") boolean preResetSnapshotEnabled,
                                          @Value("${app.f2pool.assets-balance.estimated-today-reset-hour:8}") int estimatedResetHour,
                                          @Value("${app.f2pool.assets-balance.estimated-today-reset-minute:0}") int estimatedResetMinute,
                                          @Value("${app.f2pool.assets-balance.estimated-today-reset-window-minutes:2}") int estimatedResetWindowMinutes) {
        this.properties = properties;
        this.client = client;
        this.parser = parser;
        this.payloadService = payloadService;
        this.balanceMapper = balanceMapper;
        this.hourlySettlementMapper = hourlySettlementMapper;
        this.reconcileService = reconcileService;
        this.alertService = alertService;
        this.enabled = enabled;
        this.calculateEstimatedIncome = calculateEstimatedIncome;
        this.historicalTotalIncomeOutcome = historicalTotalIncomeOutcome;
        this.preResetSnapshotEnabled = preResetSnapshotEnabled;
        this.estimatedResetHour = clampRange(estimatedResetHour, 0, 23);
        this.estimatedResetMinute = clampRange(estimatedResetMinute, 0, 59);
        this.estimatedResetWindowMinutes = Math.max(0, estimatedResetWindowMinutes);
    }

    @Scheduled(fixedDelayString = "${app.f2pool.assets-balance.sync-interval-ms:300000}")
    public void syncAssetsBalance() {
        if (!enabled || properties == null || !properties.isEnabled()) {
            return;
        }
        if (!properties.isV2()) {
            log.warn("F2Pool assets balance sync skipped: apiVersion={} requires v2", properties.getApiVersion());
            return;
        }
        if (CollectionUtils.isEmpty(properties.getAccounts())) {
            return;
        }
        for (F2PoolProperties.Account account : properties.getAccounts()) {
            if (account == null || !StringUtils.hasText(account.getName()) || !StringUtils.hasText(account.getCoin())) {
                continue;
            }
            syncAccountBalance(account);
        }
    }

    @Scheduled(cron = "${app.f2pool.assets-balance.pre-reset-cron:0 59 7 * * ?}", zone = "Asia/Shanghai")
    public void syncAssetsBalancePreResetSnapshot() {
        if (!preResetSnapshotEnabled) {
            return;
        }
        if (!enabled || properties == null || !properties.isEnabled()) {
            return;
        }
        if (!properties.isV2()) {
            log.warn("F2Pool assets balance pre-reset snapshot skipped: apiVersion={} requires v2", properties.getApiVersion());
            return;
        }
        if (CollectionUtils.isEmpty(properties.getAccounts())) {
            return;
        }
        for (F2PoolProperties.Account account : properties.getAccounts()) {
            if (account == null || !StringUtils.hasText(account.getName()) || !StringUtils.hasText(account.getCoin())) {
                continue;
            }
            if (!isConflux(normalizeCoin(account.getCoin()))) {
                continue;
            }
            syncAccountBalance(account);
        }
    }

    private void syncAccountBalance(F2PoolProperties.Account account) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("currency", account.getCoin());
        payload.put("mining_user_name", account.getName());
        payload.put("calculate_estimated_income", calculateEstimatedIncome);
        payload.put("historical_total_income_outcome", historicalTotalIncomeOutcome);

        F2PoolClient.F2PoolRawResponse response = client.fetchAssetsBalanceV2(account, payload);
        if (handleHttpError(account, response, "assets_balance")) {
            return;
        }
        if (response == null || !StringUtils.hasText(response.body())) {
            alertService.raiseAlert(account.getName(), account.getCoin(), null,
                    "API_ERROR", "WARN", "assets_balance", "empty assets balance response");
            return;
        }

        String fingerprint = payloadService.persistPayload(
                account.getName(),
                account.getCoin(),
                "assets_balance",
                response.body(),
                response.fetchedAt());

        Optional<F2PoolParser.V2Error> v2Error = parser.detectV2Error(response.body());
        if (v2Error.isPresent()) {
            F2PoolParser.V2Error err = v2Error.get();
            alertService.raiseAlert(account.getName(), account.getCoin(), null,
                    "REMOTE_ERROR", "WARN", "assets_balance",
                    "code=" + err.code() + ", msg=" + err.msg());
            return;
        }

        F2PoolParser.ParsedAssetsBalance parsed = parser.parseAssetsBalanceV2(response.body());
        if (parsed == null) {
            alertService.raiseAlert(account.getName(), account.getCoin(), null,
                    "PARSE_FAILED", "WARN", "assets_balance", "no balance_info parsed");
            return;
        }

        LocalDateTime fetchedAt = toLocalDateTime(response.fetchedAt());
        Optional<F2PoolAssetsBalance> prevOpt = selectPrevious(account, fetchedAt);

        F2PoolAssetsBalance record = new F2PoolAssetsBalance();
        record.setAccount(account.getName());
        record.setCoin(account.getCoin());
        record.setMiningUserName(account.getName());
        record.setAddress(null);
        record.setCalculateEstimatedIncome(calculateEstimatedIncome);
        record.setHistoricalTotalIncomeOutcome(historicalTotalIncomeOutcome);
        record.setBalance(safe(parsed.balance()));
        record.setImmatureBalance(safe(parsed.immatureBalance()));
        record.setPaid(safe(parsed.paid()));
        record.setTotalIncome(safe(parsed.totalIncome()));
        record.setYesterdayIncome(safe(parsed.yesterdayIncome()));
        BigDecimal estimatedRaw = safe(parsed.estimatedTodayIncome());
        BigDecimal estimatedNormalized = normalizeEstimatedTodayIncome(account.getCoin(), parsed.estimatedTodayIncome());
        if (shouldZeroEstimatedTodayIncome(account.getCoin(), fetchedAt)) {
            estimatedRaw = BigDecimal.ZERO;
            estimatedNormalized = BigDecimal.ZERO;
        }
        record.setEstimatedTodayIncomeRaw(estimatedRaw);
        record.setEstimatedTodayIncome(estimatedNormalized);
        record.setPayloadFingerprint(fingerprint);
        record.setFetchedAt(fetchedAt);
        record.setCreatedTime(LocalDateTime.now(BJT));

        try {
            balanceMapper.insert(record);
        } catch (DataAccessException ex) {
            log.warn("F2Pool assets balance insert failed (account={}, coin={}, error={})",
                    account.getName(), account.getCoin(), ex.getMessage());
            return;
        }

        reconcileDelta(account, record, prevOpt);
    }

    private Optional<F2PoolAssetsBalance> selectPrevious(F2PoolProperties.Account account, LocalDateTime fetchedAt) {
        if (balanceMapper == null || account == null || fetchedAt == null) {
            return Optional.empty();
        }
        LocalDateTime before = fetchedAt.minusNanos(1);
        return balanceMapper.selectLatestBefore(account.getName(), account.getCoin(), before);
    }

    private void reconcileDelta(F2PoolProperties.Account account,
                                F2PoolAssetsBalance current,
                                Optional<F2PoolAssetsBalance> prevOpt) {
        if (account == null || current == null || prevOpt.isEmpty()) {
            return;
        }
        BigDecimal currentTotal = safe(current.getTotalIncome());
        BigDecimal prevTotal = safe(prevOpt.get().getTotalIncome());
        BigDecimal delta = currentTotal.subtract(prevTotal);
        if (delta.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        LocalDateTime start = truncateToHour(prevOpt.get().getFetchedAt());
        LocalDateTime end = truncateToHour(current.getFetchedAt());
        if (start == null || end == null || !end.isAfter(start)) {
            return;
        }
        BigDecimal settlementSum = hourlySettlementMapper
                .sumTotalCoinByWindowStartRange(account.getName(), account.getCoin(), start, end);
        if (settlementSum == null || settlementSum.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        reconcileService.reconcileRevenue(account.getName(), account.getCoin(), delta, settlementSum);
    }

    private LocalDateTime truncateToHour(LocalDateTime time) {
        if (time == null) {
            return null;
        }
        return time.truncatedTo(ChronoUnit.HOURS);
    }

    private LocalDateTime toLocalDateTime(Instant instant) {
        if (instant == null) {
            return LocalDateTime.now(BJT);
        }
        return LocalDateTime.ofInstant(instant, BJT);
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private boolean shouldZeroEstimatedTodayIncome(String coin, LocalDateTime fetchedAt) {
        if (estimatedResetWindowMinutes <= 0) {
            return false;
        }
        String normalized = normalizeCoin(coin);
        if (!isConflux(normalized)) {
            return false;
        }
        if (fetchedAt == null) {
            return false;
        }
        LocalDateTime resetAt = fetchedAt.toLocalDate().atTime(estimatedResetHour, estimatedResetMinute);
        LocalDateTime resetEnd = resetAt.plusMinutes(estimatedResetWindowMinutes);
        boolean inWindow = !fetchedAt.isBefore(resetAt) && !fetchedAt.isAfter(resetEnd);
        if (inWindow) {
            log.info("F2Pool assets balance: zero estimated_today_income for reset window (coin={}, fetchedAt={}, resetAt={}, resetEnd={})",
                    coin, fetchedAt, resetAt, resetEnd);
        }
        return inWindow;
    }

    private BigDecimal normalizeEstimatedTodayIncome(String coin, BigDecimal estimated) {
        BigDecimal value = safe(estimated);
        if (value.compareTo(BigDecimal.ZERO) <= 0) {
            return value;
        }
        String normalized = normalizeCoin(coin);
        if (isConflux(normalized)) {
            return value.divide(CFX_ESTIMATED_INCOME_SCALE, 12, RoundingMode.HALF_UP);
        }
        return value;
    }

    private boolean isConflux(String normalized) {
        return "conflux".equals(normalized) || "confluxtoken".equals(normalized) || "cfx".equals(normalized);
    }

    private int clampRange(int value, int min, int max) {
        if (value < min) {
            return min;
        }
        return Math.min(value, max);
    }

    private String normalizeCoin(String coin) {
        return StringUtils.hasText(coin) ? coin.trim().toLowerCase() : "";
    }

    private boolean handleHttpError(F2PoolProperties.Account account,
                                    F2PoolClient.F2PoolRawResponse response,
                                    String refKey) {
        if (response == null || response.isSuccess()) {
            return false;
        }
        Integer status = response.statusCode();
        String alertType = classifyStatus(status);
        String message = buildStatusMessage(status, response.errorBody());
        alertService.raiseAlert(account.getName(), account.getCoin(), null, alertType, "WARN", refKey, message);
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
