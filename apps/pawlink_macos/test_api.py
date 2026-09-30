"""Integration checks against a running PawLink Pet; restores the previous action."""
import concurrent.futures
import http.client
import json
import socket

HOST, PORT = '127.0.0.1', 8766

def request(method, path, body=None, headers=None):
    connection = http.client.HTTPConnection(HOST, PORT, timeout=3)
    try:
        connection.request(method, path, body, headers or {})
        response = connection.getresponse()
        return response.status, json.loads(response.read())
    finally:
        connection.close()

def post(action):
    return request('POST', '/v1/state', json.dumps({'action': action}), {'Content-Type': 'application/json'})

assert request('GET', '/health')[1]['status'] == 'ok'
previous = request('GET', '/v1/state')[1]['action']
try:
    actions = request('GET', '/v1/actions')[1]['actions']
    assert len(actions) == 7
    for item in actions:
        code, state = post(item['action'])
        assert code == 200 and state['action'] == item['action'] and state['source'] == 'collar'
        assert request('GET', '/v1/state')[1]['action'] == item['action']
    before = request('GET', '/v1/state')[1]
    for body in ['{}', '{', '{"action":"fly"}', '{"action":123}']:
        assert request('POST','/v1/state',body,{'Content-Type':'application/json'})[0] == 400
        assert request('GET','/v1/state')[1] == before
    assert request('POST','/v1/state','{"action":"walk"}',{'Content-Type':'text/plain'})[0] == 415
    assert request('POST','/v1/state','{"action":"walk"}',{'Content-Type':'application/json','Origin':'https://example.com'})[0] == 403
    assert request('GET','/missing')[0] == 404
    assert request('DELETE','/v1/state')[0] == 405
    assert request('GET','/health',headers={'Host':'example.com'})[0] == 403
    assert request('POST','/v1/state','x'*4097,{'Content-Type':'application/json'})[0] == 413
    body = b'{"action":"roll"}'
    with socket.create_connection((HOST, PORT), timeout=3) as s:
        s.sendall(f'POST /v1/state HTTP/1.1\r\nHost: {HOST}:{PORT}\r\nContent-Type: application/json\r\nContent-Length: {len(body)}\r\n\r\n'.encode())
        for chunk in [body[:4], body[4:10], body[10:]]:
            s.sendall(chunk)
        response = http.client.HTTPResponse(s); response.begin()
        assert response.status == 200 and json.loads(response.read())['action'] == 'roll'
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
        assert all(code == 200 for code, _ in pool.map(lambda _: post('walk'), range(24)))
    print('PASS: 7 actions, readback, invalid input isolation, origin/host checks, size limits, fragmented request, concurrent reports')
finally:
    post(previous)
