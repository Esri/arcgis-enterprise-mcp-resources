@echo off
::
:: Installs the ArcGIS Enterprise MCP API and MCP API Archetype artifacts
:: into the local Maven repository for the selected ArcGIS Enterprise version.
::
:: Usage: install-maven-artifacts.bat <ArcGISVersion>
::   where <ArcGISVersion> is the full version, for example 12.2.0
::
setlocal

set "SUPPORTED_VERSIONS=11.1.0, 11.3.0, 11.5.0, 12.1.0, 12.2.0"

:: Resolve paths relative to this script so it can be run from any directory.
set "SCRIPT_DIR=%~dp0"

if "%~1"=="" goto usage
if not "%~2"=="" goto usage
if /I "%~1"=="/?" goto help
if /I "%~1"=="-h" goto help
if /I "%~1"=="--help" goto help

set "ARCGIS_VERSION=%~1"
set "VALID_VERSION="
set "SUGGESTED_VERSION="
for %%V in (11.1.0 11.3.0 11.5.0 12.1.0 12.2.0) do (
	if "%%V"=="%ARCGIS_VERSION%" set "VALID_VERSION=1"
	if "%%V"=="%ARCGIS_VERSION%.0" set "SUGGESTED_VERSION=%%V"
)
if not defined VALID_VERSION goto invalidVersion

set "MCP_VERSION=%ARCGIS_VERSION%"
set "REQUIRED_JDK="
if "%ARCGIS_VERSION%"=="12.2.0" set "REQUIRED_JDK=21"
if "%ARCGIS_VERSION%"=="12.1.0" set "REQUIRED_JDK=17"
if "%ARCGIS_VERSION%"=="11.5.0" set "REQUIRED_JDK=17"
if "%ARCGIS_VERSION%"=="11.3.0" set "REQUIRED_JDK=17"
if "%ARCGIS_VERSION%"=="11.1.0" set "REQUIRED_JDK=11"

:: RESOLVE ARTIFACTS
:: The artifacts and their folder both use the full version, for example artifacts\12.2.0\mcp-api-12.2.0.jar.
set "ARTIFACTS_DIR=%SCRIPT_DIR%artifacts\%ARCGIS_VERSION%"
if not exist "%ARTIFACTS_DIR%\" goto artifactsDirErr

set "API_JAR=%ARTIFACTS_DIR%\mcp-api-%MCP_VERSION%.jar"
set "API_POM=%ARTIFACTS_DIR%\mcp-api-%MCP_VERSION%.pom"
set "ARCHETYPE_JAR=%ARTIFACTS_DIR%\mcp-api-archetype-%MCP_VERSION%.jar"
set "ARCHETYPE_POM=%ARTIFACTS_DIR%\mcp-api-archetype-%MCP_VERSION%.pom"

set "MISSING_FILE="
for %%F in ("%API_JAR%" "%API_POM%" "%ARCHETYPE_JAR%" "%ARCHETYPE_POM%") do if not exist "%%~F" if not defined MISSING_FILE set "MISSING_FILE=%%~F"
if defined MISSING_FILE goto artifactMissingErr

:: CHECK IF JDK IS INSTALLED
if "%JAVA_HOME%"=="" goto javaHomeErr
if not exist "%JAVA_HOME%\bin\javac.exe" goto javaHomeErr

:: CHECK IF JDK VERSION MATCHES ARCGIS ENTERPRISE VERSION REQUIREMENT
:: Read the "javac <version>" line only. The JVM can print other lines first,
:: for example "Picked up JAVA_TOOL_OPTIONS: ..." when that variable is set.
set "JAVAC_VERSION_RAW="
for /f "tokens=1,2" %%A in ('"%JAVA_HOME%\bin\javac.exe" -version 2^>^&1') do (
	if /I "%%A"=="javac" if not defined JAVAC_VERSION_RAW set "JAVAC_VERSION_RAW=%%B"
)
if "%JAVAC_VERSION_RAW%"=="" goto jdkVersionParseErr
:: "21.0.2" -> "21", "21-ea" -> "21"
set "JDK_MAJOR="
for /f "tokens=1 delims=.-" %%M in ("%JAVAC_VERSION_RAW%") do set "JDK_MAJOR=%%M"
if "%JDK_MAJOR%"=="" goto jdkVersionParseErr
if not "%JDK_MAJOR%"=="%REQUIRED_JDK%" goto jdkVersionErr

:: CHECK IF MAVEN IS INSTALLED
call mvn -version > nul 2>&1 || goto mavenErr

:: INSTALL MCP API AND ARCHETYPE TO LOCAL MAVEN REPOSITORY
echo Installing MCP API %MCP_VERSION% to the local Maven repository...
call mvn install:install-file -Dfile="%API_JAR%" -DpomFile="%API_POM%" -Dpackaging=jar || goto apiInstallErr

echo Installing MCP API Archetype %MCP_VERSION% to the local Maven repository...
call mvn install:install-file -Dfile="%ARCHETYPE_JAR%" -DpomFile="%ARCHETYPE_POM%" -Dpackaging=jar || goto archetypeInstallErr

echo Installation completed successfully for ArcGIS Enterprise %ARCGIS_VERSION%.
goto end

:javaHomeErr
1>&2 echo ERROR: JAVA_HOME environment variable is not defined correctly. JAVA_HOME should point to a JDK, not a JRE.
goto exit

:jdkVersionParseErr
1>&2 echo ERROR: Could not determine JDK version from "%JAVA_HOME%\bin\javac.exe -version" output.
goto exit

:jdkVersionErr
1>&2 echo ERROR: ArcGIS Enterprise %ARCGIS_VERSION% requires JDK %REQUIRED_JDK%, but detected JDK %JDK_MAJOR%.
1>&2 echo        JAVA_HOME currently points to: %JAVA_HOME%
goto exit

:invalidVersion
if defined SUGGESTED_VERSION goto invalidShortVersion
1>&2 echo ERROR: Invalid ArcGIS Enterprise version "%ARCGIS_VERSION%". Allowed values: %SUPPORTED_VERSIONS%
goto exit

:invalidShortVersion
1>&2 echo ERROR: Invalid ArcGIS Enterprise version "%ARCGIS_VERSION%". Use the full version, for example %SUGGESTED_VERSION%. Allowed values: %SUPPORTED_VERSIONS%
goto exit

:artifactsDirErr
1>&2 echo ERROR: Artifacts folder not found: %ARTIFACTS_DIR%
goto exit

:artifactMissingErr
1>&2 echo ERROR: Required artifact not found: %MISSING_FILE%
1>&2 echo        Make sure all four artifact files for ArcGIS Enterprise %ARCGIS_VERSION% are present. See %ARTIFACTS_DIR%\readme.md.
goto exit

:mavenErr
1>&2 echo ERROR: Maven is not installed or not available on PATH.
goto exit

:apiInstallErr
1>&2 echo ERROR: Failed to install the MCP API artifact.
goto exit

:archetypeInstallErr
1>&2 echo ERROR: Failed to install the MCP API Archetype artifact.
goto exit

:help
echo Usage: install-maven-artifacts.bat ^<ArcGISVersion^>
echo.
echo ArcGISVersion must be one of: %SUPPORTED_VERSIONS%
echo Example: install-maven-artifacts.bat 12.2.0
exit /b 0

:usage
echo Usage: install-maven-artifacts.bat ^<ArcGISVersion^>
echo.
echo ArcGISVersion must be one of: %SUPPORTED_VERSIONS%
echo Example: install-maven-artifacts.bat 12.2.0
exit /b 1

:exit
exit /b 1

:end
exit /b 0
