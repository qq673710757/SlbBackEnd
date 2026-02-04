package com.slb.mining_backend.modules.xmr.domain;

import java.util.List;
import java.util.Optional;

public interface PoolClient {

    Optional<PoolStats> fetchStats(String address) throws PoolClientException;

    List<WorkerHash> fetchWorkers(String address) throws PoolClientException;

    default List<PoolPayment> fetchPayments(String address) throws PoolClientException {
        return List.of();
    }

    String name();
}
