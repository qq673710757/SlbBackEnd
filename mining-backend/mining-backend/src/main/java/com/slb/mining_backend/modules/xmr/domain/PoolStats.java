package com.slb.mining_backend.modules.xmr.domain;

import java.time.Instant;

/**
 * Normalised miner statistics returned by any supported pool implementation.
 * All monetary values are stored in atomic units (piconero): 1 XMR = 1e12 atomic.
 */
public record PoolStats(
        String address,
        long unpaidAtomic,
        long paidTotalAtomic,
        double hashrateHps,
        Instant fetchedAt
) {
}