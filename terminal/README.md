# Embedded Linux terminal

The `:terminal` library contains the Linux installer, PRoot launcher, JNI PTY,
session lifetime and a local xterm.js display. The app owns navigation and Compose
controls. No external terminal app, shell command alias, model download or database
is involved.

## Runtime and storage

- `files/terminal/environments/{debian,ubuntu}/rootfs`: installed Linux filesystems.
- The adjacent `.installed` marker is published after verified extraction.
- `files/terminal/workspace`: shared as `/workspace`; managed by the existing Files UI.
- `files/terminal/tmp`: PRoot runtime temporary files.
- Install progress and live sessions survive screen navigation, but not app process
  death. Commands are never automatically replayed. Linux files and shell history persist.

Archives are pinned by SHA-256 and hashed during download. Installation handles
regular files and deferred symlinks. Archive hard links become separate copies
with the source permissions because Android forbids apps from creating hard links.
Environment deletion walks without following symlinks. Debian
uses Docker Hub's public pull-token endpoint; no account credentials are needed.

Native PRoot and its loader are packaged as executable ELF files named `.so`,
separate from the small JNI PTY library. See `src/main/assets/terminal/legal` for
their complete corresponding sources, notices and rebuild command. Rebuild into
a new scratch directory; never overwrite published binaries implicitly.

The WebView loads only bundled assets from an intercepted private HTTPS origin.
It cannot navigate to external pages or access files/content URIs. Process output
is passed to xterm as binary terminal data, never interpreted as HTML or JS.

## Manual acceptance (user-operated)

Install each distribution, start a session, run `apt update`, install
`python3 python3-venv python3-pip git`, create a virtual environment and use pip.
Check interactive input, Ctrl-C, Tab, arrows, Unicode, copy/paste, keyboard resize,
multiple sessions, navigation, Files import/export, cancellation and deletion.
Run these on the intended release variant too: Android release process tracing
and native executable loading must work before shipping. Compilation alone does
not establish runtime or Google Play acceptance.
