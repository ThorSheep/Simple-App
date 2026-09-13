# Simple App

詳細版本更新與已知限制請參閱 [CHANGELOG](CHANGELOG.md)。

繁體中文 Android 原生離線 App。Kotlin、Jetpack Compose、Room，最低 Android 8.0。

## 功能
- 首頁：本月收支、今日課程、近期／逾期事項、行事曆入口與關鍵字公告；各區塊可獨立開關和排序。
- 記帳：收入／支出、15 種常用分類、備註、日期、精確到分的金額與月統計。
- 課程：進入後預設課表，可切換課程清單；每門課可設定多個上課時段、各時段教室、教師、顏色與封存。
- 待辦：作業、報告、考試或生活事項；截止日期／時間、報告日期／時間、分組資料、重要程度、備註、課程關聯、完成切換與篩選。
- 行事曆／行程：七日行程合併課程、事項與手機行事曆；可自由勾選各日曆及 App 內容。僅要求 READ_CALENDAR，不寫入手機日曆。拒絕權限仍可使用 App 行程。
- 校園公告：可選擇中大、海大的已驗證公開單位；支援單位全部訂閱或只訂閱公告分類，顯示分類、標題、日期與原文連結。
- 公告關鍵字：使用者自訂、停用／刪除，比對標題、單位與分類，醒目顯示並可篩選；可選擇新公告通知。
- 公告自動更新：關閉、每 6 小時、12 小時或每天；可限制不計量網路。系統可能依省電與網路狀態延後執行。首次更新建立基準，不補發舊公告。
- 介面設定：底部常用功能可選擇、排序及設定首頁位置；可選左右滑動切換、跟隨系統、淺色或深色主題。
- App 更新：正式版可自動或手動檢查 GitHub Release，下載後由 Android 系統驗證簽章及確認安裝；Android Studio debug 版不支援 App 內更新。
- 全部資料 JSON 匯出／匯入，還原前確認、驗證與 Room 交易保護。
- 透過 Android 系統檔案選擇器備份；公告更新需要網路權限。

## 開啟與建置
以 Android Studio 開啟本資料夾，安裝 SDK 35，使用 JDK 17 或 21。

```powershell
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug
```

APK：`app/build/outputs/apk/debug/app-debug.apk`。Debug 版僅供開發測試。

本電腦 SDK 位於 `tools/sdk`；`local.properties` 已設定本機路徑，不提交 Git。
Windows 若 Java 出現 `Unable to establish loopback connection`，可使用專案內暫存目錄：

```powershell
New-Item -ItemType Directory -Force tools/sockets | Out-Null
$env:JAVA_TOOL_OPTIONS = "-Djdk.net.unixdomain.tmpdir=$PWD/tools/sockets"
$env:GRADLE_USER_HOME = "$PWD/tools/gradle-home"
.\gradlew.bat assembleDebug
```

## 手機驗收
1. 安裝 APK，新增一筆 125.50 元支出、一筆收入，檢查本月結餘。
2. 新增今天兩堂課，確認首頁與課表依時間排列；新增不同星期課程。
3. 新增關聯課程的待辦，測試編輯、完成、逾期；刪除課程後待辦應保留。
4. 強制關閉後重開 App，資料應仍在。
5. 匯出 JSON，傳到另一台手機匯入，確認全部資料相同。
6. 取消還原或匯入無效檔案，既有資料不應改變。
7. 分別測試 S26 Ultra、Pixel 10 的手勢導覽、鍵盤、大字體及旋轉畫面。

## 正式簽署與 GitHub 發布
正式版固定使用 `tw.thorsheep.simpleapp` 識別碼與同一簽署金鑰。
請在首次公開發行前安全建立並備份金鑰；切勿提交金鑰、密碼或個人 JSON 備份。
Debug 與正式版簽章不同。Debug 版的識別碼為 `tw.thorsheep.simpleapp.debug`，可與正式版同時安裝；Android Studio 的 Run 會安裝 Debug 版，不會覆蓋正式版。兩個版本的本機資料彼此獨立，若需要帶入正式版資料，請在正式版匯出 JSON 後於 Debug 版匯入。

GitHub Actions 已設定：一般 push/PR 執行檢查並提供測試 APK；`v*` tag 在檢查通過後建置正式版並建立 Release。Release 標題使用版本號（例如 `v2.2.1`），說明使用「Simple App 版本號」與「本次更新：」的中文格式。
需在儲存庫 Actions secrets 設定：
- SIGNING_KEYSTORE_BASE64
- SIGNING_STORE_PASSWORD
- SIGNING_KEY_ALIAS
- SIGNING_KEY_PASSWORD

每次發行先提高 `app/build.gradle.kts` 的 `versionCode` 與 `versionName`，更新 `CHANGELOG.md` 的詳細紀錄，並新增 `docs/releases/v版本號.md` 作為簡短 Release 說明，再建立相符 tag（例如 v3.0.0）。發布流程會讀取該版本說明檔。
正式版啟動時可自動檢查 GitHub Release，也可在設定手動檢查；下載完成後由 Android 系統驗證簽章並確認安裝。Android Studio debug 版因簽章不同，不支援 App 內更新。

版本規則：`2.0.1` 為修正錯誤、`2.1.0` 為小功能更新、`3.0.0` 為大版本更新。

## 手動新增或維護公告來源

公告來源以每間學校一份 CSV 放在 `app/src/main/assets/announcements/`，例如中央大學是 `ncu.csv`。欄位為 `school,category,parent,name,url,parser,categories`；`parent` 留空代表沒有上層單位，`categories` 以 `|` 分隔。一般靜態網頁的 `parser` 填 `GENERIC`，App 會尋找附有日期的公告連結。

`available.csv` 是已驗證可連線的來源清單；App 只顯示清單內的單位。原始 `ncu.csv` 與 `ntou.csv` 會保留所有單位與網址，待網址或解析方式修正後，再將對應的學校與單位名稱加入 `available.csv`。

## 已知公告來源問題

- `available.csv` 以外的 NCU 與 NTOU 單位目前因網址失效、連線逾時、TLS 問題或公告頁結構不相容而暫時隱藏；原始資料保留在各校 CSV，待有使用需求時再逐一確認現行網址與解析方式。
- 海大資訊工程學系與食品科學系使用 `NTOU_CSIE`／`NTOU_LIST` 列表解析器，讀取公告列表的標題與日期；若校方變更列表 HTML 結構，需重新調整選擇器。

如果網站有固定分類，將分類名稱填入 `categories`，使用者就能單獨訂閱。若網站不是一般靜態頁面，請在同一筆資料指定 `AnnouncementParser`，並在 `app/src/main/java/tw/thorsheep/studentjournal/Announcements.kt` 加入對應抓取方法：`SHSD_JSON` 是公開 JSON API 範例，`CSIE_SECTIONS` 是分類 HTML 區塊範例。新增後先按「更新」確認 App 能取得標題、日期、分類與原文網址，再提供給使用者。

## 備份格式
3.1.0 使用 `schemaVersion: 5`：`entries`（記帳）、`courses`、`meetings`、`items`、`subscriptions`、`keywords`、`options` 與 `exportedAt`。支援匯入 schema 1～4 的舊備份，舊課程與待辦會轉換並保留關聯。手機日曆事件與裝置日曆 ID 不匯出，換機後需重新勾選。公告內容會在還原後重新更新。
金額以整數分儲存。限制 5 MB / 20,000 筆，還原完整取代現有資料。
備份未加密。卸載 App 會刪除本機資料，請先匯出。

## 3.0.0 範圍與限制
無登入、雲端同步、跨週／學期例外排課或重複待辦。課程以每週固定時段顯示，學期結束可封存；事項尚無到期通知。公告只比對列表欄位，不抓取公告全文。自動更新只讀取訂閱來源；失敗紀錄在設定頁顯示，下個週期再次嘗試。

課程與事項使用獨立 Room 資料表；正式 2.3.0 資料庫升級時保留原有課程 ID，避免將同名不同課程誤合併。刪除課程會刪除其時段，保留事項並解除關聯。3.0.0 驗收步驟見 [docs/3.0.0-testing.md](docs/3.0.0-testing.md)。

## 本機驗證紀錄
執行 `assembleDebug testDebugUnitTest lintDebug`；實際結果以 `app/build/reports` 為準。
APK 仍需在 S26 Ultra / Pixel 10 進行實機驗收。


## 此專案正式簽署
本機正式金鑰位於 `.signing/release.jks`；密碼以 Windows DPAPI 加密存於 `.signing/password.dpapi`，只可由本機同一 Windows 使用者解密。兩者均忽略，不會提交到 Git。

```powershell
pwsh -File scripts/build-release.ps1
```

正式 APK 輸出：`dist/simple-app-<版本號>.apk`，例如 `dist/simple-app-2.1.0.apk`。請使用正式版，以便後續相同簽章的 APK 覆蓋更新。
**金鑰長期備份仍需由你保存到安全的位置。僅複製 DPAPI 密碼檔到另一台電腦無法解密。**
GitHub Actions secrets 亦保存簽署所需資料，但不能作為可下載的金鑰備份。
私人儲存庫的 Release 下載需要登入獲授權 GitHub 帳號。
