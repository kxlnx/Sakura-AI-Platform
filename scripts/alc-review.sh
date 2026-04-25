#!/bin/bash

# AI CodeReview Diff 生成脚本
# 使用方式：
#   ./alc-review.sh              # 生成暂存区变更的diff
#   ./alc-review.sh --all        # 生成所有未合并到master的变更
#   ./alc-review.sh --base dev   # 基于dev分支生成diff

# 解析参数
BASE_BRANCH="master"
SCOPE="staged"

while [[ $# -gt 0 ]]; do
    case $1 in
        --all)
            SCOPE="all"
            shift
            ;;
        --base)
            BASE_BRANCH="$2"
            shift 2
            ;;
        *)
            echo "未知参数: $1"
            echo "使用方式："
            echo "  ./alc-review.sh              # 生成暂存区变更的diff"
            echo "  ./alc-review.sh --all       # 生成所有未合并到master的变更"
            echo "  ./alc-review.sh --base dev  # 基于dev分支生成diff"
            exit 1
            ;;
    esac
done

# 生成diff文件
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
DIFF_FILE="code_review_${TIMESTAMP}.diff"

if [ "$SCOPE" == "staged" ]; then
    echo "生成暂存区变更的diff..."
    git diff --cached > "$DIFF_FILE"
elif [ "$SCOPE" == "all" ]; then
    echo "生成与${BASE_BRANCH}分支的diff..."
    git diff "${BASE_BRANCH}..HEAD" > "$DIFF_FILE"
fi

# 检查diff是否为空
if [ ! -s "$DIFF_FILE" ]; then
    echo "错误：没有发现任何代码变更"
    rm "$DIFF_FILE"
    exit 1
fi

echo ""
echo "=========================================="
echo "Diff文件已生成: $DIFF_FILE"
echo "=========================================="
echo ""
echo "请在Cursor中输入以下提示词进行CodeReview："
echo ""
echo "=========================================="
echo "【重要】请先阅读规则文件："
echo "使用 @{alc-review} 规则对以下diff进行CodeReview"
echo "=========================================="
echo ""
cat "$DIFF_FILE"