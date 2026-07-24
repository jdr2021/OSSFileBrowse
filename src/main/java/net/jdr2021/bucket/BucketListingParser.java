package net.jdr2021.bucket;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.SAXParserFactory;
import java.io.StringReader;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Secure, namespace-aware parser for S3 and OSS ListBucketResult responses.
 */
public final class BucketListingParser {
    private static final int MAX_XML_CHARS = 16 * 1024 * 1024;
    private static final int MAX_OBJECTS_PER_PAGE = 100_000;

    private BucketListingParser() {
    }

    public static BucketListing parse(String xml) {
        if (xml == null || xml.trim().isEmpty()) {
            throw new IllegalArgumentException("存储桶响应为空");
        }
        if (xml.length() > MAX_XML_CHARS) {
            throw new IllegalArgumentException("存储桶响应超过解析上限");
        }
        String document = stripLeadingDocumentWhitespace(xml);

        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setXIncludeAware(false);

            XMLReader reader = factory.newSAXParser().getXMLReader();
            reader.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            reader.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            ListingHandler handler = new ListingHandler();
            reader.setContentHandler(handler);
            reader.setErrorHandler(handler);
            reader.parse(new InputSource(new StringReader(document)));
            return handler.toListing();
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("存储桶 XML 解析失败: " + e.getMessage(), e);
        }
    }

    private static String stripLeadingDocumentWhitespace(String xml) {
        int start = 0;
        if (!xml.isEmpty() && xml.charAt(0) == '\uFEFF') {
            start++;
        }
        while (start < xml.length() && Character.isWhitespace(xml.charAt(start))) {
            start++;
        }
        return start == 0 ? xml : xml.substring(start);
    }

    private static final class ListingHandler extends DefaultHandler {
        private final StringBuilder text = new StringBuilder();
        private final List<BucketObject> objects = new ArrayList<>();
        private boolean listBucketResult;
        private boolean inContents;
        private String name;
        private String prefix;
        private boolean truncated;
        private String nextMarker;
        private String nextContinuationToken;
        private String key;
        private long size = -1;
        private String etag;
        private Instant lastModified;

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes)
                throws SAXException {
            String element = elementName(localName, qName);
            text.setLength(0);
            if ("ListBucketResult".equals(element)) {
                listBucketResult = true;
            } else if ("Contents".equals(element)) {
                inContents = true;
                key = null;
                size = -1;
                etag = null;
                lastModified = null;
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            text.append(ch, start, length);
        }

        @Override
        public void endElement(String uri, String localName, String qName) throws SAXException {
            String element = elementName(localName, qName);
            String rawValue = text.toString();
            String value = rawValue.trim();
            if (inContents) {
                if ("Key".equals(element)) {
                    // Object keys are opaque values. Leading/trailing spaces are
                    // valid and must survive URL construction unchanged.
                    key = rawValue;
                } else if ("Size".equals(element)) {
                    size = parseLong(value);
                } else if ("ETag".equals(element)) {
                    etag = stripQuotes(value);
                } else if ("LastModified".equals(element)) {
                    lastModified = parseInstant(value);
                } else if ("Contents".equals(element)) {
                    inContents = false;
                    if (key != null && !key.isEmpty()) {
                        if (objects.size() >= MAX_OBJECTS_PER_PAGE) {
                            throw new SAXException("单页对象数量超过上限");
                        }
                        objects.add(new BucketObject(key, size, etag, lastModified));
                    }
                }
            } else {
                if ("Name".equals(element)) {
                    name = value;
                } else if ("Prefix".equals(element)) {
                    prefix = rawValue;
                } else if ("IsTruncated".equals(element)) {
                    truncated = Boolean.parseBoolean(value);
                } else if ("NextMarker".equals(element)) {
                    nextMarker = emptyToNull(rawValue);
                } else if ("NextContinuationToken".equals(element)) {
                    nextContinuationToken = emptyToNull(rawValue);
                }
            }
            text.setLength(0);
        }

        private BucketListing toListing() {
            if (!listBucketResult) {
                throw new IllegalArgumentException("响应根节点不是 ListBucketResult");
            }
            return new BucketListing(name, prefix, truncated, nextMarker,
                    nextContinuationToken, objects);
        }

        private static String elementName(String localName, String qName) {
            return localName == null || localName.isEmpty() ? qName : localName;
        }

        private static long parseLong(String value) {
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException e) {
                return -1;
            }
        }

        private static Instant parseInstant(String value) {
            try {
                return value.isEmpty() ? null : Instant.parse(value);
            } catch (DateTimeParseException e) {
                return null;
            }
        }

        private static String stripQuotes(String value) {
            if (value != null && value.length() >= 2
                    && value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"') {
                return value.substring(1, value.length() - 1);
            }
            return emptyToNull(value);
        }

        private static String emptyToNull(String value) {
            return value == null || value.isEmpty() ? null : value;
        }
    }
}
