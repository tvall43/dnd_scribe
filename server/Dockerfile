FROM python:3.12-slim

WORKDIR /app

COPY pyproject.toml README.md ./
COPY src ./src

RUN pip install --no-cache-dir -e .

EXPOSE 8000

CMD ["uvicorn", "dnd_scribe_server.main:app", "--app-dir", "src", "--host", "0.0.0.0", "--port", "8000"]
