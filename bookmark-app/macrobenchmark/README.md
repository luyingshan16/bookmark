# 收藏夾效能基準

使用 Android 10 以上的真機，並設定可從手機連線的 `BOOKMARK_API_BASE_URL`。先安裝 `benchmark` 版本，開啟後用測試帳號登入。此版本的套件名稱是 `com.personal.bookmark_app.benchmark`。測試帳號應有足夠的收藏夾可滑動，前後比較時請使用同一裝置、帳號與資料。

API 網址會在建置時寫入 APK。若重開通道後取得新網址，請更新 `BOOKMARK_API_BASE_URL` 並重新執行 `:app:installBenchmark`。

在 `bookmark-app` 下執行下列指令。

```powershell
.\gradlew.bat :app:installBenchmark
# 在真機開啟剛裝好的版本並登入
.\gradlew.bat :macrobenchmark:connectedBenchmarkAndroidTest
```

`coldStartToFolders` 記錄冷啟動至首幀，以及首頁回報收藏夾內容已載入的時間。`scrollFolders` 透過 Android 的 `gfxinfo` 記錄收藏夾捲動時的畫面統計，支援 Perfetto 追蹤未提供完整 frame ID 的裝置。JSON 和 Perfetto trace 可在 `macrobenchmark/build/outputs/connected_android_test_additional_output/` 找到。
