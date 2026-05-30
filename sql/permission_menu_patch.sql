-- ============================================
-- 国际四方支付 - 后台权限菜单补丁
-- 版本：v1.0
-- 创建时间：2026-05-30
--
-- 新增权限：代理商管理 + 风控中心（通道账号/阈值配置/商户风险/拒付管理）
-- 字段含义参见 t_sys_entitlement 表注释
-- 字段顺序：ent_id, ent_name, menu_icon, menu_uri, component_name,
--          ent_type, quick_jump, state, pid, ent_sort, sys_type, created_at, updated_at
-- ent_type：ML-左侧菜单 MO-其他菜单 PB-页面/按钮
-- ============================================

SET NAMES utf8mb4;

-- ============================================
-- 1. 代理商管理
-- ============================================
INSERT INTO t_sys_entitlement VALUES
('ENT_AGENT', '代理商管理', 'team', '', 'RouteView', 'ML', 0, 1, 'ROOT', '50', 'MGR', NOW(), NOW()),
  ('ENT_AGENT_INFO', '代理商列表', 'profile', '/agent', 'AgentListPage', 'ML', 0, 1, 'ENT_AGENT', '10', 'MGR', NOW(), NOW()),
    ('ENT_AGENT_LIST', '页面：代理商列表', 'no-icon', '', '', 'PB', 0, 1, 'ENT_AGENT_INFO', '0', 'MGR', NOW(), NOW()),
    ('ENT_AGENT_ADD', '按钮：新增代理商', 'no-icon', '', '', 'PB', 0, 1, 'ENT_AGENT_INFO', '0', 'MGR', NOW(), NOW()),
    ('ENT_AGENT_EDIT', '按钮：编辑代理商', 'no-icon', '', '', 'PB', 0, 1, 'ENT_AGENT_INFO', '0', 'MGR', NOW(), NOW()),
    ('ENT_AGENT_VIEW', '按钮：查看代理商', 'no-icon', '', '', 'PB', 0, 1, 'ENT_AGENT_INFO', '0', 'MGR', NOW(), NOW()),
    ('ENT_AGENT_DEL', '按钮：删除代理商', 'no-icon', '', '', 'PB', 0, 1, 'ENT_AGENT_INFO', '0', 'MGR', NOW(), NOW());


-- ============================================
-- 2. 风控中心
-- ============================================
INSERT INTO t_sys_entitlement VALUES
('ENT_RISK', '风控中心', 'safety-certificate', '', 'RouteView', 'ML', 0, 1, 'ROOT', '60', 'MGR', NOW(), NOW()),

  -- 2.1 通道账号池
  ('ENT_CHANNEL_ACCOUNT', '通道账号池', 'cluster', '/channelAccount', 'ChannelAccountListPage', 'ML', 0, 1, 'ENT_RISK', '10', 'MGR', NOW(), NOW()),
    ('ENT_CHANNEL_ACCOUNT_LIST', '页面：通道账号列表', 'no-icon', '', '', 'PB', 0, 1, 'ENT_CHANNEL_ACCOUNT', '0', 'MGR', NOW(), NOW()),
    ('ENT_CHANNEL_ACCOUNT_ADD', '按钮：新增账号', 'no-icon', '', '', 'PB', 0, 1, 'ENT_CHANNEL_ACCOUNT', '0', 'MGR', NOW(), NOW()),
    ('ENT_CHANNEL_ACCOUNT_EDIT', '按钮：编辑账号', 'no-icon', '', '', 'PB', 0, 1, 'ENT_CHANNEL_ACCOUNT', '0', 'MGR', NOW(), NOW()),
    ('ENT_CHANNEL_ACCOUNT_VIEW', '按钮：查看账号', 'no-icon', '', '', 'PB', 0, 1, 'ENT_CHANNEL_ACCOUNT', '0', 'MGR', NOW(), NOW()),
    ('ENT_CHANNEL_ACCOUNT_DEL', '按钮：删除账号', 'no-icon', '', '', 'PB', 0, 1, 'ENT_CHANNEL_ACCOUNT', '0', 'MGR', NOW(), NOW()),

  -- 2.2 风险阈值配置
  ('ENT_RISK_THRESHOLD', '风险阈值配置', 'setting', '/riskThreshold', 'RiskThresholdPage', 'ML', 0, 1, 'ENT_RISK', '20', 'MGR', NOW(), NOW()),
    ('ENT_RISK_THRESHOLD_LIST', '页面：阈值配置列表', 'no-icon', '', '', 'PB', 0, 1, 'ENT_RISK_THRESHOLD', '0', 'MGR', NOW(), NOW()),
    ('ENT_RISK_THRESHOLD_EDIT', '按钮：编辑阈值', 'no-icon', '', '', 'PB', 0, 1, 'ENT_RISK_THRESHOLD', '0', 'MGR', NOW(), NOW()),

  -- 2.3 商户风险看板
  ('ENT_MERCHANT_RISK', '商户风险看板', 'fund', '/merchantRisk', 'MerchantRiskPage', 'ML', 0, 1, 'ENT_RISK', '30', 'MGR', NOW(), NOW()),
    ('ENT_MERCHANT_RISK_LIST', '页面：商户风险列表', 'no-icon', '', '', 'PB', 0, 1, 'ENT_MERCHANT_RISK', '0', 'MGR', NOW(), NOW()),

  -- 2.4 拒付管理
  ('ENT_CHARGEBACK', '拒付管理', 'exception', '/chargeback', 'ChargebackListPage', 'ML', 0, 1, 'ENT_RISK', '40', 'MGR', NOW(), NOW()),
    ('ENT_CHARGEBACK_LIST', '页面：拒付列表', 'no-icon', '', '', 'PB', 0, 1, 'ENT_CHARGEBACK', '0', 'MGR', NOW(), NOW()),
    ('ENT_CHARGEBACK_EDIT', '按钮：处理拒付', 'no-icon', '', '', 'PB', 0, 1, 'ENT_CHARGEBACK', '0', 'MGR', NOW(), NOW());


-- ============================================
-- 3. 默认超管角色自动拥有所有新权限
-- 注意：JeePay 超管账号（is_admin=1）拥有所有权限，无需关联表
--      若非超管角色需要使用，请手工分配
-- ============================================

-- ============================================
-- 执行完成
-- 新增菜单结构：
--   代理商管理（一级菜单）
--     - 代理商列表
--   风控中心（一级菜单）
--     - 通道账号池
--     - 风险阈值配置
--     - 商户风险看板
--     - 拒付管理
-- ============================================
