#!/bin/bash
echo "=== Installing common modules to local Maven repository ==="
./mvnw install -pl common -am -Dmaven.test.skip=true -q
if [ $? -ne 0 ]; then
    echo "[ERROR] Common modules installation failed"
    exit 1
fi
echo "=== Compiling all services ==="
./mvnw compile -Dmaven.test.skip=true -q
if [ $? -ne 0 ]; then
    echo "[ERROR] Compilation failed"
    exit 1
fi
echo "=== Build complete ==="
