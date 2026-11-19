@echo off
rem Decode benchmark on real label crops, on the connected phone or emulator.
rem   tools\decode_bench.bat                 (one device connected)
rem   tools\decode_bench.bat 192.168.x.y:5555
rem Installs com.sweepscan.debug + its test package, runs DecodeBenchmarkTest, prints the table
rem (also saved to samples\decode-bench.txt) and removes both packages again. The real app
rem (com.sweepscan) and its count are never touched.
rem Gradle's connectedAndroidTest is not used: it cannot handle a Wi-Fi serial ("ip:port") on Windows.
setlocal
set "ROOT=%~dp0.."
if exist "%ProgramFiles%\Android\Android Studio\jbr\bin\java.exe" set "JAVA_HOME=%ProgramFiles%\Android\Android Studio\jbr"
set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
if not "%~1"=="" set "ANDROID_SERIAL=%~1"

call "%ROOT%\gradlew.bat" -p "%ROOT%" :app:assembleFullDebug :app:assembleFullDebugAndroidTest -q || exit /b 1
"%ADB%" install -r -t "%ROOT%\app\build\outputs\apk\full\debug\app-full-debug.apk" || exit /b 1
"%ADB%" install -r -t "%ROOT%\app\build\outputs\apk\androidTest\full\debug\app-full-debug-androidTest.apk" || exit /b 1
"%ADB%" logcat -c
"%ADB%" shell am instrument -w com.sweepscan.debug.test/androidx.test.runner.AndroidJUnitRunner
set "RESULT=%ERRORLEVEL%"
if not exist "%ROOT%\samples" mkdir "%ROOT%\samples"
"%ADB%" logcat -d -s DecodeBench:I -v raw > "%ROOT%\samples\decode-bench.txt"
type "%ROOT%\samples\decode-bench.txt"
"%ADB%" uninstall com.sweepscan.debug.test >nul
"%ADB%" uninstall com.sweepscan.debug >nul
exit /b %RESULT%
