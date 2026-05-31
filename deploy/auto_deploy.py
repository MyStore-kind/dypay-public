#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
自动部署脚本：把本地项目上传到 187.127.100.8 并完成部署
凭据从环境变量读取，不硬编码
"""
import os
import sys
import time
import paramiko
from scp import SCPClient

HOST = "187.127.100.8"
PORT = 22
USER = "root"
PASSWORD = os.environ.get("DEPLOY_PASSWORD", "")
if not PASSWORD:
    print("❌ 缺少 DEPLOY_PASSWORD 环境变量")
    sys.exit(1)

REMOTE_BASE = "/www/wwwroot"

LOCAL_BACKEND = r"C:\Users\惠普\Desktop\新建文件夹\my-international-payment"
LOCAL_FRONTEND = r"C:\Users\惠普\Desktop\新建文件夹\jeepay-ui"


def log(msg, level="INFO"):
    colors = {"INFO": "\033[36m", "OK": "\033[32m", "ERR": "\033[31m", "WARN": "\033[33m"}
    reset = "\033[0m"
    prefix = colors.get(level, "") + f"[{level}]" + reset
    print(f"{prefix} {msg}", flush=True)


def make_ssh():
    """建立 SSH 连接"""
    log(f"连接 {USER}@{HOST}:{PORT} ...")
    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    try:
        client.connect(
            hostname=HOST,
            port=PORT,
            username=USER,
            password=PASSWORD,
            timeout=15,
            banner_timeout=15,
            auth_timeout=15,
        )
        log("SSH 连接成功", "OK")
        return client
    except paramiko.AuthenticationException:
        log("认证失败：密码错误", "ERR")
        sys.exit(2)
    except Exception as e:
        log(f"连接失败: {e}", "ERR")
        sys.exit(3)


def run(ssh, cmd, timeout=120, check=True):
    """执行远程命令，实时输出"""
    log(f"$ {cmd}")
    stdin, stdout, stderr = ssh.exec_command(cmd, timeout=timeout)
    # 实时打印输出
    for line in iter(stdout.readline, ""):
        if line:
            print("  " + line.rstrip(), flush=True)
    err = stderr.read().decode("utf-8", errors="replace")
    rc = stdout.channel.recv_exit_status()
    if err:
        for line in err.splitlines():
            if line.strip():
                print("  [stderr] " + line, flush=True)
    if check and rc != 0:
        log(f"命令失败 (rc={rc})", "ERR")
        return rc
    return rc


def get(ssh, cmd, timeout=30):
    """执行命令并返回输出"""
    stdin, stdout, stderr = ssh.exec_command(cmd, timeout=timeout)
    return stdout.read().decode("utf-8", errors="replace").strip()


def main():
    # ============ 第 1 步：环境探测 ============
    log("========== 第 1 步：环境探测 ==========")
    ssh = make_ssh()

    os_info = get(ssh, "cat /etc/os-release 2>/dev/null | head -3")
    log("系统信息:\n" + os_info)

    arch = get(ssh, "uname -m")
    log(f"架构: {arch}")

    disk = get(ssh, "df -h / | tail -1")
    log(f"磁盘: {disk}")

    mem = get(ssh, "free -h | grep Mem")
    log(f"内存: {mem}")

    docker_v = get(ssh, "docker --version 2>&1 || echo NOT_INSTALLED")
    log(f"Docker: {docker_v}")

    compose_v = get(ssh, "docker compose version 2>&1 || docker-compose --version 2>&1 || echo NOT_INSTALLED")
    log(f"Compose: {compose_v}")

    # 检查 8083 端口当前情况
    port_check = get(ssh, "ss -tlnp 2>&1 | grep -E ':(8083|9216|9217|9218|3306|6379)\\s' || echo NO_LISTEN")
    log(f"关键端口监听:\n{port_check}")

    # 检查目标目录
    run(ssh, f"mkdir -p {REMOTE_BASE}")
    existing = get(ssh, f"ls {REMOTE_BASE}/my-international-payment 2>/dev/null | head -3 || echo NOT_EXISTS")
    log(f"已存在项目: {existing}")

    ssh.close()
    log("环境探测完成", "OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
