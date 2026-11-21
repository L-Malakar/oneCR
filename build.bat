@echo off
setlocal EnableExtensions EnableDelayedExpansion
title oneCR build

REM =====================================================================
REM  oneCR - one-command build for Windows
REM
REM  Output:  oneCR\Android\oneCR-v<VERSION>.apk
REM
REM  Usage:   build.bat [--clean] [--debug] [--install] [--help]
REM
REM  Requirements:
REM    - JDK 17 or newer (JAVA_HOME set, or java on PATH)
REM    - Android SDK via ANDROID_HOME, or a default Android Studio install
REM    - First run auto-generates a local signing keystore in .signing\
REM      (gitignored) so every future build installs as an update.
REM =====================================================================

cd /d "%~dp0"
set "ROOT=%CD%"

REM ---------------------------------------------------------------------
REM 0. Parse arguments
REM ---------------------------------------------------------------------
set "BUILD_TYPE=release"
set "TASK=assembleRelease"
set "APK_SRC=app\build\outputs\apk\release\app-release.apk"
set "CLEAN=0"
set "INSTALL=0"
set "SHOWHELP=0"

:parse_args
if "%~1"=="" goto args_done
if /i "%~1"=="--clean"   ( set "CLEAN=1" & shift & goto parse_args )
if /i "%~1"=="--debug"   ( set "BUILD_TYPE=debug" & set "TASK=assembleDebug" & set "APK_SRC=app\build\outputs\apk\debug\app-debug.apk" & shift & goto parse_args )
if /i "%~1"=="--install" ( set "INSTALL=1" & shift & goto parse_args )
if /i "%~1"=="--help"    ( set "SHOWHELP=1" & shift & goto parse_args )
echo [oneCR] Unknown option: %~1
shift
goto parse_args

:args_done
if "%SHOWHELP%"=="1" goto usage

echo.
echo ============================================================
echo   oneCR build
echo ============================================================

REM ---------------------------------------------------------------------
REM 1. Read VERSION_NAME from gradle.properties (single source of truth)
REM ---------------------------------------------------------------------
set "VERSION="
for /f "tokens=1,* delims==" %%A in (gradle.properties) do (
    if /i "%%A"=="VERSION_NAME" set "VERSION=%%B"
)
if not defined VERSION set "VERSION=0.1.0"
echo [oneCR] Version : v%VERSION%
echo [oneCR] Type    : %BUILD_TYPE%
echo [oneCR] Root    : %ROOT%

REM ---------------------------------------------------------------------
REM 2. Java check (JDK 17+)
REM ---------------------------------------------------------------------
if defined JAVA_HOME set "PATH=%JAVA_HOME%\bin;%PATH%"
where java >nul 2>nul
if errorlevel 1 (
    echo [oneCR] ERROR: Java not found.
    echo         Install JDK 17 or newer from https://adoptium.net
    echo         and set JAVA_HOME to the JDK folder.
    exit /b 1
)
set "JMAJOR="
for /f "tokens=3" %%v in ('java -version 2^>^&1 ^| findstr /i "version"') do (
    if not defined JMAJOR (
        set JV=%%v
        set JV=!JV:"=!
        for /f "delims=. tokens=1" %%m in ("!JV!") do set "JMAJOR=%%m"
    )
)
if defined JMAJOR (
    if !JMAJOR! LSS 17 (
        echo [oneCR] ERROR: JDK 17 or newer is required, found major version !JMAJOR!.
        exit /b 1
    )
) else (
    echo [oneCR] WARNING: could not detect the Java version, continuing anyway.
)
echo [oneCR] Java    : OK

REM ---------------------------------------------------------------------
REM 3. Android SDK check
REM ---------------------------------------------------------------------
set "SDK="
if defined ANDROID_HOME if exist "%ANDROID_HOME%" set "SDK=%ANDROID_HOME%"
if not defined SDK if exist "%LOCALAPPDATA%\Android\Sdk" set "SDK=%LOCALAPPDATA%\Android\Sdk"
set "PF86=%ProgramFiles(x86)%"
if not defined SDK if defined PF86 if exist "%PF86%\Android\android-sdk" set "SDK=%PF86%\Android\android-sdk"
if not defined SDK (
    echo [oneCR] ERROR: Android SDK not found.
    echo         Install Android Studio ^(it installs the SDK to
    echo         %%LOCALAPPDATA%%\Android\Sdk^) or set ANDROID_HOME manually.
    exit /b 1
)
echo [oneCR] Android SDK: %SDK%
if not exist "%SDK%\platforms\android-35" (
    echo [oneCR] WARNING: platform android-35 not found in this SDK.
    echo         Open Android Studio - SDK Manager and install
    echo         "Android SDK Platform 35", or accept licenses once so
    echo         Gradle can fetch it automatically.
)
if not exist local.properties (
    echo sdk.dir=%SDK:\=\\%> local.properties
    echo [oneCR] Wrote local.properties ^(sdk.dir^)
)

REM ---------------------------------------------------------------------
REM 4. Local signing keystore (auto-generated on first run, gitignored)
REM ---------------------------------------------------------------------
if exist ".signing\onecr.jks" goto keystore_ok
echo [oneCR] First run: generating local signing keystore ...
if not exist ".signing" mkdir ".signing"
set "KSPASS="
for /f "delims=" %%p in ('powershell -NoProfile -Command "[guid]::NewGuid().ToString('N').Substring(0,20)" 2^>nul') do set "KSPASS=%%p"
if not defined KSPASS set "KSPASS=onecr%RANDOM%%RANDOM%"
set "KEYTOOL=keytool"
if defined JAVA_HOME set "KEYTOOL=%JAVA_HOME%\bin\keytool.exe"
"%KEYTOOL%" -genkeypair -v -keystore ".signing\onecr.jks" -alias onecr -keyalg RSA -keysize 2048 -validity 10000 -storepass "%KSPASS%" -keypass "%KSPASS%" -dname "CN=oneCR, OU=oneCR, O=Lalmohan Malakar, C=IN" >nul 2>&1
if errorlevel 1 (
    echo [oneCR] ERROR: keytool could not generate the keystore.
    echo         Verify that JAVA_HOME points to a JDK 17+ installation.
    exit /b 1
)
> ".signing\keystore.properties" (
    echo storeFile=.signing/onecr.jks
    echo keyAlias=onecr
    echo storePassword=%KSPASS%
    echo keyPassword=%KSPASS%
)
echo [oneCR] Keystore ready: .signing\onecr.jks
echo [oneCR] The password is stored in .signing\keystore.properties
echo [oneCR] NEVER commit the .signing folder.
:keystore_ok

REM ---------------------------------------------------------------------
REM 5. Gradle build
REM ---------------------------------------------------------------------
if "%CLEAN%"=="1" (
    echo [oneCR] Cleaning previous build outputs ...
    call gradlew.bat --no-daemon clean
    if errorlevel 1 (
        echo [oneCR] ERROR: clean failed.
        exit /b 1
    )
)
echo [oneCR] Running Gradle: :app:%TASK%
echo.
call gradlew.bat :app:%TASK%
if errorlevel 1 (
    echo.
    echo [oneCR] ERROR: Gradle build FAILED.
    echo         Re-run with: build.bat --clean
    exit /b 1
)

REM ---------------------------------------------------------------------
REM 6. Publish APK to oneCR\Android\
REM ---------------------------------------------------------------------
if not exist "%APK_SRC%" (
    echo [oneCR] ERROR: expected APK not found at %APK_SRC%
    exit /b 1
)
set "OUT_NAME=oneCR-v%VERSION%.apk"
if "%BUILD_TYPE%"=="debug" set "OUT_NAME=oneCR-v%VERSION%-debug.apk"
if not exist "oneCR\Android" mkdir "oneCR\Android"
copy /Y "%APK_SRC%" "oneCR\Android\%OUT_NAME%" >nul
echo.
echo [oneCR] ============================================================
echo [oneCR] APK written to: oneCR\Android\%OUT_NAME%
for %%F in ("oneCR\Android\%OUT_NAME%") do echo [oneCR] Size: %%~zF bytes
echo [oneCR] SHA-256:
certutil -hashfile "oneCR\Android\%OUT_NAME%" SHA256
echo [oneCR] ============================================================

REM ---------------------------------------------------------------------
REM 7. Optional install
REM ---------------------------------------------------------------------
if not "%INSTALL%"=="1" goto done
set "ADB=%SDK%\platform-tools\adb.exe"
if not exist "%ADB%" (
    echo [oneCR] WARNING: adb.exe not found at %ADB% - skipped install.
    goto done
)
echo [oneCR] Installing on connected device ...
"%ADB%" install -r "oneCR\Android\%OUT_NAME%"

:done
echo [oneCR] Done.
endlocal
exit /b 0

:usage
echo.
echo Usage: build.bat [options]
echo.
echo   --clean    delete build outputs before compiling
echo   --debug    build the debug APK instead of release
echo   --install  install the APK on a connected device via adb
echo   --help     show this help
echo.
echo Output: oneCR\Android\oneCR-v^<version^>.apk
echo.
exit /b 0
