
Init and run:

```shell
python3.10 -m venv env
source env/bin/activate
pip install fastapi==0.115.0 uvicorn==0.30.6 rouge-score==0.1.2 bert-score==0.3.13

uvicorn answer_checks_service:app --host 0.0.0.0 --port 8001
```

Test service:

```shell
curl -X POST -H "Content-Type: application/json" -d '{"reference": "The quick brown fox jumps over the lazy dog.", "actual": "A quick brown fox leaps over a lazy dog."}' http://localhost:8001/rouge
```

Build docker image:

```shell
docker build -t jmix-ai-answer-checks .
```

Run docker container:

```shell
docker run -p 8001:8001 --name jmix-ai-answer-checks-1 jmix-ai-answer-checks
```
