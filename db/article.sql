-- 1. 一级技术分类表
CREATE TABLE IF NOT EXISTS categories (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    name VARCHAR(64) NOT NULL COMMENT '分类名称，如：后端、前端、人工智能',
    slug VARCHAR(64) NOT NULL COMMENT '英文路由标识，如：backend, frontend, ai',
    sort_order INT NOT NULL DEFAULT 0 COMMENT '排序权重（升序）',
    icon_url VARCHAR(512) NULL COMMENT '分类图标 URL',
    PRIMARY KEY (id),
    UNIQUE KEY uk_categories_slug (slug),
    UNIQUE KEY uk_categories_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文章一级技术分类表';

-- 2. 二级技术标签表
CREATE TABLE IF NOT EXISTS tags (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    name VARCHAR(64) NOT NULL COMMENT '标签名称，如：Java, Docker, Vue.js, Python',
    icon_url VARCHAR(512) NULL COMMENT '标签图标 URL',
    article_count BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '该标签下文章聚合计数',
    PRIMARY KEY (id),
    UNIQUE KEY uk_tags_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文章二级技术标签表';

-- 3. 分类与标签多对多关联表
CREATE TABLE IF NOT EXISTS category_tag_rel (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    category_id BIGINT UNSIGNED NOT NULL COMMENT '分类 ID',
    tag_id BIGINT UNSIGNED NOT NULL COMMENT '标签 ID',
    PRIMARY KEY (id),
    UNIQUE KEY uk_category_tag (category_id, tag_id),
    KEY ix_tag_category (tag_id, category_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='分类与标签多对多关联表';

-- 4. 文章核心主表
CREATE TABLE IF NOT EXISTS articles (
    id BIGINT UNSIGNED NOT NULL COMMENT '文章全局分布式 ID（雪花算法生成）',
    author_id BIGINT UNSIGNED NOT NULL COMMENT '创作者用户 ID',
    category_id BIGINT UNSIGNED NOT NULL COMMENT '所属一级技术分类 ID',
    title VARCHAR(256) NOT NULL COMMENT '文章主标题',
    summary VARCHAR(512) NOT NULL COMMENT '文章摘要',
    cover_url VARCHAR(512) NULL COMMENT '文章列表封面图 URL',
    content_md LONGTEXT NULL COMMENT 'Markdown 格式正文字符串',
    word_count INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '正文字数统计',
    view_count BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '阅读量统计',
    like_count BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '点赞量统计',
    comment_count BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '评论量统计',
    favorite_count BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '收藏量统计',
    is_top TINYINT(1) NOT NULL DEFAULT 0 COMMENT '创作者主页是否置顶：1=置顶，0=正常',
    visible VARCHAR(32) NOT NULL DEFAULT 'public' COMMENT '可见性：public=公开，private=仅自己可见',
    status VARCHAR(16) NOT NULL DEFAULT 'draft' COMMENT '文章状态：draft=草稿，published=已发布，offline=已下架，deleted=已删除',
    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    publish_time TIMESTAMP NULL DEFAULT NULL COMMENT '首次公开发布时间',
    PRIMARY KEY (id),
    KEY ix_articles_category_pub (category_id, status, publish_time),
    KEY ix_articles_author_pub (author_id, status, is_top, publish_time),
    KEY ix_articles_status_pub (status, publish_time),
    CONSTRAINT fk_articles_author FOREIGN KEY (author_id) REFERENCES users(id),
    CONSTRAINT fk_articles_category FOREIGN KEY (category_id) REFERENCES categories(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文章核心主表';

-- 5. 文章与标签多对多关联表
CREATE TABLE IF NOT EXISTS article_tag_rel (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    article_id BIGINT UNSIGNED NOT NULL COMMENT '文章 ID',
    tag_id BIGINT UNSIGNED NOT NULL COMMENT '标签 ID',
    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_article_tag (article_id, tag_id),
    KEY ix_tag_article (tag_id, article_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文章与标签多对多关联表';

-- 初始化常用技术频道
INSERT INTO categories (id, name, slug, sort_order) VALUES
(1, '后端', 'backend', 1),
(2, '前端', 'frontend', 2),
(3, '人工智能', 'ai', 3),
(4, 'Android', 'android', 4),
(5, 'iOS', 'ios', 5),
(6, '开发工具', 'freebie', 6),
(7, '代码人生', 'career', 7),
(8, '阅读', 'article', 8)
ON DUPLICATE KEY UPDATE name=VALUES(name);

-- 初始化常见技术标签
INSERT INTO tags (id, name) VALUES
(1, 'Java'),
(2, 'Spring Boot'),
(3, 'MySQL'),
(4, 'Redis'),
(5, 'Vue.js'),
(6, 'React'),
(7, 'TypeScript'),
(8, 'Docker'),
(9, 'Python'),
(10, 'Git'),
(11, 'LLM')
ON DUPLICATE KEY UPDATE name=VALUES(name);

-- 初始化分类与标签挂载关系
INSERT INTO category_tag_rel (category_id, tag_id) VALUES
-- 后端分类
(1, 1), -- Java
(1, 2), -- Spring Boot
(1, 3), -- MySQL
(1, 4), -- Redis
(1, 7), -- TypeScript (后端/NestJS)
(1, 8), -- Docker
(1, 9), -- Python (后端)
(1, 10), -- Git
-- 前端分类
(2, 5), -- Vue.js
(2, 6), -- React
(2, 7), -- TypeScript (前端)
(2, 10), -- Git
-- 人工智能
(3, 9), -- Python (AI)
(3, 11), -- LLM
-- 开发工具
(6, 8), -- Docker (开发工具)
(6, 10) -- Git (开发工具)
ON DUPLICATE KEY UPDATE category_id=VALUES(category_id);