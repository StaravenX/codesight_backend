// benchmark/k6/search_benchmark.js
// 关键词全文检索压测（验证 Elasticsearch 多字段权重与 function_score 排序性能）

import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL, getRandomHeaders } from './common.js';

const KEYWORDS = ['Spring', 'Redis', 'Kafka', 'MySQL', '架构', '高并发', '性能', '分布式'];

export const options = {
    scenarios: {
        search_query_stream: {
            executor: 'ramping-vus',
            startVUs: 10,
            stages: [
                { duration: '5s', target: 80 },
                { duration: '20s', target: 80 },
            ],
            gracefulRampDown: '0s',
        },
    },
    thresholds: {
        http_req_failed: ['rate<0.01'],
        http_req_duration: ['p(95)<150', 'p(99)<300'],
    },
};

export default function () {
    const q = KEYWORDS[__ITER % KEYWORDS.length];
    const url = `${BASE_URL}/api/v1/search?q=${encodeURIComponent(q)}&size=10`;
    const headers = getRandomHeaders();

    const res = http.get(url, { headers });

    check(res, {
        'status is 200': (r) => r.status === 200,
        'has search data': (r) => {
            try {
                const body = r.json();
                return body.code === 'SUCCESS';
            } catch (e) {
                return false;
            }
        },
    });
}
