/*
 * Copyright (c) 2026, 国际四方支付系统改造项目.
 */
package com.jeequan.jeepay.service.impl;

import com.alibaba.fastjson.JSONObject;
import com.jeequan.jeepay.core.cache.RedisUtil;
import com.jeequan.jeepay.core.entity.ChannelAccount;
import com.jeequan.jeepay.core.entity.MchInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.concurrent.TimeUnit;

/**
 * 熔断 / 降流引擎（任务 #18）
 *
 * 与已有 {@link CircuitBreakerEngine} 的关系：
 * - {@link CircuitBreakerEngine}：负责"扫描指标 → 比对阈值 → 调用本引擎"的判定逻辑
 * - {@link RiskCircuitBreakerEngine}：负责"执行动作 → 写 Redis → 查询 / 解除熔断"的状态机
 * 拆分目的：让动作执行与阈值比对解耦，便于在订单钩子、调度、回调等多处直接触发。
 *
 * 4 种动作：
 *   - ALERT          仅写告警，调用 NotificationService（无熔断态）
 *   - THROTTLE       限速，Redis key risk:throttle:mch:{mchNo} TTL=1h
 *   - SWITCH_CHANNEL 通道账号 health_status=3 (HEALTH_LIMITED)，触发路由重选
 *   - SUSPEND        商户 / 账号置为 state=2（FROZEN），需运营手动解除
 *
 * Redis Key 规范：
 *   risk:cb:mch:{mchNo}        商户熔断态
 *   risk:cb:account:{accountId} 通道账号熔断态
 *   risk:throttle:mch:{mchNo}   商户限流标记
 *
 * @author 反风控改造组
 */
@Service
public class RiskCircuitBreakerEngine {

    private static final Logger logger = LoggerFactory.getLogger(RiskCircuitBreakerEngine.class);

    // ===== 动作枚举 =====
    public static final String ACTION_ALERT          = "ALERT";
    public static final String ACTION_THROTTLE       = "THROTTLE";
    public static final String ACTION_SWITCH_CHANNEL = "SWITCH_CHANNEL";
    public static final String ACTION_SUSPEND        = "SUSPEND";

    // ===== 目标类型 =====
    public static final String TARGET_MERCHANT = "MERCHANT";
    public static final String TARGET_ACCOUNT  = "ACCOUNT";

    // ===== Redis Key 前缀 =====
    private static final String KEY_CB_MCH       = "risk:cb:mch:";
    private static final String KEY_CB_ACCOUNT   = "risk:cb:account:";
    private static final String KEY_THROTTLE_MCH = "risk:throttle:mch:";

    /** 限流 TTL：1 小时（与设计一致） */
    private static final long THROTTLE_TTL_SECONDS = 3600L;
    /** 熔断态默认 TTL：7 天，过期后强制重新评估 */
    private static final long CB_TTL_SECONDS = 7L * 24 * 3600;

    @Autowired private ChannelAccountService channelAccountService;
    @Autowired private MchInfoService mchInfoService;
    @Autowired private NotificationService notificationService;

    // ============================================
    // 1. 动作触发
    // ============================================

    /**
     * 触发商户级别熔断 / 降流
     *
     * @param mchNo    商户号
     * @param action   动作类型
     * @param reason   触发原因（写入 Redis snapshot）
     * @param metrics  触发时的指标快照（如 chargebackRate、riskScore）
     */
    public void triggerForMerchant(String mchNo, String action, String reason, JSONObject metrics) {
        if (mchNo == null || action == null) return;
        logger.warn("[CB] 触发商户熔断 mchNo={} action={} reason={}", mchNo, action, reason);

        // 始终发出告警通知，便于运营第一时间感知
        notificationService.notify(
                "商户风险熔断 [" + action + "]",
                String.format("商户 %s 触发 %s，原因：%s", mchNo, action, reason));

        switch (action) {
            case ACTION_ALERT:
                // 仅告警，不落 Redis 熔断态
                break;

            case ACTION_THROTTLE:
                // 写限流 key（路由层据此降流，限制 QPS）
                RedisUtil.setString(KEY_THROTTLE_MCH + mchNo, buildSnapshot(action, reason, metrics),
                        THROTTLE_TTL_SECONDS);
                writeCbState(KEY_CB_MCH + mchNo, action, reason, metrics);
                break;

            case ACTION_SUSPEND:
                writeCbState(KEY_CB_MCH + mchNo, action, reason, metrics);
                // state=2 冻结，需运营手动解除
                try {
                    MchInfo upd = new MchInfo().setMchNo(mchNo).setState((byte) 2);
                    mchInfoService.updateById(upd);
                } catch (Exception e) {
                    logger.error("[CB] 商户冻结失败 mchNo={}", mchNo, e);
                }
                break;

            case ACTION_SWITCH_CHANNEL:
                // 商户维度的"切通道"实质上是触发下一次路由重选，仅写状态供路由识别
                writeCbState(KEY_CB_MCH + mchNo, action, reason, metrics);
                break;

            default:
                logger.warn("[CB] 未知动作 mchNo={} action={}", mchNo, action);
        }
    }

    /**
     * 触发通道账号级别熔断
     */
    public void triggerForAccount(String accountId, String action, String reason, JSONObject metrics) {
        if (accountId == null || action == null) return;
        logger.warn("[CB] 触发账号熔断 accountId={} action={} reason={}", accountId, action, reason);

        notificationService.notify(
                "通道账号风险熔断 [" + action + "]",
                String.format("账号 %s 触发 %s，原因：%s", accountId, action, reason));

        switch (action) {
            case ACTION_ALERT:
                break;

            case ACTION_SWITCH_CHANNEL:
                // health_status=3 (HEALTH_LIMITED)，路由器据此剔除并重选
                writeCbState(KEY_CB_ACCOUNT + accountId, action, reason, metrics);
                try {
                    channelAccountService.updateHealthStatus(accountId, ChannelAccount.HEALTH_LIMITED);
                } catch (Exception e) {
                    logger.error("[CB] 账号健康度更新失败 accountId={}", accountId, e);
                }
                break;

            case ACTION_THROTTLE:
                // 账号维度限流：直接降级健康度，路由层减小权重
                writeCbState(KEY_CB_ACCOUNT + accountId, action, reason, metrics);
                try {
                    channelAccountService.updateHealthStatus(accountId, ChannelAccount.HEALTH_WARNING);
                } catch (Exception e) {
                    logger.error("[CB] 账号限流失败 accountId={}", accountId, e);
                }
                break;

            case ACTION_SUSPEND:
                writeCbState(KEY_CB_ACCOUNT + accountId, action, reason, metrics);
                try {
                    ChannelAccount upd = new ChannelAccount()
                            .setAccountId(accountId)
                            .setState(ChannelAccount.STATE_FROZEN);
                    channelAccountService.updateById(upd);
                } catch (Exception e) {
                    logger.error("[CB] 账号冻结失败 accountId={}", accountId, e);
                }
                break;

            default:
                logger.warn("[CB] 未知动作 accountId={} action={}", accountId, action);
        }
    }

    // ============================================
    // 2. 查询接口
    // ============================================

    /** 商户是否处于熔断态（含限流）。订单创建前调用 */
    public boolean isCircuitBroken(String mchNo) {
        if (mchNo == null) return false;
        return RedisUtil.hasKey(KEY_CB_MCH + mchNo) || RedisUtil.hasKey(KEY_THROTTLE_MCH + mchNo);
    }

    /** 仅判断是否被限流（路由层据此做 QPS 限制） */
    public boolean isThrottled(String mchNo) {
        if (mchNo == null) return false;
        return RedisUtil.hasKey(KEY_THROTTLE_MCH + mchNo);
    }

    /** 通道账号是否处于熔断态。路由层调用 */
    public boolean isAccountCircuitBroken(String accountId) {
        if (accountId == null) return false;
        return RedisUtil.hasKey(KEY_CB_ACCOUNT + accountId);
    }

    /** 读取熔断快照（含原因、触发时间、指标），运营查看时用 */
    public String getSnapshot(String targetType, String targetId) {
        String key = resolveKey(targetType, targetId);
        return key == null ? null : RedisUtil.getString(key);
    }

    // ============================================
    // 3. 运营手动解除
    // ============================================

    /**
     * 运营手动解除熔断
     * 注意：state 字段若已为 frozen 不会自动恢复，需运营在商户 / 账号管理页另行启用
     *
     * @param targetType MERCHANT / ACCOUNT
     * @param targetId   商户号 / 账号 ID
     * @param operator   操作人（写日志）
     */
    public boolean release(String targetType, String targetId, String operator) {
        if (targetType == null || targetId == null) return false;
        String key = resolveKey(targetType, targetId);
        if (key == null) return false;

        // 删除熔断态与可能存在的限流 key
        RedisUtil.del(key);
        if (TARGET_MERCHANT.equalsIgnoreCase(targetType)) {
            RedisUtil.del(KEY_THROTTLE_MCH + targetId);
        }

        logger.warn("[CB] 运营手动解除熔断 type={} id={} operator={}", targetType, targetId, operator);
        notificationService.notify(
                "风险熔断已解除",
                String.format("操作人 %s 已解除 %s [%s] 的熔断", operator, targetType, targetId));
        return true;
    }

    // ============================================
    // 4. 内部辅助
    // ============================================

    private String resolveKey(String targetType, String targetId) {
        if (TARGET_MERCHANT.equalsIgnoreCase(targetType)) return KEY_CB_MCH + targetId;
        if (TARGET_ACCOUNT.equalsIgnoreCase(targetType))  return KEY_CB_ACCOUNT + targetId;
        return null;
    }

    /** 写熔断态 + snapshot（含原因 + 触发时间 + 指标快照） */
    private void writeCbState(String key, String action, String reason, JSONObject metrics) {
        try {
            RedisUtil.setString(key, buildSnapshot(action, reason, metrics), CB_TTL_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            // Redis 失败不阻塞主流程
            logger.error("[CB] 写 Redis 熔断态失败 key={}", key, e);
        }
    }

    private String buildSnapshot(String action, String reason, JSONObject metrics) {
        JSONObject snap = new JSONObject();
        snap.put("action", action);
        snap.put("reason", reason);
        snap.put("triggeredAt", new Date().getTime());
        snap.put("metrics", metrics == null ? new JSONObject() : metrics);
        return snap.toJSONString();
    }
}
