package com.slb.mining_backend.modules.withdraw;

import com.slb.mining_backend.common.exception.BizException;
import com.slb.mining_backend.modules.exchange.service.ExchangeRateService;
import com.slb.mining_backend.modules.users.entity.User;
import com.slb.mining_backend.modules.users.mapper.UserMapper;
import com.slb.mining_backend.modules.withdraw.config.WithdrawProperties;
import com.slb.mining_backend.modules.withdraw.dto.WithdrawApplyDto;
import com.slb.mining_backend.modules.withdraw.mapper.WithdrawalMapper;
import com.slb.mining_backend.modules.withdraw.service.WithdrawalService;
import com.slb.mining_backend.modules.xmr.service.f2pool.F2PoolAlertService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WithdrawalServiceTest {

    @Mock
    private WithdrawalMapper withdrawalMapper;
    @Mock
    private UserMapper userMapper;
    @Mock
    private WithdrawProperties withdrawProperties;
    @Mock
    private ExchangeRateService exchangeRateService;
    @Mock
    private F2PoolAlertService f2PoolAlertService;

    @InjectMocks
    private WithdrawalService withdrawalService;

    @Captor
    private ArgumentCaptor<com.slb.mining_backend.modules.withdraw.entity.Withdrawal> withdrawalCaptor;

    @BeforeEach
    void setup() {
        // 1 CAL = 0.001 XMR
        ReflectionTestUtils.setField(withdrawalService, "calXmrRatio", new BigDecimal("0.001"));
        when(withdrawProperties.getMinAmount()).thenReturn(new BigDecimal("3"));
        when(withdrawProperties.getDailyLimitCount()).thenReturn(20);
    }

    @Test
    void apply_amountUnitCal_calBelow3ButCnyAbove3_shouldPassMinAmountAndDeductCal() {
        Long userId = 1L;

        // calAmount = 2.50 CAL
        // xmrEquiv = 2.50 * 0.001 = 0.0025
        // xmrToCny = 2000 => cnyGross = 5.00
        when(exchangeRateService.getLatestRate("XMR/CNY")).thenReturn(new BigDecimal("2000"));

        when(userMapper.lockByIdForUpdate(userId)).thenReturn(userId);
        when(withdrawalMapper.countActiveByUserId(userId)).thenReturn(0L);
        when(withdrawalMapper.countTodayWithdrawalsByUserId(userId)).thenReturn(0L);

        User user = new User();
        user.setId(userId);
        user.setCalBalance(new BigDecimal("10.00000000"));
        when(userMapper.selectById(userId)).thenReturn(Optional.of(user));

        WithdrawApplyDto dto = new WithdrawApplyDto();
        dto.setAmount(new BigDecimal("2.50"));
        dto.setAmountUnit("CAL");
        dto.setAccountType("alipay");
        dto.setAccount("13800000000");
        dto.setCurrency("CNY"); // should be ignored when amountUnit=CAL

        withdrawalService.applyForWithdrawal(userId, dto);

        // 扣减 CAL：2.50
        verify(userMapper, times(1)).updateUserWallet(eq(userId), eq(new BigDecimal("-2.50")));

        // 落库：currency=CAL，amount=等价CNY=5.00，fee=0.15（3%）
        verify(withdrawalMapper).insert(withdrawalCaptor.capture());
        var w = withdrawalCaptor.getValue();
        assertEquals("CAL", w.getCurrency());
        assertEquals(new BigDecimal("5.00"), w.getAmount());
        assertEquals(new BigDecimal("0.15"), w.getFeeAmount());
        assertEquals(new BigDecimal("0.002500000000"), w.getXmrEquivalent());
        assertEquals(new BigDecimal("2000"), w.getRateAtRequest());
    }

    @Test
    void apply_amountUnitCal_cnyGrossBelowMin_shouldRejectWithClearMessage() {
        Long userId = 1L;
        when(exchangeRateService.getLatestRate("XMR/CNY")).thenReturn(new BigDecimal("100")); // too low

        WithdrawApplyDto dto = new WithdrawApplyDto();
        dto.setAmount(new BigDecimal("2.50"));
        dto.setAmountUnit("CAL");
        dto.setAccountType("alipay");
        dto.setAccount("13800000000");

        BizException ex = assertThrows(BizException.class, () -> withdrawalService.applyForWithdrawal(userId, dto));
        assertTrue(ex.getMessage().contains("按等价CNY"));
        verifyNoInteractions(withdrawalMapper);
    }

    @Test
    void apply_amountUnitCal_insufficientCalBalance_shouldReject() {
        Long userId = 1L;
        when(exchangeRateService.getLatestRate("XMR/CNY")).thenReturn(new BigDecimal("2000"));

        when(userMapper.lockByIdForUpdate(userId)).thenReturn(userId);
        when(withdrawalMapper.countActiveByUserId(userId)).thenReturn(0L);
        when(withdrawalMapper.countTodayWithdrawalsByUserId(userId)).thenReturn(0L);

        User user = new User();
        user.setId(userId);
        user.setCalBalance(new BigDecimal("1.00"));
        when(userMapper.selectById(userId)).thenReturn(Optional.of(user));

        WithdrawApplyDto dto = new WithdrawApplyDto();
        dto.setAmount(new BigDecimal("2.50"));
        dto.setAmountUnit("CAL");
        dto.setAccountType("alipay");
        dto.setAccount("13800000000");

        BizException ex = assertThrows(BizException.class, () -> withdrawalService.applyForWithdrawal(userId, dto));
        assertTrue(ex.getMessage().contains("CAL 余额不足"));
        verify(withdrawalMapper, never()).insert(any());
    }
}


