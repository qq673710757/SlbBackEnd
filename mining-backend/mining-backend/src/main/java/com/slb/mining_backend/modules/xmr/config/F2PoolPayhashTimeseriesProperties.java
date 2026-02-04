package com.slb.mining_backend.modules.xmr.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.f2pool.payhash.timeseries")
@Data
public class F2PoolPayhashTimeseriesProperties {

    private String table = "f2pool_payhash_stats";
    private String accountColumn = "account";
    private String coinColumn = "coin";
    private String workerColumn = "worker_id";
    private String payhashColumn = "payhash";
    private String timeColumn = "bucket_time";
}
