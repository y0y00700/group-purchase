"""007 real Compose + two product JAR processes (Windows/Linux). Cleans only its random fixture."""
import base64
import importlib.util
import json
from pathlib import Path
import secrets
import shlex
import subprocess
import tempfile
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('member_tool', ROOT / 'scripts/member-tool.py')
tool = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tool)
login_id = 'probe007_' + secrets.token_hex(6)
password = secrets.token_hex(24)
credential = (ROOT / 'secrets/member_internal_credential').read_text().rstrip('\r\n')
session_ids = set()


def request(port, path, method='GET', cookie=None, token=None, body=None, internal=False):
    headers = {'X-Request-Id': 'a' * 32}
    if cookie:
        headers['Cookie'] = cookie
    if token:
        headers['X-CSRF-TOKEN'] = token
    if internal:
        headers.update({'X-Internal-Caller': 'api-gateway', 'X-Internal-Credential': credential})
    if body is not None:
        headers['Content-Type'] = 'application/json'
    req = urllib.request.Request(f'http://127.0.0.1:{port}{path}', headers=headers, method=method,
                                 data=None if body is None else json.dumps(body).encode())
    try:
        response = urllib.request.urlopen(req, timeout=15)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        raw = response.read()
        result = json.loads(raw) if raw else {}
        new_cookie = response.headers.get('Set-Cookie', '').split(';', 1)[0]
        if new_cookie.startswith('GP_SESSION=') and new_cookie.split('=', 1)[1]:
            session_ids.add(base64.b64decode(new_cookie.split('=', 1)[1]).decode())
        if response.status >= 400 and not path.startswith('/actuator/'):
            assert result['status'] == response.status and len(result['requestId']) == 32
            assert response.headers['X-Request-Id'] == result['requestId']
            assert response.headers.get_content_type() == 'application/problem+json'
        return response.status, result, new_cookie


def wait(port):
    end = time.monotonic() + 90
    while time.monotonic() < end:
        try:
            if request(port, '/actuator/health')[0] == 200:
                return
        except OSError:
            pass
        time.sleep(1)
    raise AssertionError('Member product JAR did not become healthy')


def sql(query):
    # Password stays inside PostgreSQL container. SQL is only server-generated fixture identifiers.
    script = 'export PGPASSWORD="$(cat /run/secrets/member_password)"\n' + (
        'psql -X -w -h 127.0.0.1 -U member_app -d member -v ON_ERROR_STOP=1 -At -c ' + shlex.quote(query) + '\n')
    result = subprocess.run(['docker', 'compose', 'exec', '-T', 'postgres', 'sh', '-s'],
                            cwd=ROOT, input=script.encode(), capture_output=True, timeout=20)
    assert result.returncode == 0, 'Fixture SQL failed'
    return result.stdout.decode().strip()


def exercise(a, b):
    status, seed, old = request(a, '/api/auth/csrf')
    assert status == 200
    assert request(a, '/api/auth/login', 'POST', old, None, {})[0] == 403
    status, member, cookie = request(b, '/api/auth/login', 'POST', old, seed['token'],
                                     {'loginId': login_id, 'password': password})
    assert status == 200 and cookie and cookie != old
    assert request(a, '/api/members/me', cookie=old)[0] == 401
    assert request(a, '/api/members/me', cookie=cookie)[1] == member
    assert request(b, '/internal/auth/check', 'POST', cookie, body={'method': 'GET'}, internal=True)[1] == member
    assert request(b, '/internal/auth/check', 'POST', cookie, body={'method': 'POST'}, internal=True)[0] == 403
    _, fresh, _ = request(a, '/api/auth/csrf', cookie=cookie)
    assert request(b, '/internal/auth/check', 'POST', cookie, seed['token'], {'method': 'POST'}, True)[0] == 403
    status, _, headers = request(b, '/internal/auth/check', 'POST', cookie, fresh['token'], {'method': 'POST'}, True)
    assert status == 200 and not headers
    assert request(b, '/api/auth/logout', 'POST', cookie, fresh['token'])[0] == 204
    assert request(a, '/api/members/me', cookie=cookie)[0] == 401
    print('PASS: real HTTP session rotation/sharing/CSRF/logout', a, b)


def main():
    processes = []
    streams = []
    member_id = None
    with tempfile.TemporaryDirectory(prefix='007-member-') as directory:
        try:
            tool.prepare(login_id, password)
            member_id = sql(f"SELECT member_id FROM app.members WHERE login_id='{login_id}';")
            before_hash = sql(f"SELECT md5(password_hash) FROM app.members WHERE member_id='{member_id}';")
            try:
                tool.prepare(login_id, secrets.token_hex(24))
            except RuntimeError:
                pass
            else:
                raise AssertionError('Existing member was overwritten without explicit update')
            tool.prepare(login_id, roles='BUYER,SELLER', update=True)
            assert sql(f"SELECT md5(password_hash) FROM app.members WHERE member_id='{member_id}';") == before_hash
            print('PASS: local tool preserves existing account/hash; explicit role update')
            exercise(8081, 8081)
            for port in (18081, 18082):
                stream = open(Path(directory) / f'{port}.jsonl', 'wb')
                streams.append(stream)
                args = tool.command(port)
                processes.append(subprocess.Popen(args, cwd=ROOT, env=tool.environment(), stdout=stream, stderr=stream))
                wait(port)
            exercise(18081, 18082)
            assert request(8000, '/api/members/me')[0] == 401
            exercise(8000, 8081)
            assert request(8000, '/internal/auth/check', 'POST', body={'method': 'GET'})[0] == 404
            raw = subprocess.run(['docker', 'compose', 'logs', '--no-color', 'member-service'], cwd=ROOT,
                                 capture_output=True, check=True, timeout=20).stdout.decode()
            for stream in streams:
                stream.flush()
                raw += Path(stream.name).read_text(encoding='utf-8')
            assert password not in raw and credential not in raw and login_id not in raw
            for event in raw.splitlines():
                if '"event":"http_request_completed"' in event.replace(' ', ''):
                    item = json.loads(event[event.index('{'):])
                    assert item['route'] in ('UNMATCHED', '/api/auth/csrf', '/api/auth/login', '/api/auth/logout', '/api/members/me', '/internal/auth/check')
            instances = urllib.request.Request('http://127.0.0.1:8761/eureka/apps/MEMBER', headers={'Accept':'application/json'})
            with urllib.request.urlopen(instances, timeout=10) as response:
                records = json.load(response)['application']['instance']
            if isinstance(records, dict): records = [records]
            assert len(records) == 1 and records[0]['hostName'] == 'member-service' and records[0]['status'] == 'UP'
            result = subprocess.run(['docker','compose','exec','-T','config-server','curl','--fail','--silent',
                                     'http://member-service:8081/actuator/health'], cwd=ROOT, capture_output=True, check=True, timeout=15)
            assert json.loads(result.stdout)['status'] == 'UP'
            print('PASS: Windows/Linux product JARs, JSON/privacy logs, member registration, Gateway boundary')
        finally:
            for process in processes:
                process.terminate()
                try: process.wait(timeout=20)
                except subprocess.TimeoutExpired: process.kill(); process.wait(timeout=10)
            for stream in streams: stream.close()
            if member_id:
                ids = ','.join("'"+value+"'" for value in session_ids)
                sql(f"DELETE FROM app.spring_session WHERE principal_name='{member_id}'" + (f' OR session_id IN ({ids})' if ids else '') + ';'
                    + f"DELETE FROM app.members WHERE member_id='{member_id}';")


if __name__ == '__main__':
    main()
