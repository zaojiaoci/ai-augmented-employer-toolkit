#!/usr/bin/env bash
#
# openKylin 验证脚本
#
# 用途：在 openKylin 环境一键完成「环境采集 + 全量测试 + 长期记忆评测」，
#       产出可直接作为参赛佐证的输出（环境信息、测试结果、六维指标报告）。
#
# 用法：
#   ./scripts/verify-openkylin.sh
#
# 说明：
#   - 全部用例不依赖 API Key，离线环境也能跑完（需要 Key 的测试会自动跳过）。
#   - 若本机已配置 AI_DASHSCOPE_API_KEY，会额外执行需 Key 的测试。
#
set -euo pipefail

cd "$(dirname "$0")/.."

echo "=============================================="
echo " 1/3 环境信息采集"
echo "=============================================="
echo "主机架构: $(uname -m)"
echo "内核:     $(uname -r)"
if [ -f /etc/os-release ]; then
  echo "系统:     $(. /etc/os-release && echo "$PRETTY_NAME")"
fi
echo "JDK:      $(java -version 2>&1 | head -n 1)"
echo "内存:     $(free -h 2>/dev/null | awk 'NR==2{print $2}' || echo '未知')"
echo "目录:     $(pwd)"
echo

echo "=============================================="
echo " 2/3 全量单元测试"
echo "=============================================="
./mvnw test
echo

echo "=============================================="
echo " 3/3 长期记忆评测（六维 benchmark）"
echo "=============================================="
./mvnw test -Dtest=MemoryBenchmarkRunnerTest
echo

echo "=============================================="
echo " 验证完成"
echo "=============================================="
echo "上方「长期记忆评测报告」即为自动产出的多维指标，可直接作为参赛材料佐证。"
echo "评测过程证据由 EvidenceCollector 导出为 JSON Lines（见测试用例中的导出路径）。"
