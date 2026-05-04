from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
from sentence_transformers import CrossEncoder
import uvicorn
import torch
import time
from typing import List

app = FastAPI(title="Reranking Server")

device = "mps" if torch.backends.mps.is_available() else "cpu"

print(f"\n{'='*50}\nRERANKING SERVER  (port 8082)\nDEVICE: {device.upper()}\n{'='*50}\n")


def load_model():
    start = time.time()
    try:
        model = CrossEncoder('cross-encoder/ms-marco-MiniLM-L-6-v2', device=device)
        print(f"[1/1] Cross-Encoder loaded in {time.time() - start:.2f}s")
        return model
    except Exception as e:
        print(f"[1/1] Cross-Encoder failed to load: {e}")
        return None


reranker = load_model()
print("\nReranking server ready. Listening on port 8082.\n")


class RerankRequest(BaseModel):
    query: str
    documents: List[str]


class RerankResponse(BaseModel):
    scores: List[float]


@app.post("/rerank", response_model=RerankResponse)
async def rerank(data: RerankRequest):
    if not reranker:
        raise HTTPException(status_code=503, detail="Reranker not loaded")
    if not data.documents:
        return {"scores": []}
    pairs = [[data.query, doc] for doc in data.documents]
    try:
        scores = reranker.predict(pairs).tolist()
        if isinstance(scores, float):
            scores = [scores]
        return {"scores": scores}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@app.get("/health")
def health():
    return {"status": "ok", "device": device, "model_loaded": reranker is not None}


if __name__ == "__main__":
    uvicorn.run("reranking_server:app", host="0.0.0.0", port=8082, reload=False, workers=1)
