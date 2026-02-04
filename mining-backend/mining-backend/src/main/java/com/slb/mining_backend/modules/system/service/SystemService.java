package com.slb.mining_backend.modules.system.service;

import com.slb.mining_backend.common.security.CustomUserDetails;
import com.slb.mining_backend.common.vo.PageVo;
import com.slb.mining_backend.modules.device.mapper.DeviceMapper;
import com.slb.mining_backend.modules.earnings.service.MarketDataService;
import com.slb.mining_backend.modules.system.dto.FeedbackSubmitDto;
import com.slb.mining_backend.modules.system.entity.Announcement;
import com.slb.mining_backend.modules.system.entity.Feedback;
import com.slb.mining_backend.modules.system.mapper.AnnouncementMapper;
import com.slb.mining_backend.modules.system.mapper.FeedbackMapper;
import com.slb.mining_backend.modules.system.vo.SystemStatusVo;
import com.slb.mining_backend.modules.users.mapper.UserMapper;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class SystemService {

    private final AnnouncementMapper announcementMapper;
    private final FeedbackMapper feedbackMapper;
    private final UserMapper userMapper;
    private final DeviceMapper deviceMapper;
    private final MarketDataService marketDataService;

    public SystemService(AnnouncementMapper announcementMapper, FeedbackMapper feedbackMapper, UserMapper userMapper, DeviceMapper deviceMapper, MarketDataService marketDataService) {
        this.announcementMapper = announcementMapper;
        this.feedbackMapper = feedbackMapper;
        this.userMapper = userMapper;
        this.deviceMapper = deviceMapper;
        this.marketDataService = marketDataService;
    }


    public PageVo<Announcement> getAnnouncements(int page, int size) {
        long total = announcementMapper.count();
        int offset = (page - 1) * size;
        List<Announcement> list = announcementMapper.findPaginated(offset, size);
        return new PageVo<>(total, page, size, list);
    }

    public void submitFeedback(FeedbackSubmitDto dto, CustomUserDetails userDetails) {
        Feedback feedback = new Feedback();
        if (userDetails != null) {
            feedback.setUserId(userDetails.getUser().getId());
        }
        feedback.setType(dto.getType());
        feedback.setContent(dto.getContent());
        feedback.setContact(dto.getContact());
        feedbackMapper.insert(feedback);
    }

    public SystemStatusVo getSystemStatus() {
        return SystemStatusVo.builder()
                .totalDevices(deviceMapper.countTotalDevices())
                .onlineDevices(deviceMapper.countByUserIdAndStatus(null, 1))
                .totalUsers(userMapper.countTotalUsers())
                .activeUsers(userMapper.countActiveUsers())
                .totalCpuHashrate(deviceMapper.sumTotalCpuHashrate())
                .totalGpuHashrate(deviceMapper.sumTotalGpuHashrate())
                .calToCnyRate(marketDataService.getCalToCnyRate())
                .serverStatus("normal")
                .maintenancePlanned(false)
                .build();
    }
}