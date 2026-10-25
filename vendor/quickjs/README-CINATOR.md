# Vendored QuickJS source

Pinned from https://github.com/bellard/quickjs commit `04be246001599f5995fa2f2d8c91a0f198d3f34c` (reported version `2026-06-04`). Upstream QuickJS license is MIT; see `LICENSE`. Exact per-file hashes are in `PROVENANCE.json`.

This tree is built only into Cinator's narrow, isolated, authored-resolver runtime. It excludes `quickjs-libc.c`, the qjs command-line/std/os host and remote plugin scripts. Vendoring a JavaScript engine does not itself sandbox code or implement Nuvio/CloudStream compatibility. The Android service/IPC/deadline/memory tests and source admission rules are separate release gates.

Local modification: M2/M3/M4 opcode-packing macros use unsigned shifts and explicit int32_t conversion to remove an observed UBSan signed-shift failure while preserving the int vararg interface. PROVENANCE.json preserves upstreamSha256 and current sha256 for quickjs.c. Host release and UBSan suites passed after this patch; no blanket memory-safety certification is implied.

Do not replace these files from a mutable branch. Review any update, record a new immutable commit and hashes, re-run native and Android isolation tests, and retain license/source distribution obligations.
