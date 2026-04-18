from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
from sentence_transformers import SentenceTransformer
import uvicorn
from typing import List

app = FastAPI(title="BERT Multilingual Embedding Server")

print("Loading model BGE-M3...")
model = SentenceTransformer("BAAI/bge-m3")
print("Model BGE-M3 ready!")

class TextRequest(BaseModel):
    texts: List[str]

class VectorResponse(BaseModel):
    embeddings: List[List[float]]

@app.post("/embed", response_model=VectorResponse)
async def get_embeddings(data: TextRequest):
    if not data.texts:
        raise HTTPException(status_code=400, detail="empty list")

    try:
        embeddings = model.encode(data.texts).tolist()
        return {"embeddings": embeddings}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@app.get("/health")
def health():
    return {"status": "ok"}

if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=8080)