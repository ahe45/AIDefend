@echo off
setlocal
set "GLASSES_JAVA=java.exe"
if defined JAVA_HOME set "GLASSES_JAVA=%JAVA_HOME%\bin\java.exe"
"%GLASSES_JAVA%" -jar "%~dp0gradle\wrapper\gradle-wrapper.jar" %*
exit /b %ERRORLEVEL%

