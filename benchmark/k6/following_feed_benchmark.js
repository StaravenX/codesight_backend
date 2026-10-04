import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL, DEFAULT_AUTH_TOKEN, getRandomHeaders } from './common.js';

export const options = {
    scenarios: {
        following_feed_stream: {
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
        http_req_failed: ['rate<0.01'],
        http_req_duration: ['p(95)<200', 'p(99)<300'],
    },
};

export default function () {
    const url = `${BASE_URL}/api/v1/articles/feed?sortType=FOLLOWING&size=10`;
    const headers = getRandomHeaders(DEFAULT_AUTH_TOKEN);

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
