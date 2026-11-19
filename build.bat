@echo off
rem Builds SweepScan with the JDK bundled in Android Studio (the JDK on PATH may be too old),
rem runs the unit tests and copies the installable APK to dist\.
setlocal
if not defined JAVA_HOME set "JAVA_HOME=%ProgramFiles%\Android\Android Studio\jbr"
if not exist "%JAVA_HOME%\bin\java.exe" (
    echo JDK not found. Install Android Studio or set JAVA_HOME to a JDK 17+.
    exit /b 1
)
cd /d "%~dp0"
call "%~dp0gradlew.bat" :core:test :app:testFullDebugUnitTest :app:lintFullDebug :app:assembleFullRelease :app:assembleDosyaRelease || (
    echo.
    echo BUILD FAILED
    exit /b 1
)
if not exist dist mkdir dist
copy /y "app\build\outputs\apk\full\release\app-full-release.apk" "dist\SweepScan.apk" >nul
copy /y "app\build\outputs\apk\dosya\release\app-dosya-release.apk" "dist\SweepScan-dosya.apk" >nul
echo.
echo OK: dist\SweepScan.apk (full: install.bat, from the PC)  +  dist\SweepScan-dosya.apk (to send as a file)
