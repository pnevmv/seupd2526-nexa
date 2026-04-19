from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
from sentence_transformers import SentenceTransformer
from easynmt import EasyNMT
import fasttext
import uvicorn
import torch
import time
from typing import List

app = FastAPI(title="NLP Processing Pipeline for IR Paper")

# Verifica MPS (Metal) per il tuo Mac M4
device = "mps" if torch.backends.mps.is_available() else "cpu"

print("\n" + "="*50)
print(f"🚀 SISTEMA DI INDICIZZAZIONE NEXA - MAC M4")
print(f"🚀 DEVICE RILEVATO: {device.upper()}")
print("="*50 + "\n")

def load_models():
    models = {}

    # 1. Language Detector
    start = time.time()
    models['lang'] = fasttext.load_model("lid.176.ftz")
    print(f"✅ [1/4] FastText (Language Detector) caricato in: {time.time() - start:.2f}s")

    # 2. Traduttore
    start = time.time()
    models['translator'] = EasyNMT('opus-mt')
    print(f"✅ [2/4] EasyNMT (Translator) caricato in: {time.time() - start:.2f}s")

    # 3. BGE-M3 (Multilingua)
    start = time.time()
    models['multi'] = SentenceTransformer("BAAI/bge-m3", device=device)
    print(f"✅ [3/4] BGE-M3 (Multilingual Embedder) caricato in: {time.time() - start:.2f}s")

    # 4. Gemma 300M (Inglese specifico)
    start = time.time()
    GEMMA_PREFIX = "search_document: "
    try:
        models['en'] = SentenceTransformer("google/embeddinggemma-300m", device=device, trust_remote_code=True)
        print(f"✅ [4/4] Gemma 300M (English Embedder) caricato in: {time.time() - start:.2f}s")
    except Exception as e:
        print(f"❌ Errore nel caricamento di Gemma: {e}")
        models['en'] = None

    return models

# Carichiamo tutto all'avvio
loaded_models = load_models()
GEMMA_PREFIX = "search_document: "

print(f"\n✨ TUTTI I MODELLI PRONTI! Il server è in ascolto sulla porta 8080.\n")

# --- MODELLI DATI ---
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

# --- PIPELINE ---
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

            # 2. Translation
            english_text = text if detected_lang == 'en' else loaded_models['translator'].translate(text, target_lang='en')

            # 3. Embedding BGE-M3
            v_multi = loaded_models['multi'].encode(text).tolist()

            # 4. Embedding Gemma
            v_gemma = [0.0] * 1024 # Fallback
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
    # Soluzione al bug loop_factory: usiamo uvicorn.run con parametri semplificati
    uvicorn.run("bert:app", host="0.0.0.0", port=8080, reload=False, workers=1)