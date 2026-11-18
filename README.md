# 聚藏 Bookmark App

聚藏是一個個人書籤管理 App。從 Android 分享網址或在 App 內貼上連結，將文章、貼文與影音收進收藏夾。Android 介面使用 Kotlin 與 Jetpack Compose，透過 Spring Boot REST API 讀寫 MySQL。

## 已實作功能

- 電子郵件註冊、驗證、登入與密碼重設。設定 Google OAuth 後可使用 Google 登入與綁定。
- 分享或貼上網址新增收藏，解析可取得的標題與封面，並可手動編輯標題、標籤與筆記。
- 收藏夾支援網格與條列瀏覽。可在夾內依關鍵字與標籤篩選，也可跨收藏夾搜尋並依來源平台過濾。
- 支援單則與批量移動或刪除。整夾搬移或刪除時，首頁顯示背景作業狀態。
- 「今日拾藏」用三則收藏協助回顧，每日以台北時間 06:00 為刷新界線。

## 專案結構

| 路徑 | 內容 |
| --- | --- |
| `bookmark-app/` | Android App 與 JVM 單元測試 |
| `bookmark-backend/` | Spring Boot API 與後端測試 |
| `bookmark-backend/db/schema.sql` | MySQL 空庫結構與來源平台初始資料 |
| `.github/workflows/backend-ci.yml` | 後端 GitHub Actions 測試設定 |

## 本機啟動後端

準備 JDK 17 與 MySQL 8。兩個子專案各自附有 Gradle Wrapper。

1. 在 MySQL 建立並選取空的 `bookmark_db`，執行 [建庫 SQL](bookmark-backend/db/schema.sql)。已有最新版結構的資料庫可直接沿用。若使用其他庫名、主機或連接埠，設定 `DB_URL`。
2. 參照 [`.env.example`](.env.example)，把實際值注入啟動後端的程序。可用同一個 PowerShell 視窗的環境變數，或 IntelliJ Run Configuration 的 **Environment variables**。Spring Boot 不會自動載入 `.env`。
3. 從專案根目錄啟動後端。預設在本機的 `http://localhost:8080` 提供 API。

   ```powershell
   cd bookmark-backend
   .\gradlew.bat bootRun
   ```

| 後端環境變數 | 用途 |
| --- | --- |
| `BOOKMARK_DB_USERNAME`、`BOOKMARK_DB_PASSWORD` | MySQL 帳號與密碼 |
| `BOOKMARK_JWT_SECRET` | 至少 32 個隨機位元組的 Base64 編碼，部署時應跨重啟保留同一把金鑰 |
| `MAIL_USERNAME`、`MAIL_PASSWORD` | Gmail SMTP 帳號與應用程式密碼，用於驗證信與密碼重設 |
| `DB_URL` | 選填。預設指向 `localhost:3306/bookmark_db`。遠端部署時設定目標 JDBC URL 和 TLS |
| `GOOGLE_WEB_CLIENT_ID` | 選填。設定 Google OAuth 的 Web Client ID。若啟用 Google 登入，Android 和後端使用相同值 |
| `AVATAR_UPLOAD_DIR` | 選填。預設為相對於後端工作目錄的 `uploads/avatars` |
| `BOOKMARK_PUBLIC_BASE_URL` | 回傳頭像網址所用的後端根網址，預設 `http://localhost:8080`。設為手機或模擬器可連線的位址，正式部署使用公開 HTTPS 網址 |

MySQL 連線使用 UTC，後端會在啟動時檢查連線時區。頭像網址存在 MySQL 的 `users.avatar_url`，圖片檔存在 `AVATAR_UPLOAD_DIR`。若需保留已上傳的頭像，請將目錄放在持久儲存空間，並和資料庫一起備份。

## 安裝並執行 Android App

用 Android Studio 開啟 `bookmark-app/`，安裝 Android SDK 36，並使用 Android Studio 隨附的 JDK 執行 Gradle。App 支援 Android 7.0（API 24）以上裝置。

啟動後端與 Android Studio 模擬器後，在 `bookmark-app/` 執行以下命令。`10.0.2.2` 讓模擬器連到開發電腦的本機後端。

```powershell
.\gradlew.bat :app:installDebug -PBOOKMARK_API_BASE_URL=http://10.0.2.2:8080/
```

- 實體手機請先啟用 USB 偵錯，再將 API 位址改為手機可連線的電腦區網位址，例如 `http://192.168.1.10:8080/`。
- 從 Android Studio 執行時，可在 `bookmark-app/gradle.properties` 設定本機使用的 `BOOKMARK_API_BASE_URL`。使用後端根網址，保留結尾的 `/`。
- 測試頭像上傳時，請在啟動後端前把 `BOOKMARK_PUBLIC_BASE_URL` 設為裝置可連線的根網址，例如模擬器使用 `http://10.0.2.2:8080`。這會影響新上傳頭像寫入資料庫的網址。
- 若要產生 APK 檔，將命令中的 `:app:installDebug` 換成 `:app:assembleDebug`。產物位於 `bookmark-app/app/build/outputs/apk/debug/app-debug.apk`。

### Release

Release 必須設定 `BOOKMARK_RELEASE_API_BASE_URL`，接受同名 Gradle property 或環境變數，Gradle property 優先。使用穩定的 HTTPS DNS 網址並保留結尾的 `/`。網址在建置時寫入 App，換網域後需重新建置並發行。

```powershell
# 在 bookmark-app/ 執行，將範例網址換成實際部署位址。
$env:BOOKMARK_RELEASE_API_BASE_URL = 'https://api.example.com/'
.\gradlew.bat :app:assembleRelease
```

目前 Release 未配置發行簽章，以上命令產生 `bookmark-app/app/build/outputs/apk/release/app-release-unsigned.apk`。先用發行金鑰簽署再安裝。若使用 Google 登入，在 Google OAuth 設定對應的 Android 套件名稱與簽章指紋。

APK 與 AAB 為本機建置產物，來源庫不包含可下載的安裝包。可依上面的 Debug 步驟從原始碼安裝。

## 測試

| 元件 | 執行目錄 | Windows 指令 |
| --- | --- | --- |
| 後端 | `bookmark-backend/` | `.\gradlew.bat test` |
| Android JVM 單元測試 | `bookmark-app/` | `.\gradlew.bat :app:testDebugUnitTest` |

macOS 或 Linux 使用 `./gradlew`。Android JVM 單元測試不需要啟動模擬器或後端。

後端預設測試使用隔離的 H2 或替身，無須使用 MySQL 與部署機密。`folder_jobs` 測試取用 `db/schema.sql` 的建表定義。另有一項 MySQL UTC 整合測試，必須有專用的隔離資料庫才會啟用，預設會跳過。

[Backend CI](.github/workflows/backend-ci.yml) 提交至 GitHub 後，當 push 或 PR 變更後端或 workflow 自身時，會在 Ubuntu 上以 JDK 17 執行後端測試。

## 設計文件

[介面設計](bookmark-app/DESIGN.md) 記錄目前的品牌配色、主要畫面與操作流程。

## 授權

本專案尚未指定開源授權。
