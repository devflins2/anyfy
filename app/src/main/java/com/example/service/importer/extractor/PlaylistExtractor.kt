package com.example.service.importer.extractor

import com.example.service.importer.model.PlaylistImportData

interface PlaylistExtractor {
    suspend fun extractPlaylist(
        playlistId: String,
        onProgress: ((loaded: Int, total: Int) -> Unit)? = null
    ): Result<PlaylistImportData>
}

