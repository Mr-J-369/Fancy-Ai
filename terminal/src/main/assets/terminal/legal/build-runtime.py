#!/usr/bin/env python3
"""Build the separate GPL PRoot executables into a NEW output directory.

Usage: python3 build-runtime.py /path/to/android-ndk-r29 /new/output/directory
Requires Linux x86_64, Python 3, make, and binutils (readelf). No network or git.
The adjacent source archives are the complete upstream corresponding sources.
"""
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import tempfile


def run(args, directory, environment):
    subprocess.run(args, cwd=directory, env=environment, check=True)


def main():
    ndk, output = map(lambda value: Path(value).resolve(), sys.argv[1:])
    output.mkdir(parents=True, exist_ok=False)
    source = Path(__file__).resolve().parent
    toolchain = ndk / 'toolchains/llvm/prebuilt/linux-x86_64/bin'
    cc = str(toolchain / 'aarch64-linux-android31-clang')
    ar = str(toolchain / 'llvm-ar')
    env = dict(os.environ, CC=cc, AR=ar, CFLAGS='-O2 -fPIC',
               LDFLAGS='-Wl,-z,max-page-size=16384,--undefined-version', PYTHONHASHSEED='1')
    with tempfile.TemporaryDirectory(prefix='fancy-proot-') as temporary:
        work = Path(temporary)
        for name, checksum in {
            'proot.tar.gz': '428c7f9dbb3178bf58956dfbb28449582193f45d2a2bec48851731fe56b525bf',
            'talloc.tar.gz': 'dc46c40b9f46bb34dd97fe41f548b0e8b247b77a918576733c528e83abd854dd',
        }.items():
            archive = source / name
            if hashlib.sha256(archive.read_bytes()).hexdigest() != checksum:
                raise ValueError(name)
            with tarfile.open(archive) as tar:
                tar.extractall(work, filter='data')
        talloc = work / 'talloc-2.4.3'
        proot = work / 'proot-7266fb3e8516535682f5a9c8f3a7e70f6506eddb' / 'src'
        answers = [
            'Checking simple C program', 'building library support',
            'Checking for large file support', 'Checking for -D_FILE_OFFSET_BITS=64',
            'Checking for C99 vsnprintf', 'Checking for HAVE_SECURE_MKSTEMP',
            'rpath library support', 'Checking correct behavior of strtoll',
            'Checking correct behavior of strptime', 'Checking for HAVE_IFACE_GETIFADDRS',
            'Checking for HAVE_IFACE_IFCONF', 'Checking for HAVE_IFACE_IFREQ',
            'Checking getconf LFS_CFLAGS', 'Checking for large file support without additional flags',
            'Checking for working strptime', 'Checking for HAVE_SHARED_MMAP',
            'Checking for HAVE_MREMAP', 'Checking for HAVE_INCOHERENT_MMAP',
            'Checking getconf large file support flags work',
        ]
        (talloc / 'cross-answers.txt').write_text(
            'Checking uname sysname type: "Linux"\nChecking uname machine type: "aarch64"\n'
            'Checking uname release type: "dontcare"\nChecking uname version type: "dontcare"\n'
            'Checking for WORDS_BIGENDIAN: FAIL\n-Wl,--version-script support: OK\n'
            + ''.join(answer + ': OK\n' for answer in answers), encoding='utf-8')
        run(['./configure', '--prefix=' + str(work / 'prefix'), '--disable-rpath', '--disable-python',
             '--cross-compile', '--cross-answers=cross-answers.txt'], talloc, env)
        run([sys.executable, 'buildtools/bin/waf', 'build', '--targets=talloc'], talloc, env)
        run([ar, 'rcs', str(work / 'libtalloc.a'), str(talloc / 'bin/default/talloc.c.6.o')], work, env)
        # Upstream Android source uses strcmp/memset but omits this required standard header.
        file = proot / 'extension/ashmem_memfd/ashmem_memfd.c'
        file.write_text(file.read_text(encoding='utf-8').replace(
            '#include <stdlib.h>', '#include <stdlib.h>\n#include <string.h>'), encoding='utf-8')
        run(['make', '-j4', 'GIT=false', 'CC=' + cc, 'PROOT_UNBUNDLE_LOADER=/unused',
             'CPPFLAGS=-D_FILE_OFFSET_BITS=64 -D_GNU_SOURCE -I. -I' + str(talloc) + ' -DARG_MAX=131072',
             'LDFLAGS=-L' + str(work) + ' -ltalloc -Wl,-z,noexecstack,-z,max-page-size=16384'], proot, env)
        for original, packaged in [('proot', 'libfancy_proot.so'), ('loader/loader', 'libfancy_proot_loader.so')]:
            shutil.copyfile(proot / original, output / packaged)
            run([str(toolchain / 'llvm-strip'), str(output / packaged)], work, env)
    print('Runtime executables written to', output)


if __name__ == '__main__':
    main()
