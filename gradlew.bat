@echo off
@rem Gradle wrapper script for MebahelZombieHorde.
@rem Run `gradle wrapper --gradle-version 9.1.0` once to generate gradle\wrapper\gradle-wrapper.jar.
setlocal
set APP_HOME=%~dp0
set CLASSPATH=%APP_HOME%gradle\wrapper\gradle-wrapper.jar
if not exist "%CLASSPATH%" (
    echo ERROR: Missing %CLASSPATH%
    echo Bootstrap once with: gradle wrapper --gradle-version 9.1.0
    exit /b 1
)
if defined JAVA_HOME (
    set JAVA_EXE=%JAVA_HOME%\bin\java.exe
) else (
    set JAVA_EXE=java.exe
)
"%JAVA_EXE%" -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*
