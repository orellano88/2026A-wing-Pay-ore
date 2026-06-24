#!/bin/bash
# JARVIS: Motor de Construcción Stark v4.0 (Inyección de Motor 8.5)

GRADLE_VERSION="8.5"
GRADLE_DIR="$HOME/gradle-$GRADLE_VERSION"

if [ ! -d "$GRADLE_DIR" ]; then
    echo "JARVIS: Motor Gradle no detectado en el servidor. Iniciando Protocolo de Inyección..."
    curl -sL https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip -o gradle.zip
    unzip -q gradle.zip -d $HOME
    rm gradle.zip
fi

export PATH="$GRADLE_DIR/bin:$PATH"
echo "JARVIS: Motor Gradle 8.5 En Línea. Iniciando Forjado del APK..."
gradle "$@"
