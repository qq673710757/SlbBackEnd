package com.slb.mining_backend.modules.users.service.impl;

import com.slb.mining_backend.modules.users.entity.User;
import com.slb.mining_backend.modules.users.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserDetailsServiceImplTest {

    @Mock
    private UserMapper userMapper;

    @InjectMocks
    private UserDetailsServiceImpl userDetailsService;

    @Test
    void loadUserByUsername_email_shouldQueryEmailFirst() {
        User u = new User();
        u.setId(1L);
        u.setUserName("alice");
        u.setStatus(1);
        u.setEmail("alice@example.com");

        when(userMapper.selectByUserEmail("alice@example.com")).thenReturn(Optional.of(u));

        userDetailsService.loadUserByUsername("alice@example.com");

        verify(userMapper, times(1)).selectByUserEmail("alice@example.com");
        verify(userMapper, never()).selectByUserName("alice@example.com");
    }

    @Test
    void loadUserByUsername_username_shouldQueryUsernameFirst() {
        User u = new User();
        u.setId(2L);
        u.setUserName("bob");
        u.setStatus(1);

        when(userMapper.selectByUserName("bob")).thenReturn(Optional.of(u));

        userDetailsService.loadUserByUsername("bob");

        verify(userMapper, times(1)).selectByUserName("bob");
        verify(userMapper, never()).selectByUserEmail("bob");
    }

    @Test
    void loadUserByUsername_username_shouldFallbackToEmail() {
        when(userMapper.selectByUserName("nameOnly")).thenReturn(Optional.empty());
        when(userMapper.selectByUserEmail("nameOnly")).thenReturn(Optional.empty());

        assertThrows(UsernameNotFoundException.class, () -> userDetailsService.loadUserByUsername("nameOnly"));

        verify(userMapper, times(1)).selectByUserName("nameOnly");
        verify(userMapper, times(1)).selectByUserEmail("nameOnly");
    }

    @Test
    void loadUserByUsername_email_shouldFallbackToUsername() {
        when(userMapper.selectByUserEmail("x@y.com")).thenReturn(Optional.empty());
        when(userMapper.selectByUserName("x@y.com")).thenReturn(Optional.empty());

        assertThrows(UsernameNotFoundException.class, () -> userDetailsService.loadUserByUsername("x@y.com"));

        verify(userMapper, times(1)).selectByUserEmail("x@y.com");
        verify(userMapper, times(1)).selectByUserName("x@y.com");
    }
}


