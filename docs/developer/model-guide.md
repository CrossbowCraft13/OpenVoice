# Model Guide

## Overview

OpenVoice uses multiple AI models for different tasks. All models are optional and user-managed — nothing is bundled in the APK.

## Model Types

### Speech Recognition (STT)

| Model | File | Size | RAM | Quality |
|-------|------|------|-----|---------|
| Whisper Tiny | `ggml-tiny.en.bin` | 75MB | 1GB | Fast, adequate |
| Whisper Base | `ggml-base.en.bin` | 150MB | 1.5GB | Recommended |
| Whisper Small | `ggml-small.en.bin` | 500MB | 2.5GB | High quality |

**Format**: GGML/GGUF  
**Engine**: Whisper.cpp via JNI  
**Source**: https://huggingface.co/ggerganov/whisper.cpp  

### Text-to-Speech (TTS)

| Model | File | Size | Quality |
|-------|------|------|---------|
| Piper en_US-lessac-medium | `en_US-lessac-medium.onnx` | 15MB | Good |
| Piper en_US-amy-low | `en_US-amy-low.onnx` | 10MB | Fast, lower quality |
| Piper en_US-normans-medium | `en_US-normans-medium.onnx` | 15MB | Good |

**Format**: ONNX  
**Engine**: Piper via ONNX Runtime  
**Source**: https://huggingface.co/rhasspy/piper  

### Voice Activity Detection

| Model | File | Size |
|-------|------|------|
| Silero VAD | `silero_vad.onnx` | 5MB |

**Format**: ONNX  
**Source**: https://github.com/snakers4/silero-vad  

### Wake Word Detection

| Model | File | Size |
|-------|------|------|
| OpenWakeWord | `openwakeword_openvoice.tflite` | 200KB |

**Format**: TFLite  
**Source**: https://github.com/dscripka/openWakeWord  

### Local LLM (Optional)

| Model | File | Size | RAM | Quality |
|-------|------|------|-----|---------|
| Qwen2 0.5B | `Qwen2-0.5B-Instruct-Q4_K_M.gguf` | 380MB | 2GB | Basic |
| Gemma 2B | `Gemma-2B-Instruct-Q4_K_M.gguf` | 1.4GB | 4GB | Good |
| Phi-3 Mini | `Phi-3-mini-4k-instruct-Q4_K_M.gguf` | 2.6GB | 6GB | Very good |
| Mistral 7B | `Mistral-7B-Instruct-v0.3-Q4_K_M.gguf` | 4.4GB | 8GB | Excellent |

**Format**: GGUF  
**Engine**: Llama.cpp via JNI  
**Source**: Various HuggingFace repos  

### Vision Model (Optional)

| Model | File | Size | RAM |
|-------|------|------|-----|
| Florence-2-base | ONNX export | 500MB | 3GB |
| SmolVLM-256M | ONNX export | 400MB | 2GB |

**Format**: ONNX  
**Engine**: ONNX Runtime  
**Source**: Various HuggingFace repos  

## Auto-Recommendation

OpenVoice's DeviceProfiler automatically recommends an appropriate model:

| RAM | Recommended LLM |
|-----|----------------|
| <4GB | Not recommended |
| 4-6GB | Qwen2 0.5B |
| 6-8GB | Gemma 2B |
| 8-12GB | Phi-3 Mini |
| 12-16GB | Mistral 7B |
| 16GB+ | Llama 3.2 8B |

## Model Directory

Models are stored at:
```
{app_files}/models/
```

You can view, activate, and delete models through the Model Manager in Settings.

## Downloading Models

Models can be downloaded through the in-app Model Manager, or manually from HuggingFace. The Model Manager supports:
- Background downloads
- Resume interrupted downloads
- SHA-256 verification
- Storage tracking
