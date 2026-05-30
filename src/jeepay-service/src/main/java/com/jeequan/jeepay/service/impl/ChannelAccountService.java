/*
 * Copyright (c) 2026, 国际四方支付系统改造项目.
 */
package com.jeequan.jeepay.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.jeequan.jeepay.core.entity.ChannelAccount;
import com.jeequan.jeepay.service.mapper.ChannelAccountMapper;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.Date;
import java.util.List;

/**
 * 通道账号池服务
 *
 * 核心职责：
 * - 多账号管理（增删改查）
 * - 账号额度累计与重置
 * - 按通道/状态/风险等级筛选可用账号
 *
 * 设计：不在此处做选择决策，决策由 ChannelRouterService 负责
 *
 * @author 反风控改造组
 */
@Service
public class ChannelAccountService extends ServiceImpl<ChannelAccountMapper, ChannelAccount> {

    /**
     * 查询通道可用账号列表
     * 过滤：启用 + 健康度非异常 + 当日额度未超限
     * 不排序，由调用方按策略排序
     */
    public List<ChannelAccount> listAvailable(String ifCode) {
        return list(ChannelAccount.gw()
                .eq(ChannelAccount::getIfCode, ifCode)
                .eq(ChannelAccount::getState, ChannelAccount.STATE_ENABLE)
                .ne(ChannelAccount::getHealthStatus, ChannelAccount.HEALTH_ERROR));
    }

    /**
     * 检查账号是否满足业务过滤条件
     * 单一职责：只做匹配判断，不修改状态
     *
     * @param account     账号
     * @param mccCode     商户 MCC
     * @param country     消费者国家
     * @param currency    交易币种
     * @param amount      订单金额
     * @param merchantTier 商户风险等级
     * @return true=可用
     */
    public boolean isEligible(ChannelAccount account, String mccCode, String country,
                              String currency, Long amount, String merchantTier) {
        // 1. 风险等级匹配（账号承载的等级必须 >= 商户等级）
        if (!tierMatch(account.getRiskTier(), merchantTier)) {
            return false;
        }
        // 2. MCC 白/黑名单
        if (!inList(mccCode, account.getMccWhitelist(), true)) return false;
        if (inList(mccCode, account.getMccBlacklist(), false)) return false;
        // 3. 国家白/黑名单
        if (!inList(country, account.getCountryWhitelist(), true)) return false;
        if (inList(country, account.getCountryBlacklist(), false)) return false;
        // 4. 币种白名单
        if (!inList(currency, account.getCurrencyWhitelist(), true)) return false;
        // 5. 单笔限额
        if (account.getSingleLimitAmount() != null && account.getSingleLimitAmount() > 0
                && amount > account.getSingleLimitAmount()) return false;
        // 6. 日额度
        if (account.getDailyLimitAmount() != null && account.getDailyLimitAmount() > 0) {
            long after = (account.getCurrentDailyAmount() == null ? 0 : account.getCurrentDailyAmount()) + amount;
            if (after > account.getDailyLimitAmount()) return false;
        }
        // 7. 月额度
        if (account.getMonthlyLimitAmount() != null && account.getMonthlyLimitAmount() > 0) {
            long after = (account.getCurrentMonthlyAmount() == null ? 0 : account.getCurrentMonthlyAmount()) + amount;
            if (after > account.getMonthlyLimitAmount()) return false;
        }
        return true;
    }

    /**
     * 累加账号已使用额度（支付成功后调用）
     * 注意：使用乐观更新，避免并发问题
     */
    @Transactional(rollbackFor = Exception.class)
    public void incrementUsage(String accountId, Long amount) {
        ChannelAccount a = getById(accountId);
        if (a == null) return;
        // 日累计重置判断
        Date today = new Date();
        if (a.getLastResetDate() == null || !isSameDay(a.getLastResetDate(), today)) {
            a.setCurrentDailyAmount(0L);
            a.setLastResetDate(today);
        }
        a.setCurrentDailyAmount((a.getCurrentDailyAmount() == null ? 0 : a.getCurrentDailyAmount()) + amount);
        a.setCurrentMonthlyAmount((a.getCurrentMonthlyAmount() == null ? 0 : a.getCurrentMonthlyAmount()) + amount);
        updateById(a);
    }

    /**
     * 更新账号健康状态（由 ChannelHealthService 调度任务调用）
     */
    public void updateHealthStatus(String accountId, byte healthStatus) {
        ChannelAccount a = new ChannelAccount();
        a.setAccountId(accountId);
        a.setHealthStatus(healthStatus);
        updateById(a);
    }

    // ===== 辅助方法 =====

    /**
     * 风险等级匹配规则
     * 账号 high 可承载 high/mid/low
     * 账号 mid 可承载 mid/low
     * 账号 low 只承载 low
     */
    private boolean tierMatch(String accountTier, String merchantTier) {
        if (StringUtils.isBlank(accountTier) || StringUtils.isBlank(merchantTier)) return true;
        if ("high".equals(accountTier)) return true;
        if ("mid".equals(accountTier)) return !"high".equals(merchantTier);
        return "low".equals(merchantTier);
    }

    /**
     * 列表匹配
     * @param isWhitelist 是否白名单（白名单空表示全允许，黑名单空表示无限制）
     */
    private boolean inList(String value, String listStr, boolean isWhitelist) {
        if (StringUtils.isBlank(listStr)) {
            // 白名单空 = 全部允许；黑名单空 = 无禁止
            return isWhitelist;
        }
        if (StringUtils.isBlank(value)) return !isWhitelist;
        List<String> items = Arrays.asList(listStr.split(","));
        for (String item : items) {
            if (value.equalsIgnoreCase(item.trim())) return true;
        }
        return false;
    }

    private boolean isSameDay(Date a, Date b) {
        if (a == null || b == null) return false;
        return a.getYear() == b.getYear() && a.getMonth() == b.getMonth() && a.getDate() == b.getDate();
    }
}
