package net.jdr2021;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import javafx.scene.image.Image;
import net.jdr2021.controller.mainController;
import net.jdr2021.utils.RuntimeDiagnostics;
import net.jdr2021.utils.RuntimePlatform;
import java.io.InputStream;

/**
 * @version 2.0
 * @Author jdr
 * @Date 2024-5-23 14:25
 * @注释
 */

public class main extends Application {
    private mainController controller;

    @Override
    public void start(Stage primaryStage) {
        RuntimeDiagnostics.initialize();
        try {
            FXMLLoader loader = new FXMLLoader();
            loader.setLocation(getClass().getResource("/fxml/main.fxml"));
            Parent content = loader.load();
            controller = loader.getController();
            Scene scene = new Scene(content);
            // 添加图标
            InputStream iconStream = getClass().getResourceAsStream("/images/logo.png");
            Image icon = new Image(iconStream);
            primaryStage.getIcons().add(icon);
            primaryStage.setTitle("OSSFileBrowse "
                    + RuntimePlatform.buildVersion() + " - jdr2021");
            primaryStage.setScene(scene);
            primaryStage.setResizable(false);
            primaryStage.show();
        } catch(Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void stop() {
        if (controller != null) {
            controller.shutdown();
        }
    }

    public static void main(String[] args){
        RuntimeDiagnostics.initialize();
        launch(args);
    }
}
