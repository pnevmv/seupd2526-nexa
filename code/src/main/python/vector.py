# Installazione necessaria:
# pip install fastapi uvicorn sentence-transformers torch

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
from sentence_transformers import SentenceTransformer
import uvicorn
from typing import List

app = FastAPI(title="BERT Multilingual Embedding Server")

# Caricamento del modello (avviene una sola volta all'avvio)
# Questo modello ha 384 dimensioni ed è ottimo per il multilingua
print("Caricamento del modello multilingua...")
model = SentenceTransformer('paraphrase-multilingual-MiniLM-L12-v2')
print("Modello pronto!")

class TextRequest(BaseModel):
    texts: List[str]

class VectorResponse(BaseModel):
    embeddings: List[List[float]]

@app.post("/embed", response_model=VectorResponse)
async def get_embeddings(data: TextRequest):
    if not data.texts:
        raise HTTPException(status_code=400, detail="La lista dei testi è vuota")

    try:
        # Generazione dei vettori
        # convert_to_tensor=False ci restituisce direttamente liste/numpy array
        embeddings = model.encode(data.texts).tolist()
        return {"embeddings": embeddings}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

if __name__ == "__main__":
    # Avvia il server sulla porta 8000
    uvicorn.run(app, host="0.0.0.0", port=8000)