package net.jdr2021.controller;

import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.web.WebView;
import net.jdr2021.utils.Base64Utils;
import net.jdr2021.utils.ConfigLoader;
import net.jdr2021.utils.HttpUtils;
import net.jdr2021.utils.XMLParser;
import java.io.IOException;
import java.net.URL;

/**
 * @version 1.0
 * @Author jdr
 * @Date 2024-5-23 14:25
 * @注释
 */

public class mainController{


    @FXML
    private TextField tf_kkfileview_url,tf_oss_url;
    @FXML
    private WebView webView;
    @FXML
    private TreeView treeView;
    @FXML
    private TreeItem<String> root;

    private String[] keys;
    private String[] fileUrls; // 存储每个文件的完整 URL
    private int currentIndex = 0;

    //String imageExtensions = ConfigLoader.getProperty("image.extensions");
    String allowExtensions = ConfigLoader.getProperty("allow.extensions");
    String kkFileView_URL = ConfigLoader.getProperty("kkFileView_URL");
    String api = "/onlinePreview?url=";

    //初始化
    @FXML
    private void initialize(){
        if(kkFileView_URL.endsWith("/")){
            kkFileView_URL = kkFileView_URL;
        }else{
            kkFileView_URL = kkFileView_URL + "/";
        }
        tf_kkfileview_url.setText(kkFileView_URL);
    }

    @FXML
    protected void Loading() throws IOException {
        String oss_url = tf_oss_url.getText();
        String responseData = HttpUtils.httpGet(oss_url);
        if(oss_url.endsWith("/")){
            oss_url = oss_url;
        }else{
            oss_url = oss_url + "/";
        }
        System.out.println(responseData);
        if (XMLParser.isXMLData(responseData)) {
            if (XMLParser.containsListBucketResult(responseData)) {
                keys = XMLParser.extractKeys(responseData);
//                for (String key : keys) {
//                    System.out.println(tf_kkfileview_url.getText()+key);
//                }
                if (keys.length > 0) {
                    constructFileUrls(oss_url);
                    loadNextFile();
                } else {
                    System.out.println("存储桶为空");
                    showAlert(Alert.AlertType.INFORMATION, "提示", null, "存储桶为空");
                }
            } else if (XMLParser.containsAccessDenied(responseData)) {
                // responseData 包含 <Code>AccessDenied</Code> 标签
                System.out.println("存储桶禁止访问");
                showAlert(Alert.AlertType.INFORMATION, "提示", null, "存储桶禁止访问");
            } else {
                // 其他情况
                System.out.println("不是存储桶");
                showAlert(Alert.AlertType.INFORMATION, "提示", null, "不是存储桶");
            }
        } else {
            // responseData 不是 XML 格式的数据
            System.out.println("存储桶遍历漏洞不存在");
            showAlert(Alert.AlertType.INFORMATION, "提示", null, "存储桶遍历漏洞不存在");
        }
    }
    @FXML
    protected void PreviousFile() {
        if (fileUrls != null && currentIndex > 0) {
            currentIndex--;
            loadPreviousFile();
        } else {
            System.out.println("已经是第一个文件");
            showAlert(Alert.AlertType.INFORMATION, "提示", null, "已经是第一个文件");
        }
    }

    @FXML
    protected void NextFile() {
        if (fileUrls != null && currentIndex < fileUrls.length - 1) {
            currentIndex++;
            loadNextFile();
        } else {
            System.out.println("已加载所有文件");
            showAlert(Alert.AlertType.INFORMATION, "提示", null, "已加载所有文件");
        }
    }

    // 显示下一个文件
//    private void loadNextFile() {
//        while (currentIndex < fileUrls.length) {
//            String nextFileUrl = fileUrls[currentIndex];
//            String extension = getFileExtension(nextFileUrl);
//            if (allowExtensions.contains(extension.toLowerCase())) {
//                // 如果允许加载这个文件的扩展名，则在 WebView 中加载
//                System.out.println("加载第"+currentIndex+"个资源："+nextFileUrl);
//                webView.setPrefSize(800,800);
//                webView.getEngine().load(tf_kkfileview_url.getText()+api+Base64Utils.encode(nextFileUrl));
//                currentIndex++;
//                return;
//            } else {
//                // 否则加载下一个文件
//                currentIndex++;
//            }
//        }
//        System.out.println("已加载所有文件");
//        showAlert(Alert.AlertType.INFORMATION, "提示", null, "已加载所有文件");
//    }
//    // 显示上一个文件
//    private void loadPreviousFile() {
//        while (currentIndex >= 0) {
//            String previousFileUrl = fileUrls[currentIndex];
//            String extension = getFileExtension(previousFileUrl);
//            if (allowExtensions.contains(extension.toLowerCase())) {
//                // 如果允许加载这个文件的扩展名，则在 WebView 中加载
//                System.out.println("加载第" + currentIndex + "个资源：" + previousFileUrl);
//                webView.setPrefSize(800, 800);
//                webView.getEngine().load(tf_kkfileview_url.getText() + api + Base64Utils.encode(previousFileUrl));
//                return;
//            } else {
//                // 否则加载上一个文件
//                currentIndex--;
//            }
//        }
//        System.out.println("已加载所有文件");
//        showAlert(Alert.AlertType.INFORMATION, "提示", null, "已加载所有文件");
//    }
    private void loadNextFile() {
        while (currentIndex < fileUrls.length) {
            String nextFileUrl = fileUrls[currentIndex];
            String extension = getFileExtension(nextFileUrl);

            // 检查 allowExtensions 是否为空
            if (allowExtensions.isEmpty()) {
                // 如果 allowExtensions 为空，则加载所有文件
                System.out.println("加载第" + currentIndex + "个资源：" + nextFileUrl);
                webView.setPrefSize(800, 800);
                webView.getEngine().load(tf_kkfileview_url.getText() + api + Base64Utils.encode(nextFileUrl));
                currentIndex++;
                return;
            } else if (allowExtensions.contains(extension.toLowerCase())) {
                // 如果 allowExtensions 不为空并且包含此扩展名，则加载文件
                System.out.println("加载第" + currentIndex + "个资源：" + nextFileUrl);
                webView.setPrefSize(800, 800);
                webView.getEngine().load(tf_kkfileview_url.getText() + api + Base64Utils.encode(nextFileUrl));
                currentIndex++;
                return;
            } else {
                // 否则加载下一个文件
                currentIndex++;
            }
        }
        System.out.println("已加载所有文件");
        showAlert(Alert.AlertType.INFORMATION, "提示", null, "已加载所有文件");
    }

    private void loadPreviousFile() {
        while (currentIndex >= 0) {
            String previousFileUrl = fileUrls[currentIndex];
            String extension = getFileExtension(previousFileUrl);

            // 检查 allowExtensions 是否为空
            if (allowExtensions.isEmpty()) {
                // 如果 allowExtensions 为空，则加载所有文件
                System.out.println("加载第" + currentIndex + "个资源：" + previousFileUrl);
                webView.setPrefSize(800, 800);
                webView.getEngine().load(tf_kkfileview_url.getText() + api + Base64Utils.encode(previousFileUrl));
                return;
            } else if (allowExtensions.contains(extension.toLowerCase())) {
                // 如果 allowExtensions 不为空并且包含此扩展名，则加载文件
                System.out.println("加载第" + currentIndex + "个资源：" + previousFileUrl);
                webView.setPrefSize(800, 800);
                webView.getEngine().load(tf_kkfileview_url.getText() + api + Base64Utils.encode(previousFileUrl));
                return;
            } else {
                // 否则加载上一个文件
                currentIndex--;
            }
        }
        System.out.println("已加载所有文件");
        showAlert(Alert.AlertType.INFORMATION, "提示", null, "已加载所有文件");
    }

    // 文件列表
    private void constructFileUrls(String oss_url) {
        fileUrls = new String[keys.length];
        root = new TreeItem<>("Files"); // 创建根节点

        for (int i = 0; i < keys.length; i++) {
            fileUrls[i] = oss_url + keys[i];
            System.out.println(fileUrls[i]);

            try {
                URL url = new URL(fileUrls[i]);
                String path = url.getPath(); // 获取 URL 的路径部分
                String extension = getFileExtension(path); // 获取文件扩展名

                // 检查 allowExtensions 是否为空
                if (allowExtensions.isEmpty() || allowExtensions.contains(extension.toLowerCase())) {
                    // 如果 allowExtensions 为空或包含此扩展名，则添加文件节点
                    TreeItem<String> fileItem = new TreeItem<>(path);
                    root.getChildren().add(fileItem);
                }
            } catch (Exception e) {
                // 处理无法解析 URL 的情况
                e.printStackTrace();
                showAlert(Alert.AlertType.INFORMATION, "提示", null, "无法解析文件列表里的url：" + fileUrls[i]);
            }
        }

        treeView.setRoot(root);
        treeView.setShowRoot(false);

        // 添加选择改变监听器
        treeView.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, newValue) -> {
            handleNodeSelected((TreeItem<String>) newValue);
        });
    }

    // 处理节点被选中的方法
    private void handleNodeSelected(TreeItem<String> selectedItem) {
        if (selectedItem != null) {
            String selectedPath = selectedItem.getValue();
            //System.out.println(selectedPath);
            String url = remove(tf_oss_url.getText())+ selectedPath;
            System.out.println("当前选择是："+url);
            webView.setPrefSize(800,800);
            webView.getEngine().load(tf_kkfileview_url.getText()+api+Base64Utils.encode(url));
        }else {
            // 如果没有节点被选中
            System.out.println("没有选中任何节点");
            showAlert(Alert.AlertType.INFORMATION, "提示", null, "没有选中任何文件，请确定是否加载成功");
        }
    }
    // 获取oss资源的后缀
    private String getFileExtension(String url) {
        int lastDotIndex = url.lastIndexOf('.');
        if (lastDotIndex >= 0) {
            return url.substring(lastDotIndex).toLowerCase();
        }
        return "";
    }

    public static void showAlert(Alert.AlertType type, String title, String headerText, String contentText) {
        Alert alert = new Alert(type);
        alert.setTitle(title);
        alert.setHeaderText(headerText);
        alert.setContentText(contentText);
        alert.showAndWait();
    }

    //删除url末尾的/
    private String remove(String url) {
        if (url.endsWith("/")) {
            return url.substring(0, url.length() - 1);
        }
        return url;
    }
}
