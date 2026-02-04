package com.slb.mining_backend.modules.device.mapper;

import com.slb.mining_backend.modules.device.entity.DeviceGpuHashrateReport;
import com.slb.mining_backend.modules.device.vo.DeviceGpuHashrateSnapshotVo;
import com.slb.mining_backend.modules.device.vo.GpuAlgorithmHashrateVo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * MyBatis mapper：操作 device_gpu_hashrate_reports 表（分钟级 GPU 明细，仅展示）。
 */
@Mapper
public interface DeviceGpuHashrateReportMapper {

    /**
     * 幂等 upsert：同一设备同一分钟同一 GPU index 重复上报会覆盖该分钟桶的数据。
     */
    int upsertMinuteReport(DeviceGpuHashrateReport report);

    /**
     * 查询设备最近一段时间的 GPU 明细上报（按 bucket_time 升序返回）。
     */
    List<DeviceGpuHashrateReport> selectByDeviceSince(@Param("userId") Long userId,
                                                      @Param("deviceId") String deviceId,
                                                      @Param("since") LocalDateTime since);

    /**
     * 查询当前用户下每台设备的 GPU 顺时算力（每块 GPU 最近一分钟桶）。
     */
    List<DeviceGpuHashrateSnapshotVo> selectLatestByUser(@Param("userId") Long userId,
                                                         @Param("cutoffTime") LocalDateTime cutoffTime);

    /**
     * 查询当前用户下 GPU 最新算力按算法汇总（基于每块 GPU 最新一分钟桶）。
     */
    List<GpuAlgorithmHashrateVo> sumLatestHashrateByAlgorithm(@Param("userId") Long userId,
                                                              @Param("cutoffTime") LocalDateTime cutoffTime);
}
