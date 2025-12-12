#!/bin/bash

# Script para instalar Whisper.cpp e baixar modelo base.en
# Execute: chmod +x setup-whisper.sh && ./setup-whisper.sh

set -e

WHISPER_DIR="/tmp/whisper.cpp"
MODELS_DIR="/tmp/whisper-models"

echo "=== Instalando Whisper.cpp ==="

# Clonar repositório
if [ ! -d "$WHISPER_DIR" ]; then
    echo "Clonando whisper.cpp..."
    git clone https://github.com/ggerganov/whisper.cpp.git $WHISPER_DIR
else
    echo "Whisper.cpp já existe, atualizando..."
    cd $WHISPER_DIR && git pull
fi

# Compilar
cd $WHISPER_DIR
echo "Compilando whisper.cpp..."
make clean
make -j$(nproc 2>/dev/null || sysctl -n hw.ncpu)

# Criar diretório de modelos
mkdir -p $MODELS_DIR

# Baixar modelo base.en (melhor balanço velocidade/qualidade)
echo "Baixando modelo base.en..."
cd $WHISPER_DIR/models
bash download-ggml-model.sh base.en

# Copiar modelo para diretório padrão
cp ggml-base.en.bin $MODELS_DIR/

echo ""
echo "=== Instalação concluída ==="
echo ""
echo "Whisper binário: $WHISPER_DIR/main"
echo "Modelo: $MODELS_DIR/ggml-base.en.bin"
echo ""
echo "Configure no application.properties:"
echo "whisper.path=$WHISPER_DIR/main"
echo "whisper.models.path=$MODELS_DIR"
echo ""
echo "Teste com:"
echo "$WHISPER_DIR/main -m $MODELS_DIR/ggml-base.en.bin -f seu_audio.wav"
