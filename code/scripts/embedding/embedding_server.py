from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
from sentence_transformers import SentenceTransformer
from easynmt import EasyNMT
import fasttext
import uvicorn
import torch
import time
import os
from typing import List

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))

app = FastAPI(title="Embedding Server")

device = "mps" if torch.backends.mps.is_available() else "cpu"

print(f"\n{'='*50}\nEMBEDDING SERVER  (port 8080)\nDEVICE: {device.upper()}\n{'='*50}\n")

GEMMA_PREFIX = "search_document: "


def load_models():
    models = {}

    start = time.time()
    models['lang'] = fasttext.load_model(os.path.join(SCRIPT_DIR, "lid.176.ftz"))
    print(f"[1/4] FastText (language detector) loaded in {time.time() - start:.2f}s")

    start = time.time()
    models['translator'] = EasyNMT('opus-mt')
    print(f"[2/4] EasyNMT (translator) loaded in {time.time() - start:.2f}s")

    start = time.time()
    models['multi'] = SentenceTransformer("BAAI/bge-m3", device=device)
    print(f"[3/4] BGE-M3 (multilingual embedder) loaded in {time.time() - start:.2f}s")

    start = time.time()
    try:
        models['en'] = SentenceTransformer("google/embeddinggemma-300m", device=device, trust_remote_code=True)
        print(f"[4/4] Gemma 300M (English embedder) loaded in {time.time() - start:.2f}s")
    except Exception as e:
        print(f"[4/4] Gemma 300M failed to load: {e}")
        models['en'] = None

    return models


loaded_models = load_models()
print("\nEmbedding server ready. Listening on port 8080.\n")


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


@app.post("/process", response_model=ProcessResponse)
async def process_pipeline(data: TextRequest):
    if not data.texts:
        raise HTTPException(status_code=400, detail="Empty list")
    results = []
    try:
        for text in data.texts:
            prediction = loaded_models['lang'].predict(text.replace("\n", " "), k=1)
            detected_lang = prediction[0][0].replace("__label__", "")

            try:
                english_text = text if detected_lang == 'en' else loaded_models['translator'].translate(text, target_lang='en')
            except Exception:
                english_text = text

            v_multi = loaded_models['multi'].encode(text).tolist()

            v_gemma = [0.0] * 1024
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


@app.get("/health")
def health():
    return {"status": "ok", "device": device}


if __name__ == "__main__":
    uvicorn.run("embedding_server:app", host="0.0.0.0", port=8080, reload=False, workers=1)
