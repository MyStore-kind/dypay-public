-- ============================================
-- 国际四方支付 - 拒付惩罚 P0 补丁
-- 版本：v2.1.0
-- 创建时间：2026-05-31
-- 设计原则：
--   1. 拒付 1 次 = 扣 N 倍本金（默认 3，每商户可配）
--   2. 扣款来源按优先级：available → pending（未下发）
--   3. 不封号：扣款不修改 t_mch_info.state
--   4. 全流水审计：每次扣款写 t_chargeback_penalty_record
-- ============================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ============================================
-- 1. 商户余额字段（扣款来源）
-- 为什么单独 ALTER：避免动到现有 t_mch_info DDL，复用现表
-- ============================================
ALTER TABLE `t_mch_info`
  ADD COLUMN `balance_available` BIGINT NOT NULL DEFAULT 0
    COMMENT '可用余额（分），拒付扣款第一优先级' AFTER `auto_suspend_threshold`,
  ADD COLUMN `balance_pending` BIGINT NOT NULL DEFAULT 0
    COMMENT '未下发余额（分），拒付扣款第二优先级' AFTER `balance_available`,
  ADD COLUMN `balance_frozen` BIGINT NOT NULL DEFAULT 0
    COMMENT '已冻结余额（分），仅审计用，不参与扣款' AFTER `balance_pending`;

-- ============================================
-- 2. 拒付惩罚配置表（每商户独立倍数）
-- 为什么独立表：与 t_risk_threshold_config 解耦
--   t_risk_threshold_config = 全局阈值（拒付率告警等）
--   t_chargeback_penalty_config = 商户级动作参数（倍数 / 扣款来源）
-- ============================================
DROP TABLE IF EXISTS `t_chargeback_penalty_config`;
CREATE TABLE `t_chargeback_penalty_config` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `mch_no` VARCHAR(64) NOT NULL COMMENT '商户号；__GLOBAL__ 为兜底默认',

  -- 核心规则
  `enabled` TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用 0-否 1-是',
  `penalty_multiplier` DECIMAL(5,2) NOT NULL DEFAULT 3.00
    COMMENT '惩罚倍数，例 3.00 = 扣本金 ×3',
  `deduct_source_priority` VARCHAR(64) NOT NULL DEFAULT 'available,pending'
    COMMENT '扣款来源优先级，逗号分隔。可选 available/pending',
  `allow_negative` TINYINT NOT NULL DEFAULT 1
    COMMENT '余额不足是否允许扣成负数 0-否(扣到0为止) 1-是(全额扣)',

  -- 保护开关
  `auto_freeze_on_chargeback` TINYINT NOT NULL DEFAULT 0
    COMMENT '是否拒付后冻结商户 0-否(只扣不封) 1-是；按需求默认 0',
  `min_alert_balance` BIGINT NOT NULL DEFAULT 0
    COMMENT '余额低于此值时告警（分），0=不告警',

  -- 兜底
  `remark` VARCHAR(255) DEFAULT NULL,
  `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_mch_no` (`mch_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='拒付惩罚配置（每商户独立）';

-- 全局兜底默认：3 倍，不封号
INSERT INTO `t_chargeback_penalty_config`
  (`mch_no`, `enabled`, `penalty_multiplier`, `deduct_source_priority`,
   `allow_negative`, `auto_freeze_on_chargeback`, `remark`)
VALUES
  ('__GLOBAL__', 1, 3.00, 'available,pending', 1, 0, '系统默认兜底配置，禁止删除');

-- ============================================
-- 3. 拒付惩罚流水表（审计 / 排查 / 财务对账）
-- 设计：每次扣款产生 1 条；幂等键 = chargeback_record_id
-- ============================================
DROP TABLE IF EXISTS `t_chargeback_penalty_record`;
CREATE TABLE `t_chargeback_penalty_record` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `chargeback_record_id` BIGINT NOT NULL COMMENT '关联 t_chargeback_record.id',
  `pay_order_id` VARCHAR(64) NOT NULL COMMENT '原支付订单号',
  `mch_no` VARCHAR(64) NOT NULL,

  -- 扣款计算
  `principal_amount` BIGINT NOT NULL COMMENT '本金（分），= 原订单金额',
  `multiplier_snapshot` DECIMAL(5,2) NOT NULL COMMENT '本次扣款使用的倍数快照',
  `expected_deduct_amount` BIGINT NOT NULL COMMENT '应扣金额 = 本金 × 倍数',
  `actual_deduct_amount` BIGINT NOT NULL COMMENT '实际扣款金额（可能因余额不足小于应扣）',

  -- 扣款来源拆分
  `deducted_from_available` BIGINT NOT NULL DEFAULT 0 COMMENT '从可用余额扣',
  `deducted_from_pending` BIGINT NOT NULL DEFAULT 0 COMMENT '从未下发余额扣',

  -- 余额快照（扣款前后）
  `balance_available_before` BIGINT NOT NULL,
  `balance_available_after` BIGINT NOT NULL,
  `balance_pending_before` BIGINT NOT NULL,
  `balance_pending_after` BIGINT NOT NULL,

  -- 状态
  `state` VARCHAR(20) NOT NULL DEFAULT 'success'
    COMMENT 'success / partial(余额不足部分扣) / failed / skipped(禁用或商户不存在)',
  `reason` VARCHAR(255) DEFAULT NULL COMMENT '失败 / 部分扣原因',

  `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_chargeback_record_id` (`chargeback_record_id`),
  KEY `idx_mch_no` (`mch_no`),
  KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='拒付惩罚扣款流水';

-- ============================================
-- 4. 通道账号扩展字段（P0 渠道侧保护地基）
-- ============================================
ALTER TABLE `t_channel_account`
  ADD COLUMN `upstream_risk_rules` JSON
    COMMENT '上游三方分控规则快照（人工或 AI 录入）' AFTER `currency_whitelist`,
  ADD COLUMN `closed_by_upstream` TINYINT NOT NULL DEFAULT 0
    COMMENT '是否被上游关停 0-否 1-是' AFTER `upstream_risk_rules`,
  ADD COLUMN `circuit_callback_received_at` TIMESTAMP NULL
    COMMENT '收到上游关停回执时间' AFTER `closed_by_upstream`,
  ADD COLUMN `circuit_callback_payload` TEXT
    COMMENT '上游关停回执原始报文（排查用）' AFTER `circuit_callback_received_at`;

SET FOREIGN_KEY_CHECKS = 1;

-- ============================================
-- 验证查询（手工跑一遍确认无误）
-- SELECT * FROM t_chargeback_penalty_config;
-- DESC t_mch_info;
-- DESC t_channel_account;
-- ============================================
