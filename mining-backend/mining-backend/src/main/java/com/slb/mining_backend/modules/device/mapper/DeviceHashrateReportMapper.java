package com.slb.mining_backend.modules.device.mapper;

import com.slb.mining_backend.modules.device.entity.DeviceHashrateReport;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * MyBatis mapper：操作 device_hashrate_reports 表（分钟级算力上报，仅展示）。
 */
@Mapper
public interface DeviceHashrateReportMapper {

    /**
     * 幂等 upsert：同一设备同一分钟内重复上报会覆盖该分钟桶的数据。
     */
    int upsertMinuteReport(DeviceHashrateReport report);

    /**
     * 查询设备最近一段时间的分钟桶上报（按 bucket_time 升序返回，便于前端画图）。
     */
    List<DeviceHashrateReport> selectByDeviceSince(@Param("userId") Long userId,
                                                   @Param("deviceId") String deviceId,
                                                   @Param("since") LocalDateTime since);
}


