package com.slb.mining_backend.modules.xmr.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for the XMR payment sync job.
 *
 * <p>The job 现在统一从矿池主地址（c3pool block_payments）同步收益，因此需要提供是否启用、
 * 轮询间隔以及主钱包信息等配置。</p>
 */
@Component
@ConfigurationProperties(prefix = "app.xmr.wallet")
@Data
public class XmrWalletProperties {

    /**
     * 是否启用收益同步。
     */
    private boolean enabled = false;

    /**
     * c3pool 上的主钱包地址（miner address）。为空时同步任务会跳过。
     */
    private String masterAddress;

    /**
     * 将主钱包入账临时归属到哪个用户（通常是管理员账号，后续结算会再分配到真实用户）。
     */
    private Long masterOwnerUserId = 1L;

    /**
     * 同步轮询间隔（毫秒）。
     */
    private long syncIntervalMs = 300_000L;
}

