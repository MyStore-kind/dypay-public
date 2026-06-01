-- ============================================
-- 国际四方支付 Dypay - 风控 V3 补丁（R1 日额熔断 + R3 Stripe EFW 冻结）
-- 版本：v1.0
-- 创建时间：2026-06-02
--
-- 内容：
--   1. t_mch_info 增加商户级"日交易额熔断"覆盖列（R1：商户可在全局默认基础上自定义阈值/时长）
--   2. t_risk_threshold_config 注入 R1（merchant 组）与 R3（stripe 组）所需 KV 配置
--
-- 依赖：t_mch_info、t_risk_threshold_config 必须已由 risk_control_patch.sql 与
--      risk_circuit_breaker_patch.sql 建好；本补丁不重复建表。
-- ============================================

SET NAMES utf8mb4;

-- ============================================
-- 1. 商户表新增"商户级日额熔断覆盖"列（R1）
-- 紧跟在 risk_circuit_breaker_patch.sql 添加的 auto_suspend_threshold 之后，
-- 命名保持 *_threshold / *_seconds 风格；NULL 语义=回落到全局默认（避免一刀切）。
-- 注意：MySQL 不支持 ADD COLUMN IF NOT EXISTS，已部署环境二次执行请见文件末尾说明。
-- ============================================
ALTER TABLE `t_mch_info`
  ADD COLUMN `daily_amount_threshold_usd` DECIMAL(18,2) DEFAULT NULL
        COMMENT '商户级日交易额熔断阈值(USD)，NULL=使用全局默认' AFTER `auto_suspend_threshold`,
  ADD COLUMN `daily_amount_circuit_seconds` INT DEFAULT NULL
        COMMENT '商户级日额熔断时长(秒)，NULL=使用全局默认' AFTER `daily_amount_threshold_usd`;


-- ============================================
-- 2. 注入 R1 / R3 阈值 KV
-- 为什么用 INSERT IGNORE：
--   同一 config_key 可能已被运营在后台手工 UPDATE，重跑补丁绝不能回滚他们的修改。
--   IGNORE 在主键冲突时跳过，保证补丁脚本天然幂等。
-- ============================================
INSERT IGNORE INTO `t_risk_threshold_config`
  (config_key, config_value, value_type, group_key, group_name, config_name, config_desc, action_type, action_enabled, sort_num)
VALUES
  -- R1：商户日交易额熔断（merchant 组） ---------------------------------
  ('merchant.daily_amount.threshold_usd', '500000', 'number', 'merchant', '商户',
   '商户日交易额熔断阈值(USD)', '超过此金额触发熔断；默认 50 万美金',
   'suspend', 1, 10),

  ('merchant.daily_amount.circuit_seconds', '1800', 'number', 'merchant', '商户',
   '日额熔断时长(秒)', '默认 30 分钟',
   'none', 1, 11),

  ('merchant.daily_amount.enabled', 'true', 'boolean', 'merchant', '商户',
   '启用日额熔断', 'R1 总开关',
   'none', 1, 12),

  -- R3：Stripe Radar Early Fraud Warning 自动冻结（stripe 组） ----------
  ('stripe.efw.enabled', 'true', 'boolean', 'stripe', 'Stripe',
   '启用 Stripe EFW 自动冻结', 'R3 总开关',
   'reject', 1, 20),

  ('stripe.efw.freeze_minutes', '30', 'number', 'stripe', 'Stripe',
   'EFW 冻结时长(分钟)', '早期欺诈预警来后冻结卡 BIN 多久',
   'none', 1, 21),

  ('stripe.efw.notify_alert', 'true', 'boolean', 'stripe', 'Stripe',
   'EFW 触发同步告警', '是否同时调 RiskAlertNotifier',
   'none', 1, 22);


-- ============================================
-- 运维提示（幂等性说明）
-- --------------------------------------------
-- MySQL 不支持 `ALTER TABLE ... ADD COLUMN IF NOT EXISTS`，
-- 已部署环境二次执行本补丁会因列已存在报错；
-- 正确做法是手工跳过 ALTER 段，仅执行 INSERT IGNORE 段。
-- （也可仿照 risk_control_patch.sql 中的 `pr_risk_add_col` 存储过程做封装，
--  本补丁因仅涉及 2 列、改动小，未引入额外存储过程。）
-- ============================================
