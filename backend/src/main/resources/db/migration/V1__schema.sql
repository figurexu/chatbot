-- 历史人物对话应用 表结构
CREATE TABLE IF NOT EXISTS characters (
    id            VARCHAR(32)  NOT NULL,
    name          VARCHAR(64)  NOT NULL,
    dynasty       VARCHAR(64)  NOT NULL DEFAULT '',
    title         VARCHAR(128) NOT NULL DEFAULT '',
    tagline       VARCHAR(256) NOT NULL DEFAULT '',
    avatar        VARCHAR(256) NOT NULL DEFAULT '',
    greeting      TEXT,
    system_prompt TEXT,
    sort_order    INT          NOT NULL DEFAULT 0,
    enabled       TINYINT(1)   NOT NULL DEFAULT 1,
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS messages (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    session_id   VARCHAR(64)  NOT NULL,
    character_id VARCHAR(32)  NOT NULL,
    role         VARCHAR(16)  NOT NULL,
    content      TEXT         NOT NULL,
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_session (session_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
