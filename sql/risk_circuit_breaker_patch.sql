-- ============================================
-- 国际四方支付 - 风险熔断/降流引擎补丁（任务 #17 + #18）
-- 版本：v1.0
-- 创建时间：2026-05-30
--
-- 内容：
--   1. 商户表补充 chargeback_alert_threshold / auto_suspend_threshold 列（与技术方案 V3 §3.3 对齐）
--   2. 补充评分作业与熔断引擎所需的阈值配置项
--   3. 补充运营手动解除熔断的权限点
-- ============================================

SET NAMES utf8mb4;

-- ============================================
-- 1. 商户表新增"自定义阈值"列
-- 注意：列名与技术方案 V3 一致；若已存在请手工跳过
-- ============================================
ALTER TABLE `t_mch_info`
  ADD COLUMN `chargeback_alert_threshold` DECIMAL(8,4) DEFAULT 0.7000
        COMMENT '商户自定义拒付率告警阈值(%)' AFTER `auto_suspend_enabled`,
  ADD COLUMN `auto_suspend_threshold` DECIMAL(8,4) DEFAULT 0.9000
        COMMENT '商户自定义拒付率自动暂停阈值(%)' AFTER `chargeback_alert_threshold`;


-- ============================================
-- 2. 补充风险阈值配置项
-- ============================================
INSERT INTO `t_risk_threshold_config`
  (config_key, config_value, value_type, group_key, group_name, config_name, config_desc, action_type, action_enabled, sort_num)
VALUES
  -- 评分维度的自动暂停阈值（与 t_mch_info.auto_suspend_threshold 配合使用，按运营策略可改）
  ('merchant.auto_suspend.threshold', '90', 'number', 'merchant', '商户',
   '自动暂停评分阈值', '评分超过此值，按 action_type 执行（默认 suspend）。',
   'suspend', 1, 5),

  -- 降流（限流）专用阈值，THROTTLE 动作启用开关
  ('merchant.throttle.enabled', 'true', 'boolean', 'merchant', '商户',
   '启用商户限流', 'false 时拒付红线只告警不限流', 'limit', 1, 6),

  -- 熔断态在 Redis 中的默认 TTL（秒），引擎硬编码 7 天兜底，留运营覆盖
  ('circuit_breaker.state_ttl_seconds', '604800', 'number', 'merchant', '商户',
   '熔断态 Redis TTL(秒)', '过期后强制重新评估', 'none', 1, 7);


-- ============================================
-- 3. 权限点：运营手动解除熔断 / 查看熔断态
--    挂在 ENT_RISK 下；按钮级粒度
-- ============================================
INSERT INTO t_sys_entitlement
  (ent_id, ent_name, menu_icon, menu_uri, component_name, ent_type, quick_jump, state, pid, ent_sort, sys_type, created_at, updated_at)
VALUES
  ('ENT_RISK_CIRCUIT_BREAKER', '风险熔断管理', 'thunderbolt', '/riskCircuitBreaker', 'RiskCircuitBreakerPage',
   'ML', 0, 1, 'ENT_RISK', '50', 'MGR', NOW(), NOW());
