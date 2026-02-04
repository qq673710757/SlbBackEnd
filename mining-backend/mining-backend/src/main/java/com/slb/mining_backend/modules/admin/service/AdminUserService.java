package com.slb.mining_backend.modules.admin.service;

import com.slb.mining_backend.common.exception.BizException;
import com.slb.mining_backend.common.vo.PageVo;
import com.slb.mining_backend.modules.admin.vo.AdminUserDetailVo;
import com.slb.mining_backend.modules.device.mapper.DeviceMapper;
import com.slb.mining_backend.modules.device.vo.UserHashrateSummaryVo;
import com.slb.mining_backend.modules.invite.mapper.InviteMapper;
import com.slb.mining_backend.modules.invite.vo.InviteRecordsVo;
import com.slb.mining_backend.modules.users.entity.User;
import com.slb.mining_backend.modules.users.mapper.UserMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class AdminUserService {

    private static final BigDecimal ONE_MILLION = BigDecimal.valueOf(1_000_000L);

    private final UserMapper userMapper;
    private final DeviceMapper deviceMapper;
    private final InviteMapper inviteMapper;

    public AdminUserService(UserMapper userMapper, DeviceMapper deviceMapper, InviteMapper inviteMapper) {
        this.userMapper = userMapper;
        this.deviceMapper = deviceMapper;
        this.inviteMapper = inviteMapper;
    }

    public AdminUserDetailVo getUserDetail(Long uid, int invitePage, int inviteSize) {
        User user = userMapper.selectById(uid).orElseThrow(() -> new BizException("用户不存在"));

        BigDecimal cpuHps = safe(deviceMapper.sumCpuHashrateByUserId(uid));
        BigDecimal cpuKh = cpuHps.divide(BigDecimal.valueOf(1000L), 2, RoundingMode.HALF_UP);
        BigDecimal cfxMh = safe(deviceMapper.sumGpuHashrateOctopusByUserId(uid)).setScale(2, RoundingMode.HALF_UP);
        BigDecimal rvnMh = safe(deviceMapper.sumGpuHashrateKawpowByUserId(uid)).setScale(2, RoundingMode.HALF_UP);
        BigDecimal commissionTotal = safe(inviteMapper.sumTotalCommissionByUserId(uid));

        AdminUserDetailVo vo = new AdminUserDetailVo();
        vo.setUid(user.getId());
        vo.setUsername(user.getUserName());
        vo.setRegisterTime(user.getCreateTime());
        vo.setRegisterChannel(user.getRegInto());
        vo.setCpuKh(cpuKh);
        vo.setCfxMh(cfxMh);
        vo.setRvnMh(rvnMh);
        vo.setCalBalance(user.getCalBalance());
        vo.setCnyBalance(user.getCashBalance());
        vo.setCommissionTotal(commissionTotal);
        vo.setInvited(buildInvited(uid, invitePage, inviteSize));
        return vo;
    }

    private PageVo<AdminUserDetailVo.InvitedItem> buildInvited(Long uid, int page, int size) {
        long total = inviteMapper.countInviteesByUserId(uid);
        if (total <= 0) {
            return new PageVo<>(0L, page, size, List.of());
        }
        int offset = Math.max(0, (page - 1) * size);
        List<InviteRecordsVo.RecordItem> records = inviteMapper.findInviteRecordsPaginated(uid, offset, size);
        if (CollectionUtils.isEmpty(records)) {
            return new PageVo<>(total, page, size, List.of());
        }
        List<Long> inviteeIds = records.stream()
                .map(InviteRecordsVo.RecordItem::getInviteeUid)
                .filter(id -> id != null && id > 0)
                .toList();
        Map<Long, UserHashrateSummaryVo> hashrateMap = new HashMap<>();
        if (!CollectionUtils.isEmpty(inviteeIds)) {
            List<UserHashrateSummaryVo> summaries = deviceMapper.sumHashrateByUserIds(inviteeIds);
            if (summaries != null) {
                for (UserHashrateSummaryVo summary : summaries) {
                    if (summary != null && summary.getUserId() != null) {
                        hashrateMap.put(summary.getUserId(), summary);
                    }
                }
            }
        }

        List<AdminUserDetailVo.InvitedItem> list = records.stream().map(record -> {
            AdminUserDetailVo.InvitedItem item = new AdminUserDetailVo.InvitedItem();
            item.setUid(record.getInviteeUid());
            item.setUsername(record.getInviteeName());
            item.setRegisterTime(record.getCreateTime());
            item.setCommissionEarned(record.getCommissionEarned());
            item.setCurrentHashrate(resolveTotalHashrate(hashrateMap.get(record.getInviteeUid())));
            return item;
        }).toList();
        return new PageVo<>(total, page, size, list);
    }

    private BigDecimal resolveTotalHashrate(UserHashrateSummaryVo summary) {
        if (summary == null) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal cpuMh = safe(summary.getCpuHashrate()).divide(ONE_MILLION, 6, RoundingMode.HALF_UP);
        BigDecimal gpuMh = safe(summary.getGpuHashrate());
        return cpuMh.add(gpuMh).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
