package com.slb.mining_backend.modules.xmr.service.f2pool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slb.mining_backend.modules.xmr.config.F2PoolProperties;
import com.slb.mining_backend.modules.xmr.domain.F2PoolWorkerSample;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class F2PoolParserTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldParseWorkersFromListAndMapStructures() {
        F2PoolProperties properties = new F2PoolProperties();
        F2PoolParser parser = new F2PoolParser(objectMapper, properties);

        F2PoolProperties.Account account = new F2PoolProperties.Account();
        account.setName("acc");
        account.setCoin("XMR");

        String listOfList = "{\"workers\":[[\"w1\",1000,\"KH/s\",1700000000]]}";
        List<F2PoolWorkerSample> result1 = parser.parseWorkers(listOfList, account);
        assertThat(result1).hasSize(1);
        assertThat(result1.get(0).workerId()).isEqualTo("w1");
        assertThat(result1.get(0).hashNowHps()).isEqualTo(1d);

        String listOfObj = "{\"workers\":[{\"worker_name\":\"w2\",\"hashrate\":2,\"hashrate_unit\":\"MH/s\",\"last_share_time\":1700000001}]}";
        List<F2PoolWorkerSample> result2 = parser.parseWorkers(listOfObj, account);
        assertThat(result2).hasSize(1);
        assertThat(result2.get(0).workerId()).isEqualTo("w2");
        assertThat(result2.get(0).hashNowHps()).isEqualTo(2d);

        String mapObj = "{\"workers\":{\"w3\":{\"hashrate\":\"3\",\"hashrate_unit\":\"GH/s\",\"last_share_time\":1700000002}}}";
        List<F2PoolWorkerSample> result3 = parser.parseWorkers(mapObj, account);
        assertThat(result3).hasSize(1);
        assertThat(result3.get(0).workerId()).isEqualTo("w3");
        assertThat(result3.get(0).hashNowHps()).isEqualTo(3_000d);
    }
}
