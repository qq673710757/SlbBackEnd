package com.slb.mining_backend.modules.users.service.impl;

import cn.hutool.core.lang.UUID;
import com.slb.mining_backend.common.exception.BizException;
import com.slb.mining_backend.common.security.CustomUserDetails;
import com.slb.mining_backend.common.util.JwtUtil;
import com.slb.mining_backend.modules.exchange.service.ExchangeRateService;
import com.slb.mining_backend.modules.device.service.DeviceService;
import com.slb.mining_backend.modules.users.dto.code.EmailCodeLoginDTO;
import com.slb.mining_backend.modules.users.dto.code.EmailResetPasswordDTO;
import com.slb.mining_backend.modules.users.dto.code.SendCodeDTO;
import com.slb.mining_backend.modules.users.vo.UserLoginVO;
import com.slb.mining_backend.modules.users.vo.UserProfileVO;
import com.slb.mining_backend.modules.users.dto.user.ResetPasswordDTO;
import com.slb.mining_backend.modules.users.dto.user.UpdateAlipayAccountDTO;
import com.slb.mining_backend.modules.users.dto.user.UserLoginDTO;
import com.slb.mining_backend.modules.users.dto.user.UserRegisterDTO;
import com.slb.mining_backend.modules.users.dto.user.UserUpdateDTO;
import com.slb.mining_backend.modules.users.entity.User;
import com.slb.mining_backend.modules.users.mapper.UserMapper;
import com.slb.mining_backend.modules.users.service.EmailService;
import com.slb.mining_backend.modules.users.service.UserService;
import com.slb.mining_backend.modules.users.service.VerificationCodeService;
import com.slb.mining_backend.modules.users.enums.SettlementCurrency;
import com.slb.mining_backend.modules.xmr.config.XmrWalletProperties;
import com.slb.mining_backend.modules.xmr.entity.XmrPoolStats;
import com.slb.mining_backend.modules.xmr.entity.XmrUserAddress;
import com.slb.mining_backend.modules.xmr.mapper.XmrPoolStatsMapper;
import com.slb.mining_backend.modules.xmr.mapper.XmrUserAddressMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.Optional;

@Service
public class UserServiceImpl implements UserService {

    private static final long ALIPAY_UPDATE_COOLDOWN_DAYS = 30L;

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final AuthenticationManager authenticationManager;
    private final VerificationCodeService verificationCodeService;
    private final DeviceService deviceService;
    private final EmailService emailService;

    private final XmrUserAddressMapper xmrUserAddressMapper;
    private final XmrPoolStatsMapper xmrPoolStatsMapper;
    private final ExchangeRateService exchangeRateService;
    private final XmrWalletProperties xmrWalletProperties;

    @Value("${admin.reset-password-code}")
    private String adminResetCode;

    public UserServiceImpl(
            UserMapper userMapper,
            PasswordEncoder passwordEncoder,
            JwtUtil jwtUtil,
            AuthenticationManager authenticationManager,
            VerificationCodeService verificationCodeService,
            DeviceService deviceService,
            EmailService emailService,
            XmrUserAddressMapper xmrUserAddressMapper,
            XmrPoolStatsMapper xmrPoolStatsMapper,
            ExchangeRateService exchangeRateService,
            XmrWalletProperties xmrWalletProperties
    ) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
        this.authenticationManager = authenticationManager;
        this.verificationCodeService = verificationCodeService;
        this.deviceService = deviceService;
        this.emailService = emailService;
        this.xmrUserAddressMapper = xmrUserAddressMapper;
        this.xmrPoolStatsMapper = xmrPoolStatsMapper;
        this.exchangeRateService = exchangeRateService;
        this.xmrWalletProperties = xmrWalletProperties;
    }

    @Override
    @Transactional
    public UserLoginVO register(UserRegisterDTO registerDTO, HttpServletRequest request) {
        // 1) 校验邮箱验证码
        verificationCodeService.validateCode(registerDTO.getEmail(), registerDTO.getCode());

        // 1.1) 解析并校验邀请码（若填写）
        Long inviterId = null;
        String rawInviteCode = registerDTO.getInviteCode();
        if (StringUtils.hasText(rawInviteCode)) {
            String normalizedInviteCode = rawInviteCode.trim().toUpperCase();
            inviterId = userMapper.selectByInviteCode(normalizedInviteCode)
                    .map(User::getId)
                    .orElseThrow(() -> new BizException("邀请码不存在或已失效"));
        }

        // 2) 检查用户名/邮箱是否被占用
        userMapper.selectByUserName(registerDTO.getUserName()).ifPresent(u -> {
            throw new BizException("用户名已存在");
        });
        userMapper.selectByUserEmail(registerDTO.getEmail()).ifPresent(u -> {
            throw new BizException("该邮箱已被注册");
        });

        // 3) 创建用户
        User user = new User();
        user.setUserName(registerDTO.getUserName());
        user.setPasswordHash(passwordEncoder.encode(registerDTO.getUserPassword()));
        user.setSalt(""); // 兼容历史结构
        user.setAlipayAccount(registerDTO.getAlipayAccount());
        user.setAlipayName(registerDTO.getAlipayName());
        user.setPhone(registerDTO.getPhone());
        user.setEmail(registerDTO.getEmail());
        user.setRegInto(registerDTO.getRegInto());
        user.setInviteCode(generateUniqueInviteCode());
        user.setInviterId(inviterId);

        // 为新用户生成唯一的 workerId（用于矿工区分）
        user.setWorkerId(UUID.randomUUID().toString(true).substring(0, 12));

        // 初始化 XMR 相关金额
        user.setXmrBalance(BigDecimal.ZERO);
        user.setFrozenXmr(BigDecimal.ZERO);
        user.setTotalEarnedXmr(BigDecimal.ZERO);
        user.setStatusFrozen(0);

        // 4) 写库
        userMapper.insert(user);

        // 5) 给用户创建一个"子地址"（这里示例使用假地址，真实应调用 Monero RPC）
        try {
            XmrUserAddress addr = new XmrUserAddress();
            addr.setUserId(user.getId());
            addr.setAccountIndex(0);
            addr.setSubaddressIndex(0);
            // 使用配置的主钱包地址（支持单地址多用户模式）
            String mainAddress = normalizeMasterAddress(xmrWalletProperties.getMasterAddress());
            if (!StringUtils.hasText(mainAddress)) {
                throw new BizException("主钱包地址未配置");
            }
            addr.setSubaddress(mainAddress);
            addr.setLabel("UID:" + user.getWorkerId());
            addr.setIsActive(true);
            addr.setCreatedTime(LocalDateTime.now());
            xmrUserAddressMapper.insert(addr);

            // 初始化矿池统计
            XmrPoolStats stats = new XmrPoolStats();
            stats.setUserId(user.getId());
            stats.setSubaddress(mainAddress);
            stats.setLastHashrate(0L);
            stats.setLastReportedShares(0L);
            stats.setUnpaidXmr(BigDecimal.ZERO);
            stats.setPaidXmrTotal(BigDecimal.ZERO);
            stats.setSource("kryptex");
            stats.setWorkerId(user.getWorkerId());
            xmrPoolStatsMapper.insert(stats);
        } catch (Exception e) {
            throw new BizException("创建用户子地址失败: " + e.getMessage());
        }

        // 6) 注册成功后自动登录（email + password）
        return login(new UserLoginDTO(registerDTO.getEmail(), registerDTO.getUserPassword()), request);
    }

    @Override
    public UserLoginVO login(UserLoginDTO loginDTO, HttpServletRequest request) {
        // 1) 认证（principal 使用邮箱）
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(loginDTO.getEmail(), loginDTO.getUserPassword())
        );

        // 2) 取用户
        CustomUserDetails userDetails = (CustomUserDetails) authentication.getPrincipal();
        User user = userDetails.getUser();
        if (user == null) {
            throw new BizException("登录失败：无法在认证信息中找到用户");
        }

        // 3) 设备指纹
        String ipAddress = request.getRemoteAddr();
        String userAgent = request.getHeader("User-Agent");
        String fingerprint = fingerprintOf(ipAddress, userAgent);

        // 4) 生成 AccessToken（新版：带指纹与可选 email）
        String accessToken = jwtUtil.generateAccessToken(userDetails, fingerprint, user.getEmail());
        String refreshToken = jwtUtil.generateRefreshToken(userDetails, user.getEmail());

        return new UserLoginVO(user.getId(), user.getUserName(), accessToken, refreshToken, user.getWorkerId());
    }

    @Override
    public UserProfileVO getUserProfile(Long userId) {
        User user = userMapper.selectById(userId)
                .orElseThrow(() -> new BizException("用户不存在"));

        long deviceCount = deviceService.getTotalDeviceCount(userId);
        long onlineDeviceCount = deviceService.getOnlineTotalDeviceCount(userId);

        BigDecimal balanceXmr = safe(user.getXmrBalance());
        BigDecimal frozenXmr = safe(user.getFrozenXmr());

        Optional<XmrPoolStats> statsOptional = xmrPoolStatsMapper.selectByUserId(userId);
        BigDecimal unpaidXmr = safe(statsOptional.map(XmrPoolStats::getUnpaidXmr).orElse(null));
        BigDecimal paidXmrTotal = safe(statsOptional.map(XmrPoolStats::getPaidXmrTotal).orElse(null));

        // totalEarnedXmr：优先使用矿池统计（paid + unpaid）作为"累计产出"口径；
        // users.total_earned_xmr 当前代码路径中几乎不更新，容易长期为 0，导致 profile 展示异常。
        BigDecimal totalEarnedXmr = paidXmrTotal.add(unpaidXmr);

        BigDecimal xmrToCny = exchangeRateService.getXmrToCnyRate();

        BigDecimal xmrBalanceCny = (xmrToCny != null && xmrToCny.compareTo(BigDecimal.ZERO) > 0)
                ? balanceXmr.multiply(xmrToCny).setScale(4, RoundingMode.HALF_UP)
                : null;

        // totalEarnedCny 语义：totalEarnedXmr 的 CNY 估值（不混入 CAL 口径的 totalEarnings，避免语义混淆/双计）
        BigDecimal totalEarnedCny = (xmrToCny != null && xmrToCny.compareTo(BigDecimal.ZERO) > 0)
                ? totalEarnedXmr.multiply(xmrToCny).setScale(4, RoundingMode.HALF_UP)
                : BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        Long poolHashrate = statsOptional.map(XmrPoolStats::getLastHashrate).orElse(0L);
        Long poolShares = statsOptional.map(XmrPoolStats::getLastReportedShares).orElse(0L);
        java.time.LocalDateTime poolLastUpdate = statsOptional.map(XmrPoolStats::getLastUpdateTime).orElse(null);

        return UserProfileVO.builder()
                .uid(user.getId())
                .userName(user.getUserName())
                .alipayAccount(user.getAlipayAccount())
                .alipayName(user.getAlipayName())
                .reInto(user.getRegInto())
                .createTime(user.getCreateTime())
                .inviteCode(user.getInviteCode())
                .calBalance(user.getCalBalance())
                .cashBalance(user.getCashBalance())
                .totalEarnings(user.getTotalEarnings())
                .totalWithdrawn(user.getTotalWithdrawn())
                .settlementCurrency(user.getSettlementCurrency())
                .deviceCount((int) deviceCount)
                .onlineDeviceCount((int) onlineDeviceCount)
                .workerId(user.getWorkerId())
                .xmrBalance(balanceXmr)
                .frozenXmr(frozenXmr)
                .totalEarnedXmr(totalEarnedXmr)
                .xmrBalanceCny(xmrBalanceCny)
                .totalEarnedCny(totalEarnedCny)
                .unpaidXmr(unpaidXmr)
                .paidXmrTotal(paidXmrTotal)
                .poolHashrate(poolHashrate)
                .poolShares(poolShares)
                .poolLastUpdateTime(poolLastUpdate)
                .statusFrozen(user.getStatusFrozen())
                .build();
    }

    @Override
    @Transactional
    public void updateUser(Long userId, UserUpdateDTO updateDTO) {
        User user = userMapper.selectById(userId)
                .orElseThrow(() -> new BizException("用户不存在"));

        boolean isUpdated = false;

        // 修改密码
        if (StringUtils.hasText(updateDTO.getNewPassword())) {
            if (!StringUtils.hasText(updateDTO.getOldPassword())) {
                throw new BizException("请先提供旧密码再修改新密码");
            }
            if (!passwordEncoder.matches(updateDTO.getOldPassword(), user.getPasswordHash())) {
                throw new BizException("旧密码不正确");
            }
            user.setPasswordHash(passwordEncoder.encode(updateDTO.getNewPassword()));
            isUpdated = true;
        }

        // 其他可修改字段（支付宝账号需走专用接口）
        if (StringUtils.hasText(updateDTO.getPhone())) {
            user.setPhone(updateDTO.getPhone());
            isUpdated = true;
        }
        if (StringUtils.hasText(updateDTO.getEmail())) {
            user.setEmail(updateDTO.getEmail());
            isUpdated = true;
        }

        if (isUpdated) {
            userMapper.update(user);
        }
    }

    @Override
    @Transactional
    public void resetPasswordByAdmin(ResetPasswordDTO resetDTO) {
        if (!adminResetCode.equals(resetDTO.getAdminCode())) {
            throw new BizException("管理员重置码无效");
        }
        User user = userMapper.selectByUserName(resetDTO.getUserName())
                .orElseThrow(() -> new BizException("用户不存在"));
        user.setPasswordHash(passwordEncoder.encode(resetDTO.getPassword()));
        userMapper.update(user);
    }

    @Override
    public void sendVerificationCode(SendCodeDTO sendCodeDTO) {
        String email = sendCodeDTO.getEmail();
        String type = sendCodeDTO.getType();

        if ("REGISTER".equals(type)) {
            userMapper.selectByUserEmail(email).ifPresent(u -> {
                throw new BizException("该邮箱已被注册");
            });
        } else if ("LOGIN".equals(type) || "RESET_PASSWORD".equals(type)) {
            userMapper.selectByUserEmail(email)
                    .orElseThrow(() -> new BizException("该邮箱尚未注册"));
        } else if ("ALIPAY_UPDATE".equals(type)) {
            userMapper.selectByUserEmail(email)
                    .orElseThrow(() -> new BizException("该邮箱尚未注册"));
        } else {
            throw new BizException("不支持的验证码类型");
        }

        String code = verificationCodeService.generateAndCacheCode(email);
        String subject = "【算力宝】您的验证码";
        
        // 根据不同类型定制邮件内容
        String actionText;
        switch (type) {
            case "REGISTER":
                actionText = "注册账号";
                break;
            case "LOGIN":
                actionText = "登录账号";
                break;
            case "RESET_PASSWORD":
                actionText = "重置密码";
                break;
            case "ALIPAY_UPDATE":
                actionText = "修改支付宝账号";
                break;
            default:
                actionText = "验证身份";
        }
        
        String text = String.format(
            "尊敬的用户，您好！\n\n" +
            "您正在进行【%s】操作，验证码为：\n\n" +
            "    %s\n\n" +
            "验证码有效期为 10 分钟，请尽快完成验证。\n\n" +
            "温馨提示：\n" +
            "• 请勿将验证码透露给他人\n" +
            "• 如非本人操作，请忽略此邮件\n" +
            "• 如有疑问，请联系客服\n\n" +
            "——————————————————————\n" +
            "算力宝团队\n" +
            "让算力创造价值",
            actionText, code
        );
        
        emailService.sendEmail(email, subject, text);
    }

    @Override
    public UserLoginVO loginWithCode(EmailCodeLoginDTO loginDTO, HttpServletRequest request) {
        // 1) 校验验证码
        verificationCodeService.validateCode(loginDTO.getEmail(), loginDTO.getCode());

        // 2) 查询用户
        User user = userMapper.selectByUserEmail(loginDTO.getEmail())
                .orElseThrow(() -> new BizException("该邮箱尚未注册"));

        // 3) 构造 UserDetails
        CustomUserDetails customUserDetails = new CustomUserDetails(user);

        // 4) 指纹
        String ipAddr = request.getRemoteAddr();
        String userAgent = request.getHeader("User-Agent");
        String fingerprint = fingerprintOf(ipAddr, userAgent);

        // 5) 生成 AccessToken
        String accessToken = jwtUtil.generateAccessToken(customUserDetails, fingerprint, user.getEmail());
        String refreshToken = jwtUtil.generateRefreshToken(customUserDetails, user.getEmail());

        return new UserLoginVO(user.getId(), user.getUserName(), accessToken, refreshToken, user.getWorkerId());
    }

    @Override
    @Transactional
    public void resetPasswordWithCode(EmailResetPasswordDTO resetDTO) {
        verificationCodeService.validateCode(resetDTO.getEmail(), resetDTO.getCode());

        User user = userMapper.selectByUserEmail(resetDTO.getEmail())
                .orElseThrow(() -> new BizException("该邮箱尚未注册"));

        user.setPasswordHash(passwordEncoder.encode(resetDTO.getNewPassword()));
        userMapper.update(user);
    }

    @Override
    public void updateSettlementPreference(Long operatorUserId,
                                           Long targetUserId,
                                           SettlementCurrency currency,
                                           boolean operatorIsAdmin) {
        if (currency == null) {
            throw new BizException("结算偏好不能为空");
        }
        if (!operatorIsAdmin && !operatorUserId.equals(targetUserId)) {
            throw new BizException(403, "无权修改其他用户的结算偏好");
        }
        userMapper.selectById(targetUserId)
                .orElseThrow(() -> new BizException("用户不存在"));
        int affected = userMapper.updateSettlementCurrency(targetUserId, currency.name());
        if (affected <= 0) {
            throw new BizException("更新结算偏好失败，请稍后重试");
        }
    }

    @Override
    @Transactional
    public void updateAlipayAccount(Long userId, UpdateAlipayAccountDTO request) {
        User user = userMapper.selectById(userId)
                .orElseThrow(() -> new BizException("用户不存在"));

        if (!StringUtils.hasText(user.getEmail()) ||
                !user.getEmail().equalsIgnoreCase(request.getEmail())) {
            throw new BizException("邮箱不匹配，无法修改支付宝账号");
        }

        verificationCodeService.validateCode(request.getEmail(), request.getCode());

        LocalDateTime lastUpdate = user.getAlipayAccountUpdatedAt();
        if (lastUpdate != null &&
                lastUpdate.isAfter(LocalDateTime.now().minusDays(ALIPAY_UPDATE_COOLDOWN_DAYS))) {
            throw new BizException("一个月内仅可修改一次支付宝账号/姓名");
        }

        user.setAlipayAccount(request.getAlipayAccount());
        user.setAlipayName(request.getAlipayName());
        user.setAlipayAccountUpdatedAt(LocalDateTime.now());
        userMapper.update(user);
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String normalizeMasterAddress(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String normalized = raw.trim();
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    /* -------------------- 内部小工具（与 AuthService 一致） -------------------- */

    private static String fingerprintOf(String ip, String ua) {
        String raw = (ip == null ? "" : ip) + "|" + (ua == null ? "" : ua);
        return md5Hex(raw);
    }

    private static String md5Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String generateUniqueInviteCode() {
        for (int i = 0; i < 10; i++) {
            String code = UUID.randomUUID().toString(true).substring(0, 8).toUpperCase();
            if (userMapper.selectByInviteCode(code).isEmpty()) {
                return code;
            }
        }
        throw new BizException("邀请码生成失败，请稍后重试");
    }
}
