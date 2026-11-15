#!/usr/bin/env python3
"""Lê os .proto do Meshtastic (subconjunto da sintaxe proto3) e produz:
  - esquema JSON para o codec em JavaScript da app (só as mensagens pedidas e as que elas usam)
  - FileDescriptorProto para o protobuf do Python servir de oráculo nos testes
Uso: proto2schema.py <pasta dos .proto> <saida.json> [--fdp saida.bin]

GEOREDE app — Copyright (C) 2026 David Azevedo — GPL-3.0 (ver LICENSE).
Os .proto de entrada são do Meshtastic (https://github.com/meshtastic/protobufs, GPL-3.0).
"""
import re, sys, json, os

SCALARS = {'double','float','int32','int64','uint32','uint64','sint32','sint64','fixed32','fixed64',
           'sfixed32','sfixed64','bool','string','bytes'}

def limpar(txt):
    txt = re.sub(r'/\*.*?\*/', '', txt, flags=re.S)
    txt = re.sub(r'//[^\n]*', '', txt)
    return txt

TOK = re.compile(r'"(?:[^"\\]|\\.)*"|[A-Za-z_][A-Za-z0-9_.]*|-?0[xX][0-9a-fA-F]+|-?\d+|[{}=;\[\],<>()]')

def tokens(txt):
    return TOK.findall(limpar(txt))

class Parser:
    def __init__(self, toks):
        self.t = toks; self.i = 0
    def peek(self, k=0): return self.t[self.i+k] if self.i+k < len(self.t) else None
    def next(self):
        v = self.t[self.i]; self.i += 1; return v
    def expect(self, v):
        got = self.next()
        assert got == v, f'esperava {v}, veio {got} (pos {self.i})'
    def skip_stmt(self):
        depth = 0
        while True:
            v = self.next()
            if v == '{': depth += 1
            elif v == '}':
                depth -= 1
                if depth == 0: return
            elif v == ';' and depth == 0: return
    def skip_options_bracket(self):
        if self.peek() == '[':
            while self.next() != ']': pass
    def file(self):
        out = {'package': '', 'imports': [], 'messages': {}, 'enums': {}}
        while self.peek() is not None:
            v = self.peek()
            if v == 'syntax': self.skip_stmt()
            elif v == 'package': self.next(); out['package'] = self.next(); self.expect(';')
            elif v == 'import':
                self.next()
                if self.peek() == 'public': self.next()
                out['imports'].append(self.next().strip('"')); self.expect(';')
            elif v == 'option': self.skip_stmt()
            elif v == 'message': self.next(); n, m = self.message(''); out['messages'][n] = m
            elif v == 'enum': self.next(); n, e = self.enum(''); out['enums'][n] = e
            else: self.skip_stmt()
        return out
    def enum(self, prefix):
        name = self.next(); full = prefix + name; self.expect('{'); vals = {}
        while self.peek() != '}':
            if self.peek() in ('option', 'reserved'): self.skip_stmt(); continue
            k = self.next(); self.expect('='); v = int(self.next(), 0); self.skip_options_bracket(); self.expect(';')
            vals[k] = v
        self.expect('}')
        return full, vals
    def message(self, prefix):
        name = self.next(); full = prefix + name; self.expect('{')
        fields = []; nested = {}; enums = {}
        while self.peek() != '}':
            v = self.peek()
            if v == 'message': self.next(); n, m = self.message(full + '.'); nested[n] = m
            elif v == 'enum': self.next(); n, e = self.enum(full + '.'); enums[n] = e
            elif v in ('option', 'reserved', 'extensions'): self.skip_stmt()
            elif v == 'oneof':
                self.next(); oname = self.next(); self.expect('{')
                while self.peek() != '}':
                    if self.peek() == 'option': self.skip_stmt(); continue
                    fields.append(self.field(oneof=oname))
                self.expect('}')
            elif v == 'map':
                self.next(); self.expect('<'); kt = self.next(); self.expect(','); vt = self.next(); self.expect('>')
                fn = self.next(); self.expect('='); num = int(self.next()); self.skip_options_bracket(); self.expect(';')
                fields.append({'name': fn, 'num': num, 'type': 'map', 'key': kt, 'value': vt, 'label': 'repeated'})
            else:
                fields.append(self.field())
        self.expect('}')
        return full, {'fields': fields, 'nested': nested, 'enums': enums}
    def field(self, oneof=None):
        label = 'optional' if oneof else 'single'
        if self.peek() in ('repeated', 'optional', 'required'):
            label = self.next()
        typ = self.next(); name = self.next(); self.expect('='); num = int(self.next())
        self.skip_options_bracket(); self.expect(';')
        f = {'name': name, 'num': num, 'type': typ, 'label': label}
        if oneof: f['oneof'] = oneof
        return f

def carregar(pasta):
    files = {}
    for fn in sorted(os.listdir(pasta)):
        if fn.endswith('.proto'):
            files[fn] = Parser(tokens(open(os.path.join(pasta, fn), encoding='utf-8').read())).file()
    return files

def indexar(files):
    """nome qualificado (sem o pacote) -> ('message'|'enum', def, ficheiro)"""
    idx = {}
    def walk_msg(name, m, fn):
        idx[name] = ('message', m, fn)
        for n, mm in m['nested'].items(): walk_msg(n, mm, fn)
        for n, e in m['enums'].items(): idx[n] = ('enum', e, fn)
    for fn, f in files.items():
        for n, m in f['messages'].items(): walk_msg(n, m, fn)
        for n, e in f['enums'].items(): idx[n] = ('enum', e, fn)
    return idx

def resolver(typ, scope, idx):
    """Resolve um nome de tipo como o protoc: do âmbito mais interior para o exterior."""
    if typ in SCALARS: return typ, 'scalar'
    t = typ[len('meshtastic.'):] if typ.startswith('meshtastic.') else typ
    partes = scope.split('.') if scope else []
    for k in range(len(partes), -1, -1):
        cand = '.'.join(partes[:k] + [t]) if k else t
        if cand in idx: return cand, idx[cand][0]
    raise KeyError(f'tipo {typ} não encontrado a partir de {scope}')

def esquema(files, raizes, omitir=()):
    """Esquema JSON só com as mensagens pedidas e tudo o que elas referem (fecho transitivo)."""
    idx = indexar(files); out = {'messages': {}, 'enums': {}}; fila = list(raizes)
    while fila:
        n = fila.pop()
        if n in out['messages']: continue
        kind, m, _ = idx[n]; assert kind == 'message', n
        campos = []
        for f in m['fields']:
            if f['type'] == 'map': continue
            if (n, f['name']) in omitir: continue
            rt, rk = resolver(f['type'], n, idx)
            c = {'name': f['name'], 'num': f['num'], 'type': rt if rk != 'scalar' else f['type'],
                 'kind': rk, 'repeated': f['label'] == 'repeated'}
            if 'oneof' in f: c['oneof'] = f['oneof']
            elif f['label'] == 'optional': c['optional'] = True
            campos.append(c)
            if rk == 'message': fila.append(rt)
            elif rk == 'enum': out['enums'][rt] = idx[rt][1]
        out['messages'][n] = campos
    return out

# ---- oráculo: FileDescriptorProto para o protobuf do Python
def descriptor_set(files):
    from google.protobuf import descriptor_pb2 as D
    TYPES = {'double': D.FieldDescriptorProto.TYPE_DOUBLE, 'float': D.FieldDescriptorProto.TYPE_FLOAT,
             'int32': D.FieldDescriptorProto.TYPE_INT32, 'int64': D.FieldDescriptorProto.TYPE_INT64,
             'uint32': D.FieldDescriptorProto.TYPE_UINT32, 'uint64': D.FieldDescriptorProto.TYPE_UINT64,
             'sint32': D.FieldDescriptorProto.TYPE_SINT32, 'sint64': D.FieldDescriptorProto.TYPE_SINT64,
             'fixed32': D.FieldDescriptorProto.TYPE_FIXED32, 'fixed64': D.FieldDescriptorProto.TYPE_FIXED64,
             'sfixed32': D.FieldDescriptorProto.TYPE_SFIXED32, 'sfixed64': D.FieldDescriptorProto.TYPE_SFIXED64,
             'bool': D.FieldDescriptorProto.TYPE_BOOL, 'string': D.FieldDescriptorProto.TYPE_STRING,
             'bytes': D.FieldDescriptorProto.TYPE_BYTES}
    idx = indexar(files); fds = D.FileDescriptorSet()
    for fn, f in files.items():
        fdp = fds.file.add(); fdp.name = 'meshtastic/' + fn; fdp.package = 'meshtastic'; fdp.syntax = 'proto3'
        for imp in f['imports']:
            if imp.startswith('meshtastic/'): fdp.dependency.append(imp)
        def add_enum(container, name, vals):
            e = container.add(); e.name = name.split('.')[-1]
            for k, v in vals.items(): x = e.value.add(); x.name = k; x.number = v
        def add_msg(container, name, m):
            d = container.add(); d.name = name.split('.')[-1]
            oneofs = {}; sinteticos = []
            for fld in m['fields']:
                fd = d.field.add(); fd.name = fld['name']; fd.number = fld['num']
                if fld['type'] == 'map':
                    # mapa = mensagem aninhada *Entry repetida
                    en = ''.join(p.capitalize() for p in fld['name'].split('_')) + 'Entry'
                    me = d.nested_type.add(); me.name = en; me.options.map_entry = True
                    for i, (nm, tp) in enumerate((('key', fld['key']), ('value', fld['value'])), 1):
                        kf = me.field.add(); kf.name = nm; kf.number = i; kf.label = D.FieldDescriptorProto.LABEL_OPTIONAL
                        if tp in SCALARS: kf.type = TYPES[tp]
                        else:
                            rt, rk = resolver(tp, name, idx)
                            kf.type = D.FieldDescriptorProto.TYPE_MESSAGE if rk == 'message' else D.FieldDescriptorProto.TYPE_ENUM
                            kf.type_name = '.meshtastic.' + rt
                    fd.label = D.FieldDescriptorProto.LABEL_REPEATED; fd.type = D.FieldDescriptorProto.TYPE_MESSAGE
                    fd.type_name = '.meshtastic.' + name + '.' + en
                    continue
                fd.label = D.FieldDescriptorProto.LABEL_REPEATED if fld['label'] == 'repeated' else D.FieldDescriptorProto.LABEL_OPTIONAL
                if fld['type'] in SCALARS: fd.type = TYPES[fld['type']]
                else:
                    rt, rk = resolver(fld['type'], name, idx)
                    fd.type = D.FieldDescriptorProto.TYPE_MESSAGE if rk == 'message' else D.FieldDescriptorProto.TYPE_ENUM
                    fd.type_name = '.meshtastic.' + rt
                if 'oneof' in fld:
                    if fld['oneof'] not in oneofs:
                        oneofs[fld['oneof']] = len(d.oneof_decl); d.oneof_decl.add().name = fld['oneof']
                    fd.oneof_index = oneofs[fld['oneof']]
                elif fld['label'] == 'optional':
                    fd.proto3_optional = True; sinteticos.append(fd)
            for fd in sinteticos:
                fd.oneof_index = len(d.oneof_decl); d.oneof_decl.add().name = '_' + fd.name
            for n2, m2 in m['nested'].items(): add_msg(d.nested_type, n2, m2)
            for n2, e2 in m['enums'].items(): add_enum(d.enum_type, n2, e2)
        for n, m in f['messages'].items(): add_msg(fdp.message_type, n, m)
        for n, e in f['enums'].items(): add_enum(fdp.enum_type, n, e)
    return fds

RAIZES = ['ToRadio', 'FromRadio']
# Nunca entregar a chave do canal à app: o campo psk fica de fora do esquema (o descodificador salta-o)
OMITIR = {('ChannelSettings', 'psk'),
          # fora do esquema da app (não precisa deles; o descodificador salta campos desconhecidos)
          ('FromRadio', 'config'), ('FromRadio', 'moduleConfig'), ('FromRadio', 'log_record'), ('FromRadio', 'xmodemPacket'),
          ('FromRadio', 'fileInfo'), ('FromRadio', 'deviceuiConfig'), ('FromRadio', 'region_presets'),
          ('FromRadio', 'display_frame'), ('FromRadio', 'display_palette'), ('FromRadio', 'lockdown_status'),
          ('FromRadio', 'mqttClientProxyMessage'), ('ToRadio', 'xmodemPacket'), ('ToRadio', 'mqttClientProxyMessage'),
          ('Telemetry', 'environment_metrics'), ('Telemetry', 'air_quality_metrics'), ('Telemetry', 'power_metrics'),
          ('Telemetry', 'local_stats'), ('Telemetry', 'health_metrics'), ('Telemetry', 'host_metrics'),
          ('NodeInfo', 'user_lite'), ('Telemetry', 'traffic_management_stats'), ('Telemetry', 'soil_water_metrics'),
          ('Routing', 'route_request'), ('Routing', 'route_reply'), ('DeviceMetadata', 'display'),
          ('ClientNotification', 'key_verification_number_inform'), ('ClientNotification', 'key_verification_number_request'),
          ('ClientNotification', 'key_verification_final'), ('ClientNotification', 'duplicated_public_key'),
          ('ClientNotification', 'low_entropy_key') }
RAIZES_EXTRA = ['Telemetry', 'Routing', 'Position', 'User']   # vão dentro de Data.payload

if __name__ == '__main__':
    pasta, saida = sys.argv[1], sys.argv[2]
    files = carregar(pasta)
    sch = esquema(files, RAIZES + RAIZES_EXTRA, OMITIR)
    json.dump({'messages': sch['messages']}, open(saida, 'w'), separators=(',', ':'))
    print('mensagens:', len(sch['messages']), 'enums:', len(sch['enums']), '->', saida, os.path.getsize(saida), 'bytes')
    if '--fdp' in sys.argv:
        out = sys.argv[sys.argv.index('--fdp') + 1]
        open(out, 'wb').write(descriptor_set(files).SerializeToString()); print('descritor ->', out)
