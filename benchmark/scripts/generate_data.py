"""
codesight_backend 性能压测基准数据生成与预热脚本
生成规模：
- 10,000 用户（ID: 1 ~ 10000，密码统一为 12345678）
- 100,000 篇已发布公开文章（ID: 2100000000000000001 ~ 2100000000000100000）
- 200,000 条文章-标签关联记录
- 6,000+ 社交关注关系（用户 100 构造为 >5500 粉丝的大 V，用户 1 关注用户 100 及若干普通作者）
- Redis 预热：feed:recommend:pool (Top-3000), feed:big_v:authors
"""

import sys
import os
import random
import time
import subprocess
import json

USER_COUNT = 10000
ARTICLE_COUNT = 100000
TOP_RECOMMEND_COUNT = 3000
BIG_V_USER_ID = 100
BIG_V_FOLLOWERS = 6000

PASSWORD_HASH = "$2a$12$6N5Dl6Y7RhQfb3TZtTId0ekl8t2Jy.6hgYehlU5oaDQdOXNtWtOqW"

CATEGORY_TAG_MAP = {
    1: [1, 2, 3, 4, 7, 8, 9, 10],
    2: [5, 6, 7, 10],
    3: [9, 11],
    4: [1, 8],
    5: [8, 10],
    6: [8, 10],
    7: [1, 9],
    8: [1, 3]
}

TECH_KEYWORDS = [
    "Spring Boot 3.3", "Redis 8.8", "Kafka 4.2", "MySQL 8.0", "Elasticsearch 9.2",
    "Java 21 虚拟线程", "Redisson 分布式锁", "Caffeine 本地缓存", "SingleFlight",
    "4KB 分片位图", "16B SDS 计数快照", "推拉结合信息流", "RRF 混合检索",
    "Spring AI RAG", "Docker Compose", "OAuth2 JWT", "BCrypt", "Knife4j"
]

def run_mysql_sql(sql_content):
    proc = subprocess.Popen(
        ["docker", "exec", "-i", "mysql", "mysql", "-uroot", "-p123456", "codesight", "--default-character-set=utf8mb4"],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        encoding="utf-8"
    )
    stdout, stderr = proc.communicate(input=sql_content)
    if proc.returncode != 0 and "Warning" not in stderr:
        print(f"[MySQL Error]: {stderr}", file=sys.stderr)
        raise RuntimeError(f"MySQL execution failed: {stderr}")
    return stdout

def run_redis_cmd(cmd_list):
    proc = subprocess.Popen(
        ["docker", "exec", "-i", "redis", "redis-cli"],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        encoding="utf-8"
    )
    commands = "\n".join(cmd_list) + "\n"
    stdout, stderr = proc.communicate(input=commands)
    return stdout

def generate_users():
    print(f"[*] 开始生成 {USER_COUNT} 名用户...")
    batch_size = 1000
    total_batches = USER_COUNT // batch_size
    
    run_mysql_sql("SET FOREIGN_KEY_CHECKS = 0; TRUNCATE TABLE users;")
    
    for b in range(total_batches):
        start_id = b * batch_size + 1
        end_id = start_id + batch_size
        values = []
        for uid in range(start_id, end_id):
            phone = f"138{uid:08d}"
            email = f"user_{uid}@codesight.com"
            nickname = f"DevUser_{uid}"
            cs_id = f"geek_{uid:08d}"
            values.append(
                f"({uid}, '{phone}', '{email}', '{PASSWORD_HASH}', '{nickname}', 'default-avatar.png', "
                f"'热爱技术的高并发工程师 #{uid}', '{cs_id}', '后端研发', '资深专家', 'CodeSight Inc', '2020-07', "
                f"'https://github.com/user{uid}', '[\"Java\", \"Go\", \"AI\"]', NOW(), NOW())"
            )
        sql = "INSERT INTO users (id, phone, email, password_hash, nickname, avatar, bio, cs_id, job_direction, job_title, company, work_date, home_page, interested_domains, created_time, updated_time) VALUES\n" + ",\n".join(values) + ";"
        run_mysql_sql(sql)
        print(f"    - 已写入用户批次 {b + 1}/{total_batches} ({end_id - 1} 条)")
    print(f"[+] 用户数据生成完毕！\n")

def generate_articles():
    print(f"[*] 开始生成 {ARTICLE_COUNT} 篇已发布文章与标签关联...")
    batch_size = 2000
    total_batches = ARTICLE_COUNT // batch_size
    base_article_id = 2100000000000000000
    
    run_mysql_sql("SET FOREIGN_KEY_CHECKS = 0; TRUNCATE TABLE articles; TRUNCATE TABLE article_tag_rel;")
    
    start_time = time.time()
    for b in range(total_batches):
        start_idx = b * batch_size + 1
        end_idx = start_idx + batch_size
        
        article_rows = []
        rel_rows = []
        
        for i in range(start_idx, end_idx):
            aid = base_article_id + i
            author_id = (i % USER_COUNT) + 1
            cat_id = (i % 8) + 1
            kw1 = TECH_KEYWORDS[i % len(TECH_KEYWORDS)]
            kw2 = TECH_KEYWORDS[(i * 3 + 1) % len(TECH_KEYWORDS)]
            
            title = f"深入理解 {kw1} 架构设计与 {kw2} 最佳实践 (第{i}篇)"
            summary = f"本文全面剖析 {kw1} 在千万级高并发技术社区下的核心设计理念，结合 {kw2} 给出生产环境落地的避坑指南与调优方案。"
            content = f"# {title}\\n\\n## 一、背景介绍\\n在大型分布式系统中，{kw1} 承担着不可替代的关键职责。\\n\\n## 二、核心实现原理\\n通过结合 {kw2}，系统在保证数据最终一致性的同时大幅降低延迟。\\n\\n## 三、性能调优经验\\n注意连接池配置与垃圾回收调优。"
            
            word_count = random.randint(600, 3000)
            read_time = max(1, word_count // 400)
            
            toc = [
                {"id": "heading-1", "title": title, "level": 1},
                {"id": "heading-2", "title": "一、背景介绍", "level": 2},
                {"id": "heading-3", "title": "二、核心实现原理", "level": 2},
                {"id": "heading-4", "title": "三、性能调优经验", "level": 2}
            ]
            toc_json = json.dumps(toc, ensure_ascii=False).replace("'", "''")
            
            if i <= TOP_RECOMMEND_COUNT:
                rank_score = round(100.0 - (i / TOP_RECOMMEND_COUNT) * 80.0, 2)
                like_count = random.randint(100, 500)
                view_count = random.randint(1000, 10000)
            else:
                rank_score = round(random.uniform(0.1, 15.0), 2)
                like_count = random.randint(0, 50)
                view_count = random.randint(10, 500)
            
            article_rows.append(
                f"({aid}, {author_id}, {cat_id}, '{title}', '{summary}', NULL, '{content}', "
                f"{word_count}, {read_time}, '{toc_json}', {view_count}, {like_count}, 0, 0, "
                f"{rank_score}, 0, 'public', 'published', NOW(), NOW(), NOW())"
            )
            
            avail_tags = CATEGORY_TAG_MAP.get(cat_id, [1])
            tags_chosen = random.sample(avail_tags, min(2, len(avail_tags)))
            for tid in tags_chosen:
                rel_rows.append(f"({aid}, {tid}, NOW())")
        
        sql_art = "INSERT INTO articles (id, author_id, category_id, title, summary, cover_url, content_md, word_count, read_time_minutes, toc_json, view_count, like_count, comment_count, favorite_count, rank_score, is_top, visible, status, created_time, updated_time, publish_time) VALUES\n" + ",\n".join(article_rows) + ";"
        run_mysql_sql(sql_art)
        
        sql_rel = "INSERT INTO article_tag_rel (article_id, tag_id, created_time) VALUES\n" + ",\n".join(rel_rows) + ";"
        run_mysql_sql(sql_rel)
        
        if (b + 1) % 5 == 0 or b == total_batches - 1:
            elapsed = time.time() - start_time
            print(f"    - 已写入文章批次 {b + 1}/{total_batches} ({end_idx - 1} 篇, 耗时 {elapsed:.1f}s)")
            
    print(f"[+] 100,000 篇已发布文章与标签关系写入完毕！\n")

def generate_relations():
    print(f"[*] 开始生成关注与粉丝关系（构建大 V 用户 {BIG_V_USER_ID} 的 {BIG_V_FOLLOWERS} 粉丝）...")
    run_mysql_sql("SET FOREIGN_KEY_CHECKS = 0; TRUNCATE TABLE user_following; TRUNCATE TABLE user_follower;")
    
    batch_size = 2000
    total_batches = BIG_V_FOLLOWERS // batch_size
    rel_id = 1
    
    for b in range(total_batches):
        start_u = b * batch_size + 1
        end_u = start_u + batch_size
        f_rows = []
        r_rows = []
        for uid in range(start_u, end_u):
            if uid == BIG_V_USER_ID:
                continue
            f_rows.append(f"({rel_id}, {uid}, {BIG_V_USER_ID}, NOW())")
            r_rows.append(f"({rel_id}, {BIG_V_USER_ID}, {uid}, NOW())")
            rel_id += 1
            
        sql_f = "INSERT INTO user_following (id, from_user_id, to_user_id, created_time) VALUES\n" + ",\n".join(f_rows) + ";"
        sql_r = "INSERT INTO user_follower (id, to_user_id, from_user_id, created_time) VALUES\n" + ",\n".join(r_rows) + ";"
        run_mysql_sql(sql_f)
        run_mysql_sql(sql_r)
        
    extra_f = []
    extra_r = []
    for target in range(2, 31):
        extra_f.append(f"({rel_id}, 1, {target}, NOW())")
        extra_r.append(f"({rel_id}, {target}, 1, NOW())")
        rel_id += 1
    sql_ef = "INSERT INTO user_following (id, from_user_id, to_user_id, created_time) VALUES\n" + ",\n".join(extra_f) + " ON DUPLICATE KEY UPDATE from_user_id=from_user_id;"
    sql_er = "INSERT INTO user_follower (id, to_user_id, from_user_id, created_time) VALUES\n" + ",\n".join(extra_r) + " ON DUPLICATE KEY UPDATE to_user_id=to_user_id;"
    run_mysql_sql(sql_ef)
    run_mysql_sql(sql_er)
    
    run_mysql_sql("SET FOREIGN_KEY_CHECKS = 1;")
    print(f"[+] 关注与粉丝关系写入完毕！\n")

def warmup_redis():
    print("[*] 开始预热 Redis 推荐池与大 V 状态机...")
    base_article_id = 2100000000000000000
    
    redis_cmds = ["DEL feed:recommend:pool"]
    for i in range(1, TOP_RECOMMEND_COUNT + 1):
        aid = base_article_id + i
        score = round(100.0 - (i / TOP_RECOMMEND_COUNT) * 80.0, 2)
        redis_cmds.append(f"ZADD feed:recommend:pool {score} {aid}")
        
    redis_cmds.append("DEL feed:big_v:authors")
    redis_cmds.append(f"SADD feed:big_v:authors {BIG_V_USER_ID}")
    
    outbox_key = f"feed:outbox:{BIG_V_USER_ID}"
    redis_cmds.append(f"DEL {outbox_key}")
    now_ms = int(time.time() * 1000)
    for i in range(1, 11):
        aid = base_article_id + i * 100
        redis_cmds.append(f"ZADD {outbox_key} {now_ms - i * 60000} {aid}")
        
    run_redis_cmd(redis_cmds)
    print("[+] Redis 推荐池与大 V 状态机预热完毕！\n")

if __name__ == "__main__":
    t0 = time.time()
    generate_users()
    generate_articles()
    generate_relations()
    warmup_redis()
    total_time = time.time() - t0
    print(f"==========================================")
    print(f"  全部预置数据准备就绪！总耗时: {total_time:.2f} 秒")
    print(f"==========================================")
