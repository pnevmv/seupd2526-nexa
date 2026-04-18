FROM python:3.10-slim

WORKDIR /app

COPY bert.py /app/bert.py

RUN pip install --no-cache-dir \
    fastapi \
    "uvicorn[standard]" \
    sentence-transformers \
    torch

EXPOSE 8080

CMD ["python", "bert.py"]