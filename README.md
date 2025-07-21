# Jmix AI Backend

## Development

Running PgVector:
```shell
docker run --name pgvector -p 5433:5432 -e POSTGRES_USER=postgres -e POSTGRES_PASSWORD=postgres pgvector/pgvector:pg17
```

Running reranker:
```shell
python3.10 -m venv env
source env/bin/activate
pip install fastapi==0.115.0 uvicorn==0.30.6 torch==2.4.1 transformers==4.44.2 pydantic==2.9.2

uvicorn reranker_service:app --host 0.0.0.0 --port 8000
```

## Building images

Build app image:
```shell
./gradlew bootBuildImage -Pvaadin.productionMode=true
```

Build reranker image:
```shell
cd reranker
python3.10 -m venv env
source env/bin/activate
pip install fastapi==0.115.0 uvicorn==0.30.6 torch==2.4.1 transformers==4.44.2 pydantic==2.9.2
python3.10 download_model.py
docker build -t jmix-ai-reranker .
```

## Running in production

See <https://github.com/jmix-framework/jmix-ai-deploy>
