@echo off
rem Windows twin of synthetic.sh (plain JDK 17+, no Maven/Spring/database).
cd /d "%~dp0\.."
if not exist target\synthetic-classes mkdir target\synthetic-classes
javac --release 17 -d target\synthetic-classes src\main\java\com\skillorbit\skillorbit_ai\synthetic\*.java || exit /b 1
java -cp target\synthetic-classes com.skillorbit.skillorbit_ai.synthetic.SyntheticCli %*
