from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
from sentence_transformers import SentenceTransformer, CrossEncoder
from easynmt import EasyNMT
from transformers import pipeline
import fasttext
import uvicorn
import torch
import time
import os
from typing import List

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))

app = FastAPI(title="NLP Processing Pipeline for IR Paper")

# Use Metal (MPS) on Apple Silicon when available, otherwise fall back to CPU
device = "mps" if torch.backends.mps.is_available() else "cpu"

print("\n" + "="*50)
print(f"NEXA INDEXING SYSTEM")
print(f"DEVICE: {device.upper()}")
print("="*50 + "\n")

def load_models():
    models = {}

    # 1. Language Detector
    start = time.time()
    models['lang'] = fasttext.load_model(os.path.join(SCRIPT_DIR, "lid.176.ftz"))
    print(f"[1/5] FastText (Language Detector) loaded in: {time.time() - start:.2f}s")

    # 2. Translator (EasyNMT)
    start = time.time()
    models['translator'] = EasyNMT('opus-mt')
    print(f"[2/5] EasyNMT (Translator) loaded in: {time.time() - start:.2f}s")

    # 3. BGE-M3 (Multilingual)
    start = time.time()
    models['multi'] = SentenceTransformer("BAAI/bge-m3", device=device)
    print(f"[3/5] BGE-M3 (Multilingual Embedder) loaded in: {time.time() - start:.2f}s")

    # 4. Gemma 300M (English-specific embedder)
    start = time.time()
    GEMMA_PREFIX = "search_document: "
    try:
        models['en'] = SentenceTransformer("google/embeddinggemma-300m", device=device, trust_remote_code=True)
        print(f"[4/5] Gemma 300M (English Embedder) loaded in: {time.time() - start:.2f}s")
    except Exception as e:
        print(f"[4/5] Failed to load Gemma 300M: {e}")
        models['en'] = None

    # 5. TranslateGemma — skipped to preserve MPS memory for embeddings
    models['translategemma'] = None
    print("[5/5] TranslateGemma 4B skipped (disabled to free MPS memory)")

    # 6. Reranker (Cross-Encoder)
    start = time.time()
    try:
        models['reranker'] = CrossEncoder('cross-encoder/ms-marco-MiniLM-L-6-v2', device=device)
        print(f"[6/6] Cross-Encoder Reranker loaded in: {time.time() - start:.2f}s")
    except Exception as e:
        print(f"[6/6] Failed to load Reranker: {e}")
        models['reranker'] = None

    return models

loaded_models = load_models()
GEMMA_PREFIX = "search_document: "

print(f"\nAll models ready. Server listening on port 8080.\n")

# --- TRANSLATEGEMMA HELPER ---
def extract_translation(output):
    if not output:
        raise ValueError("Empty generation output.")

    generated_text = output[0].get("generated_text")
    if isinstance(generated_text, list) and generated_text:
        last_message = generated_text[-1]
        if isinstance(last_message, dict):
            content = last_message.get("content")
            if isinstance(content, str) and content.strip():
                return content.strip()
    if isinstance(generated_text, str) and generated_text.strip():
        return generated_text.strip()

    raise ValueError(f"Unexpected TranslateGemma output format: {output}")


# --- DATA MODELS ---
class TextRequest(BaseModel):
    texts: List[str]

class ProcessedDocument(BaseModel):
    original_text: str
    detected_language: str
    english_text: str
    embedding_multi: List[float]
    embedding_gemma: List[float]

class ProcessResponse(BaseModel):
    results: List[ProcessedDocument]

class TranslateGemmaRequest(BaseModel):
    source_lang_code: str
    target_lang_code: str
    text: str

class TranslateGemmaResponse(BaseModel):
    translation: str

class RerankRequest(BaseModel):
    query: str
    documents: List[str]

class RerankResponse(BaseModel):
    scores: List[float]

# --- ENDPOINTS ---
@app.post("/process", response_model=ProcessResponse)
async def process_pipeline(data: TextRequest):
    if not data.texts:
        raise HTTPException(status_code=400, detail="Empty list")

    results = []
    try:
        for text in data.texts:
            # 1. Language Detection
            prediction = loaded_models['lang'].predict(text.replace("\n", " "), k=1)
            detected_lang = prediction[0][0].replace("__label__", "")

            # 2. Translation (fall back to original if the lang-pair model is missing)
            try:
                english_text = text if detected_lang == 'en' else loaded_models['translator'].translate(text, target_lang='en')
            except Exception:
                english_text = text

            # 3. BGE-M3 embedding
            v_multi = loaded_models['multi'].encode(text).tolist()

            # 4. Gemma embedding
            v_gemma = [0.0] * 1024  # fallback when model is unavailable
            if loaded_models['en']:
                v_gemma = loaded_models['en'].encode(GEMMA_PREFIX + english_text).tolist()

            results.append(ProcessedDocument(
                original_text=text,
                detected_language=detected_lang,
                english_text=english_text,
                embedding_multi=v_multi,
                embedding_gemma=v_gemma
            ))

        return {"results": results}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@app.post("/translate", response_model=TranslateGemmaResponse)
async def translate_gemma_endpoint(data: TranslateGemmaRequest):
    if not loaded_models.get('translategemma'):
        raise HTTPException(status_code=503, detail="TranslateGemma model failed to load")

    messages = [
        {
            "role": "user",
            "content": [
                {
                    "type": "text",
                    "source_lang_code": data.source_lang_code,
                    "target_lang_code": data.target_lang_code,
                    "text": data.text,
                }
            ],
        }
    ]
    try:
        output = loaded_models['translategemma'](text=messages, max_new_tokens=256)
        translation = extract_translation(output)
        return {"translation": translation}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@app.post("/rerank", response_model=RerankResponse)
async def rerank_endpoint(data: RerankRequest):
    if not loaded_models.get('reranker'):
        raise HTTPException(status_code=503, detail="Reranker model failed to load")

    if not data.documents:
        return {"scores": []}

    pairs = [[data.query, doc] for doc in data.documents]
    try:
        scores = loaded_models['reranker'].predict(pairs).tolist()
        if isinstance(scores, float):
            scores = [scores]
        return {"scores": scores}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@app.get("/health")
def health():
    return {"status": "ok", "device": device}

if __name__ == "__main__":
    uvicorn.run("inference_server:app", host="0.0.0.0", port=8080, reload=False, workers=1)
