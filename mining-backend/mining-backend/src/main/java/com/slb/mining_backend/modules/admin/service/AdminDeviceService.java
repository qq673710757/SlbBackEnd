package com.slb.mining_backend.modules.admin.service;

import com.slb.mining_backend.common.vo.PageVo;
import com.slb.mining_backend.modules.admin.vo.AdminDeviceListItemVo;
import com.slb.mining_backend.modules.device.mapper.DeviceMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

@Service
public class AdminDeviceService {

    private static final BigDecimal ONE_THOUSAND = BigDecimal.valueOf(1000L);

    private final DeviceMapper deviceMapper;

    public AdminDeviceService(DeviceMapper deviceMapper) {
        this.deviceMapper = deviceMapper;
    }

    public PageVo<AdminDeviceListItemVo> listDevices(int page, int size, String keyword, String status, Long ownerUid) {
        Integer statusCode = normalizeStatus(status);
        long total = deviceMapper.countAdminDevices(keyword, statusCode, ownerUid);
        if (total <= 0) {
            return new PageVo<>(0L, page, size, List.of());
        }
        int offset = Math.max(0, (page - 1) * size);
        List<AdminDeviceListItemVo> rows = deviceMapper.findAdminDevicesPaginated(keyword, statusCode, ownerUid, offset, size);
        List<AdminDeviceListItemVo> list = new ArrayList<>();
        for (AdminDeviceListItemVo row : rows) {
            if (row == null) {
                continue;
            }
            AdminDeviceListItemVo vo = new AdminDeviceListItemVo();
            vo.setDeviceId(row.getDeviceId());
            vo.setDeviceName(row.getDeviceName());
            vo.setOwnerUid(row.getOwnerUid());
            vo.setOwnerName(row.getOwnerName());
            vo.setCpuKh(toKh(row.getCpuHashrate()));
            vo.setCfxMh(safe(row.getCfxHashrate()).setScale(2, RoundingMode.HALF_UP));
            vo.setRvnMh(safe(row.getRvnHashrate()).setScale(2, RoundingMode.HALF_UP));
            vo.setStatus(toStatusText(row.getStatusCode()));
            vo.setCreatedAt(row.getCreatedAt());
            list.add(vo);
        }
        return new PageVo<>(total, page, size, list);
    }

    private Integer normalizeStatus(String status) {
        if (!StringUtils.hasText(status)) {
            return null;
        }
        String v = status.trim().toUpperCase();
        if ("ONLINE".equals(v)) {
            return 1;
        }
        if ("OFFLINE".equals(v)) {
            return 0;
        }
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String toStatusText(Integer statusCode) {
        if (statusCode == null) {
            return "UNKNOWN";
        }
        return statusCode == 1 ? "ONLINE" : "OFFLINE";
    }

    private BigDecimal toKh(BigDecimal cpuHps) {
        return safe(cpuHps).divide(ONE_THOUSAND, 2, RoundingMode.HALF_UP);
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
