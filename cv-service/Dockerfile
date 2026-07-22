# Build from the repository root so the shared proto/ directory (single
# source of truth for both vision-proto and cv_service/gen) is available in
# the build context:
#
#   docker build -f cv-service/Dockerfile -t cv-service .
#
# (docker-compose: use `context: .` + `dockerfile: cv-service/Dockerfile`
# for the cv-service service.)
FROM python:3.12-slim

WORKDIR /app

# Shared gRPC/protobuf contract.
COPY proto/ ./proto/
COPY cv-service/ ./cv-service/

WORKDIR /app/cv-service

RUN pip install --no-cache-dir -e . \
    && ./scripts/gen_proto.sh

EXPOSE 50051

CMD ["python", "-m", "cv_service.server"]
