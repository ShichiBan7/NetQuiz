@echo off
:: Pemakaian: scripts\run-web.bat [port-http]  (contoh: scripts\run-web.bat 8888)
cd /d "%~dp0.."
if "%1"=="" (set P=8888) else (set P=%1)
java -cp "out;lib/gson-2.15.0.jar" client.NetQuizWebBridge %P%
