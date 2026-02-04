package com.slb.mining_backend.modules.earnings.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slb.mining_backend.modules.device.mapper.DeviceMapper;
import com.slb.mining_backend.modules.exchange.service.ExchangeRateService;
import com.slb.mining_backend.modules.xmr.service.antpool.AntpoolClient;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.reactive.function.client.WebClient;

import java.lang.reflect.Method;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class MarketDataServiceParsingTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldParseC3poolPoolStatisticsHashRateAndActivePortProfit() throws Exception {
        String json = "{\"pool_statistics\":{\"hashRate\":388187818.1,\"activePortProfit\":6.611590961221549E-08}}";
        JsonNode root = objectMapper.readTree(json);

        MarketDataService svc = new MarketDataService(
                Mockito.mock(ExchangeRateService.class),
                Mockito.mock(DeviceMapper.class),
                Mockito.mock(AntpoolClient.class),
                WebClient.builder(),
                objectMapper
        );

        Method extractHashrate = MarketDataService.class.getDeclaredMethod("extractHashrate", JsonNode.class);
        extractHashrate.setAccessible(true);
        BigDecimal hashrate = (BigDecimal) extractHashrate.invoke(svc, root);
        assertThat(hashrate).isNotNull();
        assertThat(hashrate.doubleValue()).isCloseTo(388.1878181, within(0.0001));

        Method extractProfit = MarketDataService.class.getDeclaredMethod("extractActivePortProfit", JsonNode.class);
        extractProfit.setAccessible(true);
        BigDecimal profit = (BigDecimal) extractProfit.invoke(svc, root);
        assertThat(profit).isNotNull();
        assertThat(profit.doubleValue()).isCloseTo(6.611590961221549E-02, within(1e-6));
    }

    @Test
    void shouldBeCompatibleWithLegacyHashFields() throws Exception {
        MarketDataService svc = new MarketDataService(
                Mockito.mock(ExchangeRateService.class),
                Mockito.mock(DeviceMapper.class),
                Mockito.mock(AntpoolClient.class),
                WebClient.builder(),
                objectMapper
        );

        Method extractHashrate = MarketDataService.class.getDeclaredMethod("extractHashrate", JsonNode.class);
        extractHashrate.setAccessible(true);

        // root.hash (textual with unit)
        JsonNode rootHashText = objectMapper.readTree("{\"hash\":\"388 MH/s\"}");
        BigDecimal h1 = (BigDecimal) extractHashrate.invoke(svc, rootHashText);
        assertThat(h1).isEqualByComparingTo(new BigDecimal("388"));

        // root.hashRate (number)
        JsonNode rootHashRate = objectMapper.readTree("{\"hashRate\":123456.7}");
        BigDecimal h2 = (BigDecimal) extractHashrate.invoke(svc, rootHashRate);
        assertThat(h2.doubleValue()).isCloseTo(0.1234567, within(0.0001));

        // root.pool.hash (nested)
        JsonNode nested = objectMapper.readTree("{\"pool\":{\"hash\":999}}");
        BigDecimal h3 = (BigDecimal) extractHashrate.invoke(svc, nested);
        assertThat(h3).isEqualByComparingTo(new BigDecimal("0.000999"));
    }
}


