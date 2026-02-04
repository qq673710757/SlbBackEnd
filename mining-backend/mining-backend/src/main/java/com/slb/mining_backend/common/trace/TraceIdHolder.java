package com.slb.mining_backend.common.trace;

import java.util.Optional;
import java.util.UUID;

/**
 * Simple thread-local holder for per-request trace identifiers.
 */
public final class TraceIdHolder {
    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    private static final ThreadLocal<String> TRACE_ID = new ThreadLocal<>();

    private TraceIdHolder() {
    }

    public static void set(String traceId) {
        TRACE_ID.set(traceId);
    }

    public static Optional<String> getOptional() {
        return Optional.ofNullable(TRACE_ID.get());
    }

    public static String require() {
        return getOptional().orElseGet(() -> {
            String generated = UUID.randomUUID().toString().replace("-", "");
            set(generated);
            return generated;
        });
    }

    public static void clear() {
        TRACE_ID.remove();
    }
}
