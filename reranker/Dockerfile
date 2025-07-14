FROM python:3.10-slim

WORKDIR /app

# Install system dependencies
RUN apt-get update && apt-get install -y \
    gcc \
    && rm -rf /var/lib/apt/lists/*

# Copy requirements and install dependencies
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Copy the reranker service
COPY reranker_service.py .

# Copy the pre-downloaded model
COPY model_cache /app/model_cache

# Set environment variable to use cached model
ENV MODEL_CACHE_DIR=/app/model_cache
ENV PYTHONUNBUFFERED=1

# Expose port
EXPOSE 8000

# Run the service
CMD ["uvicorn", "reranker_service:app", "--host", "0.0.0.0", "--port", "8000"]