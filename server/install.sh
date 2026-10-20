#!/bin/bash
# DSH 控制台后端 · 安装脚本
#
# 用法:
#   sudo ./install.sh [安装目录]
#
# 默认安装到 /opt/dsh-console
set -e

INSTALL_DIR="${1:-/opt/dsh-console}"
SERVICE_NAME="dsh-console"
SRC_DIR="$(cd "$(dirname "$0")/.." && pwd)"

if [ "$(id -u)" != "0" ]; then
    echo "需要 root 权限：sudo $0 $INSTALL_DIR"
    exit 1
fi

echo "=== 1. 创建安装目录 ==="
mkdir -p "$INSTALL_DIR/server"
cp "$SRC_DIR/server/server.js" "$INSTALL_DIR/server/"
echo "  ✓ $INSTALL_DIR/server/server.js"

echo
echo "=== 2. 生成 systemd 单元 ==="
sed -e "s|USER|$(logname 2>/dev/null || echo "$USER")|g" \
    -e "s|INSTALL_DIR|$INSTALL_DIR|g" \
    "$SRC_DIR/server/systemd/dsh-console.service" > "/etc/systemd/system/$SERVICE_NAME.service"
echo "  ✓ /etc/systemd/system/$SERVICE_NAME.service"

echo
echo "=== 3. 提示配置 ==="
cat <<TXT
  ⚠ 请编辑 /etc/systemd/system/$SERVICE_NAME.service 设置：
    · DSH_CONSOLE_ROOTS   文件白名单
    · DSH_CONSOLE_LOG     编译日志路径
    · DSH_CONSOLE_DAEMONS 守护进程列表
    · DSH_CONSOLE_TOKEN   （可选）API 访问令牌

TXT

echo "=== 4. 启动 ==="
systemctl daemon-reload
systemctl enable $SERVICE_NAME
systemctl restart $SERVICE_NAME
sleep 2
systemctl --no-pager status $SERVICE_NAME | head -10

echo
echo "完成。默认地址: http://<本机IP>:8080"
echo "在 App 的 设置 → 服务器 中填入该地址。"
