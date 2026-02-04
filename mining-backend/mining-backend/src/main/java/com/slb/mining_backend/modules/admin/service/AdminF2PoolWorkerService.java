package com.slb.mining_backend.modules.admin.service;

import com.slb.mining_backend.modules.xmr.entity.F2PoolWorkerSnapshot;
import com.slb.mining_backend.modules.xmr.mapper.F2PoolWorkerSnapshotMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Service
public class AdminF2PoolWorkerService {

    private final F2PoolWorkerSnapshotMapper snapshotMapper;

    public AdminF2PoolWorkerService(F2PoolWorkerSnapshotMapper snapshotMapper) {
        this.snapshotMapper = snapshotMapper;
    }

    public List<F2PoolWorkerSnapshot> listLatestUnclaimed(String account, String coin, int limit) {
        Optional<LocalDateTime> bucketOpt = snapshotMapper.selectLatestBucketTime(account, coin);
        if (bucketOpt.isEmpty()) {
            return Collections.emptyList();
        }
        return snapshotMapper.selectLatestUnclaimed(account, coin, bucketOpt.get(), limit);
    }
}
