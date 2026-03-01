package com.writer.model

import java.util.UUID

class DocumentModel(
    val documentId: String = UUID.randomUUID().toString(),
    var language: String = "en-US"
) {
    val activeStrokes: MutableList<InkStroke> = mutableListOf()
}
