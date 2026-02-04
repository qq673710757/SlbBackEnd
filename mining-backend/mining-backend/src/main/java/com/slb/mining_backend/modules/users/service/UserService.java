package com.slb.mining_backend.modules.users.service;

import com.slb.mining_backend.modules.users.dto.code.EmailCodeLoginDTO;
import com.slb.mining_backend.modules.users.dto.code.EmailResetPasswordDTO;
import com.slb.mining_backend.modules.users.dto.code.SendCodeDTO;
import com.slb.mining_backend.modules.users.enums.SettlementCurrency;
import com.slb.mining_backend.modules.users.dto.user.ResetPasswordDTO;
import com.slb.mining_backend.modules.users.dto.user.UpdateAlipayAccountDTO;
import com.slb.mining_backend.modules.users.dto.user.UserLoginDTO;
import com.slb.mining_backend.modules.users.dto.user.UserRegisterDTO;
import com.slb.mining_backend.modules.users.dto.user.UserUpdateDTO;
import com.slb.mining_backend.modules.users.vo.UserLoginVO;
import com.slb.mining_backend.modules.users.vo.UserProfileVO;
import jakarta.servlet.http.HttpServletRequest;

public interface UserService {

    /**
     * 用户注册
     * @param registerDTO 注册信息 DTO
     * @return 包含 Token 的登录视图对象
     */
    UserLoginVO register(UserRegisterDTO registerDTO, HttpServletRequest request);

    /**
     * 用户登录
     * @param loginDTO 登录信息 DTO
     * @return 包含 Token 的登录视图对象
     */
    UserLoginVO login(UserLoginDTO loginDTO, HttpServletRequest request);

    /**
     * 获取当前登录用户的个人资料
     * @param userId 当前登录用户的ID
     * @return 用户资料视图对象
     */
    UserProfileVO getUserProfile(Long userId);

    /**
     * 更新用户信息
     * @param userId 当前登录用户的ID
     * @param updateDTO 更新信息 DTO
     */
    void updateUser(Long userId, UserUpdateDTO updateDTO);

    /**
     * 管理员重置用户密码
     * @param resetDTO 重置密码 DTO
     */
    void resetPasswordByAdmin(ResetPasswordDTO resetDTO);

    /**
     * 发送验证码
     * @param sendCodeDTO DTO
     */
    void sendVerificationCode(SendCodeDTO sendCodeDTO);

    /**
     * 通过邮箱验证码登录
     * @param loginDTO DTO
     * @return 登录视图对象
     */
    UserLoginVO loginWithCode(EmailCodeLoginDTO loginDTO, HttpServletRequest request);

    /**
     * 通过邮箱验证码重置密码
     * @param resetDTO DTO
     */
    void resetPasswordWithCode(EmailResetPasswordDTO resetDTO);

    /**
     * 更新收益结算偏好。
     */
    void updateSettlementPreference(Long operatorUserId, Long targetUserId, SettlementCurrency currency, boolean operatorIsAdmin);

    /**
     * 修改支付宝账号与实名（需验证码且一个月仅限一次）。
     */
    void updateAlipayAccount(Long userId, UpdateAlipayAccountDTO request);
}
