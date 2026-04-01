@echo off
@REM Maven Wrapper script for Windows
@REM Requires Maven installed or downloads it

setlocal

set MAVEN_PROJECTBASEDIR=%~dp0
set MAVEN_WRAPPER_PROPERTIES=%MAVEN_PROJECTBASEDIR%.mvn\wrapper\maven-wrapper.properties

if exist "%MAVEN_HOME%\bin\mvn.cmd" (
    "%MAVEN_HOME%\bin\mvn.cmd" %*
) else (
    echo Please install Maven 3.9.6 or use: mvn %*
    mvn %*
)
