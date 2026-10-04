#!/usr/bin/env python3
"""lab-endpoints.py — BEFORE a service starts in a lab: say every address its configuration names, and refuse the
start unless each one is on THIS box.

    lab-endpoints.py <quarkus-app dir> <run dir> [key=value ...]

Why: a jar carries build-time defaults, and this repo's jar carries the registry and the profiles of real
deployments. A lab start that does not find its own configuration falls back to them and dials a real box. So a lab
service is started only through start-local-only.sh, which runs this first.

What is read, in the order the service itself applies them (the later wins):
    1. the application.properties inside the jar            (<quarkus-app dir>/app/*.jar)
    2. <run dir>/config/application.properties              REQUIRED — without it the start is refused
    3. key=value arguments                                  (what the launcher passes as -D)
and, as files of the lab itself, every *.yml / *.yaml under <run dir>/config (a tenant profile's addresses).
The launcher starts the service with an EMPTY environment, so no variable of the caller's shell can move an address.

What counts as an address: a value that holds scheme://host, a host:port list, an IPv4 address or the word localhost.
What counts as this box: the word localhost, a loopback address, or an address one of this box's own interfaces
holds (a lab bridge). A host NAME is never looked up — the lookup itself would leave the box — and is refused.
Listener keys (quarkus.http.host, quarkus.grpc.server.host, quarkus.management.host) are shown, not judged: nothing
is dialed there.

Exit 0: every address is on this box. Exit 1: at least one is not (each is named). Exit 2: the run directory has no
configuration of its own.
"""
import ipaddress, os, re, subprocess, sys, zipfile, glob

LISTENERS = {'quarkus.http.host', 'quarkus.grpc.server.host', 'quarkus.management.host'}
URL = re.compile(r'[a-z][a-z0-9+.:-]*://(\[[0-9a-fA-F:]+\]|[^/:?#\s,;"\']+)')
HOSTPORT = re.compile(r'^(\[[0-9a-fA-F:]+\]|[A-Za-z0-9_.-]+):\d{1,5}$')
IPV4 = re.compile(r'^\d{1,3}(\.\d{1,3}){3}$')


def properties_of(text):
    """key -> value of a .properties text (comments and blank lines dropped; a trailing backslash joins lines)."""
    props, pending = {}, ''
    for raw in text.splitlines():
        line = pending + raw.strip()
        pending = ''
        if not line or line[0] in '#!':
            continue
        if line.endswith('\\'):
            pending = line[:-1]
            continue
        m = re.match(r'([^=:\s]+)\s*[=:]\s*(.*)$', line)
        if m:
            props[m.group(1)] = m.group(2).strip()
    return props


def bundled_properties(app_dir):
    """The application.properties the jar itself carries (what a start without any configuration runs on)."""
    for jar in sorted(glob.glob(os.path.join(app_dir, 'app', '*.jar'))):
        with zipfile.ZipFile(jar) as z:
            if 'application.properties' in z.namelist():
                return properties_of(z.read('application.properties').decode('utf-8'))
    return None


def effective(layers):
    """One map out of the layers (the later wins); a %prod. key wins over the plain one; other profiles are dropped."""
    merged = {}
    for layer in layers:
        plain = {k: v for k, v in layer.items() if not k.startswith('%')}
        prod = {k[len('%prod.'):]: v for k, v in layer.items() if k.startswith('%prod.')}
        merged.update(plain)
        merged.update(prod)
    return merged


def expanded(value, props, depth=0):
    """${key} and ${key:default} put in (the way the service's own configuration does it)."""
    if depth > 8:
        return value
    def put(m):
        key, _, default = m.group(1).partition(':')
        return expanded(props.get(key, default), props, depth + 1)
    return re.sub(r'\$\{([^}]+)\}', put, value)


def hosts_of(value):
    """Every host a value names: out of URLs, out of a host:port list, or the value itself when it is an address."""
    hosts = [h.strip('[]') for h in URL.findall(value)]
    if hosts:
        return hosts
    parts = [p.strip() for p in value.split(',') if p.strip()]
    if parts and all(HOSTPORT.match(p) for p in parts):
        return [p.rsplit(':', 1)[0].strip('[]') for p in parts]
    if IPV4.match(value) or value.lower() == 'localhost':
        return [value]
    return []


def addresses_of_this_box():
    out = subprocess.run(['ip', '-o', 'addr', 'show'], capture_output=True, text=True).stdout
    held = {}
    for line in out.splitlines():
        f = line.split()
        if len(f) > 3 and f[2] in ('inet', 'inet6'):
            held[ipaddress.ip_address(f[3].split('/')[0])] = f[1]
    return held


def where(host, held):
    """(is this box, the words for it). A name is never looked up."""
    if host.lower() == 'localhost':
        return True, 'this box: localhost'
    try:
        address = ipaddress.ip_address(host)
    except ValueError:
        return False, 'NOT THIS BOX: a host name (never looked up)'
    if address.is_loopback:
        return True, 'this box: loopback'
    if address in held:
        return True, 'this box: the address of its interface ' + held[address]
    return False, 'NOT THIS BOX'


def yaml_values(path):
    """(key, value, line number) of every 'key: value' line of a YAML file — enough to find an address; no parser."""
    found = []
    with open(path, encoding='utf-8') as f:
        for n, line in enumerate(f, 1):
            m = re.match(r'\s*-?\s*([\w.-]+)\s*:\s*(.*?)\s*(#.*)?$', line)
            if m and m.group(2) and not line.lstrip().startswith('#'):
                found.append((m.group(1), m.group(2).strip('"\''), n))
    return found


def effective_properties(app_dir, run_dir, set_on_the_command_line):
    """The properties as the service will see them — or None, with the reason said, when the run directory has no
    configuration of its own or the jar's build-time defaults cannot be read."""
    own = os.path.join(run_dir, 'config', 'application.properties')
    if not os.path.isfile(own):
        print('REFUSED: %s does not exist. A start from here would run on what the jar itself carries — its build-time'
              ' defaults and, in this repo, the registry of a real deployment.' % own)
        return None
    bundled = bundled_properties(app_dir)
    if bundled is None:
        print('REFUSED: no jar with an application.properties under %s/app — the build-time defaults cannot be read,'
              ' so they cannot be judged.' % app_dir)
        return None
    return effective([bundled, properties_of(open(own, encoding='utf-8').read()),
                      properties_of('\n'.join(set_on_the_command_line))])


def say_and_judge(what, hosts, held):
    """One line for each host of one setting; [what] when a host is not this box, else []."""
    elsewhere = []
    for host in hosts:
        here, words = where(host, held)
        print('  dials    %s   [%s: %s]' % (what, host, words))
        if not here and what not in elsewhere:
            elsewhere.append(what)
    return elsewhere


def say_the_properties(props, held):
    """Every property that names an address: a listener is shown, a dialed one is judged. Returns what is elsewhere."""
    elsewhere = []
    for key in sorted(props):
        value = expanded(props[key], props)
        hosts = hosts_of(value)
        if hosts and key in LISTENERS:
            print('  listens  %s = %s' % (key, value))
        elif hosts:
            elsewhere += say_and_judge('%s = %s' % (key, value), hosts, held)
    return elsewhere


def say_the_yaml_files(run_dir, held):
    """Every address in every YAML file of the lab's own configuration (a tenant profile). Returns what is elsewhere."""
    elsewhere = []
    for path in sorted(glob.glob(os.path.join(run_dir, 'config', '**', '*.y*ml'), recursive=True)):
        for key, value, line in yaml_values(path):
            what = '%s:%d %s = %s' % (os.path.relpath(path, run_dir), line, key, value)
            elsewhere += say_and_judge(what, hosts_of(value), held)
    return elsewhere


def verdict(elsewhere):
    if not elsewhere:
        print('every address is on this box')
        return 0
    print('REFUSED: a lab start dials only this box, and these are elsewhere:')
    for setting in elsewhere:
        print('  ' + setting)
    return 1


def main(argv):
    if len(argv) < 3:
        print(__doc__)
        return 2
    app_dir, run_dir, set_on_the_command_line = argv[1], argv[2], argv[3:]
    props = effective_properties(app_dir, run_dir, set_on_the_command_line)
    if props is None:
        return 2
    held = addresses_of_this_box()
    print('endpoints of a start in %s  (jar: %s)' % (os.path.abspath(run_dir), os.path.abspath(app_dir)))
    return verdict(say_the_properties(props, held) + say_the_yaml_files(run_dir, held))


if __name__ == '__main__':
    sys.exit(main(sys.argv))
