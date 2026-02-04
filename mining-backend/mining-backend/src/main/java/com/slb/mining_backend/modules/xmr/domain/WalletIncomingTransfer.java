package com.slb.mining_backend.modules.xmr.domain;

import java.time.Instant;

/**
 * Value object representing an incoming transfer retrieved from the Monero wallet RPC.
 */
public record WalletIncomingTransfer(
        String txHash,
        long amountAtomic,
        Long blockHeight,
        Instant timestamp,
        Integer confirmations,
        Integer accountIndex,
        Integer subaddressIndex,
        String address,
        String paymentId,
        String type
) {
}

