package net.jdr2021.utils;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.w3c.dom.Element;

/**
 * @version 1.0
 * @Author jdr
 * @Date 2024-5-24 21:32
 * @注释
 */

public class XMLParser {

    public static boolean isXMLData(String data) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();

            Document doc = builder.parse(new ByteArrayInputStream(data.getBytes("UTF-8")));

            // 检查根节点是否为空
            if (doc.getDocumentElement() != null) {
                // 解析成功，是 XML 数据
                return true;
            } else {
                // 解析成功，但根节点为空
                return false;
            }
        } catch (Exception e) {
            // 解析失败，不是 XML 数据
            return false;
        }
    }

    public static boolean containsListBucketResult(String data) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new ByteArrayInputStream(data.getBytes()));

            // 查找 ListBucketResult 的节点
            NodeList nodeList = doc.getElementsByTagName("ListBucketResult");
            return nodeList.getLength() > 0;
        } catch (Exception e) {
            // 解析失败或不包含 ListBucketResult
            return false;
        }
    }

    public static boolean containsAccessDenied(String data) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new ByteArrayInputStream(data.getBytes()));

            // 查找所有Code节点，并检查其值是否为 AccessDenied
            NodeList nodeList = doc.getElementsByTagName("Code");
            for (int i = 0; i < nodeList.getLength(); i++) {
                if (nodeList.item(i).getTextContent().equals("AccessDenied")) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            // 解析失败或不包含 AccessDenied
            return false;
        }
    }
    //获取所有的key，也就是文件路径

    public static String[] extractKeys(String xml) {
        List<String> values = new ArrayList<>();
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new ByteArrayInputStream(xml.getBytes()));

            NodeList keys = doc.getElementsByTagName("Key");

            for (int i = 0; i < keys.getLength(); i++) {
                Element keyElement = (Element) keys.item(i);
                String key = keyElement.getTextContent();

                if (!key.endsWith("/")) { // 过滤掉结尾为 '/' 的键值
                    values.add(key);
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
        return values.toArray(new String[0]);
    }
}
