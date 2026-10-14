# High Bytes POS - copies the mobile texts into the app (assets/texts/<lang>.json): every mobile.* and sync.reason.* key
# of the server's message bundles, as /api/v1/messages serves them (raw, French falling back to English key by key, as
# Spring does). Run it after changing those texts: python tool/texts.py
import io
import json
import os
import re

HERE = os.path.dirname(os.path.abspath(__file__))
BUNDLES = os.path.join(HERE, '..', '..', 'src', 'main', 'resources')
OUT = os.path.join(HERE, '..', 'assets', 'texts')
PREFIXES = ('mobile.', 'sync.reason.')
ESCAPES = {'t': '\t', 'n': '\n', 'r': '\r', 'f': '\f'}


def unescape(value):
    out, i = [], 0
    while i < len(value):
        c = value[i]
        if c == '\\' and i + 1 < len(value):
            n = value[i + 1]
            if n == 'u' and re.fullmatch(r'[0-9a-fA-F]{4}', value[i + 2:i + 6] or ''):
                out.append(chr(int(value[i + 2:i + 6], 16)))
                i += 6
                continue
            out.append(ESCAPES.get(n, n))
            i += 2
            continue
        out.append(c)
        i += 1
    return ''.join(out)


def load(path):
    """A .properties file as java.util.Properties reads it (continuation lines, escapes, = : or space)."""
    texts, pending = {}, ''
    for raw in io.open(path, encoding='latin-1').read().splitlines():
        line = (pending + raw.lstrip()) if pending else raw.lstrip()
        pending = ''
        if not line or (not pending and line[0] in '#!' and not raw.startswith(' ')):
            continue
        trailing = len(line) - len(line.rstrip('\\'))
        if trailing % 2 == 1:
            pending = line[:-1]
            continue
        m = re.match(r'((?:\\.|[^=:\s\\])+)\s*[=:\s]\s*(.*)$', line)
        if m:
            texts[unescape(m.group(1))] = unescape(m.group(2))
    return texts


def main():
    os.makedirs(OUT, exist_ok=True)
    english = {k: v for k, v in load(os.path.join(BUNDLES, 'messages.properties')).items() if k.startswith(PREFIXES)}
    for lang, name in (('en', 'messages.properties'), ('fr', 'messages_fr.properties')):
        own = load(os.path.join(BUNDLES, name))
        texts = {k: own.get(k, english[k]) for k in sorted(english)}
        with io.open(os.path.join(OUT, lang + '.json'), 'w', encoding='utf-8', newline='\n') as f:
            json.dump(texts, f, ensure_ascii=False, indent=1, sort_keys=True)
            f.write('\n')
        print(lang, len(texts), 'texts')


if __name__ == '__main__':
    main()
