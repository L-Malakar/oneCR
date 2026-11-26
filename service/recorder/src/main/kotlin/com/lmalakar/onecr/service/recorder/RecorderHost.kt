package com.lmalakar.onecr.service.recorder

/**
 * Bridge [RecordingService] uses to reach the app's single [RecordingEngine]
 * — the same inversion [com.lmalakar.onecr.service.detection.DetectionServiceHost]
 * uses for detection: :app sets [engineProvider] at process start
 * (AppContainer.init) because the recorder module must not depend on :app.
 */
object RecorderHost {
    var engineProvider: (() -> RecordingEngine)? = null
}
