#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""快速状态检查"""
import os, paramiko, time
ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect("187.127.100.8", 22, "root", os.environ["DEPLOY_PASSWORD"], timeout=15)

def g(cmd):
    _, out, _ = ssh.exec_command(cmd, timeout=30)
    return out.read().decode(errors="replace").strip()

print("=" * 60)
print(" 进程状态")
print("=" * 60)
print("[Maven 编译进程]")
print(g("pgrep -af 'mvn clean package' || echo '已结束'"))
print("\n[npm install 进程]")
print(g("pgrep -af 'npm.*install\\|node.*npm' | head -3 || echo '已结束'"))
print("\n[npm build 进程]")
print(g("pgrep -af 'npm run build' || echo '未运行'"))

print("\n" + "=" * 60)
print(" Maven 编译日志（最后 8 行）")
print("=" * 60)
print(g("tail -8 /tmp/maven-build.log"))

print("\n" + "=" * 60)
print(" Maven 进度 - 已编译的模块")
print("=" * 60)
print(g("grep -E 'BUILD (SUCCESS|FAILURE)|---<.*>' /tmp/maven-build.log | tail -20"))

print("\n" + "=" * 60)
print(" 产物 jar 列表")
print("=" * 60)
print(g("find /www/wwwroot/my-international-payment -name '*.jar' -path '*/target/*' 2>/dev/null | grep -v sources | grep -v javadoc"))

print("\n" + "=" * 60)
print(" 前端 npm 日志")
print("=" * 60)
print("[manager]")
print(g("tail -5 /tmp/npm-manager.log 2>/dev/null || echo '无'"))
print("\n[merchant]")
print(g("tail -5 /tmp/npm-merchant.log 2>/dev/null || echo '无'"))

print("\n" + "=" * 60)
print(" 前端 dist 目录")
print("=" * 60)
print("[manager dist]")
print(g("ls -la /www/wwwroot/jeepay-ui/jeepay-ui-manager/dist 2>&1 | head -3 || echo '未生成'"))
print("\n[merchant dist]")
print(g("ls -la /www/wwwroot/jeepay-ui/jeepay-ui-merchant/dist 2>&1 | head -3 || echo '未生成'"))

print("\n" + "=" * 60)
print(" 数据库状态")
print("=" * 60)
print(g("docker exec jeepay-mysql8 mysql -uroot -p'JeepayMy$ql8Pass2026' -e 'use jeepaydb; show tables' 2>&1 | wc -l") + " 张表（含表头）")

print("\n" + "=" * 60)
print(" 容器状态")
print("=" * 60)
print(g("docker ps --filter name=jeepay --format 'table {{.Names}}\\t{{.Status}}'"))

ssh.close()
