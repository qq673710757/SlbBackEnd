package com.slb.mining_backend.modules.users.service.impl;

import com.slb.mining_backend.common.exception.BizException;
import com.slb.mining_backend.common.security.CustomUserDetails;
import com.slb.mining_backend.modules.users.entity.User;
import com.slb.mining_backend.modules.users.mapper.UserMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class UserDetailsServiceImpl implements UserDetailsService {

    @Autowired
    private UserMapper userMapper;

    @Override
    public UserDetails loadUserByUsername(String identifier) throws UsernameNotFoundException {
        // 兼容策略：identifier 可能是邮箱或用户名。
        // 为避免 “username 恰好等于他人 email” 导致串号，这里按格式判断优先级：
        // - 看起来像邮箱 => 优先按 email 查，再按 user_name 兜底
        // - 否则 => 优先按 user_name 查，再按 email 兜底
        boolean looksLikeEmail = looksLikeEmail(identifier);
        User user = looksLikeEmail
                ? userMapper.selectByUserEmail(identifier)
                    .orElseGet(() -> userMapper.selectByUserName(identifier)
                            .orElseThrow(() -> new UsernameNotFoundException("账号/邮箱: " + identifier + " 不存在")))
                : userMapper.selectByUserName(identifier)
                    .orElseGet(() -> userMapper.selectByUserEmail(identifier)
                            .orElseThrow(() -> new UsernameNotFoundException("账号/邮箱: " + identifier + " 不存在")));

        if (user.getStatus() == 0) {
            throw new BizException("用户已被封禁");
        }

        // 返回包含完整 User 实体的自定义 UserDetails，便于后续读取邮箱等信息
        return new CustomUserDetails(user);
    }

    private boolean looksLikeEmail(String identifier) {
        if (!StringUtils.hasText(identifier)) return false;
        String s = identifier.trim();
        // 足够的启发式：包含 @ 且前后都有字符
        int at = s.indexOf('@');
        return at > 0 && at < s.length() - 1;
    }
}
