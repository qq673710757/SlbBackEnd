package com.slb.mining_backend.modules.admin.service;

import com.slb.mining_backend.modules.admin.vo.DeviceSummaryVo;
import com.slb.mining_backend.modules.admin.vo.UserAssetsSummaryVo;
import com.slb.mining_backend.modules.device.mapper.DeviceMapper;
import com.slb.mining_backend.modules.users.mapper.UserMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

@Service
public class AdminDashboardService {

    private static final BigDecimal ONE_THOUSAND = BigDecimal.valueOf(1000L);

    private final DeviceMapper deviceMapper;
    private final UserMapper userMapper;

    public AdminDashboardService(DeviceMapper deviceMapper, UserMapper userMapper) {
        this.deviceMapper = deviceMapper;
        this.userMapper = userMapper;
    }

    public DeviceSummaryVo getDeviceSummary() {
        long total = deviceMapper.countTotalDevices();
        long online = deviceMapper.countOnlineDevices();
        BigDecimal cpuHps = safe(deviceMapper.sumTotalCpuHashrate());
        BigDecimal cpuKh = cpuHps.divide(ONE_THOUSAND, 2, RoundingMode.HALF_UP);
        BigDecimal cfxMh = safe(deviceMapper.sumTotalGpuHashrateOctopus()).setScale(2, RoundingMode.HALF_UP);
        BigDecimal rvnMh = safe(deviceMapper.sumTotalGpuHashrateKawpow()).setScale(2, RoundingMode.HALF_UP);

        DeviceSummaryVo vo = new DeviceSummaryVo();
        vo.setDeviceTotal(total);
        vo.setDeviceOnline(online);
        vo.setCpuKh(cpuKh);
        vo.setCfxMh(cfxMh);
        vo.setRvnMh(rvnMh);
        vo.setAsOf(LocalDateTime.now());
        return vo;
    }

    public UserAssetsSummaryVo getUserAssetsSummary() {
        BigDecimal calTotal = safe(userMapper.sumTotalCalBalance()).setScale(4, RoundingMode.HALF_UP);
        BigDecimal cnyTotal = safe(userMapper.sumTotalCnyBalance()).setScale(2, RoundingMode.HALF_UP);

        UserAssetsSummaryVo vo = new UserAssetsSummaryVo();
        vo.setCalTotal(calTotal);
        vo.setCnyTotal(cnyTotal);
        vo.setAsOf(LocalDateTime.now());
        return vo;
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
