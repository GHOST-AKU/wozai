package dev.ghost.nearbyim.next

/** One foreground conversation may request the existing private session slot. */
internal class PrivateAutoLink {
    var peer: String? = null
        private set
    var attempts = 0
        private set
    var nextAttempt = 0L
        private set
    var paused = false
        private set
    val exhausted: Boolean get() = attempts >= 4
    private var ready = false

    fun select(peer: String?, local: String, now: Long) {
        if (this.peer == peer) return
        this.peer = peer
        ready = false
        reset(now + if (peer != null && local > peer) 1_200 else 150)
    }

    fun reset(due: Long) {
        attempts = 0
        paused = false
        nextAttempt = due
    }

    fun pause() { paused = true }

    fun resume(now: Long) {
        if (exhausted && !paused) reset(now + 750)
    }

    fun observeReady(connected: Boolean, now: Long) {
        if (connected && !ready) reset(now + 1_000)
        ready = connected
    }

    fun attempted(now: Long, jitter: Long) {
        nextAttempt = now + (2_000L shl attempts) + jitter.coerceIn(0, 1_000)
        attempts++
    }
}

/** A later READY may release only this explicit press, to the same proof and draft. */
internal data class ConnectAndSend(
    val peer: String,
    val root: String,
    val generation: String?,
    val body: String,
    val expiresAt: Long,
) {
    fun stillPending(currentPeer: String?, draft: String, rememberedRoot: String?, readyGeneration: String?, now: Long): Boolean =
        currentPeer == peer && draft == body && now < expiresAt &&
            if (generation == null) rememberedRoot == root else readyGeneration == generation

    fun matches(provenRoot: String?, provenGeneration: String?): Boolean =
        provenRoot == root && provenGeneration != null && (generation == null || generation == provenGeneration)
}


internal fun stopsAutomaticReconnect(status: String, error: String): Boolean =
    status in setOf("peerEnded", "consentTimeout", "trustRevokedCanceled", "androidStorageStopped",
        "identityChangedRejected", "identityMismatch", "attachmentUpgradeRequired") ||
        error in setOf("androidIdentityChanged", "identityVerificationFailed", "androidStorageFailure", "identityUnavailableRestart")
