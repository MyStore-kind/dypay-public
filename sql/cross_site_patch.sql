-- ============================================
-- A/B 站联动 P2 补丁
-- B 站（商户）-> A 站（收款）推送 IP/指纹/价格的审计与重放防御
-- ============================================

SET NAMES utf8mb4;

-- 1. 跨站推送流水（A 站接收 B 站推送的每条事件都落库）
DROP TABLE IF EXISTS `t_cross_site_push_record`;
CREATE TABLE `t_cross_site_push_record` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `client_id` VARCHAR(64) NOT NULL COMMENT 'B 站身份标识（A 站颁发）',
  `order_id` VARCHAR(64) NOT NULL COMMENT 'B 站订单号',
  `amount` BIGINT NOT NULL COMMENT '金额（分）',
  `currency` VARCHAR(8) NOT NULL DEFAULT 'USD',

  -- 风控关键字段
  `ip` VARCHAR(64) COMMENT '客户 IP',
  `device_fingerprint` VARCHAR(128) COMMENT '设备指纹',
  `user_agent` VARCHAR(512) COMMENT 'UA',

  -- 签名校验
  `nonce` VARCHAR(64) NOT NULL COMMENT '随机串，防重放',
  `ts` BIGINT NOT NULL COMMENT '客户端时间戳（ms）',
  `sign` VARCHAR(128) NOT NULL COMMENT 'HMAC-SHA256 hex',
  `raw_payload` TEXT NOT NULL COMMENT '原始 JSON 字符串',

  -- 处理状态
  `state` VARCHAR(20) NOT NULL DEFAULT 'received'
    COMMENT 'received / verified / paid / failed / rejected',
  `pay_order_id` VARCHAR(64) DEFAULT NULL COMMENT 'A 站产生的支付订单号',
  `reject_reason` VARCHAR(255) DEFAULT NULL,

  `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

  PRIMARY KEY (`id`),
  -- 关键：防止重放 / 重复推送
  UNIQUE KEY `uk_client_order` (`client_id`, `order_id`),
  KEY `idx_nonce` (`nonce`),
  KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='跨站推送流水（A 站接收 B 站）';

-- 2. 跨站客户端凭据（A 站为每个 B 站颁发 client_id / secret）
DROP TABLE IF EXISTS `t_cross_site_client`;
CREATE TABLE `t_cross_site_client` (
  `client_id` VARCHAR(64) NOT NULL,
  `client_name` VARCHAR(128) NOT NULL,
  `client_secret` VARCHAR(128) NOT NULL COMMENT 'HMAC 密钥（建议 base64 32 字节随机）',
  `enabled` TINYINT NOT NULL DEFAULT 1,
  `ip_whitelist` VARCHAR(512) COMMENT '允许的 B 站出口 IP，逗号分隔',
  `remark` VARCHAR(255) DEFAULT NULL,
  `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`client_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='跨站客户端凭据';
