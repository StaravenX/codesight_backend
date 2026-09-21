// benchmark/k6/like_benchmark.js
// 文章点赞写路径压测（验证 4KB 分片位图原子翻转 + Kafka 异步削峰写吞吐）

import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL, BASE_ARTICLE_ID, getRandomHeaders } from './common.js';

export const options = {
    scenarios: {
        like_toggle_stream: {
            executor: 'ramping-vus',
            startVUs: 10,
            stages: [
                { duration: '5s', target: 200 },
                { duration: '20s', target: 200 },
            ],
            gracefulRampDown: '0s',
        },
    },
    thresholds: {
        http_req_failed: ['rate<0.01'],
        http_req_duration: ['p(95)<250', 'p(99)<300'],
    },
};

export function setup() {
    // 登录获取合法测试 Token
    const loginUrl = `${BASE_URL}/api/v1/auth/login/password`;
    const payload = JSON.stringify({
        type: 'phone',
        identifier: '13800000001',
        password: '12345678'
    });
    const headers = { 'Content-Type': 'application/json' };
    const res = http.post(loginUrl, payload, { headers });
    
    try {
        const body = res.json();
        if (body.code === 'SUCCESS' && body.data && body.data.token) {
            return { token: body.data.token.accessToken };
        }
    } catch (e) {
        console.error('Setup 登录获取 Token 失败:', res.body);
    }
    return { token: null };
}

export default function (data) {
    // 轮流对不同文章交替点赞与取消点赞
    const isLike = (__ITER % 2 === 0);
    const articleOffset = (__ITER % 50);
    const articleId = `210000000000000${String(articleOffset + 1).padStart(4, '0')}`;
    const url = `${BASE_URL}/api/v1/articles/${articleId}/like?isLike=${isLike}`;
    
    const headers = getRandomHeaders(data.token);
    const res = http.post(url, null, { headers });

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
