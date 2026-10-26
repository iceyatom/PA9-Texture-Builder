@echo off
rem Builds the release jar for whichever Minecraft version this checkout targets.
rem Versions come from gradle.properties, so this works unchanged on every branch
rem (main / 26.3-Release / 1.20.1 ...). Double-click it, or run "build-jar.bat --no-pause".
rem
rem Deliberately does NOT run "gradlew clean": that would delete jars for other
rem Minecraft versions that are kept in build\libs.

setlocal EnableDelayedExpansion
cd /d "%~dp0"

for /f "usebackq tokens=1,* delims==" %%A in ("gradle.properties") do (
	if "%%A"=="minecraft_version" set "MC_VERSION=%%B"
	if "%%A"=="mod_version" set "MOD_VERSION=%%B"
	if "%%A"=="archives_base_name" set "BASE_NAME=%%B"
)

if not defined MC_VERSION (
	echo Could not read minecraft_version from gradle.properties.
	goto :fail
)

set "JAR_NAME=%BASE_NAME% - %MC_VERSION% - %MOD_VERSION%.jar"
echo Building %JAR_NAME%
echo   Minecraft %MC_VERSION%, mod version %MOD_VERSION%
echo.

rem Full path: some shells (NoDefaultCurrentDirectoryInExePath) won't run programs from the current folder by bare name.
call "%~dp0gradlew.bat" build
if errorlevel 1 (
	echo.
	echo BUILD FAILED - see the errors above.
	goto :fail
)

if not exist "build\libs\%JAR_NAME%" (
	echo.
	echo Build finished but "build\libs\%JAR_NAME%" was not found.
	goto :fail
)

echo.
echo Done. Jar files:
for %%F in ("build\libs\%BASE_NAME% - %MC_VERSION% - %MOD_VERSION%*.jar") do echo   %%~fF
echo.
echo Copy "%JAR_NAME%" into .minecraft\mods (not the -sources jar).
call :maybe_pause %1
exit /b 0

:fail
call :maybe_pause %1
exit /b 1

:maybe_pause
if /i not "%~1"=="--no-pause" pause
exit /b 0
