// benchmark/k6/common.js
// 统一封装 k6 基础配置、随机 IP 伪造与请求工具

export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

// 基础常量配置
export const BASE_ARTICLE_ID = '2100000000000000001';
export const BIG_V_AUTHOR_ID = 100;
export const NORMAL_AUTHOR_ID = 1;
// 默认测试 Token（可通过环境变量 AUTH_TOKEN 传入；若失效需自行生成）
export const DEFAULT_AUTH_TOKEN = __ENV.AUTH_TOKEN || 'eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCIsImtpZCI6ImNvZGVzaWdodC1rZXkifQ.eyJpc3MiOiJjb2Rlc2lnaHQiLCJzdWIiOiIxIiwiZXhwIjoxNzkzNzczMzU1LCJpYXQiOjE3OTExODEzNTUsImp0aSI6ImY5MDJlMTg3LTZlMzYtNGI4Yy1hZDhiLWJlYWFkMDQyZDdmMiIsInRva2VuX3R5cGUiOiJhY2Nlc3MiLCJuaWNrbmFtZSI6InVzZXJfMSJ9.w8-7iPJTyIDgF7XZAP-I1JbKc-UQW7C9LMNpSJV_FsODnJgqpagpkySHMzTl8-TlnF3IuVC0FdBZRPSWvOyLvgsXjLpJ00Kvr3BViCWaATITQQkJvdbAioHs3AUgphWQZelLU3ORBLmg3lPhzlhNrX40hXfalQKGWXgLVV_dzqRDfO-yOwVSMXKQAGIMcxe6ik25jdh-zGRN173G1uDY5578oZpaK15zkaXkQviGfdOPNg5NCtpcZzYR62yTPX97zqpsp4oWg0XhEUzA6Lwsy36si4FfJJs2PL1aR2bT9WtIUzk7VWzPENK6ExV5BdpkEQqQz8uArn1xy989hRZoRg';

/**
 * 生成随机客户端请求头
 * @param {string} token 可选的用户 JWT
 */
export function getRandomHeaders(token = null) {
    // 构造分布在 10.0.0.0/8、172.16.0.0/12、192.168.0.0/16 之外的公网 IP 模拟真实网民
    const ip1 = Math.floor(Math.random() * 190) + 11;
    const ip2 = Math.floor(Math.random() * 255);
    const ip3 = (__VU % 250);
    const ip4 = (__ITER % 250) + 1;
    const ip = `${ip1}.${ip2}.${ip3}.${ip4}`;

    const headers = {
        'Content-Type': 'application/json',
        'X-Forwarded-For': ip,
        'X-Real-IP': ip
    };

    if (token) {
        headers['Authorization'] = `Bearer ${token}`;
    }

    return headers;
}

/**
 * 获取随机文章 ID（范围在 2100000000000000001 ~ 2100000000000003000 热点推荐池区间）
 */
export function getRandomHotArticleId() {
    const offset = Math.floor(Math.random() * 3000) + 1;
    return `210000000000000${String(offset).padStart(4, '0')}`;
}

/**
 * 获取全量冷数据文章 ID（范围在 3001 ~ 100000 区间）
 */
export function getRandomColdArticleId() {
    const offset = Math.floor(Math.random() * 90000) + 5000;
    return `21000000000000${String(offset).padStart(5, '0')}`;
}
