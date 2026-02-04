package com.slb.mining_backend.modules.system.mapper;

import com.slb.mining_backend.modules.system.entity.Feedback;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface FeedbackMapper {
    void insert(Feedback feedback);

}