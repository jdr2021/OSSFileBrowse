package net.jdr2021;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import javafx.scene.image.Image;
import java.io.InputStream;

/**
 * @version 1.0
 * @Author jdr
 * @Date 2024-5-23 14:25
 * @注释
 */

public class main extends Application {

    @Override
    public void start(Stage primaryStage) {
        try {
            FXMLLoader loader = new FXMLLoader();
            loader.setLocation(getClass().getResource("/fxml/main.fxml"));
            Parent content = loader.load();
            Scene scene = new Scene(content);
            // 添加图标
            InputStream iconStream = getClass().getResourceAsStream("/images/logo.png");
            Image icon = new Image(iconStream);
            primaryStage.getIcons().add(icon);
            primaryStage.setTitle(" 存储桶遍历漏洞工具-20240725-jdr2021");
            primaryStage.setScene(scene);
            primaryStage.setResizable(false);
            primaryStage.show();
        } catch(Exception e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args){
        launch(args);
    }
}
