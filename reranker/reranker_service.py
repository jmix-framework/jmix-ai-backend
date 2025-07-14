import os
import logging
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
import torch
import math
from transformers import AutoTokenizer, AutoModelForSequenceClassification
from typing import List

# Set up logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

try:
    # Initialize FastAPI app
    app = FastAPI(title="Cross-Encoder Reranker Service")

    # Load model and tokenizer
    model_name = "cross-encoder/ms-marco-MiniLM-L-12-v2"
    
    cache_dir = os.getenv("MODEL_CACHE_DIR", "./model_cache")
    # Create cache directory if it doesn't exist
    os.makedirs(cache_dir, exist_ok=True)

    logger.info(f"Loading tokenizer and model: {model_name}")
    tokenizer = AutoTokenizer.from_pretrained(
        model_name, 
        use_fast=True,
        cache_dir=cache_dir
    )
    model = AutoModelForSequenceClassification.from_pretrained(
        model_name,
        cache_dir=cache_dir
    )
    model.eval()  # Ensure evaluation mode
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    model.to(device)
    logger.info(f"Model loaded on device: {device}")
    logger.info("Model and tokenizer loaded successfully")
except Exception as e:
    logger.error(f"Failed to load model or tokenizer: {str(e)}")
    raise

# Define request model
class RerankRequest(BaseModel):
    query: str
    documents: List[str]
    top_n: int

# Define response model
class RerankResponse(BaseModel):
    index: int
    score: float

@app.post("/rerank", response_model=List[RerankResponse])
async def rerank(request: RerankRequest):
    try:
        logger.info(f"Received rerank request: query='{request.query}', num_documents={len(request.documents)}, top_n={request.top_n}")
        
        # Validate input
        if not request.query.strip():
            raise HTTPException(status_code=400, detail="Query cannot be empty")
        if not request.documents:
            raise HTTPException(status_code=400, detail="Documents list cannot be empty")
        if request.top_n <= 0:
            raise HTTPException(status_code=400, detail="top_n must be positive")
        
        # Filter out empty or invalid documents and track original indices
        valid_documents = []
        original_indices = []
        for i, doc in enumerate(request.documents):
            if doc and doc.strip():
                valid_documents.append(doc.strip().encode('utf-8').decode('utf-8'))
                original_indices.append(i)
        if not valid_documents:
            raise HTTPException(status_code=400, detail="No valid documents provided")
        
        # logger.info(f"Valid documents: {valid_documents}")
        
        # Prepare query-document pairs
        pairs = [[request.query.encode('utf-8').decode('utf-8'), doc] for doc in valid_documents]
        
        # Tokenize inputs
        inputs = tokenizer(
            pairs,
            padding=True,
            truncation=True,
            return_tensors="pt",
            max_length=512
        )
        
        # Move inputs to the same device as model
        inputs = {k: v.to(device) for k, v in inputs.items()}
        
        # Log tokenized inputs
        # logger.info(f"Input IDs shape: {inputs['input_ids'].shape}")
        # logger.info(f"Input IDs: {inputs['input_ids'].tolist()}")
        # logger.info(f"Attention mask: {inputs['attention_mask'].tolist()}")
        
        # Check for invalid tokenization
        if not inputs['input_ids'].sum():
            logger.warning("Invalid tokenization: all input IDs are zero")
            results = [{"index": i, "score": 0.0} for i in original_indices]
            return results[:min(request.top_n, len(results))]
        
        # Run inference
        with torch.no_grad():
            outputs = model(**inputs)
            logits = outputs.logits
            # logger.info(f"Raw logits: {logits.tolist()}")
            # Check if all logits are NaN
            if torch.isnan(logits).all():
                logger.warning("All logits are NaN, returning zero scores")
                scores = [0.0] * len(valid_documents)
            else:
                # Clamp logits for numerical stability
                logits = torch.clamp(logits, min=-100, max=100)
                scores = torch.sigmoid(logits).squeeze(-1).tolist()
                scores = [0.0 if math.isnan(score) or math.isinf(score) else float(score) for score in scores]
            logger.info(f"Processed scores: {scores}")
        
        # Create response with original indices
        results = [{"index": original_indices[i], "score": score} for i, score in enumerate(scores)]
        # Sort by score and limit to top_n
        results = sorted(results, key=lambda x: x["score"], reverse=True)[:min(request.top_n, len(results))]
        
        logger.info(f"Reranking completed: returning {len(results)} results: {results}")
        return results
    except Exception as e:
        logger.error(f"Error during reranking: {str(e)}")
        raise HTTPException(status_code=500, detail=str(e))

@app.get("/health")
async def health():
    return {"status": "healthy"}