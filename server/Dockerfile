FROM golang:1.23-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /simple-app-sync ./cmd/simple-app-sync

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /simple-app-sync /simple-app-sync
VOLUME ["/data"]
EXPOSE 8080
ENTRYPOINT ["/simple-app-sync"]
