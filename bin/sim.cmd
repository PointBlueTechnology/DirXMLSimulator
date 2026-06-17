@echo off
rem DirXML Policy Simulator CLI launcher (Windows).
rem Requires JDK 21 (the 4.10.1 engine jars are Java 21 bytecode).
rem JDK resolution: %SIM_JAVA_HOME% -> %JAVA_HOME% -> java on PATH.
setlocal enabledelayedexpansion
set "HERE=%~dp0.."

rem --- locate java ---
set "JAVA=java"
if defined SIM_JAVA_HOME set "JAVA=%SIM_JAVA_HOME%\bin\java.exe"
if not defined SIM_JAVA_HOME if defined JAVA_HOME set "JAVA=%JAVA_HOME%\bin\java.exe"

rem --- resolve application code: dev classes, packaged jar, or build it ---
set "APP="
if exist "%HERE%\target\classes" set "APP=%HERE%\target\classes"
if not defined APP for %%f in ("%HERE%\dirxml-simulator-*.jar") do set "APP=%%f"
if not defined APP for %%f in ("%HERE%\target\dirxml-simulator-*.jar") do set "APP=%%f"
if not defined APP (
  echo building ^(no target\classes or jar found^)...
  pushd "%HERE%" && call mvn -q compile && popd
  set "APP=%HERE%\target\classes"
)

"%JAVA%" -cp "%APP%;%HERE%\lib\*" com.pointblue.dirxml.sim.Cli %*
endlocal
