FROM python:3.12-slim
WORKDIR /app
COPY pyproject.toml README.md ./
COPY every_routes_server ./every_routes_server
RUN pip install --no-cache-dir .
VOLUME ["/data"]
EXPOSE 8787
CMD ["every-routes-server", "--data-dir", "/data", "--port", "8787"]
