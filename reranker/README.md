
Init and run:

```shell
python3.10 -m venv env
source env/bin/activate
pip install fastapi==0.115.0 uvicorn==0.30.6 torch==2.4.1 transformers==4.44.2 pydantic==2.9.2

uvicorn reranker_service:app --host 0.0.0.0 --port 8000
```

Standalone test:

```shell
python3.10 test_model.py
```

Test service:

```shell
curl -X POST http://localhost:8000/rerank \
-H "Content-Type: application/json" \
-d '{
    "query": "What is the capital of France?",
    "documents": [
        "France is a country in Europe.",
        "The capital of France is Paris.",
        "Florida is a state in the USA."
    ],
    "top_n": 2
}'
```

Build docker image:

```shell
docker build -t jmix-ai-reranker .
```

Run docker container:

```shell
docker run -p 8000:8000 --name jmix-ai-reranker-1 jmix-ai-reranker
```
