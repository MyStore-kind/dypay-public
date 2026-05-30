/*
 * Copyright (c) 2026, 国际四方支付系统改造项目.
 */
package com.jeequan.jeepay.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.jeequan.jeepay.core.entity.RiskBlacklist;
import com.jeequan.jeepay.service.mapper.RiskBlacklistMapper;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.Optional;

/**
 * 风险黑名单服务
 *
 * 设计：
 * - 不缓存（业务量大时改 Redis）
 * - 按类型分别查询，避免全表扫描
 * - 命中后自动累加 hit_count
 *
 * @author 反风控改造组
 */
@Service
public class BlacklistService extends ServiceImpl<RiskBlacklistMapper, RiskBlacklist> {

    /**
     * 检查指定值是否在黑名单中
     *
     * @return Optional 包装的黑名单记录，存在=命中
     */
    public Optional<RiskBlacklist> check(String listType, String listValue) {
        if (StringUtils.isBlank(listValue)) return Optional.empty();
        Date now = new Date();
        RiskBlacklist r = getOne(RiskBlacklist.gw()
                .eq(RiskBlacklist::getListType, listType)
                .eq(RiskBlacklist::getListValue, listValue)
                .eq(RiskBlacklist::getState, 1)
                .and(w -> w.isNull(RiskBlacklist::getExpireAt).or().gt(RiskBlacklist::getExpireAt, now))
                .last("LIMIT 1"));
        if (r != null) {
            // 异步记录命中（这里直接同步，量大时改异步）
            r.setHitCount((r.getHitCount() == null ? 0 : r.getHitCount()) + 1);
            r.setLastHitAt(now);
            updateById(r);
        }
        return Optional.ofNullable(r);
    }

    /**
     * 添加黑名单（运营或自动规则调用）
     */
    public RiskBlacklist add(String listType, String listValue, String reason, String source, String createdBy) {
        RiskBlacklist r = new RiskBlacklist()
                .setListType(listType)
                .setListValue(listValue)
                .setReason(reason)
                .setSource(source)
                .setCreatedBy(createdBy)
                .setState((byte) 1)
                .setHitCount(0);
        save(r);
        return r;
    }
}
