@echo off
TITLE Servico de Diretoria
echo A iniciar a Diretoria na porta 5001...
echo.
:: O classpath (-cp) inclui as classes compiladas e todos os jars na pasta lib
"C:\Users\fabio\.jdks\openjdk-25.0.1\bin\java.exe" -cp target\classes;lib\* directory.DirectoryService
pause