#!/bin/bash
# DSH 控制台后端 · 安装脚本
#
# 用法:
#   sudo ./install.sh [安装目录]
#
# 默认安装到 /opt/dsh-console
# 依赖: node (建议 18+)、systemd
set -e

INSTALL_DIR="${1:-/opt/dsh-console}"
SERVICE_NAME="dsh-console"
SERVICE_USER="$(logname 2>/dev/null || echo "${SUDO_USER:-$USER}")"
SRC_DIR="$(cd "$(dirname "$0")/.." && pwd)"

if [ "$(id -u)" != "0" ]; then
    echo "需要 root 权限：sudo $0 $INSTALL_DIR"
    exit 1
fi

if ! command -v node >/dev/null 2>&1; then
    echo "找不到 node，请先安装 Node.js（建议 18 或更新）："
    echo "  curl -fsSL https://deb.nodesource.com/setup_20.x | sudo -E bash - && sudo apt install -y nodejs"
    exit 1
fi

NODE_BIN="$(command -v node)"
NODE_MAJOR="$(node -p 'process.versions.node.split(".")[0]')"
if [ "$NODE_MAJOR" -lt 14 ]; then
    echo "Node.js 版本过低（当前 $(node -v)），需要 14 以上"
    exit 1
fi
echo "  ✓ node $(node -v) @ $NODE_BIN"

echo "=== 1. 创建安装目录 ==="
mkdir -p "$INSTALL_DIR/server"
install -m 0644 "$SRC_DIR/server/server.js" "$INSTALL_DIR/server/server.js"
echo "  ✓ $INSTALL_DIR/server/server.js"

echo
echo "=== 2. 生成 systemd 单元 ==="
sed -e "s|USER|$SERVICE_USER|g" \
    -e "s|INSTALL_DIR|$INSTALL_DIR|g" \
    -e "s|/usr/bin/node|$NODE_BIN|g" \
    "$SRC_DIR/server/systemd/dsh-console.service" > "/etc/systemd/system/$SERVICE_NAME.service"
echo "  ✓ /etc/systemd/system/$SERVICE_NAME.service"
echo "  ✓ 服务用户: $SERVICE_USER"
echo "  ✓ 状态目录: /var/lib/dsh-console（由 systemd 自动创建）"

echo
echo "=== 3. 提示配置 ==="
cat <<TXT
  请按需编辑 /etc/systemd/system/$SERVICE_NAME.service：

    DSH_CONSOLE_ROOTS     文件白名单   "名称:路径[:rw|ro],..."
    DSH_CONSOLE_LOG       编译日志路径
    DSH_CONSOLE_DAEMONS   守护进程     "名称:单元名[:critical|optional],..."
    DSH_CONSOLE_TOKEN     （可选）API 访问令牌

TXT

echo "=== 4. 启动 ==="
systemctl daemon-reload
systemctl enable "$SERVICE_NAME"
systemctl restart "$SERVICE_NAME"
sleep 2
systemctl --no-pager status "$SERVICE_NAME" | head -12 || true

echo
echo "完成。默认地址: http://<本机IP>:8080"
echo
echo "在 App 的「设置 → 服务器」中填入："
echo "  服务器地址   <本机IP>"
echo "  状态端口     8080"
echo "  文件端口     8080     ← 合并后端同一个端口"
echo "  日志端口     8080     ← 合并后端同一个端口"
echo
echo "验证：curl http://127.0.0.1:8080/api/health"
