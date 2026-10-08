#!/usr/bin/env bash
# Converte il modello unito (HF safetensors) in GGUF per l'app Android (llama.cpp).
#
# Uso: bash training/export_gguf.sh [cartella_modello_unito] [file_output.gguf] [quant]
#   quant: q8_0 (default, nessuna compilazione) | q4_k_m (più piccolo, compila llama-quantize) | f16
set -euo pipefail

MERGED="${1:-out/vextor-merged}"
OUT="${2:-out/vextor-q8_0.gguf}"
QUANT="${3:-q8_0}"
LLAMA_TAG="b11400"   # stessa versione usata dall'app Android
LLAMA_DIR="${LLAMA_DIR:-out/llama.cpp}"

if [ ! -d "$LLAMA_DIR" ]; then
  git clone --depth 1 --branch "$LLAMA_TAG" https://github.com/ggml-org/llama.cpp "$LLAMA_DIR"
fi
# solo il pacchetto gguf: torch/transformers sono già installati per il training
pip install -q "$LLAMA_DIR/gguf-py" sentencepiece protobuf

mkdir -p "$(dirname "$OUT")"
case "$QUANT" in
  q8_0|f16|bf16)
    python "$LLAMA_DIR/convert_hf_to_gguf.py" "$MERGED" --outfile "$OUT" --outtype "$QUANT"
    ;;
  *)
    F16="${OUT%.gguf}-f16.gguf"
    python "$LLAMA_DIR/convert_hf_to_gguf.py" "$MERGED" --outfile "$F16" --outtype f16
    if [ ! -x "$LLAMA_DIR/build/bin/llama-quantize" ]; then
      cmake -S "$LLAMA_DIR" -B "$LLAMA_DIR/build" -DCMAKE_BUILD_TYPE=Release -DLLAMA_CURL=OFF -DLLAMA_OPENSSL=OFF
      cmake --build "$LLAMA_DIR/build" --target llama-quantize -j "$(nproc)"
    fi
    "$LLAMA_DIR/build/bin/llama-quantize" "$F16" "$OUT" "${QUANT^^}"
    rm -f "$F16"
    ;;
esac
ls -lh "$OUT"
echo "Copia $OUT sul telefono e importalo dall'app (Modello -> Importa file .gguf),"
echo "oppure caricalo su Hugging Face / GitHub Release e incolla il link nell'app."
