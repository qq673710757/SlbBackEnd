package com.slb.mining_backend.modules.xmr.mapper;

import com.slb.mining_backend.modules.xmr.entity.XmrUserAddress;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

/**
 * MyBatis mapper：操作 xmr_user_addresses 表。
 */
@Mapper
public interface XmrUserAddressMapper {

    /**
     * 插入新的子地址记录
     *
     * @param address 实体
     * @return 影响行数
     */
    int insert(XmrUserAddress address);

    /**
     * 根据用户 ID 查询该用户的所有地址
     *
     * @param userId 用户 ID
     * @return 地址列表
     */
    List<XmrUserAddress> selectByUserId(@Param("userId") Long userId);

    /**
     * 查询某个用户的激活的地址记录（假设每个用户只有一个主用地址）
     *
     * @param userId 用户 ID
     * @return Optional 地址
     */
    Optional<XmrUserAddress> selectActiveByUserId(@Param("userId") Long userId);

    /**
     * 根据子地址字符串查找所属用户。
     *
     * @param subaddress 子地址
     * @return Optional 地址
     */
    Optional<XmrUserAddress> selectBySubaddress(@Param("subaddress") String subaddress);

    /**
     * 根据 account_index 与 subaddress_index 定位子地址。
     *
     * @param accountIndex    major index
     * @param subaddressIndex minor index
     * @return Optional 地址
     */
    Optional<XmrUserAddress> selectByAccountAndIndex(@Param("accountIndex") Integer accountIndex,
                                                     @Param("subaddressIndex") Integer subaddressIndex);

    /**
     * 按标签（通常映射到 payment id）查找子地址。
     *
     * @param label 标签
     * @return Optional 地址
     */
    Optional<XmrUserAddress> selectByLabel(@Param("label") String label);

    /**
     * 查询全部激活状态的子地址，供批量同步使用。
     *
     * @return 激活子地址列表
     */
    List<XmrUserAddress> selectAllActive();
}