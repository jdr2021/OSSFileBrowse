package net.jdr2021.utils;

import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * @version 1.0
 * @Author jdr
 * @Date 2024-5-25 14:35
 * @注释
 */

public class SaxXMLParser {
    public static boolean isXMLData(String data) {
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            SAXParser saxParser = factory.newSAXParser();
            saxParser.parse(new ByteArrayInputStream(data.getBytes()), new DefaultHandler());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean containsListBucketResult(String data) {
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            SAXParser saxParser = factory.newSAXParser();
            saxParser.parse(new ByteArrayInputStream(data.getBytes()), new DefaultHandler() {
                boolean foundListBucketResult = false;

                @Override
                public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException {
                    if ("ListBucketResult".equals(qName)) {
                        foundListBucketResult = true;
                    }
                }
            });
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean containsAccessDenied(String data) {
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            SAXParser saxParser = factory.newSAXParser();
            saxParser.parse(new ByteArrayInputStream(data.getBytes()), new DefaultHandler() {
                boolean foundAccessDenied = false;

                @Override
                public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException {
                    if ("Code".equals(qName)) {
                        foundAccessDenied = true;
                    }
                }

                @Override
                public void characters(char[] ch, int start, int length) throws SAXException {
                    if (foundAccessDenied && new String(ch, start, length).equals("AccessDenied")) {
                        throw new SAXException("AccessDenied found");
                    }
                }
            });
            return true;
        } catch (SAXException e) {
            return true; // AccessDenied
        } catch (Exception e) {
            return false;
        }
    }

    public static String[] extractKeys(String xml) {
        List<String> values = new ArrayList<>();
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            SAXParser saxParser = factory.newSAXParser();
            saxParser.parse(new ByteArrayInputStream(xml.getBytes()), new DefaultHandler() {
                boolean inKeyElement = false;

                @Override
                public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException {
                    if ("Key".equals(qName)) {
                        inKeyElement = true;
                    }
                }

                @Override
                public void characters(char[] ch, int start, int length) throws SAXException {
                    if (inKeyElement) {
                        String key = new String(ch, start, length);
                        if (!key.endsWith("/")) {
                            values.add(key);
                        }
                    }
                }
                @Override
                public void endElement(String uri, String localName, String qName) throws SAXException {
                    if ("Key".equals(qName)) {
                        inKeyElement = false;
                    }
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
        return values.toArray(new String[0]);
    }
}
