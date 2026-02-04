package com.slb.mining_backend.common.security; // 建议为安全相关的类创建一个新包

import com.slb.mining_backend.modules.users.entity.User;
import io.netty.util.internal.StringUtil;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

public class CustomUserDetails implements UserDetails {

    // 内部包装我们自己的 User 实体
    private final User user;

    public CustomUserDetails(User user) {
        this.user = user;
    }

    // 返回我们自己 User 实体的方法
    public User getUser() {
        return user;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        // 权限列表
        if (StringUtils.hasText(user.getRole())) {
            return List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole()));
        }
        return Collections.emptyList();
    }

    @Override
    public String getPassword() {
        return user.getPasswordHash();
    }

    @Override
    public String getUsername() {
        return user.getUserName();
    }

    // 以下是账户状态的方法，可以根据 User 实体的 status 字段来判断
    @Override
    public boolean isAccountNonExpired() {
        return true; // 账户未过期
    }

    @Override
    public boolean isAccountNonLocked() {
        return user.getStatus() == 1; // 1=正常, 0=锁定
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true; // 凭证未过期
    }

    @Override
    public boolean isEnabled() {
        return user.getStatus() == 1; // 1=启用, 0=禁用
    }
}
