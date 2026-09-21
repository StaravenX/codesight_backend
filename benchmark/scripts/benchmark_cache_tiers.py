import urllib.request
import json
import time

BASE_URL = "http://localhost:8080"

def get_headers():
    return {
        "X-Forwarded-For": f"101.{time.time_ns() % 250}.{time.time_ns() % 250}.{time.time_ns() % 250}"
    }

def request_article(article_id):
    url = f"{BASE_URL}/api/v1/articles/detail/{article_id}"
    req = urllib.request.Request(url, headers=get_headers())
    t0 = time.perf_counter()
    res = urllib.request.urlopen(req)
    t1 = time.perf_counter()
    return (t1 - t0) * 1000, res.status

def main():
    print("[*] 正在执行多级缓存层级性能对比测试 (L1 本地 vs L2 Redis vs L3 MySQL，每层 50 次采样)...")
    
    # 1. 测试 L2 Redis 缓存命中 (50 个样本)
    # 服务刚启动，Caffeine 堆内存为空；Redis 中已预热 2100000000000000001~050
    # 遍历请求 50 篇不同文章，各请求 1 次，全部触发 L1 未命中、L2 命中并回填 L1
    l2_times = []
    for i in range(1, 51):
        aid = 2100000000000000000 + i
        t, status = request_article(aid)
        if status == 200:
            l2_times.append(t)
            
    # 2. 测试 L3 MySQL 穿透 (50 个样本)
    # 请求未缓存过的冷文章集群 (70001 ~ 70050)，穿透至 MySQL 查询
    l3_times = []
    for i in range(1, 51):
        cold_id = 2100000000000070000 + i
        t, status = request_article(cold_id)
        if status == 200:
            l3_times.append(t)

    # 3. 测试 L1 本地缓存命中 (50 个样本)
    # 2100000000000000001 已经处于 L1 Caffeine 堆内存中，连续请求 50 次
    hot_id = 2100000000000000001
    l1_times = []
    for _ in range(50):
        t, status = request_article(hot_id)
        if status == 200:
            l1_times.append(t)
            
    l1_avg = sum(l1_times) / len(l1_times)
    l1_sorted = sorted(l1_times)
    l1_p95 = l1_sorted[int(len(l1_sorted) * 0.95)]
    
    l2_avg = sum(l2_times) / len(l2_times)
    l2_sorted = sorted(l2_times)
    l2_p95 = l2_sorted[int(len(l2_sorted) * 0.95)]
    
    l3_avg = sum(l3_times) / len(l3_times)
    l3_sorted = sorted(l3_times)
    l3_p95 = l3_sorted[int(len(l3_sorted) * 0.95)]
    
    print("\n--- 多级缓存层级访问实测结果 (50 样本平滑) ---")
    print(f"L1 本地缓存命中 (Caffeine) : 平均延迟 = {l1_avg:.2f}ms, P95 = {l1_p95:.2f}ms")
    print(f"L2 分布式缓存命中 (Redis)  : 平均延迟 = {l2_avg:.2f}ms, P95 = {l2_p95:.2f}ms")
    print(f"L3 数据库直接穿透 (MySQL)  : 平均延迟 = {l3_avg:.2f}ms, P95 = {l3_p95:.2f}ms")

if __name__ == "__main__":
    main()
