package com.example.agentrelay

internal sealed interface PairingHandoffUiState {
    data class Review(val verified: VerifiedPairingAppLink) : PairingHandoffUiState

    data object Rejected : PairingHandoffUiState
}
