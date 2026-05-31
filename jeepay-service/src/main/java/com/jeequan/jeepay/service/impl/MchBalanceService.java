/*
 * Copyright (c) 2026, 国际四方支付系统改造项目.
 */
package com.jeequan.jeepay.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.jeequan.jeepay.core.entity.MchBalanceRecord;
import com.jeequan.jeepay.core.entity.MchInfo;
import com.jeequan.jeepay.core.exception.BizException;
import com.jeequan.jeepay.service.mapper.MchBalanceRecordMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;

/**
 * 商户余额管理（P1-3）
 *
 * 余额三栏：
 *   available  可用余额（已结算，可提现 / 可被拒付扣款）
 *   pending    未下发（订单刚收款，T+N 后转 available）
 *   frozen     冻结（争议中，无法提现）
 *
 * 核心动作：
 *   creditPending(mchNo, amount, payOrderId)   订单成功 → pending +
 *   settle(mchNo, amount, payOrderId)           T+N 到账 → pending - / available +
 *   chargeback(...) 已由 ChargebackPenaltyService 直接操作（保持原状）
 *   refund(mchNo, amount, refundOrderId)        退款 → available -
 *   freeze / unfreeze(mchNo, amount)            冻结/解冻
 *   topup / adjust(mchNo, amount, operator)     运营充值 / 调账
 *
 * 设计要点：
 *   - 所有变动走本服务，保证流水完整
 *   - 同事务内：先读余额 → 计算 → 写流水 → 更新 MchInfo
 *   - 每次变动产生 1 条流水（含余额快照）
 *   - 不允许 available / pending 扣为负数（拒付扣款的 allow_negative 由调用方控制）
 *
 * @author 反风控改造组
 * @since 2026-06-01
 */
@Service
public class MchBalanceService extends ServiceImpl<MchBalanceRecordMapper, MchBalanceRecord> {

    private static final Logger logger = LoggerFactory.getLogger(MchBalanceService.class);

    @Autowired private MchInfoService mchInfoService;

    // ============================================
    // 订单链路（自动调用）
    // ============================================

    /**
     * 订单成功后入账到 pending（T+N 之前不能提现）
     * 调用时机：PayOrderService 标记订单成功的钩子里
     *
     * 并发安全（C3 修复）：
     *   1. 用 CAS UPDATE WHERE balance_pending = X 保证只有一个线程能写成功
     *   2. 写失败 → 重读 → 重试，最多 3 次
     *   3. 流水保留前后快照便于排查
     */
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public void creditPending(String mchNo, long amount, String currency, String payOrderId) {
        if (amount <= 0) return;
        int attempts = 0;
        while (attempts < 3) {
            attempts++;
            Snapshot before = readBalance(mchNo);
            long newPending = before.pending + amount;
            // CAS：只在 pending 还是读到的值时才写
            boolean ok = mchInfoService.update(
                    new LambdaUpdateWrapper<MchInfo>()
                            .set(MchInfo::getBalancePending, newPending)
                            .eq(MchInfo::getMchNo, mchNo)
                            .eq(MchInfo::getBalancePending, before.pending));
            if (ok) {
                saveRecord(mchNo, MchBalanceRecord.TYPE_ORDER_CREDIT,
                        0L, amount, 0L, currency,
                        before.available, newPending, before.frozen,
                        payOrderId, null, null, null, null, "订单收款入账");
                logger.info("[Balance] order_credit mchNo={} amount={} pending {} -> {}",
                        mchNo, amount, before.pending, newPending);
                return;
            }
            logger.warn("[Balance] creditPending CAS 冲突 mchNo={} attempt={}", mchNo, attempts);
        }
        throw new BizException("余额更新冲突，请重试 mchNo=" + mchNo);
    }

    /**
     * T+N 结算到账：pending → available
     * CAS：双字段同时校验，保证一致性
     */
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public void settle(String mchNo, long amount, String currency, String payOrderId) {
        if (amount <= 0) return;
        int attempts = 0;
        while (attempts < 3) {
            attempts++;
            Snapshot before = readBalance(mchNo);
            if (before.pending < amount) {
                logger.warn("[Balance] 结算失败：pending 不足 mchNo={} pending={} need={}",
                        mchNo, before.pending, amount);
                throw new BizException("pending 余额不足，无法结算");
            }
            long newPending = before.pending - amount;
            long newAvailable = before.available + amount;
            boolean ok = mchInfoService.update(
                    new LambdaUpdateWrapper<MchInfo>()
                            .set(MchInfo::getBalanceAvailable, newAvailable)
                            .set(MchInfo::getBalancePending, newPending)
                            .eq(MchInfo::getMchNo, mchNo)
                            .eq(MchInfo::getBalanceAvailable, before.available)
                            .eq(MchInfo::getBalancePending, before.pending));
            if (ok) {
                saveRecord(mchNo, MchBalanceRecord.TYPE_SETTLE,
                        amount, -amount, 0L, currency,
                        newAvailable, newPending, before.frozen,
                        payOrderId, null, null, null, null, "T+N 结算");
                logger.info("[Balance] settle mchNo={} amount={} pending {} -> {} / available {} -> {}",
                        mchNo, amount, before.pending, newPending, before.available, newAvailable);
                return;
            }
            logger.warn("[Balance] settle CAS 冲突 mchNo={} attempt={}", mchNo, attempts);
        }
        throw new BizException("余额更新冲突，请重试 mchNo=" + mchNo);
    }

    /**
     * 退款：优先扣 pending（订单还未到结算）再扣 available
     *
     * C4 修复：
     *   1. 不再允许 available 直接扣到负数（防薅羊毛）
     *   2. 先 pending 后 available（避免双扣）
     *   3. 流水里 amount_pending 和 amount_available 分别记录
     */
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public void refund(String mchNo, long amount, String currency, String refundOrderId) {
        if (amount <= 0) return;
        int attempts = 0;
        while (attempts < 3) {
            attempts++;
            Snapshot before = readBalance(mchNo);

            long deductFromPending;
            long deductFromAvailable;
            if (before.pending >= amount) {
                deductFromPending = amount;
                deductFromAvailable = 0L;
            } else {
                deductFromPending = before.pending;
                long remaining = amount - before.pending;
                if (before.available < remaining) {
                    logger.warn("[Balance] 退款额度不足 mchNo={} pending={} available={} need={}",
                            mchNo, before.pending, before.available, amount);
                    throw new BizException("商户余额不足以完成退款");
                }
                deductFromAvailable = remaining;
            }
            long newPending = before.pending - deductFromPending;
            long newAvailable = before.available - deductFromAvailable;

            boolean ok = mchInfoService.update(
                    new LambdaUpdateWrapper<MchInfo>()
                            .set(MchInfo::getBalancePending, newPending)
                            .set(MchInfo::getBalanceAvailable, newAvailable)
                            .eq(MchInfo::getMchNo, mchNo)
                            .eq(MchInfo::getBalancePending, before.pending)
                            .eq(MchInfo::getBalanceAvailable, before.available));
            if (ok) {
                saveRecord(mchNo, MchBalanceRecord.TYPE_REFUND,
                        -deductFromAvailable, -deductFromPending, 0L, currency,
                        newAvailable, newPending, before.frozen,
                        null, refundOrderId, null, null, null,
                        String.format("退款 pending=%d available=%d", deductFromPending, deductFromAvailable));
                logger.info("[Balance] refund mchNo={} total={} pending-{} available-{}",
                        mchNo, amount, deductFromPending, deductFromAvailable);
                return;
            }
            logger.warn("[Balance] refund CAS 冲突 mchNo={} attempt={}", mchNo, attempts);
        }
        throw new BizException("余额更新冲突，请重试 mchNo=" + mchNo);
    }

    // ============================================
    // 争议链路（手动 / 风控）
    // ============================================

    /** 冻结：available -, frozen + （CAS 安全） */
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public void freeze(String mchNo, long amount, String reason) {
        if (amount <= 0) return;
        int attempts = 0;
        while (attempts < 3) {
            attempts++;
            Snapshot before = readBalance(mchNo);
            if (before.available < amount) throw new BizException("可用余额不足，无法冻结");
            long newAvailable = before.available - amount;
            long newFrozen = before.frozen + amount;
            boolean ok = mchInfoService.update(
                    new LambdaUpdateWrapper<MchInfo>()
                            .set(MchInfo::getBalanceAvailable, newAvailable)
                            .set(MchInfo::getBalanceFrozen, newFrozen)
                            .eq(MchInfo::getMchNo, mchNo)
                            .eq(MchInfo::getBalanceAvailable, before.available)
                            .eq(MchInfo::getBalanceFrozen, before.frozen));
            if (ok) {
                saveRecord(mchNo, MchBalanceRecord.TYPE_FREEZE,
                        -amount, 0L, amount, null,
                        newAvailable, before.pending, newFrozen,
                        null, null, null, null, null, reason);
                return;
            }
        }
        throw new BizException("余额更新冲突，请重试 mchNo=" + mchNo);
    }

    /** 解冻：frozen -, available + （CAS 安全） */
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public void unfreeze(String mchNo, long amount, String reason) {
        if (amount <= 0) return;
        int attempts = 0;
        while (attempts < 3) {
            attempts++;
            Snapshot before = readBalance(mchNo);
            if (before.frozen < amount) throw new BizException("冻结余额不足");
            long newAvailable = before.available + amount;
            long newFrozen = before.frozen - amount;
            boolean ok = mchInfoService.update(
                    new LambdaUpdateWrapper<MchInfo>()
                            .set(MchInfo::getBalanceAvailable, newAvailable)
                            .set(MchInfo::getBalanceFrozen, newFrozen)
                            .eq(MchInfo::getMchNo, mchNo)
                            .eq(MchInfo::getBalanceAvailable, before.available)
                            .eq(MchInfo::getBalanceFrozen, before.frozen));
            if (ok) {
                saveRecord(mchNo, MchBalanceRecord.TYPE_UNFREEZE,
                        amount, 0L, -amount, null,
                        newAvailable, before.pending, newFrozen,
                        null, null, null, null, null, reason);
                return;
            }
        }
        throw new BizException("余额更新冲突，请重试 mchNo=" + mchNo);
    }

    // ============================================
    // 运营手动调账
    // ============================================

    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public void topup(String mchNo, long amount, String currency, String operator, String remark) {
        if (amount <= 0) throw new BizException("充值金额必须 > 0");
        int attempts = 0;
        while (attempts < 3) {
            attempts++;
            Snapshot before = readBalance(mchNo);
            long newAvailable = before.available + amount;
            boolean ok = mchInfoService.update(
                    new LambdaUpdateWrapper<MchInfo>()
                            .set(MchInfo::getBalanceAvailable, newAvailable)
                            .eq(MchInfo::getMchNo, mchNo)
                            .eq(MchInfo::getBalanceAvailable, before.available));
            if (ok) {
                saveRecord(mchNo, MchBalanceRecord.TYPE_TOPUP,
                        amount, 0L, 0L, currency,
                        newAvailable, before.pending, before.frozen,
                        null, null, null, null, operator, remark);
                logger.info("[Balance] topup mchNo={} amount={} by={}", mchNo, amount, operator);
                return;
            }
        }
        throw new BizException("余额更新冲突，请重试 mchNo=" + mchNo);
    }

    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public void adjust(String mchNo, long delta, String currency, String operator, String remark) {
        if (delta == 0) return;
        int attempts = 0;
        while (attempts < 3) {
            attempts++;
            Snapshot before = readBalance(mchNo);
            long newAvailable = before.available + delta;
            if (newAvailable < 0) throw new BizException("调账后可用余额不能为负");
            boolean ok = mchInfoService.update(
                    new LambdaUpdateWrapper<MchInfo>()
                            .set(MchInfo::getBalanceAvailable, newAvailable)
                            .eq(MchInfo::getMchNo, mchNo)
                            .eq(MchInfo::getBalanceAvailable, before.available));
            if (ok) {
                saveRecord(mchNo,
                        delta > 0 ? MchBalanceRecord.TYPE_ADJUST_PLUS : MchBalanceRecord.TYPE_ADJUST_MINUS,
                        delta, 0L, 0L, currency,
                        newAvailable, before.pending, before.frozen,
                        null, null, null, null, operator, remark);
                return;
            }
        }
        throw new BizException("余额更新冲突，请重试 mchNo=" + mchNo);
    }

    // ============================================
    // 内部辅助
    // ============================================

    private Snapshot readBalance(String mchNo) {
        MchInfo m = mchInfoService.getById(mchNo);
        if (m == null) throw new BizException("商户不存在：" + mchNo);
        Snapshot s = new Snapshot();
        s.available = m.getBalanceAvailable() == null ? 0L : m.getBalanceAvailable();
        s.pending   = m.getBalancePending()   == null ? 0L : m.getBalancePending();
        s.frozen    = m.getBalanceFrozen()    == null ? 0L : m.getBalanceFrozen();
        return s;
    }

    private void saveRecord(String mchNo, String type,
                            long deltaAvailable, long deltaPending, long deltaFrozen,
                            String currency,
                            long availableAfter, long pendingAfter, long frozenAfter,
                            String payOrderId, String refundOrderId,
                            Long chargebackId, Long penaltyRecordId,
                            String operator, String remark) {
        MchBalanceRecord r = new MchBalanceRecord()
                .setMchNo(mchNo).setType(type)
                .setAmountAvailable(deltaAvailable)
                .setAmountPending(deltaPending)
                .setAmountFrozen(deltaFrozen)
                .setCurrency(currency == null ? "USD" : currency)
                .setBalanceAvailableAfter(availableAfter)
                .setBalancePendingAfter(pendingAfter)
                .setBalanceFrozenAfter(frozenAfter)
                .setPayOrderId(payOrderId)
                .setRefundOrderId(refundOrderId)
                .setChargebackId(chargebackId)
                .setPenaltyRecordId(penaltyRecordId)
                .setOperator(operator)
                .setRemark(remark)
                .setCreatedAt(new Date());
        save(r);
    }

    private static class Snapshot {
        long available;
        long pending;
        long frozen;
    }
}
