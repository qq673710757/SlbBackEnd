package com.slb.mining_backend.modules.users.controller;

import com.slb.mining_backend.common.api.ApiResponse;
import com.slb.mining_backend.common.security.CustomUserDetails;
import com.slb.mining_backend.modules.users.dto.code.EmailCodeLoginDTO;
import com.slb.mining_backend.modules.users.dto.code.EmailResetPasswordDTO;
import com.slb.mining_backend.modules.users.dto.code.SendCodeDTO;
import com.slb.mining_backend.modules.users.vo.UserLoginVO;
import com.slb.mining_backend.modules.users.vo.UserProfileVO;
import com.slb.mining_backend.modules.users.dto.user.ResetPasswordDTO;
import com.slb.mining_backend.modules.users.dto.user.SettlementPreferenceDTO;
import com.slb.mining_backend.modules.users.dto.user.UpdateAlipayAccountDTO;
import com.slb.mining_backend.modules.users.dto.user.UserRegisterDTO;
import com.slb.mining_backend.modules.users.dto.user.UserUpdateDTO;
import com.slb.mining_backend.modules.users.entity.User;
import com.slb.mining_backend.modules.users.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/user")
@Tag(name = "用户端/用户", description = "提供用户注册、登录、资料管理、结算偏好配置等用户相关接口")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping("/register")
    @Operation(
            summary = "用户注册",
            description = """
                    新用户注册账号，支持设置邮箱、登录密码等信息。注册成功后返回登录态信息，可直接视为已登录状态。
                    
                    示例请求 (cURL):
                    curl -X POST "http://localhost:8080/api/v1/user/register" \
                      -H "Content-Type: application/json" \
                      -d '{
                        "userName": "hyperion",
                        "userPassword": "P@ssw0rd123",
                        "email": "user@example.com",
                        "code": "123456",
                        "regInto": "web"
                      }'
                    
                    示例响应 (JSON):
                    {
                      "code": 0,
                      "message": "ok",
                      "data": {
                        "uid": 10001,
                        "userName": "hyperion",
                        "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
                        "refreshToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
                        "workerId": "worker-1"
                      },
                      "traceId": "b3f7e6c9a1d24c31"
                    }
                    """
    )
    public ApiResponse<UserLoginVO> register(
            @Parameter(description = "注册请求体，包含邮箱、密码等注册信息", required = true)
            @RequestBody @Validated UserRegisterDTO registerDTO,
            @Parameter(description = "HTTP 请求对象，用于记录客户端信息")
            HttpServletRequest request) {
        UserLoginVO result = userService.register(registerDTO, request);
        return ApiResponse.ok(result);
    }


    @GetMapping("/profile")
    @Operation(
            summary = "获取当前用户资料",
            description = """
                    根据当前登录用户的身份，查询个人资料信息。需要在请求头中携带有效的访问令牌（Authorization: Bearer {accessToken}）。
                    
                    示例请求 (cURL):
                    curl -X GET "http://localhost:8080/api/v1/user/profile" \
                      -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
                    
                    示例响应 (JSON):
                    {
                      "code": 0,
                      "message": "ok",
                      "data": {
                        "uid": 10001,
                        "userName": "hyperion",
                        "alipayAccount": "pay@example.com",
                        "alipayName": "张三",
                        "inviteCode": "INVITE123",
                        "calBalance": 123.45,
                        "cashBalance": 678.90,
                        "settlementCurrency": "CAL",
                        "deviceCount": 5,
                        "onlineDeviceCount": 3
                        // ... 其他字段略
                      },
                      "traceId": "b3f7e6c9a1d24c31"
                    }
                    """
    )
    public ApiResponse<UserProfileVO> getProfile() {
        User currentUser = getCurrentUser();
        UserProfileVO userProfile = userService.getUserProfile(currentUser.getId());
        return ApiResponse.ok(userProfile);
    }

    @PutMapping("/update")
    @Operation(
            summary = "更新用户资料",
            description = """
                    修改当前登录用户的基础资料信息，例如手机号或邮箱等（支付宝账号需通过专门接口修改）。
                    
                    示例请求 (cURL):
                    curl -X PUT "http://localhost:8080/api/v1/user/update" \
                      -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..." \
                      -H "Content-Type: application/json" \
                      -d '{
                        "phone": "13900000000",
                        "email": "new@example.com"
                      }'
                    
                    示例响应 (JSON):
                    {
                      "code": 0,
                      "message": "ok",
                      "data": null,
                      "traceId": "b3f7e6c9a1d24c31"
                    }
                    """
    )
    public ApiResponse<Void> updateProfile(
            @Parameter(description = "需要更新的资料字段", required = true)
            @RequestBody @Validated UserUpdateDTO updateDTO) {
        User currentUser = getCurrentUser();
        userService.updateUser(currentUser.getId(), updateDTO);
        return ApiResponse.ok();
    }

    @PutMapping("/alipay-account")
    @Operation(
            summary = "修改支付宝账号与实名（需验证码）",
            description = """
                    使用邮箱验证码校验后，更新绑定的支付宝账号与实名信息。
                    规则：
                    1. 必须提供当前账号绑定邮箱接收到的验证码；
                    2. 每个账号 30 天内仅可修改一次支付宝账号/姓名；
                    3. 该接口仅支持修改支付宝账号及姓名，其余资料请使用 /update。
                    
                    示例请求 (cURL):
                    curl -X PUT "http://localhost:8080/api/v1/user/alipay-account" \
                      -H "Authorization: Bearer ey..." \
                      -H "Content-Type: application/json" \
                      -d '{
                        "alipayAccount": "pay@example.com",
                        "alipayName": "张三",
                        "email": "user@example.com",
                        "code": "123456"
                      }'
                    """
    )
    public ApiResponse<Void> updateAlipayAccount(
            @Parameter(description = "支付宝账号更新请求体", required = true)
            @RequestBody @Validated UpdateAlipayAccountDTO request) {
        User currentUser = getCurrentUser();
        userService.updateAlipayAccount(currentUser.getId(), request);
        return ApiResponse.ok();
    }

    @PutMapping("/settlement-preference")
    @Operation(
            summary = "配置结算偏好",
            description = """
                    设置收益结算币种等偏好。普通用户仅能修改自己的设置，管理员可为其他用户配置结算偏好。
                    
                    示例请求 (cURL):
                    curl -X PUT "http://localhost:8080/api/v1/user/settlement-preference" \
                      -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..." \
                      -H "Content-Type: application/json" \
                      -d '{
                        "settlementCurrency": "CAL"
                      }'
                    
                    示例响应 (JSON):
                    {
                      "code": 0,
                      "message": "ok",
                      "data": null,
                      "traceId": "b3f7e6c9a1d24c31"
                    }
                    """
    )
    public ApiResponse<Void> updateSettlementPreference(
            @Parameter(description = "结算偏好配置请求体", required = true)
            @RequestBody @Validated SettlementPreferenceDTO request) {
        User currentUser = getCurrentUser();
        boolean isAdmin = "ADMIN".equalsIgnoreCase(currentUser.getRole());
        Long targetUserId = request.getTargetUserId() != null && isAdmin
                ? request.getTargetUserId()
                : currentUser.getId();
        userService.updateSettlementPreference(currentUser.getId(), targetUserId, request.getSettlementCurrency(), isAdmin);
        return ApiResponse.ok();
    }

    @PostMapping("/reset-password")
    @Operation(
            summary = "管理员重置用户密码",
            description = """
                    仅管理员可调用，用于重置指定用户的登录密码。
                    
                    示例请求 (cURL):
                    curl -X POST "http://localhost:8080/api/v1/user/reset-password" \
                      -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..." \
                      -H "Content-Type: application/json" \
                      -d '{
                        "userName": "targetUser",
                        "adminCode": "ADMIN-SECRET",
                        "password": "N3wP@ssw0rd"
                      }'
                    
                    示例响应 (JSON):
                    {
                      "code": 0,
                      "message": "ok",
                      "data": null,
                      "traceId": "b3f7e6c9a1d24c31"
                    }
                    """
    )
    public ApiResponse<Void> resetPassword(
            @Parameter(description = "重置密码请求体，包含目标用户和新密码信息", required = true)
            @RequestBody @Validated ResetPasswordDTO resetDTO) {
        userService.resetPasswordByAdmin(resetDTO);
        return ApiResponse.ok();
    }

    /**
     * 发送邮箱验证码接口
     */
    @PostMapping("/send-code")
    @Operation(
            summary = "发送邮箱验证码",
            description = """
                    向用户邮箱发送验证码，用于登录、注册或重置密码。一个邮箱在一定时间内发送次数有限制。
                    
                    示例请求 (cURL):
                    curl -X POST "http://localhost:8080/api/v1/user/send-code" \
                      -H "Content-Type: application/json" \
                      -d '{
                        "email": "user@example.com",
                        "type": "LOGIN"
                      }'
                    
                    示例响应 (JSON):
                    {
                      "code": 0,
                      "message": "ok",
                      "data": null,
                      "traceId": "b3f7e6c9a1d24c31"
                    }
                    """
    )
    public ApiResponse<Void> sendCode(
            @Parameter(description = "验证码发送请求体，包含邮箱和业务类型（登录/重置密码等）", required = true)
            @RequestBody @Validated SendCodeDTO sendCodeDTO) {
        userService.sendVerificationCode(sendCodeDTO);
        return ApiResponse.ok();
    }

    /**
     * 邮箱验证码登录接口
     */
    @PostMapping("/login-by-code")
    @Operation(
            summary = "邮箱验证码登录",
            description = """
                    用户通过邮箱和收到的验证码进行无密码登录。
                    
                    示例请求 (cURL):
                    curl -X POST "http://localhost:8080/api/v1/user/login-by-code" \
                      -H "Content-Type: application/json" \
                      -d '{
                        "email": "user@example.com",
                        "code": "123456"
                      }'
                    
                    示例响应 (JSON):
                    {
                      "code": 0,
                      "message": "ok",
                      "data": {
                        "uid": 10001,
                        "userName": "hyperion",
                        "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
                        "refreshToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
                        "workerId": "worker-1"
                      },
                      "traceId": "b3f7e6c9a1d24c31"
                    }
                    """
    )
    public ApiResponse<UserLoginVO> loginByCode(
            @Parameter(description = "验证码登录请求体，包含邮箱和验证码", required = true)
            @RequestBody @Validated EmailCodeLoginDTO loginDTO,
            @Parameter(description = "HTTP 请求对象，用于记录客户端信息")
            HttpServletRequest request) {
        UserLoginVO result = userService.loginWithCode(loginDTO, request);
        return ApiResponse.ok(result);
    }

    /**
     * 邮箱验证码重置密码接口
     */
    @PostMapping("/reset-password-by-code")
    @Operation(
            summary = "邮箱验证码重置密码",
            description = """
                    用户通过邮箱和验证码验证身份后，重置登录密码。
                    
                    示例请求 (cURL):
                    curl -X POST "http://localhost:8080/api/v1/user/reset-password-by-code" \
                      -H "Content-Type: application/json" \
                      -d '{
                        "email": "user@example.com",
                        "code": "123456",
                        "newPassword": "N3wP@ssw0rd"
                      }'
                    
                    示例响应 (JSON):
                    {
                      "code": 0,
                      "message": "ok",
                      "data": null,
                      "traceId": "b3f7e6c9a1d24c31"
                    }
                    """
    )
    public ApiResponse<Void> resetPasswordByCode(
            @Parameter(description = "验证码重置密码请求体，包含邮箱、验证码及新密码", required = true)
            @RequestBody @Validated EmailResetPasswordDTO resetDTO) {
        userService.resetPasswordWithCode(resetDTO);
        return ApiResponse.ok();
    }

    /**
     * 从 SecurityContextHolder 获取当前登录的用户信息
     * 返回的是 User 实体，在 UserDetailsServiceImpl 中做了相应处理
     * @return User 实体
     */
    private User getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || "anonymousUser".equals(authentication.getPrincipal())) {
            throw new com.slb.mining_backend.common.exception.BizException(401, "用户未登录");
        }

        // 1.从认证信息获取Principal对象
        Object principal = authentication.getPrincipal();

        // 2.检查是否是需要的自定义UserDetails对象
        if (principal instanceof CustomUserDetails){
            // 如果是，返回UserDetails对象中的user
            return ((CustomUserDetails) principal).getUser();
        } else {
            // 如果不是，抛出
            throw new IllegalStateException("安全上下文中的 Principal 类型不正确,需要的是UserDetails,但实际的是: " + principal.getClass().getName());
        }

    }

}
