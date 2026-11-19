@echo off
rem Installs SweepScan on the phone connected by USB (USB debugging on), grants its permissions
rem and starts it. Usage: install.bat [path-to-apk]
setlocal
set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
if not exist "%ADB%" set "ADB=adb"
set "APK=%~1"
if "%APK%"=="" set "APK=%~dp0dist\SweepScan.apk"
if not exist "%APK%" (
    echo APK not found: %APK%
    echo Run build.bat first.
    exit /b 1
)

"%ADB%" start-server >nul 2>&1
set "STATE="
for /f "skip=1 tokens=2" %%s in ('"%ADB%" devices') do if not defined STATE set "STATE=%%s"
if not defined STATE (
    echo No phone found.
    echo  1. Phone: Settings - About phone - Software information - tap "Build number" 7 times.
    echo  2. Phone: Settings - Developer options - turn on "USB debugging".
    echo  3. Connect the USB cable and choose "Allow" on the phone.
    exit /b 1
)
if "%STATE%"=="unauthorized" (
    echo The phone is connected but has not allowed this PC.
    echo Unlock the phone, choose "Allow USB debugging", then run install.bat again.
    exit /b 1
)
if not "%STATE%"=="device" (
    echo The phone is not ready ^(state: %STATE%^). Reconnect the cable and try again.
    exit /b 1
)

echo Installing %APK% ...
"%ADB%" install -r "%APK%" || (
    echo.
    echo INSTALL FAILED. If the message says the signatures do not match, uninstall the old
    echo SweepScan from the phone first ^(this clears its saved session^) and run install.bat again.
    exit /b 1
)
rem Without these the app asks on first start; granting them here is just a convenience.
"%ADB%" shell pm grant com.sweepscan android.permission.CAMERA >nul 2>&1
"%ADB%" shell pm grant com.sweepscan android.permission.BLUETOOTH_CONNECT >nul 2>&1
"%ADB%" shell pm grant com.sweepscan android.permission.BLUETOOTH_ADVERTISE >nul 2>&1
"%ADB%" shell am start -n com.sweepscan/.ui.MainActivity >nul
echo.
echo OK: SweepScan is installed and running on the phone.
