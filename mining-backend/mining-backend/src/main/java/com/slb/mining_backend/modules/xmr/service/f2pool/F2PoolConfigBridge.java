package com.slb.mining_backend.modules.xmr.service.f2pool;

import com.slb.mining_backend.modules.xmr.config.F2PoolProperties;
import org.springframework.stereotype.Component;

@Component
public class F2PoolConfigBridge {

    private final F2PoolProperties properties;

    public F2PoolConfigBridge(F2PoolProperties properties) {
        this.properties = properties;
    }

    public boolean isAllowSyntheticUsrFromRawWorkerId() {
        return properties != null && properties.isAllowSyntheticUsrFromRawWorkerId();
    }
}
