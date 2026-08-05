#!/bin/bash
set -e

REMOTE="origin"
LOCAL_BRANCH=$(git rev-parse --abbrev-ref HEAD)
REMOTE_BRANCH="${1:-$LOCAL_BRANCH}"

echo "=== Push: $LOCAL_BRANCH -> $REMOTE/$REMOTE_BRANCH ==="

git add -u
git commit -m "update" 2>/dev/null || echo "(nothing to commit, skipping)"
git push "$REMOTE" "$LOCAL_BRANCH:$REMOTE_BRANCH"

echo "=== Done: $REMOTE/$REMOTE_BRANCH — GitHub Actions triggered ==="
