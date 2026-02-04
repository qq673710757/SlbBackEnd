package com.slb.mining_backend.modules.xmr.service.f2pool;

import com.slb.mining_backend.modules.xmr.config.F2PoolProperties;
import com.slb.mining_backend.modules.xmr.entity.F2PoolSettlementItem;
import com.slb.mining_backend.modules.xmr.mapper.F2PoolPayoutDailyMapper;
import com.slb.mining_backend.modules.xmr.mapper.F2PoolSettlementItemMapper;
import com.slb.mining_backend.modules.xmr.mapper.F2PoolSettlementMapper;
import com.slb.mining_backend.modules.system.service.PlatformSettingsService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class F2PoolSettlementServiceTest {

    @Test
    void allocateItemsShouldBalanceGrossCal() throws Exception {
        F2PoolProperties properties = new F2PoolProperties();
        F2PoolSettlementService service = new F2PoolSettlementService(
                properties,
                mock(F2PoolPayoutDailyMapper.class),
                mock(F2PoolSettlementMapper.class),
                mock(F2PoolSettlementItemMapper.class),
                mock(F2PoolPayhashWindowScoreService.class),
                mock(WorkerOwnershipResolver.class),
                mock(F2PoolValuationService.class),
                mock(F2PoolReconcileService.class),
                mock(F2PoolAlertService.class),
                mock(PlatformSettingsService.class),
                999L,
                new BigDecimal("0.1")
        );

        F2PoolProperties.Account account = new F2PoolProperties.Account();
        account.setName("acc");
        account.setCoin("XMR");

        Map<Long, BigDecimal> userScores = Map.of(
                1L, BigDecimal.ONE,
                2L, BigDecimal.ONE
        );

        Method method = F2PoolSettlementService.class.getDeclaredMethod(
                "allocateItems",
                F2PoolProperties.Account.class,
                Map.class,
                BigDecimal.class,
                BigDecimal.class,
                BigDecimal.class,
                BigDecimal.class,
                F2PoolValuationService.RateSnapshot.class
        );
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<F2PoolSettlementItem> items = (List<F2PoolSettlementItem>) method.invoke(
                service,
                account,
                userScores,
                new BigDecimal("2"),
                BigDecimal.ONE,
                new BigDecimal("100.00000000"),
                new BigDecimal("0.1"),
                new F2PoolValuationService.RateSnapshot(new BigDecimal("100"), BigDecimal.ONE, "TEST")
        );

        BigDecimal grossSum = items.stream()
                .map(F2PoolSettlementItem::getGrossAmountCal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal netSum = items.stream()
                .map(F2PoolSettlementItem::getNetCal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertThat(grossSum).isEqualByComparingTo(new BigDecimal("100.00000000"));
        assertThat(netSum).isEqualByComparingTo(new BigDecimal("90.00000000"));
    }
}
