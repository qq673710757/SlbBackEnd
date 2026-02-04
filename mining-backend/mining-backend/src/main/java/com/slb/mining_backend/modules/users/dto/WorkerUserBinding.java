package com.slb.mining_backend.modules.users.dto;

import lombok.Data;

@Data
public class WorkerUserBinding {
    private Long userId;
    private String workerId;
}

