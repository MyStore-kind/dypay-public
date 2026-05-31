-- ============================================
-- 商户余额入账链路 — 流水表
-- 版本：v3.2.0
-- 创建：2026-06-01
--
-- 设计原则：
--   1. 每一笔余额变动都进流水（含拒付扣款）
--   2. 流水含余额前后快照，用于对账与排查
--   3. 流水不可修改，仅追加
--   4. type 字段语义化，方便筛选
-- ============================================

SET NAMES utf8mb4;

DROP TABLE IF EXISTS `t_mch_balance_record`;
CREATE TABLE `t_mch_balance_record` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `mch_no` VARCHAR(64) NOT NULL,

  -- 变动类型：
  --   topup         运营充值
  --   order_credit  订单收款入账（pending +）
  --   settle        T+N 结算（pending - / available +）
  --   chargeback    拒付扣款（available - / pending -）
  --   refund        退款（available -）
  --   freeze        冻结（available - / frozen +）
  --   unfreeze      解冻（frozen - / available +）
  --   withdraw      提现申请（available -）
  --   adjust_plus   手动调账加
  --   adjust_minus  手动调账减
  `type` VARCHAR(20) NOT NULL,

  -- 影响金额（分，正负号代表方向）
  `amount_available` BIGINT NOT NULL DEFAULT 0 COMMENT '可用余额变动量（正/负）',
  `amount_pending`   BIGINT NOT NULL DEFAULT 0 COMMENT '未下发变动量',
  `amount_frozen`    BIGINT NOT NULL DEFAULT 0 COMMENT '冻结变动量',
  `currency` VARCHAR(8) NOT NULL DEFAULT 'USD',

  -- 三栏快照（变动后）
  `balance_available_after` BIGINT NOT NULL,
  `balance_pending_after`   BIGINT NOT NULL,
  `balance_frozen_after`    BIGINT NOT NULL,

  -- 业务关联（任选其一）
  `pay_order_id`     VARCHAR(64) DEFAULT NULL COMMENT '支付订单号（type=order_credit/settle 时）',
  `refund_order_id`  VARCHAR(64) DEFAULT NULL COMMENT '退款单号',
  `chargeback_id`    BIGINT      DEFAULT NULL COMMENT 't_chargeback_record.id',
  `penalty_record_id` BIGINT     DEFAULT NULL COMMENT 't_chargeback_penalty_record.id',

  -- 审计
  `operator`   VARCHAR(64) DEFAULT NULL COMMENT '操作员（手动调账时）',
  `remark`     VARCHAR(255) DEFAULT NULL,
  `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

  PRIMARY KEY (`id`),
  KEY `idx_mch_type_time` (`mch_no`, `type`, `created_at`),
  KEY `idx_pay_order` (`pay_order_id`),
  KEY `idx_chargeback` (`chargeback_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商户余额变动流水';

-- ============================================
-- t_pay_order 扩字段（结算调度需要）
-- ============================================
ALTER TABLE `t_pay_order`
  ADD COLUMN `settle_at` TIMESTAMP NULL COMMENT '应到账时间（T+N 结算）' AFTER `paid_at`,
  ADD COLUMN `settle_state` TINYINT NOT NULL DEFAULT 0 COMMENT '结算状态：0-待入 pending  1-已入 pending  2-已结算到 available' AFTER `settle_at`,
  ADD KEY `idx_settle_pending` (`settle_state`, `settle_at`);

-- 历史订单数据迁移（C1 修复）
-- 已成功（state=2）且 success_time 不为空的旧订单：
--   1. 算出 settle_at = success_time + T+N
--   2. 把 settle_state 改成 1（等待 MchSettleSchedule 把 pending → available）
-- 注意：旧订单本身没经过 creditPending 入账，所以这里只是给"未来某个时刻"补结算
--      如果旧订单的 pending 永远是 0，settle 时会因 pending 不足抛 BizException 而 fail
--      这是已知行为：旧订单不补入账（避免重复扣款），仅打标避免历史数据失踪
--      运营如需历史订单入账，请用 /api/mchInfo/balance/{mchNo}/topup 手动充值
UPDATE `t_pay_order` o
SET o.`settle_at` = DATE_ADD(COALESCE(o.`success_time`, o.`updated_at`),
                              INTERVAL COALESCE(
                                  (SELECT m.`settle_delay_days` FROM `t_mch_info` m WHERE m.`mch_no` = o.`mch_no`),
                                  1) DAY),
    o.`settle_state` = 2  -- 直接标记"已结算"，避免重复入账
WHERE o.`state` = 2
  AND o.`settle_state` = 0;

-- ============================================
-- 默认配置：T+N
-- ============================================
ALTER TABLE `t_mch_info`
  ADD COLUMN `settle_delay_days` INT NOT NULL DEFAULT 1 COMMENT '结算延迟天数 T+N，默认 T+1' AFTER `balance_frozen`;

-- ============================================
-- 菜单 + 权限注入
-- ============================================
INSERT INTO `t_sys_entitlement` VALUES
('ENT_MCH_BALANCE', '商户余额', 'wallet', '/mch/balance', 'MchBalancePage', 'ML', 0, 1, 'ENT_MCH', '15', 'MGR', NOW(), NOW()),
('ENT_MCH_BALANCE_LIST', '页面：余额管理', 'no-icon', '', '', 'PB', 0, 1, 'ENT_MCH_BALANCE', '0', 'MGR', NOW(), NOW()),
('ENT_MCH_BALANCE_OPERATE', '按钮：充值/调账/冻结', 'no-icon', '', '', 'PB', 0, 1, 'ENT_MCH_BALANCE', '0', 'MGR', NOW(), NOW());

-- 自动赋权超级管理员
INSERT IGNORE INTO t_sys_role_ent_rela (role_id, ent_id, sys_type)
SELECT 'ROLE_SUPER_ADMIN', ent_id, 'MGR'
FROM t_sys_entitlement
WHERE ent_id IN ('ENT_MCH_BALANCE', 'ENT_MCH_BALANCE_LIST', 'ENT_MCH_BALANCE_OPERATE');
