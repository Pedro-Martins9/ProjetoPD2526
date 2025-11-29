@echo off
TITLE Cliente PD
echo A iniciar a Aplicacao Cliente...
echo.
"C:\Users\fabio\.jdks\openjdk-25.0.1\bin\java.exe" -cp target\classes;lib\* client.ClientUI
pause