#!/usr/bin/env python3
"""Generate a public, deterministic test vector independently of the Rust codec.

Optional developer tool: requires cryptography (BSD/Apache-2.0; not shipped or
required by the Rust build). All seeds below are public test fixtures, never identities.
"""
import hashlib
import json
from pathlib import Path
import struct
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
import cryptography

seeds=[bytes([n])*32 for n in [1,2,3]]
keys=[Ed25519PrivateKey.from_private_bytes(seed) for seed in seeds]
public=[key.public_key().public_bytes_raw() for key in keys]
body='vector 🌍';nonce=bytes(range(16));created=1000;expires=1600;budget=3
canonical=(b'NearbyMesh/public/v1\0'+bytes([1,1])+public[0]+nonce+
           struct.pack('>QQBI',created,expires,budget,len(body.encode()))+body.encode())
signature=keys[0].sign(canonical);mid=hashlib.sha256(canonical).digest()
packet={'message':dict(version=1,kind=1,id=mid.hex(),origin=public[0].hex(),nonce=nonce.hex(),created=created,
                      expires=expires,max_hops=budget,body=body,signature=signature.hex()),'route':[]}
previous=hashlib.sha256(b'NearbyMesh/route-root/v1\0'+mid+signature).digest()
hop_inputs=[]
for index in range(2):
    hop=b'NearbyMesh/hop/v1\0'+previous+public[index+1]
    signature=keys[index].sign(hop);hop_inputs.append(hop.hex())
    packet['route'].append(dict(to=public[index+1].hex(),signature=signature.hex()))
    previous=hashlib.sha256(hop+signature).digest()
fixture={'schema':1,'generator':'mesh/tools/protocol-vector.py (Python hashlib/struct + cryptography Ed25519)',
         'generation_library':cryptography.__version__,'test_seeds_hex':[seed.hex() for seed in seeds],
         'canonical_hex':canonical.hex(),'hop_inputs_hex':hop_inputs,'packet':packet}
path=Path(__file__).resolve().parents[1]/'tests/vectors/public-v1.json'
path.write_text(json.dumps(fixture,ensure_ascii=False,indent=2)+'\n')
print(path)
