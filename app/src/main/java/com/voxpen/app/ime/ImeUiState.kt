package com.voxpen.app.ime

import com.voxpen.app.data.model.VoiceCommand

sealed interface ImeUiState {
    data object Idle : ImeUiState
    data object Recording : ImeUiState
    data object Processing : ImeUiState
    data class Result(val text: String) : ImeUiState
    data class Refining(val original: String) : ImeUiState
    data class Refined(val original: String, val refined: String) : ImeUiState
    data class Error(val message: String) : ImeUiState
    data class CommandDetected(val command: VoiceCommand) : ImeUiState
    data class EditInstruction(val instruction: String) : ImeUiState
    data object Editing : ImeUiState
    data class EditResult(val revised: String) : ImeUiState
}
