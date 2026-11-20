# 正式部署與 HTTPS

在一台有公網 IP 的 Linux 伺服器上執行 Docker Engine 和 Compose plugin。以下以 Ubuntu 舉例。Caddy 會接收公開的 HTTP 和 HTTPS 流量，並在 Compose 網路內將請求轉送給 Spring Boot。MySQL 也走 Compose 內部網路。 [Docker Engine 安裝說明](https://docs.docker.com/engine/install/ubuntu/)

## 設定網域和服務

1. 在 DNS 為 API 子網域建立 `A` 記錄，指向伺服器的公網 IPv4。有可從外部連線的 IPv6 時，再加 `AAAA` 記錄。允許外部連到伺服器的 TCP `80` 和 `443`，供 Caddy 申請及續訂憑證。 [Caddy 自動 HTTPS 說明](https://caddyserver.com/docs/automatic-https)
2. 將專案帶到伺服器，在專案根目錄建立正式環境的 `.env`。由 `.env.example` 複製時，使用 `umask 077`，讓新檔案只開放給目前的帳號。

   ```bash
   umask 077
   cp -n .env.example .env
   ```

3. 在 `.env` 設好以下欄位。若要保留原環境的收藏和頭像，先準備資料庫與頭像的備份，並在正式環境開放使用前完成匯入和驗證。

   | 欄位 | 填入的值 |
   | --- | --- |
   | `BOOKMARK_API_DOMAIN` | API 網域，例如 `api.example.com`。只填主機名稱 |
   | `MYSQL_ROOT_PASSWORD`、`BOOKMARK_DB_USERNAME`、`BOOKMARK_DB_PASSWORD` | 正式 MySQL 的管理員密碼、應用程式帳號與密碼。使用兩組不同的密碼 |
   | `BOOKMARK_JWT_SECRET` | 至少 32 個隨機位元組的 Base64 金鑰。可用 `openssl rand -base64 32` 產生，部署和重啟沿用正式環境的同一把 |
   | `MAIL_USERNAME`、`MAIL_PASSWORD` | 寄送註冊驗證信與密碼重設信的 Gmail 帳號和應用程式密碼 |
   | `GOOGLE_WEB_CLIENT_ID` | 啟用 Google 登入時填寫，使用與 Android Release 相同的 Web Client ID |

4. 從專案根目錄執行以下指令。若沿用 Flyway 導入前建立的資料庫，先完成 [既有資料庫首次導入 Flyway](#既有資料庫首次導入-flyway)。正式版 Compose 設定會把頭像的公開網址設為 `https://<API 網域>`，並將主機對外的 HTTP 與 HTTPS 連接埠交給 Caddy。

   ```bash
   docker compose -f compose.yaml -f compose.production.yaml config --quiet
   docker compose -f compose.yaml -f compose.production.yaml up -d --build
   docker compose -f compose.yaml -f compose.production.yaml ps
   ```

5. 從另一台裝置測試，將範例網域換成實際網址。收到 `401` 代表 HTTPS 已轉送到需要登入的 API。若連線或憑證有問題，查看 `docker compose -f compose.yaml -f compose.production.yaml logs caddy api`。

   ```bash
   curl -i https://api.example.com/api/folders
   ```

6. 將 Android 的 `BOOKMARK_RELEASE_API_BASE_URL` 設為正式網址，保留結尾 `/`。用簽署過的 Release 版測試驗證信、登入、收藏和頭像。啟用 Google 登入時，也要用正式簽章測試。 [Android Release 建置步驟](../README.md#release)

## 既有資料庫首次導入 Flyway

新資料庫會自動套用 V1。若資料庫是由舊版 MySQL 腳本建立，先依下方的備份指令備份，再核對既有表結構與必要的 `bookmark_sources` 初始資料是否符合 [V1](../bookmark-backend/src/main/resources/db/migration/V1__initial_schema.sql)。Flyway 的基準版本標記本身不會比對結構。

確認相符後，使用現有的專案目錄、Compose 專案名稱和 MySQL 資料卷，執行一次：

```bash
sudo SPRING_FLYWAY_BASELINE_ON_MIGRATE=true docker compose -f compose.yaml -f compose.production.yaml up -d --build
sudo docker compose -f compose.yaml -f compose.production.yaml logs -f api
```

看到 Flyway 登記基準版本 `1` 且後端成功啟動後，按 Ctrl+C 離開日誌，再執行一般的 `up -d`，讓後端恢復預設設定。

```bash
sudo docker compose -f compose.yaml -f compose.production.yaml up -d
```

本機 Compose 接手既有資料庫時，可省略 `-f compose.production.yaml`。在 PowerShell 用 `$env:SPRING_FLYWAY_BASELINE_ON_MIGRATE = 'true'` 設定。成功後用 `Remove-Item Env:SPRING_FLYWAY_BASELINE_ON_MIGRATE` 清除，再執行 `docker compose up -d`。

## 備份

定期將 MySQL 與頭像備份存到伺服器以外的受保護位置，並用隔離的環境試還原。先建立權限受限的備份目錄，再執行以下指令。

```bash
umask 077
backup_dir="backups/$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -p "$backup_dir"
docker compose -f compose.yaml -f compose.production.yaml exec -T db sh -c \
  'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysqldump -uroot --single-transaction --no-tablespaces "$MYSQL_DATABASE"' \
  > "$backup_dir/bookmark_db.sql"
docker compose -f compose.yaml -f compose.production.yaml exec -T api \
  tar -czf - -C /app/uploads/avatars . > "$backup_dir/avatars.tar.gz"
```

保留 Caddy 的 `caddy-data` 資料卷，以便重啟後繼續使用已有憑證。正式服務的容器更新使用同一組 Compose 檔，維護時保留資料卷。
