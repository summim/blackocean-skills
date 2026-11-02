@echo off
chcp 65001 >nul
cd /d "%~dp0"
echo ========================================
echo  BlackOcean Skills 빌드 (처음엔 5~10분)
echo ========================================
call gradlew.bat build
if errorlevel 1 (
  echo.
  echo [실패] 위의 빨간 오류를 그대로 복사해서 Claude 에게 보여주세요.
  pause
  exit /b 1
)
echo.
echo [완료] build\libs\blackocean_skills-1.0.0.jar
echo 이 파일을 서버 mods 폴더와 접속기 모드팩 둘 다에 넣으세요.
pause
