package com.slb.mining_backend.modules.xmr.service;

import com.slb.mining_backend.modules.xmr.entity.XmrWorkerEarningDelta;
import com.slb.mining_backend.modules.xmr.entity.XmrWorkerEarnings;
import com.slb.mining_backend.modules.xmr.mapper.XmrWorkerEarningDeltaMapper;
import com.slb.mining_backend.modules.xmr.mapper.XmrWorkerEarningsMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class XmrWorkerEarningsService {

    private final XmrWorkerEarningsMapper earningsMapper;
    private final XmrWorkerEarningDeltaMapper deltaMapper;

    public XmrWorkerEarningsService(XmrWorkerEarningsMapper earningsMapper,
                                    XmrWorkerEarningDeltaMapper deltaMapper) {
        this.earningsMapper = earningsMapper;
        this.deltaMapper = deltaMapper;
    }

    @Transactional
    public void recordShare(Long userId,
                            String workerId,
                            long deltaAtomic,
                            LocalDateTime windowStart,
                            LocalDateTime windowEnd) {
        if (deltaAtomic <= 0) {
            return;
        }
        XmrWorkerEarnings total = new XmrWorkerEarnings();
        total.setUserId(userId);
        total.setWorkerId(workerId);
        total.setTotalAtomic(deltaAtomic);
        earningsMapper.upsert(total);

        XmrWorkerEarningDelta delta = new XmrWorkerEarningDelta();
        delta.setUserId(userId);
        delta.setWorkerId(workerId);
        delta.setDeltaAtomic(deltaAtomic);
        delta.setWindowStart(windowStart);
        delta.setWindowEnd(windowEnd);
        delta.setCreatedTime(LocalDateTime.now());
        delta.setSettled(Boolean.FALSE);
        deltaMapper.insert(delta);
    }
}
