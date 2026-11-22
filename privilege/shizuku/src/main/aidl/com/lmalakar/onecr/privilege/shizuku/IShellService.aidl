// oneCR shell bridge — runs INSIDE the Shizuku server process (UID 2000).
// Keep this surface minimal: it is the entire privileged code path (see ARCHITECTURE.md).
package com.lmalakar.onecr.privilege.shizuku;

interface IShellService {
    // Reserved destroy method required by the Shizuku server (do not reuse this code).
    void destroy() = 16777114;

    // Run a short command with sh and return combined stdout+stderr.
    // A trailing line "__onecr_exit__<code>" reports the exit status.
    String exec(String command) = 1;

    // Process UID as seen inside the privileged process (expect 2000 = shell).
    int getUid() = 2;
}
