from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
from transformers import pipeline
import uvicorn
import torch
import time
import os
import sys

app = FastAPI(title="Translation Server")

device = "mps" if torch.backends.mps.is_available() else "cpu"

print(f"\n{'='*50}\nTRANSLATION SERVER  (port 8081)\nDEVICE: {device.upper()}\n{'='*50}\n")

if not os.environ.get("HF_TOKEN"):
    print("ERROR: HF_TOKEN is not set. TranslateGemma is a gated model.", file=sys.stderr)
    print("Export it or add it to code/scripts/translategemma.env", file=sys.stderr)
    sys.exit(1)


def load_model():
    start = time.time()
    try:
        torch_dtype = torch.bfloat16 if device in ("mps", "cuda") else torch.float32
        model = pipeline(
            "image-text-to-text",
            model="google/translategemma-4b-it",
            device=device,
            dtype=torch_dtype,
        )
        print(f"[1/1] TranslateGemma 4B loaded in {time.time() - start:.2f}s")
        return model
    except Exception as e:
        print(f"[1/1] TranslateGemma 4B failed to load: {e}", file=sys.stderr)
        return None


translate_model = load_model()
print("\nTranslation server ready. Listening on port 8081.\n")


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


class TranslateRequest(BaseModel):
    source_lang_code: str
    target_lang_code: str
    text: str


class TranslateResponse(BaseModel):
    translation: str


@app.post("/translate", response_model=TranslateResponse)
async def translate(data: TranslateRequest):
    if not translate_model:
        raise HTTPException(status_code=503, detail="Translation model not loaded")
    messages = [{"role": "user", "content": [{"type": "text", "source_lang_code": data.source_lang_code, "target_lang_code": data.target_lang_code, "text": data.text}]}]
    try:
        output = translate_model(text=messages, max_new_tokens=256)
        return {"translation": extract_translation(output)}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@app.get("/health")
def health():
    return {"status": "ok", "device": device, "model_loaded": translate_model is not None}


if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=8081, reload=False)
