@echo off
rem IwaraFlow 一键构建（Windows）。
rem 仓库里不放 gradle-wrapper.jar（二进制），所以这个脚本按下面的顺序找 Gradle：
rem   1) 仓库里有 gradlew.bat 就用它；
rem   2) 系统里装了 gradle 就用它；
rem   3) 都没有就按 gradle/wrapper/gradle-wrapper.properties 里写的版本下载一份，
rem      解压到 %USERPROFILE%\.gradle\iwaraflow-dist 下，之后再构建就直接复用。
setlocal enabledelayedexpansion

where java >nul 2>nul || (echo [ERROR] 没找到 Java，请先安装 JDK 17 或更新版本 & pause & exit /b 1)

set "TASK=%~1"
if "%TASK%"=="" set "TASK=assembleDebug"

if exist gradlew.bat (
  call gradlew.bat %TASK%
  goto :done
)

where gradle >nul 2>nul
if not errorlevel 1 (
  call gradle %TASK%
  goto :done
)

rem ---- 下载并使用指定版本的 Gradle
set "PROPS=gradle\wrapper\gradle-wrapper.properties"
if not exist "%PROPS%" (
  echo [ERROR] 找不到 %PROPS%，无法确定 Gradle 版本
  pause & exit /b 1
)
for /f "tokens=2 delims==" %%u in ('findstr /b distributionUrl "%PROPS%"') do set "DIST_URL=%%u"
set "DIST_URL=%DIST_URL:\:=:%"
for %%f in ("%DIST_URL%") do set "DIST_ZIP=%%~nxf"
for %%f in ("%DIST_ZIP%") do set "DIST_NAME=%%~nf"
set "DIST_NAME=%DIST_NAME:-bin=%"

set "HOME_DIR=%USERPROFILE%\.gradle\iwaraflow-dist"
set "GRADLE_BIN=%HOME_DIR%\%DIST_NAME%\bin\gradle.bat"

if not exist "%GRADLE_BIN%" (
  echo [INFO] 正在下载 %DIST_ZIP% ……（只需一次，约 100 MB）
  if not exist "%HOME_DIR%" mkdir "%HOME_DIR%"
  powershell -NoProfile -Command ^
    "$ErrorActionPreference='Stop';" ^
    "Invoke-WebRequest -Uri '%DIST_URL%' -OutFile '%HOME_DIR%\%DIST_ZIP%';" ^
    "Expand-Archive -Path '%HOME_DIR%\%DIST_ZIP%' -DestinationPath '%HOME_DIR%' -Force;" ^
    "Remove-Item '%HOME_DIR%\%DIST_ZIP%'"
  if errorlevel 1 (
    echo [ERROR] 下载或解压失败。也可以自行安装 Gradle 后重试，或用 Android Studio 打开本项目构建。
    pause & exit /b 1
  )
)

call "%GRADLE_BIN%" %TASK%

:done
if errorlevel 1 (
  echo [ERROR] 构建失败
  pause
  exit /b 1
)
echo [OK] 构建完成：app\build\outputs\apk
pause
endlocal
