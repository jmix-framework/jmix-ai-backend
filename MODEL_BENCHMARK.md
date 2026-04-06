# Model Benchmark: Jmix 1.7 RAG Quality (Self-Hosted)

## Test Setup
- **Server**: 2x NVIDIA RTX A4000 (32GB total VRAM)
- **Runtime**: Ollama
- **Reranker**: bge-reranker-v2-m3 (Docker, CPU)
- **Embedding**: bge-m3 (Ollama)
- **Evaluator**: GPT-5.2 (OpenAI API)
- **Dataset**: jmix-1.7-reference-v1, 10 golden questions

## RAG Benchmark Results (with retrieval + reranker)

| Model | Quant | Size | Score | Stability (3 runs) | Notes |
|-------|-------|------|-------|---------------------|-------|
| **GPT-5.4** (cloud) | - | cloud | **0.907** | N/A | Baseline cloud |
| **qwen3-coder:30b (Ollama)** | Q4_K_M | 17.3GB | **0.832** | 0.808, 0.850, 0.839 | **Best self-hosted** |
| Q5_K_XL (Ollama) | Q5_K_XL | 20.2GB | **0.799** | 0.781, 0.780, 0.835 | |
| Devstral 24B (Ollama) | Q4_K_M | 13.3GB | **0.777** | single run | Mistral SWE model |
| qwen3:30b (Ollama) | Q4_K_M | 17.3GB | **0.759** | 0.744, 0.741, 0.792 | |
| Q6_K_XL (Ollama) | Q6_K_XL | 24.5GB | **0.754** | single run | Higher quant = worse on MoE |
| mistral-small (Ollama) | Q4_K_M | 13.3GB | **0.689** | 0.737, 0.651, 0.678 | |
| Devstral-Small (llama.cpp) | Q5_K_M | ~16GB | 0.676 | single run | |
| DeepSeek-R1-Distill-Qwen-14B | Q4_K_M | ~10GB | 0.627 | single run | |

## Key Findings

### MoE Quantization Paradox on Ollama
- Q4_K_M (17.3GB) = 0.832 > Q5_K_XL (20.2GB) = 0.799 > Q6_K_XL (24.5GB) = 0.754
- Higher quant = worse for MoE on Ollama with 32GB GPU
- Cause: larger model leaves less VRAM for KV cache → partial CPU offload
- Sweet spot: Q4_K_M leaves ~14GB headroom for embedding + KV cache

### Retrieval Parameters
- **topK: 10, topReranked: 3** — optimal balance
- **minRerankedScore: 0.01** — required for cross-encoder scores
- topK 15 adds noise, topK 8 loses context

### LLM-based Document Splitting (tested, rejected)
- Splitting docs via gpt-4.1-mini into 600-1500 char sub-docs
- Result: golden 0.832→0.795, full 0.623→0.590 — WORSE
- Small chunks lose context, reranker picks noisier results

## Production Configuration

**Model**: `qwen3-coder:30b` (Q4_K_M, 17.3GB)
**Reranker**: bge-reranker-v2-m3 in Docker on CPU (OpenAI-compatible wrapper)
**Embedding**: bge-m3 (Ollama)
**Score**: 0.832 golden avg, 0.623 full
