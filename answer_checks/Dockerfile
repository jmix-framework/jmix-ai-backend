FROM python:3.10-slim

WORKDIR /app

# Install system dependencies
RUN apt-get update && apt-get install -y \
    gcc \
    && rm -rf /var/lib/apt/lists/*

# Copy requirements and install dependencies
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt -f https://download.pytorch.org/whl/cpu/torch_stable.html && \
 python -c "import nltk; nltk.download('punkt')"

# Copy the reranker service
COPY answer_checks_service.py .

ENV PYTHONUNBUFFERED=1

# Expose port
EXPOSE 8001

# Run the service
CMD ["uvicorn", "answer_checks_service:app", "--host", "0.0.0.0", "--port", "8001"]