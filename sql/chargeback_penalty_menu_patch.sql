-- ============================================
-- 新增菜单：拒付惩罚配置 / 流水查询 / A-B 站联动
-- 依赖：permission_menu_patch.sql 已先执行（已存在 ENT_RISK 节点）
-- ============================================

INSERT INTO `t_sys_entitlement` VALUES
-- 拒付惩罚配置
('ENT_CHARGEBACK_PENALTY_CONFIG', '拒付惩罚配置', 'dollar', '/risk/chargebackPenalty/config', 'RiskChargebackPenaltyConfigPage', 'ML', 0, 1, 'ENT_RISK', '45', 'MGR', NOW(), NOW()),
('ENT_CHARGEBACK_PENALTY_CONFIG_LIST', '页面：惩罚配置列表', 'no-icon', '', '', 'PB', 0, 1, 'ENT_CHARGEBACK_PENALTY_CONFIG', '0', 'MGR', NOW(), NOW()),
('ENT_CHARGEBACK_PENALTY_CONFIG_EDIT', '按钮：编辑惩罚配置', 'no-icon', '', '', 'PB', 0, 1, 'ENT_CHARGEBACK_PENALTY_CONFIG', '0', 'MGR', NOW(), NOW()),

-- 拒付惩罚流水
('ENT_CHARGEBACK_PENALTY_RECORD', '拒付扣款流水', 'transaction', '/risk/chargebackPenalty/record', 'RiskChargebackPenaltyRecordPage', 'ML', 0, 1, 'ENT_RISK', '46', 'MGR', NOW(), NOW()),
('ENT_CHARGEBACK_PENALTY_RECORD_LIST', '页面：扣款流水列表', 'no-icon', '', '', 'PB', 0, 1, 'ENT_CHARGEBACK_PENALTY_RECORD', '0', 'MGR', NOW(), NOW());
