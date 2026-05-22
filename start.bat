@echo off
chcp 65001 >nul
title Sakura Agent
java -Dfile.encoding=UTF-8 -jar target\sakura-agent-0.0.1-SNAPSHOT.jar
pause
