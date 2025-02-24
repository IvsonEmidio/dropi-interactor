@echo off
setlocal enabledelayedexpansion

REM Set Java path
set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.5.11-hotspot"
set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"

REM Set Maven path - ajuste este caminho para o seu ambiente
set "MAVEN_HOME=C:\Program Files\apache-maven-3.9.6"
set "PATH=%MAVEN_HOME%\bin;%PATH%"

REM Set application directory
set "APP_DIR=%~dp0"
cd "%APP_DIR%"

REM Check if jar exists, if not build it
if not exist "target\dropi-interactor-1.0-SNAPSHOT.jar" (
    echo Building application...
    call "%MAVEN_HOME%\bin\mvn" clean package -DskipTests
    if !ERRORLEVEL! neq 0 (
        echo Failed to build application
        pause
        exit /b 1
    )
)

REM Create logs directory if it doesn't exist
if not exist "logs" mkdir logs

REM Run the application
echo Starting application...
"%JAVA_EXE%" -Xmx1G -jar target\dropi-interactor-1.0-SNAPSHOT.jar

REM If there's an error, pause to show the message
if !ERRORLEVEL! neq 0 (
    echo Application exited with error code !ERRORLEVEL!
    pause
)

endlocal
