package com.slb.mining_backend.modules.admin.service;

import com.slb.mining_backend.modules.xmr.entity.F2PoolAlert;
import com.slb.mining_backend.modules.xmr.mapper.F2PoolAlertMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

@Service
public class AdminF2PoolAlertService {

    private static final ZoneId BJT = ZoneId.of("Asia/Shanghai");

    private final F2PoolAlertMapper alertMapper;
    private final long adminUserId;

    public AdminF2PoolAlertService(F2PoolAlertMapper alertMapper,
                                   @Value("${app.settlement.admin-user-id:1}") long adminUserId) {
        this.alertMapper = alertMapper;
        this.adminUserId = adminUserId;
    }

    public long countByStatus(String status) {
        return alertMapper.countByStatus(status);
    }

    public List<F2PoolAlert> listByStatus(String status, int offset, int size) {
        return alertMapper.findByStatusPaginated(status, offset, size);
    }

    public void resolveAlert(Long id) {
        alertMapper.resolveById(id, LocalDateTime.now(BJT), adminUserId);
    }
}
