package com.slb.mining_backend.modules.device.mapper;

import com.slb.mining_backend.modules.device.entity.DeviceRemoteCommand;
import com.slb.mining_backend.modules.device.enums.CommandStatus;
import com.slb.mining_backend.modules.device.enums.CommandType;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 设备远程控制指令 Mapper
 */
@Mapper
public interface DeviceRemoteCommandMapper {
    
    /**
     * 插入新指令
     */
    void insert(DeviceRemoteCommand command);
    
    /**
     * 查询最新的待执行指令（按创建时间倒序）
     */
    DeviceRemoteCommand findLatestPendingCommand(
        @Param("userId") Long userId,
        @Param("deviceId") String deviceId,
        @Param("types") List<CommandType> types,
        @Param("now") LocalDateTime now
    );
    
    /**
     * 将旧指令标记为过期（避免冲突）
     */
    void expireOldCommands(
        @Param("deviceId") String deviceId,
        @Param("type") CommandType type
    );
    
    /**
     * 根据commandId和userId查询指令
     */
    Optional<DeviceRemoteCommand> findByCommandIdAndUserId(
        @Param("commandId") String commandId,
        @Param("userId") Long userId
    );
    
    /**
     * 更新指令
     */
    void update(DeviceRemoteCommand command);
    
    /**
     * 定时任务：清理过期指令（建议保留7天历史记录）
     */
    int expireTimeoutCommands(@Param("now") LocalDateTime now);
    
    /**
     * 定时任务：删除7天前的历史记录
     */
    int deleteOldRecords(@Param("threshold") LocalDateTime threshold);
}

