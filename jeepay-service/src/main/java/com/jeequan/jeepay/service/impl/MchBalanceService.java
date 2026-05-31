/*
 * Copyright (c) 2026, 国际四方支付系统改造项目.
 */
package com.jeequan.jeepay.service.impl;

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
     */
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public void creditPending(String mchNo, long amount, String currency, String payOrderId) {
        if (amount <= 0) return;
        Snapshot before = readBalance(mchNo);
        long newPending = before.pending + amount;

        mchInfoService.updateById(new MchInfo().setMchNo(mchNo).setBalancePending(newPending));
        saveRecord(mchNo, MchBalanceRecord.TYPE_ORDER_CREDIT,
                0L, amount, 0L, currency,
                before.available, newPending, before.frozen,
                payOrderId, null, null, null, null, "订单收款入账");

        logger.info("[Balance] order_credit mchNo={} amount={} pending {} -> {}",
                mchNo, amount, before.pending, newPending);
    }

    /**
     * T+N 结算到账：pending → available
     */
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public void settle(String mchNo, long amount, String currency, String payOrderId) {
        if (amount <= 0) return;
        Snapshot before = readBalance(mchNo);
        if (before.pending < amount) {
            logger.warn("[Balance] 结算失败：pending 不足 mchNo={} pending={} need={}",
                    mchNo, before.pending, amount);
            throw new BizException("pending 余额不足，无法结算");
        }
        long newPending = before.pending - amount;
        long newAvailable = before.available + amount;

        mchInfoService.updateById(new MchInfo().setMchNo(mchNo)
                .setBalancePending(newPending).setBalanceAvailable(newAvailable));
        saveRecord(mchNo, MchBalanceRecord.TYPE_SETTLE,
                amount, -amount, 0L, currency,
                newAvailable, newPending, before.frozen,
                payOrderId, null, null, null, null, "T+N 结算");

        logger.info("[Balance] settle mchNo={} amount={} pending {} -> {} / available {} -> {}",
                mchNo, amount, before.pending, newPending, before.available, newAvailable);
    }

    /**
     * 退款：available -
     */
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public void refund(String mchNo, long amount, String currency, String refundOrderId) {
        if (amount <= 0) return;
        Snapshot before = readBalance(mchNo);
        long newAvailable = before.available - amount;
        // 允许 available 退到负数（避免商户已提现后还要退款的死锁）

        mchInfoService.updateById(new MchInfo().setMchNo(mchNo).setBalanceAvailable(newAvailable));
        saveRecord(mchNo, MchBalanceRecord.TYPE_REFUND,
                -amount, 0L, 0L, currency,
                newAvailable, before.pending, before.frozen,
                null, refundOrderId, null, null, null, "退款扣减");

        logger.info("[Balance] refund mchNo={} amount={} available {} -> {}",
                mchNo, amount, before.available, newAvailable);
    }

    // ============================================
    // 争议链路（手动 / 风控）
    // ============================================

    /** 冻结：available -, frozen + */
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public void freeze(String mchNo, long amount, String reason) {
        if (amount <= 0) return;
        Snapshot before = readBalance(mchNo);
        if (before.available < amount) throw new BizException("可用余额不足，无法冻结");
        long newAvailable = before.available - amount;
        long newFrozen = before.frozen + amount;
        mchInfoService.updateById(new MchInfo().setMchNo(mchNo)
                .setBalanceAvailable(newAvailable).setBalanceFrozen(newFrozen));
        saveRecord(mchNo, MchBalanceRecord.TYPE_FREEZE,
                -amount, 0L, amount, null,
                newAvailable, before.pending, newFrozen,
                null, null, null, null, null, reason);
    }

    /** 解冻：frozen -, available + */
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public void unfreeze(String mchNo, long amount, String reason) {
        if (amount <= 0) return;
        Snapshot before = readBalance(mchNo);
        if (before.frozen < amount) throw new BizException("冻结余额不足");
        long newAvailable = before.available + amount;
        long newFrozen = before.frozen - amount;
        mchInfoService.updateById(new MchInfo().setMchNo(mchNo)
                .setBalanceAvailable(newAvailable).setBalanceFrozen(newFrozen));
        saveRecord(mchNo, MchBalanceRecord.TYPE_UNFREEZE,
                amount, 0L, -amount, null,
                newAvailable, before.pending, newFrozen,
                null, null, null, null, null, reason);
    }

    // ============================================
    // 运营手动调账
    // ============================================

    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public void topup(String mchNo, long amount, String currency, String operator, String remark) {
        if (amount <= 0) throw new BizException("充值金额必须 > 0");
        Snapshot before = readBalance(mchNo);
        long newAvailable = before.available + amount;
        mchInfoService.updateById(new MchInfo().setMchNo(mchNo).setBalanceAvailable(newAvailable));
        saveRecord(mchNo, MchBalanceRecord.TYPE_TOPUP,
                amount, 0L, 0L, currency,
                newAvailable, before.pending, before.frozen,
                null, null, null, null, operator, remark);
        logger.info("[Balance] topup mchNo={} amount={} by={}", mchNo, amount, operator);
    }

    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public void adjust(String mchNo, long delta, String currency, String operator, String remark) {
        if (delta == 0) return;
        Snapshot before = readBalance(mchNo);
        long newAvailable = before.available + delta;
        mchInfoService.updateById(new MchInfo().setMchNo(mchNo).setBalanceAvailable(newAvailable));
        saveRecord(mchNo,
                delta > 0 ? MchBalanceRecord.TYPE_ADJUST_PLUS : MchBalanceRecord.TYPE_ADJUST_MINUS,
                delta, 0L, 0L, currency,
                newAvailable, before.pending, before.frozen,
                null, null, null, null, operator, remark);
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
