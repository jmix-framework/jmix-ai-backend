"""
BGE Reranker v2-m3 with OpenAI-compatible Chat Completions API.

Pretends to be an OpenAI chat model. When Reranker.java sends a reranking prompt
with query + candidate documents, this service:
1. Parses the user message to extract query and documents
2. Runs cross-encoder scoring (much better than LLM-as-reranker)
3. Returns scores in the JSON format Reranker.java expects
"""
import json
import re
import time
import uuid

from fastapi import FastAPI
from pydantic import BaseModel
from sentence_transformers import CrossEncoder
import torch
import uvicorn

app = FastAPI()

device = "cuda" if torch.cuda.is_available() else "cpu"
model = CrossEncoder("BAAI/bge-reranker-v2-m3", max_length=8192, device=device)
print(f"Reranker loaded on {device}")


# ── OpenAI-compatible models ──

class ChatMessage(BaseModel):
    role: str
    content: str

class ChatCompletionRequest(BaseModel):
    model: str = "bge-reranker-v2-m3"
    messages: list[ChatMessage]
    temperature: float = 0.0
    response_format: dict | None = None

@app.post("/v1/chat/completions")
def chat_completions(req: ChatCompletionRequest):
    """OpenAI-compatible endpoint. Parses rerank prompt, runs cross-encoder."""
    user_msg = next((m.content for m in req.messages if m.role == "user"), "")

    query, documents = _parse_rerank_prompt(user_msg)

    if not query or not documents:
        # Fallback: return empty results
        result_json = json.dumps({"results": []})
    else:
        pairs = [[query, doc["content"]] for doc in documents]
        scores = model.predict(pairs).tolist()
        results = [{"index": doc["index"], "score": round(float(s), 3)} for doc, s in zip(documents, scores)]
        result_json = json.dumps({"results": results})

    return {
        "id": f"chatcmpl-{uuid.uuid4().hex[:12]}",
        "object": "chat.completion",
        "created": int(time.time()),
        "model": req.model,
        "choices": [{
            "index": 0,
            "message": {"role": "assistant", "content": result_json},
            "finish_reason": "stop"
        }],
        "usage": {"prompt_tokens": 0, "completion_tokens": 0, "total_tokens": 0}
    }

@app.get("/v1/models")
def list_models():
    """OpenAI-compatible models list."""
    return {
        "object": "list",
        "data": [{
            "id": "bge-reranker-v2-m3",
            "object": "model",
            "created": int(time.time()),
            "owned_by": "local"
        }]
    }


def _parse_rerank_prompt(user_msg: str) -> tuple:
    """Extract query and candidate documents from Reranker.java prompt format.

    Expected format:
        Query:
        <query text>

        Candidate documents:
        [{"index":0,"source":"...","content":"..."}, ...]
    """
    query = ""
    documents = []

    # Extract query
    query_match = re.search(r"Query:\s*\n(.+?)(?:\n\s*\n|\nCandidate)", user_msg, re.DOTALL)
    if query_match:
        query = query_match.group(1).strip()

    # Extract documents JSON array
    docs_match = re.search(r"Candidate documents:\s*\n(\[.+\])", user_msg, re.DOTALL)
    if docs_match:
        try:
            documents = json.loads(docs_match.group(1))
        except json.JSONDecodeError:
            pass

    return query, documents


# ── Direct rerank endpoint (like main branch) ──

class RerankRequest(BaseModel):
    query: str
    documents: list[str]
    top_n: int = 3

@app.post("/rerank")
def rerank(req: RerankRequest):
    """Direct cross-encoder reranking. Returns [{index, score}] sorted by score desc."""
    if not req.documents:
        return []

    pairs = [[req.query, doc] for doc in req.documents]
    scores = model.predict(pairs).tolist()

    results = [{"index": i, "score": round(float(s), 4)} for i, s in enumerate(scores)]
    results.sort(key=lambda x: x["score"], reverse=True)
    return results[:req.top_n]


# ── Health check ──

@app.get("/health")
def health():
    return {"status": "ok", "device": device}


if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=8000)
