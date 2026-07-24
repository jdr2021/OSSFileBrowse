package net.jdr2021.preview;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;

import java.io.IOException;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Detects a preview format by extension, magic bytes and container contents.
 * Files with an unrecognized suffix or without a suffix deliberately fall back
 * to plain text. For recognized suffixes, conclusive magic can refine or
 * correct the extension; an OOXML ZIP is refined by its entry names.
 */
public final class FormatDetector {
    private static final Map<String, PreviewFormat> EXTENSIONS =
            new HashMap<String, PreviewFormat>();

    static {
        register(PreviewFormat.PDF, "pdf");
        register(PreviewFormat.DOC, "doc", "dot");
        register(PreviewFormat.DOCX, "docx", "docm", "dotx", "dotm");
        register(PreviewFormat.XLS, "xls", "xlt");
        register(PreviewFormat.XLSX, "xlsx", "xlsm", "xltx", "xltm");
        register(PreviewFormat.PPT, "ppt", "pps", "pot");
        register(PreviewFormat.PPTX, "pptx", "pptm", "ppsx", "potx");
        register(PreviewFormat.TEXT, "txt", "log", "ini", "properties", "conf",
                "csv", "tsv", "srt", "vtt", "rtf");
        register(PreviewFormat.JSON, "json", "map");
        register(PreviewFormat.XML, "xml", "xsl", "xslt", "xsd");
        register(PreviewFormat.MARKDOWN, "md", "markdown");
        register(PreviewFormat.CODE, "java", "kt", "kts", "groovy", "js", "ts",
                "jsx", "tsx", "css", "scss", "less", "py", "rb", "go", "rs",
                "c", "h", "cpp", "hpp", "cs", "php", "sql", "sh", "bat",
                "cmd", "ps1", "yaml", "yml", "toml");
        register(PreviewFormat.IMAGE, "jpg", "jpeg", "png", "gif", "bmp", "webp",
                "svg", "ico", "jfif", "avif", "heic", "heif", "tif", "tiff");
        register(PreviewFormat.HTML, "html", "htm", "xhtml");
        register(PreviewFormat.VIDEO, "mp4", "m4v", "webm", "ogv", "mov");
        register(PreviewFormat.AUDIO, "mp3", "m4a", "aac", "wav", "flac", "ogg", "oga");
        register(PreviewFormat.ZIP, "zip");
        register(PreviewFormat.JAR, "jar", "war", "ear");
        register(PreviewFormat.SEVEN_Z, "7z");
        register(PreviewFormat.RAR, "rar");
        register(PreviewFormat.TAR, "tar");
        register(PreviewFormat.GZIP, "gz", "gzip", "tgz");
    }

    private FormatDetector() {
    }

    public static PreviewFormat detect(String fileName, String mimeType, byte[] bytes) {
        byte[] sample = bytes == null ? new byte[0] : bytes;
        String extension = extensionOf(fileName);
        PreviewFormat byExtension = EXTENSIONS.get(extension);
        if (byExtension == null) {
            return PreviewFormat.TEXT;
        }
        PreviewFormat hint = byExtension;

        PreviewFormat magic = detectMagic(sample, hint);
        if (magic != PreviewFormat.UNKNOWN) {
            return magic;
        }
        return byExtension;
    }

    private static PreviewFormat detectMagic(byte[] bytes, PreviewFormat hint) {
        if (startsWith(bytes, "%PDF-".getBytes())) {
            return PreviewFormat.PDF;
        }
        if (startsWith(bytes, new byte[]{(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0,
                (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1})) {
            if (hint == PreviewFormat.XLS || hint == PreviewFormat.PPT) {
                return hint;
            }
            return PreviewFormat.DOC;
        }
        if (startsWith(bytes, new byte[]{'P', 'K', 3, 4})
                || startsWith(bytes, new byte[]{'P', 'K', 5, 6})
                || startsWith(bytes, new byte[]{'P', 'K', 7, 8})) {
            PreviewFormat ooxml = refineZip(bytes);
            if (ooxml != PreviewFormat.ZIP) {
                return ooxml;
            }
            return hint == PreviewFormat.JAR || hint == PreviewFormat.DOCX
                    || hint == PreviewFormat.XLSX || hint == PreviewFormat.PPTX
                    ? hint : PreviewFormat.ZIP;
        }
        if (startsWith(bytes, new byte[]{'R', 'a', 'r', '!', 0x1A, 0x07})) {
            return PreviewFormat.RAR;
        }
        if (startsWith(bytes, new byte[]{0x37, 0x7A, (byte) 0xBC, (byte) 0xAF, 0x27, 0x1C})) {
            return PreviewFormat.SEVEN_Z;
        }
        if (startsWith(bytes, new byte[]{0x1F, (byte) 0x8B})) {
            return PreviewFormat.GZIP;
        }
        if (bytes.length > 262 && asciiEquals(bytes, 257, "ustar")) {
            return PreviewFormat.TAR;
        }
        if (startsWith(bytes, new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A})
                || startsWith(bytes, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF})
                || startsWith(bytes, "GIF87a".getBytes())
                || startsWith(bytes, "GIF89a".getBytes())
                || startsWith(bytes, "BM".getBytes())
                || (bytes.length >= 12 && asciiEquals(bytes, 0, "RIFF") && asciiEquals(bytes, 8, "WEBP"))) {
            return PreviewFormat.IMAGE;
        }
        if (bytes.length >= 12 && asciiEquals(bytes, 4, "ftyp")) {
            return hint == PreviewFormat.AUDIO ? PreviewFormat.AUDIO : PreviewFormat.VIDEO;
        }
        if (startsWith(bytes, "ID3".getBytes())
                || startsWith(bytes, "fLaC".getBytes())
                || startsWith(bytes, "OggS".getBytes())
                || (bytes.length >= 12 && asciiEquals(bytes, 0, "RIFF") && asciiEquals(bytes, 8, "WAVE"))) {
            return hint == PreviewFormat.VIDEO ? PreviewFormat.VIDEO : PreviewFormat.AUDIO;
        }
        String prefix = asciiPrefix(bytes, 512).trim().toLowerCase(Locale.ROOT);
        if (prefix.startsWith("<!doctype html") || prefix.startsWith("<html")
                || prefix.contains("<html")) {
            return PreviewFormat.HTML;
        }
        return PreviewFormat.UNKNOWN;
    }

    private static PreviewFormat refineZip(byte[] bytes) {
        // Central-directory inspection reads entry names without expanding content.
        // A truncated object sample simply falls back to ZIP or the caller's hint.
        ZipFile zip = null;
        try {
            zip = ZipFile.builder()
                    .setSeekableByteChannel(new SeekableInMemoryByteChannel(bytes))
                    .get();
            Enumeration<ZipArchiveEntry> entries = zip.getEntries();
            int count = 0;
            while (entries.hasMoreElements() && count++ < 256) {
                ZipArchiveEntry entry = entries.nextElement();
                String name = entry.getName().toLowerCase(Locale.ROOT);
                if (name.equals("word/document.xml")) {
                    return PreviewFormat.DOCX;
                }
                if (name.equals("xl/workbook.xml")) {
                    return PreviewFormat.XLSX;
                }
                if (name.equals("ppt/presentation.xml")) {
                    return PreviewFormat.PPTX;
                }
            }
        } catch (IOException ignored) {
            // Detection continues using the caller's extension/MIME hints.
        } finally {
            if (zip != null) {
                try {
                    zip.close();
                } catch (IOException ignored) {
                    // Format detection already has a useful result/fallback.
                }
            }
        }
        return PreviewFormat.ZIP;
    }

    private static void register(PreviewFormat format, String... extensions) {
        for (String extension : extensions) {
            EXTENSIONS.put(extension, format);
        }
    }

    private static String extensionOf(String fileName) {
        if (fileName == null) return "";
        int slash = Math.max(fileName.lastIndexOf('/'), fileName.lastIndexOf('\\'));
        int dot = fileName.lastIndexOf('.');
        return dot > slash && dot + 1 < fileName.length()
                ? fileName.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }

    private static boolean startsWith(byte[] value, byte[] prefix) {
        return value.length >= prefix.length
                && Arrays.equals(Arrays.copyOf(value, prefix.length), prefix);
    }

    private static boolean asciiEquals(byte[] bytes, int offset, String text) {
        if (offset < 0 || offset + text.length() > bytes.length) return false;
        for (int i = 0; i < text.length(); i++) {
            if ((bytes[offset + i] & 0xFF) != text.charAt(i)) return false;
        }
        return true;
    }

    private static String asciiPrefix(byte[] bytes, int max) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < Math.min(max, bytes.length); i++) {
            int value = bytes[i] & 0xFF;
            out.append(value >= 0x20 && value <= 0x7E ? (char) value : ' ');
        }
        return out.toString();
    }
}
