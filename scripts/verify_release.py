#!/usr/bin/env python3
"""Verify a signed family-release candidate without installing it."""
import argparse, hashlib, json, re, subprocess, zipfile
from pathlib import Path

def run(*args):
    return subprocess.check_output(args, text=True, stderr=subprocess.STDOUT)

def verify(apk, tools, version, certificate, minimum_code):
    badging = run(str(tools / 'aapt'), 'dump', 'badging', str(apk))
    manifest = run(str(tools / 'aapt'), 'dump', 'xmltree', str(apk), 'AndroidManifest.xml')
    signature = run(str(tools / 'apksigner'), 'verify', '--verbose', '--print-certs', str(apk))
    package = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging)
    if not package or package[1] != 'com.siaovon.kanah': raise ValueError('Unexpected package')
    if package[3] != version or int(package[2]) < minimum_code: raise ValueError('Unexpected version or non-increasing version code')
    if 'application-debuggable' in badging or re.search(r'android:(debuggable|testOnly).*0xffffffff', manifest): raise ValueError('Debuggable/test-only APK')
    if not re.search(r'android:allowBackup.*0x0(?:\s|$)', manifest): raise ValueError('Backup must be disabled')
    certs = re.findall(r'Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]+)', signature)
    if certs != [certificate.lower()]: raise ValueError('Signer mismatch: upgrade compatibility not verified')
    with zipfile.ZipFile(apk) as z:
        names = z.namelist()
        forbidden = [n for n in names if n.lower().endswith(('.key','.p12','.jks','.keystore','.safetensors')) or any(x in n.lower() for x in ('password.txt','glowsans','mlkit','root-ca.'))]
        if forbidden: raise ValueError('Forbidden packaged material: '+str(forbidden))
        required = []
        if not all(n in names for n in required): raise ValueError('Missing ABI or dependency notice')
    return dict(apk=str(apk),sha256=hashlib.sha256(apk.read_bytes()).hexdigest(),package=package[1],version=package[3],versionCode=int(package[2]),certificateSha256=certs[0],checks='passed; does not replace device acceptance')

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('apk',type=Path);p.add_argument('--build-tools',required=True,type=Path)
    p.add_argument('--version',required=True);p.add_argument('--certificate',required=True)
    p.add_argument('--minimum-code',required=True,type=int)
    a=p.parse_args()
    try: print(json.dumps(verify(a.apk,a.build_tools,a.version,a.certificate,a.minimum_code),indent=2))
    except (ValueError,subprocess.CalledProcessError) as e: p.exit(1,str(e)+'\n')
if __name__=='__main__':main()
