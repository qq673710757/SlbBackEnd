package com.slb.mining_backend.modules.xmr.service.f2pool;

import com.slb.mining_backend.modules.xmr.config.F2PoolProperties;
import com.slb.mining_backend.modules.earnings.service.MarketDataService;
import com.slb.mining_backend.modules.xmr.entity.XmrWalletIncoming;
import com.slb.mining_backend.modules.xmr.mapper.XmrWalletIncomingMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@Slf4j
public class F2PoolPayoutSyncService {

    private static final ZoneId BJT = ZoneId.of("Asia/Shanghai");
    private static final int XMR_SCALE = 12;

    private final F2PoolProperties properties;
    private final F2PoolClient client;
    private final F2PoolParser parser;
    private final F2PoolPayloadService payloadService;
    private final F2PoolAlertService alertService;
    private final F2PoolValuationService valuationService;
    private final MarketDataService marketDataService;
    private final XmrWalletIncomingMapper walletIncomingMapper;
    private final long adminUserId;

    public F2PoolPayoutSyncService(F2PoolProperties properties,
                                   F2PoolClient client,
                                   F2PoolParser parser,
                                   F2PoolPayloadService payloadService,
                                   F2PoolAlertService alertService,
                                   F2PoolValuationService valuationService,
                                   MarketDataService marketDataService,
                                   XmrWalletIncomingMapper walletIncomingMapper,
                                   @Value("${app.settlement.admin-user-id:1}") long adminUserId) {
        this.properties = properties;
        this.client = client;
        this.parser = parser;
        this.payloadService = payloadService;
        this.alertService = alertService;
        this.valuationService = valuationService;
        this.marketDataService = marketDataService;
        this.walletIncomingMapper = walletIncomingMapper;
        this.adminUserId = adminUserId;
    }

    @Scheduled(fixedDelayString = "${app.f2pool.payout-sync-interval-ms:300000}")
    public void syncPayouts() {
        if (!properties.isEnabled() || CollectionUtils.isEmpty(properties.getAccounts())) {
            return;
        }
        if (!properties.isV2()) {
            log.warn("F2Pool payout sync skipped: apiVersion={} requires v2", properties.getApiVersion());
            return;
        }
        for (F2PoolProperties.Account account : properties.getAccounts()) {
            if (account == null || !StringUtils.hasText(account.getName()) || !StringUtils.hasText(account.getCoin())) {
                continue;
            }
            syncPayoutHistory(account);
            if (properties.isIncludeValueLastDay()) {
                log.warn("F2Pool includeValueLastDay enabled; unpaid revenue will be treated as incoming payouts");
            syncValueLastDay(account);
            }
        }
    }

    private void syncPayoutHistory(F2PoolProperties.Account account) {
        int lookbackDays = Math.max(1, properties.getPayoutHistoryLookbackDays());
        Instant end = Instant.now();
        Instant start = end.minus(Duration.ofDays(lookbackDays));
        Map<String, Object> payload = new HashMap<>();
        payload.put("currency", account.getCoin());
        payload.put("mining_user_name", account.getName());
        payload.put("type", "payout");
        payload.put("start_time", start.getEpochSecond());
        payload.put("end_time", end.getEpochSecond());
        F2PoolClient.F2PoolRawResponse response = client.fetchPayoutHistoryV2(account, payload);
        if (handleHttpError(account, response, "transactions_list")) {
            return;
        }
        if (!StringUtils.hasText(response.body())) {
            alertService.raiseAlert(account.getName(), account.getCoin(), null,
                    "API_ERROR", "WARN", "transactions_list", "empty transactions response");
            return;
        }
        java.util.Optional<F2PoolParser.V2Error> errorMsg = parser.detectV2Error(response.body());
        if (errorMsg.isPresent()) {
            F2PoolParser.V2Error err = errorMsg.get();
            String msg = "code=" + err.code() + ", msg=" + err.msg();
            alertService.raiseAlert(account.getName(), account.getCoin(), null,
                    "REMOTE_ERROR", "WARN", "transactions_list", msg);
            return;
        }
        payloadService.persistPayload(
                account.getName(),
                account.getCoin(),
                "transactions_list",
                response.body(),
                response.fetchedAt());
        List<F2PoolParser.PayoutItem> items = parser.parsePayoutHistoryV2(response.body());
        if (items.isEmpty()) {
            java.util.OptionalInt count = parser.resolveTransactionsCountV2(response.body());
            if (count.isPresent() && count.getAsInt() == 0) {
                log.info("F2Pool transactions empty (account={}, coin={})",
                        account.getName(), account.getCoin());
                return;
            }
            alertService.raiseAlert(account.getName(), account.getCoin(), null,
                    "PARSE_FAILED", "WARN", "transactions_list", "no payout items parsed");
            return;
        }
        BigDecimal ratio = safePositive(marketDataService.getCalXmrRatio());
        for (F2PoolParser.PayoutItem item : items) {
            if (item == null || item.amount() == null || item.amount().compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            BigDecimal amountXmr = toXmrAmount(account, item.amount(), ratio);
            if (amountXmr == null || amountXmr.compareTo(BigDecimal.ZERO) <= 0) {
                log.warn("F2Pool payout skipped due to missing rate (account={}, coin={})",
                        account.getName(), account.getCoin());
                continue;
            }
            String txHash = normalizeTxHash(account, item.txId(), item);
            if (!StringUtils.hasText(txHash)) {
                log.warn("F2Pool payout skipped due to missing txId (account={}, coin={})",
                        account.getName(), account.getCoin());
                continue;
            }
            XmrWalletIncoming record = new XmrWalletIncoming();
            record.setUserId(adminUserId);
            record.setSubaddress(buildSubaddress(account));
            record.setTxHash(txHash);
            record.setAmountXmr(amountXmr);
            record.setTs(resolveTimestamp(item));
            record.setSettled(Boolean.FALSE);
            walletIncomingMapper.insertIgnore(record);
        }
    }

    private void syncValueLastDay(F2PoolProperties.Account account) {
        F2PoolClient.F2PoolRawResponse response = client.fetchValueLastDay(account);
        if (!StringUtils.hasText(response.body())) {
            return;
        }
        String fingerprint = payloadService.persistPayload(
                account.getName(),
                account.getCoin(),
                "value_last_day",
                response.body(),
                response.fetchedAt());
        BigDecimal value = parser.parseValueLastDay(response.body());
        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        BigDecimal ratio = safePositive(marketDataService.getCalXmrRatio());
        BigDecimal amountXmr = toXmrAmount(account, value, ratio);
        if (amountXmr == null || amountXmr.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        XmrWalletIncoming record = new XmrWalletIncoming();
        record.setUserId(adminUserId);
        record.setSubaddress(buildSubaddress(account));
        record.setTxHash("f2pool:unpaid:" + fingerprint);
        record.setAmountXmr(amountXmr);
        record.setTs(LocalDateTime.now(BJT));
        record.setSettled(Boolean.FALSE);
        walletIncomingMapper.insertIgnore(record);
    }

    private BigDecimal toXmrAmount(F2PoolProperties.Account account, BigDecimal amount, BigDecimal ratio) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0 || !isPositive(ratio)) {
            return BigDecimal.ZERO;
        }
        F2PoolValuationService.RateSnapshot rate = valuationService.resolveRate(account.getCoin());
        if (rate == null || rate.coinToCalRate() == null || rate.coinToCalRate().compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal amountCal = amount.multiply(rate.coinToCalRate());
        return amountCal.multiply(ratio).setScale(XMR_SCALE, java.math.RoundingMode.HALF_UP);
    }

    private String normalizeTxHash(F2PoolProperties.Account account, String txId, F2PoolParser.PayoutItem item) {
        if (StringUtils.hasText(txId)) {
            return "f2pool:" + account.getCoin().toLowerCase(Locale.ROOT) + ":" + txId.trim();
        }
        if (item == null || item.payoutDate() == null) {
            return null;
        }
        String amount = item.amount() != null ? item.amount().toPlainString() : "0";
        String ts = item.timestamp() != null ? String.valueOf(item.timestamp().getEpochSecond()) : "0";
        return "f2pool:" + account.getCoin().toLowerCase(Locale.ROOT) + ":" + account.getName() + ":"
                + item.payoutDate() + ":" + amount + ":" + ts;
    }

    private String buildSubaddress(F2PoolProperties.Account account) {
        String coin = account != null && StringUtils.hasText(account.getCoin())
                ? account.getCoin().trim().toLowerCase(Locale.ROOT) : "coin";
        String name = account != null && StringUtils.hasText(account.getName())
                ? account.getName().trim() : "account";
        return "f2pool:" + coin + ":" + name;
    }

    private LocalDateTime resolveTimestamp(F2PoolParser.PayoutItem item) {
        if (item != null && item.timestamp() != null) {
            return LocalDateTime.ofInstant(item.timestamp(), BJT);
        }
        if (item != null && item.payoutDate() != null) {
            return item.payoutDate().atStartOfDay();
        }
        return LocalDateTime.now(BJT);
    }

    private boolean isPositive(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) > 0;
    }

    private BigDecimal safePositive(BigDecimal value) {
        return isPositive(value) ? value : BigDecimal.ZERO;
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
