@echo off
cd /d "%~dp0.."
if exist out rmdir /s /q out
mkdir out
javac -cp lib\gson-2.15.0.jar -d out server\*.java client\*.java && echo Build OK
