package com.slb.mining_backend.modules.device.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slb.mining_backend.common.exception.BizException;
import com.slb.mining_backend.common.vo.PageVo;
import com.slb.mining_backend.modules.device.dto.DeviceHashrateReportReqDto;
import com.slb.mining_backend.modules.device.dto.DeviceRegisterReqDto;
import com.slb.mining_backend.modules.device.dto.DeviceUpdateReqDto;
import com.slb.mining_backend.modules.device.entity.Device;
import com.slb.mining_backend.modules.device.entity.DeviceHashrateReport;
import com.slb.mining_backend.modules.device.mapper.DeviceHashrateReportMapper;
import com.slb.mining_backend.modules.device.mapper.DeviceMapper;
import com.slb.mining_backend.modules.device.vo.DeviceGpuHashrateSnapshotVo;
import com.slb.mining_backend.modules.device.vo.DeviceHashratePointVo;
import com.slb.mining_backend.modules.device.vo.DeviceVo;
import com.slb.mining_backend.modules.device.vo.GpuAlgorithmDailyIncomeVo;
import com.slb.mining_backend.modules.device.vo.GpuAlgorithmHashrateVo;
import com.slb.mining_backend.modules.device.vo.HashrateSummaryVo;
import com.slb.mining_backend.modules.earnings.service.MarketDataService;
import com.slb.mining_backend.modules.invite.config.InviteProperties;
import com.slb.mining_backend.modules.invite.service.InviteService;
import com.slb.mining_backend.modules.users.entity.User;
import com.slb.mining_backend.modules.users.mapper.UserMapper;
import com.slb.mining_backend.modules.system.service.PlatformSettingsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Slf4j
public class DeviceService {

    private final DeviceMapper deviceMapper;
    private final DeviceHashrateReportMapper deviceHashrateReportMapper;
    private final com.slb.mining_backend.modules.device.mapper.DeviceGpuHashrateReportMapper deviceGpuHashrateReportMapper;
    private final ObjectMapper objectMapper; // Spring Boot 自动配置
    private final MarketDataService marketDataService;
    private final UserMapper userMapper;
    private final InviteService inviteService;
    private final InviteProperties inviteProperties;
    private final PlatformSettingsService platformSettingsService;

    @Value("${app.earnings.estimate.xmr-block-reward}")
    private BigDecimal xmrBlockReward;

    @Value("${app.earnings.estimate.blocks-per-hour}")
    private BigDecimal blocksPerHour;

    /**
     * 预估 bonusFactor 语义：乘数（multiplier）
     * - 1.0 表示无加成
     * - total = base * bonusFactor
     */
    @Value("${app.earnings.estimate.bonus-factor:1.0}")
    private BigDecimal bonusFactor;

    @Value("${app.earnings.estimate.manual-cpu-daily-cny-per-1000h:0}")
    private BigDecimal manualCpuDailyCnyPer1000H;

    @Value("${app.earnings.estimate.manual-gpu-daily-cny-per-1mh:0}")
    private BigDecimal manualGpuDailyCnyPer1Mh;

    @Value("${app.earnings.estimate.gpu-octopus-pool-fee-rate:0.01}")
    private BigDecimal gpuOctopusPoolFeeRate;

    @Value("${app.platform.commission-rate:0.30}")
    private BigDecimal platformCommissionRate;

    // 与 EarningsService 保持一致的兜底，避免 poolTotalHashrate 过小导致预估爆炸（单位：MH/s）
    private static final BigDecimal FALLBACK_NETWORK_HASHRATE = BigDecimal.valueOf(3_000L);
    private static final BigDecimal MIN_VALID_HASHRATE = BigDecimal.valueOf(10L);
    @Value("${app.external-api.active-port-profit-max:0.1}")
    private BigDecimal activePortProfitMax;

    @Value("${app.devices.offline-threshold-minutes:5}")
    private long deviceOfflineThresholdMinutes;

    @Autowired
    public DeviceService(DeviceMapper deviceMapper,
                         DeviceHashrateReportMapper deviceHashrateReportMapper,
                         com.slb.mining_backend.modules.device.mapper.DeviceGpuHashrateReportMapper deviceGpuHashrateReportMapper,
                         ObjectMapper objectMapper,
                         MarketDataService marketDataService,
                         UserMapper userMapper,
                         InviteService inviteService,
                         InviteProperties inviteProperties,
                         PlatformSettingsService platformSettingsService) {
        this.deviceMapper = deviceMapper;
        this.deviceHashrateReportMapper = deviceHashrateReportMapper;
        this.deviceGpuHashrateReportMapper = deviceGpuHashrateReportMapper;
        this.objectMapper = objectMapper;
        this.marketDataService = marketDataService;
        this.userMapper = userMapper;
        this.inviteService = inviteService;
        this.inviteProperties = inviteProperties;
        this.platformSettingsService = platformSettingsService;
    }

    /**
     * 注册新设备
     */
    @Transactional
    public DeviceVo registerDevice(DeviceRegisterReqDto dto, Long userId) {
        // 1. 处理 uniqueId 作为 deviceId
        // 移除 UUID 中的横杠，确保长度为 32 位
        String deviceId = dto.getUniqueId().replace("-", "");
        if (deviceId.length() > 32) {
            throw new BizException("uniqueId 格式错误");
        }

        // 2. 检查是否已存在 (幂等处理)
        // findById 包含已软删除的记录
        Device existingDevice = deviceMapper.findById(deviceId).orElse(null);
        if (existingDevice != null) {
            boolean needUpdate = false;

            // 允许“切号换绑”：同一台物理设备在不同账号登录时，注册接口会将设备归属切换到当前用户
            // 备注：device_id 全局唯一；换绑后，旧账号将不再拥有该设备的访问权限
            if (existingDevice.getUserId() == null || !existingDevice.getUserId().equals(userId)) {
                log.info("Rebinding device {} from user {} to user {}", deviceId, existingDevice.getUserId(), userId);
                existingDevice.setUserId(userId);
                existingDevice.setStatus(0);      // 注册本身不代表在线
                existingDevice.setCpuHashrate(0.0);
                existingDevice.setGpuHashrate(0.0);
                existingDevice.setGpuHashrateOctopus(0.0);
                existingDevice.setGpuHashrateKawpow(0.0);
                existingDevice.setGpuDailyIncomeCny(BigDecimal.ZERO);
                existingDevice.setGpuDailyIncomeCnyOctopus(BigDecimal.ZERO);
                existingDevice.setGpuDailyIncomeCnyKawpow(BigDecimal.ZERO);
                existingDevice.setIsDeleted(false);
                needUpdate = true;
            }

            // 如果设备被软删除了，恢复它
            if (Boolean.TRUE.equals(existingDevice.getIsDeleted())) {
                existingDevice.setIsDeleted(false);
                existingDevice.setStatus(0); // 恢复为离线状态
                needUpdate = true;
            }

            // 更新设备信息（如名称、类型、配置可能变化）
            if (dto.getDeviceName() != null && !dto.getDeviceName().equals(existingDevice.getDeviceName())) {
                existingDevice.setDeviceName(dto.getDeviceName());
                needUpdate = true;
            }
            if (dto.getDeviceType() != null && !dto.getDeviceType().equals(existingDevice.getDeviceType())) {
                existingDevice.setDeviceType(dto.getDeviceType());
                needUpdate = true;
            }

            // 总是更新 deviceInfo
            try {
                existingDevice.setDeviceInfo(objectMapper.writeValueAsString(dto.getDeviceInfo()));
                needUpdate = true;
            } catch (JsonProcessingException e) {
                throw new BizException("设备信息格式错误");
            }

            if (needUpdate) {
                deviceMapper.update(existingDevice);
            }

            // 返回现有设备信息 (包含密钥，以便客户端恢复)
            DeviceVo vo = new DeviceVo();
            BeanUtils.copyProperties(existingDevice, vo);
            vo.setDeviceId(existingDevice.getId());
            vo.setDeviceSecret(existingDevice.getDeviceSecret());
            return vo;
        }

        // 3. 创建新设备
        Device device = new Device();
        device.setId(deviceId);
        device.setUserId(userId);
        device.setDeviceName(dto.getDeviceName() != null ? dto.getDeviceName() : "新设备");
        device.setDeviceType(dto.getDeviceType());
        try {
            device.setDeviceInfo(objectMapper.writeValueAsString(dto.getDeviceInfo()));
        } catch (JsonProcessingException e) {
            throw new BizException("设备信息格式错误");
        }
        device.setStatus(0); // 初始为离线
        device.setCpuHashrate(0.0);
        device.setGpuHashrate(0.0);
        device.setGpuHashrateOctopus(0.0);
        device.setGpuHashrateKawpow(0.0);
        device.setGpuDailyIncomeCny(BigDecimal.ZERO);
        device.setGpuDailyIncomeCnyOctopus(BigDecimal.ZERO);
        device.setGpuDailyIncomeCnyKawpow(BigDecimal.ZERO);
        device.setIsDeleted(false);
        device.setDeviceSecret(generateDeviceSecret());

        deviceMapper.insert(device);

        DeviceVo vo = new DeviceVo();
        vo.setDeviceId(device.getId());
        vo.setDeviceName(device.getDeviceName());
        vo.setDeviceType(device.getDeviceType());
        vo.setStatus(device.getStatus());
        vo.setCreateTime(LocalDateTime.now());
        vo.setDeviceSecret(device.getDeviceSecret());
        BigDecimal zero = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        vo.setCpuDailyIncomeCny(zero);
        vo.setGpuDailyIncomeCny(zero);
        vo.setGpuDailyIncomeCnyOctopus(zero);
        vo.setGpuDailyIncomeCnyKawpow(zero);
        return vo;
    }

    /**
     * 设备算力上报（分钟级，仅展示）。
     *
     * 规则：
     * - 上报时将设备置为在线，并刷新 last_online_time；
     * - 同一分钟内重复上报会覆盖该分钟桶的数据（DB 层唯一键 + upsert）。
     */
    @Transactional
    public void reportHashrate(String deviceId, DeviceHashrateReportReqDto dto, Long userId) {
        Device device = findDeviceAndVerifyOwnership(deviceId, userId);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime bucketTime = truncateToMinute(now);

        DeviceHashrateReport report = new DeviceHashrateReport();
        report.setUserId(userId);
        report.setDeviceId(deviceId);
        report.setBucketTime(bucketTime);
        BigDecimal cpuHashrate = toCpuHps(dto.getCpuHashrate());
        BigDecimal gpuHashrate = toDecimal(dto.getGpuHashrate());
        report.setCpuHashrate(cpuHashrate);
        report.setGpuHashrate(gpuHashrate);
        report.setShares(dto.getShares());
        report.setUptime(dto.getUptime());
        report.setVersion(dto.getVersion());
        report.setAlgorithm(dto.getAlgorithm());
        deviceHashrateReportMapper.upsertMinuteReport(report);

        device.setStatus(1); // 在线
        device.setCpuHashrate(cpuHashrate.doubleValue());
        device.setGpuHashrate(gpuHashrate.doubleValue());
        applyGpuEstimatesFromAlgorithm(device, gpuHashrate, dto.getAlgorithm());
        device.setLastOnlineTime(now);
        deviceMapper.update(device);
    }

    /**
     * 设备 GPU 明细算力上报（分钟级，仅展示）。
     *
     * 规则：
     * - 上报时将设备置为在线，并刷新 last_online_time；
     * - 同一分钟内同一 GPU index 重复上报会覆盖该分钟桶的数据（DB 层唯一键 + upsert）。
     */
    @Transactional
    public void reportGpuHashrateDetail(String deviceId, com.slb.mining_backend.modules.device.dto.DeviceGpuHashrateReportReqDto dto, Long userId) {
        Device device = findDeviceAndVerifyOwnership(deviceId, userId);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime bucketTime = truncateToMinute(dto != null ? dto.getBucketTime() : null);
        if (bucketTime == null) {
            bucketTime = truncateToMinute(now);
        }
        if (dto == null || dto.getGpus() == null || dto.getGpus().isEmpty()) {
            return;
        }

        BigDecimal totalGpuHashrate = BigDecimal.ZERO;
        BigDecimal octopusHashrate = BigDecimal.ZERO;
        BigDecimal kawpowHashrate = BigDecimal.ZERO;
        for (com.slb.mining_backend.modules.device.dto.DeviceGpuHashrateItemDto item : dto.getGpus()) {
            if (item == null || item.getIndex() == null || item.getIndex() < 0) {
                continue;
            }
            java.math.BigDecimal hashrate = toDecimal(item.getHashrate());
            if (hashrate.compareTo(java.math.BigDecimal.ZERO) < 0) {
                continue;
            }
            totalGpuHashrate = totalGpuHashrate.add(hashrate);
            String algorithm = normalizeAlgorithm(item.getAlgorithm());
            if (isCfxAlgorithm(algorithm)) {
                octopusHashrate = octopusHashrate.add(hashrate);
            } else if (isRvnAlgorithm(algorithm)) {
                kawpowHashrate = kawpowHashrate.add(hashrate);
            }
            com.slb.mining_backend.modules.device.entity.DeviceGpuHashrateReport report =
                    new com.slb.mining_backend.modules.device.entity.DeviceGpuHashrateReport();
            report.setUserId(userId);
            report.setDeviceId(deviceId);
            report.setGpuIndex(item.getIndex());
            report.setGpuName(item.getName());
            report.setHashrateMhs(hashrate);
            report.setAlgorithm(item.getAlgorithm());
            report.setBucketTime(bucketTime);
            deviceGpuHashrateReportMapper.upsertMinuteReport(report);
        }

        device.setStatus(1); // 在线
        device.setGpuHashrate(totalGpuHashrate.doubleValue());
        applyDeviceGpuAlgorithmStats(device, octopusHashrate, kawpowHashrate);
        device.setLastOnlineTime(now);
        deviceMapper.update(device);
    }

    /**
     * 获取设备最近 N 分钟的算力趋势（分钟桶）。
     */
    public List<DeviceHashratePointVo> getDeviceHashrateTrend(String deviceId, Long userId, int minutes) {
        if (minutes <= 0) {
            return List.of();
        }
        // 验证设备归属（避免越权读他人设备曲线）
        findDeviceAndVerifyOwnership(deviceId, userId);

        int safeMinutes = Math.min(minutes, 24 * 60); // 防止一次拉取过大
        LocalDateTime since = truncateToMinute(LocalDateTime.now().minusMinutes(safeMinutes));

        List<DeviceHashrateReport> reports = deviceHashrateReportMapper.selectByDeviceSince(userId, deviceId, since);
        if (reports == null || reports.isEmpty()) {
            return List.of();
        }
        IncomeContext incomeContext = buildIncomeContext();
        BigDecimal netMultiplier = resolveEstimateNetMultiplier(userId);
        List<DeviceHashratePointVo> points = new ArrayList<>(reports.size());
        for (DeviceHashrateReport r : reports) {
            DeviceHashratePointVo p = new DeviceHashratePointVo();
            p.setBucketTime(r.getBucketTime());
            BigDecimal cpuHashrate = safeDecimal(r.getCpuHashrate());
            BigDecimal gpuHashrate = safeDecimal(r.getGpuHashrate());
            p.setCpuHashrate(cpuHashrate);
            p.setGpuHashrate(gpuHashrate);
            p.setTotalHashrate(toMhFromHps(cpuHashrate).add(gpuHashrate));
            p.setAlgorithm(r.getAlgorithm());
            BigDecimal cpuIncome = estimateDailyIncomeCny(cpuHashrate, incomeContext, true);
            p.setCpuDailyIncomeCny(applyEstimateNetMultiplier(cpuIncome, netMultiplier, 2));
            BigDecimal gpuDailyIncome = estimateGpuDailyIncomeCnyWithFallback(gpuHashrate, r.getAlgorithm(), incomeContext);
            p.setGpuDailyIncomeCny(applyEstimateNetMultiplier(gpuDailyIncome, netMultiplier, 2));
            points.add(p);
        }
        return points;
    }

    /**
     * 获取设备 GPU 明细趋势（最近 N 分钟，按 GPU index 分组）。
     */
    public List<com.slb.mining_backend.modules.device.vo.DeviceGpuHashrateSeriesVo> getDeviceGpuHashrateTrend(String deviceId, Long userId, int minutes) {
        if (minutes <= 0) {
            return List.of();
        }
        findDeviceAndVerifyOwnership(deviceId, userId);
        int safeMinutes = Math.min(minutes, 24 * 60);
        LocalDateTime since = truncateToMinute(LocalDateTime.now().minusMinutes(safeMinutes));
        List<com.slb.mining_backend.modules.device.entity.DeviceGpuHashrateReport> reports =
                deviceGpuHashrateReportMapper.selectByDeviceSince(userId, deviceId, since);
        if (reports == null || reports.isEmpty()) {
            return List.of();
        }
        java.util.Map<Integer, java.util.List<com.slb.mining_backend.modules.device.entity.DeviceGpuHashrateReport>> grouped =
                reports.stream().collect(java.util.stream.Collectors.groupingBy(
                        com.slb.mining_backend.modules.device.entity.DeviceGpuHashrateReport::getGpuIndex,
                        java.util.TreeMap::new,
                        java.util.stream.Collectors.toList()
                ));

        IncomeContext incomeContext = buildIncomeContext();
        BigDecimal netMultiplier = resolveEstimateNetMultiplier(userId);
        List<com.slb.mining_backend.modules.device.vo.DeviceGpuHashrateSeriesVo> series = new java.util.ArrayList<>();
        for (java.util.Map.Entry<Integer, java.util.List<com.slb.mining_backend.modules.device.entity.DeviceGpuHashrateReport>> entry : grouped.entrySet()) {
            List<com.slb.mining_backend.modules.device.vo.DeviceGpuHashratePointVo> points = new java.util.ArrayList<>();
            String name = null;
            for (com.slb.mining_backend.modules.device.entity.DeviceGpuHashrateReport r : entry.getValue()) {
                if (r == null) {
                    continue;
                }
                if (name == null && r.getGpuName() != null && !r.getGpuName().isBlank()) {
                    name = r.getGpuName();
                }
                com.slb.mining_backend.modules.device.vo.DeviceGpuHashratePointVo p =
                        new com.slb.mining_backend.modules.device.vo.DeviceGpuHashratePointVo();
                p.setBucketTime(r.getBucketTime());
                BigDecimal hashrate = safeDecimal(r.getHashrateMhs());
                p.setHashrate(hashrate);
                p.setAlgorithm(r.getAlgorithm());
                BigDecimal income = estimateGpuDailyIncomeCnyWithFallback(hashrate, r.getAlgorithm(), incomeContext);
                p.setGpuDailyIncomeCny(applyEstimateNetMultiplier(income, netMultiplier, 2));
                points.add(p);
            }
            com.slb.mining_backend.modules.device.vo.DeviceGpuHashrateSeriesVo s =
                    new com.slb.mining_backend.modules.device.vo.DeviceGpuHashrateSeriesVo();
            s.setIndex(entry.getKey());
            s.setName(name);
            s.setPoints(points);
            series.add(s);
        }
        return series;
    }

    /**
     * 获取设备列表 (分页)
     */
    public PageVo<DeviceVo> getDeviceList(Long userId, Integer status, int page, int size, String sortBy, String orderBy) {
        long total = deviceMapper.countByUserIdAndStatus(userId, status);
        if (total == 0) {
            return new PageVo<>(0L, page, size, List.of());
        }

        int offset = (page - 1) * size;
        List<Device> devices = deviceMapper.findByUserIdAndStatusPaginated(userId, status, offset, size, sortBy, orderBy);

        IncomeContext incomeContext = buildIncomeContext();
        BigDecimal netMultiplier = resolveEstimateNetMultiplier(userId);
        List<DeviceVo> voList = devices.stream().map(device -> {
            DeviceVo vo = new DeviceVo();
            BeanUtils.copyProperties(device, vo);
            vo.setDeviceId(device.getId()); // 确保ID被复制
            BigDecimal cpuHashrate = toDecimal(device.getCpuHashrate());
            BigDecimal cpuIncome = estimateDailyIncomeCny(cpuHashrate, incomeContext, true);
            vo.setCpuDailyIncomeCny(applyEstimateNetMultiplier(cpuIncome, netMultiplier, 2));
            vo.setGpuDailyIncomeCny(applyEstimateNetMultiplier(resolveDeviceGpuDailyIncomeCny(device, incomeContext), netMultiplier, 2));
            vo.setGpuDailyIncomeCnyOctopus(applyEstimateNetMultiplier(resolveDeviceGpuDailyIncomeCnyOctopus(device, incomeContext), netMultiplier, 2));
            vo.setGpuDailyIncomeCnyKawpow(applyEstimateNetMultiplier(resolveDeviceGpuDailyIncomeCnyKawpow(device, incomeContext), netMultiplier, 2));
            vo.setDeviceSecret(null);
            return vo;
        }).collect(Collectors.toList());

        return new PageVo<>(total, page, size, voList);
    }

    /**
     * 获取设备详情
     */
    public DeviceVo getDeviceDetail(String deviceId, Long userId) {
        Device device = findDeviceAndVerifyOwnership(deviceId, userId);
        DeviceVo vo = new DeviceVo();
        BeanUtils.copyProperties(device, vo);
        vo.setDeviceId(device.getId());
        IncomeContext incomeContext = buildIncomeContext();
        BigDecimal netMultiplier = resolveEstimateNetMultiplier(userId);
        BigDecimal cpuHashrate = toDecimal(device.getCpuHashrate());
        BigDecimal cpuIncome = estimateDailyIncomeCny(cpuHashrate, incomeContext, true);
        vo.setCpuDailyIncomeCny(applyEstimateNetMultiplier(cpuIncome, netMultiplier, 2));
        vo.setGpuDailyIncomeCny(applyEstimateNetMultiplier(resolveDeviceGpuDailyIncomeCny(device, incomeContext), netMultiplier, 2));
        vo.setGpuDailyIncomeCnyOctopus(applyEstimateNetMultiplier(resolveDeviceGpuDailyIncomeCnyOctopus(device, incomeContext), netMultiplier, 2));
        vo.setGpuDailyIncomeCnyKawpow(applyEstimateNetMultiplier(resolveDeviceGpuDailyIncomeCnyKawpow(device, incomeContext), netMultiplier, 2));
        try {
            vo.setDeviceInfo(objectMapper.readValue(device.getDeviceInfo(), new TypeReference<Map<String, String>>() {}));
        } catch (JsonProcessingException e) {
            // Log the error, but don't fail the request. Maybe return empty map.
            vo.setDeviceInfo(null);
        }
        vo.setDeviceSecret(null);
        return vo;
    }

    /**
     * 更新设备名称
     */
    @Transactional
    public DeviceVo updateDevice(String deviceId, DeviceUpdateReqDto dto, Long userId) {
        Device device = findDeviceAndVerifyOwnership(deviceId, userId);
        device.setDeviceName(dto.getDeviceName());
        deviceMapper.update(device);

        DeviceVo vo = new DeviceVo();
        BeanUtils.copyProperties(device, vo);
        vo.setDeviceId(device.getId());
        IncomeContext incomeContext = buildIncomeContext();
        BigDecimal netMultiplier = resolveEstimateNetMultiplier(userId);
        BigDecimal cpuHashrate = toDecimal(device.getCpuHashrate());
        BigDecimal cpuIncome = estimateDailyIncomeCny(cpuHashrate, incomeContext, true);
        vo.setCpuDailyIncomeCny(applyEstimateNetMultiplier(cpuIncome, netMultiplier, 2));
        vo.setGpuDailyIncomeCny(applyEstimateNetMultiplier(resolveDeviceGpuDailyIncomeCny(device, incomeContext), netMultiplier, 2));
        vo.setGpuDailyIncomeCnyOctopus(applyEstimateNetMultiplier(resolveDeviceGpuDailyIncomeCnyOctopus(device, incomeContext), netMultiplier, 2));
        vo.setGpuDailyIncomeCnyKawpow(applyEstimateNetMultiplier(resolveDeviceGpuDailyIncomeCnyKawpow(device, incomeContext), netMultiplier, 2));
        vo.setDeviceSecret(null);
        return vo;
    }

    /**
     * 获取当前用户所有设备的 GPU 顺时算力列表（每块 GPU 最近一分钟桶）。
     */
    public List<DeviceGpuHashrateSnapshotVo> getLatestGpuHashrateSnapshots(Long userId) {
        LocalDateTime cutoffTime = LocalDateTime.now().minusMinutes(deviceOfflineThresholdMinutes);
        List<DeviceGpuHashrateSnapshotVo> snapshots =
                deviceGpuHashrateReportMapper.selectLatestByUser(userId, cutoffTime);
        if (snapshots == null || snapshots.isEmpty()) {
            return List.of();
        }
        IncomeContext incomeContext = buildIncomeContext();
        BigDecimal netMultiplier = resolveEstimateNetMultiplier(userId);
        for (DeviceGpuHashrateSnapshotVo vo : snapshots) {
            BigDecimal income = estimateGpuDailyIncomeCnyWithFallback(safeDecimal(vo.getHashrate()), vo.getAlgorithm(), incomeContext);
            vo.setGpuDailyIncomeCny(applyEstimateNetMultiplier(income, netMultiplier, 2));
        }
        return snapshots;
    }


    /**
     * 删除设备 (软删除)
     */
    @Transactional
    public void deleteDevice(String deviceId, Long userId) {
        Device device = findDeviceAndVerifyOwnership(deviceId, userId);
        device.setIsDeleted(true);
        device.setStatus(0); // 标记为离线
        deviceMapper.update(device);
    }


    /**
     * 内部方法：查找设备并验证所有权
     */
    private Device findDeviceAndVerifyOwnership(String deviceId, Long userId) {
        return deviceMapper.findByIdAndUserId(deviceId, userId)
                .orElseThrow(() -> new BizException(404, "设备不存在或您没有权限访问"));
    }

    // 设备统计方法类
    /**
     * 根据用户ID获取设备总数
     * @param userId 用户ID
     * @return 设备总数
     */
    public long getTotalDeviceCount(Long userId) {
        // 调用Mapper
        return deviceMapper.countByUserIdAndStatus(userId, null);
    }

    /**
     * 根据用户ID获取在线设备数
     * @param userId 用户ID
     * @return 在线设备数
     */
    public long getOnlineTotalDeviceCount(Long userId) {
        return deviceMapper.countByUserIdAndStatus(userId, 1);
    }

    /**
     * 汇总当前用户的在线算力并估算实时收益
     */
    public HashrateSummaryVo getHashrateSummary(Long userId) {
        BigDecimal cpuHashrate = safeHashrate(deviceMapper.sumCpuHashrateByUserId(userId));
        BigDecimal gpuHashrateDevice = safeHashrate(deviceMapper.sumGpuHashrateByUserId(userId));

        IncomeContext ctx = buildIncomeContext();
        BigDecimal netMultiplier = resolveEstimateNetMultiplier(userId);
        BigDecimal cpuDailyIncomeCny = applyEstimateNetMultiplier(
                estimateDailyIncomeCny(cpuHashrate, ctx, true), netMultiplier, 2);

        LocalDateTime cutoffTime = LocalDateTime.now().minusMinutes(deviceOfflineThresholdMinutes);
        List<GpuAlgorithmHashrateVo> algorithmHashrates =
                deviceGpuHashrateReportMapper.sumLatestHashrateByAlgorithm(userId, cutoffTime);
        List<GpuAlgorithmDailyIncomeVo> algorithmDailyIncomes = new ArrayList<>();
        BigDecimal gpuDailyIncomeCnyCfx = BigDecimal.ZERO;
        BigDecimal gpuDailyIncomeCnyRvn = BigDecimal.ZERO;
        BigDecimal gpuDailyIncomeCnyOther = BigDecimal.ZERO;
        BigDecimal xmrToCnyRate = safePositive(marketDataService.getXmrToCnyRate());
        BigDecimal cfxToXmrRate = safePositive(marketDataService.getCfxToXmrRate());
        BigDecimal cfxToCnyRate = safePositive(marketDataService.getCfxToCnyRate());
        BigDecimal rvnToXmrRate = safePositive(marketDataService.getRvnToXmrRate());

        if (algorithmHashrates == null || algorithmHashrates.isEmpty()) {
            GpuAlgorithmHashrateVo fallback = new GpuAlgorithmHashrateVo();
            fallback.setAlgorithm("unknown");
            fallback.setTotalHashrate(gpuHashrateDevice);
            algorithmHashrates = List.of(fallback);
        }

        BigDecimal gpuHashrate = BigDecimal.ZERO;
        for (GpuAlgorithmHashrateVo entry : algorithmHashrates) {
            gpuHashrate = gpuHashrate.add(safeHashrate(entry.getTotalHashrate()));
        }
        if (gpuHashrate.compareTo(BigDecimal.ZERO) <= 0) {
            gpuHashrate = gpuHashrateDevice;
        }
        BigDecimal totalHashrate = toMhFromHps(cpuHashrate).add(gpuHashrate);
        if (algorithmHashrates != null) {
            for (GpuAlgorithmHashrateVo entry : algorithmHashrates) {
                String algorithm = normalizeAlgorithm(entry.getAlgorithm());
                BigDecimal hashrate = safeHashrate(entry.getTotalHashrate());
                BigDecimal dailyCoinPerMh = resolveGpuDailyCoinPerMh(algorithm);
                BigDecimal dailyCny = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

                String coinSymbol = "UNKNOWN";
                BigDecimal coinToXmrRate = BigDecimal.ZERO;
                BigDecimal dailyCoin = isPositive(dailyCoinPerMh)
                        ? hashrate.multiply(dailyCoinPerMh).setScale(8, RoundingMode.HALF_UP)
                        : BigDecimal.ZERO;
                if (isCfxAlgorithm(algorithm)) {
                    coinSymbol = "CFX";
                    coinToXmrRate = cfxToXmrRate;
                    dailyCny = isPositive(cfxToCnyRate)
                            ? dailyCoin.multiply(cfxToCnyRate).setScale(2, RoundingMode.HALF_UP)
                            : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
                    dailyCoin = applyEstimateNetMultiplier(dailyCoin, netMultiplier, 8);
                    dailyCny = applyEstimateNetMultiplier(dailyCny, netMultiplier, 2);
                    gpuDailyIncomeCnyCfx = gpuDailyIncomeCnyCfx.add(dailyCny);
                } else if (isRvnAlgorithm(algorithm)) {
                    coinSymbol = "RVN";
                    coinToXmrRate = rvnToXmrRate;
                    BigDecimal rvnToCnyRate = isPositive(coinToXmrRate) && isPositive(xmrToCnyRate)
                            ? coinToXmrRate.multiply(xmrToCnyRate)
                            : BigDecimal.ZERO;
                    dailyCny = isPositive(rvnToCnyRate)
                            ? dailyCoin.multiply(rvnToCnyRate).setScale(2, RoundingMode.HALF_UP)
                            : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
                    dailyCoin = applyEstimateNetMultiplier(dailyCoin, netMultiplier, 8);
                    dailyCny = applyEstimateNetMultiplier(dailyCny, netMultiplier, 2);
                    gpuDailyIncomeCnyRvn = gpuDailyIncomeCnyRvn.add(dailyCny);
                } else {
                    dailyCoin = applyEstimateNetMultiplier(dailyCoin, netMultiplier, 8);
                    dailyCny = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
                    gpuDailyIncomeCnyOther = gpuDailyIncomeCnyOther.add(dailyCny);
                }

                GpuAlgorithmDailyIncomeVo item = new GpuAlgorithmDailyIncomeVo();
                item.setAlgorithm(algorithm);
                item.setCoinSymbol(coinSymbol);
                item.setHashrateMh(hashrate);
                item.setDailyCoin(dailyCoin);
                item.setCoinToXmrRate(coinToXmrRate);
                item.setDailyIncomeCny(dailyCny);
                algorithmDailyIncomes.add(item);
            }
        }

        BigDecimal gpuDailyIncomeCny = gpuDailyIncomeCnyCfx.add(gpuDailyIncomeCnyRvn).add(gpuDailyIncomeCnyOther);
        // 兜底：若分类汇总为 0，但明细中已有收益，则按明细重新汇总
        if (algorithmDailyIncomes != null && !algorithmDailyIncomes.isEmpty()
                && gpuDailyIncomeCny.compareTo(BigDecimal.ZERO) <= 0) {
            BigDecimal sumFromAlgorithms = BigDecimal.ZERO;
            for (GpuAlgorithmDailyIncomeVo item : algorithmDailyIncomes) {
                if (item != null && item.getDailyIncomeCny() != null) {
                    sumFromAlgorithms = sumFromAlgorithms.add(item.getDailyIncomeCny());
                }
            }
            if (sumFromAlgorithms.compareTo(BigDecimal.ZERO) > 0) {
                gpuDailyIncomeCny = sumFromAlgorithms.setScale(2, RoundingMode.HALF_UP);
                log.error("Gpu daily income fallback applied: userId={}, sumFromAlgorithms={}, algorithmCount={}",
                        userId, sumFromAlgorithms, algorithmDailyIncomes.size());
            }
        }
        BigDecimal dailyIncomeCny = cpuDailyIncomeCny.add(gpuDailyIncomeCny).setScale(2, RoundingMode.HALF_UP);
        if (dailyIncomeCny.compareTo(BigDecimal.ZERO) <= 0 && gpuDailyIncomeCny.compareTo(BigDecimal.ZERO) > 0) {
            dailyIncomeCny = gpuDailyIncomeCny.setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal hourlyIncomeCny = dailyIncomeCny.divide(BigDecimal.valueOf(24), 2, RoundingMode.HALF_UP);
        BigDecimal monthlyIncomeCny = dailyIncomeCny.multiply(BigDecimal.valueOf(30)).setScale(2, RoundingMode.HALF_UP);

        HashrateSummaryVo vo = new HashrateSummaryVo();
        vo.setCpuHashrate(cpuHashrate);
        vo.setGpuHashrate(gpuHashrate);
        vo.setTotalHashrate(totalHashrate);
        vo.setHourlyIncomeCny(hourlyIncomeCny);
        vo.setDailyIncomeCny(dailyIncomeCny);
        vo.setCpuDailyIncomeCny(cpuDailyIncomeCny);
        vo.setGpuDailyIncomeCny(gpuDailyIncomeCny);
        vo.setGpuDailyIncomeCnyCfx(gpuDailyIncomeCnyCfx);
        vo.setGpuDailyIncomeCnyRvn(gpuDailyIncomeCnyRvn);
        vo.setGpuDailyIncomeCnyOther(gpuDailyIncomeCnyOther);
        vo.setGpuAlgorithmDailyIncomes(algorithmDailyIncomes);
        vo.setMonthlyIncomeCny(monthlyIncomeCny);
        return vo;
    }

    private IncomeContext buildIncomeContext() {
        BigDecimal poolTotalHashrate = marketDataService.getPoolTotalHashrate();
        if (poolTotalHashrate.compareTo(MIN_VALID_HASHRATE) < 0) {
            poolTotalHashrate = FALLBACK_NETWORK_HASHRATE;
        }

        IncomeContext ctx = new IncomeContext();
        ctx.poolTotalHashrate = poolTotalHashrate;
        ctx.ratio = safePositive(marketDataService.getCalXmrRatio());
        ctx.calToCnyRate = safePositive(marketDataService.getCalToCnyRate());
        ctx.bonusMultiplier = safeBonusMultiplier(bonusFactor);
        ctx.activePortProfit = sanitizeActivePortProfit(
                safePositive(marketDataService.getExternalPoolActivePortProfitXmrPerHashDay()));
        ctx.poolHourlyRewardXmr = safeConfig(xmrBlockReward).multiply(safeConfig(blocksPerHour));
        return ctx;
    }

    private BigDecimal estimateDailyIncomeCny(BigDecimal totalHashrate, IncomeContext ctx, boolean isCpu) {
        if (!isPositive(totalHashrate)) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal manual = isCpu ? manualCpuDailyCnyPer1000H : manualGpuDailyCnyPer1Mh;
        if (isPositive(manual)) {
            BigDecimal multiplier = isCpu
                    ? totalHashrate.divide(BigDecimal.valueOf(1000), 6, RoundingMode.HALF_UP)
                    : totalHashrate;
            return multiplier.multiply(manual).setScale(2, RoundingMode.HALF_UP);
        }
        if (ctx == null || !isPositive(ctx.ratio) || !isPositive(ctx.calToCnyRate)) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }

        BigDecimal hourlyXmrBase = BigDecimal.ZERO;
        BigDecimal hashrateForCalc = isCpu ? toMhFromHps(totalHashrate) : totalHashrate;
        if (isPositive(ctx.activePortProfit)) {
            BigDecimal dailyXmr = hashrateForCalc.multiply(ctx.activePortProfit);
            hourlyXmrBase = dailyXmr.divide(BigDecimal.valueOf(24), 18, RoundingMode.HALF_UP);
        } else if (isPositive(ctx.poolTotalHashrate) && isPositive(ctx.poolHourlyRewardXmr)) {
            BigDecimal share = hashrateForCalc.divide(ctx.poolTotalHashrate, 18, RoundingMode.HALF_UP);
            hourlyXmrBase = share.multiply(ctx.poolHourlyRewardXmr);
        }

        BigDecimal hourlyXmr = hourlyXmrBase.multiply(ctx.bonusMultiplier);
        return convertXmrToCny(hourlyXmr, ctx.ratio, ctx.calToCnyRate, BigDecimal.valueOf(24));
    }

    private BigDecimal estimateGpuDailyIncomeCny(BigDecimal totalHashrate, String algorithm) {
        if (!isPositive(totalHashrate)) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal manual = manualGpuDailyCnyPer1Mh;
        if (isPositive(manual)) {
            return totalHashrate.multiply(manual).setScale(2, RoundingMode.HALF_UP);
        }
        String normalizedAlgorithm = normalizeAlgorithm(algorithm);
        BigDecimal dailyCoinPerMh = resolveGpuDailyCoinPerMh(normalizedAlgorithm);
        if (!isPositive(dailyCoinPerMh)) {
            log.warn("GPU Octopus收益计算失败: dailyCoinPerMh为0, algorithm={}, normalizedAlgorithm={}, hashrate={}",
                    algorithm, normalizedAlgorithm, totalHashrate);
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal dailyCoin = totalHashrate.multiply(dailyCoinPerMh).setScale(8, RoundingMode.HALF_UP);
        if (isCfxAlgorithm(normalizedAlgorithm)) {
            BigDecimal cfxToCnyRate = safePositive(marketDataService.getCfxToCnyRate());
            if (!isPositive(cfxToCnyRate)) {
                log.warn("GPU Octopus收益计算失败: cfxToCnyRate为0, algorithm={}, hashrate={}, dailyCoinPerMh={}, dailyCoin={}",
                        algorithm, totalHashrate, dailyCoinPerMh, dailyCoin);
                return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            }
            BigDecimal incomeBeforeFee = dailyCoin.multiply(cfxToCnyRate);
            boolean applyPoolFee = !marketDataService.isCfxProfitFromF2Pool();
            BigDecimal income = incomeBeforeFee;
            if (applyPoolFee) {
                // 应用矿池费率折扣（非 F2Pool 数据源时生效）
                BigDecimal feeMultiplier = BigDecimal.ONE.subtract(
                        safePositive(gpuOctopusPoolFeeRate).min(BigDecimal.ONE).max(BigDecimal.ZERO)
                );
                income = incomeBeforeFee.multiply(feeMultiplier);
            }
            income = income.setScale(2, RoundingMode.HALF_UP);
            log.debug("GPU Octopus收益计算成功: algorithm={}, hashrate={}, dailyCoinPerMh={}, cfxToCnyRate={}, poolFeeRate={}, applyPoolFee={}, profitSource={}, incomeBeforeFee={}, income={}",
                    algorithm, totalHashrate, dailyCoinPerMh, cfxToCnyRate, gpuOctopusPoolFeeRate, applyPoolFee,
                    marketDataService.getCfxProfitSource(), incomeBeforeFee, income);
            return income;
        }
        if (isRvnAlgorithm(normalizedAlgorithm)) {
            BigDecimal rvnToXmrRate = safePositive(marketDataService.getRvnToXmrRate());
            BigDecimal xmrToCnyRate = safePositive(marketDataService.getXmrToCnyRate());
            BigDecimal rvnToCnyRate = isPositive(rvnToXmrRate) && isPositive(xmrToCnyRate)
                    ? rvnToXmrRate.multiply(xmrToCnyRate)
                    : BigDecimal.ZERO;
            return isPositive(rvnToCnyRate)
                    ? dailyCoin.multiply(rvnToCnyRate).setScale(2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        log.warn("GPU收益计算失败: 未知算法, algorithm={}, normalizedAlgorithm={}, hashrate={}",
                algorithm, normalizedAlgorithm, totalHashrate);
        return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal estimateGpuDailyIncomeCnyWithFallback(BigDecimal totalHashrate, String algorithm, IncomeContext ctx) {
        BigDecimal income = estimateGpuDailyIncomeCny(totalHashrate, algorithm);
        if (isPositive(income)) {
            return income;
        }
        String normalizedAlgorithm = normalizeAlgorithm(algorithm);
        if (isCfxAlgorithm(normalizedAlgorithm) || isRvnAlgorithm(normalizedAlgorithm)) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        if (isPositive(safePositive(marketDataService.getCfxDailyCoinPerMh()))) {
            return estimateGpuDailyIncomeCny(totalHashrate, "octopus");
        }
        if (isPositive(safePositive(marketDataService.getRvnDailyCoinPerMh()))) {
            return estimateGpuDailyIncomeCny(totalHashrate, "kawpow");
        }
        return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    }

    private void applyGpuEstimatesFromAlgorithm(Device device, BigDecimal totalGpuHashrate, String algorithmRaw) {
        if (device == null) {
            return;
        }
        String algorithm = normalizeAlgorithm(algorithmRaw);
        if (isCfxAlgorithm(algorithm)) {
            applyDeviceGpuAlgorithmStats(device, totalGpuHashrate, BigDecimal.ZERO);
        } else if (isRvnAlgorithm(algorithm)) {
            applyDeviceGpuAlgorithmStats(device, BigDecimal.ZERO, totalGpuHashrate);
        } else {
            // 如果算法未知或不匹配，清零两个算法特定的算力字段
            applyDeviceGpuAlgorithmStats(device, BigDecimal.ZERO, BigDecimal.ZERO);
        }
    }

    private void applyDeviceGpuAlgorithmStats(Device device, BigDecimal octopusHashrate, BigDecimal kawpowHashrate) {
        if (device == null) {
            return;
        }
        BigDecimal octopus = safeDecimal(octopusHashrate).setScale(2, RoundingMode.HALF_UP);
        BigDecimal kawpow = safeDecimal(kawpowHashrate).setScale(2, RoundingMode.HALF_UP);
        device.setGpuHashrateOctopus(octopus.doubleValue());
        device.setGpuHashrateKawpow(kawpow.doubleValue());

        BigDecimal incomeOctopus = estimateGpuDailyIncomeCny(octopus, "octopus");
        BigDecimal incomeKawpow = estimateGpuDailyIncomeCny(kawpow, "kawpow");
        BigDecimal totalIncome = incomeOctopus.add(incomeKawpow).setScale(2, RoundingMode.HALF_UP);
        device.setGpuDailyIncomeCnyOctopus(incomeOctopus);
        device.setGpuDailyIncomeCnyKawpow(incomeKawpow);
        device.setGpuDailyIncomeCny(totalIncome);
    }

    private BigDecimal resolveDeviceGpuDailyIncomeCny(Device device, IncomeContext ctx) {
        if (device == null) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal incomeOctopus = resolveDeviceGpuDailyIncomeCnyOctopus(device, ctx);
        BigDecimal incomeKawpow = resolveDeviceGpuDailyIncomeCnyKawpow(device, ctx);
        return incomeOctopus.add(incomeKawpow).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal resolveDeviceGpuDailyIncomeCnyOctopus(Device device, IncomeContext ctx) {
        if (device == null) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal hashrate = toDecimal(device.getGpuHashrateOctopus());
        return estimateGpuDailyIncomeCnyWithFallback(hashrate, "octopus", ctx);
    }

    private BigDecimal resolveDeviceGpuDailyIncomeCnyKawpow(Device device, IncomeContext ctx) {
        if (device == null) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal hashrate = toDecimal(device.getGpuHashrateKawpow());
        return estimateGpuDailyIncomeCnyWithFallback(hashrate, "kawpow", ctx);
    }

    private BigDecimal resolveGpuDailyCoinPerMh(String algorithm) {
        if (isCfxAlgorithm(algorithm)) {
            return safePositive(marketDataService.getCfxDailyCoinPerMh());
        }
        if (isRvnAlgorithm(algorithm)) {
            return safePositive(marketDataService.getRvnDailyCoinPerMh());
        }
        return BigDecimal.ZERO;
    }

    private String normalizeAlgorithm(String algorithm) {
        if (algorithm == null) {
            return "unknown";
        }
        String v = algorithm.trim().toLowerCase(Locale.ROOT);
        if (v.isEmpty()) {
            return "unknown";
        }
        String compact = v.replaceAll("[^a-z0-9]", "");
        if (!compact.isEmpty()) {
            if (compact.startsWith("oct") || compact.contains("octopus") || compact.contains("cfx")) {
                return "octopus";
            }
            if (compact.startsWith("kaw") || compact.contains("kawpow") || compact.contains("rvn")) {
                return "kawpow";
            }
        }
        return v;
    }

    private boolean isCfxAlgorithm(String algorithm) {
        return "octopus".equalsIgnoreCase(algorithm) || "cfx".equalsIgnoreCase(algorithm);
    }

    private boolean isRvnAlgorithm(String algorithm) {
        return "kawpow".equalsIgnoreCase(algorithm) || "rvn".equalsIgnoreCase(algorithm);
    }

    private static class IncomeContext {
        private BigDecimal poolTotalHashrate;
        private BigDecimal ratio;
        private BigDecimal calToCnyRate;
        private BigDecimal bonusMultiplier;
        private BigDecimal activePortProfit;
        private BigDecimal poolHourlyRewardXmr;
    }

    private BigDecimal convertXmrToCny(BigDecimal hourlyXmr, BigDecimal ratio, BigDecimal calToCnyRate, BigDecimal hours) {
        if (!isPositive(hourlyXmr) || !isPositive(ratio) || !isPositive(calToCnyRate) || hours == null) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal periodXmr = hourlyXmr.multiply(hours);
        // 1 CAL = ratio * XMR，因此 CAL = XMR / ratio
        BigDecimal periodCal = periodXmr.divide(ratio, 18, RoundingMode.HALF_UP);
        return periodCal.multiply(calToCnyRate).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal safeHashrate(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal sanitizeActivePortProfit(BigDecimal value) {
        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal max = activePortProfitMax;
        if (max == null || max.compareTo(BigDecimal.ZERO) <= 0) {
            max = new BigDecimal("0.1");
        }
        if (value.compareTo(max) > 0) {
            return BigDecimal.ZERO;
        }
        return value;
    }

    private BigDecimal toCpuHps(Double value) {
        if (value == null) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        // 客户端 CPU 上报单位为 H/s，服务端保持 H/s 存储/展示
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal toMhFromHps(BigDecimal value) {
        if (value == null) {
            return BigDecimal.ZERO.setScale(6, RoundingMode.HALF_UP);
        }
        return value.divide(BigDecimal.valueOf(1_000_000L), 6, RoundingMode.HALF_UP);
    }

    private BigDecimal safeConfig(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private BigDecimal safePositive(BigDecimal value) {
        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return value;
    }

    private BigDecimal safeBonusMultiplier(BigDecimal value) {
        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ONE;
        }
        return value;
    }

    private BigDecimal resolveEstimateNetMultiplier(Long userId) {
        BigDecimal platformRate = clampRate(safePositive(resolvePlatformCommissionRate()));
        BigDecimal userRate = BigDecimal.ONE.subtract(platformRate);
        if (userId == null) {
            return userRate;
        }
        User user = userMapper.selectById(userId).orElse(null);
        if (user == null) {
            return userRate;
        }
        Long inviterId = user.getInviterId();
        if (inviterId != null) {
            InviteProperties.InviteeDiscount discount = inviteProperties != null ? inviteProperties.getInviteeDiscount() : null;
            if (discount != null && discount.isEnabled() && isWithinDiscountWindow(user, discount.getDurationDays())) {
                BigDecimal platformFeeMultiplier = clampRate(safePositive(discount.getPlatformFeeMultiplier()));
                BigDecimal discountRate = BigDecimal.ONE.subtract(platformFeeMultiplier);
                if (discountRate.compareTo(BigDecimal.ZERO) > 0) {
                    userRate = userRate.add(platformRate.multiply(discountRate));
                }
            }
            BigDecimal inviterRate = inviteService != null
                    ? clampRate(safePositive(inviteService.getCommissionRateForUser(inviterId)))
                    : BigDecimal.ZERO;
            if (inviterRate.compareTo(BigDecimal.ZERO) > 0) {
                userRate = userRate.multiply(BigDecimal.ONE.subtract(inviterRate));
            }
        }
        if (userRate.compareTo(BigDecimal.ZERO) < 0) {
            return BigDecimal.ZERO;
        }
        if (userRate.compareTo(BigDecimal.ONE) > 0) {
            return BigDecimal.ONE;
        }
        return userRate;
    }

    private BigDecimal resolvePlatformCommissionRate() {
        if (platformSettingsService == null) {
            return platformCommissionRate;
        }
        BigDecimal rate = platformSettingsService.getPlatformCommissionRate();
        return rate != null ? rate : platformCommissionRate;
    }

    private BigDecimal applyEstimateNetMultiplier(BigDecimal value, BigDecimal multiplier, int scale) {
        if (!isPositive(value)) {
            return BigDecimal.ZERO.setScale(scale, RoundingMode.HALF_UP);
        }
        BigDecimal rate = (multiplier == null) ? BigDecimal.ONE : multiplier;
        return value.multiply(rate).setScale(scale, RoundingMode.HALF_UP);
    }

    private BigDecimal clampRate(BigDecimal value) {
        BigDecimal v = value == null ? BigDecimal.ZERO : value;
        if (v.compareTo(BigDecimal.ZERO) < 0) {
            return BigDecimal.ZERO;
        }
        if (v.compareTo(BigDecimal.ONE) > 0) {
            return BigDecimal.ONE;
        }
        return v;
    }

    private boolean isWithinDiscountWindow(User user, int durationDays) {
        if (user == null || durationDays <= 0 || user.getCreateTime() == null) {
            return false;
        }
        return !LocalDateTime.now().isAfter(user.getCreateTime().plusDays(durationDays));
    }

    private boolean isPositive(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) > 0;
    }

    private String generateDeviceSecret() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private LocalDateTime truncateToMinute(LocalDateTime time) {
        if (time == null) {
            return null;
        }
        return time.withSecond(0).withNano(0);
    }

    private BigDecimal toDecimal(Double value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal safeDecimal(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /**
     * 定时任务：将超过阈值未收到算力上报（last_online_time 未刷新）的设备判定为离线。
     */
    @Scheduled(fixedDelayString = "${app.devices.offline-scan-fixed-delay-ms:60000}")
    public void markDevicesOfflineIfHeartbeatExpired() {
        if (deviceOfflineThresholdMinutes <= 0) {
            return;
        }
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(deviceOfflineThresholdMinutes);
        int affected = deviceMapper.markDevicesOffline(cutoff);
        if (affected > 0) {
            log.info("Marked {} devices offline due to no heartbeat since {}", affected, cutoff);
        }
    }
}
