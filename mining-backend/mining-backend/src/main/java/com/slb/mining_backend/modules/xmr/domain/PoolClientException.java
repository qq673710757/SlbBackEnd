package com.slb.mining_backend.modules.xmr.domain;

public class PoolClientException extends RuntimeException {
    public PoolClientException(String message) {
        super(message);
    }

    public PoolClientException(String message, Throwable cause) {
        super(message, cause);
    }
}