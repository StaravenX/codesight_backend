"""
轻量级 OpenAI 兼容 Mock 服务 (Python 内置 http.server，零第三方依赖)
用于本地压测时模拟 Chat / Embedding 模型接口，并支持延时注入以验证 300ms 熔断降级。
端口：8090
端点：
- POST /embeddings：返回 1536 维向量。若请求体包含 '__DELAY__' 或带延迟开关，则休眠 1000ms 触发 300ms 熔断降级。
- POST /chat/completions：返回通用技术答复。
"""

import json
import time
from http.server import HTTPServer, BaseHTTPRequestHandler

DIMS = 1536
MOCK_VECTOR = [0.01] * DIMS
GLOBAL_DELAY = 0.0

class MockAiHandler(BaseHTTPRequestHandler):
    def do_POST(self):
        global GLOBAL_DELAY
        if '/mock/delay' in self.path:
            from urllib.parse import urlparse, parse_qs
            qs = parse_qs(urlparse(self.path).query)
            if 'delay' in qs:
                GLOBAL_DELAY = float(qs['delay'][0])
            self.send_response(200)
            self.end_headers()
            self.wfile.write(json.dumps({"delay": GLOBAL_DELAY}).encode('utf-8'))
            return

        content_len = int(self.headers.get('Content-Length', 0))
        body = self.rfile.read(content_len).decode('utf-8', errors='ignore')

        delay = GLOBAL_DELAY
        if ('timeout' in body.lower()) or (self.headers.get('X-Mock-Delay') == 'true'):
            delay = 1.0

        if delay > 0:
            time.sleep(delay)

        if '/embeddings' in self.path:
            resp_data = {
                "object": "list",
                "data": [
                    {
                        "object": "embedding",
                        "index": 0,
                        "embedding": MOCK_VECTOR
                    }
                ],
                "model": "text-embedding-3-small",
                "usage": {"prompt_tokens": 5, "total_tokens": 5}
            }
        else:
            resp_data = {
                "id": "chatcmpl-mock-123",
                "object": "chat.completion",
                "created": int(time.time()),
                "model": "gpt-4o-mini",
                "choices": [{
                    "index": 0,
                    "message": {"role": "assistant", "content": "这是 Mock 返回的技术解析。"},
                    "finish_reason": "stop"
                }]
            }

        resp_bytes = json.dumps(resp_data).encode('utf-8')
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(resp_bytes)))
        self.end_headers()
        self.wfile.write(resp_bytes)

    def log_message(self, format, *args):
        # 静默日志，避免刷屏
        pass

def run_server(port=8090):
    server_address = ('', port)
    httpd = HTTPServer(server_address, MockAiHandler)
    print(f"[*] Mock AI Server 已启动，监听端口 {port}...")
    httpd.serve_forever()

if __name__ == '__main__':
    run_server()
