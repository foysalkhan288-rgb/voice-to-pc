@echo off
setlocal
set "APP_HOME=%~dp0"
set "CLASSPATH=%APP_HOME%gradle\wrapper\gradle-wrapper.jar"
if not exist "%CLASSPATH%" (
  echo Gradle wrapper JAR is missing. Open this project in Android Studio or restore gradle\wrapper\gradle-wrapper.jar.
  exit /b 1
)
java -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*
