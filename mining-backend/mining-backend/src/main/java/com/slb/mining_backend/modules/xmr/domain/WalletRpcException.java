package com.slb.mining_backend.modules.xmr.domain;

/**
 * Exception thrown when the Monero wallet RPC call fails or returns an unexpected response.
 */
public class WalletRpcException extends RuntimeException {

    public WalletRpcException(String message) {
        super(message);
    }

    public WalletRpcException(String message, Throwable cause) {
        super(message, cause);
    }
}

