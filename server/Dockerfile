FROM python:3.12-slim

# Create a non-root user and group
RUN groupadd -r dndscribe && useradd -r -g dndscribe -d /app -s /sbin/nologin dndscribe

WORKDIR /app

# Create data directory and set permissions
RUN mkdir -p /data && chown -R dndscribe:dndscribe /data /app

# Copy dependency definition
COPY pyproject.toml README.md ./

# Install packages
RUN pip install --no-cache-dir .

# Copy sources
COPY src ./src

# Set proper ownership for all app files
RUN chown -R dndscribe:dndscribe /app

# Switch to non-root user
USER dndscribe

# Configure default DB path to persistent data volume
ENV DND_SCRIBE_DB_PATH=/data/sessions.sqlite

VOLUME ["/data"]

EXPOSE 8000

HEALTHCHECK --interval=30s --timeout=5s --start-period=5s --retries=3 \
  CMD python -c "import urllib.request; urllib.request.urlopen('http://localhost:8000/health')"

CMD ["uvicorn", "dnd_scribe_server.main:app", "--app-dir", "src", "--host", "0.0.0.0", "--port", "8000"]
