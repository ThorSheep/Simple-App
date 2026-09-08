# Simple App

繁體中文 Android 原生離線 App。Kotlin、Jetpack Compose、Room，最低 Android 8.0。

## 功能
- 首頁：今日課程、最近五筆未完成待辦、本月收支。
- 記帳：收入／支出、15 種常用分類、備註、日期、精確到分的金額與月統計。
- 課表：星期切換、上課時段、教室、教師；同一課程多時段分筆新增。
- 待辦：截止日期、備註、課程關聯、完成切換、逾期提示。
- 校園公告：可選擇中大、海大的已驗證公開單位，抓取標題、日期與原文連結。
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
Debug 與正式版簽章不同，切換前先匯出備份，再移除 Debug 版、安裝正式版並還原。

GitHub Actions 已設定：一般 push/PR 執行檢查並提供測試 APK；`v*` tag 在檢查通過後建置正式版並建立 Release。
需在儲存庫 Actions secrets 設定：
- SIGNING_KEYSTORE_BASE64
- SIGNING_STORE_PASSWORD
- SIGNING_KEY_ALIAS
- SIGNING_KEY_PASSWORD

每次發行先提高 `app/build.gradle.kts` 的 `versionCode` 與 `versionName`，再建立相符 tag（例如 v1.0.1）。
目前更新方式是手動下載 GitHub Release 的 APK 後安裝。尚無 App 內檢查更新。

版本規則：`2.0.1` 為修正錯誤、`2.1.0` 為小功能更新、`3.0.0` 為大版本更新。

## 備份格式
`app: simple-app`、`schemaVersion: 1`、`exportedAt`、`entries`。
金額以整數分儲存。限制 5 MB / 20,000 筆，還原完整取代現有資料。
備份未加密。卸載 App 會刪除本機資料，請先匯出。

## 第一版界線
無登入、雲端同步、通知提醒、跨週／學期例外排課、重複待辦或自動更新。

## 本機驗證紀錄
執行 `assembleDebug testDebugUnitTest lintDebug`；實際結果以 `app/build/reports` 為準。
APK 仍需在 S26 Ultra / Pixel 10 進行實機驗收。


## 此專案正式簽署
本機正式金鑰位於 `.signing/release.jks`；密碼以 Windows DPAPI 加密存於 `.signing/password.dpapi`，只可由本機同一 Windows 使用者解密。兩者均忽略，不會提交到 Git。

```powershell
pwsh -File scripts/build-release.ps1
```

正式 APK 輸出：`dist/simple-app.apk`。請使用正式版，以便後續相同簽章的 APK 覆蓋更新。
**金鑰長期備份仍需由你保存到安全的位置。僅複製 DPAPI 密碼檔到另一台電腦無法解密。**
GitHub Actions secrets 亦保存簽署所需資料，但不能作為可下載的金鑰備份。
私人儲存庫的 Release 下載需要登入獲授權 GitHub 帳號。
