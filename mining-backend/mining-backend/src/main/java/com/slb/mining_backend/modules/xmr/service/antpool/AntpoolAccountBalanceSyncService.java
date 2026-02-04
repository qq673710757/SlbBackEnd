package com.slb.mining_backend.modules.xmr.service.antpool;

import com.slb.mining_backend.modules.xmr.config.AntpoolProperties;
import com.slb.mining_backend.modules.xmr.entity.AntpoolAccountBalance;
import com.slb.mining_backend.modules.xmr.mapper.AntpoolAccountBalanceMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

@Service
@Slf4j
public class AntpoolAccountBalanceSyncService {

    private static final ZoneId BJT = ZoneId.of("Asia/Shanghai");

    private final AntpoolProperties properties;
    private final AntpoolClient client;
    private final AntpoolParser parser;
    private final AntpoolAccountBalanceMapper balanceMapper;
    private final boolean enabled;

    public AntpoolAccountBalanceSyncService(AntpoolProperties properties,
                                            AntpoolClient client,
                                            AntpoolParser parser,
                                            AntpoolAccountBalanceMapper balanceMapper,
                                            @Value("${app.antpool.account-balance.enabled:true}") boolean enabled) {
        this.properties = properties;
        this.client = client;
        this.parser = parser;
        this.balanceMapper = balanceMapper;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${app.antpool.account-balance-sync-interval-ms:300000}")
    public void syncAccountBalance() {
        if (!enabled || properties == null || !properties.isEnabled()) {
            return;
        }
        String account = properties.getUserId();
        String coin = properties.getCoin();
        if (!StringUtils.hasText(account) || !StringUtils.hasText(coin)) {
            return;
        }
        AntpoolClient.AntpoolRawResponse response = client.fetchAccountBalance();
        if (response == null || !StringUtils.hasText(response.body())) {
            log.warn("Antpool account balance empty response (account={}, coin={})", account, coin);
            return;
        }
        AntpoolParser.ParsedAccountBalance parsed = parser.parseAccountBalance(response.body());
        if (parsed == null) {
            log.warn("Antpool account balance parse failed (account={}, coin={})", account, coin);
            return;
        }
        AntpoolAccountBalance record = new AntpoolAccountBalance();
        record.setAccount(account.trim());
        record.setCoin(coin.trim());
        record.setEarn24Hours(safe(parsed.earn24Hours()));
        record.setEarnTotal(safe(parsed.earnTotal()));
        record.setPaidOut(safe(parsed.paidOut()));
        record.setBalance(safe(parsed.balance()));
        record.setSettleTime(parsed.settleTime());
        record.setFetchedAt(toLocalDateTime(response.fetchedAt()));
        record.setCreatedTime(LocalDateTime.now(BJT));
        balanceMapper.insert(record);
    }

    private LocalDateTime toLocalDateTime(Instant instant) {
        if (instant == null) {
            return LocalDateTime.now(BJT);
        }
        return LocalDateTime.ofInstant(instant, BJT);
    }

    private BigDecimal safe(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }
}
