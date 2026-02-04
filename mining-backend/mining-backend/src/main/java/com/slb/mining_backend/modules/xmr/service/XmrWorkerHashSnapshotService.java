package com.slb.mining_backend.modules.xmr.service;

import com.slb.mining_backend.modules.xmr.domain.WorkerHash;
import com.slb.mining_backend.modules.xmr.entity.XmrWorkerHashSnapshot;
import com.slb.mining_backend.modules.xmr.mapper.XmrWorkerHashSnapshotMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class XmrWorkerHashSnapshotService {

    private final XmrWorkerHashSnapshotMapper snapshotMapper;

    public XmrWorkerHashSnapshotService(XmrWorkerHashSnapshotMapper snapshotMapper) {
        this.snapshotMapper = snapshotMapper;
    }

    @Transactional
    public void replaceSnapshot(Long userId, String workerId, List<WorkerHash> workers) {
        snapshotMapper.deleteByUserId(userId);
        if (workers == null || workers.isEmpty()) {
            return;
        }
        List<XmrWorkerHashSnapshot> records = workers.stream()
                .map(hash -> toEntity(userId, workerId, hash))
                .collect(Collectors.toList());
        snapshotMapper.insertBatch(records);
    }

    private XmrWorkerHashSnapshot toEntity(Long userId, String workerId, WorkerHash hash) {
        XmrWorkerHashSnapshot entity = new XmrWorkerHashSnapshot();
        entity.setUserId(userId);
        String snapshotWorkerId = StringUtils.hasText(hash.workerId()) ? hash.workerId() : workerId;
        entity.setWorkerId(snapshotWorkerId);
        entity.setHashNowHps(BigDecimal.valueOf(hash.hashNowHps()));
        entity.setHashAvgHps(BigDecimal.valueOf(hash.hashAvgHps()));
        entity.setReportedAt(LocalDateTime.ofInstant(hash.fetchedAt(), java.time.ZoneOffset.UTC));
        entity.setCreatedTime(LocalDateTime.now());
        return entity;
    }
}