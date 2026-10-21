#!/usr/bin/env bash
set -e

echo "==================================================="
echo "  AMORA Bot - Automated Git Commit & Push"
echo "==================================================="

# Navigate to script directory
cd "$(dirname "$0")"

echo "[1/3] Staging files..."
git add .

# Check if there are staged changes
if git diff --cached --quiet; then
    echo "[INFO] No new changes to commit."
else
    # Determine commit message
    COMMIT_MSG="$1"
    if [ -z "$COMMIT_MSG" ]; then
        read -r -p "Enter commit message (Press Enter for auto-date): " USER_INPUT
        COMMIT_MSG="$USER_INPUT"
    fi

    if [ -z "$COMMIT_MSG" ]; then
        TIMESTAMP=$(date +"%Y-%m-%d %H:%M")
        COMMIT_MSG="update: AMORA bot updates ($TIMESTAMP)"
    fi

    echo "[2/3] Committing changes: $COMMIT_MSG"
    git commit -m "$COMMIT_MSG"
fi

echo "[3/3] Pushing to GitHub (origin main)..."
git push origin main

echo "==================================================="
echo "  [SUCCESS] Successfully pushed to GlitchMatcha!"
echo "==================================================="
