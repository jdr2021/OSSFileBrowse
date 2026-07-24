package net.jdr2021.preview;

/**
 * Stable format identifiers used between the HTTP gateway and renderers.
 * Keep exact legacy/OOXML and archive variants separate so capability
 * negotiation never relies on file extensions after detection.
 */
public enum PreviewFormat {
    PDF,
    DOC,
    DOCX,
    XLS,
    XLSX,
    PPT,
    PPTX,
    TEXT,
    JSON,
    XML,
    MARKDOWN,
    CODE,
    IMAGE,
    HTML,
    VIDEO,
    AUDIO,
    ZIP,
    JAR,
    SEVEN_Z,
    RAR,
    TAR,
    GZIP,
    UNKNOWN
}
