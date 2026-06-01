/*
 * Copyright (c) 2026, 国际四方支付系统改造项目.
 */
package com.jeequan.jeepay.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.jeequan.jeepay.core.entity.RiskBlacklist;
import com.jeequan.jeepay.service.mapper.RiskBlacklistMapper;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Date;

/**
 * 风险黑名单——临时冻结门面（R3 等自动规则使用）。
 *
 * 设计要点（为什么不复用现有 BlacklistService）：
 * - BlacklistService.add 是"永久/运营手动"语义，不会处理唯一键冲突
 * - 自动规则（如 Stripe EFW）会就同一个 BIN 反复触发，必须实现幂等：
 *   首次写入 + 后续 UPDATE 延长 expire_at（而不是抛 DuplicateKeyException）
 * - t_risk_blacklist 的 UNIQUE KEY 是 (list_type, list_value)，因此用先查后写/更新策略
 *
 * 与 BlacklistService 的边界：
 * - BlacklistService：运营手动维护，主要负责 add/check（永久）
 * - RiskBlacklistService：自动规则写入，负责 addTemporary（带 TTL，可延长）
 *
 * 注意：本类与 BlacklistService 都基于 RiskBlacklistMapper，但 MyBatis-Plus 允许
 * 同一 Mapper 被多个 ServiceImpl 使用（仅是 CRUD 工具类，不是单例 bean 冲突）。
 *
 * @author 反风控改造组
 */
@Service
public class RiskBlacklistService extends ServiceImpl<RiskBlacklistMapper, RiskBlacklist> {

    private static final Logger logger = LoggerFactory.getLogger(RiskBlacklistService.class);

    /**
     * 添加/续期一条带 TTL 的临时黑名单条目（幂等）。
     *
     * 行为：
     * - 不存在 -> INSERT，expire_at = now + ttlMinutes
     * - 已存在 -> 仅 UPDATE expire_at（延长），并把 reason / source 覆盖为最新一次的来源信息
     *   为什么覆盖：自动规则的"最近触发原因"对运营更有价值
     * - state 若被运营手动停用过，本方法也会恢复为启用（state=1）；
     *   为什么：自动规则又一次命中说明风险仍存在，应继续生效
     *
     * 调用方应保证 listType / value 已规范化（如卡 BIN 取前 6 位、去空白）。
     *
     * @param listType   黑名单类型（参见 RiskBlacklist.TYPE_*）
     * @param value      黑名单值
     * @param ttlMinutes 过期分钟数，<=0 时按 1 分钟兜底（避免立即失效或永久）
     * @param source     来源标识（如 "stripe_efw"）
     * @param reason     原因（建议带可回溯 ID，如 "stripe_efw:efw_xxx"）
     * @return true=成功（新增或续期），false=参数非法
     */
    public boolean addTemporary(String listType, String value, int ttlMinutes,
                                String source, String reason) {
        if (StringUtils.isBlank(listType) || StringUtils.isBlank(value)) {
            logger.warn("[RiskBlacklistService] addTemporary 参数为空 listType={} value={}", listType, value);
            return false;
        }
        int safeMinutes = ttlMinutes > 0 ? ttlMinutes : 1;
        Date now = new Date();
        Date newExpireAt = new Date(now.getTime() + safeMinutes * 60_000L);

        // 唯一键 (list_type, list_value) —— 先查后写/更新，避免依赖 DB 抛重复键再补救
        RiskBlacklist exist = getOne(RiskBlacklist.gw()
                .eq(RiskBlacklist::getListType, listType)
                .eq(RiskBlacklist::getListValue, value)
                .last("LIMIT 1"));

        if (exist == null) {
            RiskBlacklist row = new RiskBlacklist()
                    .setListType(listType)
                    .setListValue(value)
                    .setReason(reason)
                    .setSource(source)
                    .setHitCount(0)
                    .setExpireAt(newExpireAt)
                    .setState((byte) 1)
                    .setCreatedBy(source);
            try {
                return save(row);
            } catch (Exception e) {
                // 极端情况下两个并发线程都走到 INSERT 分支 -> 唯一键冲突；
                // 兜底：再做一次 UPDATE 续期，保证幂等
                logger.warn("[RiskBlacklistService] 并发插入冲突，转为续期 listType={} value={}", listType, value);
                return extendExpire(listType, value, newExpireAt, source, reason);
            }
        }

        // 已存在：仅在 newExpireAt 更晚时才覆盖，避免短 TTL 把长 TTL 缩短
        Date oldExpire = exist.getExpireAt();
        Date finalExpire = (oldExpire == null || oldExpire.after(newExpireAt)) ? oldExpire : newExpireAt;
        // 但永久（NULL）记录不被本方法影响 —— 永久优于临时
        if (oldExpire == null) {
            // 仍然把命中信息记进去（最近 source/reason），但不动 expire_at
            exist.setReason(reason);
            exist.setSource(source);
            exist.setState((byte) 1);
            return updateById(exist);
        }
        exist.setExpireAt(finalExpire);
        exist.setReason(reason);
        exist.setSource(source);
        exist.setState((byte) 1);
        return updateById(exist);
    }

    /** 单纯延长过期时间（仅作为并发兜底使用） */
    private boolean extendExpire(String listType, String value, Date newExpireAt,
                                 String source, String reason) {
        RiskBlacklist exist = getOne(RiskBlacklist.gw()
                .eq(RiskBlacklist::getListType, listType)
                .eq(RiskBlacklist::getListValue, value)
                .last("LIMIT 1"));
        if (exist == null) return false;
        Date oldExpire = exist.getExpireAt();
        if (oldExpire != null && oldExpire.before(newExpireAt)) {
            exist.setExpireAt(newExpireAt);
        }
        exist.setReason(reason);
        exist.setSource(source);
        exist.setState((byte) 1);
        return updateById(exist);
    }

    /**
     * 校验是否命中生效中的黑名单。
     *
     * TODO(R4)：本方法将作为订单 pre-check 的统一查询入口；R3 暂不强依赖。
     * 当前给出占位实现，便于 R4 接入时直接替换为带通配（如邮箱后缀 *@xxx）的实现。
     */
    public boolean isBlocked(String listType, String value) {
        if (StringUtils.isBlank(listType) || StringUtils.isBlank(value)) return false;
        Date now = new Date();
        RiskBlacklist r = getOne(RiskBlacklist.gw()
                .eq(RiskBlacklist::getListType, listType)
                .eq(RiskBlacklist::getListValue, value)
                .eq(RiskBlacklist::getState, 1)
                .and(w -> w.isNull(RiskBlacklist::getExpireAt).or().gt(RiskBlacklist::getExpireAt, now))
                .last("LIMIT 1"));
        return r != null;
    }
}
