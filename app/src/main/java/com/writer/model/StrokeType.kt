package com.writer.model

enum class StrokeType {
    FREEHAND,
    LINE,
    ARROW_HEAD,          // arrowhead at end (→)
    ARROW_TAIL,          // arrowhead at start (←)
    ARROW_BOTH,          // bidirectional (↔)
    ELLIPSE,
    RECTANGLE,
    ROUNDED_RECTANGLE,
    TRIANGLE,
    DIAMOND
}
