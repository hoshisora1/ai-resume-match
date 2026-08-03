FROM python:3.13-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1

WORKDIR /app
COPY pyproject.toml ./
COPY agent_service ./agent_service
RUN python -m pip install --no-cache-dir . \
    && addgroup --system agent \
    && adduser --system --ingroup agent --home /app agent \
    && chown -R agent:agent /app

USER agent
EXPOSE 8000

HEALTHCHECK --interval=15s --timeout=3s --start-period=10s --retries=5 \
  CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8000/health', timeout=2)" || exit 1

CMD ["uvicorn", "agent_service.main:app", "--host", "0.0.0.0", "--port", "8000", "--no-access-log"]
