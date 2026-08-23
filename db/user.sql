-- 1. 用户表
CREATE TABLE IF NOT EXISTS users (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    phone VARCHAR(32) NULL,
    email VARCHAR(128) NULL,
    password_hash VARCHAR(128) NULL,
    nickname VARCHAR(64) NOT NULL,
    avatar TEXT NULL,
    bio VARCHAR(512) NULL COMMENT '个人签名',
    cs_id VARCHAR(64) NULL COMMENT 'CodeSight 极客号',
    gender VARCHAR(16) NULL,
    birthday DATE NULL,
    company VARCHAR(128) NULL COMMENT '就职公司',
    job_title VARCHAR(64) NULL COMMENT '职位',
    school VARCHAR(128) NULL COMMENT '院校',
    interested_domains JSON NULL COMMENT '感兴趣的技术领域，如 ["Java","Spring","计算机网络","后端"]',
    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_users_phone (phone),
    UNIQUE KEY uk_users_email (email),
    UNIQUE KEY uk_users_cs_id (cs_id)
);