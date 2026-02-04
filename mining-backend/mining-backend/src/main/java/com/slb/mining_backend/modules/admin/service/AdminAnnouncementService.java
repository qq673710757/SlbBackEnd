package com.slb.mining_backend.modules.admin.service;

import com.slb.mining_backend.common.exception.BizException;
import com.slb.mining_backend.common.vo.PageVo;
import com.slb.mining_backend.modules.admin.dto.AnnouncementCreateDto;
import com.slb.mining_backend.modules.admin.dto.AnnouncementUpdateDto;
import com.slb.mining_backend.modules.system.entity.Announcement;
import com.slb.mining_backend.modules.system.mapper.AnnouncementMapper;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AdminAnnouncementService {

    private final AnnouncementMapper announcementMapper;

    public AdminAnnouncementService(AnnouncementMapper announcementMapper) {
        this.announcementMapper = announcementMapper;
    }

    public PageVo<Announcement> listAll(int page, int size) {
        if (page < 1) {
            throw new BizException("page 必须 >= 1");
        }
        if (size < 1 || size > 100) {
            throw new BizException("size 参数非法，仅支持 1-100");
        }
        long total = announcementMapper.countAll();
        if (total == 0) {
            return new PageVo<>(0L, page, size, List.of());
        }
        int offset = (page - 1) * size;
        List<Announcement> list = announcementMapper.findAllPaginated(offset, size);
        return new PageVo<>(total, page, size, list);
    }

    public Announcement getById(Integer id) {
        return announcementMapper.findById(id)
                .orElseThrow(() -> new BizException("公告不存在"));
    }

    public void create(AnnouncementCreateDto dto) {
        validateStatus(dto.getStatus());
        Announcement announcement = new Announcement();
        announcement.setTitle(dto.getTitle());
        announcement.setContent(dto.getContent());
        announcement.setIsImportant(dto.getIsImportant());
        announcement.setStatus(dto.getStatus());
        announcementMapper.insert(announcement);
    }

    public void update(Integer id, AnnouncementUpdateDto dto) {
        validateStatus(dto.getStatus());
        Announcement existing = getById(id);
        existing.setTitle(dto.getTitle());
        existing.setContent(dto.getContent());
        existing.setIsImportant(dto.getIsImportant());
        existing.setStatus(dto.getStatus());
        int updated = announcementMapper.update(existing);
        if (updated <= 0) {
            throw new BizException("公告更新失败");
        }
    }

    public void updateStatus(Integer id, Integer status) {
        validateStatus(status);
        Announcement existing = getById(id);
        existing.setStatus(status);
        int updated = announcementMapper.update(existing);
        if (updated <= 0) {
            throw new BizException("公告状态更新失败");
        }
    }

    public void delete(Integer id) {
        announcementMapper.deleteById(id);
    }

    private void validateStatus(Integer status) {
        if (status == null || (status != 0 && status != 1)) {
            throw new BizException("status 参数非法，仅支持 0/1");
        }
    }
}
