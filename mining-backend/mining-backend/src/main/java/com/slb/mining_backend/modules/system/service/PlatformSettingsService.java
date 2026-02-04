package com.slb.mining_backend.modules.system.service;

import com.slb.mining_backend.modules.system.entity.PlatformCommissionRate;
import com.slb.mining_backend.modules.system.mapper.PlatformCommissionRateMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

@Service
public class PlatformSettingsService {

    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

    private final PlatformCommissionRateMapper commissionRateMapper;

    @Value("${app.platform.commission-rate:0.30}")
    private BigDecimal defaultCommissionRate;

    public PlatformSettingsService(PlatformCommissionRateMapper commissionRateMapper) {
        this.commissionRateMapper = commissionRateMapper;
    }

    public PlatformCommissionRate getCommissionRateSetting() {
        Optional<PlatformCommissionRate> current = commissionRateMapper.selectCurrent();
        if (current.isPresent()) {
            return current.get();
        }
        PlatformCommissionRate fallback = new PlatformCommissionRate();
        fallback.setId(1L);
        fallback.setRatePercent(toPercent(defaultCommissionRate));
        fallback.setUpdatedBy("system");
        commissionRateMapper.upsert(fallback);
        return commissionRateMapper.selectCurrent().orElse(fallback);
    }

    public PlatformCommissionRate updateCommissionRate(BigDecimal ratePercent, String updatedBy) {
        PlatformCommissionRate toSave = new PlatformCommissionRate();
        toSave.setId(1L);
        toSave.setRatePercent(safePercent(ratePercent));
        toSave.setUpdatedBy(StringUtils.hasText(updatedBy) ? updatedBy : "admin");
        commissionRateMapper.upsert(toSave);
        return commissionRateMapper.selectCurrent().orElse(toSave);
    }

    public BigDecimal getPlatformCommissionRate() {
        PlatformCommissionRate setting = getCommissionRateSetting();
        BigDecimal percent = setting != null ? setting.getRatePercent() : null;
        if (percent == null) {
            return safeRate(defaultCommissionRate);
        }
        return percentToRate(percent);
    }

    private BigDecimal toPercent(BigDecimal rate) {
        if (rate == null) {
            return BigDecimal.ZERO;
        }
        return safeRate(rate).multiply(ONE_HUNDRED).setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal percentToRate(BigDecimal percent) {
        if (percent == null) {
            return safeRate(defaultCommissionRate);
        }
        return percent.divide(ONE_HUNDRED, 8, RoundingMode.HALF_UP);
    }

    private BigDecimal safeRate(BigDecimal rate) {
        if (rate == null) {
            return BigDecimal.ZERO;
        }
        if (rate.compareTo(BigDecimal.ZERO) < 0) {
            return BigDecimal.ZERO;
        }
        if (rate.compareTo(BigDecimal.ONE) > 0) {
            return BigDecimal.ONE;
        }
        return rate;
    }

    private BigDecimal safePercent(BigDecimal percent) {
        if (percent == null) {
            return BigDecimal.ZERO;
        }
        if (percent.compareTo(BigDecimal.ZERO) < 0) {
            return BigDecimal.ZERO;
        }
        if (percent.compareTo(ONE_HUNDRED) > 0) {
            return ONE_HUNDRED;
        }
        return percent.setScale(4, RoundingMode.HALF_UP);
    }
}
