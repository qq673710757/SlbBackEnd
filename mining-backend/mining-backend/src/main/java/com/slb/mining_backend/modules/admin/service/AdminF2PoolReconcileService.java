package com.slb.mining_backend.modules.admin.service;

import com.slb.mining_backend.modules.xmr.entity.F2PoolReconcileReport;
import com.slb.mining_backend.modules.xmr.mapper.F2PoolReconcileReportMapper;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AdminF2PoolReconcileService {

    private final F2PoolReconcileReportMapper reportMapper;

    public AdminF2PoolReconcileService(F2PoolReconcileReportMapper reportMapper) {
        this.reportMapper = reportMapper;
    }

    public List<F2PoolReconcileReport> listLatest(String account, String coin, int limit) {
        return reportMapper.findLatest(account, coin, limit);
    }
}
