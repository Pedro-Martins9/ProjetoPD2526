@echo off
TITLE Cliente PD
echo A iniciar a Cliente...
echo.

if "%JAVA_HOME%" == "" (
    echo JAVA_HOME não definido.
    pause
    exit
)

"%JAVA_HOME%\bin\java.exe" -cp target\classes;lib\* client.ClientUI
pause