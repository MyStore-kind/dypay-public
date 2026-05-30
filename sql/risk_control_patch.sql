-- ============================================
-- 国际四方支付 - 反风控模块数据库补丁
-- 版本：v2.0.0
-- 创建时间：2026-05-30
-- 设计原则：
--   1. 系统只输出数据，不下判断
--   2. 所有红线/阈值可配置，运营自行调整
--   3. 触发动作可开关（告警/限流/熔断）
-- ============================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ============================================
-- 1. 通道账号池
-- 设计：每个通道可挂多个账号，由路由器选择
-- ============================================
DROP TABLE IF EXISTS `t_channel_account`;
CREATE TABLE `t_channel_account` (
  `account_id` VARCHAR(64) NOT NULL COMMENT '账号ID（如 stripe_acct_001）',
  `if_code` VARCHAR(20) NOT NULL COMMENT '通道编码（stripe/paypal/alipay）',
  `account_name` VARCHAR(128) NOT NULL COMMENT '账号显示名',
  `config_params` JSON COMMENT '通道密钥等参数 JSON',

  -- 额度控制（运营配置）
  `daily_limit_amount` BIGINT NOT NULL DEFAULT 0 COMMENT '日交易限额（分，0=不限）',
  `monthly_limit_amount` BIGINT NOT NULL DEFAULT 0 COMMENT '月交易限额（分，0=不限）',
  `single_limit_amount` BIGINT NOT NULL DEFAULT 0 COMMENT '单笔限额（分，0=不限）',

  -- 实时累计（系统更新）
  `current_daily_amount` BIGINT NOT NULL DEFAULT 0 COMMENT '当日已交易（分）',
  `current_monthly_amount` BIGINT NOT NULL DEFAULT 0 COMMENT '当月已交易（分）',
  `last_reset_date` DATE COMMENT '上次日累计重置日期',

  -- 风险指标快照（由调度任务更新，方便快速查询）
  `chargeback_rate` DECIMAL(8,4) NOT NULL DEFAULT 0 COMMENT '拒付率（最近30天）',
  `dispute_rate` DECIMAL(8,4) NOT NULL DEFAULT 0 COMMENT '投诉率',
  `refund_rate` DECIMAL(8,4) NOT NULL DEFAULT 0 COMMENT '退款率',
  `success_rate` DECIMAL(8,4) NOT NULL DEFAULT 100 COMMENT '成功率',
  `total_transactions_30d` INT NOT NULL DEFAULT 0 COMMENT '30天总笔数',
  `last_health_check_at` TIMESTAMP NULL COMMENT '最后一次健康度计算时间',

  -- 业务隔离（运营配置）
  `risk_tier` VARCHAR(10) NOT NULL DEFAULT 'mid' COMMENT '承载风险等级 low/mid/high',
  `mcc_whitelist` VARCHAR(1024) COMMENT '允许的MCC列表（逗号分隔，空=全部）',
  `mcc_blacklist` VARCHAR(1024) COMMENT '禁止的MCC列表',
  `country_whitelist` VARCHAR(512) COMMENT '允许的国家代码（ISO 3166-1，逗号分隔）',
  `country_blacklist` VARCHAR(512) COMMENT '禁止的国家代码',
  `currency_whitelist` VARCHAR(256) COMMENT '允许的币种（逗号分隔）',

  -- 路由控制（运营配置）
  `priority` INT NOT NULL DEFAULT 0 COMMENT '路由优先级（数字越大越优先）',
  `weight` INT NOT NULL DEFAULT 100 COMMENT '权重（用于加权随机路由）',

  -- 状态
  `health_status` TINYINT NOT NULL DEFAULT 1 COMMENT '健康状态 0-异常 1-健康 2-警告 3-限流',
  `state` TINYINT NOT NULL DEFAULT 1 COMMENT '启用状态 0-停用 1-启用 2-冻结',
  `remark` VARCHAR(512) COMMENT '备注',

  `created_at` TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),

  PRIMARY KEY (`account_id`),
  KEY `idx_if_code_state` (`if_code`, `state`),
  KEY `idx_health_status` (`health_status`),
  KEY `idx_risk_tier` (`risk_tier`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通道账号池';


-- ============================================
-- 2. 通道健康度快照（时序数据）
-- 设计：每小时一条，便于绘制趋势图
-- ============================================
DROP TABLE IF EXISTS `t_channel_health_snapshot`;
CREATE TABLE `t_channel_health_snapshot` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `account_id` VARCHAR(64) NOT NULL COMMENT '账号ID',
  `snapshot_time` TIMESTAMP NOT NULL COMMENT '快照时间',
  `window_type` VARCHAR(10) NOT NULL COMMENT '统计窗口 1H/24H/7D/30D',

  -- 计数指标
  `total_count` INT NOT NULL DEFAULT 0 COMMENT '总笔数',
  `success_count` INT NOT NULL DEFAULT 0 COMMENT '成功笔数',
  `fail_count` INT NOT NULL DEFAULT 0 COMMENT '失败笔数',
  `chargeback_count` INT NOT NULL DEFAULT 0 COMMENT '拒付笔数',
  `dispute_count` INT NOT NULL DEFAULT 0 COMMENT '投诉笔数',
  `refund_count` INT NOT NULL DEFAULT 0 COMMENT '退款笔数',
  `three_ds_count` INT NOT NULL DEFAULT 0 COMMENT '3DS触发笔数',

  -- 比率指标（百分比）
  `success_rate` DECIMAL(8,4) NOT NULL DEFAULT 0 COMMENT '成功率',
  `chargeback_rate` DECIMAL(8,4) NOT NULL DEFAULT 0 COMMENT '拒付率',
  `dispute_rate` DECIMAL(8,4) NOT NULL DEFAULT 0 COMMENT '投诉率',
  `refund_rate` DECIMAL(8,4) NOT NULL DEFAULT 0 COMMENT '退款率',
  `three_ds_rate` DECIMAL(8,4) NOT NULL DEFAULT 0 COMMENT '3DS比例',

  -- 金额指标
  `total_amount` BIGINT NOT NULL DEFAULT 0 COMMENT '总金额（分，已归一到USD）',

  `created_at` TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

  PRIMARY KEY (`id`),
  KEY `idx_account_time` (`account_id`, `snapshot_time`),
  KEY `idx_window_time` (`window_type`, `snapshot_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通道健康度快照';


-- ============================================
-- 3. 商户风险评分历史
-- 设计：每日一条快照，便于查看趋势
-- ============================================
DROP TABLE IF EXISTS `t_merchant_risk_score`;
CREATE TABLE `t_merchant_risk_score` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `mch_no` VARCHAR(64) NOT NULL COMMENT '商户号',
  `score_date` DATE NOT NULL COMMENT '评分日期',

  -- 评分结果
  `risk_score` INT NOT NULL DEFAULT 50 COMMENT '风险评分 0-100，越大越危险',
  `risk_tier` VARCHAR(10) NOT NULL DEFAULT 'mid' COMMENT '风险等级 low/mid/high',

  -- 指标明细
  `chargeback_rate` DECIMAL(8,4) NOT NULL DEFAULT 0,
  `dispute_rate` DECIMAL(8,4) NOT NULL DEFAULT 0,
  `refund_rate` DECIMAL(8,4) NOT NULL DEFAULT 0,
  `success_rate` DECIMAL(8,4) NOT NULL DEFAULT 100,
  `high_risk_card_rate` DECIMAL(8,4) NOT NULL DEFAULT 0 COMMENT '高风险卡占比',
  `total_orders_30d` INT NOT NULL DEFAULT 0,
  `total_amount_30d` BIGINT NOT NULL DEFAULT 0,

  `evaluation_window` VARCHAR(10) NOT NULL DEFAULT '30D' COMMENT '评估窗口',
  `score_detail` JSON COMMENT '评分明细 JSON（各项加权得分）',

  `created_at` TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_mch_date` (`mch_no`, `score_date`),
  KEY `idx_risk_tier_date` (`risk_tier`, `score_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商户风险评分历史';


-- ============================================
-- 4. 订单风控记录
-- 设计：每笔订单一条，记录风控决策过程
-- ============================================
DROP TABLE IF EXISTS `t_order_risk_record`;
CREATE TABLE `t_order_risk_record` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `pay_order_id` VARCHAR(30) NOT NULL COMMENT '订单号',
  `mch_no` VARCHAR(64) NOT NULL COMMENT '商户号',
  `account_id` VARCHAR(64) COMMENT '使用的通道账号',

  -- 风控结果
  `risk_score` INT NOT NULL DEFAULT 0 COMMENT '风险评分 0-100',
  `risk_action` VARCHAR(20) NOT NULL DEFAULT 'pass' COMMENT '决策动作 pass/3ds/reject',
  `risk_factors` JSON COMMENT '触发的规则明细',

  -- 设备指纹
  `ip` VARCHAR(45) COMMENT '客户端IP',
  `ip_country` VARCHAR(2) COMMENT 'IP所属国家',
  `ip_risk_level` VARCHAR(10) COMMENT 'IP风险等级 low/mid/high',
  `device_fingerprint` VARCHAR(128) COMMENT '设备指纹',
  `user_agent` VARCHAR(512) COMMENT 'UA',

  -- 卡信息（脱敏存储）
  `card_bin` VARCHAR(8) COMMENT '卡BIN',
  `card_last4` VARCHAR(4) COMMENT '卡尾号',
  `card_country` VARCHAR(2) COMMENT '卡发行国',
  `card_type` VARCHAR(20) COMMENT 'credit/debit/prepaid',
  `card_brand` VARCHAR(20) COMMENT 'visa/mastercard/amex',

  -- 买家信息
  `buyer_email` VARCHAR(128),
  `buyer_phone` VARCHAR(32),
  `buyer_name` VARCHAR(128),

  -- 3DS 信息
  `three_ds_triggered` TINYINT DEFAULT 0 COMMENT '是否触发3DS',
  `three_ds_result` VARCHAR(20) COMMENT '3DS结果 success/fail/skip',

  `created_at` TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_pay_order` (`pay_order_id`),
  KEY `idx_mch_time` (`mch_no`, `created_at`),
  KEY `idx_ip_time` (`ip`, `created_at`),
  KEY `idx_card_bin` (`card_bin`),
  KEY `idx_email` (`buyer_email`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单风控记录';


-- ============================================
-- 5. 黑名单
-- 设计：多类型统一表，按类型查询
-- ============================================
DROP TABLE IF EXISTS `t_risk_blacklist`;
CREATE TABLE `t_risk_blacklist` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `list_type` VARCHAR(20) NOT NULL COMMENT '类型 card_bin/card_number/ip/email/device/phone/country',
  `list_value` VARCHAR(256) NOT NULL COMMENT '黑名单值',
  `reason` VARCHAR(256) COMMENT '加入原因',
  `source` VARCHAR(32) COMMENT '来源 manual/auto/chargeback/risk_rule',

  `hit_count` INT NOT NULL DEFAULT 0 COMMENT '命中次数',
  `last_hit_at` TIMESTAMP NULL COMMENT '最后命中时间',

  `expire_at` TIMESTAMP NULL COMMENT '过期时间（NULL=永久）',
  `state` TINYINT NOT NULL DEFAULT 1 COMMENT '0-停用 1-启用',
  `created_by` VARCHAR(64) COMMENT '创建人',

  `created_at` TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),

  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_type_value` (`list_type`, `list_value`),
  KEY `idx_state_expire` (`state`, `expire_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='风险黑名单';


-- ============================================
-- 6. 拒付记录
-- 设计：完整记录每笔拒付，包含证据材料
-- ============================================
DROP TABLE IF EXISTS `t_chargeback_record`;
CREATE TABLE `t_chargeback_record` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `pay_order_id` VARCHAR(30) NOT NULL COMMENT '原支付订单号',
  `channel_chargeback_id` VARCHAR(128) NOT NULL COMMENT '通道方拒付ID',
  `mch_no` VARCHAR(64) NOT NULL COMMENT '商户号',
  `account_id` VARCHAR(64) NOT NULL COMMENT '通道账号',
  `if_code` VARCHAR(20) NOT NULL COMMENT '通道编码',

  -- 拒付信息
  `chargeback_amount` BIGINT NOT NULL COMMENT '拒付金额（分）',
  `chargeback_currency` VARCHAR(3) NOT NULL COMMENT '拒付币种',
  `chargeback_reason_code` VARCHAR(64) COMMENT '拒付原因码（如 4855）',
  `chargeback_reason_desc` VARCHAR(512) COMMENT '拒付原因描述',
  `chargeback_type` VARCHAR(20) COMMENT 'fraudulent/unrecognized/duplicate/product_not_received',

  -- 状态机
  `state` VARCHAR(20) NOT NULL DEFAULT 'received' COMMENT 'received/under_review/responded/won/lost/expired',
  `evidence_due_at` TIMESTAMP NULL COMMENT '证据提交截止时间',
  `evidence_submitted_at` TIMESTAMP NULL COMMENT '证据提交时间',
  `resolved_at` TIMESTAMP NULL COMMENT '最终解决时间',

  -- 证据快照（订单时已收集）
  `customer_ip` VARCHAR(45),
  `customer_email` VARCHAR(128),
  `customer_name` VARCHAR(128),
  `shipping_address` TEXT,
  `billing_address` TEXT,
  `receipt_url` VARCHAR(512) COMMENT '电子收据URL',
  `service_documentation` TEXT COMMENT '服务履约证明',
  `communication_log` TEXT COMMENT '客户沟通记录',
  `evidence_files` JSON COMMENT '证据文件列表',

  `remark` VARCHAR(512),
  `created_at` TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),

  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_channel_id` (`channel_chargeback_id`),
  KEY `idx_pay_order` (`pay_order_id`),
  KEY `idx_state_due` (`state`, `evidence_due_at`),
  KEY `idx_mch_time` (`mch_no`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='拒付记录';


-- ============================================
-- 7. 风险阈值配置（运营核心配置表）
-- 设计：所有红线、阈值、动作全部可配置
-- ============================================
DROP TABLE IF EXISTS `t_risk_threshold_config`;
CREATE TABLE `t_risk_threshold_config` (
  `config_key` VARCHAR(64) NOT NULL COMMENT '配置key（如 channel.chargeback_rate.warning）',
  `config_value` VARCHAR(256) NOT NULL COMMENT '配置值',
  `value_type` VARCHAR(20) NOT NULL DEFAULT 'string' COMMENT 'string/number/boolean/json',

  `group_key` VARCHAR(64) NOT NULL COMMENT '分组（channel/merchant/order/blacklist）',
  `group_name` VARCHAR(64) NOT NULL COMMENT '分组显示名',

  `config_name` VARCHAR(128) NOT NULL COMMENT '配置项显示名',
  `config_desc` VARCHAR(512) COMMENT '配置说明',

  -- 触发动作配置（重要）
  `action_type` VARCHAR(20) DEFAULT 'notify' COMMENT '触发动作 notify/limit/suspend/none',
  `action_enabled` TINYINT NOT NULL DEFAULT 1 COMMENT '动作是否启用（0仅记录不动作）',

  `sort_num` INT NOT NULL DEFAULT 0,
  `created_at` TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),

  PRIMARY KEY (`config_key`),
  KEY `idx_group` (`group_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='风险阈值配置';


-- ============================================
-- 8. 商户表字段扩展
-- ============================================
ALTER TABLE `t_mch_info`
  ADD COLUMN `mcc_code` VARCHAR(10) COMMENT 'MCC行业代码' AFTER `support_currencies`,
  ADD COLUMN `risk_tier` VARCHAR(10) NOT NULL DEFAULT 'mid' COMMENT '风险等级 low/mid/high' AFTER `mcc_code`,
  ADD COLUMN `current_risk_score` INT NOT NULL DEFAULT 50 COMMENT '当前风险评分' AFTER `risk_tier`,
  ADD COLUMN `daily_limit_amount` BIGINT NOT NULL DEFAULT 0 COMMENT '日交易限额（分，0=不限）' AFTER `current_risk_score`,
  ADD COLUMN `single_limit_amount` BIGINT NOT NULL DEFAULT 0 COMMENT '单笔限额（分，0=不限）' AFTER `daily_limit_amount`,
  ADD COLUMN `auto_suspend_enabled` TINYINT NOT NULL DEFAULT 0 COMMENT '超阈值自动暂停 0-否 1-是' AFTER `single_limit_amount`,
  ADD KEY `idx_risk_tier` (`risk_tier`);


-- ============================================
-- 9. 订单表字段扩展（风控信息）
-- ============================================
ALTER TABLE `t_pay_order`
  ADD COLUMN `risk_score` INT DEFAULT 0 COMMENT '风险评分' AFTER `settlement_amount`,
  ADD COLUMN `risk_action` VARCHAR(20) DEFAULT 'pass' COMMENT '风控动作 pass/3ds/reject' AFTER `risk_score`,
  ADD COLUMN `account_id` VARCHAR(64) COMMENT '使用的通道账号ID' AFTER `risk_action`,
  ADD KEY `idx_account_id` (`account_id`),
  ADD KEY `idx_risk_action` (`risk_action`);


-- ============================================
-- 10. 初始化默认阈值配置（参考值，运营可改）
-- ============================================
INSERT INTO `t_risk_threshold_config` (config_key, config_value, value_type, group_key, group_name, config_name, config_desc, action_type, action_enabled, sort_num) VALUES
-- 通道账号阈值
('channel.chargeback_rate.warning', '0.7', 'number', 'channel', '通道账号', '拒付率黄线(%)', '触发后告警通知，参考值 0.7%', 'notify', 1, 1),
('channel.chargeback_rate.critical', '0.9', 'number', 'channel', '通道账号', '拒付率红线(%)', '触发后自动限流或停用账号，参考 Visa 红线 0.9%', 'limit', 1, 2),
('channel.dispute_rate.warning', '0.8', 'number', 'channel', '通道账号', '投诉率黄线(%)', '', 'notify', 1, 3),
('channel.dispute_rate.critical', '1.0', 'number', 'channel', '通道账号', '投诉率红线(%)', '', 'limit', 1, 4),
('channel.refund_rate.warning', '5.0', 'number', 'channel', '通道账号', '退款率黄线(%)', '', 'notify', 1, 5),
('channel.refund_rate.critical', '8.0', 'number', 'channel', '通道账号', '退款率红线(%)', '', 'limit', 1, 6),
('channel.success_rate.warning', '90.0', 'number', 'channel', '通道账号', '成功率黄线(%)', '低于此值告警', 'notify', 1, 7),
('channel.success_rate.critical', '85.0', 'number', 'channel', '通道账号', '成功率红线(%)', '低于此值切换账号', 'switch', 1, 8),
('channel.daily_amount_burst_ratio', '3.0', 'number', 'channel', '通道账号', '日交易突增比例', '当日金额超昨日 N 倍时告警', 'notify', 1, 9),

-- 商户阈值
('merchant.chargeback_rate.warning', '0.7', 'number', 'merchant', '商户', '商户拒付率黄线(%)', '', 'notify', 1, 1),
('merchant.chargeback_rate.critical', '0.9', 'number', 'merchant', '商户', '商户拒付率红线(%)', '触发后限流或暂停商户', 'limit', 1, 2),
('merchant.dispute_rate.critical', '1.0', 'number', 'merchant', '商户', '商户投诉率红线(%)', '', 'limit', 1, 3),
('merchant.high_risk_score.threshold', '70', 'number', 'merchant', '商户', '高风险评分阈值', '评分超过此值标记为 high', 'notify', 1, 4),

-- 订单风控阈值
('order.risk_score.reject', '60', 'number', 'order', '订单风控', '订单拒绝评分阈值', '订单评分超此值直接拒绝', 'reject', 1, 1),
('order.risk_score.3ds', '30', 'number', 'order', '订单风控', '订单强制3DS阈值', '订单评分超此值强制3DS', '3ds', 1, 2),
('order.same_ip_freq.threshold', '5', 'number', 'order', '订单风控', '同IP高频阈值(笔/10分钟)', '同IP 10分钟内超过 N 笔失败时拦截', 'reject', 1, 3),
('order.same_card_merchants.threshold', '3', 'number', 'order', '订单风控', '同卡多商户阈值(个/24小时)', '同卡 24 小时使用超过 N 个商户时告警', 'notify', 1, 4),

-- 黑名单配置
('blacklist.card_bin.enabled', 'true', 'boolean', 'blacklist', '黑名单', '启用卡BIN黑名单', '', 'reject', 1, 1),
('blacklist.ip.enabled', 'true', 'boolean', 'blacklist', '黑名单', '启用IP黑名单', '', 'reject', 1, 2),
('blacklist.email.enabled', 'true', 'boolean', 'blacklist', '黑名单', '启用邮箱黑名单', '', 'reject', 1, 3),
('blacklist.device.enabled', 'true', 'boolean', 'blacklist', '黑名单', '启用设备指纹黑名单', '', 'reject', 1, 4),

-- 通知配置
('notify.telegram.enabled', 'false', 'boolean', 'notify', '通知', '启用Telegram通知', '', 'notify', 1, 1),
('notify.telegram.bot_token', '', 'string', 'notify', '通知', 'Telegram Bot Token', '', 'notify', 1, 2),
('notify.telegram.chat_id', '', 'string', 'notify', '通知', 'Telegram Chat ID', '', 'notify', 1, 3),
('notify.email.enabled', 'false', 'boolean', 'notify', '通知', '启用邮件通知', '', 'notify', 1, 4),
('notify.email.recipients', '', 'string', 'notify', '通知', '邮件接收人(逗号分隔)', '', 'notify', 1, 5);


SET FOREIGN_KEY_CHECKS = 1;
-- ============================================
-- 反风控数据库补丁执行完成
-- 包含：7张新表 + 2张扩展表 + 初始化阈值配置
-- 关键设计：所有阈值与动作均可在运营后台调整
-- ============================================
