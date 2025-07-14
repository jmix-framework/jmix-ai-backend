from transformers import AutoTokenizer, AutoModelForSequenceClassification
import os

model_name = "cross-encoder/ms-marco-MiniLM-L-12-v2"
cache_dir = "./model_cache"

# Create cache directory
os.makedirs(cache_dir, exist_ok=True)

# Download tokenizer and model
print(f"Downloading tokenizer for {model_name}...")
tokenizer = AutoTokenizer.from_pretrained(model_name, use_fast=True, cache_dir=cache_dir)
print(f"Downloading model for {model_name}...")
model = AutoModelForSequenceClassification.from_pretrained(model_name, cache_dir=cache_dir)
print("Model and tokenizer downloaded successfully.")