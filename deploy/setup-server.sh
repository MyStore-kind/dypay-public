#!/bin/bash
# ============================================================
# JeePay 国际支付 - 服务器环境检测与依赖安装
# 兼容：CentOS 7/8、Ubuntu 20/22、Debian 11/12
# 用法：bash deploy/setup-server.sh
# ============================================================
set -euo pipefail

GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m'

log()  { echo -e "${GREEN}✅ $*${NC}"; }
warn() { echo -e "${YELLOW}⚠️  $*${NC}"; }
err()  { echo -e "${RED}❌ $*${NC}" >&2; }

require_root() {
  if [[ $EUID -ne 0 ]]; then
    err "请用 root 或 sudo 执行：sudo bash $0"
    exit 1
  fi
}

detect_os() {
  if [[ -f /etc/os-release ]]; then
    # shellcheck disable=SC1091
    . /etc/os-release
    OS_ID="${ID:-unknown}"
    OS_VER="${VERSION_ID:-unknown}"
  else
    err "无法识别系统（缺少 /etc/os-release）"
    exit 1
  fi
  log "系统：${OS_ID} ${OS_VER}"
}

install_docker() {
  if command -v docker >/dev/null 2>&1; then
    log "Docker 已安装：$(docker --version)"
    return
  fi
  warn "未检测到 Docker，开始安装（阿里云镜像源）"
  case "${OS_ID}" in
    centos|rhel|almalinux|rocky)
      yum install -y yum-utils
      yum-config-manager --add-repo https://mirrors.aliyun.com/docker-ce/linux/centos/docker-ce.repo
      yum install -y docker-ce docker-ce-cli containerd.io
      ;;
    ubuntu|debian)
      apt-get update -y
      apt-get install -y ca-certificates curl gnupg lsb-release
      install -m 0755 -d /etc/apt/keyrings
      curl -fsSL https://mirrors.aliyun.com/docker-ce/linux/${OS_ID}/gpg \
        | gpg --dearmor -o /etc/apt/keyrings/docker.gpg
      chmod a+r /etc/apt/keyrings/docker.gpg
      echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] \
        https://mirrors.aliyun.com/docker-ce/linux/${OS_ID} $(lsb_release -cs) stable" \
        > /etc/apt/sources.list.d/docker.list
      apt-get update -y
      apt-get install -y docker-ce docker-ce-cli containerd.io
      ;;
    *)
      err "不支持的系统：${OS_ID}，请手动安装 Docker"
      exit 1
      ;;
  esac
  systemctl enable --now docker
  log "Docker 安装完成：$(docker --version)"
}

configure_docker_mirror() {
  local cfg=/etc/docker/daemon.json
  if [[ -f $cfg ]] && grep -q registry-mirrors "$cfg"; then
    log "Docker 镜像加速已配置"
    return
  fi
  warn "写入阿里云 Docker 镜像加速配置"
  mkdir -p /etc/docker
  cat > "$cfg" <<'EOF'
{
  "registry-mirrors": [
    "https://docker.m.daocloud.io",
    "https://dockerproxy.com",
    "https://hub-mirror.c.163.com"
  ],
  "log-driver": "json-file",
  "log-opts": { "max-size": "100m", "max-file": "3" }
}
EOF
  systemctl daemon-reload
  systemctl restart docker
  log "Docker 镜像加速已生效"
}

install_compose() {
  if docker compose version >/dev/null 2>&1; then
    log "docker compose（插件版）已安装：$(docker compose version --short)"
    return
  fi
  if command -v docker-compose >/dev/null 2>&1; then
    log "docker-compose（独立版）已安装：$(docker-compose --version)"
    return
  fi
  warn "未检测到 docker compose，开始安装独立版"
  local ver="v2.27.0"
  local url="https://mirrors.aliyun.com/docker-toolbox/linux/compose/${ver}/docker-compose-Linux-x86_64"
  curl -fsSL "$url" -o /usr/local/bin/docker-compose || {
    err "下载失败，请检查网络或使用宝塔面板软件商店安装 Docker 管理器"
    exit 1
  }
  chmod +x /usr/local/bin/docker-compose
  log "docker-compose 安装完成：$(docker-compose --version)"
}

check_ports() {
  local ports=(3306 6379 9876 9216 9217 9218 80 8082 8083)
  local occupied=()
  for p in "${ports[@]}"; do
    if ss -ltn 2>/dev/null | awk '{print $4}' | grep -E "[:.]${p}\$" -q; then
      occupied+=("$p")
    fi
  done
  if [[ ${#occupied[@]} -eq 0 ]]; then
    log "关键端口全部空闲：${ports[*]}"
  else
    warn "下列端口已被占用，请在 .env 调整或停止占用进程：${occupied[*]}"
  fi
}

summary() {
  echo
  echo "========= 环境总结 ========="
  echo "系统          ：${OS_ID} ${OS_VER}"
  echo "Docker        ：$(docker --version 2>/dev/null || echo 缺失)"
  echo "Compose       ：$(docker compose version --short 2>/dev/null || docker-compose --version 2>/dev/null || echo 缺失)"
  echo "内核          ：$(uname -r)"
  echo "CPU 核心      ：$(nproc)"
  echo "内存          ：$(free -h | awk '/^Mem:/{print $2}')"
  echo "磁盘剩余      ：$(df -h / | awk 'NR==2{print $4}')"
  echo "============================"
  log "环境准备完毕，下一步：编辑 deploy/.env 后执行 bash deploy/deploy.sh"
}

main() {
  require_root
  detect_os
  install_docker
  configure_docker_mirror
  install_compose
  check_ports
  summary
}
main "$@"
