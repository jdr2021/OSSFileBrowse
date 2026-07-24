package net.jdr2021.controller;

import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;
import javafx.fxml.FXMLLoader;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeTableColumn;
import javafx.scene.control.TreeTableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.scene.image.ImageView;
import javafx.scene.media.MediaView;
import javafx.scene.shape.SVGPath;
import javafx.scene.web.WebView;
import javafx.stage.Window;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.jdr2021.preview.PreviewFormat;
import net.jdr2021.utils.ConfigLoader;
import net.jdr2021.utils.FfmpegSettings;
import net.jdr2021.utils.KkFileViewSettings;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Base64;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class MainFxmlTest {
    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @BeforeClass
    public static void initializeJavaFx() {
        new JFXPanel();
        Platform.setImplicitExit(false);
    }

    @Test
    public void preservesThreeColumnsAndAddsCollectableHeaderRows() throws Exception {
        runOnFxThread(() -> {
            FXMLLoader loader = new FXMLLoader(
                    MainFxmlTest.class.getResource("/fxml/main.fxml"));
            HBox root = loader.load();

            assertEquals(1400.0, root.getPrefWidth(), 0.01);
            assertEquals(800.0, root.getPrefHeight(), 0.01);
            assertEquals(3, root.getChildren().size());
            VBox leftColumn = (VBox) root.getChildren().get(0);
            assertEquals(400.0, leftColumn.getPrefWidth(), 0.01);
            assertTrue(root.lookup("#previewStatusLabel") instanceof Label);
            assertTrue(root.lookup("#fileTypeFilterComboBox") instanceof ComboBox);
            assertTrue(root.lookup("#fileTypeFilterComboBox").isDisabled());
            assertTrue(root.lookup("#fileNameSearchField") instanceof TextField);
            assertTrue(root.lookup("#fileNameSearchButton") instanceof Button);
            Button fileNameSearchButton = (Button) root.lookup(
                    "#fileNameSearchButton");
            assertEquals("", fileNameSearchButton.getText());
            assertTrue(fileNameSearchButton.getGraphic()
                    instanceof SVGPath);
            assertTrue(root.lookup("#fileNameSearchField").isDisabled());
            assertTrue(root.lookup("#fileNameSearchButton").isDisabled());
            assertTrue(root.lookup("#treeView") instanceof TreeTableView);
            TreeTableView<?> fileTree =
                    (TreeTableView<?>) root.lookup("#treeView");
            assertEquals(2, fileTree.getColumns().size());
            assertEquals("路径名称",
                    fileTree.getColumns().get(0).getText());
            assertEquals("文件大小",
                    fileTree.getColumns().get(1).getText());
            assertTrue(fileTree.getColumns().get(0).isSortable());
            assertTrue(fileTree.getColumns().get(1).isSortable());
            assertEquals(TreeTableView.CONSTRAINED_RESIZE_POLICY,
                    fileTree.getColumnResizePolicy());
            assertTrue(fileTree.getColumns().get(1).getStyle()
                    .contains("center"));
            assertTrue(root.lookup("#settingsButton") instanceof Button);
            assertTrue(root.lookup("#searchRegexField")
                    instanceof TextField);
            assertTrue(root.lookup("#includeRulesCheckBox")
                    instanceof CheckBox);
            assertTrue(root.lookup("#searchButton") instanceof Button);
            assertTrue(root.lookup("#searchButton").isDisabled());
            assertTrue(root.lookup("#downloadAllButton") instanceof Button);
            assertTrue(root.lookup("#downloadAllButton").isDisabled());
            HBox leftToolbar = (HBox) leftColumn.getChildren().get(0);
            assertTrue(leftToolbar.getChildren().get(0)
                    == root.lookup("#settingsButton"));
            assertTrue(leftToolbar.getChildren().get(1)
                    == root.lookup("#fileTypeFilterComboBox"));
            assertTrue(leftToolbar.getChildren().get(2)
                    == root.lookup("#fileNameSearchField"));
            assertTrue(leftToolbar.getChildren().get(3)
                    == root.lookup("#fileNameSearchButton"));
            assertTrue(leftColumn.getChildren().get(
                    leftColumn.getChildren().size() - 1)
                    == root.lookup("#previewStatusLabel"));
            assertTrue(root.lookup("#previewStatusField") == null);
            assertEquals(800.0, ((VBox) root.getChildren().get(1)).getPrefWidth(), 0.01);
            VBox controlColumn = (VBox) root.getChildren().get(2);
            assertEquals(200.0, controlColumn.getPrefWidth(), 0.01);
            assertTrue(root.lookup("#mediaView") instanceof MediaView);
            assertTrue(root.lookup("#javaCvImageView")
                    instanceof ImageView);
            assertTrue(mainController.usesNativeMedia(PreviewFormat.VIDEO));
            assertTrue(mainController.usesNativeMedia(PreviewFormat.AUDIO));
            assertTrue(!mainController.usesNativeMedia(PreviewFormat.IMAGE));

            TextField bucketUrl = (TextField) root.lookup("#tf_oss_url");
            Button addHeader = (Button) controlColumn.getChildren().get(
                    controlColumn.getChildren().indexOf(bucketUrl) + 1);
            assertEquals("添加自定义请求头", addHeader.getText());

            VBox headerRows = (VBox) root.lookup("#customHeadersBox");
            addHeader.fire();
            addHeader.fire();
            assertEquals(2, headerRows.getChildren().size());

            HBox firstRow = (HBox) headerRows.getChildren().get(0);
            assertEquals(3, firstRow.getChildren().size());
            assertTrue(firstRow.getChildren().get(0) instanceof TextField);
            assertTrue(firstRow.getChildren().get(1) instanceof TextField);
            ((TextField) firstRow.getChildren().get(0)).setText("Authorization");
            ((TextField) firstRow.getChildren().get(1)).setText("Bearer fixture");

            HBox incompleteRow = (HBox) headerRows.getChildren().get(1);
            ((TextField) incompleteRow.getChildren().get(0)).setText("X-Incomplete");

            mainController controller = loader.getController();
            Map<String, String> headers = controller.collectCustomRequestHeaders();
            assertEquals(1, headers.size());
            assertEquals("Bearer fixture", headers.get("Authorization"));

            AtomicReference<Throwable> dialogFailure =
                    new AtomicReference<Throwable>();
            Platform.runLater(() -> inspectAndCloseSettingsDialog(
                    dialogFailure));
            ((Button) root.lookup("#settingsButton")).fire();
            if (dialogFailure.get() != null) {
                throw new AssertionError(
                        "Settings dialog verification failed",
                        dialogFailure.get());
            }

            controller.shutdown();
            return null;
        });
    }

    @Test
    public void filtersTreeByCachedExtensionWithoutReloadingBucket()
            throws Exception {
        AtomicInteger listingRequests = new AtomicInteger();
        HttpServer origin =
                HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        origin.createContext("/", exchange ->
                serveFilterFixture(exchange, listingRequests));
        origin.start();

        AtomicReference<mainController> controllerRef = new AtomicReference<>();
        AtomicReference<HBox> rootRef = new AtomicReference<>();
        try {
            runOnFxThread(() -> {
                FXMLLoader loader = new FXMLLoader(
                        MainFxmlTest.class.getResource("/fxml/main.fxml"));
                HBox root = loader.load();
                controllerRef.set(loader.getController());
                rootRef.set(root);
                ((TextField) root.lookup("#tf_oss_url")).setText(
                        "http://127.0.0.1:"
                                + origin.getAddress().getPort() + "/");
                findButton(root, "加载").fire();
                return null;
            });

            waitFor(() -> runOnFxThread(() -> {
                TreeTableView<?> tree =
                        (TreeTableView<?>) rootRef.get().lookup("#treeView");
                ComboBox<?> filter = (ComboBox<?>) rootRef.get()
                        .lookup("#fileTypeFilterComboBox");
                return tree.getRoot() != null
                        && tree.getRoot().getChildren().size() == 5
                        && !filter.isDisabled()
                        && filter.getItems().size() == 5;
            }), 12_000);

            runOnFxThread(() -> {
                @SuppressWarnings("unchecked")
                ComboBox<mainController.FileTypeFilter> filter =
                        (ComboBox<mainController.FileTypeFilter>) rootRef.get()
                                .lookup("#fileTypeFilterComboBox");
                assertTrue(filter.getItems().get(0).isHeader());
                assertEquals("*", filter.getSelectionModel()
                        .getSelectedItem().getExtension());
                assertFilter(filter, "*", 5);
                assertFilter(filter, ".mp4", 1);
                assertFilter(filter, ".txt", 2);
                assertFilter(filter, "other", 2);
                filter.getSelectionModel().select(
                        findFilter(filter, ".txt"));
                return null;
            });

            waitFor(() -> runOnFxThread(() -> {
                TreeTableView<?> tree =
                        (TreeTableView<?>) rootRef.get().lookup("#treeView");
                Label status = (Label) rootRef.get()
                        .lookup("#previewStatusLabel");
                return tree.getRoot().getChildren().size() == 2
                        && status.getText().contains("筛选 .txt")
                        && status.getText().contains("2 / 5")
                        && listingRequests.get() == 1;
            }), 4_000);

            runOnFxThread(() -> {
                @SuppressWarnings("unchecked")
                ComboBox<mainController.FileTypeFilter> filter =
                        (ComboBox<mainController.FileTypeFilter>) rootRef.get()
                                .lookup("#fileTypeFilterComboBox");
                filter.getSelectionModel().select(
                        findFilter(filter, "other"));
                return null;
            });

            waitFor(() -> runOnFxThread(() -> {
                TreeTableView<?> tree =
                        (TreeTableView<?>) rootRef.get().lookup("#treeView");
                Label status = (Label) rootRef.get()
                        .lookup("#previewStatusLabel");
                return tree.getRoot().getChildren().size() == 2
                        && status.getText().contains("筛选 other")
                        && listingRequests.get() == 1;
            }), 4_000);

            runOnFxThread(() -> {
                @SuppressWarnings("unchecked")
                ComboBox<mainController.FileTypeFilter> filter =
                        (ComboBox<mainController.FileTypeFilter>) rootRef.get()
                                .lookup("#fileTypeFilterComboBox");
                filter.getSelectionModel().select(findFilter(filter, "*"));
                TextField fileName = (TextField) rootRef.get()
                        .lookup("#fileNameSearchField");
                fileName.setText("b.t");
                ((Button) rootRef.get()
                        .lookup("#fileNameSearchButton")).fire();
                return null;
            });

            waitFor(() -> runOnFxThread(() -> {
                TreeTableView<?> tree =
                        (TreeTableView<?>) rootRef.get().lookup("#treeView");
                Label status = (Label) rootRef.get()
                        .lookup("#previewStatusLabel");
                return tree.getRoot().getChildren().size() == 1
                        && "B.TXT".equals(tree.getRoot()
                        .getChildren().get(0).getValue())
                        && status.getText().contains("文件名“b.t”")
                        && listingRequests.get() == 1;
            }), 4_000);

            runOnFxThread(() -> {
                TextField fileName = (TextField) rootRef.get()
                        .lookup("#fileNameSearchField");
                fileName.clear();
                ((Button) rootRef.get()
                        .lookup("#fileNameSearchButton")).fire();
                @SuppressWarnings("unchecked")
                TreeTableView<String> tree =
                        (TreeTableView<String>) rootRef.get()
                                .lookup("#treeView");
                @SuppressWarnings("unchecked")
                TreeTableColumn<String, Long> size =
                        (TreeTableColumn<String, Long>)
                                tree.getColumns().get(1);
                size.setSortType(TreeTableColumn.SortType.ASCENDING);
                tree.getSortOrder().setAll(size);
                tree.sort();
                return null;
            });

            waitFor(() -> runOnFxThread(() -> {
                TreeTableView<?> tree =
                        (TreeTableView<?>) rootRef.get().lookup("#treeView");
                return tree.getRoot().getChildren().size() == 5
                        && ".env".equals(tree.getRoot()
                        .getChildren().get(0).getValue())
                        && "video.mp4".equals(tree.getRoot()
                        .getChildren().get(4).getValue())
                        && listingRequests.get() == 1;
            }), 4_000);

            runOnFxThread(() -> {
                @SuppressWarnings("unchecked")
                TreeTableView<String> tree =
                        (TreeTableView<String>) rootRef.get()
                                .lookup("#treeView");
                @SuppressWarnings("unchecked")
                TreeTableColumn<String, String> path =
                        (TreeTableColumn<String, String>)
                                tree.getColumns().get(0);
                path.setSortType(TreeTableColumn.SortType.DESCENDING);
                tree.getSortOrder().setAll(path);
                tree.sort();
                return null;
            });

            waitFor(() -> runOnFxThread(() -> {
                TreeTableView<?> tree =
                        (TreeTableView<?>) rootRef.get().lookup("#treeView");
                return "video.mp4".equals(tree.getRoot()
                        .getChildren().get(0).getValue())
                        && ".env".equals(tree.getRoot()
                        .getChildren().get(4).getValue())
                        && listingRequests.get() == 1;
            }), 4_000);

            assertEquals("other", mainController.fileTypeOf("README"));
            assertEquals("other", mainController.fileTypeOf(".env"));
            assertEquals(".gz",
                    mainController.fileTypeOf("folder/archive.TAR.GZ"));
        } finally {
            if (controllerRef.get() != null) {
                runOnFxThread(() -> {
                    controllerRef.get().shutdown();
                    return null;
                });
            }
            origin.stop(0);
        }
    }

    @Test
    public void settingsDialogPersistsPerItemEnableCheckboxes()
            throws Exception {
        String previousUserDir = System.getProperty("user.dir");
        Path settingsDirectory =
                temporary.newFolder("settings-checkbox-state").toPath();
        AtomicReference<mainController> controllerRef =
                new AtomicReference<mainController>();
        try {
            System.setProperty("user.dir", settingsDirectory.toString());
            ConfigLoader.reloadForTests();

            runOnFxThread(() -> {
                FXMLLoader loader = new FXMLLoader(
                        MainFxmlTest.class.getResource("/fxml/main.fxml"));
                HBox root = loader.load();
                controllerRef.set(loader.getController());
                AtomicReference<Throwable> dialogFailure =
                        new AtomicReference<Throwable>();
                Platform.runLater(() ->
                        editAndConfirmSettingsDialog(dialogFailure));
                ((Button) root.lookup("#settingsButton")).fire();
                if (dialogFailure.get() != null) {
                    throw new AssertionError(
                            "Settings dialog save failed",
                            dialogFailure.get());
                }
                return null;
            });

            assertTrue(KkFileViewSettings.isEnabled());
            assertEquals("http://127.0.0.1:8181/kk/",
                    KkFileViewSettings.configuredValue());
            assertFalse(FfmpegSettings.isEnabled());
            assertEquals("C:\\tools\\ffmpeg.exe",
                    FfmpegSettings.configuredValue());
            String persisted = new String(Files.readAllBytes(
                    settingsDirectory.resolve("config.properties")),
                    StandardCharsets.UTF_8);
            assertTrue(persisted.contains(
                    "kkfileview.enabled=true"));
            assertTrue(persisted.contains(
                    "ffmpeg.enabled=false"));
        } finally {
            if (controllerRef.get() != null) {
                runOnFxThread(() -> {
                    controllerRef.get().shutdown();
                    return null;
                });
            }
            System.setProperty("user.dir", previousUserDir);
            ConfigLoader.reloadForTests();
        }
    }

    @Test
    public void loadsAuthenticatedBucketAndPreviewsTextEndToEnd() throws Exception {
        byte[] textBody = "authenticated remote preview".getBytes(StandardCharsets.UTF_8);
        HttpServer origin = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        origin.createContext("/", exchange -> serveFixture(exchange, textBody));
        origin.start();

        AtomicReference<mainController> controllerRef = new AtomicReference<>();
        AtomicReference<HBox> rootRef = new AtomicReference<>();
        try {
            runOnFxThread(() -> {
                FXMLLoader loader = new FXMLLoader(
                        MainFxmlTest.class.getResource("/fxml/main.fxml"));
                HBox root = loader.load();
                mainController controller = loader.getController();
                controllerRef.set(controller);
                rootRef.set(root);

                TextField bucketUrl = (TextField) root.lookup("#tf_oss_url");
                bucketUrl.setText("http://127.0.0.1:" + origin.getAddress().getPort() + "/");
                Button addHeader = findButton(root, "添加自定义请求头");
                addHeader.fire();
                VBox rows = (VBox) root.lookup("#customHeadersBox");
                HBox row = (HBox) rows.getChildren().get(0);
                ((TextField) row.getChildren().get(0)).setText("X-Bucket-Token");
                ((TextField) row.getChildren().get(1)).setText("fixture-secret");
                findButton(root, "加载").fire();
                return null;
            });

            waitFor(() -> runOnFxThread(() -> {
                TreeTableView<?> tree = (TreeTableView<?>) rootRef.get().lookup("#treeView");
                Label status = (Label) rootRef.get().lookup("#previewStatusLabel");
                WebView web = (WebView) rootRef.get().lookup("#webView");
                Object body = web.getEngine().executeScript(
                        "document.body ? document.body.innerText : ''");
                return tree.getRoot() != null
                        && tree.getRoot().getChildren().size() == 1
                        && status.getText().contains("预览完成")
                        && String.valueOf(body).contains("authenticated remote preview");
            }), 12_000);
        } finally {
            if (controllerRef.get() != null) {
                runOnFxThread(() -> {
                    controllerRef.get().shutdown();
                    return null;
                });
            }
            origin.stop(0);
        }
    }

    @Test
    public void delegatesRemoteObjectPreviewToEnabledKkFileView()
            throws Exception {
        AtomicInteger objectRequests = new AtomicInteger();
        AtomicReference<String> kkRawQuery = new AtomicReference<String>();
        HttpServer origin =
                HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        origin.createContext("/", exchange -> {
            if ("/delegated.docx".equals(
                    exchange.getRequestURI().getPath())) {
                objectRequests.incrementAndGet();
                byte[] body = "local-renderer-must-not-fetch"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream output = exchange.getResponseBody()) {
                    output.write(body);
                }
                return;
            }
            String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<ListBucketResult><Name>kk-fixture</Name>"
                    + "<IsTruncated>false</IsTruncated>"
                    + "<Contents><Key>delegated.docx</Key>"
                    + "<Size>321</Size></Contents></ListBucketResult>";
            byte[] bytes = xml.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set(
                    "Content-Type", "application/xml; charset=UTF-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        origin.start();

        HttpServer kk =
                HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        kk.createContext("/onlinePreview", exchange -> {
            kkRawQuery.set(exchange.getRequestURI().getRawQuery());
            byte[] body = ("<html><body>"
                    + "KKFILEVIEW_DELEGATED_PREVIEW"
                    + "</body></html>").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set(
                    "Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        kk.start();

        String previousUserDir = System.getProperty("user.dir");
        Path settingsDirectory =
                temporary.newFolder("kk-preview-settings").toPath();
        AtomicReference<mainController> controllerRef =
                new AtomicReference<mainController>();
        AtomicReference<HBox> rootRef = new AtomicReference<HBox>();
        try {
            System.setProperty("user.dir", settingsDirectory.toString());
            ConfigLoader.reloadForTests();
            KkFileViewSettings.saveSettings(true,
                    "http://127.0.0.1:"
                            + kk.getAddress().getPort() + "/");

            runOnFxThread(() -> {
                FXMLLoader loader = new FXMLLoader(
                        MainFxmlTest.class.getResource("/fxml/main.fxml"));
                HBox root = loader.load();
                controllerRef.set(loader.getController());
                rootRef.set(root);
                ((TextField) root.lookup("#tf_oss_url")).setText(
                        "http://127.0.0.1:"
                                + origin.getAddress().getPort() + "/");
                findButton(root, "加载").fire();
                return null;
            });

            waitFor(() -> runOnFxThread(() -> {
                WebView web = (WebView) rootRef.get().lookup("#webView");
                Label status = (Label) rootRef.get()
                        .lookup("#previewStatusLabel");
                Object body = web.getEngine().executeScript(
                        "document.body ? document.body.innerText : ''");
                return String.valueOf(body).contains(
                        "KKFILEVIEW_DELEGATED_PREVIEW")
                        && status.getText().contains("kkFileView 预览")
                        && kkRawQuery.get() != null;
            }), 12_000);

            assertEquals(0, objectRequests.get());
            String rawQuery = kkRawQuery.get();
            assertTrue(rawQuery.startsWith("url="));
            String base64 = URLDecoder.decode(
                    rawQuery.substring("url=".length()), "UTF-8");
            String delegatedObjectUrl = new String(
                    Base64.getDecoder().decode(base64),
                    StandardCharsets.UTF_8);
            assertEquals("http://127.0.0.1:"
                            + origin.getAddress().getPort()
                            + "/delegated.docx",
                    delegatedObjectUrl);
        } finally {
            if (controllerRef.get() != null) {
                runOnFxThread(() -> {
                    controllerRef.get().shutdown();
                    return null;
                });
            }
            origin.stop(0);
            kk.stop(0);
            System.setProperty("user.dir", previousUserDir);
            ConfigLoader.reloadForTests();
        }
    }

    @Test
    public void searchesRemoteAndArchiveContentAndWritesWorkingDirectoryReport()
            throws Exception {
        byte[] archive = archiveSearchFixture();
        HttpServer origin = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0), 0);
        origin.createContext("/", exchange ->
                serveSearchFixture(exchange, archive));
        origin.start();

        String previousUserDir = System.getProperty("user.dir");
        Path reportDirectory = temporary.newFolder(
                "search-report-working-directory").toPath();
        AtomicReference<mainController> controllerRef =
                new AtomicReference<>();
        AtomicReference<HBox> rootRef = new AtomicReference<>();
        try {
            runOnFxThread(() -> {
                FXMLLoader loader = new FXMLLoader(
                        MainFxmlTest.class.getResource("/fxml/main.fxml"));
                HBox root = loader.load();
                controllerRef.set(loader.getController());
                rootRef.set(root);
                ((TextField) root.lookup("#tf_oss_url")).setText(
                        "http://127.0.0.1:"
                                + origin.getAddress().getPort() + "/");
                findButton(root, "加载").fire();
                return null;
            });

            waitFor(() -> runOnFxThread(() -> {
                TreeTableView<?> tree = (TreeTableView<?>) rootRef.get()
                        .lookup("#treeView");
                Button search = (Button) rootRef.get()
                        .lookup("#searchButton");
                return tree.getRoot() != null
                        && tree.getRoot().getChildren().size() == 2
                        && !search.isDisabled();
            }), 12_000);

            System.setProperty("user.dir", reportDirectory.toString());
            runOnFxThread(() -> {
                TextField regex = (TextField) rootRef.get()
                        .lookup("#searchRegexField");
                CheckBox includeRules = (CheckBox) rootRef.get()
                        .lookup("#includeRulesCheckBox");
                regex.setText("domain.example.org");
                includeRules.setSelected(false);
                ((Button) rootRef.get().lookup("#searchButton")).fire();
                return null;
            });

            Path report = reportDirectory.resolve(
                    "127.0.0.1_leak_info.html");
            waitFor(() -> runOnFxThread(() -> {
                Label status = (Label) rootRef.get()
                        .lookup("#previewStatusLabel");
                WebView web = (WebView) rootRef.get().lookup("#webView");
                Object body = web.getEngine().executeScript(
                        "document.body ? document.body.innerText : ''");
                return Files.isRegularFile(report)
                        && status.getText().contains("搜索完成")
                        && String.valueOf(body).contains(
                        "domain.example.org")
                        && String.valueOf(body).contains(
                        "inner/config.txt");
            }), 20_000);

            String html = new String(Files.readAllBytes(report),
                    StandardCharsets.UTF_8);
            assertTrue(html.contains("正则规则名称"));
            assertTrue(html.contains("采集到的样例数据"));
            assertTrue(html.contains("所属文件链接"));
            assertTrue(html.contains("筛选 ▾"));
            assertTrue(html.contains("archive.zip!/inner/config.txt"));
        } finally {
            System.setProperty("user.dir", previousUserDir);
            if (controllerRef.get() != null) {
                runOnFxThread(() -> {
                    controllerRef.get().shutdown();
                    return null;
                });
            }
            origin.stop(0);
        }
    }

    @Test
    public void downloadsAllFilesIntoHostBackDirectoryWithObjectHierarchy()
            throws Exception {
        HttpServer origin = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0), 0);
        origin.createContext("/", MainFxmlTest::serveBulkDownloadFixture);
        origin.start();

        String previousUserDir = System.getProperty("user.dir");
        Path workingDirectory = temporary.newFolder(
                "bulk-download-working-directory").toPath();
        AtomicReference<mainController> controllerRef =
                new AtomicReference<>();
        AtomicReference<HBox> rootRef = new AtomicReference<>();
        try {
            System.setProperty("user.dir", workingDirectory.toString());
            runOnFxThread(() -> {
                FXMLLoader loader = new FXMLLoader(
                        MainFxmlTest.class.getResource("/fxml/main.fxml"));
                HBox root = loader.load();
                controllerRef.set(loader.getController());
                rootRef.set(root);
                ((TextField) root.lookup("#tf_oss_url")).setText(
                        "http://127.0.0.1:"
                                + origin.getAddress().getPort() + "/");
                ((Button) root.lookup("#loadButton")).fire();
                return null;
            });

            waitFor(() -> runOnFxThread(() ->
                    !rootRef.get().lookup("#downloadAllButton")
                            .isDisabled()), 12_000);
            runOnFxThread(() -> {
                ((Button) rootRef.get().lookup(
                        "#downloadAllButton")).fire();
                return null;
            });

            Path root = workingDirectory.resolve("127.0.0.1_back");
            Path first = root.resolve("folder/a.txt");
            Path second = root.resolve("nested/path/b.bin");
            waitFor(() -> runOnFxThread(() -> {
                Label status = (Label) rootRef.get()
                        .lookup("#previewStatusLabel");
                return Files.isRegularFile(first)
                        && Files.isRegularFile(second)
                        && status.getText().contains("全部下载完成");
            }), 20_000);

            assertEquals("alpha", new String(
                    Files.readAllBytes(first), StandardCharsets.UTF_8));
            assertEquals("bravo", new String(
                    Files.readAllBytes(second), StandardCharsets.UTF_8));
        } finally {
            if (controllerRef.get() != null) {
                runOnFxThread(() -> {
                    controllerRef.get().shutdown();
                    return null;
                });
            }
            origin.stop(0);
            System.setProperty("user.dir", previousUserDir);
        }
    }

    @Test
    public void expandsArchiveHierarchyAndPreviewsSelectedEntry() throws Exception {
        byte[] archive = archiveFixture();
        HttpServer origin =
                HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        origin.createContext("/", exchange ->
                serveArchiveFixture(exchange, archive));
        origin.start();

        AtomicReference<mainController> controllerRef = new AtomicReference<>();
        AtomicReference<HBox> rootRef = new AtomicReference<>();
        try {
            runOnFxThread(() -> {
                FXMLLoader loader = new FXMLLoader(
                        MainFxmlTest.class.getResource("/fxml/main.fxml"));
                HBox root = loader.load();
                controllerRef.set(loader.getController());
                rootRef.set(root);
                TextField bucketUrl = (TextField) root.lookup("#tf_oss_url");
                bucketUrl.setText("http://127.0.0.1:"
                        + origin.getAddress().getPort() + "/");
                findButton(root, "加载").fire();
                return null;
            });

            waitFor(() -> runOnFxThread(() -> {
                TreeTableView<?> tree =
                        (TreeTableView<?>) rootRef.get().lookup("#treeView");
                if (tree.getRoot() == null
                        || tree.getRoot().getChildren().size() != 1) {
                    return false;
                }
                javafx.scene.control.TreeItem<?> archiveNode =
                        tree.getRoot().getChildren().get(0);
                return archiveNode.isExpanded()
                        && archiveNode.getChildren().size() == 2
                        && "folder".equals(
                        archiveNode.getChildren().get(0).getValue())
                        && archiveNode.getChildren().get(0)
                        .getChildren().size() == 1;
            }), 12_000);

            runOnFxThread(() -> {
                TreeTableView<?> tree =
                        (TreeTableView<?>) rootRef.get().lookup("#treeView");
                javafx.scene.control.TreeItem<?> archiveNode =
                        tree.getRoot().getChildren().get(0);
                javafx.scene.control.TreeItem<?> readme =
                        archiveNode.getChildren().get(0)
                                .getChildren().get(0);
                @SuppressWarnings("unchecked")
                TreeTableView<String> typedTree = (TreeTableView<String>) tree;
                @SuppressWarnings("unchecked")
                javafx.scene.control.TreeItem<String> typedReadme =
                        (javafx.scene.control.TreeItem<String>) readme;
                typedTree.getSelectionModel().select(typedReadme);
                return null;
            });

            waitFor(() -> runOnFxThread(() -> {
                Label status = (Label) rootRef.get()
                        .lookup("#previewStatusLabel");
                WebView web = (WebView) rootRef.get().lookup("#webView");
                Button download = (Button) rootRef.get()
                        .lookup("#downloadButton");
                Object body = web.getEngine().executeScript(
                        "document.body ? document.body.innerText : ''");
                return status.getText().contains("预览完成")
                        && status.getText().contains("folder/readme.txt")
                        && !download.isDisabled()
                        && String.valueOf(body).contains(
                        "archive entry preview");
            }), 12_000);
        } finally {
            if (controllerRef.get() != null) {
                runOnFxThread(() -> {
                    controllerRef.get().shutdown();
                    return null;
                });
            }
            origin.stop(0);
        }
    }

    @Test
    public void expandsAndPreviewsMultipleNestedArchiveLevels()
            throws Exception {
        byte[] inner = zipWithEntry("deep/final.txt",
                "three-level nested archive preview"
                        .getBytes(StandardCharsets.UTF_8));
        byte[] middle = zipWithEntry("level2/inner.zip", inner);
        byte[] outer = zipWithEntry("level1/middle.zip", middle);
        HttpServer origin =
                HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        origin.createContext("/", exchange ->
                serveArchiveFixture(exchange, outer));
        origin.start();

        AtomicReference<mainController> controllerRef =
                new AtomicReference<>();
        AtomicReference<HBox> rootRef = new AtomicReference<>();
        try {
            runOnFxThread(() -> {
                FXMLLoader loader = new FXMLLoader(
                        MainFxmlTest.class.getResource("/fxml/main.fxml"));
                HBox root = loader.load();
                controllerRef.set(loader.getController());
                rootRef.set(root);
                ((TextField) root.lookup("#tf_oss_url")).setText(
                        "http://127.0.0.1:"
                                + origin.getAddress().getPort() + "/");
                ((Button) root.lookup("#loadButton")).fire();
                return null;
            });

            waitFor(() -> runOnFxThread(() -> {
                TreeTableView<?> tree = (TreeTableView<?>) rootRef.get()
                        .lookup("#treeView");
                if (tree.getRoot() == null
                        || tree.getRoot().getChildren().isEmpty()) {
                    return false;
                }
                TreeItem<?> middleNode = descendant(
                        tree.getRoot().getChildren().get(0),
                        "level1", "middle.zip");
                return middleNode != null;
            }), 12_000);

            runOnFxThread(() -> {
                @SuppressWarnings("unchecked")
                TreeTableView<String> tree =
                        (TreeTableView<String>) rootRef.get()
                                .lookup("#treeView");
                @SuppressWarnings("unchecked")
                TreeItem<String> middleNode = (TreeItem<String>) descendant(
                        tree.getRoot().getChildren().get(0),
                        "level1", "middle.zip");
                tree.getSelectionModel().select(middleNode);
                return null;
            });

            waitFor(() -> runOnFxThread(() -> {
                TreeTableView<?> tree = (TreeTableView<?>) rootRef.get()
                        .lookup("#treeView");
                TreeItem<?> middleNode = descendant(
                        tree.getRoot().getChildren().get(0),
                        "level1", "middle.zip");
                return middleNode != null && middleNode.isExpanded()
                        && descendant(middleNode,
                        "level2", "inner.zip") != null;
            }), 12_000);

            runOnFxThread(() -> {
                @SuppressWarnings("unchecked")
                TreeTableView<String> tree =
                        (TreeTableView<String>) rootRef.get()
                                .lookup("#treeView");
                TreeItem<?> middleNode = descendant(
                        tree.getRoot().getChildren().get(0),
                        "level1", "middle.zip");
                @SuppressWarnings("unchecked")
                TreeItem<String> innerNode = (TreeItem<String>) descendant(
                        middleNode, "level2", "inner.zip");
                tree.getSelectionModel().select(innerNode);
                return null;
            });

            waitFor(() -> runOnFxThread(() -> {
                TreeTableView<?> tree = (TreeTableView<?>) rootRef.get()
                        .lookup("#treeView");
                TreeItem<?> middleNode = descendant(
                        tree.getRoot().getChildren().get(0),
                        "level1", "middle.zip");
                TreeItem<?> innerNode = descendant(
                        middleNode, "level2", "inner.zip");
                return innerNode != null && innerNode.isExpanded()
                        && descendant(innerNode,
                        "deep", "final.txt") != null;
            }), 12_000);

            runOnFxThread(() -> {
                @SuppressWarnings("unchecked")
                TreeTableView<String> tree =
                        (TreeTableView<String>) rootRef.get()
                                .lookup("#treeView");
                TreeItem<?> middleNode = descendant(
                        tree.getRoot().getChildren().get(0),
                        "level1", "middle.zip");
                TreeItem<?> innerNode = descendant(
                        middleNode, "level2", "inner.zip");
                @SuppressWarnings("unchecked")
                TreeItem<String> finalNode = (TreeItem<String>) descendant(
                        innerNode, "deep", "final.txt");
                tree.getSelectionModel().select(finalNode);
                return null;
            });

            waitFor(() -> runOnFxThread(() -> {
                WebView web = (WebView) rootRef.get().lookup("#webView");
                Label status = (Label) rootRef.get()
                        .lookup("#previewStatusLabel");
                Object body = web.getEngine().executeScript(
                        "document.body ? document.body.innerText : ''");
                return status.getText().contains(
                        "middle.zip!/level2/inner.zip!/deep/final.txt")
                        && String.valueOf(body).contains(
                        "three-level nested archive preview");
            }), 12_000);
        } finally {
            if (controllerRef.get() != null) {
                runOnFxThread(() -> {
                    controllerRef.get().shutdown();
                    return null;
                });
            }
            origin.stop(0);
        }
    }

    @Test
    public void previewsTarLargerThanLegacy64MiBWhenConfigIsEmpty()
            throws Exception {
        String previousUserDir = System.getProperty("user.dir");
        Path workingDirectory = temporary.newFolder(
                "large-tar-preview").toPath();
        Path archive = createLargeTar(
                workingDirectory.resolve("large.tar"),
                65L * 1024 * 1024);
        long archiveSize = Files.size(archive);
        assertTrue(archiveSize > 64L * 1024 * 1024);

        HttpServer origin =
                HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        origin.createContext("/", exchange ->
                serveLargeTarFixture(exchange, archive, archiveSize));
        origin.start();

        AtomicReference<mainController> controllerRef =
                new AtomicReference<>();
        AtomicReference<HBox> rootRef = new AtomicReference<>();
        try {
            System.setProperty("user.dir", workingDirectory.toString());
            ConfigLoader.reloadForTests();
            assertEquals("", ConfigLoader.getProperty("preview.max.size"));
            runOnFxThread(() -> {
                FXMLLoader loader = new FXMLLoader(
                        MainFxmlTest.class.getResource("/fxml/main.fxml"));
                HBox root = loader.load();
                controllerRef.set(loader.getController());
                rootRef.set(root);
                ((TextField) root.lookup("#tf_oss_url")).setText(
                        "http://127.0.0.1:"
                                + origin.getAddress().getPort() + "/");
                ((Button) root.lookup("#loadButton")).fire();
                return null;
            });

            waitFor(() -> runOnFxThread(() -> {
                TreeTableView<?> tree = (TreeTableView<?>) rootRef.get()
                        .lookup("#treeView");
                if (tree.getRoot() == null
                        || tree.getRoot().getChildren().isEmpty()) {
                    return false;
                }
                TreeItem<?> archiveNode =
                        tree.getRoot().getChildren().get(0);
                WebView web = (WebView) rootRef.get().lookup("#webView");
                Object body = web.getEngine().executeScript(
                        "document.body ? document.body.innerText : ''");
                return archiveNode.isExpanded()
                        && descendant(archiveNode, "payload.bin") != null
                        && String.valueOf(body).contains(
                        "压缩包目录已展开到左侧文件树")
                        && !String.valueOf(body).contains(
                        "内存预览上限为 64.0 MiB");
            }), 30_000);
        } finally {
            if (controllerRef.get() != null) {
                runOnFxThread(() -> {
                    controllerRef.get().shutdown();
                    return null;
                });
            }
            origin.stop(0);
            System.setProperty("user.dir", previousUserDir);
            ConfigLoader.reloadForTests();
        }
    }

    @Test
    public void showsEmptyMediaDiagnosticBeforeStartingMediaPlayer()
            throws Exception {
        HttpServer origin =
                HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        origin.createContext("/", MainFxmlTest::serveEmptyMediaFixture);
        origin.start();

        AtomicReference<mainController> controllerRef = new AtomicReference<>();
        AtomicReference<HBox> rootRef = new AtomicReference<>();
        try {
            runOnFxThread(() -> {
                FXMLLoader loader = new FXMLLoader(
                        MainFxmlTest.class.getResource("/fxml/main.fxml"));
                HBox root = loader.load();
                controllerRef.set(loader.getController());
                rootRef.set(root);
                ((TextField) root.lookup("#tf_oss_url")).setText(
                        "http://127.0.0.1:"
                                + origin.getAddress().getPort() + "/");
                findButton(root, "加载").fire();
                return null;
            });

            waitFor(() -> runOnFxThread(() -> {
                Label status = (Label) rootRef.get()
                        .lookup("#previewStatusLabel");
                WebView web = (WebView) rootRef.get().lookup("#webView");
                VBox media = (VBox) rootRef.get().lookup("#mediaContainer");
                Object body = web.getEngine().executeScript(
                        "document.body ? document.body.innerText : ''");
                return status.getText().contains("预览受限")
                        && String.valueOf(body).contains("0 字节")
                        && !media.isVisible();
            }), 12_000);
        } finally {
            if (controllerRef.get() != null) {
                runOnFxThread(() -> {
                    controllerRef.get().shutdown();
                    return null;
                });
            }
            origin.stop(0);
        }
    }

    private static void serveFixture(HttpExchange exchange, byte[] textBody) throws IOException {
        if (!"fixture-secret".equals(
                exchange.getRequestHeaders().getFirst("X-Bucket-Token"))) {
            exchange.sendResponseHeaders(403, -1);
            exchange.close();
            return;
        }
        if ("/note.txt".equals(exchange.getRequestURI().getPath())) {
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
            exchange.getResponseHeaders().set("Content-Length",
                    String.valueOf(textBody.length));
            if ("HEAD".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
                return;
            }
            exchange.sendResponseHeaders(200, textBody.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(textBody);
            }
            return;
        }

        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<ListBucketResult><Name>fixture</Name><IsTruncated>false</IsTruncated>"
                + "<Contents><Key>note.txt</Key><Size>" + textBody.length
                + "</Size></Contents></ListBucketResult>";
        byte[] bytes = xml.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/xml; charset=UTF-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static void serveArchiveFixture(HttpExchange exchange,
                                            byte[] archive) throws IOException {
        if ("/sample.zip".equals(exchange.getRequestURI().getPath())) {
            exchange.getResponseHeaders().set(
                    "Content-Type", "application/zip");
            exchange.getResponseHeaders().set(
                    "Content-Length", String.valueOf(archive.length));
            if ("HEAD".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
                return;
            }
            exchange.sendResponseHeaders(200, archive.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(archive);
            }
            return;
        }
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<ListBucketResult><Name>archive-fixture</Name>"
                + "<IsTruncated>false</IsTruncated>"
                + "<Contents><Key>sample.zip</Key><Size>"
                + archive.length
                + "</Size></Contents></ListBucketResult>";
        byte[] bytes = xml.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set(
                "Content-Type", "application/xml; charset=UTF-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static void serveLargeTarFixture(HttpExchange exchange,
                                             Path archive,
                                             long archiveSize)
            throws IOException {
        if (!"/large.tar".equals(exchange.getRequestURI().getPath())) {
            String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<ListBucketResult><Name>large-tar</Name>"
                    + "<IsTruncated>false</IsTruncated>"
                    + "<Contents><Key>large.tar</Key><Size>"
                    + archiveSize
                    + "</Size></Contents></ListBucketResult>";
            serveBytes(exchange, xml.getBytes(StandardCharsets.UTF_8),
                    "application/xml; charset=UTF-8");
            return;
        }

        exchange.getResponseHeaders().set(
                "Content-Type", "application/x-tar");
        exchange.getResponseHeaders().set(
                "Accept-Ranges", "bytes");
        exchange.getResponseHeaders().set(
                "Content-Length", String.valueOf(archiveSize));
        if ("HEAD".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
            return;
        }

        long end = archiveSize - 1;
        int status = 200;
        String requestedRange =
                exchange.getRequestHeaders().getFirst("Range");
        if (requestedRange != null
                && requestedRange.startsWith("bytes=0-")) {
            String rawEnd = requestedRange.substring("bytes=0-".length());
            if (!rawEnd.isEmpty()) {
                end = Math.min(end, Long.parseLong(rawEnd));
                status = 206;
                exchange.getResponseHeaders().set("Content-Range",
                        "bytes 0-" + end + "/" + archiveSize);
            }
        }
        long responseLength = end + 1;
        exchange.getResponseHeaders().set(
                "Content-Length", String.valueOf(responseLength));
        exchange.sendResponseHeaders(status, responseLength);
        try (InputStream input = Files.newInputStream(archive);
             OutputStream output = exchange.getResponseBody()) {
            byte[] buffer = new byte[64 * 1024];
            long remaining = responseLength;
            while (remaining > 0) {
                int read = input.read(buffer, 0,
                        (int) Math.min(buffer.length, remaining));
                if (read < 0) {
                    break;
                }
                output.write(buffer, 0, read);
                remaining -= read;
            }
        }
    }

    private static void serveSearchFixture(HttpExchange exchange,
                                           byte[] archive)
            throws IOException {
        String path = exchange.getRequestURI().getPath();
        if ("/app.js".equals(path)) {
            byte[] body = "const host='domain.example.org';"
                    .getBytes(StandardCharsets.UTF_8);
            serveBytes(exchange, body, "text/javascript");
            return;
        }
        if ("/archive.zip".equals(path)) {
            serveBytes(exchange, archive, "application/zip");
            return;
        }
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<ListBucketResult><Name>search-fixture</Name>"
                + "<IsTruncated>false</IsTruncated>"
                + "<Contents><Key>app.js</Key><Size>32</Size></Contents>"
                + "<Contents><Key>archive.zip</Key><Size>"
                + archive.length
                + "</Size></Contents></ListBucketResult>";
        serveBytes(exchange, xml.getBytes(StandardCharsets.UTF_8),
                "application/xml; charset=UTF-8");
    }

    private static void serveBytes(HttpExchange exchange,
                                   byte[] bytes,
                                   String contentType) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set(
                "Content-Length", String.valueOf(bytes.length));
        if ("HEAD".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
            return;
        }
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static void serveBulkDownloadFixture(HttpExchange exchange)
            throws IOException {
        String path = exchange.getRequestURI().getPath();
        if ("/folder/a.txt".equals(path)) {
            serveBytes(exchange, "alpha".getBytes(StandardCharsets.UTF_8),
                    "text/plain; charset=UTF-8");
            return;
        }
        if ("/nested/path/b.bin".equals(path)) {
            serveBytes(exchange, "bravo".getBytes(StandardCharsets.UTF_8),
                    "application/octet-stream");
            return;
        }
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<ListBucketResult><Name>bulk-download</Name>"
                + "<IsTruncated>false</IsTruncated>"
                + "<Contents><Key>folder/a.txt</Key><Size>5</Size>"
                + "</Contents>"
                + "<Contents><Key>nested/path/b.bin</Key><Size>5</Size>"
                + "</Contents></ListBucketResult>";
        serveBytes(exchange, xml.getBytes(StandardCharsets.UTF_8),
                "application/xml; charset=UTF-8");
    }

    private static void serveEmptyMediaFixture(HttpExchange exchange)
            throws IOException {
        if ("/empty.mp4".equals(exchange.getRequestURI().getPath())) {
            exchange.getResponseHeaders().set(
                    "Content-Type", "video/mp4");
            exchange.getResponseHeaders().set("Content-Length", "0");
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
            return;
        }
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<ListBucketResult><Name>empty-media</Name>"
                + "<IsTruncated>false</IsTruncated>"
                + "<Contents><Key>empty.mp4</Key><Size>0</Size>"
                + "</Contents></ListBucketResult>";
        byte[] bytes = xml.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set(
                "Content-Type", "application/xml; charset=UTF-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static void serveFilterFixture(HttpExchange exchange,
                                           AtomicInteger listingRequests)
            throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (!"/".equals(path)) {
            byte[] body = "cached extension fixture"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set(
                    "Content-Type", "text/plain; charset=UTF-8");
            exchange.getResponseHeaders().set(
                    "Content-Length", String.valueOf(body.length));
            if ("HEAD".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
                return;
            }
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
            return;
        }

        listingRequests.incrementAndGet();
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<ListBucketResult><Name>filter-fixture</Name>"
                + "<IsTruncated>false</IsTruncated>"
                + "<Contents><Key>folder/a.txt</Key><Size>240</Size></Contents>"
                + "<Contents><Key>B.TXT</Key><Size>12</Size></Contents>"
                + "<Contents><Key>video.mp4</Key><Size>1024</Size></Contents>"
                + "<Contents><Key>README</Key><Size>99</Size></Contents>"
                + "<Contents><Key>.env</Key><Size>5</Size></Contents>"
                + "</ListBucketResult>";
        byte[] bytes = xml.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set(
                "Content-Type", "application/xml; charset=UTF-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static byte[] archiveFixture() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ZipOutputStream zip = new ZipOutputStream(bytes);
        zip.putNextEntry(new ZipEntry("folder/readme.txt"));
        zip.write("archive entry preview".getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
        zip.putNextEntry(new ZipEntry("root.txt"));
        zip.write("root entry".getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
        zip.finish();
        zip.close();
        return bytes.toByteArray();
    }

    private static byte[] archiveSearchFixture() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("inner/config.txt"));
            zip.write("upstream=domain.example.org"
                    .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static byte[] zipWithEntry(String entryName, byte[] content)
            throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry(entryName));
            zip.write(content);
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static Path createLargeTar(Path target, long entrySize)
            throws IOException {
        byte[] zeros = new byte[1024 * 1024];
        try (OutputStream file = Files.newOutputStream(target);
             TarArchiveOutputStream tar =
                     new TarArchiveOutputStream(file)) {
            TarArchiveEntry entry =
                    new TarArchiveEntry("payload.bin");
            entry.setSize(entrySize);
            tar.putArchiveEntry(entry);
            long remaining = entrySize;
            while (remaining > 0) {
                int count = (int) Math.min(zeros.length, remaining);
                tar.write(zeros, 0, count);
                remaining -= count;
            }
            tar.closeArchiveEntry();
            tar.finish();
        }
        return target;
    }

    private static TreeItem<?> descendant(
            TreeItem<?> root, String... path) {
        TreeItem<?> current = root;
        for (String segment : path) {
            if (current == null) {
                return null;
            }
            TreeItem<?> next = null;
            for (TreeItem<?> child : current.getChildren()) {
                if (segment.equals(String.valueOf(child.getValue()))) {
                    next = child;
                    break;
                }
            }
            current = next;
        }
        return current;
    }

    private static Button findButton(HBox root, String text) {
        Button found = findButtonRecursively(root, text);
        if (found != null) {
            return found;
        }
        throw new AssertionError("Button not found: " + text);
    }

    private static void inspectAndCloseSettingsDialog(
            AtomicReference<Throwable> failure) {
        DialogPane pane = null;
        try {
            pane = findShowingDialogPane();
            assertNotNull(pane);
            assertTrue(pane.getContent() instanceof GridPane);
            GridPane grid = (GridPane) pane.getContent();
            assertEquals(4, grid.getColumnConstraints().size());
            CheckBox kkEnabled = (CheckBox)
                    pane.lookup("#kkFileViewEnabledCheckBox");
            TextField kkUrl = (TextField)
                    pane.lookup("#kkFileViewUrlField");
            CheckBox ffmpegEnabled = (CheckBox)
                    pane.lookup("#ffmpegEnabledCheckBox");
            TextField ffmpegPath = (TextField)
                    pane.lookup("#ffmpegPathField");
            Button browse = (Button)
                    pane.lookup("#ffmpegBrowseButton");
            assertNotNull(kkEnabled);
            assertNotNull(kkUrl);
            assertNotNull(ffmpegEnabled);
            assertNotNull(ffmpegPath);
            assertNotNull(browse);
            assertEquals(Integer.valueOf(0),
                    GridPane.getColumnIndex(kkEnabled));
            assertEquals(Integer.valueOf(0),
                    GridPane.getColumnIndex(ffmpegEnabled));
        } catch (Throwable throwable) {
            failure.set(throwable);
        } finally {
            if (pane != null) {
                for (ButtonType type : pane.getButtonTypes()) {
                    if (type.getButtonData()
                            == ButtonBar.ButtonData.CANCEL_CLOSE) {
                        ((Button) pane.lookupButton(type)).fire();
                        break;
                    }
                }
            }
        }
    }

    private static void editAndConfirmSettingsDialog(
            AtomicReference<Throwable> failure) {
        DialogPane pane = null;
        try {
            pane = findShowingDialogPane();
            assertNotNull(pane);
            ((CheckBox) pane.lookup(
                    "#kkFileViewEnabledCheckBox")).setSelected(true);
            ((TextField) pane.lookup(
                    "#kkFileViewUrlField")).setText(
                    "http://127.0.0.1:8181/kk");
            ((CheckBox) pane.lookup(
                    "#ffmpegEnabledCheckBox")).setSelected(false);
            ((TextField) pane.lookup(
                    "#ffmpegPathField")).setText(
                    "C:\\tools\\ffmpeg.exe");
            for (ButtonType type : pane.getButtonTypes()) {
                if (type.getButtonData()
                        == ButtonBar.ButtonData.OK_DONE) {
                    ((Button) pane.lookupButton(type)).fire();
                    return;
                }
            }
            throw new AssertionError("Confirm button not found");
        } catch (Throwable throwable) {
            failure.set(throwable);
            closeDialog(pane);
        }
    }

    private static DialogPane findShowingDialogPane() {
        DialogPane pane = null;
        java.util.Iterator<Window> windows =
                Window.impl_getWindows();
        while (windows.hasNext()) {
            Window window = windows.next();
            if (window.isShowing()
                    && window.getScene() != null
                    && window.getScene().getRoot()
                    instanceof DialogPane) {
                pane = (DialogPane) window.getScene().getRoot();
            }
        }
        return pane;
    }

    private static void closeDialog(DialogPane pane) {
        if (pane == null) {
            return;
        }
        for (ButtonType type : pane.getButtonTypes()) {
            if (type.getButtonData()
                    == ButtonBar.ButtonData.CANCEL_CLOSE) {
                ((Button) pane.lookupButton(type)).fire();
                return;
            }
        }
    }

    private static mainController.FileTypeFilter findFilter(
            ComboBox<mainController.FileTypeFilter> comboBox,
            String extension) {
        for (mainController.FileTypeFilter filter : comboBox.getItems()) {
            if (extension.equals(filter.getExtension())) {
                return filter;
            }
        }
        throw new AssertionError("Filter not found: " + extension);
    }

    private static void assertFilter(
            ComboBox<mainController.FileTypeFilter> comboBox,
            String extension,
            int count) {
        assertEquals(count, findFilter(comboBox, extension).getCount());
    }

    private static Button findButtonRecursively(javafx.scene.Parent parent, String text) {
        for (javafx.scene.Node node : parent.getChildrenUnmodifiable()) {
            if (node instanceof Button && text.equals(((Button) node).getText())) {
                return (Button) node;
            }
            if (node instanceof javafx.scene.Parent) {
                Button found = findButtonRecursively((javafx.scene.Parent) node, text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static void waitFor(Callable<Boolean> condition, long timeoutMillis)
            throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.call()) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Timed out waiting for asynchronous preview");
    }

    private static <T> T runOnFxThread(Callable<T> action) throws Exception {
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                result.set(action.call());
            } catch (Throwable throwable) {
                failure.set(throwable);
            } finally {
                completed.countDown();
            }
        });
        if (!completed.await(10, TimeUnit.SECONDS)) {
            throw new AssertionError("Timed out waiting for JavaFX application thread");
        }
        Throwable throwable = failure.get();
        if (throwable instanceof Exception) {
            throw (Exception) throwable;
        }
        if (throwable instanceof Error) {
            throw (Error) throwable;
        }
        return result.get();
    }
}
