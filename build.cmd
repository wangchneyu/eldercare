@echo off
echo === Installing common modules to local Maven repository ===
call mvnw.cmd install -pl common -am -Dmaven.test.skip=true -q
if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] Common modules installation failed
    exit /b 1
)
echo === Compiling all services ===
call mvnw.cmd compile -Dmaven.test.skip=true -q
if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] Compilation failed
    exit /b 1
)
echo === Build complete ===
