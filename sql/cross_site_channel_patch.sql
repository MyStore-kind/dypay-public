-- ============================================
-- 跨站支付通道对接 + 异步通知 补丁
-- ============================================

SET NAMES utf8mb4;

-- 1. t_cross_site_push_record 增加通道相关字段
ALTER TABLE `t_cross_site_push_record`
  ADD COLUMN `channel_provider` VARCHAR(20) DEFAULT NULL
    COMMENT '实际走的支付通道 stripe/paypal' AFTER `risk_decision`,
  ADD COLUMN `channel_intent_id` VARCHAR(128) DEFAULT NULL
    COMMENT 'Stripe PaymentIntent ID / PayPal Order ID' AFTER `channel_provider`,
  ADD COLUMN `channel_client_secret` VARCHAR(255) DEFAULT NULL
    COMMENT 'Stripe PaymentIntent.client_secret（给前端 Elements 用）' AFTER `channel_intent_id`,
  ADD COLUMN `paid_at` TIMESTAMP NULL COMMENT '付款完成时间' AFTER `channel_client_secret`,
  ADD COLUMN `failed_reason` VARCHAR(512) DEFAULT NULL COMMENT '支付失败原因' AFTER `paid_at`,
  ADD KEY `idx_channel_intent` (`channel_provider`, `channel_intent_id`);

-- 2. 跨站异步通知表（独立于 t_mch_notify_record，避免污染原表）
-- 设计：6 次指数退避  60s → 5m → 30m → 1h → 6h → 24h
DROP TABLE IF EXISTS `t_cross_site_notify_record`;
CREATE TABLE `t_cross_site_notify_record` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `push_record_id` BIGINT NOT NULL COMMENT '关联 t_cross_site_push_record.id',
  `client_id` VARCHAR(64) NOT NULL,
  `order_id` VARCHAR(64) NOT NULL,
  `notify_url` VARCHAR(512) NOT NULL,

  -- 通知内容（已含签名的完整 payload，避免重发时 ts 漂移导致签名失效）
  `payload` TEXT NOT NULL COMMENT '完整通知 JSON 字符串（含 sign）',
  `event_type` VARCHAR(40) NOT NULL COMMENT 'paid / failed / expired / refunded',

  -- 重试控制
  `notify_count` INT NOT NULL DEFAULT 0 COMMENT '已通知次数',
  `notify_count_limit` INT NOT NULL DEFAULT 6 COMMENT '最大次数',
  `next_notify_time` TIMESTAMP NULL COMMENT '下次通知时间（NULL 表示终态）',
  `last_notify_time` TIMESTAMP NULL,
  `last_response` TEXT COMMENT '上次 HTTP 响应（截断 2KB）',
  `last_http_code` INT DEFAULT NULL,

  -- 状态
  `state` TINYINT NOT NULL DEFAULT 1 COMMENT '1-通知中 2-成功 3-失败（超限）',

  `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_push_event` (`push_record_id`, `event_type`),  -- 同一订单同事件只通知一组
  KEY `idx_next_notify` (`state`, `next_notify_time`),
  KEY `idx_client_order` (`client_id`, `order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='跨站异步通知重试表';
