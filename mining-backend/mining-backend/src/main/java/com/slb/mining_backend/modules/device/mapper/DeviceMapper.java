package com.slb.mining_backend.modules.device.mapper;

import com.slb.mining_backend.modules.device.entity.Device;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import com.slb.mining_backend.modules.device.vo.UserHashrateSummaryVo;

@Mapper
public interface DeviceMapper {

    /**
     * 插入一个新设备
     */
    void insert(Device device);

    /**
     * 根据设备ID查找设备 (包括已软删除的)
     */
    Optional<Device> findById(@Param("id") String id);

    /**
     * 根据设备ID和用户ID查找设备 (确保设备属于该用户)
     */
    Optional<Device> findByIdAndUserId(@Param("id") String id, @Param("userId") Long userId);

    /**
     * 更新设备信息
     */
    int update(Device device);

    /**
     * 根据条件查询设备列表总数
     */
    long countByUserIdAndStatus(@Param("userId") Long userId, @Param("status") Integer status);

    /**
     * 根据条件分页查询设备列表
     */
    List<Device> findByUserIdAndStatusPaginated(
            @Param("userId") Long userId,
            @Param("status") Integer status,
            @Param("offset") int offset,
            @Param("size") int size,
            @Param("sortBy") String sortBy,
            @Param("orderBy") String orderBy
    );

    long countTotalDevices();
    long countOnlineDevices();
    BigDecimal sumTotalCpuHashrate();
    BigDecimal sumTotalGpuHashrate();
    BigDecimal sumTotalGpuHashrateOctopus();
    BigDecimal sumTotalGpuHashrateKawpow();

    BigDecimal sumCpuHashrateByUserId(@Param("userId") Long userId);
    BigDecimal sumGpuHashrateByUserId(@Param("userId") Long userId);
    BigDecimal sumGpuHashrateOctopusByUserId(@Param("userId") Long userId);
    BigDecimal sumGpuHashrateKawpowByUserId(@Param("userId") Long userId);

    List<UserHashrateSummaryVo> sumHashrateByUser();
    List<UserHashrateSummaryVo> sumHashrateByUserIds(@Param("userIds") List<Long> userIds);
    List<UserHashrateSummaryVo> sumGpuHashrateOctopusByUser();
    List<UserHashrateSummaryVo> sumGpuHashrateKawpowByUser();

    long countAdminDevices(@Param("keyword") String keyword,
                           @Param("status") Integer status,
                           @Param("ownerUid") Long ownerUid);
    List<com.slb.mining_backend.modules.admin.vo.AdminDeviceListItemVo> findAdminDevicesPaginated(
            @Param("keyword") String keyword,
            @Param("status") Integer status,
            @Param("ownerUid") Long ownerUid,
            @Param("offset") int offset,
            @Param("size") int size
    );

    /**
     * 将超过截止时间未再上报心跳的设备标记为离线并清零算力。
     *
     * @param cutoffTime 判断离线的时间阈值
     * @return 受影响的记录数
     */
    int markDevicesOffline(@Param("cutoffTime") LocalDateTime cutoffTime);

}
