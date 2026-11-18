# 聚藏 Bookmark App 介面設計

聚藏讓使用者收錄網頁文章、貼文與影音，透過收藏夾、標籤和搜尋整理內容，再用「今日拾藏」回顧收藏。本文件依目前 Android Jetpack Compose 實作整理畫面與操作。

## 視覺語言

以暖紙白作為淺色背景，搭配深炭灰文字、鼠尾草綠的主要操作與選取狀態，以及少量陶土色點綴。Material 3 主題跟隨系統深淺色設定，預設使用品牌配色。各畫面以細邊框、圓角與留白區分內容。

| 用途 | 淺色 | 深色 |
| --- | --- | --- |
| 主色 | `#5E715D` | `#B4C6AA` |
| 陶土點綴 | `#B88578` | `#D6A294` |
| 頁面背景 | `#FBF9F3` | `#22241F` |
| 卡片表面 | `#FFFDF8` | `#2B2E28` |
| 主要文字 | `#2B2C28` | `#EAE7DE` |
| 次要文字 | `#77766F` | `#B8B5AA` |
| 邊框與分隔線 | `#E5E0D5` | `#45483F` |

共用字體使用 `FontFamily.Default`，由 Android 提供字型和中文回退。主題的 `headlineLarge` 為 30 sp 粗體，`titleLarge` 為 22 sp，`titleMedium` 為 16 sp 半粗體，`bodyMedium` 為 14 sp。個別元件可以調整字重，例如首頁頂端的「聚藏」使用 `titleLarge` 粗體。

色彩與字體以 [ui/theme](app/src/main/java/com/personal/bookmark_app/ui/theme) 的 `Color.kt`、`Theme.kt` 和 `Type.kt` 為準。

## 導覽與畫面

登入後進入首頁，從首頁開啟搜尋、新增收藏、今日拾藏與各收藏夾。主要入口放在頂部操作列及首頁內容區。個人檔案用對話框呈現，收藏夾管理和多數收藏編輯操作使用底部面板。Android 分享文字中的網址也可進入新增收藏流程。 [AppNavigation.kt](app/src/main/java/com/personal/bookmark_app/navigation/AppNavigation.kt)

### 首頁與收藏夾管理

- 頂部左側為圓角頭像與「聚藏」，右側為搜尋及新增圖示。頭像開啟「帳號與個人檔案」，可查看電子郵件、從相簿更換頭像、查看或綁定 Google 帳號，以及登出。
- 「今日拾藏」卡片位於「我的收藏」上方。「我的收藏」顯示分類數，支援雙欄網格與條列切換。收藏夾項目顯示名稱、收藏數與封面。
- 點入有收藏的收藏夾會開啟清單。點到空夾時，底部面板提供「新增第一則收藏」和「稍後再說」。
- 「管理收藏夾」底部面板可新建、改名或刪除收藏夾。預設的「未分類」沒有改名與刪除操作。刪除有內容的收藏夾時，可先將收藏移到既有或新建的目的夾，也可確認連同收藏刪除。背景作業進度在首頁顯示。

畫面實作位於 [ui/screen/home](app/src/main/java/com/personal/bookmark_app/ui/screen/home)。

### 收藏夾清單與全域搜尋

| 畫面 | 內容與操作 |
| --- | --- |
| 收藏夾清單 | 頂部顯示收藏夾名稱。可在夾內搜尋、以多個標籤篩選，並分頁瀏覽結果。選取多個標籤時，收藏須符合所有選取標籤。 |
| 批量管理 | 勾選收藏後可移動或刪除。使用「全選」會選取符合目前條件的收藏，包含尚未載入的頁面。刪除前顯示確認。 |
| 全域搜尋 | 頂部提供可清除的關鍵字輸入框，下方以平台膠囊切換「全部」或指定來源。來源筆數可獨立載入。無結果時提示調整關鍵字或平台。 |

清單和搜尋共用收藏卡片。卡片有 12 dp 圓角，顯示來源、日期、標題及標籤，有封面時顯示右側縮圖。筆記較長時可展開，原始連結可用裝置上的應用程式開啟。三點選單提供編輯標題、筆記與標籤、移動至其他收藏夾及刪除。

畫面與元件分別位於 [收藏夾清單](app/src/main/java/com/personal/bookmark_app/ui/screen/folderbookmark)、[搜尋](app/src/main/java/com/personal/bookmark_app/ui/screen/searchbookmark) 與 [BookmarkCard.kt](app/src/main/java/com/personal/bookmark_app/ui/components/bookmark/BookmarkCard.kt)。

### 新增收藏

- 頂部操作列顯示返回、「新增收藏」及「儲存」。可貼上網址或從其他 App 分享進來。解析狀態在網址欄附近顯示，可點「重新解析」。取不到預覽時，仍可手動輸入標題後儲存。
- 表單可預覽取得的封面、選擇或新建收藏夾、選擇來源平台、填寫文章或影音標題、編寫個人拾藏札記，以及切換「加入今日拾藏」。
- 標籤區以膠囊呈現目前收藏夾的標籤，可選取既有標籤或自訂新標籤，每則最多五個。標題、收藏夾名稱及標籤名稱的長度限制以共用輸入規則為準。

畫面實作位於 [ui/screen/addbookmark](app/src/main/java/com/personal/bookmark_app/ui/screen/addbookmark)，長度限制見 [InputLimits.kt](app/src/main/java/com/personal/bookmark_app/ui/utils/InputLimits.kt)。

### 今日拾藏

從首頁卡片進入「今日拾藏」，以直向卡片回顧三則收藏。卡片顯示來源、日期、標題及可用的封面、標籤與筆記，點擊可開啟原始內容。清晨 06:00（台北時間）是每日刷新界線，收藏總數超過三則時可手動「換一組」。沒有收藏與收藏不足三則各有不同提示，並提供新增收藏的入口。

畫面實作位於 [ui/screen/dailypicks](app/src/main/java/com/personal/bookmark_app/ui/screen/dailypicks)。

### 帳號與驗證

登入畫面以「聚藏」為品牌標題，提供電子郵件與密碼登入、Google 登入，以及註冊和忘記密碼入口。註冊後導向電子郵件驗證。忘記密碼依序輸入電子郵件、驗證碼和新密碼。

流程見 [AppNavigation.kt](app/src/main/java/com/personal/bookmark_app/navigation/AppNavigation.kt) 與 [PasswordResetRoute.kt](app/src/main/java/com/personal/bookmark_app/navigation/PasswordResetRoute.kt)。

## 更新這份文件

調整介面後，優先核對導覽入口、畫面用語與空白、載入或失敗狀態，再同步本文。共用色彩、字體和元件行為以對應的 Compose 程式碼為準。
