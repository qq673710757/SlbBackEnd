package com.slb.mining_backend.modules.asset.service;

import com.slb.mining_backend.modules.asset.entity.AssetLedger;
import com.slb.mining_backend.modules.asset.mapper.AssetLedgerMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Service
public class AssetLedgerService {

    private final AssetLedgerMapper assetLedgerMapper;

    public AssetLedgerService(AssetLedgerMapper assetLedgerMapper) {
        this.assetLedgerMapper = assetLedgerMapper;
    }

    /**
     * 记录矿池结算流水。
     */
    @Transactional
    public void recordMiningPayout(Long userId,
                                   String currency,
                                   BigDecimal amountXmr,
                                   BigDecimal amountCal,
                                   BigDecimal amountCny,
                                   String txHash,
                                   String remark) {
        recordMiningPayout(userId, currency, amountXmr, amountCal, amountCny, null, txHash, remark, null);
    }

    /**
     * 记录矿池结算流水（带 refId 用于链路追溯）。
     *
     * @param refId 关联业务主键（建议：挖矿结算关联 earnings_history.id；邀请佣金关联 INVITE 类型 earnings_history.id）
     */
    @Transactional
    public void recordMiningPayout(Long userId,
                                   String currency,
                                   BigDecimal amountXmr,
                                   BigDecimal amountCal,
                                   BigDecimal amountCny,
                                   Long refId,
                                   String txHash,
                                   String remark) {
        recordMiningPayout(userId, currency, amountXmr, amountCal, amountCny, refId, txHash, remark, null);
    }

    /**
     * 记录矿池结算流水（带 refId + eventTime 用于链路追溯与统一时间口径）。
     *
     * @param refId     关联业务主键（建议：挖矿结算关联 earnings_history.id；邀请佣金关联 INVITE 类型 earnings_history.id）
     * @param eventTime 业务发生时间（建议：写入 xmr_wallet_incoming.ts）
     */
    @Transactional
    public void recordMiningPayout(Long userId,
                                   String currency,
                                   BigDecimal amountXmr,
                                   BigDecimal amountCal,
                                   BigDecimal amountCny,
                                   Long refId,
                                   String txHash,
                                   String remark,
                                   LocalDateTime eventTime) {
        AssetLedger ledger = new AssetLedger();
        ledger.setUserId(userId);
        ledger.setCurrency(currency);
        ledger.setAmountXmr(amountXmr);
        ledger.setAmountCal(amountCal);
        ledger.setAmountCny(amountCny);
        ledger.setRefType("mining_payout");
        ledger.setRefId(refId);
        ledger.setTxHash(txHash);
        ledger.setRemark(remark);
        ledger.setEventTime(eventTime);
        // 幂等写入：依赖数据库唯一键避免重复入账（重复时返回 0）
        assetLedgerMapper.insertIgnore(ledger);
    }

    /**
     * 记录提现流水。
     *
     * @param userId       用户 ID
     * @param currency     提现所消耗的资产类型：XMR / CNY / CAL / USDT
     * @param amountXmr    对应的 XMR 数量（若适用，为负值表示扣减）
     * @param amountCal    对应的 CAL 数量（若适用，为负值表示扣减）
     * @param amountCny    折算的 CNY 金额（为负值表示用户资产流出）
     * @param withdrawalId 关联的提现记录 ID
     */
    @Transactional
    public void recordWithdrawal(Long userId,
                                 String currency,
                                 BigDecimal amountXmr,
                                 BigDecimal amountCal,
                                 BigDecimal amountCny,
                                 Long withdrawalId) {
        AssetLedger ledger = new AssetLedger();
        ledger.setUserId(userId);
        ledger.setCurrency(currency);
        ledger.setAmountXmr(amountXmr);
        ledger.setAmountCal(amountCal);
        ledger.setAmountCny(amountCny);
        ledger.setRefType("withdrawal");
        ledger.setRefId(withdrawalId);
        ledger.setTxHash(null);
        ledger.setRemark("用户提现");
        ledger.setEventTime(LocalDateTime.now());
        assetLedgerMapper.insert(ledger);
    }

    /**
     * 统计某用户在指定 refType 下的 CAL 额度使用情况（用于折扣封顶等）。
     */
    public BigDecimal sumAmountCalByRefTypeAndRemarkPrefixBetween(Long userId,
                                                                  String refType,
                                                                  String remarkPrefix,
                                                                  LocalDateTime start,
                                                                  LocalDateTime end) {
        return assetLedgerMapper.sumAmountCalByUserIdAndRefTypeAndRemarkPrefixBetween(userId, refType, remarkPrefix, start, end);
    }

    /**
     * 是否存在指定 refType 的记录（用于激活标记等）。
     */
    public boolean hasRefTypeWithRemarkPrefix(Long userId, String refType, String remarkPrefix) {
        return assetLedgerMapper.countByUserIdAndRefTypeAndRemarkPrefix(userId, refType, remarkPrefix) > 0;
    }

    /**
     * 记录“被邀请者新手折扣”使用情况（仅用于统计与审计，不直接影响余额）。
     */
    @Transactional
    public void recordInviteeDiscount(Long userId,
                                      BigDecimal amountXmr,
                                      BigDecimal amountCal,
                                      BigDecimal amountCny,
                                      String txHash,
                                      String remark) {
        AssetLedger ledger = new AssetLedger();
        ledger.setUserId(userId);
        ledger.setCurrency("CAL");
        ledger.setAmountXmr(amountXmr);
        ledger.setAmountCal(amountCal);
        ledger.setAmountCny(amountCny);
        // 兼容数据库 ENUM：ref_type 使用 bonus，通过 remark 前缀区分子类型
        ledger.setRefType("bonus");
        ledger.setRefId(null);
        ledger.setTxHash(txHash);
        String suffix = (remark != null && !remark.isBlank()) ? remark : "新手手续费折扣";
        ledger.setRemark("invitee_discount:" + suffix);
        ledger.setEventTime(LocalDateTime.now());
        assetLedgerMapper.insertIgnore(ledger);
    }

    /**
     * 记录“被邀请者激活”标记（用于后续是否开始给邀请者计佣）。
     */
    @Transactional
    public void recordInviteeActivation(Long userId, String txHash) {
        AssetLedger ledger = new AssetLedger();
        ledger.setUserId(userId);
        ledger.setCurrency("CAL");
        ledger.setAmountXmr(BigDecimal.ZERO);
        ledger.setAmountCal(BigDecimal.ZERO);
        ledger.setAmountCny(BigDecimal.ZERO);
        // 兼容数据库 ENUM：ref_type 使用 bonus，通过 remark 前缀区分子类型
        ledger.setRefType("bonus");
        ledger.setRefId(null);
        ledger.setTxHash(txHash);
        ledger.setRemark("invitee_activation:邀请激活");
        ledger.setEventTime(LocalDateTime.now());
        assetLedgerMapper.insertIgnore(ledger);
    }
}
