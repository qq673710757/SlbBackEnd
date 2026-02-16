package com.slb.mining_backend.modules.earnings.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slb.mining_backend.modules.device.mapper.DeviceHashrateReportMapper;
import com.slb.mining_backend.modules.device.mapper.DeviceMapper;
import com.slb.mining_backend.modules.device.service.DeviceService;
import com.slb.mining_backend.modules.device.vo.HashrateSummaryVo;
import com.slb.mining_backend.modules.earnings.mapper.EarningsHistoryMapper;
import com.slb.mining_backend.modules.earnings.mapper.EarningsMapper;
import com.slb.mining_backend.modules.earnings.vo.EstimateVo;
import com.slb.mining_backend.modules.invite.config.InviteProperties;
import com.slb.mining_backend.modules.invite.mapper.CommissionRecordMapper;
import com.slb.mining_backend.modules.invite.mapper.PlatformCommissionMapper;
import com.slb.mining_backend.modules.invite.service.InviteService;
import com.slb.mining_backend.modules.system.service.PlatformSettingsService;
import com.slb.mining_backend.modules.users.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static org.assertj.core.api.Assertions.assertThat;

class EarningsEstimateConsistencyTest {

    @Test
    void estimateHourlyCnyShouldAlignWithHashrateSummaryHourlyIncomeCnyAfterRounding() {
        MarketDataService marketDataService = Mockito.mock(MarketDataService.class);
        Mockito.when(marketDataService.getPoolTotalHashrate()).thenReturn(new BigDecimal("100")); // 100 MH/s
        Mockito.when(marketDataService.getCalXmrRatio()).thenReturn(new BigDecimal("0.001"));          // 1 CAL = 0.001 XMR
        Mockito.when(marketDataService.getCalToCnyRate()).thenReturn(new BigDecimal("1.2005"));       // CNY/CAL
        Mockito.when(marketDataService.getXmrToCnyRate()).thenReturn(new BigDecimal("1200.5"));       // CNY/XMR
        Mockito.when(marketDataService.getExternalPoolActivePortProfitXmrPerHashDay()).thenReturn(BigDecimal.ZERO);

        EarningsService earningsService = new EarningsService(
                Mockito.mock(EarningsMapper.class),
                Mockito.mock(UserMapper.class),
                marketDataService,
                Mockito.mock(InviteService.class),
                Mockito.mock(PlatformCommissionMapper.class),
                Mockito.mock(EarningsHistoryMapper.class),
                Mockito.mock(CommissionRecordMapper.class),
                Mockito.mock(PlatformSettingsService.class)
        );
        ReflectionTestUtils.setField(earningsService, "xmrBlockReward", new BigDecimal("0.6"));
        ReflectionTestUtils.setField(earningsService, "blocksPerHour", new BigDecimal("30"));
        ReflectionTestUtils.setField(earningsService, "bonusFactor", new BigDecimal("1.0"));

        DeviceMapper deviceMapper = Mockito.mock(DeviceMapper.class);
        Mockito.when(deviceMapper.sumCpuHashrateByUserId(1L)).thenReturn(new BigDecimal("5000000"));
        Mockito.when(deviceMapper.sumGpuHashrateByUserId(1L)).thenReturn(new BigDecimal("20"));

        UserMapper deviceUserMapper = Mockito.mock(UserMapper.class);
        Mockito.when(deviceUserMapper.selectById(1L)).thenReturn(java.util.Optional.empty());

        DeviceService deviceService = new DeviceService(
                deviceMapper,
                Mockito.mock(DeviceHashrateReportMapper.class),
                Mockito.mock(com.slb.mining_backend.modules.device.mapper.DeviceGpuHashrateReportMapper.class),
                Mockito.mock(com.slb.mining_backend.modules.device.mapper.DeviceRemoteCommandMapper.class),
                new ObjectMapper(),
                marketDataService,
                deviceUserMapper,
                Mockito.mock(InviteService.class),
                Mockito.mock(InviteProperties.class),
                Mockito.mock(PlatformSettingsService.class)
        );
        ReflectionTestUtils.setField(deviceService, "xmrBlockReward", new BigDecimal("0.6"));
        ReflectionTestUtils.setField(deviceService, "blocksPerHour", new BigDecimal("30"));
        ReflectionTestUtils.setField(deviceService, "bonusFactor", new BigDecimal("1.0"));

        EstimateVo estimate = earningsService.getEstimate(5_000_000.0, 20.0);
        HashrateSummaryVo summary = deviceService.getHashrateSummary(1L);

        BigDecimal estimateHourlyCnyRounded2 = estimate.getHourly().getCnyAmount().setScale(2, RoundingMode.HALF_UP);
        assertThat(summary.getHourlyIncomeCny()).isEqualByComparingTo(estimateHourlyCnyRounded2);
    }
}


