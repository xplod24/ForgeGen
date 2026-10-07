FROM python:3.13-alpine@sha256:2d9aefe2fef018a7eb2c13064c89c71929800fd2e5dccdbf52ea5da5bb8d929a
WORKDIR /app
COPY server.py /app/server.py
USER 1000:1000
CMD ["python", "-u", "/app/server.py"]
