@echo off
cd /d "%~dp0.."
java -cp "out;lib/gson-2.15.0.jar" server.NetQuizServer
