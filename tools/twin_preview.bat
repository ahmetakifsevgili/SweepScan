@echo off
rem Draws the app's 3D scenes on this PC with the app's own shaders (WebGL2), to check the look
rem without a phone. Writes build\twin_preview.html; open it in a browser through a local server
rem (python -m http.server 8765, then http://localhost:8765/build/twin_preview.html).
setlocal
if not defined JAVA_HOME set "JAVA_HOME=%ProgramFiles%\Android\Android Studio\jbr"
cd /d "%~dp0.."
set "TWIN_EXPORT=%CD%\build\twin_scenes.js"
if not exist build mkdir build
call gradlew.bat :core:test --tests com.sweepscan.twin.TwinExportTest --rerun -q || exit /b 1
python tools\twin_preview.py "%TWIN_EXPORT%" build\twin_preview.html
