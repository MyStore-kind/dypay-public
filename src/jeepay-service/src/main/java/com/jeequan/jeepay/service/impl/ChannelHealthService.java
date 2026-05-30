/*
 * Copyright (c) 2026, 国际四方支付系统改造项目.
 */
package com.jeequan.jeepay.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.jeequan.jeepay.core.entity.ChannelAccount;
import com.jeequan.jeepay.core.entity.ChannelHealthSnapshot;
import com.jeequan.jeepay.service.mapper.ChannelHealthSnapshotMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Date;
import java.util.List;

/**
 * 通道健康度服务
 *
 * 核心职责：
 * - 根据订单数据实时计算通道账号的健康指标
 * - 生成时序快照（每小时一条）
 * - 更新 ChannelAccount 表的快照字段
 *
 * 注意：本服务只输出数据，红线判定由 CircuitBreakerEngine 处理
 *
 * @author 反风控改造组
 */
@Service
public class ChannelHealthService extends ServiceImpl<ChannelHealthSnapshotMapper, ChannelHealthSnapshot> {

    @Autowired
    private ChannelAccountService channelAccountService;

    /**
     * 计算指定账号在指定窗口内的健康指标
     *
     * @param accountId 账号ID
     * @param windowType 统计窗口（1H/24H/7D/30D）
     * @return 快照对象（未持久化，由调用方决定是否保存）
     */
    public ChannelHealthSnapshot calculateSnapshot(String accountId, String windowType) {
        Date now = new Date();
        Date startTime = calculateStartTime(now, windowType);

        // TODO 实际项目中需要从 t_pay_order / t_refund_order / t_chargeback_record 聚合
        // 此处给出聚合 SQL 思路，由 Mapper 自定义 SQL 实现（待补充 xml）：
        //   SELECT
        //     COUNT(*) total,
        //     SUM(CASE WHEN state=2 THEN 1 ELSE 0 END) success,
        //     SUM(CASE WHEN state=3 THEN 1 ELSE 0 END) fail,
        //     ...
        //   FROM t_pay_order WHERE account_id=? AND created_at BETWEEN ? AND ?

        ChannelHealthSnapshot snapshot = new ChannelHealthSnapshot()
                .setAccountId(accountId)
                .setSnapshotTime(now)
                .setWindowType(windowType);

        // 占位：实际数据由 Mapper 聚合查询填充
        snapshot.setTotalCount(0);
        snapshot.setSuccessCount(0);
        snapshot.setFailCount(0);
        snapshot.setChargebackCount(0);
        snapshot.setDisputeCount(0);
        snapshot.setRefundCount(0);
        snapshot.setThreeDsCount(0);
        snapshot.setTotalAmount(0L);

        calculateRates(snapshot);
        return snapshot;
    }

    /**
     * 计算所有比率指标
     * 注意：除数为 0 时返回 0，避免业务方拿到 NaN
     */
    public void calculateRates(ChannelHealthSnapshot s) {
        s.setSuccessRate(safeRate(s.getSuccessCount(), s.getTotalCount()));
        s.setChargebackRate(safeRate(s.getChargebackCount(), s.getSuccessCount()));
        s.setDisputeRate(safeRate(s.getDisputeCount(), s.getSuccessCount()));
        s.setRefundRate(safeRate(s.getRefundCount(), s.getSuccessCount()));
        s.setThreeDsRate(safeRate(s.getThreeDsCount(), s.getTotalCount()));
    }

    /**
     * 生成并保存快照
     */
    public ChannelHealthSnapshot generateAndSave(String accountId, String windowType) {
        ChannelHealthSnapshot s = calculateSnapshot(accountId, windowType);
        save(s);
        // 同步更新 ChannelAccount 表的快照字段（便于快速查询）
        if (ChannelHealthSnapshot.WINDOW_30D.equals(windowType)) {
            ChannelAccount a = new ChannelAccount()
                    .setAccountId(accountId)
                    .setChargebackRate(s.getChargebackRate())
                    .setDisputeRate(s.getDisputeRate())
                    .setRefundRate(s.getRefundRate())
                    .setSuccessRate(s.getSuccessRate())
                    .setTotalTransactions30d(s.getTotalCount())
                    .setLastHealthCheckAt(s.getSnapshotTime());
            channelAccountService.updateById(a);
        }
        return s;
    }

    /**
     * 查询账号最近 N 条快照（用于趋势图）
     */
    public List<ChannelHealthSnapshot> listRecentSnapshots(String accountId, String windowType, int limit) {
        return list(ChannelHealthSnapshot.gw()
                .eq(ChannelHealthSnapshot::getAccountId, accountId)
                .eq(ChannelHealthSnapshot::getWindowType, windowType)
                .orderByDesc(ChannelHealthSnapshot::getSnapshotTime)
                .last("LIMIT " + limit));
    }

    // ===== 辅助方法 =====

    private BigDecimal safeRate(Integer numerator, Integer denominator) {
        if (numerator == null || denominator == null || denominator == 0) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(numerator)
                .multiply(new BigDecimal(100))
                .divide(new BigDecimal(denominator), 4, RoundingMode.HALF_UP);
    }

    private Date calculateStartTime(Date now, String windowType) {
        long ms;
        switch (windowType) {
            case ChannelHealthSnapshot.WINDOW_1H:  ms = 3600_000L; break;
            case ChannelHealthSnapshot.WINDOW_24H: ms = 86400_000L; break;
            case ChannelHealthSnapshot.WINDOW_7D:  ms = 7 * 86400_000L; break;
            case ChannelHealthSnapshot.WINDOW_30D: ms = 30L * 86400_000L; break;
            default: ms = 86400_000L;
        }
        return new Date(now.getTime() - ms);
    }
}
