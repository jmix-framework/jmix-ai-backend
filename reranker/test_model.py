from transformers import AutoTokenizer, AutoModelForSequenceClassification
import torch

model_name = "cross-encoder/ms-marco-MiniLM-L-12-v2"
tokenizer = AutoTokenizer.from_pretrained(model_name, use_fast=True)
model = AutoModelForSequenceClassification.from_pretrained(model_name)
model.eval()

pairs = [
    ["What is the capital of France?", "France is a country in Europe."],
    ["What is the capital of France?", "The capital of France is Paris."],
    ["What is the capital of France?", "Florida is a state in the USA."]
]
inputs = tokenizer(pairs, padding=True, truncation=True, return_tensors="pt", max_length=512)
with torch.no_grad():
    outputs = model(**inputs)
    logits = outputs.logits
    scores = torch.sigmoid(logits).squeeze(-1).tolist()
print("Input IDs:", inputs['input_ids'].tolist())
print("Logits:", logits.tolist())
print("Scores:", scores)