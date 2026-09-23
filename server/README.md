# Simple App Sync Server

This is the self-hosted synchronization endpoint for Simple App v4. It is intended for one person's own devices and is not a public multi-user cloud service.

## Deploy with Docker Compose

1. Copy `.env.example` to `.env` and set `SYNC_DOMAIN` to a DNS name that points to the server.
2. Set `PAIR_CODE` to a newly generated random secret of at least 16 characters.
3. Ensure ports 80 and 443 reach the server, then run `docker compose up -d --build`.
4. Confirm `https://<your-domain>/healthz` returns `{"status":"ok"}` before pairing a device.

Caddy obtains and renews the HTTPS certificate. The SQLite database lives in the `sync-data` Docker volume; back up that volume before upgrades.

For private deployments over Tailscale or WireGuard, place a reverse proxy trusted by the devices in front of the `sync` service instead of exposing it directly to the internet.

## Environment

| Variable | Default | Purpose |
| --- | --- | --- |
| `LISTEN_ADDR` | `:8080` | HTTP listener used behind the reverse proxy |
| `DATABASE_PATH` | `/data/sync.db` | SQLite database path |
| `PAIR_CODE` | none | Required one-time pairing secret; 16+ characters |

The server accepts only the version 1 synchronization protocol. Keep the pairing secret private and change it after all intended devices have paired.
