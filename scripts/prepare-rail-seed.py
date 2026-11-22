#!/usr/bin/env python3
"""Build the deterministic N02 seed from the official UTF-8 archive."""
import gzip
import hashlib
import json
from pathlib import Path
import sys
from urllib.request import urlopen
from zipfile import ZipFile
from io import BytesIO

URL = 'https://nlftp.mlit.go.jp/ksj/gml/data/N02/N02-25/N02-25_GML.zip'
SHA256 = 'aaf76af133b2e771e538fabc4646d2e443dc1d5a67b221382a28d744e706cc9f'
target = Path(__file__).resolve().parents[1] / 'app/src/main/assets/rail-2025.tsv.gz'
if len(sys.argv) > 1:
    data = Path(sys.argv[1]).read_bytes()
else:
    with urlopen(URL, timeout=90) as response:
        data = response.read()
if hashlib.sha256(data).hexdigest() != SHA256:
    raise SystemExit('Unexpected N02 archive checksum')
with ZipFile(BytesIO(data)) as archive, target.open('wb') as file:
    with gzip.GzipFile(filename='', fileobj=file, mode='wb', mtime=0) as output:
        for suffix in ('RailroadSection', 'Station'):
            name = next(n for n in archive.namelist() if '/UTF-8/' in n and n.endswith(suffix + '.geojson'))
            for feature in json.loads(archive.read(name))['features']:
                props = feature['properties']
                coords = feature['geometry']['coordinates']
                fields = (props['N02_004'], props['N02_003'], props['N02_002'], props.get('N02_005', ''),
                          ';'.join(f'{lon:.6f},{lat:.6f}' for lon, lat in coords))
                output.write(('\t'.join(fields) + '\n').encode())
print(target, target.stat().st_size)
