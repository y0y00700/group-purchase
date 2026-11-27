"""Finite inbox checks restricted to the isolated group-purchase-013 Compose project.

Uses a snapshot of the verification JAR. --lifecycle includes DB/broker outages
in the same consumer process, two real members and full down/up with volumes kept.
No product hold is released; only this run's verify.inbox.* topics/groups are removed.
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
    parser.add_argument('--project', default='group-purchase-013', choices=['group-purchase-013'])
    parser.add_argument('--lifecycle', action='store_true')
    args = parser.parse_args()
    run_id = uuid.uuid4().hex
    directory = ROOT / '.tmp/inbox' / run_id
    directory.mkdir(parents=True)
    jar = directory / 'inbox-probe.jar'
    shutil.copyfile(LIBS / 'group-purchase-service-0.0.1-SNAPSHOT-inbox-probe.jar', jar)
    product = directory / 'product.jar'
    shutil.copyfile(LIBS / 'group-purchase-service-0.0.1-SNAPSHOT.jar', product)
    java = str(Path(os.environ['JAVA_HOME']) / 'bin/java') if os.environ.get('JAVA_HOME') else 'java'
    compose = ['docker', 'compose', '-p', args.project]
    bootstrap = 'localhost:' + os.environ.get('KAFKA_PORT', '9092')
    jdbc = 'jdbc:postgresql://localhost:' + os.environ.get('POSTGRES_PORT', '5432') + '/group_purchase?currentSchema=app&connectTimeout=3&socketTimeout=5'
    resources, checks, children = [], [], []

    def run(command, label, timeout=120):
        result = subprocess.run([str(v) for v in command], cwd=ROOT, capture_output=True, text=True,
                                encoding='utf-8', errors='replace', timeout=timeout)
        path = directory / f'{len(checks):02d}-{label}.log'
        path.write_text(result.stdout + result.stderr, encoding='utf-8')
        checks.append({'label': label, 'exit': result.returncode, 'log': str(path.relative_to(ROOT))})
        if result.returncode:
            raise RuntimeError(f'{label}: see {path}')
        print('CHECK_OK', label, flush=True)
        return result.stdout

    def command(mode, topic, target, internal=False, duration=12000):
        group = topic + '.group'
        if internal:
            return compose + ['run', '--rm', '--no-deps', '-v', f'{jar.as_posix()}:/probe.jar:ro',
                              '--entrypoint', 'java', 'group-purchase-service', '-jar', '/probe.jar', mode,
                              'jdbc:postgresql://postgres:5432/group_purchase?currentSchema=app&connectTimeout=3&socketTimeout=5',
                              'group_purchase_app', '/run/secrets/spring.datasource.password', 'kafka:19092', topic, group,
                              '/tmp/inbox-probe', str(duration)]
        return [java, '-jar', jar, mode, jdbc, 'group_purchase_app', ROOT / 'secrets/group_purchase_password',
                bootstrap, topic, group, target, str(duration)]

    def probe(mode, topic, target, internal=False):
        return run(command(mode, topic, target, internal), ('internal-' if internal else 'host-') + mode, 100)

    def start(topic, target, duration=90000):
        stream = open(target / f'loop-{len(children)}.log', 'w', encoding='utf-8')
        child = subprocess.Popen([str(v) for v in command('loop', topic, target, duration=duration)], cwd=ROOT,
                                 stdout=stream, stderr=subprocess.STDOUT)
        children.append((child, stream))
        return child

    def finish(child):
        code = child.wait(timeout=150)
        if code:
            raise RuntimeError('consumer process failed; see loop logs')

    def ready():
        initializer = run(compose + ['ps', '-a', '-q', 'kafka-init'], 'initializer-id').strip()
        if not initializer or run(['docker', 'wait', initializer], 'initializer-exit', 120).strip() != '0':
            raise RuntimeError('topic initializer must exit zero')
        output = run(compose + ['ps', '-a', '--format', '{{.Service}} {{.State}} {{.Health}} {{.ExitCode}}'], 'services')
        actual = {v[0]: v[1:] for line in output.splitlines() if (v := line.split())}
        for name in ['postgres', 'kafka', 'config-server', 'eureka-server', 'api-gateway', 'member-service', 'group-purchase-service']:
            if actual.get(name) != ['running', 'healthy', '0']:
                raise RuntimeError('expected seven healthy services')
        if actual.get('kafka-init') != ['exited', '0']:
            raise RuntimeError('topic initializer must exit zero')
        with urllib.request.urlopen('http://localhost:8080/actuator/info', timeout=5) as response:
            info = json.load(response)
        if info['inbox'] != {'enabled': False, 'status': 'DISABLED'}:
            raise RuntimeError('product inbox must remain disabled')

    try:
        print('DIAGNOSTICS', directory, flush=True)
        ready()
        for listener in ['host', 'internal']:
            topic = f'verify.inbox.{run_id}.{listener}'
            resources.append(topic)
            target = directory / listener
            target.mkdir()
            probe('prepare', topic, target)
            probe('seed', topic, target)
            probe('loop', topic, target, listener == 'internal')
            print(probe('verify', topic, target).strip().splitlines()[-2], flush=True)
        if args.lifecycle:
            topic = resources[0]
            target = directory / 'host'
            # Start before stopping DB: this exact process must recover and preserve prior HALTED.
            child = start(topic, target, 80000)
            time.sleep(7)
            run(compose + ['stop', 'postgres'], 'stop-db')
            probe('append', topic, target)  # send has no DB access
            time.sleep(7)
            run(compose + ['start', '--wait', '--wait-timeout', '90', 'postgres'], 'restore-db')
            finish(child)
            probe('verify', topic, target)
            # Two actual members: first exits while the second keeps running.
            first = start(topic, target, 16000)
            time.sleep(5)
            second = start(topic, target, 32000)
            finish(first)
            probe('append', topic, target)
            finish(second)
            probe('verify', topic, target)
            child = start(topic, target, 55000)
            time.sleep(7)
            run(compose + ['stop', 'kafka'], 'stop-broker')
            time.sleep(5)
            run(compose + ['start', '--wait', '--wait-timeout', '90', 'kafka'], 'restore-broker')
            probe('append', topic, target)
            finish(child)
            probe('verify', topic, target)
            run(compose + ['down'], 'down-volumes-preserved', 180)
            run(compose + ['up', '-d', '--wait', '--wait-timeout', '240'], 'up-volumes-preserved', 300)
            ready()
            probe('loop', topic, target)
            probe('verify', topic, target)
        print('INBOX_CHECKS_OK', len(checks), flush=True)
    finally:
        for child, stream in children:
            if child.poll() is None:
                child.kill()
                child.wait(timeout=15)
            stream.close()
        # Delete only the exact names this invocation created. DB source failures remain for inspection.
        if resources:
            cleanup = directory / 'cleanup.log'
            with cleanup.open('w', encoding='utf-8') as output:
                for topic in resources:
                    for name in [topic, topic + '.dlt']:
                        subprocess.run(compose + ['exec', '-T', 'kafka', '/opt/kafka/bin/kafka-topics.sh', '--bootstrap-server',
                                                 'kafka:19092', '--delete', '--if-exists', '--topic', name], cwd=ROOT,
                                       stdout=output, stderr=subprocess.STDOUT, timeout=30)
                    subprocess.run(compose + ['exec', '-T', 'kafka', '/opt/kafka/bin/kafka-consumer-groups.sh',
                                             '--bootstrap-server', 'kafka:19092', '--delete', '--group', topic + '.group'],
                                   cwd=ROOT, stdout=output, stderr=subprocess.STDOUT, timeout=30)
        (directory / 'manifest.json').write_text(json.dumps({'runId': run_id, 'checks': checks, 'resources': resources,
            'sha256': {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in [jar, product]}}, indent=2), encoding='utf-8')


if __name__ == '__main__':
    main()
