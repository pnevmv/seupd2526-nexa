FROM python:3.10-slim

WORKDIR /app

COPY inference_server.py /app/inference_server.py

RUN pip install --no-cache-dir \
    fastapi \
    "uvicorn[standard]" \
    sentence-transformers \
    torch

EXPOSE 8080

CMD ["python", "inference_server.py"]