package com.slb.mining_backend.modules.users.service;


import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.slb.mining_backend.common.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class VerificationCodeService {

    // 验证码最大输入次数
    private static final int MAX_ATTEMPTS = 3;
    // 验证码间隔时间
    private static final long COOLDOWN_MINUTES = 1;
    // 验证码过期时间
    private static final long CODE_EXPIRATION_MINUTES = 10;

    // 内部类,用于存储验证码及相关数据
    private static class CodeInfo {
        String code;
        int attempts = 0;

        CodeInfo(String code) {
            this.code = code;
        }
    }

    // 使用 Guava Cache 存储验证码,10分钟后过期
    private final Cache<String, CodeInfo> codeCache = CacheBuilder.newBuilder()
            .expireAfterAccess(CODE_EXPIRATION_MINUTES, TimeUnit.MINUTES)
            .build();

    // 使用 Guava Cache 存储发送冷却时间,一分钟后过期
    private final Cache<String, Long> cooldownCache = CacheBuilder.newBuilder()
            .expireAfterAccess(COOLDOWN_MINUTES, TimeUnit.MINUTES)
            .build();

    /**
     * 生成并返回一个6位数的验证码
     *
     * @param email 邮箱地址，作为缓存的 key
     * @return 生成的验证码
     */
    public String generateAndCacheCode(String email) {
        if (cooldownCache.getIfPresent(email) != null) {
            throw new BizException("请求过于频繁,请稍后再试");
        }

        String code = String.format("%06d", new SecureRandom().nextInt(999999));
        codeCache.put(email, new CodeInfo(code));
        cooldownCache.put(email, System.currentTimeMillis());

        // 日志打印验证码
        log.info("为邮箱: {}生产了验证码: {}", email, code);
        return code;
    }

    /**
     * 校验验证码
     *
     * @param email        邮箱地址
     * @param providedCode 用户提供的验证码
     */
    public void validateCode(String email, String providedCode) {
        CodeInfo codeInfo = codeCache.getIfPresent(email);

        if (codeInfo == null) {
            throw new BizException("验证码已过期或不存在,请重新发送");
        }
        if (codeInfo.attempts >= MAX_ATTEMPTS) {
            codeCache.invalidate(email); // 超过次数,使验证码失效

            throw new BizException("验证码错误次数过多,请重新发送");
        }
        if (!codeInfo.code.equals(providedCode)) {
            codeInfo.attempts++; // 验证失败,+1
            throw new BizException("验证不正确,请重新输入验证码");
        }

        // 验证成功,从缓存中移除,防止重复利用
        codeCache.invalidate(email);
    }

}
