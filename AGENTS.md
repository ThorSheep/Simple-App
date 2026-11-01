# Simple App 工作規則

本檔適用於整個專案。使用者的當前指示優先於本檔。

## 專案目標與溝通

- Simple App 是以繁體中文為主的個人 Android 管理 App，搭配可自託管的同步伺服器、LINE 個人管家、MCP 與 ee-class 匯入擴充功能。
- 面向使用者的說明、畫面文字、README 與更新紀錄使用繁體中文
- 先說明結果，再說明必要步驟。不要假設使用者會自行推論 Git、Docker、DNS 或 LINE 設定。
- 不要將 API Key、配對碼、LINE token、MCP token、User ID、`.env` 內容或其他敏感資料寫入 Git、文件範例、終端輸出或對話。

## 版本規則

- 大版本（明顯的新系統或跨平台能力）使用 `X.0.0`。
- 小功能使用 `X.Y.0`。
- 純 Bug 修正使用 `X.Y.Z`。
- 純重構、文件或內部維護不應自行提高使用者可見版本號；除非使用者明確要求發版。
- Android 版本由 `app/build.gradle.kts` 的 `versionCode`、`versionName` 管理；Release 說明位於 `docs/releases/vX.Y.Z.md`，完整紀錄位於 `CHANGELOG.md`。

## Git、分支與發布

- 開始修改前先檢查 `git status`、目前分支與遠端狀態，絕不覆寫使用者既有的未提交變更。
- 每個大版本只使用**一條**長期開發分支，命名為 `development/vX`（例如所有 `5.x.x` 工作都使用 `development/v5`）。建立下一個大版本時才建立 `development/v(X+1)`；不能直接開發於 `main`。
- 小功能版（`X.Y.0`）與 Bug 修補版（`X.Y.Z`）都提交到目前的大版本開發分支；不得因單一小版本、修補版或文件修改另開分支。
- 每個可獨立驗證的完成部分，都要先測試、提交，並推送到該大版本開發分支備份。
- 除非使用者在當前對話明確要求，禁止：推送到 `main`、建立或合併 PR、建立 tag、建立 GitHub Release、刪除分支或重寫 Git 歷史。
- 使用者手動測試完成前，保留在目前的大版本開發分支；合併前再次確認遠端 `main` 沒有新提交。
- 發布時依序：完成版本號／更新紀錄／Release 說明 → 測試 → 合併至 `main` → 推送 `main` → 建立並推送 `vX.Y.Z` tag。
- 推送 tag 後會觸發 GitHub Actions 的簽署 APK 與 Release；除非使用者要求，不主動等待、輪詢或宣稱 Release 已完成。

## 測試與品質門檻

- Android 修改後至少執行：`./gradlew.bat testDebugUnitTest lintDebug assembleDebug --console=plain`。
- 伺服器修改後至少執行：在 `server/` 執行 `go test ./...` 及 `docker compose -f compose.yaml -f compose.dual.yaml config --quiet`。
- 修改前後執行 `git diff --check`；有格式化工具時先格式化（Go 使用 `gofmt`）。
- 不把「能編譯」當作功能驗證。涉及同步、LINE、提醒、MCP、部署或 UI 的變更，要列出可供使用者手動驗證的情境。

## 自託管伺服器與安全

- `server/.env` 只存在本機，且不可讀取或顯示其敏感值；只可請使用者在本機自行填入。
- 雙環境模式中：`sync` 是測試環境，`sync-prod` 是正式環境；正式敏感設定使用 `PROD_*` 變數，兩者使用不同 SQLite Docker volume。
- 測試環境不得使用真實 LINE 或 OpenAI 憑證，避免誤發通知與誤計費。
- 伺服器程式變更或 `.env` 設定變更都必須重建／重啟相對應容器才會生效。正式環境可用 `server/restart-production-server.cmd` 快速重啟 `sync-prod`。
- 不主動部署、重啟 Docker、改網域、改 LINE webhook 或呼叫付費 OpenAI API；需要使用者明確授權。
- MCP 只提供唯讀個人資料工具。不要新增修改／建立／刪除資料的 MCP 或 LINE AI 工具，除非使用者明確同意並先完成安全設計。

## 架構與相容性

- Android 原始碼在 `app/src/main/java/tw/thorsheep/studentjournal/`；依資料庫、同步、課程、公告、LINE 設定等職責放入對應檔案，避免再建立全能的 `Data.kt` 或 `MainActivity.kt`。
- 伺服器原始碼在 `server/cmd/simple-app-sync/`；依啟動設定、HTTP 同步、提醒、LINE、MCP、Ollama、OpenAI 等領域拆檔。新增程式應放入對應領域檔案並補測試。
- `personalButlerInstruction` 是 Ollama 與 OpenAI 共用的系統提示詞，目前位於 `server/cmd/simple-app-sync/openai_chat.go`。修改它時需同時考量兩個提供者。
- 同步協定、Room 資料庫、SQLite 結構、備份 JSON 與既有 Docker volumes 均屬使用者資料相容性介面。任何變更都必須提供 migration／向後相容策略與測試，不能直接破壞既有資料。
- 依賴與架構整理應優先採漸進式重構：先抽離責任、維持既有公開 API 與資料格式，再逐步改善；不要為了目錄漂亮而進行高風險的大搬遷。

## AI 提供者

- LINE 的固定指令直接使用本機唯讀 MCP 工具，不應消耗 AI 用量。
- OpenAI 與 Ollama 的選擇由 App 同步設定決定；API Key 與模型名稱只能儲存在伺服器本機 `.env`。
- 未經使用者同意，不得切換使用者的 AI 提供者、模型或執行會產生費用的實際 API 測試。
