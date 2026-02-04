package com.slb.mining_backend.modules.device.mapper;

import com.slb.mining_backend.modules.device.entity.DeviceReport;
import org.apache.ibatis.annotations.Mapper;

/**
 * MyBatis mapper：操作 device_reports 表。
 */
@Mapper
public interface DeviceReportMapper {

    /**
     * 插入一条设备上报记录
     */
    int insert(DeviceReport report);
}