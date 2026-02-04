package com.slb.mining_backend.modules.xmr.domain;

import java.time.Instant;

/**
 * Worker level hashrate projection returned by pool clients.
 */
public record WorkerHash(
        String address,
        String workerId,
        double hashNowHps,
        double hashAvgHps,
        Instant fetchedAt
) {
}