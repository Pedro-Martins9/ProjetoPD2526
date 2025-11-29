@echo off
TITLE Servidor PD
echo.
echo --- CONFIGURACAO DO SERVIDOR ---
echo.

:: Pergunta os argumentos ao utilizador (com valores padrao sugeridos)
set /p db="Nome do ficheiro da BD (ex: servidor.db): "
if "%db%"=="" set db=servidor.db

set /p tcp="Porta TCP Clientes (ex: 8080): "
if "%tcp%"=="" set tcp=8080

set /p sync="Porta TCP Sincronizacao (ex: 8081): "
if "%sync%"=="" set sync=8081

echo.
echo A iniciar Servidor com: BD=%db% - TCP=%tcp% - SYNC=%sync%
echo.

:: Executa o servidor com os argumentos recolhidos
:: (Altera o caminho dependendo de onde esta a pasta com o java.exe)
"C:\Users\fabio\.jdks\openjdk-25.0.1\bin\java.exe" -cp target\classes;lib\* server.Server %db% %tcp% %sync%
pause