package com.slb.mining_backend.modules.xmr.entity;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
public class F2PoolRawPayload implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;
    private String account;
    private String coin;
    private String endpoint;
    private String fingerprint;
    private String payload;
    private LocalDateTime fetchedAt;
    private LocalDateTime createdTime;
}
