package com.slb.mining_backend.modules.xmr.service.f2pool;

import com.slb.mining_backend.modules.users.dto.WorkerUserBinding;
import com.slb.mining_backend.modules.users.mapper.UserMapper;
import com.slb.mining_backend.modules.xmr.service.WorkerIdNormalizationHelper;
import com.slb.mining_backend.modules.xmr.service.WorkerWhitelistService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkerOwnershipResolverTest {

    @Test
    void shouldResolveByBaseWorkerId() {
        UserMapper userMapper = Mockito.mock(UserMapper.class);
        WorkerWhitelistService whitelistService = Mockito.mock(WorkerWhitelistService.class);
        WorkerIdNormalizationHelper normalizationHelper = new WorkerIdNormalizationHelper("suanlibao.");
        F2PoolConfigBridge configBridge = Mockito.mock(F2PoolConfigBridge.class);
        when(configBridge.isAllowSyntheticUsrFromRawWorkerId()).thenReturn(false);

        WorkerUserBinding binding = new WorkerUserBinding();
        binding.setUserId(99L);
        binding.setWorkerId("base");
        when(userMapper.selectByWorkerIds(anyList())).thenReturn(List.of(binding));

        WorkerOwnershipResolver resolver = new WorkerOwnershipResolver(userMapper, whitelistService, normalizationHelper, configBridge);
        Map<String, Long> owners = resolver.resolveOwners(List.of("base.rig01"));
        assertThat(owners.get("base.rig01")).isEqualTo(99L);
    }

    @Test
    void shouldResolveSyntheticUsrWhenAllowedAndWhitelisted() {
        UserMapper userMapper = Mockito.mock(UserMapper.class);
        WorkerWhitelistService whitelistService = Mockito.mock(WorkerWhitelistService.class);
        WorkerIdNormalizationHelper normalizationHelper = new WorkerIdNormalizationHelper("suanlibao.");
        F2PoolConfigBridge configBridge = Mockito.mock(F2PoolConfigBridge.class);
        when(configBridge.isAllowSyntheticUsrFromRawWorkerId()).thenReturn(true);
        when(whitelistService.isValid("USR-42")).thenReturn(true);

        WorkerOwnershipResolver resolver = new WorkerOwnershipResolver(userMapper, whitelistService, normalizationHelper, configBridge);
        Long userId = resolver.resolveUserId("USR-42", Map.of());
        assertThat(userId).isEqualTo(42L);
    }
}
