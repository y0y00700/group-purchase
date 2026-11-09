"""Explicit local member preparation. Passwords enter only a child environment, never argv."""
import argparse
import getpass
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
JAR = ROOT / 'services/member-service/build/libs/member-service-0.0.1-SNAPSHOT.jar'


def environment():
    env = os.environ.copy()
    env.update(SPRING_PROFILES_ACTIVE='local', CONFIG_SERVER_URL='http://127.0.0.1:8888',
               SPRING_DATASOURCE_PASSWORD=(ROOT / 'secrets/member_password').read_text().rstrip('\r\n'),
               APP_AUTH_INTERNAL_CREDENTIAL=(ROOT / 'secrets/member_internal_credential').read_text().rstrip('\r\n'))
    return env


def java():
    name = 'java.exe' if os.name == 'nt' else 'java'
    return str(Path(os.environ['JAVA_HOME']) / 'bin' / name)


def command(port=0):
    return [java(), '-jar', str(JAR), f'--server.port={port}', '--eureka.client.enabled=false']


def prepare(login_id, password=None, roles='BUYER', status='ACTIVE', update=False, reset=False):
    env = environment()
    if password is not None:
        env['GP_MEMBER_PASSWORD'] = password
    args = command() + ['--app.member-tool.enabled=true', f'--app.member-tool.login-id={login_id}',
                        f'--app.member-tool.roles={roles}', f'--app.member-tool.status={status}',
                        f'--app.member-tool.update={str(update).lower()}',
                        f'--app.member-tool.reset-password={str(reset).lower()}']
    result = subprocess.run(args, cwd=ROOT, env=env, capture_output=True, timeout=90)
    if result.returncode or b'Member tool completed' not in result.stdout:
        # Do not echo captured configuration/exception text or any supplied credential.
        raise RuntimeError('Member preparation failed; existing accounts are preserved by default.')
    print('PASS: explicitly selected member prepared')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('login_id')
    parser.add_argument('--roles', default='BUYER')
    parser.add_argument('--status', choices=('ACTIVE', 'DISABLED'), default='ACTIVE')
    parser.add_argument('--update', action='store_true')
    parser.add_argument('--reset-password', action='store_true')
    args = parser.parse_args()
    password = getpass.getpass('Member password (hidden): ') if not args.update or args.reset_password else None
    prepare(args.login_id, password, args.roles, args.status, args.update, args.reset_password)
