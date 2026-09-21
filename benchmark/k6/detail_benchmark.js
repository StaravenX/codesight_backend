// benchmark/k6/detail_benchmark.js
// 文章详情接口压测（验证 L1 Caffeine + L2 Redis 多级缓存吞吐量与极低延迟）

import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL, BASE_ARTICLE_ID, getRandomHeaders } from './common.js';

export const options = {
    scenarios: {
        hot_detail_cache: {
            executor: 'ramping-vus',
            startVUs: 10,
            stages: [
                { duration: '5s', target: 300 },
                { duration: '20s', target: 300 },
            ],
            gracefulRampDown: '0s',
        },
    },
    thresholds: {
        http_req_failed: ['rate<0.001'],
        http_req_duration: ['p(95)<250', 'p(99)<300'],
    },
};

export default function () {
    const url = `${BASE_URL}/api/v1/articles/detail/${BASE_ARTICLE_ID}`;
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
