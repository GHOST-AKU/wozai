package dev.ghost.nearbyim.next

fun draftSelectionCurrent(visiblePeer: String?, draftPeer: String?, controllerPeer: String?, loading: Boolean): Boolean =
    !loading && visiblePeer != null && visiblePeer == draftPeer && draftPeer == controllerPeer

fun observerNeedsImmediateRefresh(visible: NextState, selectedPeer: String?, connectedPeer: String?, connected: Boolean,
    connecting: Boolean, saving: Boolean, unhandledSave: Boolean): Boolean =
    selectedPeer != visible.selectedId || connectedPeer != visible.connectedId || connected != visible.connected ||
        connecting != visible.connecting || saving != visible.saving || unhandledSave
