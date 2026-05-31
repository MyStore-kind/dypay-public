/*
 * Copyright (c) 2026, 国际四方支付系统改造项目.
 */
package com.jeequan.jeepay.service.schedule;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jeequan.jeepay.core.cache.RedisUtil;
import com.jeequan.jeepay.core.entity.PayOrder;
import com.jeequan.jeepay.service.impl.MchBalanceService;
import com.jeequan.jeepay.service.impl.PayOrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 商户结算调度任务（T+N）
 *
 * 业务流：
 *   每 5 分钟扫一次 t_pay_order
 *   条件：settle_state=1 AND settle_at <= NOW()
 *   动作：MchBalanceService.settle()，把订单金额从 pending 转到 available
 *
 * 设计要点：
 *   - 分布式锁：risk:settle:lock，防止多实例同时跑
 *   - 每批最多处理 200 单，控制单次执行时间
 *   - 单条失败不影响整批（每条 try-catch）
 *
 * @author 反风控改造组
 * @since 2026-06-01
 */
@Component
public class MchSettleSchedule {

    private static final Logger logger = LoggerFactory.getLogger(MchSettleSchedule.class);

    private static final String LOCK_KEY = "risk:settle:lock";
    private static final long LOCK_TTL_SECONDS = 600;
    private static final int BATCH_SIZE = 200;

    @Autowired private PayOrderService payOrderService;
    @Autowired private MchBalanceService mchBalanceService;

    /** 每 5 分钟跑一次 */
    @Scheduled(cron = "0 */5 * * * ?")
    public void run() {
        boolean locked = false;
        try {
            // 简易分布式锁：先 hasKey，没有就 set
            // 注意：hasKey + set 非原子，并发场景下可能两个实例都进；
            //      但 settle 方法本身已经做 settle_state=1 守卫，重复执行不会双入账
            if (RedisUtil.hasKey(LOCK_KEY)) {
                logger.debug("[Settle] 锁存在，跳过本轮");
                return;
            }
            RedisUtil.setString(LOCK_KEY, "1", LOCK_TTL_SECONDS, TimeUnit.SECONDS);
            locked = true;

            Date now = new Date();
            LambdaQueryWrapper<PayOrder> w = new LambdaQueryWrapper<PayOrder>()
                    .eq(PayOrder::getSettleState, (byte) 1)
                    .le(PayOrder::getSettleAt, now)
                    .orderByAsc(PayOrder::getSettleAt)
                    .last("LIMIT " + BATCH_SIZE);

            List<PayOrder> due = payOrderService.list(w);
            if (due.isEmpty()) {
                logger.debug("[Settle] 无待结算订单");
                return;
            }
            logger.info("[Settle] 本轮待结算订单数={}", due.size());

            int ok = 0, fail = 0;
            for (PayOrder o : due) {
                try {
                    // 先 CAS 更新 settle_state 1→2，update 影响行数=1 才执行 settle
                    // 这样即便多实例并发，每条订单只有一个能进 settle
                    LambdaQueryWrapper<PayOrder> cas = new LambdaQueryWrapper<PayOrder>()
                            .eq(PayOrder::getPayOrderId, o.getPayOrderId())
                            .eq(PayOrder::getSettleState, (byte) 1);
                    PayOrder upd = new PayOrder();
                    upd.setSettleState((byte) 2);
                    boolean won = payOrderService.update(upd, cas);
                    if (!won) {
                        // 已被别的实例处理过，跳过
                        continue;
                    }
                    // 真正入账
                    mchBalanceService.settle(
                            o.getMchNo(),
                            o.getAmount() == null ? 0 : o.getAmount(),
                            o.getCurrency(),
                            o.getPayOrderId());
                    ok++;
                } catch (Exception e) {
                    fail++;
                    logger.error("[Settle] 订单结算失败 payOrderId={} mchNo={}",
                            o.getPayOrderId(), o.getMchNo(), e);
                    // 回滚 settle_state，让下一轮重试
                    try {
                        PayOrder rollback = new PayOrder();
                        rollback.setPayOrderId(o.getPayOrderId());
                        rollback.setSettleState((byte) 1);
                        payOrderService.updateById(rollback);
                    } catch (Exception ignored) {}
                }
            }
            logger.info("[Settle] 本轮完成 success={} fail={}", ok, fail);
        } catch (Exception e) {
            logger.error("[Settle] 调度异常", e);
        } finally {
            if (locked) {
                try { RedisUtil.del(LOCK_KEY); } catch (Exception ignored) {}
            }
        }
    }
}
