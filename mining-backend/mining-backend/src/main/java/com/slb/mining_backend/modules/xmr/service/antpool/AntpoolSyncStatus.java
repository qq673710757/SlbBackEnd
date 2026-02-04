package com.slb.mining_backend.modules.xmr.service.antpool;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class AntpoolSyncStatus {

    private static final ZoneId BJT = ZoneId.of("Asia/Shanghai");
    private final AtomicReference<SyncStatus> lastWorkerSync = new AtomicReference<>();

    public record SyncStatus(LocalDateTime at, String status, String detail) {
    }

    public void recordWorkerSync(String status, String detail) {
        lastWorkerSync.set(new SyncStatus(LocalDateTime.now(BJT), status, detail));
    }

    public SyncStatus getLastWorkerSync() {
        return lastWorkerSync.get();
    }
}
