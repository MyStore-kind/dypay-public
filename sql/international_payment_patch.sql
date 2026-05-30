-- ============================================
-- 国际四方支付系统数据库补丁
-- 版本：v1.0.0
-- 创建时间：2026-05-30
-- 说明：基于 JeePay 扩展国际化功能
-- 执行前请备份数据库！
-- ============================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ============================================
-- 第一部分：新增表
-- ============================================

-- 1. 代理商信息表
DROP TABLE IF EXISTS `t_agent_info`;
CREATE TABLE `t_agent_info` (
  `agent_no` VARCHAR(64) NOT NULL COMMENT '代理商号',
  `agent_name` VARCHAR(128) NOT NULL COMMENT '代理商名称',
  `agent_short_name` VARCHAR(64) COMMENT '代理商简称',
  `contact_name` VARCHAR(32) COMMENT '联系人姓名',
  `contact_tel` VARCHAR(32) COMMENT '联系人手机号',
  `contact_email` VARCHAR(64) COMMENT '联系人邮箱',

  `parent_agent_no` VARCHAR(64) COMMENT '上级代理商号（一级代理为NULL）',
  `agent_level` TINYINT NOT NULL DEFAULT 1 COMMENT '代理商层级 1-一级 2-二级 3-三级',

  `profit_rate` DECIMAL(20,6) NOT NULL DEFAULT 0 COMMENT '分润比例（百分比，如：0.5 表示 0.5%）',
  `settlement_cycle` VARCHAR(20) NOT NULL DEFAULT 'T1' COMMENT '结算周期 T0-实时 T1-次日 T7-每周 T30-每月',
  `min_settlement_amount` BIGINT NOT NULL DEFAULT 0 COMMENT '最低结算金额（单位：分）',

  `state` TINYINT NOT NULL DEFAULT 1 COMMENT '状态 0-停用 1-启用 2-冻结',
  `remark` VARCHAR(256) COMMENT '备注',

  `created_at` TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',

  PRIMARY KEY (`agent_no`),
  KEY `idx_parent_agent` (`parent_agent_no`),
  KEY `idx_state` (`state`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='代理商信息表';

-- 2. 代理商分润记录表
DROP TABLE IF EXISTS `t_agent_profit_record`;
CREATE TABLE `t_agent_profit_record` (
  `record_id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '记录ID',
  `agent_no` VARCHAR(64) NOT NULL COMMENT '代理商号',
  `mch_no` VARCHAR(64) NOT NULL COMMENT '商户号',
  `pay_order_id` VARCHAR(30) NOT NULL COMMENT '支付订单号',

  `order_amount` BIGINT NOT NULL COMMENT '订单金额（单位：分）',
  `order_currency` VARCHAR(3) NOT NULL DEFAULT 'CNY' COMMENT '订单币种',

  `profit_amount` BIGINT NOT NULL COMMENT '分润金额（单位：分）',
  `profit_currency` VARCHAR(3) NOT NULL DEFAULT 'CNY' COMMENT '分润币种',
  `profit_rate` DECIMAL(20,6) NOT NULL COMMENT '分润比例',

  `state` TINYINT NOT NULL DEFAULT 0 COMMENT '状态 0-待结算 1-已结算 2-已冻结',
  `settle_date` DATE COMMENT '结算日期',

  `created_at` TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',

  PRIMARY KEY (`record_id`),
  KEY `idx_agent_no` (`agent_no`),
  KEY `idx_pay_order_id` (`pay_order_id`),
  KEY `idx_state_settle_date` (`state`, `settle_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='代理商分润记录表';

-- 3. 汇率表
DROP TABLE IF EXISTS `t_currency_rate`;
CREATE TABLE `t_currency_rate` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT 'ID',
  `base_currency` VARCHAR(3) NOT NULL COMMENT '基准币种（如：USD）',
  `target_currency` VARCHAR(3) NOT NULL COMMENT '目标币种（如：CNY）',

  `rate` DECIMAL(20,8) NOT NULL COMMENT '汇率（1 基准币种 = rate 目标币种）',
  `rate_source` VARCHAR(32) NOT NULL COMMENT '汇率来源（manual-手动 api-API fixer-Fixer.io stripe-Stripe）',

  `effective_time` TIMESTAMP(3) NOT NULL COMMENT '生效时间',
  `expire_time` TIMESTAMP(3) COMMENT '失效时间（NULL表示长期有效）',

  `created_at` TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',

  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_currency_pair_time` (`base_currency`, `target_currency`, `effective_time`),
  KEY `idx_effective_time` (`effective_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='汇率表';

-- 4. 支持币种配置表
DROP TABLE IF EXISTS `t_currency_config`;
CREATE TABLE `t_currency_config` (
  `currency` VARCHAR(3) NOT NULL COMMENT '币种代码（ISO 4217）',
  `currency_name` VARCHAR(64) NOT NULL COMMENT '币种名称',
  `currency_symbol` VARCHAR(8) NOT NULL COMMENT '币种符号（如：$、€、¥）',

  `decimal_places` TINYINT NOT NULL DEFAULT 2 COMMENT '小数位数',
  `min_amount` BIGINT NOT NULL DEFAULT 1 COMMENT '最小金额（单位：分）',
  `max_amount` BIGINT NOT NULL DEFAULT 999999999 COMMENT '最大金额（单位：分）',

  `state` TINYINT NOT NULL DEFAULT 1 COMMENT '状态 0-停用 1-启用',
  `sort_num` INT NOT NULL DEFAULT 0 COMMENT '排序',

  `created_at` TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',

  PRIMARY KEY (`currency`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='支持币种配置表';

-- ============================================
-- 第二部分：扩展现有表（幂等：基于 information_schema 判断）
-- 为什么这么做：MySQL 5.7/8.0 ALTER TABLE 不原生支持 ADD COLUMN IF NOT EXISTS，
-- 重复执行会报 Duplicate column 错误中断脚本，所以统一封装为存储过程。
-- ============================================

DROP PROCEDURE IF EXISTS `pr_add_col`;
DELIMITER //
CREATE PROCEDURE `pr_add_col`(IN p_table VARCHAR(64), IN p_col VARCHAR(64), IN p_def TEXT)
BEGIN
    IF NOT EXISTS(SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
                  WHERE TABLE_SCHEMA = DATABASE()
                    AND TABLE_NAME = p_table AND COLUMN_NAME = p_col) THEN
        SET @s = CONCAT('ALTER TABLE `', p_table, '` ADD COLUMN `', p_col, '` ', p_def);
        PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;
    END IF;
END//
DELIMITER ;

DROP PROCEDURE IF EXISTS `pr_add_idx`;
DELIMITER //
CREATE PROCEDURE `pr_add_idx`(IN p_table VARCHAR(64), IN p_idx VARCHAR(64), IN p_cols VARCHAR(256))
BEGIN
    IF NOT EXISTS(SELECT 1 FROM INFORMATION_SCHEMA.STATISTICS
                  WHERE TABLE_SCHEMA = DATABASE()
                    AND TABLE_NAME = p_table AND INDEX_NAME = p_idx) THEN
        SET @s = CONCAT('ALTER TABLE `', p_table, '` ADD KEY `', p_idx, '` (', p_cols, ')');
        PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;
    END IF;
END//
DELIMITER ;

-- 1. 商户信息表扩展（幂等）
CALL `pr_add_col`('t_mch_info', 'agent_no', 'VARCHAR(64) COMMENT ''所属代理商号'' AFTER `isv_no`');
CALL `pr_add_col`('t_mch_info', 'settlement_currency', 'VARCHAR(3) DEFAULT ''CNY'' COMMENT ''结算币种'' AFTER `agent_no`');
CALL `pr_add_col`('t_mch_info', 'support_currencies', 'VARCHAR(256) COMMENT ''支持币种列表（逗号分隔，如：USD,EUR,GBP）'' AFTER `settlement_currency`');
CALL `pr_add_idx`('t_mch_info', 'idx_agent_no', '`agent_no`');

-- 2. 支付订单表扩展（幂等）
CALL `pr_add_col`('t_pay_order', 'currency', 'VARCHAR(3) NOT NULL DEFAULT ''CNY'' COMMENT ''订单币种'' AFTER `amount`');
CALL `pr_add_col`('t_pay_order', 'exchange_rate', 'DECIMAL(20,8) COMMENT ''下单时汇率'' AFTER `currency`');
CALL `pr_add_col`('t_pay_order', 'settlement_currency', 'VARCHAR(3) COMMENT ''结算币种'' AFTER `exchange_rate`');
CALL `pr_add_col`('t_pay_order', 'settlement_amount', 'BIGINT COMMENT ''结算金额（单位：分）'' AFTER `settlement_currency`');
CALL `pr_add_idx`('t_pay_order', 'idx_currency', '`currency`');

-- 3. 退款订单表扩展（幂等）
CALL `pr_add_col`('t_refund_order', 'currency', 'VARCHAR(3) NOT NULL DEFAULT ''CNY'' COMMENT ''退款币种'' AFTER `refund_amount`');
CALL `pr_add_col`('t_refund_order', 'exchange_rate', 'DECIMAL(20,8) COMMENT ''退款时汇率'' AFTER `currency`');
CALL `pr_add_col`('t_refund_order', 'settlement_currency', 'VARCHAR(3) COMMENT ''结算币种'' AFTER `exchange_rate`');
CALL `pr_add_col`('t_refund_order', 'settlement_amount', 'BIGINT COMMENT ''结算金额（单位：分）'' AFTER `settlement_currency`');

-- 4. 订单冻结汇率快照字段补丁（任务#4 多币种汇率管理）
-- 复用上方 pr_add_col 通用存储过程，保持幂等。
-- frozen_rate：下单时锁定的汇率快照；base_currency：基准币种参照系。
CALL `pr_add_col`('t_pay_order', 'frozen_rate', 'DECIMAL(20,8) COMMENT ''订单冻结汇率快照(base->currency)'' AFTER `exchange_rate`');
CALL `pr_add_col`('t_pay_order', 'base_currency', 'VARCHAR(3) COMMENT ''基准币种(冻结汇率参照系)'' AFTER `frozen_rate`');
CALL `pr_add_col`('t_refund_order', 'frozen_rate', 'DECIMAL(20,8) COMMENT ''退款冻结汇率(继承订单)'' AFTER `exchange_rate`');
CALL `pr_add_col`('t_refund_order', 'base_currency', 'VARCHAR(3) COMMENT ''基准币种'' AFTER `frozen_rate`');

-- 5. 汇率源开关配置（任务#4 多币种汇率管理）
INSERT IGNORE INTO `t_sys_config`(`config_key`, `config_name`, `config_desc`, `config_val`, `type`, `group_key`, `sort`, `created_at`, `updated_at`) VALUES
('rate.source.fixer.enable',   'Fixer 汇率源开关',  '是否启用 Fixer.io 拉取', 'true',  'text', 'rate', 1, NOW(), NOW()),
('rate.source.stripe.enable',  'Stripe 汇率源开关', '是否启用 Stripe 通道汇率', 'false', 'text', 'rate', 2, NOW(), NOW()),
('rate.source.manual.enable',  '手动汇率源开关',    '是否启用运营手动维护汇率', 'true',  'text', 'rate', 3, NOW(), NOW()),
('rate.fixer.api.key',         'Fixer API Key',      'apilayer Fixer 的 apikey', '', 'text', 'rate', 4, NOW(), NOW()),
('rate.fixer.api.url',         'Fixer API URL',      'Fixer.io 接口地址', 'https://api.apilayer.com/fixer/latest', 'text', 'rate', 5, NOW(), NOW()),
('rate.base.currency',         '基准币种',           '汇率拉取使用的枢轴币种', 'USD', 'text', 'rate', 6, NOW(), NOW()),
('rate.support.currencies',    '支持币种列表',       '逗号分隔，至少 10 种', 'USD,EUR,JPY,CNY,GBP,HKD,SGD,AUD,CAD,KRW', 'text', 'rate', 7, NOW(), NOW());

-- ============================================
-- 第三部分：初始化数据
-- ============================================

-- 1. 初始化币种配置（主要国际币种，含 USD/EUR/JPY/CNY/GBP/HKD/SGD/AUD/CAD/CHF/KRW）
INSERT IGNORE INTO `t_currency_config` VALUES
('USD', '美元', '$', 2, 1, 999999999, 1, 1, NOW(), NOW()),
('EUR', '欧元', '€', 2, 1, 999999999, 1, 2, NOW(), NOW()),
('GBP', '英镑', '£', 2, 1, 999999999, 1, 3, NOW(), NOW()),
('JPY', '日元', '¥', 0, 1, 999999999, 1, 4, NOW(), NOW()),
('CNY', '人民币', '¥', 2, 1, 999999999, 1, 5, NOW(), NOW()),
('HKD', '港币', 'HK$', 2, 1, 999999999, 1, 6, NOW(), NOW()),
('SGD', '新加坡元', 'S$', 2, 1, 999999999, 1, 7, NOW(), NOW()),
('AUD', '澳元', 'A$', 2, 1, 999999999, 1, 8, NOW(), NOW()),
('CAD', '加元', 'C$', 2, 1, 999999999, 1, 9, NOW(), NOW()),
('CHF', '瑞士法郎', 'CHF', 2, 1, 999999999, 1, 10, NOW(), NOW()),
('KRW', '韩元', '₩', 0, 1, 999999999, 1, 11, NOW(), NOW());

-- 2. 初始化汇率（示例数据，实际应从API获取）
INSERT IGNORE INTO `t_currency_rate` (`base_currency`, `target_currency`, `rate`, `rate_source`, `effective_time`) VALUES
('USD', 'CNY', 7.2500, 'manual', NOW()),
('EUR', 'CNY', 7.8500, 'manual', NOW()),
('GBP', 'CNY', 9.1500, 'manual', NOW()),
('JPY', 'CNY', 0.0485, 'manual', NOW()),
('HKD', 'CNY', 0.9280, 'manual', NOW()),
('SGD', 'CNY', 5.3800, 'manual', NOW()),
('AUD', 'CNY', 4.7800, 'manual', NOW()),
('CAD', 'CNY', 5.3500, 'manual', NOW()),
('KRW', 'CNY', 0.0054, 'manual', NOW());

-- 3. 新增支付接口定义（Stripe、PayPal）
INSERT IGNORE INTO `t_pay_interface_define` VALUES
('stripe', 'Stripe', 'stripe', 'Stripe 国际支付（信用卡/借记卡）', 1, 'https://stripe.com', NULL, NULL, 1, NOW(), NOW()),
('paypal', 'PayPal', 'paypal', 'PayPal 电子钱包', 1, 'https://paypal.com', NULL, NULL, 1, NOW(), NOW());

-- 4. 新增支付方式定义
INSERT IGNORE INTO `t_pay_way` VALUES
('stripe_card', 'Stripe 信用卡', 'stripe', 1, NOW(), NOW()),
('stripe_wallet', 'Stripe 电子钱包', 'stripe', 1, NOW(), NOW()),
('paypal_wallet', 'PayPal 钱包', 'paypal', 1, NOW(), NOW());

-- ============================================
-- 第四部分：测试数据（可选）
-- ============================================

-- 创建测试代理商
INSERT IGNORE INTO `t_agent_info` VALUES
('AGENT001', '一级代理商A', '代理A', '张三', '13800138000', 'agent_a@example.com', NULL, 1, 0.50, 'T1', 10000, 1, '测试代理商', NOW(), NOW()),
('AGENT002', '二级代理商B', '代理B', '李四', '13800138001', 'agent_b@example.com', 'AGENT001', 2, 0.30, 'T1', 5000, 1, '测试代理商', NOW(), NOW());

SET FOREIGN_KEY_CHECKS = 1;

-- ============================================
-- 附加：代理商分润结算单表（任务 #3）
-- 用途：
--   - 每个结算周期对达标的代理商生成一条结算单
--   - 与 t_agent_profit_record 是 1:N（结算单聚合多条分润明细）
--   - 不达标的金额延迟到下一周期，不生成结算单
-- ============================================
DROP TABLE IF EXISTS `t_agent_settle_record`;
CREATE TABLE `t_agent_settle_record` (
  `settle_id`         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '结算单ID',
  `settle_no`         VARCHAR(64)  NOT NULL COMMENT '结算单号（业务唯一，便于对账）',
  `agent_no`          VARCHAR(64)  NOT NULL COMMENT '代理商号',
  `settlement_cycle`  VARCHAR(8)   NOT NULL COMMENT '结算周期 T1/T7/T30',
  `period_start`      DATE         NOT NULL COMMENT '聚合起始日（含）',
  `period_end`        DATE         NOT NULL COMMENT '聚合结束日（含）',
  `record_count`      INT          NOT NULL DEFAULT 0 COMMENT '聚合的分润记录数',
  `total_amount`      BIGINT       NOT NULL DEFAULT 0 COMMENT '结算金额合计（分）',
  `currency`          VARCHAR(3)   NOT NULL DEFAULT 'CNY' COMMENT '币种',
  `state`             TINYINT      NOT NULL DEFAULT 0 COMMENT '0-待打款 1-已打款 2-已冻结 3-已驳回',
  `settle_date`       DATETIME              DEFAULT NULL COMMENT '实际打款时间',
  `remark`            VARCHAR(512)          DEFAULT NULL COMMENT '备注（如冻结原因）',
  `created_at`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`settle_id`),
  UNIQUE KEY `uk_settle_no` (`settle_no`),
  KEY `idx_agent_period` (`agent_no`, `period_end`),
  KEY `idx_state` (`state`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='代理商分润结算单';

-- 为分润明细补充 settle_id 字段，建立结算单→明细的反向追溯（幂等）
CALL `pr_add_col`('t_agent_profit_record', 'settle_id', 'BIGINT DEFAULT NULL COMMENT ''关联结算单ID'' AFTER `settle_date`');
CALL `pr_add_idx`('t_agent_profit_record', 'idx_settle_id', '`settle_id`');

-- 清理临时存储过程
DROP PROCEDURE IF EXISTS `pr_add_col`;
DROP PROCEDURE IF EXISTS `pr_add_idx`;

-- ============================================
-- 执行完成提示
-- ============================================
-- 数据库补丁执行完成！
-- 请检查以下内容：
-- 1. 新增表是否创建成功（4张表）
-- 2. 现有表字段是否扩展成功（3张表）
-- 3. 初始化数据是否插入成功（币种、汇率、支付接口）
-- 4. 测试代理商数据是否创建成功
-- ============================================