@echo off
chcp 65001 > nul

setlocal enabledelayedexpansion

set SCRIPT_DIR=%~dp0

:find_git_root
for %%i in ("%SCRIPT_DIR%.") do set "PARENT=%%~dpi"
if exist "%PARENT%.git" (
    set PROJECT_DIR=%PARENT%
    goto :found_git
)
set "SCRIPT_DIR=%PARENT%"
if not "%SCRIPT_DIR%"=="%PARENT%" goto :find_git_root
echo Error: Not a git repository
exit /b 1

:found_git
cd /d "%PROJECT_DIR%"

for /f "tokens=2 delims==" %%a in ('wmic OS Get localdatetime /value') do set "dt=%%a"
set TIMESTAMP=%dt:~0,8%_%dt:~8,6%
set DIFF_FILE=%PROJECT_DIR%scripts\code_review_%TIMESTAMP%.diff

echo Current directory: %CD%
echo Git repository found!
echo.

echo Generating Diff file...

git diff HEAD > "%DIFF_FILE%"

for %%A in ("%DIFF_FILE%") do if %%~zA==0 (
    echo Error: No code changes found
    echo.
    echo Tip: Make changes to files first, or use 'git add' to stage changes
    del "%DIFF_FILE%"
    exit /b 1
)

echo.
echo ==========================================
echo Diff file generated: %DIFF_FILE%
echo ==========================================
echo.
echo Please use the following prompt in Cursor for CodeReview:
echo.
echo ==========================================
echo Read the rules first:
echo Use @^{alc-review^} rules to CodeReview the following diff
echo ==========================================
echo.
type "%DIFF_FILE%"

endlocal