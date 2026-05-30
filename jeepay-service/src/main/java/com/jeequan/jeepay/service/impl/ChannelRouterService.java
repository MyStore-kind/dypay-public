/*
 * Copyright (c) 2026, 国际四方支付系统改造项目.
 */
package com.jeequan.jeepay.service.impl;

import com.jeequan.jeepay.core.entity.ChannelAccount;
import com.jeequan.jeepay.core.exception.BizException;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

/**
 * 通道智能路由服务（账号池路由器）
 *
 * 核心职责：从可用账号池中按 6 步策略链选择最优账号
 *
 * 策略链（按顺序执行，前一步过滤通过才进入下一步）：
 *   1. 过滤 state=1 且 health_status IN (1,2)          —— 停用/异常/限流剔除
 *   2. 商户 risk_tier ≤ 账号 risk_tier 承载等级         —— 风险等级匹配
 *   3. MCC 白名单命中 + MCC 黑名单不命中                —— 行业隔离
 *   4. 国家白名单命中 + 国家黑名单不命中                —— 地域隔离
 *   5. 币种白名单命中                                  —— 币种隔离
 *   6. 单笔、日、月额度均充足                          —— 额度隔离
 *
 * 排序规则（多键复合）：
 *   - health_status 升序（1 健康优先于 2 警告）
 *   - chargeback_rate 升序（拒付率低优先）
 *   - priority 降序（运营手动指定的优先级）
 *   - 同优先级支持加权随机（weight 字段）
 *
 * 为什么这么排：先保证不踩雷（健康/拒付率），再尊重运营意图（priority），
 *               最后用加权随机稀释同档账号的流量，避免单账号风控触发。
 *
 * @author 反风控改造组
 */
@Service
public class ChannelRouterService {

    private static final Logger logger = LoggerFactory.getLogger(ChannelRouterService.class);

    @Autowired
    private ChannelAccountService channelAccountService;

    /**
     * 随机数生成器：用于同优先级账号的加权随机选择
     * 为什么不用 SecureRandom：路由选择非安全敏感场景，性能优先
     */
    private final Random random = new Random();

    /**
     * 6 步策略链路由（新签名）
     *
     * @param mchNo       商户号（用于日志/审计，本身不参与决策）
     * @param ifCode      通道编码（stripe/paypal）
     * @param orderAmount 订单金额（单位：分）
     * @param currency    交易币种（ISO 4217 三位码）
     * @param country     消费者国家（ISO 3166-1 alpha-2，可空）
     * @param mccCode     商户 MCC 行业代码（可空）
     * @param merchantTier 商户风险等级 low/mid/high（决策入参）
     * @return 选中的账号
     * @throws BizException 无可用账号
     */
    public ChannelAccount route(String mchNo, String ifCode, Long orderAmount,
                                String currency, String country,
                                String mccCode, String merchantTier) {
        if (StringUtils.isBlank(ifCode)) {
            throw new BizException("通道编码 ifCode 不能为空");
        }
        long amount = orderAmount == null ? 0L : orderAmount;
        String tier = StringUtils.isBlank(merchantTier) ? ChannelAccount.TIER_MID : merchantTier;

        // ===== Step 1: 候选池（state=1 + health_status IN(1,2)）=====
        // 由 ChannelAccountService.listAvailable 在 SQL 层完成，避免内存全表过滤
        List<ChannelAccount> step1 = channelAccountService.listAvailable(ifCode);
        if (step1.isEmpty()) {
            throw new BizException("无可用通道账号");
        }

        // ===== Step 2~6: 内存过滤（账号数量通常 < 10，内存过滤足够高效）=====
        List<ChannelAccount> eligible = new ArrayList<>(step1.size());
        for (ChannelAccount a : step1) {
            if (!tierMatch(a.getRiskTier(), tier)) continue;                              // Step 2
            if (!mccMatch(a, mccCode)) continue;                                          // Step 3
            if (!countryMatch(a, country)) continue;                                      // Step 4
            if (!currencyMatch(a, currency)) continue;                                    // Step 5
            if (!quotaSufficient(a, amount)) continue;                                    // Step 6
            eligible.add(a);
        }
        if (eligible.isEmpty()) {
            // 不带具体原因外抛，避免敏感策略泄漏；调用方可通过日志定位
            logger.warn("[Router] 候选账号被策略链过滤完 mchNo={} ifCode={} amount={} ccy={} country={} mcc={} tier={}",
                    mchNo, ifCode, amount, currency, country, mccCode, tier);
            throw new BizException("无可用通道账号");
        }

        // ===== 排序：health_status ↑ → chargeback_rate ↑ → priority ↓ =====
        eligible.sort(Comparator
                .comparing(ChannelAccount::getHealthStatus, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(ChannelAccount::getChargebackRate, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(ChannelAccount::getPriority, Comparator.nullsLast(Comparator.reverseOrder())));

        // 同优先级（同 health + 同 chargeback + 同 priority）进入加权随机
        ChannelAccount head = eligible.get(0);
        List<ChannelAccount> sameRank = eligible.stream()
                .filter(a -> equalRank(a, head))
                .collect(Collectors.toList());
        ChannelAccount picked = sameRank.size() == 1 ? head : weightedPick(sameRank);

        logger.info("[Router] 选中账号 mchNo={} ifCode={} accountId={} health={} cbRate={} priority={} 候选数={}",
                mchNo, ifCode, picked.getAccountId(), picked.getHealthStatus(),
                picked.getChargebackRate(), picked.getPriority(), eligible.size());
        return picked;
    }

    // ============ 兼容旧签名（早期调用方过渡用） ============

    /**
     * 兼容旧签名：调用方未传 mchNo 时使用
     * 为什么保留：避免破坏二开过程中其它接入点
     */
    public ChannelAccount route(String ifCode, String mccCode, String country,
                                String currency, Long amount, String merchantTier) {
        return route(null, ifCode, amount, currency, country, mccCode, merchantTier);
    }

    // ============ 内部策略实现 ============

    /**
     * Step 2: 风险等级承载匹配
     * 账号 high 承载 high/mid/low；mid 承载 mid/low；low 仅承载 low
     * 为什么这么设计：高风险账号才能消化高风险商户，避免把脏单送进干净账号
     */
    private boolean tierMatch(String accountTier, String merchantTier) {
        if (StringUtils.isBlank(accountTier)) return true; // 未配置视为通用
        int aLevel = tierLevel(accountTier);
        int mLevel = tierLevel(merchantTier);
        return aLevel >= mLevel;
    }

    private int tierLevel(String tier) {
        if (ChannelAccount.TIER_HIGH.equalsIgnoreCase(tier)) return 3;
        if (ChannelAccount.TIER_MID.equalsIgnoreCase(tier)) return 2;
        return 1; // low 或未知
    }

    /** Step 3: MCC 白名单命中 + 黑名单不命中 */
    private boolean mccMatch(ChannelAccount a, String mccCode) {
        if (!inWhitelist(mccCode, a.getMccWhitelist())) return false;
        return !inBlacklist(mccCode, a.getMccBlacklist());
    }

    /** Step 4: 国家白名单命中 + 黑名单不命中 */
    private boolean countryMatch(ChannelAccount a, String country) {
        if (!inWhitelist(country, a.getCountryWhitelist())) return false;
        return !inBlacklist(country, a.getCountryBlacklist());
    }

    /** Step 5: 币种白名单命中（无黑名单字段） */
    private boolean currencyMatch(ChannelAccount a, String currency) {
        return inWhitelist(currency, a.getCurrencyWhitelist());
    }

    /**
     * 白名单命中：空白名单 = 放行所有（设计上不强制运营配置全量）
     * value 为空：白名单非空时判定为不命中（保守策略）
     */
    private boolean inWhitelist(String value, String listStr) {
        if (StringUtils.isBlank(listStr)) return true;          // 未配置视为放行
        if (StringUtils.isBlank(value)) return false;            // 有白名单但值缺失 → 拒绝
        return containsIgnoreCase(listStr, value);
    }

    /** 黑名单命中：空黑名单 = 不命中（不拦截）；value 空 = 不命中 */
    private boolean inBlacklist(String value, String listStr) {
        if (StringUtils.isBlank(listStr) || StringUtils.isBlank(value)) return false;
        return containsIgnoreCase(listStr, value);
    }

    private boolean containsIgnoreCase(String listStr, String value) {
        for (String item : listStr.split(",")) {
            if (value.equalsIgnoreCase(item.trim())) return true;
        }
        return false;
    }

    /**
     * Step 6: 单笔 / 日 / 月额度全部充足
     * 注意：累计额度 + 本单金额 必须 ≤ 上限；为 0 或 null 视为未设置
     */
    private boolean quotaSufficient(ChannelAccount a, long amount) {
        // 单笔
        if (a.getSingleLimitAmount() != null && a.getSingleLimitAmount() > 0
                && amount > a.getSingleLimitAmount()) {
            return false;
        }
        // 日累计
        if (a.getDailyLimitAmount() != null && a.getDailyLimitAmount() > 0) {
            long used = a.getCurrentDailyAmount() == null ? 0L : a.getCurrentDailyAmount();
            if (used + amount > a.getDailyLimitAmount()) return false;
        }
        // 月累计
        if (a.getMonthlyLimitAmount() != null && a.getMonthlyLimitAmount() > 0) {
            long used = a.getCurrentMonthlyAmount() == null ? 0L : a.getCurrentMonthlyAmount();
            if (used + amount > a.getMonthlyLimitAmount()) return false;
        }
        return true;
    }

    /**
     * 判断两个账号是否处于同一排序档位（用于触发加权随机）
     */
    private boolean equalRank(ChannelAccount x, ChannelAccount y) {
        return safeEq(x.getHealthStatus(), y.getHealthStatus())
                && safeBdEq(x.getChargebackRate(), y.getChargebackRate())
                && safeEq(x.getPriority(), y.getPriority());
    }

    private boolean safeEq(Object a, Object b) {
        return a == null ? b == null : a.equals(b);
    }

    private boolean safeBdEq(java.math.BigDecimal a, java.math.BigDecimal b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.compareTo(b) == 0;
    }

    /**
     * 加权随机选择
     * weight 缺省 100；总权重 ≤ 0 时退化为取第一个
     */
    private ChannelAccount weightedPick(List<ChannelAccount> list) {
        int totalWeight = list.stream()
                .mapToInt(a -> a.getWeight() == null ? 100 : Math.max(0, a.getWeight()))
                .sum();
        if (totalWeight <= 0) return list.get(0);
        int pick = random.nextInt(totalWeight);
        int cursor = 0;
        for (ChannelAccount a : list) {
            cursor += (a.getWeight() == null ? 100 : Math.max(0, a.getWeight()));
            if (pick < cursor) return a;
        }
        return list.get(0);
    }
}
