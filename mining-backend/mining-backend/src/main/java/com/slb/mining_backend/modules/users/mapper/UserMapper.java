package com.slb.mining_backend.modules.users.mapper;

import com.slb.mining_backend.modules.users.entity.User;
import com.slb.mining_backend.modules.users.dto.WorkerUserBinding;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Mapper
public interface UserMapper {

    /**
     * 根据用户名查找用户
     *
     * @param userName 用户名
     * @return Optional<User>
     */
    Optional<User> selectByUserName(@Param("userName") String userName);

//    /**
//     * 根据邮箱名查找用户
//     *
//     * @param userName 用户名
//     * @return Optional<User>
//     */
//    Optional<User> selectByEmail(@Param("email") String userName);

    /**
     * 根据ID查找用户
     *
     * @param id 用户ID
     * @return Optional<User>
     */
    Optional<User> selectById(@Param("id") Long id);

    /**
     * 插入一个新用户
     *
     * @param user 用户实体
     * @return 影响的行数
     */
    int insert(User user);

    /**
     * 更新用户信息
     *
     * @param user 用户实体
     * @return 影响的行数
     */
    int update(User user);

    /**
     * 更新用户密码信息
     *
     * @param id 用户实体
     * @return 影响的行数
     */
    int updatePassword(@Param("id") Long id,
                       @Param("hash") String hash,
//                       @Param("pwd") String pwd,
                       @Param("salt") String salt);

    /**
     * 通过邮箱查找用户
     *
     * @param email 邮箱地址
     * @return Optional<User>
     */
    Optional<User> selectByUserEmail(@Param("email") String email);

    /**
     * 通过邀请码查找用户
     *
     * @param inviteCode 邀请码
     * @return Optional<User>
     */
    Optional<User> selectByInviteCode(@Param("inviteCode") String inviteCode);

    /**
     * 根据 Worker ID 查找用户
     *
     * @param workerId 矿工ID
     * @return Optional<User>
     */
    Optional<User> selectByWorkerId(@Param("workerId") String workerId);

    void updateUserWallet(@Param("userId") Long userId, @Param("calAmount") BigDecimal calAmount);

    /**
     * 仅更新 CAL 余额（不影响累计收益）。
     */
    void updateCalBalance(@Param("userId") Long userId, @Param("calAmount") BigDecimal calAmount);

    /**
     * 更新余额
     *
     * @param userId              用户id
     * @param cashBalanceChange   改变的余额
     * @param frozenBalanceChange 冻结的余额
     * @param withdrawnChange     提现的余额
     * @return null
     */
    int updateCashBalances(
            @Param("userId") Long userId,
            @Param("cashBalanceChange") BigDecimal cashBalanceChange,
            @Param("frozenBalanceChange") BigDecimal frozenBalanceChange,
            @Param("withdrawnChange") BigDecimal withdrawnChange
    );

    int updateSettlementCurrency(
            @Param("userId") Long userId,
            @Param("currency") String currency
    );

    /**
     * 更新用户的 XMR 相关余额。该方法原子性地调整用户的 XMR 余额、冻结的 XMR 以及累计产出的 XMR。
     *
     * @param userId           用户 ID
     * @param balanceChange    变动的 XMR 余额（正数增加，负数减少）
     * @param frozenChange     变动的冻结中的 XMR（正数增加，负数减少）
     * @param totalEarnedChange 变动的累计产出 XMR（正数增加，负数减少）
     * @return 受影响的行数
     */
    int updateXmrBalances(
            @Param("userId") Long userId,
            @Param("balanceChange") BigDecimal balanceChange,
            @Param("frozenChange") BigDecimal frozenChange,
            @Param("totalEarnedChange") BigDecimal totalEarnedChange
    );

    long countTotalUsers();
    long countActiveUsers();
    BigDecimal sumTotalCalBalance();
    BigDecimal sumTotalCnyBalance();

    List<User> selectAllForXmr();

    List<WorkerUserBinding> selectByWorkerIds(@Param("workerIds") List<String> workerIds);

    /**
     * 锁定用户行（FOR UPDATE），用于同一用户的关键资金操作互斥（例如提现申请防并发）。
     * 返回用户 id；若用户不存在则返回 null。
     */
    Long lockByIdForUpdate(@Param("id") Long id);

    /**
     * 拉取所有有效的 workerId，用于 Redis 白名单。
     */
    List<String> selectActiveWorkerIds();

}
