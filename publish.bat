@echo off
setlocal enabledelayedexpansion

echo ===================================================
echo   AMORA Bot - Automated Git Commit & Push
echo ===================================================

:: Check if git is available
where git >nul 2>&1
if %ERRORLEVEL% neq 0 (
    echo [ERROR] Git is not installed or not in PATH.
    pause
    exit /b 1
)

:: Ensure we are in the repository directory
cd /d "%~dp0"

:: Stage all tracked and new non-ignored files
echo [1/3] Staging files...
git add .

:: Check if there are changes to commit
git diff --cached --quiet
if %ERRORLEVEL% equ 0 (
    echo [INFO] No changes detected to commit.
    goto PUSH_ONLY
)

:: Get commit message from argument or prompt user
set "COMMIT_MSG=%~1"
if "!COMMIT_MSG!"=="" (
    set /p "COMMIT_MSG=Enter commit message (Press Enter for auto-date): "
)

if "!COMMIT_MSG!"=="" (
    for /f "tokens=2 delims==" %%I in ('wmic os get localdatetime /value') do set "DT=%%I"
    set "TIMESTAMP=!DT:~0,4!-!DT:~4,2!-!DT:~6,2! !DT:~8,2!:!DT:~10,2!"
    set "COMMIT_MSG=update: AMORA bot updates (!TIMESTAMP!)"
)

echo [2/3] Committing changes with message: "!COMMIT_MSG!"
git commit -m "!COMMIT_MSG!"
if %ERRORLEVEL% neq 0 (
    echo [ERROR] Git commit failed.
    pause
    exit /b 1
)

:PUSH_ONLY
echo [3/3] Pushing to GitHub (origin main)...
git push origin main
if %ERRORLEVEL% equ 0 (
    echo ===================================================
    echo   [SUCCESS] Successfully pushed to GlitchMatcha!
    echo ===================================================
) else (
    echo ===================================================
    echo   [ERROR] Push failed.
    echo   If prompted, sign in as GlitchMatcha in the pop-up.
    echo ===================================================
    pause
    exit /b 1
)

endlocal
