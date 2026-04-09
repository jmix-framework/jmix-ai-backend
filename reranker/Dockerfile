FROM python:3.11-slim

RUN pip install --no-cache-dir \
    fastapi==0.115.0 \
    uvicorn==0.30.6 \
    torch==2.4.1 \
    transformers==4.44.2 \
    sentence-transformers==3.3.1 \
    pydantic==2.9.2

# Pre-download model at build time
RUN python -c "from sentence_transformers import CrossEncoder; CrossEncoder('BAAI/bge-reranker-v2-m3')"

WORKDIR /app
COPY reranker_service.py .

EXPOSE 8000
CMD ["uvicorn", "reranker_service:app", "--host", "0.0.0.0", "--port", "8000"]
