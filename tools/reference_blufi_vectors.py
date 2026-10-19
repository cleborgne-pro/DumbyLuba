#!/usr/bin/env python3
"""Run the upstream methods without importing or running the rest of PyMammotion.
Usage: python3 tools/reference_blufi_vectors.py /path/to/PyMammotion
Expected revision documented in THIRD_PARTY.md; the AST extracts CRC and getPostBytes.
"""
import ast
import json
import logging
import sys
from io import BytesIO
from pathlib import Path

root = Path(sys.argv[1]) / 'pymammotion/bluetooth'
frame = ast.parse((root / 'data/framectrldata.py').read_text())
source = ast.parse((root / 'ble_message.py').read_text())
methods = []
for item in source.body:
    if isinstance(item, ast.Assign) and any(isinstance(t, ast.Name) and t.id == 'CRC_TB' for t in item.targets):
        methods.append(item)
    if isinstance(item, ast.ClassDef) and item.name == 'BleMessage':
        item.body = [m for m in item.body if isinstance(m, ast.FunctionDef) and m.name in ('calc_crc', 'getPostBytes')]
        methods.append(item)
namespace = dict(BytesIO=BytesIO, sys=sys, _LOGGER=logging.getLogger(__name__))
exec(compile(frame, 'framectrldata.py', 'exec'), namespace)
exec(compile(ast.Module(body=methods, type_ignores=[]), 'upstream-selected-methods', 'exec'), namespace)
cls = namespace['BleMessage']
obj = cls()
vectors = []
for sequence, data in [(0, bytes.fromhex('08f3011007180120012801300162020a007880d095ffbc31')), (255, bytes(range(64)))]:
    vectors.append(dict(sequence=sequence, payload=data.hex(), frame=obj.getPostBytes(0x4d, False, False, False, False, sequence, data).hex(), crc=cls.calc_crc(cls.calc_crc(0, bytes([sequence, len(data)])), data)))
print(json.dumps(vectors, indent=2))
