@echo off
setlocal
where java >nul 2>nul || (echo [ERROR] Java not found & pause & exit /b 1)
if not exist gradlew.bat (
  echo [INFO] Gradle wrapper launcher is not bundled in this source-only export.
  echo Open this project in Android Studio and choose Build ^> Build APK(s).
  pause
  exit /b 1
)
call gradlew.bat assembleDebug
if errorlevel 1 pause
endlocal
