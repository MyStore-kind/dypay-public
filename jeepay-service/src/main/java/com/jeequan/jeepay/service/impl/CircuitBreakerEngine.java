/*
 * Copyright (c) 2026, 国际四方支付系统改造项目.
 */
package com.jeequan.jeepay.service.impl;

import com.jeequan.jeepay.core.cache.RedisUtil;
import com.jeequan.jeepay.core.constants.CS;
import com.jeequan.jeepay.core.entity.ChannelAccount;
import com.jeequan.jeepay.core.entity.MchInfo;
import com.jeequan.jeepay.core.entity.RiskThresholdConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.concurrent.TimeUnit;

/**
 * 熔断/降流引擎
 *
 * 核心职责：
 * - 监听通道账号、商户指标
 * - 对照运营配置的阈值
 * - 触发对应动作（告警/限流/暂停）
 *
 * 设计原则：
 * - 系统不内置任何"应该怎么做"的逻辑
 * - 全部读取 RiskThresholdConfig 的运营配置
 * - 触发动作可独立开关（action_enabled）
 *
 * @author 反风控改造组
 */
@Service
public class CircuitBreakerEngine {

    private static final Logger logger = LoggerFactory.getLogger(CircuitBreakerEngine.class);

    @Autowired
    private RiskThresholdConfigService thresholdConfig;

    @Autowired
    private ChannelAccountService channelAccountService;

    @Autowired
    private NotificationService notificationService;

    // 商户自动熔断时同步翻转 t_mch_info.state，避免运营在商户列表仍看到"启用"而困惑；
    // 同时下单链路若只读 MchInfo.state 也能被拦住，不依赖 Redis 熔断态是否被绕过。
    @Autowired
    private MchInfoService mchInfoService;

    /**
     * 自动熔断在 Redis 上的"原因标记"前缀。
     *
     * 背景：t_mch_info 当前没有 disable_reason 字段（见 {@link MchInfo}），
     * 而运营手工停用与自动熔断停用必须区分——否则 {@link RiskCircuitBreakerEngine#release}
     * 解除熔断时会把"被运营主动停用"的商户错误地重新启用回去。
     *
     * 方案选择：不动 schema，采用 Redis 旁路标记。
     *   key = risk:cb:reason:mch:{mchNo}
     *   val = AUTO_CIRCUIT_BREAKER
     * TTL 与本引擎触发的熔断态一致（与 RiskCircuitBreakerEngine 的 CB_TTL 同量级，
     * 这里保守取 7 天）；过期即视为"非自动熔断停用"，恢复路径不再自动启用。
     */
    private static final String KEY_CB_REASON_MCH = "risk:cb:reason:mch:";
    private static final String CB_REASON_AUTO    = "AUTO_CIRCUIT_BREAKER";
    private static final long   CB_REASON_TTL_SEC = 7L * 24 * 3600;

    // R1 日额熔断写入"商户熔断态" key，与 RiskCircuitBreakerEngine 的 KEY_CB_MCH 同前缀，
    // 让现有路由层 isCircuitBroken(mchNo) 判断能直接生效，避免再开一套并行 key。
    // 不复用 RiskCircuitBreakerEngine.triggerForMerchant：那条路径会把 MchInfo.state 写成 2（FROZEN，需人工解除），
    // 而 R1 是"30 分钟自动恢复"语义，更适合 state=CS.NO + Redis TTL 过期后由调度/release 自动启用。
    private static final String KEY_CB_MCH = "risk:cb:mch:";

    /**
     * 检查通道账号是否需要触发动作
     * 由调度任务每次更新健康度后调用
     */
    public void checkChannelAccount(ChannelAccount account) {
        if (account == null) return;

        // 拒付率检查
        checkChannelMetric(
                account, "chargeback_rate", account.getChargebackRate(),
                "channel.chargeback_rate.warning",
                "channel.chargeback_rate.critical");

        // 投诉率检查
        checkChannelMetric(
                account, "dispute_rate", account.getDisputeRate(),
                "channel.dispute_rate.warning",
                "channel.dispute_rate.critical");

        // 退款率检查
        checkChannelMetric(
                account, "refund_rate", account.getRefundRate(),
                "channel.refund_rate.warning",
                "channel.refund_rate.critical");

        // 成功率检查（反向：低于阈值才触发）
        checkChannelSuccessRate(account);
    }

    private void checkChannelMetric(ChannelAccount account, String metricName,
                                    BigDecimal currentValue,
                                    String warningKey, String criticalKey) {
        if (currentValue == null) return;

        BigDecimal warning = thresholdConfig.getNumber(warningKey, null);
        BigDecimal critical = thresholdConfig.getNumber(criticalKey, null);

        if (critical != null && currentValue.compareTo(critical) >= 0) {
            triggerCriticalAction(account, metricName, currentValue, criticalKey);
        } else if (warning != null && currentValue.compareTo(warning) >= 0) {
            triggerWarningAction(account, metricName, currentValue, warningKey);
        }
    }

    private void checkChannelSuccessRate(ChannelAccount account) {
        if (account.getSuccessRate() == null) return;
        BigDecimal warning = thresholdConfig.getNumber("channel.success_rate.warning", null);
        BigDecimal critical = thresholdConfig.getNumber("channel.success_rate.critical", null);
        if (critical != null && account.getSuccessRate().compareTo(critical) < 0) {
            triggerCriticalAction(account, "success_rate", account.getSuccessRate(), "channel.success_rate.critical");
        } else if (warning != null && account.getSuccessRate().compareTo(warning) < 0) {
            triggerWarningAction(account, "success_rate", account.getSuccessRate(), "channel.success_rate.warning");
        }
    }

    private void triggerWarningAction(ChannelAccount account, String metric, BigDecimal value, String configKey) {
        // 黄线只告警（更新健康度为警告）
        if (account.getHealthStatus() != null && account.getHealthStatus() != ChannelAccount.HEALTH_WARNING) {
            channelAccountService.updateHealthStatus(account.getAccountId(), ChannelAccount.HEALTH_WARNING);
        }

        if (!thresholdConfig.isActionEnabled(configKey)) {
            logger.info("[Circuit] {} 触发黄线但动作禁用：{}={}", account.getAccountId(), metric, value);
            return;
        }
        notificationService.notify(
                "通道账号黄线告警",
                String.format("账号 %s 的 %s 当前值 %s 超过黄线阈值", account.getAccountId(), metric, value));
    }

    private void triggerCriticalAction(ChannelAccount account, String metric, BigDecimal value, String configKey) {
        // 红线动作：根据配置 action_type 执行
        String action = thresholdConfig.getActionType(configKey);
        boolean enabled = thresholdConfig.isActionEnabled(configKey);

        // 始终通知
        notificationService.notify(
                "通道账号红线告警",
                String.format("账号 %s 的 %s 当前值 %s 已超过红线（动作：%s 启用：%s）",
                        account.getAccountId(), metric, value, action, enabled));

        if (!enabled) {
            logger.warn("[Circuit] {} 触发红线但动作禁用：{}={}", account.getAccountId(), metric, value);
            return;
        }

        switch (action == null ? "" : action) {
            case RiskThresholdConfig.ACTION_LIMIT:
                channelAccountService.updateHealthStatus(account.getAccountId(), ChannelAccount.HEALTH_LIMITED);
                break;
            case RiskThresholdConfig.ACTION_SUSPEND:
                ChannelAccount upd = new ChannelAccount()
                        .setAccountId(account.getAccountId())
                        .setState(ChannelAccount.STATE_FROZEN);
                channelAccountService.updateById(upd);
                break;
            case RiskThresholdConfig.ACTION_NOTIFY:
            default:
                // 仅通知，已在上方处理
                break;
        }
    }

    /**
     * 检查商户是否需要触发动作
     */
    public void checkMerchant(MchInfo merchant, BigDecimal chargebackRate, BigDecimal disputeRate, int riskScore) {
        BigDecimal cbCritical = thresholdConfig.getNumber("merchant.chargeback_rate.critical", null);
        BigDecimal dpCritical = thresholdConfig.getNumber("merchant.dispute_rate.critical", null);
        BigDecimal scoreThreshold = thresholdConfig.getNumber("merchant.high_risk_score.threshold", null);

        boolean shouldAlert = false;
        StringBuilder reason = new StringBuilder();

        if (cbCritical != null && chargebackRate != null && chargebackRate.compareTo(cbCritical) >= 0) {
            shouldAlert = true;
            reason.append("拒付率 ").append(chargebackRate).append("% 超红线 ").append(cbCritical).append("%；");
        }
        if (dpCritical != null && disputeRate != null && disputeRate.compareTo(dpCritical) >= 0) {
            shouldAlert = true;
            reason.append("投诉率 ").append(disputeRate).append("% 超红线 ").append(dpCritical).append("%；");
        }
        if (scoreThreshold != null && riskScore >= scoreThreshold.intValue()) {
            shouldAlert = true;
            reason.append("评分 ").append(riskScore).append(" 超阈值；");
        }

        if (shouldAlert) {
            notificationService.notify(
                    "商户风险告警",
                    String.format("商户 %s：%s", merchant.getMchNo(), reason));

            // 自动暂停（仅当商户开启了 auto_suspend）
            if (merchant.getAutoSuspendEnabled() != null && merchant.getAutoSuspendEnabled() == 1) {
                logger.warn("[Circuit] 商户 {} 触发自动暂停", merchant.getMchNo());
                autoSuspendMerchant(merchant.getMchNo(), reason.toString());
            }
        }
    }

    /**
     * 商户自动熔断停用：联动 t_mch_info.state，并打 Redis 旁路标记。
     *
     * 为什么不只写 Redis：
     *   - 商户后台 UI / 商户列表只读 t_mch_info.state，不读 Redis；
     *     若仅写 Redis，运营看到的仍是"启用"，与实际行为不一致。
     *   - 自助下单链路有的分支只校验 MchInfo.state，不查 Redis 熔断态，
     *     单写 Redis 会被绕过。
     *
     * 为什么再写 Redis 标记：
     *   - 解熔断（{@link RiskCircuitBreakerEngine#release} 等路径）需要分辨
     *     "本次停用是自动熔断造成的"还是"运营主动停用的"。
     *     仅前者才能在解除时自动改回 ENABLE；后者必须保持停用，
     *     避免把运营的手工决策悄悄推翻。
     *   - 因为 MchInfo entity 没有 disable_reason 列（核对过 entity，schema 也未扩展），
     *     这里用 Redis 旁路标记代替持久化字段；过期后视为"非自动停用"，安全降级为不自动启用。
     */
    private void autoSuspendMerchant(String mchNo, String reasonText) {
        if (mchNo == null) return;
        try {
            // 1) 翻转 MchInfo.state = CS.NO（0=停用）
            //    用 updateById 而不是新增 updateState 方法，保持 MchInfoService 签名不变。
            MchInfo upd = new MchInfo().setMchNo(mchNo).setState(CS.NO);
            mchInfoService.updateById(upd);

            // 2) 打"自动熔断"旁路标记，供后续 release 判断是否可自动启用
            RedisUtil.setString(KEY_CB_REASON_MCH + mchNo, CB_REASON_AUTO,
                    CB_REASON_TTL_SEC, TimeUnit.SECONDS);

            logger.warn("[Circuit] 商户 {} 已自动停用并标记 AUTO_CIRCUIT_BREAKER，原因：{}",
                    mchNo, reasonText);
        } catch (Exception e) {
            // 联动失败不阻断主流程：告警已发，运营仍可手动处置；落 error 便于排查。
            logger.error("[Circuit] 商户 {} 自动停用联动失败", mchNo, e);
        }
    }

    /**
     * 注意：自动熔断的"解除"动作并不在本类，而由
     * {@link RiskCircuitBreakerEngine#release(String, String, String)} 统一收口
     *（删 Redis 熔断态 + 限流态 + 通知）。
     *
     * 但 release 当前只删 Redis，不会把 MchInfo.state 改回 ENABLE——这是刻意为之：
     *   - 若读到 {@code risk:cb:reason:mch:{mchNo} == AUTO_CIRCUIT_BREAKER}，
     *     说明停用是本引擎自动触发的，可安全改回 {@code CS.YES}；
     *   - 若读不到（被运营手工停用，或标记已过期），保持 {@code state} 不变，
     *     由运营在商户管理页另行启用。
     *
     * 该判断按当前职责切分应由 RiskCircuitBreakerEngine.release 完成，本类不越界。
     * 本类只负责"打上标记"，将"如何使用标记"留给恢复方。
     */

    // ============================================
    // R1：商户日交易额熔断 公共入口
    // ============================================

    /**
     * R1 专用：因"商户当日累计交易额超阈值"触发熔断。
     *
     * 为什么单独开一个 public 入口而不让 {@link MerchantDailyAmountGuard} 直接调
     * {@link #autoSuspendMerchant}：
     *   - {@code autoSuspendMerchant} 的 Redis 标记 TTL 写死 7 天（B-2 的设计前提是"评分维度"
     *     的长期停用），R1 是"30 分钟自动恢复"语义，需要参数化 TTL。
     *   - 不动 B-2 的代码：本方法在其外面再包一层，先写"商户熔断态" key（带 R1 的 TTL，
     *     供路由层 isCircuitBroken 立刻识别并拒单），再复用 {@code autoSuspendMerchant}
     *     完成 MchInfo.state 翻转 + reason 旁路标记。
     *   - 之所以仍然调 autoSuspendMerchant，是因为 schema/旁路标记/state 联动逻辑都在那里写过一次，
     *     单点维护更安全。
     *
     * @param mchNo   商户号
     * @param seconds 熔断时长（秒），R1 默认 1800
     * @param reason  触发原因，写入 Redis 熔断态 snapshot，便于排查
     */
    public void tripMerchantByDailyAmount(String mchNo, long seconds, String reason) {
        if (mchNo == null || seconds <= 0) return;
        try {
            // 1) 写"商户熔断态" key，带 R1 自定义 TTL：过期即自动恢复。
            //    snapshot 字段命名与 RiskCircuitBreakerEngine.buildSnapshot 保持一致，
            //    后台看板/运营查询可以共用一套解析逻辑。
            com.alibaba.fastjson.JSONObject snap = new com.alibaba.fastjson.JSONObject();
            snap.put("action", "SUSPEND");
            snap.put("reason", reason);
            snap.put("triggeredAt", System.currentTimeMillis());
            snap.put("source", "DAILY_AMOUNT_GUARD");
            RedisUtil.setString(KEY_CB_MCH + mchNo, snap.toJSONString(), seconds, TimeUnit.SECONDS);

            // 2) 复用 B-2：翻转 MchInfo.state=CS.NO + 打 AUTO_CIRCUIT_BREAKER 标记
            //    为什么不写在这里：state 与旁路标记的联动逻辑（含失败降级）已经在 B-2 落过，
            //    再写一遍会产生两处维护点。
            autoSuspendMerchant(mchNo, reason);

            logger.warn("[Circuit-R1] 商户 {} 因日交易额超阈熔断 {}s，原因：{}", mchNo, seconds, reason);
        } catch (Exception e) {
            // 熔断动作失败必须吞掉，不让支付主流程异常；状态可在下一笔订单/调度补救。
            logger.error("[Circuit-R1] 商户 {} 日额熔断动作执行失败", mchNo, e);
        }
    }

    /**
     * R1：判断商户当前是否已被熔断（用于 Guard 幂等，避免重复 trip）。
     * 不直接复用 RiskCircuitBreakerEngine.isCircuitBroken，原因：那边把"限流态"
     * 也算作熔断，对 R1 来说限流不应阻止累计，语义偏宽。
     */
    public boolean isMerchantCircuitBroken(String mchNo) {
        if (mchNo == null) return false;
        try {
            return RedisUtil.hasKey(KEY_CB_MCH + mchNo);
        } catch (Exception e) {
            // Redis 异常时倾向"未熔断"——宁可多 trip 一次也不放过真有问题的商户。
            logger.error("[Circuit-R1] 检查熔断态失败 mchNo={}", mchNo, e);
            return false;
        }
    }
}
