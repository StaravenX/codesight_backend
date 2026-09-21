// benchmark/k6/singleflight_benchmark.js
// 验证 MultiLevelCacheTemplate 中 SingleFlight 单飞锁在高并发冷启动/热点失效瞬间防击穿表现

import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL, getRandomHeaders } from './common.js';

// 指定一个冷数据文章 ID（确保压测发起时 L1/L2 无缓存）
const TARGET_COLD_ARTICLE_ID = '2100000000000099999';

export const options = {
    scenarios: {
        // 瞬时并发突发：80 个并发 VU 在同一瞬间发起请求
        burst_breakdown_attack: {
            executor: 'per-vu-iterations',
            vus: 80,
            iterations: 1,
            maxDuration: '10s',
        },
    },
    thresholds: {
        http_req_failed: ['rate<0.001'],
        http_req_duration: ['p(95)<100', 'p(99)<200'],
    },
};

export default function () {
    const url = `${BASE_URL}/api/v1/articles/detail/${TARGET_COLD_ARTICLE_ID}`;
    const headers = getRandomHeaders();

    const res = http.get(url, { headers });

    check(res, {
        'status is 200': (r) => r.status === 200,
        'code is SUCCESS': (r) => {
            try {
                return r.json().code === 'SUCCESS';
            } catch (e) {
                return false;
            }
        },
    });
}
