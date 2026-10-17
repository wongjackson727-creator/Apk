FROM python:3.12-slim
WORKDIR /app
COPY monitor.py config.example.json stores-china-mainland.json ./
ENV CONFIG_PATH=/data/config.json STATE_PATH=/data/state.json PYTHONUNBUFFERED=1
VOLUME ["/data"]
CMD ["python", "/app/monitor.py"]
