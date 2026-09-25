# Simple App 自託管同步伺服器

這是 Simple App v4 的自託管同步端點，供同一位使用者的個人裝置同步，不是公開的多人雲端服務。

## 使用 Docker Compose 部署

1. 複製 `.env.example` 成為 `.env`。
2. 將 `SYNC_DOMAIN` 設為已指向這台伺服器的網域名稱，例如 `sync.example.com`。
3. 將 `PAIR_CODE` 設為新產生、至少 16 字元的隨機密碼。
4. 確認伺服器的 80 與 443 連接埠可從網際網路連入，然後在本目錄執行：

   ```powershell
   docker compose up -d --build
   ```

5. 開啟 `https://你的網域/healthz`；看到 `{"status":"ok"}` 後，再以 App 配對裝置。

Caddy 會自動申請及續期 HTTPS 憑證。SQLite 資料庫儲存在 Docker 的 `sync-data` 資料卷，升級前請先備份此資料卷。

若採 Tailscale 或 WireGuard 私人網路部署，請使用裝置可信任的反向代理連到 `sync` 服務，而非將服務直接公開到網際網路。

## 環境變數

| 變數 | 預設值 | 用途 |
| --- | --- | --- |
| `LISTEN_ADDR` | `:8080` | 反向代理後方的 HTTP 監聽位址 |
| `DATABASE_PATH` | `/data/sync.db` | SQLite 資料庫位置 |
| `PAIR_CODE` | 無 | 裝置配對所需的密碼；至少 16 字元 |

伺服器目前只接受同步協定第 1 版。請妥善保管配對碼；所有預定裝置配對完成後，建議更換它。
