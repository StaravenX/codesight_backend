import urllib.request
import json
import time
import subprocess
import random

BASE_URL = "http://127.0.0.1:8080"
BASE_AID = 2100000000000000000

def get_headers():
    return {
        "X-Forwarded-For": f"101.{time.time_ns() % 250}.{time.time_ns() % 250}.{time.time_ns() % 250}"
    }

def request_article(article_id):
    url = f"{BASE_URL}/api/v1/articles/detail/{article_id}"
    req = urllib.request.Request(url, headers=get_headers())
    t0 = time.perf_counter()
    try:
        with urllib.request.urlopen(req) as res:
            t1 = time.perf_counter()
            return (t1 - t0) * 1000, res.status
    except Exception as e:
        return 0.0, 500

def prepare_l2(start_idx, count=50):
    lua_sds = "for i, k in ipairs(KEYS) do redis.call('SET', k, string.rep(string.char(0), 16)) end return true"
    keys = [f"cnt:v1:article:{BASE_AID + start_idx + i}" for i in range(count)]
    cmd = ['docker', 'exec', 'redis', 'redis-cli', 'EVAL', lua_sds, str(count)] + keys
    subprocess.run(cmd, stdout=subprocess.DEVNULL)

    sql = f"""
    SELECT a.id, a.title, a.summary, a.content_md, a.word_count, a.read_time_minutes, a.category_id, a.author_id, 
           UNIX_TIMESTAMP(a.publish_time)*1000, UNIX_TIMESTAMP(a.updated_time)*1000,
           IFNULL(GROUP_CONCAT(CONCAT(t.id, ':', t.name) SEPARATOR ';'), '') as tag_info
    FROM articles a
    LEFT JOIN article_tag_rel r ON a.id = r.article_id
    LEFT JOIN tags t ON r.tag_id = t.id
    WHERE a.id BETWEEN {BASE_AID + start_idx} AND {BASE_AID + start_idx + count - 1}
    GROUP BY a.id;
    """
    proc = subprocess.Popen(
        ["docker", "exec", "-i", "mysql", "mysql", "-uroot", "-p123456", "codesight", "-N", "-e", sql],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        encoding="utf-8"
    )
    stdout, stderr = proc.communicate()
    lines = stdout.strip().split("\n")
    
    redis_cmds = []
    for line in lines:
        if not line: continue
        parts = line.split("\t")
        aid = int(parts[0])
        title = parts[1]
        summary = parts[2]
        content_md = parts[3]
        word_count = int(parts[4])
        read_time_minutes = int(parts[5])
        category_id = int(parts[6])
        author_id = int(parts[7])
        pub_time = int(float(parts[8])) if parts[8] and parts[8] != 'NULL' else 0
        up_time = int(float(parts[9])) if parts[9] and parts[9] != 'NULL' else 0
        tag_str = parts[10] if len(parts) > 10 else ""
        
        tags = []
        if tag_str:
            for item in tag_str.split(";"):
                if ":" in item:
                    tid, tname = item.split(":", 1)
                    tags.append({"id": int(tid), "name": tname})
                    
        toc = [{"id": "heading-1", "title": title, "level": 1}]
        data = {
            "id": aid,
            "title": title,
            "summary": summary,
            "contentMd": content_md,
            "wordCount": word_count,
            "readTimeMinutes": read_time_minutes,
            "toc": toc,
            "categoryId": category_id,
            "tags": tags,
            "authorId": author_id,
            "publishTime": pub_time,
            "updatedTime": up_time
        }
        val_json = json.dumps(data, ensure_ascii=False)
        redis_cmds.append(f"SET article:detail:static:{aid} '{val_json}' EX 86400")
        
    p_red = subprocess.Popen(
        ["docker", "exec", "-i", "redis", "redis-cli"],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        encoding="utf-8"
    )
    p_red.communicate("\n".join(redis_cmds) + "\n")

def prepare_l3(start_idx, count=50):
    del_cmds = []
    for i in range(count):
        del_cmds.append(f"DEL article:detail:static:{BASE_AID + start_idx + i}")
        del_cmds.append(f"DEL cnt:v1:article:{BASE_AID + start_idx + i}")
    p_red = subprocess.Popen(
        ["docker", "exec", "-i", "redis", "redis-cli"],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        encoding="utf-8"
    )
    p_red.communicate("\n".join(del_cmds) + "\n")

def main():
    print("=" * 66)
    print("   CodeSight 多级缓存层级性能对比测试 (带 100 次 JIT 稳态预热)")
    print("=" * 66)

    warmup_aid = BASE_AID + 99001
    print("[1/4] 执行 100 次 Warmup 预热请求...")
    for _ in range(100):
        request_article(warmup_aid)
    print("      ✓ JVM JIT 预热完成，系统进入稳态运行阶段。\n")

    l2_start = random.randint(100, 300) * 100
    l3_start = random.randint(500, 800) * 100
    prepare_l2(l2_start, 50)
    prepare_l3(l3_start, 50)

    print("[2/4] 测试 L1 本地缓存命中 (Caffeine JVM 堆内存)...")
    l1_times = []
    for _ in range(50):
        t, s = request_article(warmup_aid)
        if s == 200: l1_times.append(t)
    print(f"      ✓ 50 样本采集完毕 (平均: {sum(l1_times)/len(l1_times):.2f}ms)\n")

    print("[3/4] 测试 L2 分布式缓存命中 (Redis 8.8 网络内存)...")
    l2_times = []
    for i in range(50):
        aid = BASE_AID + l2_start + i
        t, s = request_article(aid)
        if s == 200: l2_times.append(t)
    print(f"      ✓ 50 样本采集完毕 (平均: {sum(l2_times)/len(l2_times):.2f}ms)\n")

    print("[4/4] 测试 L3 数据库直接穿透 (MySQL 8.0 磁盘存储)...")
    request_article(BASE_AID + l3_start - 1)
    l3_times = []
    for i in range(50):
        aid = BASE_AID + l3_start + i
        t, s = request_article(aid)
        if s == 200: l3_times.append(t)
    print(f"      ✓ 50 样本采集完毕 (平均: {sum(l3_times)/len(l3_times):.2f}ms)\n")

    def stats(arr):
        if not arr: return 0.0, 0.0
        s = sorted(arr)
        return sum(arr) / len(arr), s[int(len(s) * 0.95)]

    l1_avg, l1_p95 = stats(l1_times)
    l2_avg, l2_p95 = stats(l2_times)
    l3_avg, l3_p95 = stats(l3_times)

    print("=" * 66)
    print("   多级缓存层级性能实测基准 (消除冷启动后的稳态对比)")
    print("=" * 66)
    print(f"| 缓存命中层级          | 存储介质               | 平均延迟    | P95 延迟    |")
    print(f"|:----------------------|:-----------------------|:------------|:------------|")
    print(f"| **L1 本地缓存**       | Caffeine (JVM 堆内存)  | **{l1_avg:.2f}ms**  | **{l1_p95:.2f}ms**  |")
    print(f"| **L2 分布式缓存**     | Redis 8.8 (网络内存)   | **{l2_avg:.2f}ms**  | **{l2_p95:.2f}ms**  |")
    print(f"| **L3 数据库直接穿透** | MySQL 8.0 (持久化磁盘) | **{l3_avg:.2f}ms** | **{l3_p95:.2f}ms** |")
    print("=" * 66)

if __name__ == "__main__":
    main()
