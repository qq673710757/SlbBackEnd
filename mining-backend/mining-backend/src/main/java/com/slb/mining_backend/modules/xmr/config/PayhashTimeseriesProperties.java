package com.slb.mining_backend.modules.xmr.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * payhash 时间序列数据库的元数据配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.payhash.timeseries")
public class PayhashTimeseriesProperties {

    /**
     * 存储 payhash 聚合数据的表名。
     */
    private String table = "miner_payhash_stats";

    /**
     * worker id 列名。
     */
    private String workerColumn = "worker_id";

    /**
     * payhash 数值列名。
     */
    private String payhashColumn = "payhash";

    /**
     * 时间列名（1 分钟聚合时间戳）。
     */
    private String timeColumn = "bucket_time";
}

