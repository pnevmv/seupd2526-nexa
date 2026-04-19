from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
from sentence_transformers import SentenceTransformer
from easynmt import EasyNMT
import fasttext
import uvicorn
from typing import List

from sympy.printing.pytorch import torch

app = FastAPI(title="NLP Processing Pipeline for IR Paper")

device = "mps" if torch.backends.mps.is_available() else "cpu"
print(f"🚀 Inizializzazione modelli su: {device.upper()}")

print("Loading models... This might take a minute.")
lang_model = fasttext.load_model("lid.176.ftz")
print("FastText Language Detector ready!")

translator = EasyNMT('opus-mt')
print("EasyNMT Translator ready!")

model = SentenceTransformer("BAAI/bge-m3")
print("BGE-M3 Embedder ready!")

model_en = SentenceTransformer("google/embeddinggemma-300m", device=device, trust_remote_code=True)
print("✅ Gemma 300M ready!")


class TextRequest(BaseModel):
    texts: List[str]

class ProcessedDocument(BaseModel):
    original_text: str
    detected_language: str
    english_text: str
    multilingual_embedding: List[float]

class ProcessResponse(BaseModel):
    results: List[ProcessedDocument]


@app.post("/process", response_model=ProcessResponse)
async def process_pipeline(data: TextRequest):
    if not data.texts:
        raise HTTPException(status_code=400, detail="Empty list of texts")

    results = []

    try:

        for text in data.texts:

            prediction = lang_model.predict(text, k=1)
            detected_lang = prediction[0][0].replace("__label__", "")

            if detected_lang != 'en':

                english_text = translator.translate(text, target_lang='en')
            else:
                english_text = text

            embedding = model.encode(text).tolist()

            results.append(ProcessedDocument(
                original_text=text,
                detected_language=detected_lang,
                english_text=english_text,
                multilingual_embedding=embedding
            ))

        return {"results": results}

    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@app.get("/health")
def health():
    return {"status": "ok"}

if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=8080)