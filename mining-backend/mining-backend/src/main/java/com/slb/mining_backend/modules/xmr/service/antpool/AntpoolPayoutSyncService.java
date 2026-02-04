package com.slb.mining_backend.modules.xmr.service.antpool;

import com.slb.mining_backend.modules.exchange.entity.ExchangeRate;
import com.slb.mining_backend.modules.exchange.mapper.ExchangeRateMapper;
import com.slb.mining_backend.modules.xmr.config.AntpoolProperties;
import com.slb.mining_backend.modules.xmr.entity.XmrWalletIncoming;
import com.slb.mining_backend.modules.xmr.mapper.XmrWalletIncomingMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

@Service
@Slf4j
public class AntpoolPayoutSyncService {

    private static final ZoneId BJT = ZoneId.of("Asia/Shanghai");
    private static final String TX_PREFIX = "antpool:rvn:";
    private static final String RVN_XMR_SYMBOL = "RVN/XMR";
    private static final int XMR_SCALE = 12;

    private static final DateTimeFormatter[] TS_FORMATTERS = new DateTimeFormatter[]{
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS")
    };

    private final AntpoolProperties properties;
    private final AntpoolClient client;
    private final AntpoolParser parser;
    private final ExchangeRateMapper exchangeRateMapper;
    private final XmrWalletIncomingMapper walletIncomingMapper;
    private final long adminUserId;

    public AntpoolPayoutSyncService(AntpoolProperties properties,
                                    AntpoolClient client,
                                    AntpoolParser parser,
                                    ExchangeRateMapper exchangeRateMapper,
                                    XmrWalletIncomingMapper walletIncomingMapper,
                                    @Value("${app.settlement.admin-user-id:1}") long adminUserId) {
        this.properties = properties;
        this.client = client;
        this.parser = parser;
        this.exchangeRateMapper = exchangeRateMapper;
        this.walletIncomingMapper = walletIncomingMapper;
        this.adminUserId = adminUserId;
    }

    @Scheduled(fixedDelayString = "${app.antpool.payout-sync-interval-ms:300000}")
    public void syncPayouts() {
        if (!properties.isEnabled()) {
            return;
        }
        int page = 1;
        int pageSize = Math.max(1, properties.getPayoutPageSize());
        int inserted = 0;
        Set<String> seen = new HashSet<>();
        while (true) {
            AntpoolClient.AntpoolRawResponse response = client.fetchPayouts(page, pageSize);
            if (!StringUtils.hasText(response.body())) {
                log.warn("Antpool payout empty response (page={})", page);
                break;
            }
            List<AntpoolParser.PayoutItem> items = parser.parsePayouts(response.body());
            if (items.isEmpty()) {
                break;
            }
            for (AntpoolParser.PayoutItem item : items) {
                if (item == null || !StringUtils.hasText(item.txId())) {
                    continue;
                }
                String txHash = normalizeTxHash(item.txId());
                if (!seen.add(txHash)) {
                    continue;
                }
                BigDecimal amountRvn = normalizeAmount(item.amount());
                if (amountRvn == null || amountRvn.compareTo(BigDecimal.ZERO) <= 0) {
                    continue;
                }
                LocalDateTime payoutTs = parseTimestamp(item.timestamp());
                if (payoutTs == null) {
                    log.warn("Antpool payout timestamp invalid (txHash={}, rawTs={})", txHash, item.timestamp());
                    continue;
                }
                BigDecimal rate = resolveRate(payoutTs);
                if (rate == null || rate.compareTo(BigDecimal.ZERO) <= 0) {
                    log.warn("Antpool payout skipped due to missing RVN/XMR rate (txHash={}, payoutTs={})", txHash, payoutTs);
                    continue;
                }
                BigDecimal amountXmr = amountRvn.multiply(rate)
                        .setScale(XMR_SCALE, RoundingMode.HALF_UP);
                XmrWalletIncoming record = new XmrWalletIncoming();
                record.setUserId(adminUserId);
                record.setSubaddress(buildSubaddress());
                record.setTxHash(txHash);
                record.setAmountXmr(amountXmr);
                record.setTs(payoutTs);
                record.setSettled(Boolean.FALSE);
                int affected = walletIncomingMapper.insertIgnore(record);
                if (affected > 0) {
                    inserted++;
                }
            }
            if (items.size() < pageSize) {
                break;
            }
            page++;
        }
        if (inserted > 0) {
            log.info("Antpool payout sync inserted {} records", inserted);
        }
    }

    private String normalizeTxHash(String txId) {
        String trimmed = txId.trim();
        if (trimmed.startsWith(TX_PREFIX)) {
            return trimmed;
        }
        return TX_PREFIX + trimmed;
    }

    private BigDecimal normalizeAmount(BigDecimal amount) {
        if (amount == null) {
            return null;
        }
        return amount.abs();
    }

    private BigDecimal resolveRate(LocalDateTime payoutTs) {
        Optional<ExchangeRate> exact = exchangeRateMapper.selectLatestBeforeBySymbol(RVN_XMR_SYMBOL, payoutTs);
        if (exact.isPresent() && exact.get().getPx() != null) {
            return exact.get().getPx();
        }
        Optional<ExchangeRate> latest = exchangeRateMapper.selectLatestBySymbol(RVN_XMR_SYMBOL);
        return latest.map(ExchangeRate::getPx).orElse(BigDecimal.ZERO);
    }

    private String buildSubaddress() {
        String userId = StringUtils.hasText(properties.getUserId()) ? properties.getUserId().trim() : "suanlibao";
        return "antpool:rvn:" + userId;
    }

    private LocalDateTime parseTimestamp(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String trimmed = raw.trim();
        Long epoch = tryParseEpoch(trimmed);
        if (epoch != null) {
            Instant instant = (Math.abs(epoch) > 10_000_000_000L)
                    ? Instant.ofEpochMilli(epoch)
                    : Instant.ofEpochSecond(epoch);
            return LocalDateTime.ofInstant(instant, BJT);
        }
        if (looksLikeInstant(trimmed)) {
            try {
                return LocalDateTime.ofInstant(Instant.parse(trimmed), BJT);
            } catch (DateTimeParseException ignored) {
                return null;
            }
        }
        for (DateTimeFormatter formatter : TS_FORMATTERS) {
            try {
                return LocalDateTime.parse(trimmed, formatter);
            } catch (DateTimeParseException ignored) {
                // try next
            }
        }
        return null;
    }

    private Long tryParseEpoch(String raw) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private boolean looksLikeInstant(String raw) {
        String upper = raw.toUpperCase(Locale.ROOT);
        return upper.contains("T") && upper.endsWith("Z");
    }
}
