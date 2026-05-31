-- ============================================
-- 方案 E 补丁：跨站托管收银台
-- 新增字段让 t_cross_site_push_record 支持 "创单 + 跳转付款页 + 指纹回传" 三阶段
-- ============================================

SET NAMES utf8mb4;

ALTER TABLE `t_cross_site_push_record`
  ADD COLUMN `pay_token` VARCHAR(64) DEFAULT NULL
    COMMENT '收银台访问令牌（URL 里的短随机串），UNIQUE' AFTER `pay_order_id`,
  ADD COLUMN `return_url` VARCHAR(512) DEFAULT NULL
    COMMENT '客户付款完成后跳转回 B 站的 URL' AFTER `pay_token`,
  ADD COLUMN `notify_url` VARCHAR(512) DEFAULT NULL
    COMMENT '异步通知 B 站的 Webhook URL' AFTER `return_url`,
  ADD COLUMN `subject` VARCHAR(255) DEFAULT NULL
    COMMENT '商品名称（展示在收银台）' AFTER `notify_url`,
  ADD COLUMN `customer_email` VARCHAR(255) DEFAULT NULL
    COMMENT '客户邮箱（B 站若已知则带过来）' AFTER `subject`,

  -- 浏览器侧采集到的指纹（A 站收银台 JS 填）
  ADD COLUMN `browser_fingerprint` TEXT
    COMMENT 'A 站收银台采集的指纹 JSON（Tier1/2 合并）' AFTER `device_fingerprint`,
  ADD COLUMN `collected_at` TIMESTAMP NULL
    COMMENT '指纹回传时间' AFTER `browser_fingerprint`,
  ADD COLUMN `risk_score_snapshot` INT DEFAULT NULL
    COMMENT '风控引擎对本次跨站订单的评分（0-100，越高越危险）' AFTER `collected_at`,
  ADD COLUMN `risk_decision` VARCHAR(20) DEFAULT NULL
    COMMENT '风控决策 pass/3ds/reject' AFTER `risk_score_snapshot`,

  ADD COLUMN `expire_at` TIMESTAMP NULL
    COMMENT '收银台失效时间（默认创单后 30min）' AFTER `risk_decision`,

  ADD UNIQUE KEY `uk_pay_token` (`pay_token`);

-- 扩展状态枚举（不改 DDL，注释说明）
-- received -> verified -> awaiting_pay(收银台已渲染) -> paying -> paid / failed / rejected / expired
