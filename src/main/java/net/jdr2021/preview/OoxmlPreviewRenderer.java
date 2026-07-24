package net.jdr2021.preview;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Enumeration;

/**
 * Dependency-light OOXML text renderer. It reads only the XML parts needed for
 * the visible document and applies strict entry/expanded-size limits.
 */
public final class OoxmlPreviewRenderer extends HtmlPreviewRenderer {
    private static final int MAX_ENTRIES = 4096;
    private static final long MAX_ENTRY_BYTES = 16L * 1024 * 1024;
    private static final long MAX_TOTAL_BYTES = 64L * 1024 * 1024;
    private static final int MAX_COMPRESSION_RATIO = 200;

    @Override
    public boolean supports(PreviewFormat format) {
        return format == PreviewFormat.DOCX || format == PreviewFormat.XLSX
                || format == PreviewFormat.PPTX;
    }

    @Override
    public PreviewResult render(PreviewRequest request) throws IOException {
        Map<String, byte[]> parts = readXmlParts(request.getData());
        String body;
        if (request.getFormat() == PreviewFormat.DOCX) {
            body = renderDocx(parts);
        } else if (request.getFormat() == PreviewFormat.XLSX) {
            body = renderXlsx(parts);
        } else {
            body = renderPptx(parts);
        }
        return page(request.getFileName(), "<h1>" + escape(request.getFileName()) +
                "</h1><div class=\"muted\">轻量 OOXML 文本视图 · " +
                humanSize(request.getDeclaredSize()) + "</div>" + body);
    }

    private static Map<String, byte[]> readXmlParts(byte[] archive) throws IOException {
        Map<String, byte[]> parts = new HashMap<String, byte[]>();
        ZipFile zip = ZipFile.builder()
                .setSeekableByteChannel(new SeekableInMemoryByteChannel(archive))
                .get();
        int count = 0;
        long total = 0;
        try {
            Enumeration<ZipArchiveEntry> entries = zip.getEntries();
            while (entries.hasMoreElements()) {
                ZipArchiveEntry entry = entries.nextElement();
                if (++count > MAX_ENTRIES) {
                    throw new IOException("OOXML entry count exceeds " + MAX_ENTRIES);
                }
                String name = normalizeEntryName(entry.getName());
                if (name == null || entry.isDirectory() || !isRelevantXml(name)) {
                    continue;
                }
                long declared = entry.getSize();
                long compressed = entry.getCompressedSize();
                validateExpandedSize(declared, compressed);
                InputStream input = zip.getInputStream(entry);
                byte[] bytes;
                try {
                    bytes = readBounded(input, MAX_ENTRY_BYTES);
                } finally {
                    input.close();
                }
                total += bytes.length;
                if (total > MAX_TOTAL_BYTES) {
                    throw new IOException("OOXML expanded XML exceeds 64 MiB");
                }
                parts.put(name, bytes);
            }
        } finally {
            zip.close();
        }
        return parts;
    }

    private static boolean isRelevantXml(String name) {
        return "word/document.xml".equals(name)
                || "xl/sharedStrings.xml".equals(name)
                || name.startsWith("xl/worksheets/sheet") && name.endsWith(".xml")
                || name.startsWith("ppt/slides/slide") && name.endsWith(".xml");
    }

    private static String renderDocx(Map<String, byte[]> parts) throws IOException {
        byte[] document = parts.get("word/document.xml");
        if (document == null) {
            return "<div class=\"card danger\">DOCX 中缺少 word/document.xml</div>";
        }
        final StringBuilder text = new StringBuilder();
        parseXml(document, new DefaultHandler() {
            private boolean inText;

            @Override
            public void startElement(String uri, String local, String qName, Attributes attributes) {
                String name = localName(local, qName);
                if ("t".equals(name)) inText = true;
                if ("tab".equals(name)) text.append('\t');
                if ("br".equals(name) || "cr".equals(name)) text.append('\n');
            }

            @Override
            public void characters(char[] ch, int start, int length) {
                if (inText) text.append(ch, start, length);
            }

            @Override
            public void endElement(String uri, String local, String qName) {
                String name = localName(local, qName);
                if ("t".equals(name)) inText = false;
                if ("p".equals(name)) text.append('\n');
            }
        });
        return "<pre>" + escape(text.toString().trim()) + "</pre>";
    }

    private static String renderXlsx(Map<String, byte[]> parts) throws IOException {
        final List<String> shared = new ArrayList<String>();
        byte[] sharedXml = parts.get("xl/sharedStrings.xml");
        if (sharedXml != null) {
            parseXml(sharedXml, new DefaultHandler() {
                private StringBuilder item;
                private boolean inText;

                @Override
                public void startElement(String uri, String local, String qName,
                                         Attributes attributes) {
                    String name = localName(local, qName);
                    if ("si".equals(name)) item = new StringBuilder();
                    if ("t".equals(name) && item != null) inText = true;
                }

                @Override
                public void characters(char[] ch, int start, int length) {
                    if (inText) item.append(ch, start, length);
                }

                @Override
                public void endElement(String uri, String local, String qName) {
                    String name = localName(local, qName);
                    if ("t".equals(name)) inText = false;
                    if ("si".equals(name) && item != null) {
                        shared.add(item.toString());
                        item = null;
                    }
                }
            });
        }

        List<String> sheets = sortedParts(parts, "xl/worksheets/sheet");
        if (sheets.isEmpty()) {
            return "<div class=\"card danger\">XLSX 中缺少工作表 XML</div>";
        }
        StringBuilder html = new StringBuilder();
        int sheetIndex = 0;
        for (String sheet : sheets) {
            final StringBuilder table = new StringBuilder("<table>");
            parseXml(parts.get(sheet), new DefaultHandler() {
                private String cellType;
                private String cellRef;
                private StringBuilder value;
                private boolean capture;

                @Override
                public void startElement(String uri, String local, String qName,
                                         Attributes attributes) {
                    String name = localName(local, qName);
                    if ("row".equals(name)) table.append("<tr>");
                    if ("c".equals(name)) {
                        cellType = attributes.getValue("t");
                        cellRef = attributes.getValue("r");
                        value = new StringBuilder();
                    }
                    if (("v".equals(name) || "t".equals(name)) && value != null) capture = true;
                }

                @Override
                public void characters(char[] ch, int start, int length) {
                    if (capture) value.append(ch, start, length);
                }

                @Override
                public void endElement(String uri, String local, String qName) {
                    String name = localName(local, qName);
                    if ("v".equals(name) || "t".equals(name)) capture = false;
                    if ("c".equals(name) && value != null) {
                        String raw = value.toString();
                        String displayed = raw;
                        if ("s".equals(cellType)) {
                            try {
                                int index = Integer.parseInt(raw.trim());
                                displayed = index >= 0 && index < shared.size()
                                        ? shared.get(index) : raw;
                            } catch (NumberFormatException ignored) {
                                displayed = raw;
                            }
                        }
                        table.append("<td title=\"").append(attribute(cellRef))
                                .append("\">").append(escape(displayed)).append("</td>");
                        value = null;
                    }
                    if ("row".equals(name)) table.append("</tr>");
                }
            });
            table.append("</table>");
            html.append("<section class=\"card\"><h2>工作表 ")
                    .append(++sheetIndex).append("</h2>").append(table).append("</section>");
        }
        return html.toString();
    }

    private static String renderPptx(Map<String, byte[]> parts) throws IOException {
        List<String> slides = sortedParts(parts, "ppt/slides/slide");
        if (slides.isEmpty()) {
            return "<div class=\"card danger\">PPTX 中缺少幻灯片 XML</div>";
        }
        StringBuilder html = new StringBuilder();
        int number = 0;
        for (String slide : slides) {
            final StringBuilder text = new StringBuilder();
            parseXml(parts.get(slide), new DefaultHandler() {
                private boolean inText;

                @Override
                public void startElement(String uri, String local, String qName,
                                         Attributes attributes) {
                    if ("t".equals(localName(local, qName))) inText = true;
                }

                @Override
                public void characters(char[] ch, int start, int length) {
                    if (inText) text.append(ch, start, length);
                }

                @Override
                public void endElement(String uri, String local, String qName) {
                    String name = localName(local, qName);
                    if ("t".equals(name)) {
                        inText = false;
                        text.append('\n');
                    }
                }
            });
            html.append("<section class=\"card\"><h2>幻灯片 ")
                    .append(++number).append("</h2><pre>")
                    .append(escape(text.toString().trim())).append("</pre></section>");
        }
        return html.toString();
    }

    private static void parseXml(byte[] xml, DefaultHandler handler) throws IOException {
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            XMLReader reader = factory.newSAXParser().getXMLReader();
            reader.setContentHandler(handler);
            reader.setEntityResolver((publicId, systemId) -> new InputSource(
                    new ByteArrayInputStream(new byte[0])));
            reader.parse(new InputSource(new ByteArrayInputStream(xml)));
        } catch (ParserConfigurationException e) {
            throw new IOException("Secure XML parser setup failed", e);
        } catch (SAXException e) {
            throw new IOException("Invalid OOXML part", e);
        }
    }

    private static String normalizeEntryName(String name) {
        if (name == null) return null;
        String value = name.replace('\\', '/');
        if (value.startsWith("/") || value.matches("^[A-Za-z]:.*")) return null;
        String[] segments = value.split("/");
        for (String segment : segments) {
            if ("..".equals(segment)) return null;
        }
        return value;
    }

    private static void validateExpandedSize(long size, long compressed) throws IOException {
        if (size > MAX_ENTRY_BYTES) {
            throw new IOException("OOXML entry exceeds 16 MiB");
        }
        if (size > 0 && compressed > 0 && size / Math.max(1, compressed) > MAX_COMPRESSION_RATIO) {
            throw new IOException("OOXML entry compression ratio exceeds limit");
        }
    }

    private static byte[] readBounded(InputStream input, long max) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > max) throw new IOException("OOXML entry exceeds memory limit");
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static List<String> sortedParts(Map<String, byte[]> parts, String prefix) {
        List<String> names = new ArrayList<String>();
        for (String name : parts.keySet()) {
            if (name.startsWith(prefix) && name.endsWith(".xml")) names.add(name);
        }
        Collections.sort(names, new Comparator<String>() {
            @Override
            public int compare(String left, String right) {
                return Integer.compare(numberInName(left), numberInName(right));
            }
        });
        return names;
    }

    private static int numberInName(String value) {
        int end = value.lastIndexOf('.');
        int start = end - 1;
        while (start >= 0 && Character.isDigit(value.charAt(start))) start--;
        try {
            return Integer.parseInt(value.substring(start + 1, end));
        } catch (RuntimeException ignored) {
            return Integer.MAX_VALUE;
        }
    }

    private static String localName(String local, String qName) {
        if (local != null && !local.isEmpty()) return local;
        int colon = qName == null ? -1 : qName.indexOf(':');
        return colon >= 0 ? qName.substring(colon + 1) : qName;
    }
}
