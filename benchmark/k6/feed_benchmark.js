// benchmark/k6/feed_benchmark.js
// 推荐信息流接口压测（验证 Redis ZSET Top-3000 候选池游标分页性能）

import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL, getRandomHeaders } from './common.js';

export const options = {
    scenarios: {
        recommend_feed_stream: {
            executor: 'ramping-vus',
            startVUs: 10,
            stages: [
                { duration: '5s', target: 150 },
                { duration: '20s', target: 150 },
            ],
            gracefulRampDown: '0s',
        },
    },
    thresholds: {
        http_req_failed: ['rate<0.001'],
        http_req_duration: ['p(95)<200', 'p(99)<250'],
    },
};

export default function () {
    const url = `${BASE_URL}/api/v1/articles/feed?sortType=RECOMMENDED&size=10`;
    const headers = getRandomHeaders();

    const res = http.get(url, { headers });

    check(res, {
        'status is 200': (r) => r.status === 200,
        'has items': (r) => {
            try {
                const body = r.json();
                return body.code === 'SUCCESS' && Array.isArray(body.data.items);
            } catch (e) {
                return false;
            }
        },
    });
}
