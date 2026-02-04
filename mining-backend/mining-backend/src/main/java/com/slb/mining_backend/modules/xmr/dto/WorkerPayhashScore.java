package com.slb.mining_backend.modules.xmr.dto;

import java.math.BigDecimal;

/**
 * 窗口期内单个矿工（worker）的工作量得分。
 */
public record WorkerPayhashScore(
        String workerId,
        BigDecimal payhash
) {
}

