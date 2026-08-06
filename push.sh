#!/bin/bash
set -e

REMOTE="origin"
LOCAL_BRANCH=$(git rev-parse --abbrev-ref HEAD)
REMOTE_BRANCH="$LOCAL_BRANCH"
WORKFLOW_TITLE=""

# 引数パース: [remote_branch] [--workflow|-w <ジョブタイトル>]
while [[ $# -gt 0 ]]; do
    case "$1" in
        --workflow|-w) WORKFLOW_TITLE="$2"; shift 2 ;;
        *) REMOTE_BRANCH="$1"; shift ;;
    esac
done

# git remote から GitHub リポジトリ名を自動検出
GITHUB_REPO=$(git remote get-url "$REMOTE" 2>/dev/null \
    | sed -E 's|.*github\.com[:/]||; s|\.git$||')

# ワークフロータイトルを自動検出（--workflow 未指定時）
# YAML の name: フィールドを読む
if [ -z "$WORKFLOW_TITLE" ]; then
    WF_COUNT=$(find .github/workflows -maxdepth 1 \( -name "*.yml" -o -name "*.yaml" \) 2>/dev/null | wc -l)
    if [ "$WF_COUNT" -eq 1 ]; then
        WF_PATH=$(find .github/workflows -maxdepth 1 \( -name "*.yml" -o -name "*.yaml" \) 2>/dev/null)
        WORKFLOW_TITLE=$(grep '^name:' "$WF_PATH" 2>/dev/null | head -1 | sed 's/^name:[[:space:]]*//')
    elif [ "$WF_COUNT" -gt 1 ]; then
        echo "複数のワークフローが存在します。--workflow <タイトル> で指定してください:"
        find .github/workflows -maxdepth 1 \( -name "*.yml" -o -name "*.yaml" \) 2>/dev/null \
            | xargs -I{} grep '^name:' {} | sed 's/^name:[[:space:]]*//'
        exit 1
    fi
fi

echo "=== Push: $LOCAL_BRANCH -> $REMOTE/$REMOTE_BRANCH ==="

git add -u
git commit -m "update" 2>/dev/null || echo "(nothing to commit, skipping)"
git push "$REMOTE" "$LOCAL_BRANCH:$REMOTE_BRANCH"

# push トリガーの遅延対策: workflow_dispatch で即時トリガー
if [ -n "$WORKFLOW_TITLE" ] && [ -n "$GITHUB_REPO" ] && [ "$REMOTE_BRANCH" = "main" ]; then
    echo "=== Triggering: \"$WORKFLOW_TITLE\" @ $GITHUB_REPO ==="
    gh workflow run "$WORKFLOW_TITLE" --repo "$GITHUB_REPO" --ref "$REMOTE_BRANCH"
fi

echo "=== Done: $REMOTE/$REMOTE_BRANCH ==="
