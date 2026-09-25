# Simple App 自託管同步伺服器

這是 Simple App v4 的自託管同步端點，供同一位使用者的個人裝置同步，不是公開的多人雲端服務。一般使用者只需要一套伺服器、一個網域與一個 SQLite 資料庫。

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

### 開發者選用：分離測試與正式環境

若你持續開發 App，想讓測試資料不會同步到正式版，可在同一台電腦保留原本單一伺服器作為測試環境，另外建立一個正式環境網址。這是選用設定，一般使用者不需要執行。

先在 DuckDNS 或你的 DNS 服務建立第二個網域，再在本目錄執行下列命令。它會保留原本 `SYNC_DOMAIN`、`PAIR_CODE` 與 `sync-data` 作為測試環境，並新增正式環境的網域和配對碼：

```powershell
.\initialize-dual-environment.ps1 -ProductionDomain sync.example.com
```

之後以 `docker compose -f compose.yaml -f compose.dual.yaml up -d --build --remove-orphans` 啟動。測試環境使用原本網址；正式環境使用新的網址與獨立的 `sync-prod-data` 資料卷。請從本機 `.env` 讀取正式配對碼，勿貼到公開對話或 Git。

若採 Tailscale 或 WireGuard 私人網路部署，請使用裝置可信任的反向代理連到 `sync` 服務，而非將服務直接公開到網際網路。

## 環境變數

| 變數 | 預設值 | 用途 |
| --- | --- | --- |
| `LISTEN_ADDR` | `:8080` | 反向代理後方的 HTTP 監聽位址 |
| `DATABASE_PATH` | `/data/sync.db` | SQLite 資料庫位置 |
| `SYNC_DOMAIN` | 無 | 預設同步服務的公開 HTTPS 網域 |
| `PAIR_CODE` | 無 | 預設裝置配對碼；至少 16 字元 |
| `SYNC_PROD_DOMAIN` | 無 | 選用雙環境設定中的正式 HTTPS 網域 |
| `PROD_PAIR_CODE` | 無 | 選用雙環境設定中的正式裝置配對碼 |

伺服器目前只接受同步協定第 1 版。請妥善保管配對碼；所有預定裝置配對完成後，建議更換它。
