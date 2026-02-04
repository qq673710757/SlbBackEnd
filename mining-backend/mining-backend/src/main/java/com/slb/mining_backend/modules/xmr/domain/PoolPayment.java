package com.slb.mining_backend.modules.xmr.domain;

import java.time.Instant;

public record PoolPayment(
        String address,
        long amountAtomic,
        String txHash,
        Long blockHeight,
        Instant timestamp
) {
}
