package com.slb.mining_backend.config;

import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;

import java.time.ZoneId;
import java.util.TimeZone;

/**
 * 强制统一为北京时间，避免服务器默认时区差异导致时间错位。
 */
@Configuration
public class TimeZoneConfig {

    @PostConstruct
    public void init() {
        TimeZone.setDefault(TimeZone.getTimeZone(ZoneId.of("Asia/Shanghai")));
    }
}
