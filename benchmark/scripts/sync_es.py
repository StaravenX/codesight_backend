"""
将 MySQL 前 5,000 篇高质量文章同步到 Elasticsearch codesight_article_index
供全文检索与相关推荐压测使用。
"""

import json
import urllib.request
import time

ES_URL = "http://localhost:9200"
INDEX_NAME = "codesight_article_index"
DIMS = 1536
MOCK_VECTOR = [0.01] * DIMS

TECH_KEYWORDS = [
    "Spring Boot 3.3", "Redis 8.8", "Kafka 4.2", "MySQL 8.0", "Elasticsearch 9.2",
    "Java 21 虚拟线程", "Redisson 分布式锁", "Caffeine 本地缓存", "SingleFlight",
    "4KB 分片位图", "16B SDS 计数快照", "推拉结合信息流", "RRF 混合检索",
    "Spring AI RAG", "Docker Compose", "OAuth2 JWT", "BCrypt", "Knife4j"
]

def ensure_index():
    req = urllib.request.Request(f"{ES_URL}/{INDEX_NAME}", method="HEAD")
    try:
        urllib.request.urlopen(req)
        print(f"[*] ES 索引 {INDEX_NAME} 已存在，删除重建以刷新分词与结构...")
        del_req = urllib.request.Request(f"{ES_URL}/{INDEX_NAME}", method="DELETE")
        urllib.request.urlopen(del_req)
    except Exception:
        pass

    mapping = {
        "mappings": {
            "properties": {
                "article_id": {"type": "long"},
                "title": {"type": "text", "analyzer": "cjk"},
                "body": {"type": "text", "analyzer": "cjk"},
                "summary": {"type": "text", "analyzer": "cjk"},
                "tags": {"type": "keyword"},
                "author_id": {"type": "long"},
                "author_avatar": {"type": "keyword"},
                "author_nickname": {"type": "keyword"},
                "cover_url": {"type": "keyword"},
                "publish_time": {"type": "date"},
                "like_count": {"type": "integer"},
                "favorite_count": {"type": "integer"},
                "view_count": {"type": "integer"},
                "status": {"type": "keyword"},
                "article_vector": {
                    "type": "dense_vector",
                    "dims": DIMS,
                    "index": True,
                    "similarity": "cosine"
                }
            }
        }
    }
    
    put_req = urllib.request.Request(
        f"{ES_URL}/{INDEX_NAME}",
        data=json.dumps(mapping).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="PUT"
    )
    urllib.request.urlopen(put_req)
    print(f"[+] ES 索引 {INDEX_NAME} 创建完毕！")

def bulk_index():
    base_id = 2100000000000000000
    batch_size = 500
    total = 5000
    
    print(f"[*] 开始向 ES 批量导入 {total} 篇基准文章...")
    
    for start in range(1, total + 1, batch_size):
        end = min(start + batch_size, total + 1)
        bulk_lines = []
        for i in range(start, end):
            aid = base_id + i
            kw1 = TECH_KEYWORDS[i % len(TECH_KEYWORDS)]
            kw2 = TECH_KEYWORDS[(i * 3 + 1) % len(TECH_KEYWORDS)]
            title = f"深入理解 {kw1} 架构设计与 {kw2} 最佳实践 (第{i}篇)"
            summary = f"本文全面剖析 {kw1} 在千万级高并发技术社区下的核心设计理念，结合 {kw2} 给出生产环境落地的避坑指南与调优方案。"
            body = f"{title}。在大型分布式系统中，{kw1} 承担着关键职责。通过结合 {kw2}，系统在保证最终一致性的同时大幅降低延迟。注意连接池与GC调优。"
            
            doc = {
                "article_id": aid,
                "title": title,
                "summary": summary,
                "body": body,
                "tags": [kw1, kw2, "架构", "性能优化"],
                "author_id": (i % 100) + 1,
                "author_avatar": "default-avatar.png",
                "author_nickname": f"DevUser_{(i % 100) + 1}",
                "cover_url": "",
                "publish_time": int(time.time() * 1000),
                "like_count": 100 + (i % 300),
                "favorite_count": 50 + (i % 100),
                "view_count": 1000 + (i % 5000),
                "status": "published",
                "article_vector": MOCK_VECTOR
            }
            
            bulk_lines.append(json.dumps({"index": {"_index": INDEX_NAME, "_id": str(aid)}}))
            bulk_lines.append(json.dumps(doc))
            
        payload = "\n".join(bulk_lines) + "\n"
        req = urllib.request.Request(
            f"{ES_URL}/_bulk?refresh=true",
            data=payload.encode("utf-8"),
            headers={"Content-Type": "application/x-ndjson"},
            method="POST"
        )
        urllib.request.urlopen(req)
        print(f"    - 已导入 ES 文档 {end - 1}/{total}")
        
    print(f"[+] ES 数据导入与索引刷新完毕！")

if __name__ == "__main__":
    ensure_index()
    bulk_index()
