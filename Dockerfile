FROM alpine:latest
RUN apk add --no-cache bash curl grep git
WORKDIR /workspace
