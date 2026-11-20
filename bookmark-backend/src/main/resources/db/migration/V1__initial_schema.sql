-- MySQL 8.0. Flyway applies this migration to a new Bookmark database.
-- Technical DATETIME values use UTC. selection_date is a Taipei business date.
SET NAMES utf8mb4;
SET SESSION time_zone = '+00:00';

CREATE TABLE `users` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '使用者唯一識別碼',
  `email` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '使用者電子郵件，作為登入帳號',
  `password_hash` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '使用者密碼雜湊值',
  `avatar_url` varchar(2048) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '使用者個人頭像圖片網址',
  `email_verified_at` datetime DEFAULT NULL COMMENT 'Email 驗證成功時間',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '帳號建立時間',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '帳號最後更新時間',
  `google_sub` varchar(255) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `email` (`email`),
  UNIQUE KEY `uk_users_google_sub` (`google_sub`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='使用者資料表，儲存帳號及個人基本資料';

CREATE TABLE `bookmark_sources` (
  `code` varchar(30) COLLATE utf8mb4_unicode_ci NOT NULL,
  `display_name` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL,
  `sort_order` smallint unsigned NOT NULL,
  `domains` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '以逗號分隔的來源網域',
  PRIMARY KEY (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `folders` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '收藏夾唯一識別碼',
  `user_id` bigint NOT NULL COMMENT '所屬使用者 ID',
  `name` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '收藏夾名稱',
  `image_url` varchar(2048) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '收藏夾最新一筆收藏的預覽圖片網址',
  `is_default` tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否為預設未分類收藏夾',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '收藏夾建立時間',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '收藏夾最後更新時間',
  `default_folder_user_id` bigint GENERATED ALWAYS AS ((case when (`is_default` = true) then `user_id` else NULL end)) STORED,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_folder_name` (`user_id`,`name`),
  UNIQUE KEY `uq_folder_id_user` (`id`,`user_id`),
  UNIQUE KEY `uq_default_folder` (`default_folder_user_id`),
  KEY `idx_folders_user` (`user_id`),
  CONSTRAINT `fk_folders_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='收藏夾資料表，管理每個使用者的收藏分類';

CREATE TABLE `bookmarks` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '收藏唯一識別碼',
  `user_id` bigint NOT NULL COMMENT '收藏所屬使用者 ID',
  `folder_id` bigint NOT NULL COMMENT '收藏所屬收藏夾 ID',
  `url` varchar(2048) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '收藏的原始網頁網址',
  `title` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '收藏標題',
  `content` text COLLATE utf8mb4_unicode_ci COMMENT '收藏的內文或使用者保存的文字內容',
  `note` text COLLATE utf8mb4_unicode_ci COMMENT '使用者自行撰寫的收藏筆記或備註',
  `source` varchar(30) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'OTHER' COMMENT '收藏來源平台',
  `image_url` varchar(2048) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '收藏網頁的預覽圖片網址',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '收藏建立時間',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '收藏最後更新時間',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_bookmark_id_user` (`id`,`user_id`),
  UNIQUE KEY `uq_bookmark_id_user_folder` (`id`,`user_id`,`folder_id`),
  KEY `fk_bookmarks_folder` (`folder_id`,`user_id`),
  KEY `idx_bookmarks_user_created` (`user_id`,`created_at`),
  KEY `idx_bookmarks_folder` (`folder_id`),
  KEY `idx_bookmarks_user` (`user_id`),
  KEY `fk_bookmarks_source` (`source`),
  KEY `idx_bookmarks_user_folder_created_id` (`user_id`,`folder_id`,`created_at`,`id`),
  CONSTRAINT `fk_bookmarks_folder` FOREIGN KEY (`folder_id`, `user_id`) REFERENCES `folders` (`id`, `user_id`),
  CONSTRAINT `fk_bookmarks_source` FOREIGN KEY (`source`) REFERENCES `bookmark_sources` (`code`),
  CONSTRAINT `fk_bookmarks_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='收藏資料表，儲存收藏網址、標題、內文及來源';

CREATE TABLE `tags` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '標籤唯一識別碼',
  `user_id` bigint NOT NULL COMMENT '標籤所屬使用者 ID',
  `folder_id` bigint NOT NULL COMMENT '標籤所屬收藏夾 ID',
  `name` varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '標籤名稱',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '標籤建立時間',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_tag_id_user_folder` (`id`,`user_id`,`folder_id`),
  UNIQUE KEY `uq_tag_folder_name` (`user_id`,`folder_id`,`name`),
  KEY `fk_tags_folder` (`folder_id`,`user_id`),
  KEY `idx_tags_user_folder` (`user_id`,`folder_id`),
  CONSTRAINT `fk_tags_folder` FOREIGN KEY (`folder_id`, `user_id`) REFERENCES `folders` (`id`, `user_id`),
  CONSTRAINT `fk_tags_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='標籤資料表，管理每個使用者各收藏夾的標籤';

CREATE TABLE `bookmark_tags` (
  `bookmark_id` bigint NOT NULL COMMENT '收藏 ID',
  `tag_id` bigint NOT NULL COMMENT '標籤 ID',
  `user_id` bigint NOT NULL COMMENT '所屬使用者 ID',
  `folder_id` bigint NOT NULL COMMENT '所屬收藏夾 ID',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '收藏與標籤關聯建立時間',
  PRIMARY KEY (`bookmark_id`,`tag_id`),
  KEY `fk_bookmark_tags_bookmark` (`bookmark_id`,`user_id`,`folder_id`),
  KEY `fk_bookmark_tags_tag` (`tag_id`,`user_id`,`folder_id`),
  KEY `idx_bookmark_tags_tag` (`tag_id`),
  KEY `idx_bookmark_tags_user_folder` (`user_id`,`folder_id`),
  CONSTRAINT `fk_bookmark_tags_bookmark` FOREIGN KEY (`bookmark_id`, `user_id`, `folder_id`) REFERENCES `bookmarks` (`id`, `user_id`, `folder_id`),
  CONSTRAINT `fk_bookmark_tags_tag` FOREIGN KEY (`tag_id`, `user_id`, `folder_id`) REFERENCES `tags` (`id`, `user_id`, `folder_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='收藏標籤關聯表，限制標籤只能用於所屬收藏夾的收藏';

CREATE TABLE `daily_pick_selections` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `bookmark_id` bigint NOT NULL,
  `selection_date` date NOT NULL,
  `position` tinyint NOT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_daily_pick_user_position` (`user_id`,`position`),
  UNIQUE KEY `uk_daily_pick_user_bookmark` (`user_id`,`bookmark_id`),
  KEY `fk_daily_pick_bookmark_owner` (`bookmark_id`,`user_id`),
  CONSTRAINT `fk_daily_pick_bookmark_owner` FOREIGN KEY (`bookmark_id`, `user_id`) REFERENCES `bookmarks` (`id`, `user_id`) ON DELETE CASCADE,
  CONSTRAINT `fk_daily_pick_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE,
  CONSTRAINT `chk_daily_pick_position` CHECK ((`position` between 1 and 3))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `email_verification_codes` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'Email 驗證紀錄唯一識別碼',
  `email` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '接收驗證碼的電子郵件',
  `code_hash` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '驗證碼雜湊值',
  `expires_at` datetime NOT NULL COMMENT '驗證碼有效期限',
  `verified_at` datetime DEFAULT NULL COMMENT '驗證成功時間',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '驗證碼建立時間',
  `attempts` int NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_verification_email` (`email`),
  KEY `idx_verification_expiry` (`expires_at`),
  KEY `idx_email_verification_code_latest` (`email`,`created_at`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Email 驗證碼資料表，管理驗證碼及有效期限';

CREATE TABLE `password_reset_codes` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `code_hash` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `expires_at` datetime(6) NOT NULL,
  `attempts` int NOT NULL DEFAULT '0',
  `verified_at` datetime(6) DEFAULT NULL,
  `reset_token_hash` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `reset_token_expires_at` datetime(6) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_password_reset_codes_user` (`user_id`),
  CONSTRAINT `fk_password_reset_codes_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE,
  CONSTRAINT `chk_password_reset_attempts` CHECK ((`attempts` >= 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `refresh_tokens` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'Refresh Token 紀錄唯一識別碼',
  `user_id` bigint NOT NULL COMMENT 'Token 所屬使用者 ID',
  `token_hash` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'Refresh Token 雜湊值',
  `expires_at` datetime NOT NULL COMMENT 'Refresh Token 有效期限',
  `revoked_at` datetime DEFAULT NULL COMMENT 'Refresh Token 撤銷時間',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Refresh Token 建立時間',
  `family_id` bigint DEFAULT NULL,
  `absolute_expires_at` datetime(6) DEFAULT NULL,
  `successor_id` bigint DEFAULT NULL,
  `rotation_request_hash` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `token_hash` (`token_hash`),
  KEY `idx_refresh_tokens_user` (`user_id`),
  KEY `idx_refresh_tokens_expiry` (`expires_at`),
  KEY `idx_refresh_token_family` (`user_id`,`family_id`,`revoked_at`),
  CONSTRAINT `fk_refresh_tokens_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='登入 Token 資料表，管理使用者的 Refresh Token';

CREATE TABLE `bookmark_batch_jobs` (
  `id` char(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `user_id` bigint NOT NULL,
  `source_id` bigint NOT NULL,
  `target_id` bigint NOT NULL,
  `total` int NOT NULL,
  `succeeded` int NOT NULL DEFAULT '0',
  `failed` int NOT NULL DEFAULT '0',
  `attempts` int NOT NULL DEFAULT '0',
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `operation` varchar(6) COLLATE utf8mb4_unicode_ci NOT NULL,
  `is_pending` tinyint(1) GENERATED ALWAYS AS (((`succeeded` + `failed`) < `total`)) VIRTUAL,
  PRIMARY KEY (`id`),
  KEY `idx_move_jobs_user_created` (`user_id`,`created_at`),
  KEY `idx_bookmark_batch_pending` (`is_pending`,`attempts`,`created_at`,`id`),
  KEY `idx_bookmark_batch_user_pending` (`user_id`,`is_pending`),
  CONSTRAINT `fk_move_jobs_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `bookmark_batch_items` (
  `job_id` char(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `bookmark_id` bigint NOT NULL,
  `failed` tinyint(1) NOT NULL DEFAULT '0',
  PRIMARY KEY (`job_id`,`bookmark_id`),
  KEY `idx_move_items_pending` (`job_id`,`failed`,`bookmark_id`),
  CONSTRAINT `fk_move_items_job` FOREIGN KEY (`job_id`) REFERENCES `bookmark_batch_jobs` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `folder_jobs` (
  `id` varchar(36) NOT NULL,
  `user_id` bigint NOT NULL,
  `source_folder_id` bigint NOT NULL,
  `target_folder_id` bigint DEFAULT NULL,
  `new_folder_name` varchar(50) DEFAULT NULL,
  `delete_bookmarks` tinyint(1) NOT NULL DEFAULT '0',
  `state` varchar(12) NOT NULL DEFAULT 'PENDING',
  `attempts` int NOT NULL DEFAULT '0',
  `error_message` varchar(255) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  KEY `idx_folder_move_user_created` (`user_id`,`created_at`,`id`),
  KEY `idx_folder_move_pending` (`state`,`attempts`,`created_at`,`id`),
  CONSTRAINT `fk_folder_jobs_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `bookmark_create_requests` (
  `user_id` bigint NOT NULL,
  `request_id` char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `fingerprint` char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `bookmark_id` bigint NOT NULL,
  `expires_at` datetime(6) NOT NULL,
  PRIMARY KEY (`user_id`,`request_id`),
  KEY `idx_bookmark_create_requests_expiry` (`expires_at`),
  CONSTRAINT `fk_bookmark_create_requests_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO `bookmark_sources`
  (`code`, `display_name`, `sort_order`, `domains`)
VALUES
  ('DCARD','Dcard',10,'dcard.tw'),
  ('INSTAGRAM','Instagram',20,'instagram.com'),
  ('OTHER','Other',100,''),
  ('THREADS','Threads',30,'threads.com,threads.net'),
  ('YOUTUBE','YouTube',40,'youtube.com,youtu.be');
