"""Finite Kafka checks using the Java probe; Python uses only the standard library.

Default is non-disruptive. --lifecycle is restricted to the dedicated 011 project.
Each Java invocation is a fresh process, including the offset/replay checks.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import time
import uuid

ROOT = Path(__file__).resolve().parents[1]
JAR = ROOT / 'services/group-purchase-service/build/libs/group-purchase-service-0.0.1-SNAPSHOT-kafka-probe.jar'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--project', default='group-purchase')
    parser.add_argument('--bootstrap', default=f"localhost:{os.environ.get('KAFKA_PORT', '9092')}")
    parser.add_argument('--lifecycle', action='store_true', help='restart, full down/up, outage (dedicated project only)')
    parser.add_argument('--cleanup-run', help='clean only the exact verify.<runId>. resources of a previous failed run')
    args = parser.parse_args()
    if args.lifecycle and args.project != 'group-purchase-011':
        parser.error('--lifecycle requires --project group-purchase-011')
    run_id = args.cleanup_run or uuid.uuid4().hex
    if not re.fullmatch(r'[a-z0-9-]{1,64}', run_id):
        parser.error('invalid runId')
    if not JAR.is_file():
        parser.error('build :group-purchase-service:kafkaProbeJar first')
    java = str(Path(os.environ['JAVA_HOME']) / 'bin/java') if os.environ.get('JAVA_HOME') else 'java'
    compose = ['docker', 'compose', '-p', args.project]
    output_dir = ROOT / '.tmp/kafka' / run_id
    output_dir.mkdir(parents=True, exist_ok=True)
    # Boot's nested JAR classes are loaded lazily. A concurrent Gradle rebuild must
    # not replace the archive while a Java process is still reading it.
    snapshot = output_dir / 'probe.jar'
    shutil.copyfile(JAR, snapshot)
    (output_dir / 'artifact.json').write_text(json.dumps({
        'sourceJar': str(JAR.relative_to(ROOT)),
        'sha256': hashlib.sha256(snapshot.read_bytes()).hexdigest(),
    }, indent=2), encoding='utf-8')
    checks = []

    def run(command, label, timeout=180, expected_error=None):
        started = time.monotonic()
        result = subprocess.run([str(c) for c in command], cwd=ROOT, capture_output=True, text=True,
                                encoding='utf-8', errors='replace', timeout=timeout)
        text = result.stdout + result.stderr
        path = output_dir / f'{len(checks):02d}-{label}.log'
        path.write_text(text, encoding='utf-8')
        checks.append({'check': label, 'seconds': round(time.monotonic() - started, 2),
                       'exit': result.returncode, 'log': str(path.relative_to(ROOT))})
        for line in text.splitlines():
            if line.startswith(('ACK ', 'RECEIVE ', 'COMMIT ', 'RESTART ', 'TOPIC_OK ', 'GROUP_SPLIT ', 'OUTAGE_', 'PROBE_OK ', 'CLEANUP ', 'REJECT ')):
                print(line)
        if expected_error:
            if result.returncode == 0 or expected_error not in text:
                raise RuntimeError(f'{label}: expected {expected_error}, see {path}')
        elif result.returncode:
            raise RuntimeError(f'{label}: exit {result.returncode}, see {path}')
        print(f'CHECK_OK {label} {checks[-1]["seconds"]}s')
        return text

    def probe(command, internal=False, expected_error=None):
        if internal:
            cmd = compose + ['run', '--rm', '--no-deps', '-v', f'{snapshot.as_posix()}:/probe.jar:ro',
                             '--entrypoint', 'java', 'group-purchase-service', '-jar', '/probe.jar',
                             'kafka:19092', run_id, command]
        else:
            cmd = [java, '-jar', snapshot, args.bootstrap, run_id, command]
        return run(cmd, ('internal-' if internal else 'host-') + command, expected_error=expected_error)

    def ready(label):
        container_id = run(compose + ['ps', '-a', '-q', 'kafka-init'], label + '-init-id').strip()
        if not re.fullmatch(r'[a-f0-9]{12,64}', container_id):
            raise RuntimeError('expected exactly one kafka-init container')
        # docker wait also accepts an already exited one-shot container.
        exit_code = run(['docker', 'wait', container_id], label + '-init-exit', timeout=120).strip()
        if exit_code != '0':
            raise RuntimeError('kafka-init exited ' + exit_code)
        state = run(compose + ['ps', '-a', '--format', '{{.Service}} {{.State}} {{.Health}} {{.ExitCode}}'], label + '-services')
        actual = {parts[0]: parts[1:] for line in state.splitlines() if (parts := line.split())}
        expected = {'postgres', 'config-server', 'eureka-server', 'api-gateway', 'group-purchase-service', 'member-service', 'kafka'}
        if args.project == 'group-purchase-014':
            expected.update({'pg-mock', 'pg-mock-postgres'})
        if any(actual.get(name) != ['running', 'healthy', '0'] for name in expected) or actual.get('kafka-init') != ['exited', '0']:
            raise RuntimeError(f'expected {len(expected)} healthy services and kafka-init exited 0: ' + state)

    try:
        if args.cleanup_run:
            probe('cleanup')
            return
        print(f'RUN_ID={run_id} diagnostics={output_dir}')
        ready('initial')
        run(compose + ['run', '--rm', 'kafka-init'], 'repeat-init', timeout=180)
        for kind, partitions, config, expected in [('topology', '1', '604800000', 'TOPOLOGY_MISMATCH'),
                                                    ('config', '3', '10000', 'CONFIG_MISMATCH')]:
            topic = f'verify.{run_id}.mismatch-{kind}'
            run(compose + ['exec', '-T', 'kafka', '/opt/kafka/bin/kafka-topics.sh', '--bootstrap-server', 'kafka:19092',
                           '--create', '--topic', topic, '--partitions', partitions, '--replication-factor', '1',
                           '--config', 'retention.ms=' + config], f'create-mismatch-{kind}')
            topic_file = output_dir / f'{kind}-topics.txt'
            # A shell read on Linux must not receive Windows' translated CRLF.
            topic_file.write_bytes((topic + '\n').encode('utf-8'))
            run(compose + ['run', '--rm', '--no-deps', '-v', f'{topic_file.as_posix()}:/contract/topics.txt:ro',
                           'kafka-init'], f'reject-mismatch-{kind}', expected_error=expected)
            unchanged = run(compose + ['exec', '-T', 'kafka', '/opt/kafka/bin/kafka-topics.sh', '--bootstrap-server',
                                      'kafka:19092', '--describe', '--topic', topic], f'unchanged-{kind}')
            if ('PartitionCount: 1' if kind == 'topology' else 'retention.ms=10000') not in unchanged:
                raise RuntimeError('initializer unexpectedly changed mismatched topic')
        probe('business')
        probe('prepare')
        probe('smoke')
        probe('ping', internal=True)
        probe('seed')
        probe('uncommitted')
        probe('uncommitted')
        probe('committed')
        probe('bad', expected_error='code=MALFORMED_JSON')
        text = probe('bad', expected_error='code=MALFORMED_JSON')
        if 'committed=1 nextPosition=1' not in text or 'offset=1 code=MALFORMED_JSON' not in text:
            raise RuntimeError('poison B was not replayed at committed offset 1')
        probe('retained', internal=True)
        if args.lifecycle:
            run(compose + ['restart', 'kafka'], 'restart')
            run(compose + ['up', '-d', '--wait', '--wait-timeout', '240'], 'restart-ready', timeout=300)
            ready('restart')
            probe('retained')
            run(compose + ['down'], 'down-volumes-preserved')
            run(compose + ['up', '-d', '--wait', '--wait-timeout', '240'], 'up-volumes-preserved', timeout=300)
            ready('preserved')
            probe('retained')
            run(compose + ['stop', 'kafka'], 'stop-broker')
            try:
                probe('outage')
            finally:
                run(compose + ['up', '-d', '--wait', '--wait-timeout', '240', 'kafka'], 'recover-broker', timeout=300)
            probe('retained', internal=True)
            probe('ping')
        probe('cleanup')
        print('KAFKA_CHECKS_OK')
    except Exception:
        # Preserve resources for diagnostics. Never delete a business topic or a volume.
        result = subprocess.run(compose + ['ps', '-a'], cwd=ROOT, capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=30)
        (output_dir / 'compose-ps.log').write_text(result.stdout + result.stderr, encoding='utf-8')
        print(f'FAILED run={run_id}; restore Kafka then clean with --project {args.project} --cleanup-run {run_id}')
        raise
    finally:
        (output_dir / 'summary.json').write_text(json.dumps(checks, indent=2), encoding='utf-8')


if __name__ == '__main__':
    main()
