package com.slb.mining_backend.modules.system.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class Feedback {
    private Long id;
    private Long userId;
    private String type;
    private String content;
    private String contact;
    private String status;
    private LocalDateTime createTime;
}