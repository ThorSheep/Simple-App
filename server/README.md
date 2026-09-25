# Simple App 自託管同步伺服器

這是 Simple App v4 的自託管同步端點，供同一位使用者的個人裝置同步，不是公開的多人雲端服務。預設部署會同時提供隔離的測試與正式環境。

## 使用 Docker Compose 部署

1. 複製 `.env.example` 成為 `.env`。
2. 將 `SYNC_TEST_DOMAIN` 與 `SYNC_PROD_DOMAIN` 設為不同、且都指向這台伺服器的網域名稱，例如 `sync-test.example.com` 與 `sync.example.com`。
3. 將 `TEST_PAIR_CODE` 與 `PROD_PAIR_CODE` 設為不同的新隨機密碼，且各至少 16 字元。
4. 確認伺服器的 80 與 443 連接埠可從網際網路連入，然後在本目錄執行：

   ```powershell
   docker compose up -d --build
   ```

5. 分別開啟兩個網域的 `/healthz`；看到 `{"status":"ok"}` 後，再以 App 配對裝置。

Caddy 會自動申請及續期 HTTPS 憑證。測試資料庫儲存在 Docker 的 `sync-data` 資料卷，正式資料庫儲存在 `sync-prod-data`；升級前請備份兩者。兩個環境的資料與裝置憑證完全隔離。

### 從舊的單一環境升級

若先前已依舊版說明建立 `.env`，可在本目錄執行下列命令。它會保留原本的網址、配對碼與測試資料作為測試環境，並產生新的正式環境配對碼：

```powershell
.\initialize-dual-environment.ps1 -ProductionDomain sync.example.com
```

之後以 `docker compose up -d --build --remove-orphans` 啟動。請從本機 `.env` 讀取正式配對碼，勿貼到公開對話或 Git。

若採 Tailscale 或 WireGuard 私人網路部署，請使用裝置可信任的反向代理連到 `sync` 服務，而非將服務直接公開到網際網路。

## 環境變數

| 變數 | 預設值 | 用途 |
| --- | --- | --- |
| `LISTEN_ADDR` | `:8080` | 反向代理後方的 HTTP 監聽位址 |
| `DATABASE_PATH` | `/data/sync.db` | SQLite 資料庫位置 |
| `SYNC_TEST_DOMAIN` | 無 | 測試同步服務的公開 HTTPS 網域 |
| `SYNC_PROD_DOMAIN` | 無 | 正式同步服務的公開 HTTPS 網域 |
| `TEST_PAIR_CODE` | 無 | 測試裝置配對碼；至少 16 字元 |
| `PROD_PAIR_CODE` | 無 | 正式裝置配對碼；至少 16 字元 |

伺服器目前只接受同步協定第 1 版。請妥善保管配對碼；所有預定裝置配對完成後，建議更換它。
