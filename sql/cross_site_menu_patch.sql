-- 跨站联动菜单注入
-- 依赖：permission_menu_patch.sql 已先执行

INSERT INTO `t_sys_entitlement` VALUES
-- 跨站客户端凭据
('ENT_CROSS_SITE', 'A/B站联动', 'link', '', 'RouteView', 'ML', 0, 1, 'ENT_RISK', '50', 'MGR', NOW(), NOW()),
('ENT_CROSS_SITE_CLIENT', '客户端凭据', 'key', '/risk/crossSite/client', 'RiskCrossSiteClientPage', 'ML', 0, 1, 'ENT_CROSS_SITE', '10', 'MGR', NOW(), NOW()),
('ENT_CROSS_SITE_RECORD', '跨站订单流水', 'unordered-list', '/risk/crossSite/record', 'RiskCrossSiteRecordPage', 'ML', 0, 1, 'ENT_CROSS_SITE', '20', 'MGR', NOW(), NOW()),
('ENT_CROSS_SITE_NOTIFY', '通知投递记录', 'mail', '/risk/crossSite/notify', 'RiskCrossSiteNotifyPage', 'ML', 0, 1, 'ENT_CROSS_SITE', '30', 'MGR', NOW(), NOW()),
-- 权限按钮
('ENT_CROSS_SITE_LIST', '页面：跨站列表', 'no-icon', '', '', 'PB', 0, 1, 'ENT_CROSS_SITE_CLIENT', '0', 'MGR', NOW(), NOW()),
('ENT_CROSS_SITE_VIEW', '按钮：查看跨站详情', 'no-icon', '', '', 'PB', 0, 1, 'ENT_CROSS_SITE_CLIENT', '0', 'MGR', NOW(), NOW()),
('ENT_CROSS_SITE_EDIT', '按钮：编辑跨站客户端', 'no-icon', '', '', 'PB', 0, 1, 'ENT_CROSS_SITE_CLIENT', '0', 'MGR', NOW(), NOW());
