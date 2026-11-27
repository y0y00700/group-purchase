"""Product scheduler checks against the dedicated group-purchase-012 Compose project.

No product HTTP seed API. Probe writes only to this disposable DB/broker.
--lifecycle also checks broker outage, Windows/local product JAR and full down/up.
Artifacts and a command/result manifest stay in .tmp/outbox/<runId>.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import time
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]
LIBS = ROOT / 'services/group-purchase-service/build/libs'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--project', default='group-purchase-012', choices=['group-purchase-012', 'group-purchase-013'])
    parser.add_argument('--lifecycle', action='store_true')
    args = parser.parse_args()
    directory = ROOT / '.tmp/outbox' / uuid.uuid4().hex
    directory.mkdir(parents=True)
    java = str(Path(os.environ['JAVA_HOME']) / 'bin/java') if os.environ.get('JAVA_HOME') else 'java'
    probe = directory / 'probe.jar'
    shutil.copyfile(LIBS / 'group-purchase-service-0.0.1-SNAPSHOT-outbox-probe.jar', probe)
    product = directory / 'product.jar'
    shutil.copyfile(LIBS / 'group-purchase-service-0.0.1-SNAPSHOT.jar', product)
    compose = ['docker', 'compose', '-p', args.project]
    port = os.environ.get('POSTGRES_PORT', '5432')
    bootstrap = 'localhost:' + os.environ.get('KAFKA_PORT', '9092')
    jdbc = f'jdbc:postgresql://localhost:{port}/group_purchase?currentSchema=app&connectTimeout=3&socketTimeout=5'
    checks = []
    (directory / 'artifacts.json').write_text(json.dumps({p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in [probe, product]}, indent=2))

    def run(command, label, timeout=180, input=None):
        result = subprocess.run([str(x) for x in command], cwd=ROOT, input=input, capture_output=True,
                                text=True, encoding='utf-8', errors='replace', timeout=timeout)
        output = result.stdout + result.stderr
        path = directory / f'{len(checks):02d}-{label}.log'
        path.write_text(output, encoding='utf-8')
        checks.append({'label': label, 'exit': result.returncode, 'log': str(path.relative_to(ROOT))})
        if result.returncode:
            raise RuntimeError(f'{label} failed: see {path}')
        print('CHECK_OK', label, flush=True)
        return output

    def sql(query):
        # Password is read inside postgres; never in argv, stdout or the manifest.
        return run(compose + ['exec', '-T', 'postgres', 'sh', '-c',
                   'export PGPASSWORD="$(cat /run/secrets/group_purchase_password)"; '
                   'exec psql -X -w -h 127.0.0.1 -U group_purchase_app -d group_purchase -v ON_ERROR_STOP=1 -At'],
                   'sql', input=query + '\n').strip()

    def probe_run(mode, target):
        target.mkdir(exist_ok=True)
        return run([java, '-jar', probe, mode, jdbc, 'group_purchase_app', ROOT / 'secrets/group_purchase_password',
                    bootstrap, target, '60000'], mode)

    def state(target, expected, timeout=100):
        message_id = str(uuid.UUID((target / 'message.id').read_text()))
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if sql(f"SELECT status FROM app.outbox_messages WHERE message_id='{message_id}';") == expected:
                print('STATE_OK', message_id, expected, flush=True)
                return message_id
            time.sleep(1)
        raise RuntimeError(f'{message_id} did not reach {expected}')

    def delivered(target):
        state(target, 'PUBLISHED')
        output = probe_run('receive', target)
        if 'MATCHES 1' not in output:
            raise RuntimeError('expected one original record')
        probe_run('inspect', target)

    try:
        print('DIAGNOSTICS', directory, flush=True)
        containers = run(compose + ['ps', '-a', '--format', '{{.Service}} {{.State}} {{.Health}} {{.ExitCode}}'], 'services')
        actual = {p[0]: p[1:] for line in containers.splitlines() if (p := line.split())}
        expected = {'kafka', 'postgres', 'config-server', 'eureka-server', 'api-gateway', 'member-service', 'group-purchase-service'}
        if any(actual.get(s) != ['running', 'healthy', '0'] for s in expected) or actual.get('kafka-init') != ['exited', '0']:
            raise RuntimeError('expected seven healthy services and kafka-init exit 0')
        if sql("SELECT count(*) FROM app.flyway_schema_history WHERE version='4' AND success;") != '1':
            raise RuntimeError('V4 not applied')
        target = directory / 'compose'
        probe_run('seed', target)
        delivered(target)  # Product Docker JAR sends via INTERNAL, probe receives via HOST.
        if args.lifecycle:
            outage = directory / 'outage'
            run(compose + ['stop', 'kafka'], 'stop-broker')
            try:
                probe_run('seed', outage)
                message_id = state(outage, 'RETRY_WAIT', 110)
                with urllib.request.urlopen('http://localhost:8080/actuator/health/readiness', timeout=5) as response:
                    assert response.status == 200
                print('HTTP_READY_DURING_OUTAGE', flush=True)
                if sql(f"SELECT retry_count>0 AND first_failure_at IS NOT NULL FROM app.outbox_messages WHERE message_id='{message_id}';") != 't':
                    raise RuntimeError('retry not persisted')
            finally:
                run(compose + ['start', 'kafka'], 'restore-broker')
            delivered(outage)
            run(compose + ['stop', 'postgres'], 'stop-database')
            try:
                # The same product process sees at least one failed poll; no restart hides recovery.
                time.sleep(12)
            finally:
                run(compose + ['up', '-d', '--wait', '--wait-timeout', '120', 'postgres'], 'restore-database')
            recovered = directory / 'database-recovery'
            probe_run('seed', recovered)
            delivered(recovered)
            run(compose + ['logs', '--no-color', '--tail=500', 'group-purchase-service'], 'publisher-recovery-logs')
            run(compose + ['stop', 'group-purchase-service'], 'stop-compose-publisher')
            env = os.environ.copy()
            env['SPRING_DATASOURCE_PASSWORD'] = (ROOT / 'secrets/group_purchase_password').read_text().strip()
            env['APP_AUTH_INTERNAL_CREDENTIAL'] = (ROOT / 'secrets/group_purchase_internal_credential').read_text().strip()
            log = (directory / 'local-product.log').open('w', encoding='utf-8')
            process = None
            try:
                process = subprocess.Popen([java, '-jar', str(product), '--spring.profiles.active=local', '--server.port=18080',
                    '--eureka.client.enabled=false', '--spring.datasource.url=' + jdbc,
                    '--app.messaging.kafka.bootstrap-servers=' + bootstrap], cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT)
                deadline = time.monotonic() + 100
                while time.monotonic() < deadline:
                    if process.poll() is not None:
                        raise RuntimeError('local product exited; see local-product.log')
                    try:
                        with urllib.request.urlopen('http://localhost:18080/actuator/health/readiness', timeout=2) as response:
                            if response.status == 200:
                                break
                    except (OSError, TimeoutError):
                        pass
                    time.sleep(1)
                else:
                    raise RuntimeError('local product readiness timeout')
                target = directory / 'local-product'
                probe_run('seed', target)
                delivered(target)
            finally:
                if process is not None and process.poll() is None:
                    process.terminate()
                    try:
                        process.wait(65)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait(10)
                log.close()
                run(compose + ['up', '-d', '--wait', '--wait-timeout', '180', 'group-purchase-service'], 'restore-compose-publisher')
            run(compose + ['down'], 'down-preserve-volumes')
            run(compose + ['up', '-d', '--wait', '--wait-timeout', '240'], 'up-preserved-volumes', timeout=300)
            delivered(target)
        print('OUTBOX_OK', flush=True)
    finally:
        (directory / 'summary.json').write_text(json.dumps(checks, indent=2), encoding='utf-8')


if __name__ == '__main__':
    main()
