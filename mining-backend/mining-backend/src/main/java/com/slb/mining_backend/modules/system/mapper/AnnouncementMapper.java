package com.slb.mining_backend.modules.system.mapper;

import com.slb.mining_backend.modules.system.entity.Announcement;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

@Mapper
public interface AnnouncementMapper {
    List<Announcement> findPaginated(int offset, int size);
    long count();

    // --- 管理员端方法 ---
    void insert(Announcement announcement);
    int update(Announcement announcement);
    void deleteById(@Param("id") Integer id);
    Optional<Announcement> findById(@Param("id") Integer id);
    List<Announcement> findAllPaginated(@Param("offset") int offset, @Param("size") int size);
    long countAll();
}