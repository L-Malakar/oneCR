package com.lmalakar.onecr.privilege.shizuku

import com.lmalakar.onecr.privilege.api.CaptureCapability
import com.lmalakar.onecr.privilege.api.PrivilegeMode
import com.lmalakar.onecr.privilege.api.PrivilegeProvider
import com.lmalakar.onecr.privilege.api.PrivilegeState

/**
 * Shizuku-backed PrivilegeProvider.
 *
 * M2 implementation plan:
 *  - dependency `rikka.shizuku:api` (+ `provider`) is added in M2 only;
 *  - `Shizuku.requestPermission(REQUEST_CODE = 1001)` then `Shizuku.bindUserService(...)`
 *    runs the capture dex as shell (UID 2000);
 *  - health(): `Shizuku.pingBinder()` + binder `linkToDeath` + user-service liveness;
 *  - PAIRING_LOST is inferred from binder death while wireless debugging is expected on.
 *
 * The stub below keeps the module graph compiling in M1.
 */
class ShizukuPrivilegeProvider : PrivilegeProvider {

    override val mode: PrivilegeMode = PrivilegeMode.SHIZUKU

    override suspend fun connect(): PrivilegeState {
        // TODO(M2): bind Shizuku user service, request permission, return OK/PAIRING_LOST.
        return PrivilegeState.NOT_CONFIGURED
    }

    override suspend fun health(): PrivilegeState {
        // TODO(M2): pingBinder + linkToDeath liveness.
        return PrivilegeState.NOT_CONFIGURED
    }

    override suspend fun probeCapture(): CaptureCapability {
        // TODO(M2): delegate to the shell capture service probe() over the pipe.
        return CaptureCapability(workingSources = emptyList(), sampleRateHz = 16_000)
    }

    override fun disconnect() {
        // TODO(M2): unbind user service.
    }
}
