package com.slb.mining_backend.modules.admin.service;

import com.slb.mining_backend.modules.xmr.entity.F2PoolPayhashHourlySettlement;
import com.slb.mining_backend.modules.xmr.mapper.F2PoolPayhashHourlySettlementMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class AdminF2PoolHourlySettlementService {

    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final F2PoolPayhashHourlySettlementMapper settlementMapper;

    public AdminF2PoolHourlySettlementService(F2PoolPayhashHourlySettlementMapper settlementMapper) {
        this.settlementMapper = settlementMapper;
    }

    public long countByFilters(String account, String coin, String startTime, String endTime) {
        return settlementMapper.countByFilters(account, coin, parseTime(startTime), parseTime(endTime));
    }

    public List<F2PoolPayhashHourlySettlement> listByFilters(String account,
                                                             String coin,
                                                             String startTime,
                                                             String endTime,
                                                             int offset,
                                                             int size) {
        return settlementMapper.findByFiltersPaginated(account, coin, parseTime(startTime), parseTime(endTime), offset, size);
    }

    private LocalDateTime parseTime(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        return LocalDateTime.parse(raw.trim(), DT);
    }
}
