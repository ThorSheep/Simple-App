# Simple App

繁體中文的 Android 原生離線學生生活管理 App。使用 Kotlin、Jetpack Compose 與 Room 製作，最低支援 Android 8.0。

詳細版本變更與已知限制請參閱 [CHANGELOG](CHANGELOG.md)。

## 功能

- 首頁可顯示並排序本月收支、今日課程、近期事項、行事曆入口與關鍵字公告。
- 記帳支援收入／支出、常用分類與「其他」、備註、日期、精確到分的金額及月統計；交易依日期分組顯示每日淨額。
- 課程支援每週上課時段、教室、教師、顏色與封存，並以課表或清單檢視。
- 待辦可管理作業、報告、考試及生活事項，包含截止日期、報告日期、分組、重要程度、備註與課程關聯。
- 行事曆／行程整合課程、事項與手機行事曆；只讀取使用者選擇的手機日曆，不會寫入手機行事曆。
- 校園公告支援已驗證的中央大學與臺灣海洋大學公開來源，可選擇單位及公告分類訂閱。
- 公告可設定關鍵字醒目顯示、通知與背景自動更新；公告頁分為內容、訂閱管理與設定。
- 可選擇底部導覽、首頁位置、左右滑動切換及系統／淺色／深色主題。
- 支援 JSON 匯出與匯入；備份包含 App 資料與設定，不包含手機行事曆事件或日曆選擇。
- 正式版可檢查 GitHub Release、下載新版 APK，並交由 Android 系統驗證簽章與確認安裝。

## 建置

以 Android Studio 開啟專案，安裝 Android SDK 35，並使用 JDK 17 或 21。

```powershell
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug
```

Debug APK 位於 `app/build/outputs/apk/debug/app-debug.apk`，僅供開發測試。Debug 與正式版使用不同套件識別碼和簽章，可同時安裝，資料彼此獨立。

## 自託管同步

同步伺服器位於 [`server/`](server/)，可用 Docker Compose 搭配 Caddy 部署。先依照 [`server/README.md`](server/README.md) 設定 DNS、HTTPS 網域與一次性配對碼；確認 `https://你的網域/healthz` 回傳 `{"status":"ok"}` 後，在 App 的「設定 → 資料與版本」輸入伺服器網址及配對碼。

首次配對會同步現有收支、課程、上課時段、待辦、公告訂閱與關鍵字。手機行事曆、公告快取、已讀狀態、通知與外觀偏好維持裝置本機。同步採離線優先；在兩台裝置同時修改同一筆資料時，使用最後修改優先規則。自託管同步仍應保留 JSON 匯出備份。

## 發布正式版

正式版固定使用 `tw.thorsheep.simpleapp` 與同一把簽署金鑰。金鑰、密碼、本機設定與個人備份必須保留在 Git 追蹤範圍外。

GitHub Actions 會在一般 push 與 Pull Request 執行測試、lint 與 debug APK 建置；推送 `v*` tag 時，會建置正式簽署 APK 並建立 GitHub Release。發布前請設定下列 GitHub Actions secrets：

- `SIGNING_KEYSTORE_BASE64`
- `SIGNING_STORE_PASSWORD`
- `SIGNING_KEY_ALIAS`
- `SIGNING_KEY_PASSWORD`

發布新版本時，依序更新 `app/build.gradle.kts` 的 `versionCode`、`versionName`、[CHANGELOG](CHANGELOG.md) 及 `docs/releases/v版本號.md`，合併到 `main` 後建立並推送 tag，例如：

```powershell
git tag -a v3.2.0 -m "發布 3.2.0"
git push origin v3.2.0
```

Release 建置完成後，App 會從公開的 GitHub Release 檢查與下載正式 APK。Android Studio 安裝的 debug 版不支援 App 內更新。

## 維護公告來源

公告來源位於 `app/src/main/assets/announcements/`。每校一份 CSV，欄位為 `school,category,parent,name,url,parser,categories`；`categories` 以 `|` 分隔。`available.csv` 是已驗證可在 App 顯示的來源清單，原始的 `ncu.csv` 與 `ntou.csv` 保留所有待驗證來源。

新增或修正來源後，請在 App 手動更新，確認能取得公告標題、日期、分類與原文連結，再將該單位加入 `available.csv`。

## 已知限制

- 未列入 `available.csv` 的公告來源可能因網址失效、連線問題或頁面結構不相容而隱藏，原始資料保留供後續修正。
- 公告關鍵字只比對公告列表的標題、單位與分類，不讀取公告全文。
- Android 的背景更新會受省電及網路條件影響，無法保證準時執行。
- 課程尚無學期起訖、停課例外或重複待辦；事項尚無到期提醒。

## 安全與隱私

App 的記帳、課程、事項與公告資料預設儲存在裝置本機。匯出備份未加密，請自行安全保存。公開儲存庫不應提交簽署金鑰、密碼、API Token、`local.properties`、個人備份或任何可識別個人的資料。
