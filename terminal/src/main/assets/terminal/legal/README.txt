FancyAI terminal runtime sources and notices

PRoot is a separate executable, not linked into FancyAI's JNI library.
Source: https://github.com/termux/proot
Revision: 7266fb3e8516535682f5a9c8f3a7e70f6506eddb
Archive SHA-256: 428c7f9dbb3178bf58956dfbb28449582193f45d2a2bec48851731fe56b525bf
License: GNU GPL v2 or later (see the COPYING/license texts in proot.tar.gz).
The executable statically links talloc 2.4.3, LGPL v3 or later.
Source: https://www.samba.org/ftp/talloc/talloc-2.4.3.tar.gz
Archive SHA-256: dc46c40b9f46bb34dd97fe41f548b0e8b247b77a918576733c528e83abd854dd
See talloc.tar.gz for complete source and license texts. The combined PRoot
binary is conveyed under GPL v3 or later to satisfy the LGPL v3 dependency.
The complete sources and build instructions accompany every APK here and
can be exported from Terminal's menu. No source download is required.

Build: Linux x86_64, Android NDK 29.0.14206865, API 31, ARM64, 16 KB ELF alignment.
Run build-runtime.py with the NDK path and a new output path. It refuses to
overwrite an existing output directory. It builds only talloc and PRoot.
Only PRoot source change: add missing <string.h> in ashmem_memfd.c.
The PRoot loader is packaged separately in the APK's executable native library
directory and selected through upstream's PROOT_LOADER environment variable.

Terminal display: xterm.js 6.0.0 and addon-fit 0.11.0 (MIT).
https://github.com/xtermjs/xterm.js
https://registry.npmjs.org/@xterm/xterm/-/xterm-6.0.0.tgz
https://registry.npmjs.org/@xterm/addon-fit/-/addon-fit-0.11.0.tgz
The original MIT copyright/license notices accompany this export.

Linux environments are downloaded only when the user selects Download.
Ubuntu: Canonical Ubuntu Base 24.04.4, ARM64.
https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/
Debian: Debian 13 slim, ARM64 official debuerreotype image layer.
https://github.com/debuerreotype/docker-debian-artifacts
Rootfs revision: f73bd086e8d0e5e1c8b838ccc442bf24eb3ea205 (trixie/slim)
Packages retain their own licenses and /usr/share/doc copyright notices.
Users can obtain package source with the distribution's source repositories.

PRoot provides filesystem translation, not a security sandbox. Commands run
with FancyAI's Android UID and permissions. Android process limits still apply.
PRoot does not provide a separate kernel, systemd, or automatic GPU support.
