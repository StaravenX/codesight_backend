// benchmark/k6/card_benchmark.js
// 创作者名片接口压测（验证 Java 21 虚拟线程三路并行聚合 + 16B SDS 实时计数吞吐）

import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL, BIG_V_AUTHOR_ID, getRandomHeaders } from './common.js';

export const options = {
    scenarios: {
        author_card_stream: {
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
        http_req_duration: ['p(95)<100', 'p(99)<150'],
    },
};

export default function () {
    // 轮流请求大 V 作者与普通作者的名片
    const authorId = (__ITER % 2 === 0) ? BIG_V_AUTHOR_ID : ((__VU % 100) + 1);
    const url = `${BASE_URL}/api/v1/profile/authors/${authorId}/`;
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
