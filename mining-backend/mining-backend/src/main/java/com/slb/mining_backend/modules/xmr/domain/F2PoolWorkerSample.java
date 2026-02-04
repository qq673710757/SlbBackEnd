package com.slb.mining_backend.modules.xmr.domain;

import java.time.Instant;

public record F2PoolWorkerSample(
        String account,
        String coin,
        String workerId,
        double hashNowHps,
        double hashAvgHps,
        Instant lastShareAt
) {
}
