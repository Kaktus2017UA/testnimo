#!/usr/bin/env bash
set -euo pipefail
python3 convert_model.py   nvidia/Nemotron-3-Diarization   --outfile nemotron-3-diarization.gguf
echo "Created nemotron-3-diarization.gguf"
