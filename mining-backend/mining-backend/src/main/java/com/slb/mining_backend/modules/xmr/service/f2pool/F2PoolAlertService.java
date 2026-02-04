package com.slb.mining_backend.modules.xmr.service.f2pool;

import com.slb.mining_backend.modules.xmr.entity.F2PoolAlert;
import com.slb.mining_backend.modules.xmr.mapper.F2PoolAlertMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.ZoneId;

@Service
public class F2PoolAlertService {

    private static final ZoneId BJT = ZoneId.of("Asia/Shanghai");
    private final F2PoolAlertMapper alertMapper;

    public F2PoolAlertService(F2PoolAlertMapper alertMapper) {
        this.alertMapper = alertMapper;
    }

    public void raiseAlert(String account,
                           String coin,
                           Long userId,
                           String alertType,
                           String severity,
                           String refKey,
                           String message) {
        if (!StringUtils.hasText(account) || !StringUtils.hasText(coin) || !StringUtils.hasText(alertType)) {
            return;
        }
        F2PoolAlert alert = new F2PoolAlert();
        alert.setAccount(account);
        alert.setCoin(coin);
        alert.setUserId(userId);
        alert.setAlertType(alertType);
        alert.setSeverity(StringUtils.hasText(severity) ? severity : "WARN");
        alert.setRefKey(refKey);
        alert.setMessage(StringUtils.hasText(message) ? message : alertType);
        alert.setStatus("OPEN");
        alert.setCreatedTime(LocalDateTime.now(BJT));
        alertMapper.insertIgnore(alert);
    }

    public boolean hasOpenAlerts(Long userId) {
        if (userId == null) {
            return false;
        }
        return alertMapper.countOpenByUserId(userId) > 0;
    }
}
