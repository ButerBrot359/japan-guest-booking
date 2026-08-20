# Multi-stage: статическая сборка Go, рантайм — alpine с CA-сертификатами
# (бот ходит на https://api.telegram.org — без сертификатов TLS не взлетит).
FROM golang:1.23-alpine AS build
WORKDIR /build
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 go build -o /bot ./cmd/bot

FROM alpine:3.20
RUN apk add --no-cache ca-certificates
COPY --from=build /bot /bot
ENTRYPOINT ["/bot"]
