@echo off
TITLE Diretoria PD
echo A iniciar a Diretoria...
echo.

if "%JAVA_HOME%" == "" (
    echo JAVA_HOME nao definido.
    pause
    exit
)

"%JAVA_HOME%\bin\java.exe" -cp target\classes;lib\* directory.DirectoryService
pause