# Android private-file probe oracle correction

CI run `40246298223` / commit `9c0cfa921fd0ded2799cddd1e77b8b00ed0747c4` compiled and passed native-linked media regression and isolated-source tests. Debug containment passed Promise/timeout/OOM/stack/output, then failed because the test demanded EACCES/EPERM and observed ENOENT.

This is not a passing permission-denial result. Android 15's [ProcessList.java](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r1/services/core/java/com/android/server/am/ProcessList.java) explicitly omits app data mounts for isolated processes (`pkgDataInfoMap = null`, `allowlistedAppDataInfoMap = null`, lines2500–2504 of the retrieved tag). Namespace invisibility is therefore a distinct possible access barrier.

The corrected test must compare the service's fixed derived marker path with the parent's exact path, verify that the parent can read the marker before and after the service attempt, and record either explicit permission denial or ENOENT/invisibility without conflating them. A mismatched path, missing parent marker, timeout, I/O error or successful read fails. No arbitrary path input is added to IPC. This proves only that the tested marker was inaccessible from the isolated service, not all filesystem paths or the exact kernel mechanism.

Archive instrumentation did not run in this failed run because the containment gate stopped the serial emulator script. No new release has been published from the failed candidate.
