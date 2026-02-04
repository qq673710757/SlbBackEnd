package com.slb.mining_backend.modules.xmr.service;

import com.slb.mining_backend.modules.xmr.config.XmrPoolProperties;
import com.slb.mining_backend.modules.xmr.config.XmrWalletProperties;
import com.slb.mining_backend.modules.xmr.domain.PoolClient;
import com.slb.mining_backend.modules.xmr.domain.PoolClientException;
import com.slb.mining_backend.modules.xmr.domain.PoolPayment;
import com.slb.mining_backend.modules.xmr.entity.XmrWalletIncoming;
import com.slb.mining_backend.modules.xmr.mapper.XmrWalletIncomingMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@Slf4j
public class XmrWalletIncomingSyncService {

    private static final ZoneId BJT = ZoneId.of("Asia/Shanghai");
    private final PoolClient poolClient;
    private final XmrWalletIncomingMapper walletIncomingMapper;
    private final XmrWalletProperties walletProperties;
    private final long atomicPerXmr;

    public XmrWalletIncomingSyncService(PoolClient poolClient,
                                        XmrWalletIncomingMapper walletIncomingMapper,
                                        XmrPoolProperties poolProperties,
                                        XmrWalletProperties walletProperties) {
        this.poolClient = poolClient;
        this.walletIncomingMapper = walletIncomingMapper;
        this.walletProperties = walletProperties;
        this.atomicPerXmr = poolProperties.getDefaultProvider().getUnit().getAtomicPerXmr();
    }

    @Scheduled(fixedDelayString = "${app.xmr.wallet.sync-interval-ms:300000}")
    @Transactional
    public void syncPayments() {
        if (!walletProperties.isEnabled()) {
            log.debug("Pool payment sync disabled; skip execution");
            return;
        }
        String masterAddress = normalizeMasterAddress(walletProperties.getMasterAddress());
        if (!StringUtils.hasText(masterAddress)) {
            log.warn("Master wallet address is not configured; skip payment sync");
            return;
        }
        List<PoolPayment> payments;
        try {
            payments = poolClient.fetchPayments(masterAddress);
        } catch (PoolClientException ex) {
            log.warn("Failed to fetch pool payments for {}: {}", masterAddress, ex.getMessage());
            return;
        }
        if (payments.isEmpty()) {
            log.info("No block payments returned for master address {}", masterAddress);
            return;
        }

        int inserted = 0;
        Set<String> seenTx = new HashSet<>();
        for (PoolPayment payment : payments) {
            if (payment == null || payment.amountAtomic() <= 0 || !StringUtils.hasText(payment.txHash())) {
                continue;
            }
            String rawTxHash = payment.txHash().trim();
            String normalizedTxHash = normalizeTxHash(rawTxHash);
            if (!StringUtils.hasText(normalizedTxHash)) {
                continue;
            }
            // 本批次去重，避免上游重复返回同一个 tx_hash
            if (!seenTx.add(normalizedTxHash)) {
                continue;
            }
            // DB 去重（兼容并发/历史重复）
            if (walletIncomingMapper.selectByTxHash(normalizedTxHash).isPresent()) {
                continue;
            }
            if (!normalizedTxHash.equals(rawTxHash) && walletIncomingMapper.selectByTxHash(rawTxHash).isPresent()) {
                continue;
            }
            boolean saved = persistPaymentIgnore(payment, masterAddress, normalizedTxHash);
            if (saved) {
            inserted++;
            }
        }
        if (inserted > 0) {
            log.info("Synced {} new block payments from master address {}", inserted, masterAddress);
        } else {
            log.info("Fetched {} payments from {} but none were persisted (likely duplicates or zero amount)", payments.size(), masterAddress);
        }
    }

    private boolean persistPaymentIgnore(PoolPayment payment, String masterAddress, String normalizedTxHash) {
        XmrWalletIncoming record = new XmrWalletIncoming();
        record.setUserId(walletProperties.getMasterOwnerUserId());
        record.setSubaddress(masterAddress);
        record.setTxHash(normalizedTxHash);
        record.setAmountXmr(toXmr(payment.amountAtomic()));
        record.setBlockHeight(payment.blockHeight());
        record.setTs(toLocalDateTime(payment.timestamp()));
        record.setSettled(Boolean.FALSE);
        int affected = walletIncomingMapper.insertIgnore(record);
        return affected > 0;
    }

    private String normalizeTxHash(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String trimmed = raw.trim();
        if (startsWithIgnoreCase(trimmed, "f2pool:") || startsWithIgnoreCase(trimmed, "antpool:")) {
            return trimmed;
        }
        String provider = normalizeProviderName(poolClient.name());
        if (!StringUtils.hasText(provider)) {
            return trimmed;
        }
        String prefix = provider + ":";
        return startsWithIgnoreCase(trimmed, prefix) ? trimmed : (prefix + trimmed);
    }

    private String normalizeProviderName(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        return raw.trim().toLowerCase();
    }

    private boolean startsWithIgnoreCase(String value, String prefix) {
        if (!StringUtils.hasText(value) || !StringUtils.hasText(prefix)) {
            return false;
        }
        if (value.length() < prefix.length()) {
            return false;
        }
        return value.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    private BigDecimal toXmr(long atomic) {
        return BigDecimal.valueOf(atomic)
                .divide(BigDecimal.valueOf(atomicPerXmr), 12, RoundingMode.HALF_UP);
    }

    private LocalDateTime toLocalDateTime(Instant instant) {
        if (instant == null) {
            return LocalDateTime.now(BJT);
        }
        // 转为北京时间存库，方便直观看到本地时间
        return LocalDateTime.ofInstant(instant, BJT);
    }

    private String normalizeMasterAddress(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String normalized = raw.trim();
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
