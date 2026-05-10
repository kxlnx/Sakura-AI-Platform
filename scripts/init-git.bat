@echo off
chcp 65001 > nul

set PROJECT_DIR=%~dp0
cd /d "%PROJECT_DIR%"

echo Initializing Git repository...
git init

echo Adding files...
git add .

echo Creating initial commit...
git commit -m "Initial commit: sakura-agent project"

git branch -M master

echo.
echo ==========================================
echo Git repository initialized successfully!
echo ==========================================
echo.
echo You can now run AI CodeReview:
echo   scripts\alc-review.bat all
echo.

pause