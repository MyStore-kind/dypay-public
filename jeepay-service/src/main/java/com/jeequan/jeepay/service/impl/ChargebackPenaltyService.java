/*
 * Copyright (c) 2026, 国际四方支付系统改造项目.
 */
package com.jeequan.jeepay.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.jeequan.jeepay.core.entity.ChargebackPenaltyConfig;
import com.jeequan.jeepay.core.entity.ChargebackPenaltyRecord;
import com.jeequan.jeepay.core.entity.ChargebackRecord;
import com.jeequan.jeepay.core.entity.MchInfo;
import com.jeequan.jeepay.core.entity.PayOrder;
import com.jeequan.jeepay.service.mapper.ChargebackPenaltyConfigMapper;
import com.jeequan.jeepay.service.mapper.ChargebackPenaltyRecordMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

/**
 * 拒付惩罚扣款引擎（P0 地基）
 *
 * 业务规则（来自需求）：
 *   1. 拒付 1 次 = 扣 N 倍本金（默认 3，每商户可在 t_chargeback_penalty_config 配置）
 *   2. 扣款来源按配置顺序：available（可用余额） → pending（未下发）
 *   3. 余额不足时：allow_negative=1 全额扣（可为负），=0 扣到 0 为止（落 partial 状态）
 *   4. 默认不封号：auto_freeze_on_chargeback=0；如运营有需要可单独开启
 *
 * 设计要点：
 *   - 用独立事务（REQUIRES_NEW）：扣款失败不能回滚 ChargebackService 的拒付落库
 *   - 幂等：UNIQUE(chargeback_record_id) 保证同一拒付不会被扣两次
 *   - 全流水：每次扣款都落 t_chargeback_penalty_record，含余额前后快照
 *   - 不抛业务异常：被调用方（ChargebackService）应静默处理
 *
 * @author 反风控改造组
 * @since 2026-05-31
 */
@Service
public class ChargebackPenaltyService extends ServiceImpl<ChargebackPenaltyRecordMapper, ChargebackPenaltyRecord> {

    private static final Logger logger = LoggerFactory.getLogger(ChargebackPenaltyService.class);

    @Autowired private ChargebackPenaltyConfigMapper configMapper;
    @Autowired private MchInfoService mchInfoService;
    @Autowired private PayOrderService payOrderService;
    @Autowired private NotificationService notificationService;

    /**
     * 汇率服务，币种归一化用。
     * required=false：旧测试上下文 / 早期未引入汇率模块时仍能加载本服务
     */
    @Autowired(required = false)
    private CurrencyRateService currencyRateService;

    /**
     * 执行扣款。失败仅记日志，不抛异常。
     *
     * 调用时机：ChargebackService.receiveChargeback() 落库成功后立刻调用。
     *
     * @param chargeback 拒付记录（已落库，含 id / mchNo / payOrderId）
     * @return 扣款流水（含状态）；任何前置校验失败返回 null
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public ChargebackPenaltyRecord applyPenalty(ChargebackRecord chargeback) {
        if (chargeback == null || chargeback.getId() == null) {
            return null;
        }

        try {
            // ===== 1. 幂等：同一拒付记录已扣过则直接返回旧流水 =====
            ChargebackPenaltyRecord existed = getOne(ChargebackPenaltyRecord.gw()
                    .eq(ChargebackPenaltyRecord::getChargebackRecordId, chargeback.getId())
                    .last("LIMIT 1"), false);
            if (existed != null) {
                logger.info("[Penalty] 拒付记录已扣款过 chargebackId={} state={}",
                        chargeback.getId(), existed.getState());
                return existed;
            }

            String mchNo = chargeback.getMchNo();
            if (mchNo == null || mchNo.isEmpty()) {
                return saveSkipped(chargeback, "拒付记录无关联商户号");
            }

            // ===== 2. 读取本金（必须有原订单） =====
            PayOrder payOrder = chargeback.getPayOrderId() == null ? null
                    : payOrderService.getById(chargeback.getPayOrderId());
            if (payOrder == null) {
                return saveSkipped(chargeback, "原订单未找到，无法计算本金");
            }
            long principal = payOrder.getAmount() == null ? 0L : payOrder.getAmount();
            if (principal <= 0) {
                return saveSkipped(chargeback, "原订单金额为 0，无需扣款");
            }

            // ===== 3. 读取配置（按商户号 → __GLOBAL__ 兜底） =====
            ChargebackPenaltyConfig cfg = loadConfig(mchNo);
            if (cfg == null) {
                return saveSkipped(chargeback, "未找到惩罚配置（含全局兜底），跳过");
            }
            if (cfg.getEnabled() == null || cfg.getEnabled() == 0) {
                return saveSkipped(chargeback, "惩罚配置未启用");
            }

            // ===== 4. 读取商户 =====
            MchInfo mch = mchInfoService.getById(mchNo);
            if (mch == null) {
                return saveSkipped(chargeback, "商户不存在：" + mchNo);
            }

            // ===== 4b. 币种归一化 =====
            // 拒付本金 principal 单位是 payOrder.currency 的最小货币单位（分/聪等）
            // 商户余额单位是 mch.settlementCurrency 的最小货币单位
            // 不同币种必须换算后再扣，否则会"扣错钱"
            //
            // 设计：
            //   原币种 = payOrder.currency（如 USD）
            //   目标币种 = mch.settlementCurrency（如 CNY）；为空时回落 USD
            //   汇率优先用 payOrder.frozenRate（下单时锁定，最稳定）
            //   没有 frozenRate 时实时查 t_currency_rate
            //   汇率服务异常 → 兜底：按 1:1 扣（保守，避免误扣巨额）+ 流水标记
            String orderCcy = payOrder.getCurrency() == null ? "USD" : payOrder.getCurrency().toUpperCase();
            String settleCcy = mch.getSettlementCurrency() == null ? "USD"
                    : mch.getSettlementCurrency().toUpperCase();
            long principalInSettle = principal;
            BigDecimal usedRate = BigDecimal.ONE;
            String rateNote = null;
            if (!orderCcy.equals(settleCcy)) {
                // 先看订单冻结汇率（只有同 base/target 时才用，这里没字段记录方向，
                // 保守做法：只有当 settleCcy 也是 USD 这种"基准币种"时不用 frozenRate，
                // 其他情况都查实时；frozenRate 主要给退款 / 对账场景）
                if (currencyRateService != null) {
                    try {
                        usedRate = currencyRateService.getRealTimeRate(orderCcy, settleCcy);
                        rateNote = "realtime";
                    } catch (Exception e) {
                        logger.warn("[Penalty] 汇率查询失败 {} -> {}，按 1:1 兜底扣款 mchNo={}",
                                orderCcy, settleCcy, mchNo, e);
                        usedRate = BigDecimal.ONE;
                        rateNote = "fallback_1_1";
                    }
                } else {
                    rateNote = "no_rate_service_1_1";
                }
                principalInSettle = new BigDecimal(principal)
                        .multiply(usedRate)
                        .setScale(0, RoundingMode.HALF_UP)
                        .longValue();
            }

            BigDecimal multiplier = cfg.getPenaltyMultiplier() == null
                    ? new BigDecimal("3.00") : cfg.getPenaltyMultiplier();
            long expectedDeduct = new BigDecimal(principalInSettle)
                    .multiply(multiplier)
                    .setScale(0, RoundingMode.HALF_UP)
                    .longValue();

            long availableBefore = nullSafe(mch.getBalanceAvailable());
            long pendingBefore = nullSafe(mch.getBalancePending());

            // ===== 5. 按优先级扣款 =====
            List<String> priority = parsePriority(cfg.getDeductSourcePriority());
            boolean allowNegative = cfg.getAllowNegative() != null && cfg.getAllowNegative() == 1;

            long remaining = expectedDeduct;
            long deductFromAvailable = 0L;
            long deductFromPending = 0L;
            long availableAfter = availableBefore;
            long pendingAfter = pendingBefore;

            for (String src : priority) {
                if (remaining <= 0) break;
                if (ChargebackPenaltyConfig.SOURCE_AVAILABLE.equals(src)) {
                    long take = Math.min(remaining, Math.max(availableAfter, 0L));
                    deductFromAvailable += take;
                    availableAfter -= take;
                    remaining -= take;
                } else if (ChargebackPenaltyConfig.SOURCE_PENDING.equals(src)) {
                    long take = Math.min(remaining, Math.max(pendingAfter, 0L));
                    deductFromPending += take;
                    pendingAfter -= take;
                    remaining -= take;
                }
            }

            // 允许负余额：把剩余 remaining 全部记到 priority 列表的最后一个来源（默认 pending）
            if (remaining > 0 && allowNegative && !priority.isEmpty()) {
                String last = priority.get(priority.size() - 1);
                if (ChargebackPenaltyConfig.SOURCE_AVAILABLE.equals(last)) {
                    deductFromAvailable += remaining;
                    availableAfter -= remaining;
                } else {
                    deductFromPending += remaining;
                    pendingAfter -= remaining;
                }
                remaining = 0;
            }

            long actualDeduct = expectedDeduct - remaining;
            String state = remaining == 0 ? ChargebackPenaltyRecord.STATE_SUCCESS
                                          : ChargebackPenaltyRecord.STATE_PARTIAL;
            String reason = remaining == 0 ? null
                    : "余额不足，应扣 " + expectedDeduct + " 实扣 " + actualDeduct;

            // ===== 6. 持久化 — 商户余额 + 流水 =====
            MchInfo upd = new MchInfo()
                    .setMchNo(mchNo)
                    .setBalanceAvailable(availableAfter)
                    .setBalancePending(pendingAfter);
            mchInfoService.updateById(upd);

            // 把币种换算信息合并到 reason 里，方便排查
            String finalReason = reason;
            if (!orderCcy.equals(settleCcy)) {
                String fxInfo = String.format("[FX %s->%s rate=%s src=%s principal_settle=%d]",
                        orderCcy, settleCcy, usedRate.toPlainString(),
                        rateNote == null ? "?" : rateNote, principalInSettle);
                finalReason = finalReason == null ? fxInfo : (fxInfo + " " + finalReason);
            }

            ChargebackPenaltyRecord rec = new ChargebackPenaltyRecord()
                    .setChargebackRecordId(chargeback.getId())
                    .setPayOrderId(chargeback.getPayOrderId())
                    .setMchNo(mchNo)
                    .setPrincipalAmount(principal)
                    .setMultiplierSnapshot(multiplier)
                    .setExpectedDeductAmount(expectedDeduct)
                    .setActualDeductAmount(actualDeduct)
                    .setDeductedFromAvailable(deductFromAvailable)
                    .setDeductedFromPending(deductFromPending)
                    .setBalanceAvailableBefore(availableBefore)
                    .setBalanceAvailableAfter(availableAfter)
                    .setBalancePendingBefore(pendingBefore)
                    .setBalancePendingAfter(pendingAfter)
                    .setState(state)
                    .setReason(finalReason)
                    .setCreatedAt(new Date());
            save(rec);

            // ===== 7. 通知（可选） =====
            if (cfg.getMinAlertBalance() != null && cfg.getMinAlertBalance() > 0
                    && availableAfter < cfg.getMinAlertBalance()) {
                notificationService.notify(
                        "商户余额告警",
                        String.format("商户 %s 拒付扣款后可用余额 %d 低于阈值 %d",
                                mchNo, availableAfter, cfg.getMinAlertBalance()));
            }
            notificationService.notify(
                    "拒付惩罚扣款 [" + state + "]",
                    String.format("商户 %s 拒付 %s，本金 %d × %s = 扣 %d（实扣 %d）",
                            mchNo, chargeback.getId(), principal, multiplier.toPlainString(),
                            expectedDeduct, actualDeduct));

            // ===== 8. 可选：按配置冻结商户（默认关） =====
            if (cfg.getAutoFreezeOnChargeback() != null && cfg.getAutoFreezeOnChargeback() == 1) {
                mchInfoService.updateById(new MchInfo().setMchNo(mchNo).setState((byte) 0));
                logger.warn("[Penalty] 按配置冻结商户 mchNo={}", mchNo);
            }

            logger.info("[Penalty] 扣款完成 mchNo={} chargebackId={} expected={} actual={} state={}",
                    mchNo, chargeback.getId(), expectedDeduct, actualDeduct, state);
            return rec;

        } catch (Exception e) {
            logger.error("[Penalty] 扣款异常 chargebackId={}", chargeback.getId(), e);
            // 不抛：避免影响拒付落库主流程
            return saveFailed(chargeback, "扣款异常：" + e.getMessage());
        }
    }

    // ============================================
    // 内部辅助
    // ============================================

    /** 按商户号查配置，找不到则回落全局 __GLOBAL__ */
    private ChargebackPenaltyConfig loadConfig(String mchNo) {
        ChargebackPenaltyConfig cfg = getOne(ChargebackPenaltyConfig.class, mchNo);
        if (cfg != null) return cfg;
        return getOne(ChargebackPenaltyConfig.class, ChargebackPenaltyConfig.GLOBAL_MCH_NO);
    }

    private ChargebackPenaltyConfig getOne(Class<ChargebackPenaltyConfig> cls, String mchNo) {
        return configMapper.selectOne(ChargebackPenaltyConfig.gw()
                .eq(ChargebackPenaltyConfig::getMchNo, mchNo)
                .last("LIMIT 1"));
    }

    private List<String> parsePriority(String priority) {
        if (priority == null || priority.isEmpty()) {
            return Arrays.asList(ChargebackPenaltyConfig.SOURCE_AVAILABLE,
                                 ChargebackPenaltyConfig.SOURCE_PENDING);
        }
        return Arrays.asList(priority.split(","));
    }

    private long nullSafe(Long v) { return v == null ? 0L : v; }

    private ChargebackPenaltyRecord saveSkipped(ChargebackRecord chargeback, String reason) {
        logger.info("[Penalty] 跳过扣款 chargebackId={} reason={}", chargeback.getId(), reason);
        ChargebackPenaltyRecord r = new ChargebackPenaltyRecord()
                .setChargebackRecordId(chargeback.getId())
                .setPayOrderId(chargeback.getPayOrderId())
                .setMchNo(chargeback.getMchNo())
                .setPrincipalAmount(0L)
                .setMultiplierSnapshot(BigDecimal.ZERO)
                .setExpectedDeductAmount(0L)
                .setActualDeductAmount(0L)
                .setDeductedFromAvailable(0L)
                .setDeductedFromPending(0L)
                .setBalanceAvailableBefore(0L)
                .setBalanceAvailableAfter(0L)
                .setBalancePendingBefore(0L)
                .setBalancePendingAfter(0L)
                .setState(ChargebackPenaltyRecord.STATE_SKIPPED)
                .setReason(reason)
                .setCreatedAt(new Date());
        try { save(r); } catch (Exception ignored) {}
        return r;
    }

    private ChargebackPenaltyRecord saveFailed(ChargebackRecord chargeback, String reason) {
        ChargebackPenaltyRecord r = new ChargebackPenaltyRecord()
                .setChargebackRecordId(chargeback.getId())
                .setPayOrderId(chargeback.getPayOrderId())
                .setMchNo(chargeback.getMchNo())
                .setPrincipalAmount(0L)
                .setMultiplierSnapshot(BigDecimal.ZERO)
                .setExpectedDeductAmount(0L)
                .setActualDeductAmount(0L)
                .setDeductedFromAvailable(0L)
                .setDeductedFromPending(0L)
                .setBalanceAvailableBefore(0L)
                .setBalanceAvailableAfter(0L)
                .setBalancePendingBefore(0L)
                .setBalancePendingAfter(0L)
                .setState(ChargebackPenaltyRecord.STATE_FAILED)
                .setReason(reason)
                .setCreatedAt(new Date());
        try { save(r); } catch (Exception ignored) {}
        return r;
    }
}
