/*
 * Copyright (c) 2026, 国际四方支付系统改造项目.
 */
package com.jeequan.jeepay.service.impl;

import com.jeequan.jeepay.core.cache.RedisUtil;
import com.jeequan.jeepay.core.entity.MchInfo;
import com.jeequan.jeepay.core.entity.PayOrder;
import com.jeequan.jeepay.core.utils.SpringBeansUtil;
import com.jeequan.jeepay.service.impl.PaySuccessEventListener.PaySuccessEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.TimeUnit;

/**
 * R1 商户日交易额自动熔断守卫
 *
 * 设计思路：
 * - 走 Spring 事件订阅（兄弟监听器 {@link PaySuccessEventListener}），
 *   不动 jeepay-payment 主链路；
 * - 累计放在 Redis INCRBY 上做单原子操作，跨进程也只会有一次跨阈值触发；
 * - 阈值优先取商户级覆盖（{@link MchInfo#getDailyAmountThresholdUsd()}），
 *   NULL/0 时回落到 KV 配置 {@code merchant.daily_amount.threshold_usd}；
 * - 熔断动作交给 {@link CircuitBreakerEngine#tripMerchantByDailyAmount}，
 *   Redis 熔断态 + MchInfo.state + reason 标记统一在那里收口；
 * - 全过程 try/catch 包住，任何异常都不外抛，避免拖垮支付主流程
 *   （PaySuccessEventListener 的兄弟 handler 也是这种姿势）。
 *
 * Redis Key：
 *   mch:daily_amount:{mchNo}:{yyyyMMdd}  —— 商户当日累计金额（USD 分），TTL 36h
 *
 * 折算策略：
 *   优先用项目里已有的 {@link CurrencyRateService#convertAmount}（汇率库 + Redis 缓存）；
 *   汇率缺失时退而求其次按 1:1 折算（带 WARN 日志），保证守卫"能跑过"，
 *   不会因为某一对币种暂时没有汇率就让风控失效。
 *
 * @author 反风控改造组
 */
@Component
public class MerchantDailyAmountGuard {

    private static final Logger logger = LoggerFactory.getLogger(MerchantDailyAmountGuard.class);

    /** 累计 Redis key 前缀（与 CLAUDE.md 中 risk:* 区分；这是"统计"语义，非熔断态） */
    private static final String KEY_DAILY_AMOUNT_PREFIX = "mch:daily_amount:";

    /** 累计 key TTL：36h。设计意图——日切后保留 12h 便于排查，过期自动清，无需调度任务 */
    private static final long DAILY_AMOUNT_TTL_SECONDS = 36L * 3600;

    /** USD 单位换算：1 USD = 100 分 */
    private static final BigDecimal HUNDRED = new BigDecimal(100);

    /** 默认熔断时长（秒），KV 解析失败时回落 */
    private static final long DEFAULT_CIRCUIT_SECONDS = 1800L;

    /** 默认阈值（USD），KV 与商户级双双缺失时回落（=50 万美金） */
    private static final BigDecimal DEFAULT_THRESHOLD_USD = new BigDecimal("500000");

    @Autowired private RiskThresholdConfigService thresholdConfig;
    @Autowired private CircuitBreakerEngine circuitBreakerEngine;
    @Autowired private CurrencyRateService currencyRateService;
    @Autowired private MchInfoService mchInfoService;

    /**
     * 监听支付成功事件，做"按 USD 累计 → 过阈触发熔断"。
     *
     * 为什么 @Async：
     *   风控统计不在支付主路径的强一致语义里——延迟几百毫秒不影响对账，
     *   但同步等 Redis/DB 会拖慢回调响应；用线程池异步分流。
     *   （与 {@link PaySuccessEventListener#handleAgentProfit} 同款姿势）
     */
    @Async
    @EventListener
    public void onPaySuccess(PaySuccessEvent event) {
        try {
            // R1 总开关：运营可在不重启的情况下临时全局关停
            if (!thresholdConfig.getBoolean("merchant.daily_amount.enabled", true)) {
                return;
            }

            PayOrder order = event.getPayOrder();
            if (order == null || order.getMchNo() == null
                    || order.getAmount() == null || order.getAmount() <= 0) {
                return;
            }
            String mchNo = order.getMchNo();

            // 幂等：当日已熔断的商户，停止再触发，但不停止累计——
            // 累计继续推进能保留"熔断期间又跑了多少额"的审计数据，
            // 同时避免每笔订单都重复打日志 / 重复写熔断态。
            boolean alreadyTripped = circuitBreakerEngine.isMerchantCircuitBroken(mchNo);

            // 折算为 USD 分
            long amountUsdCents = convertToUsdCents(order.getAmount(), order.getCurrency());
            if (amountUsdCents <= 0) {
                return;
            }

            // 累计：Redis 原子 INCRBY，跨实例并发安全
            String key = buildDailyKey(mchNo);
            long current = incrAndEnsureTtl(key, amountUsdCents);

            if (alreadyTripped) {
                // 已熔断：仅累计、不再判定阈值，避免无意义日志
                return;
            }

            // 阈值取值：商户级覆盖优先，NULL/0 回落 KV
            BigDecimal thresholdUsd = resolveThresholdUsd(mchNo);
            long thresholdCents = thresholdUsd
                    .multiply(HUNDRED)
                    .setScale(0, RoundingMode.HALF_UP)
                    .longValueExact();

            if (current >= thresholdCents) {
                long seconds = resolveCircuitSeconds(mchNo);
                String reason = "DAILY_AMOUNT:" + toUsdString(current)
                        + ">" + toUsdString(thresholdCents);
                circuitBreakerEngine.tripMerchantByDailyAmount(mchNo, seconds, reason);
                logger.warn("[R1] 商户 {} 日累计 {} USD 超阈值 {} USD，已触发熔断 {}s",
                        mchNo, toUsdString(current), toUsdString(thresholdCents), seconds);
            }
        } catch (Exception e) {
            // 任何异常吞掉：风控守卫不能反过来打断支付回调链路
            String payOrderId = event.getPayOrder() == null ? "?" : event.getPayOrder().getPayOrderId();
            logger.error("[R1] 商户日额熔断守卫处理异常 payOrderId={}", payOrderId, e);
        }
    }

    // ============================================
    // 内部辅助
    // ============================================

    /**
     * 折算订单金额（订单原币种、分）到 USD 分。
     *
     * 优先用 {@link CurrencyRateService#convertAmount}：
     *   - 已有 Redis 缓存（rate:{base}:{target} TTL 1h）+ DB 回退 + 反向汇率倒数兜底；
     *   - 同币种走快速返回，无开销。
     * 兜底：汇率缺失 / 抛 BizException 时按 1:1 折算，并打 WARN：
     *   宁可"统计偏低"也不让单笔失败把整个风控守卫拖崩。
     *   TODO 接入 Fixer/Stripe 拉取后，应改为严格失败，避免长期欺骗性低估。
     */
    private long convertToUsdCents(Long amount, String currency) {
        if (amount == null) return 0L;
        if (currency == null || currency.isEmpty() || "USD".equalsIgnoreCase(currency)) {
            return amount;
        }
        try {
            Long converted = currencyRateService.convertAmount(amount, currency, "USD");
            return converted == null ? 0L : converted;
        } catch (Exception e) {
            // 1:1 降级——避免单笔订单缺汇率就让 Guard 沉默
            logger.warn("[R1] 汇率折算失败，按 1:1 降级处理 currency={} amount={} err={}",
                    currency, amount, e.getMessage());
            return amount;
        }
    }

    /**
     * INCRBY 累计金额并补 TTL。
     *
     * 为什么手动补 TTL：
     *   Redis 的 INCRBY 不会自动设过期；首次创建 key 后必须立刻 EXPIRE，
     *   否则一旦中间走另一条没补 TTL 的分支，key 就会变永久驻留。
     *   这里取巧：只在 INCRBY 之后判断 getExpire == -1 再 expire，
     *   避免每次都覆盖 TTL（覆盖会导致活跃商户的 key 永远不过期）。
     */
    private long incrAndEnsureTtl(String key, long delta) {
        StringRedisTemplate template = SpringBeansUtil.getBean(StringRedisTemplate.class);
        Long after = template.opsForValue().increment(key, delta);
        long current = after == null ? 0L : after;
        try {
            long ttl = RedisUtil.getExpire(key);
            // -1 表示无过期；-2 表示 key 不存在（理论上 INCRBY 后不会出现）
            if (ttl == -1L) {
                RedisUtil.expire(key, DAILY_AMOUNT_TTL_SECONDS, TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            // TTL 设置失败不影响累计本身——key 会在 redis maxmemory 策略下被自然清理
            logger.warn("[R1] 累计 key 设置 TTL 失败 key={}", key, e);
        }
        return current;
    }

    /**
     * 取阈值（USD 单位）：商户级覆盖 > 全局 KV > 硬编码兜底。
     */
    private BigDecimal resolveThresholdUsd(String mchNo) {
        try {
            MchInfo mch = mchInfoService.getById(mchNo);
            if (mch != null && mch.getDailyAmountThresholdUsd() != null
                    && mch.getDailyAmountThresholdUsd().signum() > 0) {
                return mch.getDailyAmountThresholdUsd();
            }
        } catch (Exception e) {
            // 取商户信息失败按全局值兜底，不影响风控判定
            logger.warn("[R1] 读取商户级阈值失败 mchNo={}", mchNo, e);
        }
        return thresholdConfig.getNumber("merchant.daily_amount.threshold_usd",
                DEFAULT_THRESHOLD_USD);
    }

    /**
     * 取熔断时长（秒）：商户级覆盖 > 全局 KV > 硬编码兜底 1800。
     */
    private long resolveCircuitSeconds(String mchNo) {
        try {
            MchInfo mch = mchInfoService.getById(mchNo);
            if (mch != null && mch.getDailyAmountCircuitSeconds() != null
                    && mch.getDailyAmountCircuitSeconds() > 0) {
                return mch.getDailyAmountCircuitSeconds();
            }
        } catch (Exception e) {
            logger.warn("[R1] 读取商户级熔断时长失败 mchNo={}", mchNo, e);
        }
        BigDecimal v = thresholdConfig.getNumber("merchant.daily_amount.circuit_seconds", null);
        return v == null ? DEFAULT_CIRCUIT_SECONDS : v.longValue();
    }

    /**
     * 拼累计 key：mch:daily_amount:{mchNo}:{yyyyMMdd}
     * 取 JVM 默认时区——与既有 risk_control_patch 的统计粒度一致，
     * 跨时区结算的商户后续要单独切配置时可在这里加 ZoneId 维度。
     */
    private String buildDailyKey(String mchNo) {
        return KEY_DAILY_AMOUNT_PREFIX + mchNo + ":"
                + new SimpleDateFormat("yyyyMMdd").format(new Date());
    }

    /** 把 USD 分转成可读字符串（保留 2 位小数），仅用于日志/snapshot reason */
    private String toUsdString(long cents) {
        return new BigDecimal(cents).divide(HUNDRED, 2, RoundingMode.HALF_UP).toPlainString();
    }
}
