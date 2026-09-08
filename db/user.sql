-- 1. 用户表
CREATE TABLE IF NOT EXISTS users (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    phone VARCHAR(32) NULL,
    email VARCHAR(128) NULL,
    password_hash VARCHAR(128) NULL,
    nickname VARCHAR(64) NOT NULL,
    avatar TEXT NULL,
    bio VARCHAR(256) NULL COMMENT '个人介绍',
    cs_id VARCHAR(64) NULL COMMENT 'CodeSight 极客号',
    job_direction VARCHAR(64) NULL COMMENT '职业方向',
    job_title VARCHAR(64) NULL COMMENT '职位',
    company VARCHAR(64) NULL COMMENT '就职公司',
    work_date VARCHAR(16) NULL COMMENT '开始工作时间（年月）',
    home_page VARCHAR(128) NULL COMMENT '个人主页',
    interested_domains JSON NULL COMMENT '感兴趣的技术领域标签',
    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_users_phone (phone),
    UNIQUE KEY uk_users_email (email),
    UNIQUE KEY uk_users_cs_id (cs_id)
);