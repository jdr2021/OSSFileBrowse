package net.jdr2021.controller;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.concurrent.Task;
import javafx.embed.swing.SwingFXUtils;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeTableCell;
import javafx.scene.control.TreeTableColumn;
import javafx.scene.control.TreeTableView;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.image.ImageView;
import javafx.scene.media.Media;
import javafx.scene.media.MediaException;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.scene.web.WebView;
import javafx.stage.FileChooser;
import javafx.util.Duration;
import net.jdr2021.bucket.BucketClient;
import net.jdr2021.bucket.BucketListing;
import net.jdr2021.bucket.BucketObject;
import net.jdr2021.bucket.ObjectUrlBuilder;
import net.jdr2021.media.JavaCvMediaPlayer;
import net.jdr2021.preview.ArchiveBrowser;
import net.jdr2021.preview.ArchivePreviewRenderer;
import net.jdr2021.preview.FormatDetector;
import net.jdr2021.preview.HtmlPreviewRenderer;
import net.jdr2021.preview.PreviewFormat;
import net.jdr2021.preview.PagedPdfContentProvider;
import net.jdr2021.preview.PagedPdfPreviewPage;
import net.jdr2021.preview.PreviewRequest;
import net.jdr2021.preview.PreviewResult;
import net.jdr2021.preview.PreviewService;
import net.jdr2021.preview.UnknownPreviewRenderer;
import net.jdr2021.preview.http.LimitedReadResult;
import net.jdr2021.preview.http.LocalContentResponse;
import net.jdr2021.preview.http.PreviewHttpServer;
import net.jdr2021.preview.http.RegisteredObject;
import net.jdr2021.preview.http.RegisteredLocalContent;
import net.jdr2021.search.BucketSearchService;
import net.jdr2021.search.SearchReport;
import net.jdr2021.search.SearchReportWriter;
import net.jdr2021.search.SearchRule;
import net.jdr2021.search.SearchRuleLoader;
import net.jdr2021.search.SearchRuleSelector;
import net.jdr2021.search.SearchTarget;
import net.jdr2021.utils.BucketArtifactPaths;
import net.jdr2021.utils.ConfigLoader;
import net.jdr2021.utils.FfmpegSettings;
import net.jdr2021.utils.KkFileViewSettings;
import net.jdr2021.utils.PreviewSizeSettings;
import net.jdr2021.utils.RequestHeaderPolicy;
import net.jdr2021.utils.RuntimeDiagnostics;
import net.jdr2021.utils.TlsPolicy;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.awt.image.BufferedImage;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.PatternSyntaxException;

/**
 * JavaFX controller for OSSFileBrowse 2.0.
 *
 * <p>The original three-column UI is retained. Remote data is registered with
 * a loopback-only gateway and rendered in memory, with no external conversion
 * service in the preview path.</p>
 */
public class mainController {
    private static final int PROBE_BYTES = 64 * 1024;
    private static final int HTML_SAMPLE_BYTES = 8 * 1024 * 1024;
    private static final int MAX_IN_MEMORY_PREVIEW_BYTES =
            PreviewHttpServer.MAX_LIMITED_READ_BYTES;
    private static final int MAX_MEDIA_INITIALIZATION_ATTEMPTS = 2;

    @FXML
    private TextField tf_oss_url;
    @FXML
    private Label previewStatusLabel;
    @FXML
    private WebView webView;
    @FXML
    private VBox mediaContainer;
    @FXML
    private MediaView mediaView;
    @FXML
    private ImageView javaCvImageView;
    @FXML
    private Label mediaTitleLabel;
    @FXML
    private Label mediaTimeLabel;
    @FXML
    private Button mediaPlayPauseButton;
    @FXML
    private Slider mediaProgressSlider;
    @FXML
    private TreeTableView<String> treeView;
    @FXML
    private TreeTableColumn<String, String> pathColumn;
    @FXML
    private TreeTableColumn<String, Long> sizeColumn;
    @FXML
    private ComboBox<FileTypeFilter> fileTypeFilterComboBox;
    @FXML
    private TextField fileNameSearchField;
    @FXML
    private Button fileNameSearchButton;
    @FXML
    private VBox customHeadersBox;
    @FXML
    private Button loadButton;
    @FXML
    private Button downloadButton;
    @FXML
    private Button downloadAllButton;
    @FXML
    private Button settingsButton;
    @FXML
    private TextField searchRegexField;
    @FXML
    private CheckBox includeRulesCheckBox;
    @FXML
    private Button searchButton;

    private final List<HeaderRow> customHeaderRows = new ArrayList<>();
    private final List<PreviewEntry> entries = new ArrayList<>();
    private final List<TreeItem<String>> visibleTreeItems = new ArrayList<>();
    private final Map<TreeItem<String>, PreviewEntry> entryByTreeItem =
            new IdentityHashMap<>();
    private final Map<TreeItem<String>, ArchiveTreeEntry> archiveEntryByTreeItem =
            new IdentityHashMap<>();
    private final Map<TreeItem<String>, Long> sizeByTreeItem =
            new IdentityHashMap<>();
    private final Set<String> allowedExtensions = loadAllowedExtensions();

    private Map<String, String> activeRequestHeaders = Collections.emptyMap();
    private URI activeListingUri;
    private PreviewHttpServer previewServer;
    private PreviewService previewService;
    private RegisteredObject activeRegisteredObject;
    private RegisteredLocalContent activeLocalContent;
    private PreviewEntry activeEntry;
    private ArchiveTreeEntry activeArchiveEntry;
    private Task<PreparedBucket> bucketTask;
    private Task<PreviewOutcome> previewTask;
    private Task<Void> downloadTask;
    private Task<BulkDownloadResult> bulkDownloadTask;
    private Task<FfmpegSettings.ProbeResult> ffmpegSettingsTask;
    private Task<SearchExecution> searchTask;
    private JavaCvMediaPlayer activeJavaCvPlayer;
    private MediaPlayer activeMediaPlayer;
    private final AtomicBoolean javaCvFramePending = new AtomicBoolean();
    private long mediaRequestGeneration;
    private int currentIndex = -1;
    private TreeItem<String> bucketRoot;
    private boolean updatingFileTypeFilters;
    private List<SearchRule> configuredSearchRules =
            Collections.emptyList();

    @FXML
    private void initialize() {
        previewService = new PreviewService();
        downloadButton.setDisable(true);
        downloadAllButton.setDisable(true);
        searchButton.setDisable(true);
        loadSearchRules();
        refreshSettingsButton();
        mediaContainer.setManaged(false);
        mediaContainer.setVisible(false);
        mediaProgressSlider.setOnMouseReleased(event -> seekMediaFromSlider());
        treeView.setShowRoot(false);
        configureTreeColumns();
        fileNameSearchButton.setTooltip(new Tooltip("按文件名和后缀执行模糊搜索"));
        fileTypeFilterComboBox.setDisable(true);
        fileNameSearchField.setDisable(true);
        fileNameSearchButton.setDisable(true);
        fileTypeFilterComboBox.setCellFactory(
                (ListView<FileTypeFilter> list) ->
                        createFileTypeFilterCell(false));
        fileTypeFilterComboBox.setButtonCell(
                createFileTypeFilterCell(true));
        fileTypeFilterComboBox.getSelectionModel().selectedItemProperty()
                .addListener((observable, oldValue, newValue) -> {
                    if (updatingFileTypeFilters || newValue == null) {
                        return;
                    }
                    if (newValue.isHeader()) {
                        updatingFileTypeFilters = true;
                        try {
                            if (oldValue != null && !oldValue.isHeader()) {
                                fileTypeFilterComboBox.getSelectionModel()
                                        .select(oldValue);
                            } else {
                                fileTypeFilterComboBox.getSelectionModel()
                                        .select(findAllFilterIndex());
                            }
                        } finally {
                            updatingFileTypeFilters = false;
                        }
                    } else {
                        applyFileTypeFilter(newValue);
                    }
                });
        fileNameSearchField.setOnAction(event -> ApplyFileNameSearch());
        searchRegexField.setOnAction(event -> SearchFiles());
        searchRegexField.textProperty().addListener(
                (observable, oldValue, newValue) ->
                        includeRulesCheckBox.setDisable(
                                newValue == null || newValue.trim().isEmpty()));
        includeRulesCheckBox.setDisable(true);
        treeView.getSelectionModel().selectedItemProperty().addListener(
                (observable, oldValue, newValue) -> handleNodeSelected(newValue));
        treeView.setOnSort(event ->
                Platform.runLater(this::synchronizeVisibleTreeItems));
        try {
            previewServer = PreviewHttpServer.start();
            setStatus("2.0 本机预览服务已就绪"
                    + (TlsPolicy.isCertificateValidationDisabled()
                    ? " · SSL 兼容模式" : " · SSL 严格校验"));
        } catch (IOException e) {
            loadButton.setDisable(true);
            setStatus("本机预览服务启动失败");
            loadErrorPage("启动失败", e);
        }
    }

    /**
     * Adds one request-header name/value pair directly below the bucket URL.
     */
    @FXML
    protected void addCustomHeaderRow() {
        TextField nameField = new TextField();
        nameField.setPromptText("请求头");
        nameField.setPrefWidth(68);
        nameField.setMaxWidth(Double.MAX_VALUE);

        TextField valueField = new TextField();
        valueField.setPromptText("值");
        valueField.setPrefWidth(68);
        valueField.setMaxWidth(Double.MAX_VALUE);

        Button removeButton = new Button("×");
        removeButton.setFocusTraversable(false);
        removeButton.setMinWidth(24);
        removeButton.setPrefWidth(24);
        removeButton.setMaxWidth(24);

        HBox rowContainer = new HBox(3, nameField, valueField, removeButton);
        HBox.setHgrow(nameField, Priority.ALWAYS);
        HBox.setHgrow(valueField, Priority.ALWAYS);

        HeaderRow row = new HeaderRow(nameField, valueField);
        removeButton.setOnAction(event -> {
            customHeaderRows.remove(row);
            customHeadersBox.getChildren().remove(rowContainer);
        });
        customHeaderRows.add(row);
        customHeadersBox.getChildren().add(rowContainer);
        nameField.requestFocus();
    }

    /**
     * Returns a validated, case-insensitive snapshot. For duplicate names the
     * last row wins.
     */
    public Map<String, String> collectCustomRequestHeaders() {
        LinkedHashMap<String, String> rawHeaders = new LinkedHashMap<>();
        for (HeaderRow row : customHeaderRows) {
            String name = text(row.nameField).trim();
            String value = text(row.valueField).trim();
            if (!name.isEmpty() && !value.isEmpty()) {
                removeKeyIgnoreCase(rawHeaders, name);
                rawHeaders.put(name, value);
            }
        }
        return RequestHeaderPolicy.sanitize(rawHeaders);
    }

    public Map<String, String> getActiveRequestHeaders() {
        return activeRequestHeaders;
    }

    private void loadSearchRules() {
        try {
            SearchRuleLoader.Result loaded =
                    SearchRuleLoader.loadDefault();
            configuredSearchRules = loaded.getRules();
            System.out.println("[搜索] Rules.yml 已加载：有效规则="
                    + configuredSearchRules.size() + "；提示="
                    + loaded.getWarnings().size());
            for (String warning : loaded.getWarnings()) {
                System.err.println("[搜索] 规则提示：" + warning);
            }
        } catch (IOException failure) {
            configuredSearchRules = Collections.emptyList();
            RuntimeDiagnostics.logFailure(
                    "Rules.yml 加载失败", failure);
        }
    }

    /**
     * Searches the currently cached bucket entries. An empty editor uses the
     * bundled Rules.yml; a non-empty editor always adds one custom Java regex
     * and optionally includes the bundled rules.
     */
    @FXML
    protected void SearchFiles() {
        if (entries.isEmpty() || previewServer == null
                || !previewServer.isRunning()) {
            showAlert(Alert.AlertType.INFORMATION, "内容搜索", null,
                    "请先加载存储桶文件列表");
            return;
        }
        final String query = text(searchRegexField);
        final List<SearchRule> selectedRules;
        try {
            selectedRules = SearchRuleSelector.select(
                    configuredSearchRules, query,
                    includeRulesCheckBox.isSelected());
        } catch (PatternSyntaxException invalidRegex) {
            showAlert(Alert.AlertType.WARNING, "内容搜索", null,
                    "自定义正则格式错误："
                            + invalidRegex.getDescription()
                            + "（位置 " + invalidRegex.getIndex() + "）");
            searchRegexField.requestFocus();
            return;
        } catch (IllegalArgumentException invalidRegex) {
            showAlert(Alert.AlertType.WARNING, "内容搜索", null,
                    invalidRegex.getMessage());
            searchRegexField.requestFocus();
            return;
        }
        if (selectedRules.isEmpty()) {
            showAlert(Alert.AlertType.WARNING, "内容搜索", null,
                    "Rules.yml 当前没有可执行规则");
            return;
        }

        final List<SearchTarget> targets =
                new ArrayList<SearchTarget>(entries.size());
        for (PreviewEntry entry : entries) {
            targets.add(new SearchTarget(entry.object.getKey(),
                    entry.uri.toASCIIString(), entry.object.getSize()));
        }
        final Map<String, String> headers =
                Collections.unmodifiableMap(
                        new LinkedHashMap<String, String>(
                                activeRequestHeaders));
        final Path workingDirectory = Paths.get(
                System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
        final String bucketHost = BucketArtifactPaths.hostToken(
                activeListingUri);

        cancelSearchTask();
        final Task<SearchExecution> task =
                new Task<SearchExecution>() {
                    @Override
                    protected SearchExecution call() throws Exception {
                        BucketSearchService service =
                                new BucketSearchService(previewServer);
                        SearchReport report = service.search(
                                targets, headers, selectedRules, query,
                                this::isCancelled,
                                (current, total, fileName) -> updateMessage(
                                        "正在搜索 " + current + "/" + total
                                                + "：" + fileName));
                        if (isCancelled()) {
                            throw new CancellationException(
                                    "search cancelled");
                        }
                        Path reportPath = SearchReportWriter.write(
                                report, workingDirectory, bucketHost);
                        return new SearchExecution(report, reportPath);
                    }
                };
        searchTask = task;
        searchButton.setDisable(true);
        setStatus("准备搜索 " + targets.size() + " 个远程文件…");
        System.out.println("[搜索] 开始：文件=" + targets.size()
                + "；规则=" + selectedRules.size()
                + "；自定义正则=" + (query.trim().isEmpty() ? "<空>" : query)
                + "；输出目录=" + workingDirectory);
        task.messageProperty().addListener(
                (observable, oldValue, newValue) -> {
                    if (searchTask == task && newValue != null
                            && !newValue.isEmpty()) {
                        setStatus(newValue);
                    }
                });
        task.setOnSucceeded(event -> {
            if (searchTask != task) {
                return;
            }
            searchTask = null;
            searchButton.setDisable(entries.isEmpty());
            SearchExecution execution = task.getValue();
            showWebView();
            webView.getEngine().load(
                    execution.reportPath.toUri().toASCIIString());
            setStatus("搜索完成：匹配 "
                    + execution.report.getMatches().size()
                    + " 条 · " + execution.reportPath.getFileName());
            System.out.println("[搜索] 完成：匹配="
                    + execution.report.getMatches().size()
                    + "；远程文件="
                    + execution.report.getScannedFiles()
                    + "；压缩包条目="
                    + execution.report.getScannedArchiveEntries()
                    + "；报告=" + execution.reportPath);
        });
        task.setOnFailed(event -> {
            if (searchTask != task) {
                return;
            }
            searchTask = null;
            searchButton.setDisable(entries.isEmpty());
            RuntimeDiagnostics.logFailure(
                    "内容搜索失败", task.getException());
            setStatus("内容搜索失败");
            showAlert(Alert.AlertType.ERROR, "内容搜索", null,
                    userMessage(task.getException()));
        });
        task.setOnCancelled(event -> {
            if (searchTask == task) {
                searchTask = null;
                searchButton.setDisable(entries.isEmpty());
                setStatus("内容搜索已取消");
            }
        });
        startDaemon(task, "oss-content-search");
    }

    @FXML
    protected void Loading() {
        if (previewServer == null || !previewServer.isRunning()) {
            showAlert(Alert.AlertType.ERROR, "提示", null, "本机预览服务尚未就绪");
            return;
        }
        final URI listingUri;
        final Map<String, String> headers;
        try {
            listingUri = validateListingUri(tf_oss_url.getText());
            headers = snapshotCustomRequestHeaders();
        } catch (IllegalArgumentException e) {
            showAlert(Alert.AlertType.WARNING, "提示", null, e.getMessage());
            return;
        }
        RuntimeDiagnostics.bindRequestLog(listingUri);

        cancelBucketTask();
        cancelSearchTask();
        cancelBulkDownloadTask();
        releaseActivePreview();
        entries.clear();
        visibleTreeItems.clear();
        entryByTreeItem.clear();
        archiveEntryByTreeItem.clear();
        sizeByTreeItem.clear();
        currentIndex = -1;
        bucketRoot = null;
        treeView.setRoot(null);
        resetFileTypeFilters();
        fileNameSearchField.clear();
        fileNameSearchField.setDisable(true);
        fileNameSearchButton.setDisable(true);
        searchButton.setDisable(true);
        downloadAllButton.setDisable(true);
        activeListingUri = listingUri;
        activeRequestHeaders = headers;
        loadButton.setDisable(true);
        setStatus("正在读取存储桶列表…");
        System.out.println("[存储桶] 请求地址（Unicode）：" + listingUri);
        System.out.println("[存储桶] 请求地址（ASCII）：" + listingUri.toASCIIString());
        System.out.println("[存储桶] 自定义请求头名称：" + headers.keySet());

        Task<PreparedBucket> task = new Task<PreparedBucket>() {
            @Override
            protected PreparedBucket call() throws Exception {
                BucketListing listing =
                        new BucketClient().loadFirstPage(listingUri, headers);
                return prepareFiles(listingUri, listing);
            }
        };
        bucketTask = task;
        task.setOnSucceeded(event -> {
            if (bucketTask != task) {
                return;
            }
            loadButton.setDisable(false);
            populateFiles(task.getValue());
        });
        task.setOnFailed(event -> {
            if (bucketTask != task) {
                return;
            }
            loadButton.setDisable(false);
            Throwable failure = task.getException();
            RuntimeDiagnostics.logFailure("存储桶加载失败", failure);
            setStatus("存储桶加载失败");
            loadErrorPage("存储桶加载失败", failure);
            showAlert(Alert.AlertType.ERROR, "提示", null, userMessage(failure));
        });
        task.setOnCancelled(event -> {
            if (bucketTask == task) {
                loadButton.setDisable(false);
                setStatus("存储桶加载已取消");
            }
        });
        startDaemon(task, "oss-bucket-loader");
    }

    @FXML
    protected void PreviousFile() {
        synchronizeVisibleTreeItems();
        if (currentIndex <= 0 || visibleTreeItems.isEmpty()) {
            showAlert(Alert.AlertType.INFORMATION, "提示", null, "已经是第一个文件");
            return;
        }
        treeView.getSelectionModel().select(visibleTreeItems.get(currentIndex - 1));
    }

    @FXML
    protected void NextFile() {
        synchronizeVisibleTreeItems();
        if (currentIndex < 0 || currentIndex >= visibleTreeItems.size() - 1) {
            showAlert(Alert.AlertType.INFORMATION, "提示", null, "已经是最后一个文件");
            return;
        }
        treeView.getSelectionModel().select(visibleTreeItems.get(currentIndex + 1));
    }

    @FXML
    protected void DownloadSelected() {
        boolean archiveFileSelected = activeArchiveEntry != null;
        if ((!archiveFileSelected
                && (activeRegisteredObject == null || activeEntry == null))
                || downloadTask != null) {
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle(archiveFileSelected
                ? "保存压缩包内部文件" : "保存远程文件");
        chooser.setInitialFileName(safeFileName(archiveFileSelected
                ? activeArchiveEntry.entry.getPath()
                : activeEntry.object.getKey()));
        File target = chooser.showSaveDialog(webView.getScene().getWindow());
        if (target == null) {
            return;
        }

        final RegisteredObject registered = activeRegisteredObject;
        final ArchiveTreeEntry selectedArchiveEntry = activeArchiveEntry;
        final Path targetPath = target.toPath();
        Task<Void> task = new Task<Void>() {
            @Override
            protected Void call() throws Exception {
                if (selectedArchiveEntry != null) {
                    byte[] bytes = selectedArchiveEntry.browser.readEntry(
                            selectedArchiveEntry.entry);
                    writeBytesAtomically(bytes, targetPath, this);
                } else {
                    downloadFromProxy(
                            registered.getProxyUri().toURL(), targetPath, this);
                }
                return null;
            }
        };
        downloadTask = task;
        downloadButton.setDisable(true);
        setStatus("正在下载 " + (selectedArchiveEntry == null
                ? activeEntry.object.getKey()
                : selectedArchiveEntry.entry.getPath()));
        task.setOnSucceeded(event -> {
            if (downloadTask != task) {
                return;
            }
            downloadTask = null;
            downloadButton.setDisable(
                    activeRegisteredObject == null && activeArchiveEntry == null);
            setStatus("下载完成：" + targetPath.getFileName());
        });
        task.setOnFailed(event -> {
            if (downloadTask != task) {
                return;
            }
            downloadTask = null;
            downloadButton.setDisable(
                    activeRegisteredObject == null && activeArchiveEntry == null);
            RuntimeDiagnostics.logFailure("文件下载失败", task.getException());
            setStatus("下载失败");
            showAlert(Alert.AlertType.ERROR, "下载失败", null,
                    userMessage(task.getException()));
        });
        task.setOnCancelled(event -> {
            if (downloadTask == task) {
                downloadTask = null;
                downloadButton.setDisable(
                        activeRegisteredObject == null && activeArchiveEntry == null);
                setStatus("下载已取消");
            }
        });
        startDaemon(task, "oss-file-download");
    }

    /**
     * Downloads the complete in-memory bucket result into
     * {@code <domain-or-ip>_back}, preserving every object-key directory.
     */
    @FXML
    protected void DownloadAll() {
        if (entries.isEmpty() || activeListingUri == null
                || bulkDownloadTask != null || previewServer == null
                || !previewServer.isRunning()) {
            return;
        }
        final List<PreviewEntry> snapshot =
                new ArrayList<PreviewEntry>(entries);
        final Map<String, String> headers =
                Collections.unmodifiableMap(
                        new LinkedHashMap<String, String>(
                                activeRequestHeaders));
        final Path workingDirectory = Paths.get(
                System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
        final Path downloadRoot = BucketArtifactPaths.downloadRoot(
                workingDirectory, activeListingUri);
        final PreviewHttpServer server = previewServer;

        Task<BulkDownloadResult> task =
                new Task<BulkDownloadResult>() {
                    @Override
                    protected BulkDownloadResult call() throws Exception {
                        Files.createDirectories(downloadRoot);
                        int completed = 0;
                        for (int index = 0; index < snapshot.size(); index++) {
                            if (isCancelled()) {
                                throw new CancellationException(
                                        "bulk download cancelled");
                            }
                            PreviewEntry entry = snapshot.get(index);
                            Path target = BucketArtifactPaths.objectTarget(
                                    downloadRoot, entry.object.getKey());
                            updateMessage("正在下载 " + (index + 1)
                                    + "/" + snapshot.size() + "："
                                    + entry.object.getKey());
                            RegisteredObject registered = server.register(
                                    entry.uri.toASCIIString(), headers);
                            try {
                                downloadFromProxy(
                                        registered.getProxyUri().toURL(),
                                        target, this);
                                completed++;
                            } finally {
                                server.unregister(
                                        registered.getObjectId());
                            }
                        }
                        return new BulkDownloadResult(
                                downloadRoot, completed);
                    }
                };
        bulkDownloadTask = task;
        downloadAllButton.setDisable(true);
        setStatus("准备下载全部 " + snapshot.size() + " 个文件…");
        System.out.println("[全部下载] 开始：文件=" + snapshot.size()
                + "；目录=" + downloadRoot);
        task.messageProperty().addListener(
                (observable, oldValue, newValue) -> {
                    if (bulkDownloadTask == task && newValue != null
                            && !newValue.isEmpty()) {
                        setStatus(newValue);
                    }
                });
        task.setOnSucceeded(event -> {
            if (bulkDownloadTask != task) {
                return;
            }
            bulkDownloadTask = null;
            downloadAllButton.setDisable(entries.isEmpty());
            BulkDownloadResult result = task.getValue();
            setStatus("全部下载完成：" + result.completedFiles
                    + " 个文件 · " + result.downloadRoot);
            System.out.println("[全部下载] 完成：文件="
                    + result.completedFiles + "；目录="
                    + result.downloadRoot);
        });
        task.setOnFailed(event -> {
            if (bulkDownloadTask != task) {
                return;
            }
            bulkDownloadTask = null;
            downloadAllButton.setDisable(entries.isEmpty());
            RuntimeDiagnostics.logFailure(
                    "全部下载失败", task.getException());
            setStatus("全部下载失败");
            showAlert(Alert.AlertType.ERROR, "全部下载失败", null,
                    userMessage(task.getException()));
        });
        task.setOnCancelled(event -> {
            if (bulkDownloadTask == task) {
                bulkDownloadTask = null;
                downloadAllButton.setDisable(entries.isEmpty());
                setStatus("全部下载已取消");
            }
        });
        startDaemon(task, "oss-bulk-download");
    }

    /**
     * Opens the application settings panel. Every row has an explicit enable
     * checkbox; an unchecked value remains editable but does not participate
     * in preview routing.
     */
    @FXML
    protected void ShowSettings() {
        final Dialog<ButtonType> dialog = new Dialog<ButtonType>();
        dialog.setTitle("设置");
        dialog.setHeaderText("应用设置");
        if (settingsButton.getScene() != null) {
            dialog.initOwner(settingsButton.getScene().getWindow());
        }

        final CheckBox kkFileViewEnabledCheckBox = new CheckBox();
        kkFileViewEnabledCheckBox.setId("kkFileViewEnabledCheckBox");
        kkFileViewEnabledCheckBox.setSelected(
                KkFileViewSettings.isEnabled());
        kkFileViewEnabledCheckBox.setTooltip(new Tooltip(
                "勾选后，所有远端文件都交给 kkFileView 服务器预览"));
        final TextField kkFileViewUrlField =
                new TextField(KkFileViewSettings.configuredValue());
        kkFileViewUrlField.setId("kkFileViewUrlField");
        kkFileViewUrlField.setPromptText(
                KkFileViewSettings.DEFAULT_SERVER_URL);
        kkFileViewUrlField.setPrefColumnCount(42);

        final CheckBox ffmpegEnabledCheckBox = new CheckBox();
        ffmpegEnabledCheckBox.setId("ffmpegEnabledCheckBox");
        ffmpegEnabledCheckBox.setSelected(FfmpegSettings.isEnabled());
        ffmpegEnabledCheckBox.setTooltip(new Tooltip(
                "勾选后启用外部 FFmpeg 路径；路径失效时使用随包解码器"));
        final TextField ffmpegPathField =
                new TextField(FfmpegSettings.configuredValue());
        ffmpegPathField.setId("ffmpegPathField");
        ffmpegPathField.setPromptText(
                "可选；留空时使用随包 JavaCV/FFmpeg");
        ffmpegPathField.setPrefColumnCount(42);
        final Button browseButton = new Button("选择文件…");
        browseButton.setId("ffmpegBrowseButton");
        browseButton.setMaxWidth(Double.MAX_VALUE);
        browseButton.setOnAction(event ->
                chooseFfmpegExecutable(ffmpegPathField));

        Label enabledHeader = new Label("启用");
        Label fieldHeader = new Label("字段名");
        Label valueHeader = new Label("值");
        Label actionHeader = new Label("选择");
        String headerStyle = "-fx-font-size: 13px;"
                + " -fx-font-weight: bold; -fx-text-fill: #c62828;";
        enabledHeader.setStyle(headerStyle);
        fieldHeader.setStyle(headerStyle);
        valueHeader.setStyle(headerStyle);
        actionHeader.setStyle(headerStyle);

        GridPane settingsGrid = new GridPane();
        settingsGrid.setHgap(10);
        settingsGrid.setVgap(10);
        settingsGrid.setPadding(new Insets(6, 4, 8, 4));
        ColumnConstraints enabledColumn = new ColumnConstraints(44);
        ColumnConstraints fieldColumn = new ColumnConstraints(128);
        ColumnConstraints valueColumn = new ColumnConstraints();
        valueColumn.setHgrow(Priority.ALWAYS);
        ColumnConstraints actionColumn = new ColumnConstraints(92);
        settingsGrid.getColumnConstraints().addAll(
                enabledColumn, fieldColumn, valueColumn, actionColumn);
        settingsGrid.add(enabledHeader, 0, 0);
        settingsGrid.add(fieldHeader, 1, 0);
        settingsGrid.add(valueHeader, 2, 0);
        settingsGrid.add(actionHeader, 3, 0);
        settingsGrid.add(kkFileViewEnabledCheckBox, 0, 1);
        settingsGrid.add(new Label(
                KkFileViewSettings.CONFIG_PROPERTY), 1, 1);
        settingsGrid.add(kkFileViewUrlField, 2, 1);
        settingsGrid.add(new Label("—"), 3, 1);
        settingsGrid.add(ffmpegEnabledCheckBox, 0, 2);
        settingsGrid.add(new Label(
                FfmpegSettings.CONFIG_PROPERTY), 1, 2);
        settingsGrid.add(ffmpegPathField, 2, 2);
        settingsGrid.add(browseButton, 3, 2);

        ButtonType confirmType = new ButtonType(
                "确定", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelType = new ButtonType(
                "取消", ButtonBar.ButtonData.CANCEL_CLOSE);
        ButtonType resetType = new ButtonType(
                "重置", ButtonBar.ButtonData.OTHER);
        dialog.getDialogPane().getButtonTypes().addAll(
                confirmType, cancelType, resetType);
        dialog.getDialogPane().setContent(settingsGrid);
        dialog.getDialogPane().setPrefWidth(690);
        dialog.setResizable(true);

        Button resetButton =
                (Button) dialog.getDialogPane().lookupButton(resetType);
        resetButton.addEventFilter(ActionEvent.ACTION, event -> {
            kkFileViewEnabledCheckBox.setSelected(false);
            kkFileViewUrlField.setText(
                    KkFileViewSettings.DEFAULT_SERVER_URL);
            ffmpegEnabledCheckBox.setSelected(false);
            ffmpegPathField.clear();
            kkFileViewUrlField.requestFocus();
            event.consume();
        });

        Optional<ButtonType> result = dialog.showAndWait();
        if (!result.isPresent() || result.get() != confirmType) {
            return;
        }
        saveApplicationSettings(
                kkFileViewEnabledCheckBox.isSelected(),
                kkFileViewUrlField.getText(),
                ffmpegEnabledCheckBox.isSelected(),
                ffmpegPathField.getText());
    }

    private void saveApplicationSettings(boolean kkFileViewEnabled,
                                         String requestedKkFileViewUrl,
                                         boolean ffmpegEnabled,
                                         String requestedFfmpegPath) {
        cancelFfmpegSettingsTask();
        String kkFileViewUrl = requestedKkFileViewUrl == null
                ? "" : requestedKkFileViewUrl.trim();
        String ffmpegPath = requestedFfmpegPath == null
                ? "" : requestedFfmpegPath.trim();
        try {
            if (kkFileViewEnabled) {
                kkFileViewUrl = KkFileViewSettings.validateServerUri(
                        kkFileViewUrl).toString();
            }
            Map<String, String> settings =
                    new LinkedHashMap<String, String>();
            settings.put(KkFileViewSettings.ENABLED_PROPERTY,
                    Boolean.toString(kkFileViewEnabled));
            settings.put(KkFileViewSettings.CONFIG_PROPERTY,
                    kkFileViewUrl);
            settings.put(FfmpegSettings.ENABLED_PROPERTY,
                    Boolean.toString(ffmpegEnabled));
            settings.put(FfmpegSettings.CONFIG_PROPERTY, ffmpegPath);
            ConfigLoader.setProperties(settings);
        } catch (IOException | IllegalArgumentException failure) {
            RuntimeDiagnostics.logFailure("应用设置保存失败", failure);
            setStatus("应用设置保存失败");
            showAlert(Alert.AlertType.ERROR, "设置", null,
                    userMessage(failure));
            return;
        }

        refreshSettingsButton();
        applyPreviewSettingsToSelection();

        Path requested = parseConfiguredPath(ffmpegPath);
        if (ffmpegEnabled && requested != null
                && Files.isRegularFile(requested)) {
            validateAndSaveFfmpeg(requested);
            return;
        }

        if (kkFileViewEnabled) {
            setStatus("kkFileView 全局预览已启用");
        } else if (ffmpegEnabled && !ffmpegPath.isEmpty()) {
            setStatus("FFmpeg 路径未命中文件，使用随包 JavaCV/FFmpeg");
        } else {
            setStatus("应用设置已保存");
        }
        System.out.println("[设置] kkFileView="
                + (kkFileViewEnabled ? "启用" : "停用")
                + "；服务器=" + kkFileViewUrl
                + "；FFmpeg=" + (ffmpegEnabled ? "启用" : "停用")
                + "；路径=" + ffmpegPath);
    }

    private void chooseFfmpegExecutable(TextField targetField) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择 FFmpeg 可执行文件");
        if (isWindows()) {
            chooser.getExtensionFilters().add(
                    new FileChooser.ExtensionFilter(
                            "FFmpeg（ffmpeg.exe）", "ffmpeg.exe"));
        } else {
            chooser.getExtensionFilters().add(
                    new FileChooser.ExtensionFilter(
                            "FFmpeg（ffmpeg）", "ffmpeg"));
        }
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter(
                        "所有文件", isWindows() ? "*.*" : "*"));
        Path currentPath = parseConfiguredPath(targetField.getText());
        if (currentPath != null) {
            Path parent = Files.isDirectory(currentPath)
                    ? currentPath : currentPath.getParent();
            if (parent != null && Files.isDirectory(parent)) {
                chooser.setInitialDirectory(parent.toFile());
            }
            if (!Files.isDirectory(currentPath)
                    && currentPath.getFileName() != null) {
                chooser.setInitialFileName(
                        currentPath.getFileName().toString());
            }
        } else {
            chooser.setInitialFileName(
                    isWindows() ? "ffmpeg.exe" : "ffmpeg");
        }
        File selected = chooser.showOpenDialog(
                settingsButton.getScene() == null
                        ? null : settingsButton.getScene().getWindow());
        if (selected != null) {
            targetField.setText(selected.toPath().toAbsolutePath()
                    .normalize().toString());
            targetField.positionCaret(targetField.getText().length());
        }
    }

    private static Path parseConfiguredPath(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        try {
            return Paths.get(value.trim()).toAbsolutePath().normalize();
        } catch (RuntimeException invalidPath) {
            return null;
        }
    }

    private void validateAndSaveFfmpeg(final Path requested) {
        cancelFfmpegSettingsTask();
        Task<FfmpegSettings.ProbeResult> task =
                new Task<FfmpegSettings.ProbeResult>() {
                    @Override
                    protected FfmpegSettings.ProbeResult call()
                            throws Exception {
                        return FfmpegSettings.validateAndSave(requested);
                    }
                };
        ffmpegSettingsTask = task;
        settingsButton.setDisable(true);
        setStatus("正在检测 FFmpeg：" + requested.getFileName());
        System.out.println("[FFmpeg] 用户选择：" + requested.toAbsolutePath());
        task.setOnSucceeded(event -> {
            if (ffmpegSettingsTask != task) {
                return;
            }
            ffmpegSettingsTask = null;
            FfmpegSettings.ProbeResult result = task.getValue();
            refreshSettingsButton();
            setStatus("FFmpeg 已配置：" + result.getMessage());
            System.out.println("[FFmpeg] 配置完成："
                    + result.getExecutable() + "；" + result.getMessage());
            showAlert(Alert.AlertType.INFORMATION, "FFmpeg 设置", null,
                    "配置已保存到：\n" + ConfigLoader.getConfigPath()
                            + "\n\n" + result.getMessage());
        });
        task.setOnFailed(event -> {
            if (ffmpegSettingsTask != task) {
                return;
            }
            ffmpegSettingsTask = null;
            try {
                FfmpegSettings.saveSettings(
                        false, requested.toAbsolutePath().toString());
            } catch (IOException saveFailure) {
                RuntimeDiagnostics.logFailure(
                        "FFmpeg 失效状态保存失败", saveFailure);
            }
            refreshSettingsButton();
            RuntimeDiagnostics.logFailure(
                    "FFmpeg 配置失败", task.getException());
            setStatus("FFmpeg 配置失败");
            showAlert(Alert.AlertType.ERROR, "FFmpeg 设置", null,
                    userMessage(task.getException()));
        });
        task.setOnCancelled(event -> {
            if (ffmpegSettingsTask == task) {
                ffmpegSettingsTask = null;
                refreshSettingsButton();
                setStatus("FFmpeg 设置已取消");
            }
        });
        startDaemon(task, "ffmpeg-settings-probe");
    }

    private void refreshSettingsButton() {
        boolean kkFileViewEnabled = KkFileViewSettings.isEnabled();
        String kkFileViewUrl = KkFileViewSettings.configuredValue();
        Optional<Path> executable = FfmpegSettings.configuredExecutable();
        String configured = FfmpegSettings.configuredValue();
        settingsButton.setText("设置");
        if (kkFileViewEnabled) {
            try {
                URI server =
                        KkFileViewSettings.configuredServerUri();
                settingsButton.setTooltip(new Tooltip(
                        "预览模式：kkFileView 全局预览"
                                + "\n服务器：" + server
                                + "\n配置文件：" + ConfigLoader.getConfigPath()));
                settingsButton.setStyle("-fx-font-weight: bold;"
                        + " -fx-text-fill: #176b2c;");
            } catch (IllegalArgumentException invalid) {
                settingsButton.setTooltip(new Tooltip(
                        "kkFileView 已勾选，但服务器 URL 格式异常："
                                + kkFileViewUrl));
                settingsButton.setStyle("-fx-font-weight: bold;"
                        + " -fx-text-fill: #b3261e;");
            }
        } else if (executable.isPresent()) {
            settingsButton.setTooltip(new Tooltip(
                    "kkFileView：停用"
                            + "\nFFmpeg：" + executable.get()
                            + "\n媒体主播放器：随包 JavaCV/FFmpeg"
                            + "\n配置文件：" + ConfigLoader.getConfigPath()));
            settingsButton.setStyle("-fx-font-weight: bold;"
                    + " -fx-text-fill: #176b2c;");
        } else if (FfmpegSettings.isEnabled()
                && !configured.isEmpty()) {
            settingsButton.setTooltip(new Tooltip(
                    "原路径：" + configured
                            + "\n当前使用随包 JavaCV/FFmpeg。"));
            settingsButton.setStyle("-fx-font-weight: bold;"
                    + " -fx-text-fill: #b3261e;");
        } else {
            settingsButton.setTooltip(new Tooltip(
                    "当前使用随包 JavaCV/FFmpeg；配置文件："
                            + ConfigLoader.getConfigPath()));
            settingsButton.setStyle("-fx-font-weight: bold;"
                    + " -fx-text-fill: #17324d;");
        }
        settingsButton.setDisable(false);
    }

    private void applyPreviewSettingsToSelection() {
        TreeItem<String> selected =
                treeView.getSelectionModel().getSelectedItem();
        TreeItem<String> topLevel = selectedTopLevel(selected);
        PreviewEntry target = entryByTreeItem.get(topLevel);
        if (KkFileViewSettings.isEnabled()) {
            for (PreviewEntry entry : entries) {
                if (entry.treeItem != null
                        && !entry.treeItem.getChildren().isEmpty()) {
                    clearArchiveChildren(entry.treeItem);
                }
            }
        }
        if (target == null) {
            return;
        }
        if (selected != target.treeItem) {
            treeView.getSelectionModel().select(target.treeItem);
        } else {
            loadPreview(target);
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT).contains("win");
    }

    private void configureTreeColumns() {
        treeView.setColumnResizePolicy(
                TreeTableView.CONSTRAINED_RESIZE_POLICY);
        pathColumn.setCellValueFactory(cell -> {
            TreeItem<String> item = cell.getValue();
            return new ReadOnlyStringWrapper(
                    item == null || item.getValue() == null
                            ? "" : item.getValue());
        });
        pathColumn.setComparator(String.CASE_INSENSITIVE_ORDER);
        sizeColumn.setCellValueFactory(cell ->
                new ReadOnlyObjectWrapper<Long>(
                        sizeByTreeItem.get(cell.getValue())));
        sizeColumn.setCellFactory(column ->
                new TreeTableCell<String, Long>() {
                    @Override
                    protected void updateItem(Long size, boolean empty) {
                        super.updateItem(size, empty);
                        setText(empty || size == null ? ""
                                : humanSize(size.longValue()));
                        setStyle("-fx-alignment: center;"
                                + " -fx-padding: 0 4;");
                    }
                });
        sizeColumn.setStyle("-fx-alignment: center;");
        pathColumn.setSortable(true);
        sizeColumn.setSortable(true);
    }

    private void synchronizeVisibleTreeItems() {
        visibleTreeItems.clear();
        if (bucketRoot != null) {
            visibleTreeItems.addAll(bucketRoot.getChildren());
        }
        TreeItem<String> selected = selectedTopLevel(
                treeView.getSelectionModel().getSelectedItem());
        currentIndex = selected == null
                ? -1 : visibleTreeItems.indexOf(selected);
    }

    private PreparedBucket prepareFiles(URI listingUri, BucketListing listing) {
        List<PreviewEntry> preparedEntries = new ArrayList<PreviewEntry>();
        System.out.println("[存储桶] 后台生成对象地址，Contents 总数："
                + listing.getObjects().size());
        int objectIndex = 0;
        for (BucketObject object : listing.getObjects()) {
            objectIndex++;
            System.out.println("[存储桶] Key[" + objectIndex + "]="
                    + object.getKey()
                    + "；Size=" + object.getSize()
                    + "；ETag=" + object.getEtag()
                    + "；LastModified=" + object.getLastModified());
            if (object.isDirectoryMarker()) {
                System.out.println("[存储桶] 跳过目录标记：" + object.getKey());
                continue;
            }
            if (!extensionAllowed(object.getKey())) {
                System.out.println("[存储桶] 扩展名策略跳过：" + object.getKey());
                continue;
            }
            try {
                URI objectUri = ObjectUrlBuilder.build(listingUri, object.getKey());
                System.out.println("[存储桶] 对象 URL（Unicode）：" + objectUri);
                System.out.println("[存储桶] 对象 URL（ASCII）："
                        + objectUri.toASCIIString());
                preparedEntries.add(new PreviewEntry(object, objectUri));
            } catch (IllegalArgumentException failure) {
                RuntimeDiagnostics.logFailure(
                        "对象地址构建失败，Key=" + object.getKey(), failure);
            }
        }
        System.out.println("[存储桶] 对象地址生成结束：树节点="
                + preparedEntries.size() + "，原始 Contents="
                + listing.getObjects().size());
        return new PreparedBucket(listing, preparedEntries);
    }

    private void populateFiles(PreparedBucket prepared) {
        BucketListing listing = prepared.listing;
        bucketRoot = new TreeItem<>("Files");
        List<TreeItem<String>> treeItems =
                new ArrayList<TreeItem<String>>(prepared.entries.size());
        for (PreviewEntry entry : prepared.entries) {
            TreeItem<String> item = new TreeItem<>(entry.object.getKey());
            entry.treeItem = item;
            entries.add(entry);
            visibleTreeItems.add(item);
            entryByTreeItem.put(item, entry);
            sizeByTreeItem.put(item, entry.object.getSize());
            treeItems.add(item);
        }
        bucketRoot.getChildren().setAll(treeItems);
        treeView.setRoot(bucketRoot);
        rebuildFileTypeFilters();
        fileNameSearchField.setDisable(entries.isEmpty());
        fileNameSearchButton.setDisable(entries.isEmpty());
        searchButton.setDisable(entries.isEmpty());
        downloadAllButton.setDisable(entries.isEmpty());
        if (visibleTreeItems.isEmpty()) {
            setStatus("存储桶为空，或没有符合扩展名策略的文件");
            loadInfoPage("存储桶为空", "没有找到可预览文件。");
            return;
        }
        if (listing.isTruncated()) {
            setStatus("一次请求已加载 " + visibleTreeItems.size()
                    + " 个文件（服务端响应仍有后续页）");
        } else {
            setStatus("一次请求已加载 " + visibleTreeItems.size() + " 个文件");
        }
        treeView.getSelectionModel().select(visibleTreeItems.get(0));
    }

    /**
     * Rebuilds the extension selector from the latest bucket response. The
     * selector and all subsequent tree switches operate on {@link #entries};
     * changing a filter never sends another bucket-list request.
     */
    private void rebuildFileTypeFilters() {
        LinkedHashMap<String, Integer> counts =
                new LinkedHashMap<String, Integer>();
        for (PreviewEntry entry : entries) {
            String extension = fileTypeOf(entry.object.getKey());
            Integer count = counts.get(extension);
            counts.put(extension, count == null ? 1 : count + 1);
        }

        List<String> extensions = new ArrayList<String>(counts.keySet());
        extensions.remove(FileTypeFilter.OTHER);
        Collections.sort(extensions);
        if (counts.containsKey(FileTypeFilter.OTHER)) {
            extensions.add(FileTypeFilter.OTHER);
        }

        List<FileTypeFilter> filters = new ArrayList<FileTypeFilter>();
        filters.add(FileTypeFilter.header());
        filters.add(FileTypeFilter.all(entries.size()));
        for (String extension : extensions) {
            filters.add(FileTypeFilter.extension(
                    extension, counts.get(extension)));
        }

        updatingFileTypeFilters = true;
        try {
            fileTypeFilterComboBox.getItems().setAll(filters);
            if (filters.size() > 1) {
                fileTypeFilterComboBox.getSelectionModel().select(1);
            }
        } finally {
            updatingFileTypeFilters = false;
        }
        fileTypeFilterComboBox.setDisable(entries.isEmpty());
        System.out.println("[筛选] 已建立内存后缀缓存：文件="
                + entries.size() + "；类型=" + Math.max(0, filters.size() - 2));
    }

    private int findAllFilterIndex() {
        for (int index = 0;
             index < fileTypeFilterComboBox.getItems().size();
             index++) {
            if (FileTypeFilter.ALL.equals(
                    fileTypeFilterComboBox.getItems().get(index)
                            .getExtension())) {
                return index;
            }
        }
        return -1;
    }

    private void resetFileTypeFilters() {
        updatingFileTypeFilters = true;
        try {
            fileTypeFilterComboBox.getSelectionModel().clearSelection();
            fileTypeFilterComboBox.getItems().clear();
        } finally {
            updatingFileTypeFilters = false;
        }
        fileTypeFilterComboBox.setDisable(true);
    }

    private void applyFileTypeFilter(FileTypeFilter filter) {
        applyCombinedTreeFilter(filter, text(fileNameSearchField));
    }

    @FXML
    protected void ApplyFileNameSearch() {
        if (bucketRoot == null) {
            return;
        }
        FileTypeFilter filter =
                fileTypeFilterComboBox.getSelectionModel().getSelectedItem();
        if (filter == null || filter.isHeader()) {
            int allIndex = findAllFilterIndex();
            if (allIndex >= 0) {
                filter = fileTypeFilterComboBox.getItems().get(allIndex);
            }
        }
        if (filter != null) {
            applyCombinedTreeFilter(filter, text(fileNameSearchField));
        }
    }

    private void applyCombinedTreeFilter(FileTypeFilter filter,
                                         String requestedName) {
        if (bucketRoot == null) {
            return;
        }

        String nameQuery = requestedName == null
                ? "" : requestedName.trim().toLowerCase(Locale.ROOT);
        TreeItem<String> selectedItem =
                treeView.getSelectionModel().getSelectedItem();
        TreeItem<String> selectedTopLevel = selectedTopLevel(selectedItem);
        List<TreeItem<String>> filtered = new ArrayList<TreeItem<String>>();
        for (PreviewEntry entry : entries) {
            if (filter.matches(fileTypeOf(entry.object.getKey()))
                    && fileNameMatches(entry.object.getKey(), nameQuery)) {
                filtered.add(entry.treeItem);
            }
        }

        bucketRoot.getChildren().setAll(filtered);
        visibleTreeItems.clear();
        visibleTreeItems.addAll(filtered);
        boolean selectionRemainsVisible = selectedTopLevel != null
                && filtered.contains(selectedTopLevel);
        if (selectionRemainsVisible) {
            currentIndex = filtered.indexOf(selectedTopLevel);
        } else {
            treeView.getSelectionModel().clearSelection();
            currentIndex = -1;
            releaseActivePreview();
            loadInfoPage("文件筛选",
                    "已从内存缓存显示 " + filterDescription(
                            filter, nameQuery)
                            + "，共 " + filtered.size() + " 个文件。");
        }

        String status = "筛选 " + filterDescription(filter, nameQuery)
                + "：显示 "
                + filtered.size() + " / " + entries.size()
                + " 个文件（内存缓存）";
        setStatus(status);
        System.out.println("[筛选] 使用内存缓存；类型="
                + filter.getDisplayName()
                + "；文件名模糊搜索="
                + (nameQuery.isEmpty() ? "<空>" : nameQuery)
                + "；显示=" + filtered.size()
                + "；缓存总数=" + entries.size());
    }

    private static boolean fileNameMatches(String key, String lowerQuery) {
        if (lowerQuery == null || lowerQuery.isEmpty()) {
            return true;
        }
        return fileNameOf(key).toLowerCase(Locale.ROOT)
                .contains(lowerQuery);
    }

    private static String fileNameOf(String key) {
        String normalized = key == null ? "" : key.replace('\\', '/');
        int query = normalized.indexOf('?');
        if (query >= 0) {
            normalized = normalized.substring(0, query);
        }
        int slash = normalized.lastIndexOf('/');
        return slash >= 0
                ? normalized.substring(slash + 1) : normalized;
    }

    private static String filterDescription(FileTypeFilter filter,
                                            String nameQuery) {
        if (nameQuery == null || nameQuery.isEmpty()) {
            return filter.getDisplayName();
        }
        return filter.getDisplayName() + " + 文件名“" + nameQuery + "”";
    }

    private TreeItem<String> selectedTopLevel(TreeItem<String> selectedItem) {
        PreviewEntry selectedEntry = entryByTreeItem.get(selectedItem);
        if (selectedEntry != null) {
            return selectedEntry.treeItem;
        }
        ArchiveTreeEntry archiveEntry = archiveEntryByTreeItem.get(selectedItem);
        return archiveEntry == null ? null : archiveEntry.owner.treeItem;
    }

    static String fileTypeOf(String key) {
        String normalized = key == null ? "" : key.replace('\\', '/');
        int query = normalized.indexOf('?');
        if (query >= 0) {
            normalized = normalized.substring(0, query);
        }
        int slash = normalized.lastIndexOf('/');
        String name = slash >= 0
                ? normalized.substring(slash + 1) : normalized;
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) {
            return FileTypeFilter.OTHER;
        }
        return name.substring(dot).toLowerCase(Locale.ROOT);
    }

    private static ListCell<FileTypeFilter> createFileTypeFilterCell(
            final boolean buttonCell) {
        return new ListCell<FileTypeFilter>() {
            private final Label extensionLabel = new Label();
            private final Region spacer = new Region();
            private final Label countLabel = new Label();
            private final HBox row = new HBox(8,
                    extensionLabel, spacer, countLabel);

            {
                HBox.setHgrow(spacer, Priority.ALWAYS);
                row.setMaxWidth(Double.MAX_VALUE);
                row.prefWidthProperty().bind(
                        widthProperty().subtract(24));
                countLabel.setMinWidth(56);
                countLabel.setStyle("-fx-alignment: center-right;");
            }

            @Override
            protected void updateItem(FileTypeFilter item, boolean empty) {
                super.updateItem(item, empty);
                setText(null);
                if (empty || item == null) {
                    setGraphic(null);
                    setMouseTransparent(false);
                    setStyle("");
                    return;
                }
                boolean header = item.isHeader();
                extensionLabel.setText(header
                        ? "文件类型" : item.getDisplayName());
                countLabel.setText(header
                        ? "数量" : String.valueOf(item.getCount()));
                setMouseTransparent(header);
                if (header || buttonCell) {
                    String titleStyle = "-fx-font-size: 14px;"
                            + " -fx-font-weight: bold;"
                            + " -fx-text-fill: #c62828;";
                    extensionLabel.setStyle(titleStyle);
                    countLabel.setStyle(titleStyle
                            + " -fx-alignment: center-right;");
                } else {
                    extensionLabel.setStyle("-fx-font-size: 13px;"
                            + " -fx-font-weight: bold;"
                            + " -fx-text-fill: #17324d;");
                    countLabel.setStyle("-fx-font-size: 13px;"
                            + " -fx-font-weight: bold;"
                            + " -fx-text-fill: #455a64;"
                            + " -fx-alignment: center-right;");
                }
                if (header) {
                    setStyle("-fx-background-color: #fff1f1;"
                            + " -fx-border-color: transparent transparent"
                            + " #d9a5a5 transparent;"
                            + " -fx-padding: 6 8;");
                } else if (buttonCell) {
                    setStyle("-fx-padding: 3 6;");
                } else {
                    setStyle("-fx-border-color: transparent transparent"
                            + " #e2e8ef transparent;"
                            + " -fx-padding: 6 8;");
                }
                setGraphic(row);
            }
        };
    }

    private void handleNodeSelected(TreeItem<String> selectedItem) {
        ArchiveTreeEntry archiveEntry = archiveEntryByTreeItem.get(selectedItem);
        if (archiveEntry != null) {
            if (KkFileViewSettings.isEnabled()) {
                System.out.println("[kkFileView] 压缩包内部节点切换为归档文件预览："
                        + archiveEntry.owner.uri.toASCIIString());
                loadPreview(archiveEntry.owner);
                return;
            }
            System.out.println("当前选择是压缩包内部文件："
                    + archiveEntry.owner.uri.toASCIIString()
                    + "!/" + archiveEntry.nestedPath);
            loadArchiveEntryPreview(archiveEntry);
            return;
        }
        PreviewEntry entry = entryByTreeItem.get(selectedItem);
        if (entry == null) {
            return;
        }
        synchronizeVisibleTreeItems();
        currentIndex = visibleTreeItems.indexOf(selectedItem);
        System.out.println("当前选择是：" + entry.uri.toASCIIString());
        loadPreview(entry);
    }

    private void loadPreview(final PreviewEntry entry) {
        cancelPreviewTask();
        cancelDownloadTask();
        releaseActiveObjectOnly();
        final Map<String, String> headers;
        final RegisteredObject registered;
        try {
            headers = snapshotCustomRequestHeaders();
            registered = previewServer.register(entry.uri.toASCIIString(), headers);
        } catch (RuntimeException e) {
            setStatus("预览请求配置错误");
            loadErrorPage("预览请求配置错误", e);
            return;
        }

        activeRequestHeaders = headers;
        activeRegisteredObject = registered;
        activeEntry = entry;
        downloadButton.setDisable(false);
        try {
            PreviewResult sizeLimited = configuredPreviewSizeResult(
                    entry.object.getKey(), entry.object.getSize());
            if (sizeLimited != null) {
                showWebView();
                webView.getEngine().loadContent(
                        sizeLimited.bodyAsUtf8(), "text/html");
                setStatus(statusText(entry, sizeLimited));
                System.out.println("[预览大小] 已按配置跳过："
                        + entry.object.getKey() + "；文件="
                        + entry.object.getSize() + " 字节；上限="
                        + PreviewSizeSettings.configuredValue());
                return;
            }
        } catch (IllegalArgumentException invalidSizeSetting) {
            RuntimeDiagnostics.logFailure(
                    "文件预览大小配置错误", invalidSizeSetting);
            setStatus("文件预览大小配置错误");
            loadErrorPage("文件预览大小配置错误", invalidSizeSetting);
            return;
        }
        if (KkFileViewSettings.isEnabled()) {
            showKkFileViewPreview(entry);
            return;
        }
        setStatus("正在识别 " + entry.object.getKey());

        Task<PreviewOutcome> task = new Task<PreviewOutcome>() {
            @Override
            protected PreviewOutcome call() throws Exception {
                return buildPreview(entry, registered);
            }
        };
        previewTask = task;
        task.setOnSucceeded(event -> {
            PreviewOutcome outcome = task.getValue();
            if (previewTask != task || activeRegisteredObject != registered) {
                if (previewServer != null && outcome != null && outcome.localContent != null) {
                    previewServer.unregisterLocal(outcome.localContent.getContentId());
                }
                return;
            }
            activeLocalContent = outcome.localContent;
            if (outcome.archiveBrowser != null) {
                populateArchiveTree(entry.treeItem, entry,
                        outcome.archiveBrowser, "");
            }
            if (outcome.mediaUri != null) {
                showNativeMedia(entry.object.getKey(), entry.uri,
                        outcome.mediaUri, outcome.mediaFormat);
                return;
            }
            PreviewResult result = outcome.result;
            showWebView();
            webView.getEngine().loadContent(result.bodyAsUtf8(), "text/html");
            setStatus(statusText(entry, result));
        });
        task.setOnFailed(event -> {
            if (previewTask != task || activeRegisteredObject != registered) {
                return;
            }
            RuntimeDiagnostics.logFailure("文件预览失败：" + entry.object.getKey(),
                    task.getException());
            setStatus("预览失败：" + entry.object.getKey());
            loadErrorPage("预览失败", task.getException());
        });
        task.setOnCancelled(event -> {
            PreviewOutcome outcome = task.getValue();
            if (previewServer != null && outcome != null && outcome.localContent != null) {
                previewServer.unregisterLocal(outcome.localContent.getContentId());
            }
            if (previewTask == task) {
                setStatus("预览已取消");
            }
        });
        startDaemon(task, "oss-preview-renderer");
    }

    private void showKkFileViewPreview(PreviewEntry entry) {
        try {
            URI previewUri =
                    KkFileViewSettings.buildPreviewUri(entry.uri);
            showWebView();
            webView.getEngine().load(previewUri.toASCIIString());
            setStatus("kkFileView 预览：" + entry.object.getKey());
            System.out.println("[kkFileView] 全局预览已生效");
            System.out.println("[kkFileView] 原文件 URL："
                    + entry.uri.toASCIIString());
            System.out.println("[kkFileView] 预览 URL："
                    + previewUri.toASCIIString());
            if (!activeRequestHeaders.isEmpty()) {
                System.out.println("[kkFileView] 原对象自定义请求头保留给本机下载；"
                        + "远端 kkFileView 按原始对象 URL 发起请求");
            }
        } catch (IllegalArgumentException | IllegalStateException failure) {
            RuntimeDiagnostics.logFailure(
                    "kkFileView 预览配置错误", failure);
            setStatus("kkFileView 预览配置错误");
            loadErrorPage("kkFileView 预览配置错误", failure);
        }
    }

    private void loadArchiveEntryPreview(final ArchiveTreeEntry selected) {
        cancelPreviewTask();
        cancelDownloadTask();
        releaseActiveObjectOnly();
        final String displayName = selected.nestedPath;
        setStatus("正在读取压缩包内部文件：" + displayName);

        Task<PreviewOutcome> task = new Task<PreviewOutcome>() {
            @Override
            protected PreviewOutcome call() throws Exception {
                PreviewResult sizeLimited = configuredPreviewSizeResult(
                        displayName, selected.entry.getSize());
                if (sizeLimited != null) {
                    return PreviewOutcome.of(sizeLimited);
                }
                byte[] bytes = selected.browser.readEntry(selected.entry);
                return buildInMemoryPreview(displayName, bytes);
            }
        };
        previewTask = task;
        task.setOnSucceeded(event -> {
            PreviewOutcome outcome = task.getValue();
            if (previewTask != task) {
                unregisterOutcome(outcome);
                return;
            }
            activeLocalContent = outcome.localContent;
            activeArchiveEntry = selected;
            downloadButton.setDisable(false);
            if (outcome.archiveBrowser != null) {
                populateArchiveTree(selected.treeItem, selected.owner,
                        outcome.archiveBrowser, selected.nestedPath);
            }
            if (outcome.mediaUri != null) {
                showNativeMedia(displayName,
                        selected.owner.uri,
                        outcome.mediaUri, outcome.mediaFormat);
                return;
            }
            showWebView();
            webView.getEngine().loadContent(
                    outcome.result.bodyAsUtf8(), "text/html");
            setStatus(statusText(displayName, outcome.result));
        });
        task.setOnFailed(event -> {
            if (previewTask != task) {
                return;
            }
            RuntimeDiagnostics.logFailure(
                    "压缩包内部文件预览失败：" + displayName,
                    task.getException());
            setStatus("压缩包内部文件预览失败：" + displayName);
            loadErrorPage("压缩包内部文件预览失败", task.getException());
        });
        task.setOnCancelled(event -> {
            unregisterOutcome(task.getValue());
            if (previewTask == task) {
                setStatus("预览已取消");
            }
        });
        startDaemon(task, "oss-archive-entry-preview");
    }

    private PreviewOutcome buildInMemoryPreview(String fileName, byte[] bytes)
            throws IOException {
        PreviewResult sizeLimited =
                configuredPreviewSizeResult(fileName, bytes.length);
        if (sizeLimited != null) {
            return PreviewOutcome.of(sizeLimited);
        }
        String contentType = guessContentType(fileName);
        PreviewFormat format =
                FormatDetector.detect(fileName, contentType, bytes);

        if (usesNativeMedia(format) && bytes.length == 0) {
            return PreviewOutcome.of(emptyMediaResult(fileName));
        }
        if (usesNativeMedia(format) || isProxyBacked(format)) {
            String contentName = safeFileName(fileName);
            String contentPath = "content/" + contentName;
            RegisteredLocalContent local = registerInMemoryContent(
                    contentPath, contentType, bytes);
            URI localUri = local.resolve(
                    "content/" + encodePathSegment(contentName));
            if (usesNativeMedia(format)) {
                return PreviewOutcome.localMedia(
                        local, localUri, format);
            }
            PreviewRequest request = new PreviewRequest(
                    fileName, contentType, bytes.length,
                    previewSample(bytes, format), localUri.toASCIIString(), format);
            try {
                return PreviewOutcome.local(
                        previewService.render(request), local);
            } catch (IOException | RuntimeException failure) {
                previewServer.unregisterLocal(local.getContentId());
                throw failure;
            }
        }

        PreviewRequest request = new PreviewRequest(
                fileName, contentType, bytes.length,
                bytes, null, format);
        if (format == PreviewFormat.PDF) {
            PagedPdfContentProvider pages =
                    new PagedPdfContentProvider(bytes);
            RegisteredLocalContent local = previewServer.registerLocal(pages);
            try {
                return PreviewOutcome.local(PagedPdfPreviewPage.create(
                        fileName, pages.getPageCount(), local), local);
            } catch (RuntimeException failure) {
                previewServer.unregisterLocal(local.getContentId());
                throw failure;
            }
        }
        if (isArchive(format)) {
            ArchiveBrowser browser =
                    ArchiveBrowser.open(fileName, format, bytes);
            PreviewResult rendered =
                    new ArchivePreviewRenderer().render(request, browser);
            return PreviewOutcome.archive(rendered, browser);
        }
        PreviewResult rendered = previewService.render(request);
        return PreviewOutcome.of(rendered);
    }

    private RegisteredLocalContent registerInMemoryContent(String contentPath,
                                                           String contentType,
                                                           byte[] bytes) {
        final LocalContentResponse content =
                LocalContentResponse.ok(contentType, bytes);
        return previewServer.registerLocal(relativePath -> {
            if (!contentPath.equals(relativePath)) {
                return LocalContentResponse.notFound();
            }
            return content;
        });
    }

    private static String encodePathSegment(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (Exception impossible) {
            return "preview.bin";
        }
    }

    private static byte[] previewSample(byte[] bytes, PreviewFormat format) {
        if (format == PreviewFormat.HTML) {
            return bytes.length <= HTML_SAMPLE_BYTES
                    ? bytes : Arrays.copyOf(bytes, HTML_SAMPLE_BYTES);
        }
        return bytes.length <= PROBE_BYTES
                ? bytes : Arrays.copyOf(bytes, PROBE_BYTES);
    }

    private void populateArchiveTree(TreeItem<String> archiveNode,
                                     PreviewEntry owner,
                                     ArchiveBrowser browser,
                                     String parentArchivePath) {
        if (archiveNode == null) {
            return;
        }
        clearArchiveChildren(archiveNode);
        Map<String, TreeItem<String>> nodes =
                new LinkedHashMap<String, TreeItem<String>>();
        int files = 0;
        for (ArchiveBrowser.Entry archiveEntry : browser.getEntries()) {
            String path = archiveEntry.getPath();
            if (path == null || path.isEmpty()) {
                continue;
            }
            String[] segments = path.split("/");
            TreeItem<String> parent = archiveNode;
            StringBuilder fullPath = new StringBuilder();
            for (int index = 0; index < segments.length; index++) {
                String segment = segments[index];
                if (segment.isEmpty() || ".".equals(segment)) {
                    continue;
                }
                if (fullPath.length() > 0) {
                    fullPath.append('/');
                }
                fullPath.append(segment);
                String nodePath = fullPath.toString();
                TreeItem<String> node = nodes.get(nodePath);
                if (node == null) {
                    node = new TreeItem<String>(segment);
                    nodes.put(nodePath, node);
                    parent.getChildren().add(node);
                }
                boolean last = index == segments.length - 1;
                if (last) {
                    if (archiveEntry.isDirectory()) {
                        node.setValue(segment);
                    } else {
                        node.setValue(segment);
                        sizeByTreeItem.put(node, archiveEntry.getSize());
                        if (archiveEntry.isReadable()) {
                            String nestedPath = parentArchivePath == null
                                    || parentArchivePath.isEmpty()
                                    ? archiveEntry.getPath()
                                    : parentArchivePath + "!/"
                                            + archiveEntry.getPath();
                            archiveEntryByTreeItem.put(node,
                                    new ArchiveTreeEntry(owner, browser,
                                            archiveEntry, node, nestedPath));
                            files++;
                        }
                    }
                }
                parent = node;
            }
        }
        archiveNode.setExpanded(true);
        treeView.refresh();
        String archiveLocation = parentArchivePath == null
                || parentArchivePath.isEmpty()
                ? owner.uri.toASCIIString()
                : owner.uri.toASCIIString() + "!/" + parentArchivePath;
        System.out.println("[压缩包] 已展开：" + archiveLocation
                + "；可预览文件=" + files
                + "；目录条目总数=" + browser.getEntries().size()
                + "；隐藏条目=" + browser.getRejectedCount());
    }

    private void clearArchiveChildren(TreeItem<String> archiveNode) {
        for (TreeItem<String> child : new ArrayList<TreeItem<String>>(
                archiveNode.getChildren())) {
            clearArchiveMappings(child);
        }
        archiveNode.getChildren().clear();
    }

    private void clearArchiveMappings(TreeItem<String> item) {
        archiveEntryByTreeItem.remove(item);
        sizeByTreeItem.remove(item);
        for (TreeItem<String> child : item.getChildren()) {
            clearArchiveMappings(child);
        }
    }

    private void unregisterOutcome(PreviewOutcome outcome) {
        if (previewServer != null && outcome != null
                && outcome.localContent != null) {
            previewServer.unregisterLocal(
                    outcome.localContent.getContentId());
        }
    }

    private PreviewOutcome buildPreview(PreviewEntry entry, RegisteredObject registered)
            throws IOException {
        long declaredSize = entry.object.getSize();
        if (declaredSize < 0) {
            OptionalLong size = previewServer.probeSize(registered.getObjectId());
            declaredSize = size.isPresent() ? size.getAsLong() : -1;
        }

        LimitedReadResult probe =
                previewServer.readAtMost(registered.getObjectId(), PROBE_BYTES);
        byte[] probeBytes = probe.getBytes();
        if (probe.getDetectedSize() >= 0) {
            declaredSize = probe.getDetectedSize();
        }
        String contentType = guessContentType(entry.object.getKey());
        PreviewFormat format = FormatDetector.detect(
                entry.object.getKey(), contentType, probeBytes);
        PreviewResult sizeLimited = configuredPreviewSizeResult(
                entry.object.getKey(), declaredSize);
        if (sizeLimited != null) {
            return PreviewOutcome.of(sizeLimited);
        }

        if (usesNativeMedia(format)
                && probeBytes.length == 0
                && (declaredSize == 0 || probe.getDetectedSize() == 0)) {
            return PreviewOutcome.of(
                    emptyMediaResult(entry.object.getKey()));
        }
        if (usesNativeMedia(format)) {
            return PreviewOutcome.media(registered.getProxyUri(), format);
        }

        if (isProxyBacked(format)) {
            PreviewRequest request = new PreviewRequest(entry.object.getKey(), contentType,
                    normalizedSize(declaredSize, probeBytes.length),
                    probeBytes, registered.getProxyUri().toASCIIString(), format);
            return PreviewOutcome.of(previewService.render(request));
        }

        int readLimit = readLimit(declaredSize);
        if (declaredSize > readLimit && requiresCompleteData(format)) {
            return PreviewOutcome.of(
                    largeFileResult(entry.object.getKey(), format, declaredSize, readLimit));
        }

        LimitedReadResult content =
                previewServer.readAtMost(registered.getObjectId(), readLimit);
        byte[] contentBytes = content.consumeBytes();
        long effectiveSize = declaredSize >= 0 ? declaredSize : content.getDetectedSize();
        sizeLimited = configuredPreviewSizeResult(
                entry.object.getKey(), effectiveSize);
        if (sizeLimited != null) {
            return PreviewOutcome.of(sizeLimited);
        }
        if (content.isTruncated()) {
            if (requiresCompleteData(format)) {
                return PreviewOutcome.of(largeFileResult(entry.object.getKey(), format,
                        normalizedSize(effectiveSize, (long) readLimit + 1), readLimit));
            }
        }

        PreviewRequest request = new PreviewRequest(entry.object.getKey(), contentType,
                normalizedSize(effectiveSize, contentBytes.length),
                contentBytes, registered.getProxyUri().toASCIIString(), format);
        if (format == PreviewFormat.PDF) {
            PagedPdfContentProvider pages =
                    new PagedPdfContentProvider(contentBytes);
            RegisteredLocalContent local = previewServer.registerLocal(pages);
            try {
                return PreviewOutcome.local(PagedPdfPreviewPage.create(
                        entry.object.getKey(), pages.getPageCount(), local),
                        local);
            } catch (RuntimeException failure) {
                previewServer.unregisterLocal(local.getContentId());
                throw failure;
            }
        }
        if (isArchive(format)) {
            ArchiveBrowser browser =
                    ArchiveBrowser.open(entry.object.getKey(), format, contentBytes);
            PreviewResult rendered =
                    new ArchivePreviewRenderer().render(request, browser);
            return PreviewOutcome.archive(rendered, browser);
        }
        PreviewResult rendered = previewService.render(request);
        if (content.isTruncated()
                && (isText(format) || format == PreviewFormat.UNKNOWN)) {
            return PreviewOutcome.of(addTruncatedTextBanner(rendered, readLimit));
        }
        return PreviewOutcome.of(rendered);
    }

    private static PreviewResult largeFileResult(String fileName,
                                                 PreviewFormat format,
                                                 long size,
                                                 int limit) {
        String body = "<div class=\"card\"><h1>"
                + HtmlPreviewRenderer.escape(fileName)
                + "</h1><p>已识别为 <strong>"
                + HtmlPreviewRenderer.escape(String.valueOf(format))
                + "</strong>，文件大小为 " + humanSize(size) + "。</p>"
                + "<p class=\"muted\">文件超过当前 JVM 单个内存缓冲区"
                + "可表示的范围（" + humanSize(limit)
                + "），请使用右侧“下载选中文件”。</p></div>";
        return PreviewResult.html(200,
                HtmlPreviewRenderer.document("下载提示", body), true);
    }

    private static PreviewResult configuredPreviewSizeResult(
            String fileName, long size) {
        OptionalLong configuredLimit =
                PreviewSizeSettings.configuredLimitBytes();
        if (size < 0 || !configuredLimit.isPresent()
                || size <= configuredLimit.getAsLong()) {
            return null;
        }
        long limit = configuredLimit.getAsLong();
        String body = "<div class=\"card\"><h1>"
                + HtmlPreviewRenderer.escape(fileName)
                + "</h1><p>文件大小为 <strong>"
                + humanSize(size) + "</strong>，超过 config.properties 中 "
                + "<code>" + PreviewSizeSettings.CONFIG_PROPERTY + "="
                + HtmlPreviewRenderer.escape(
                PreviewSizeSettings.configuredValue().toUpperCase(Locale.ROOT))
                + "</code> 设置的预览上限（"
                + humanSize(limit) + "）。</p>"
                + "<p class=\"muted\">可使用右侧下载功能保存文件。</p></div>";
        return PreviewResult.html(200,
                HtmlPreviewRenderer.document("文件预览大小提示", body), true);
    }

    private static PreviewResult emptyMediaResult(String fileName) {
        String body = "<div class=\"card danger\"><h1>"
                + HtmlPreviewRenderer.escape(fileName)
                + "</h1><p>该媒体对象的实际内容长度为 0 字节，"
                + "播放器没有可读取的音视频数据。</p>"
                + "<p class=\"muted\">请检查存储桶中的对象内容或重新上传媒体文件。</p>"
                + "</div>";
        return PreviewResult.html(200,
                HtmlPreviewRenderer.document("空媒体文件", body), true);
    }

    private static PreviewResult addTruncatedTextBanner(PreviewResult rendered, int limit) {
        String banner = "<div class=\"card danger\">当前只展示前 "
                + humanSize(limit) + "，完整内容可使用下载功能查看。</div>";
        String html = rendered.bodyAsUtf8().replace("<body>", "<body>" + banner);
        return PreviewResult.html(rendered.getStatus(), html, true);
    }

    private static int readLimit(long declaredSize) {
        if (declaredSize < 0
                || declaredSize > MAX_IN_MEMORY_PREVIEW_BYTES) {
            return MAX_IN_MEMORY_PREVIEW_BYTES;
        }
        return (int) declaredSize;
    }

    private static boolean requiresCompleteData(PreviewFormat format) {
        return format == PreviewFormat.PDF
                || format == PreviewFormat.DOCX
                || format == PreviewFormat.XLSX
                || format == PreviewFormat.PPTX
                || format == PreviewFormat.DOC
                || format == PreviewFormat.XLS
                || format == PreviewFormat.PPT
                || format == PreviewFormat.ZIP
                || format == PreviewFormat.JAR
                || format == PreviewFormat.TAR
                || format == PreviewFormat.SEVEN_Z
                || format == PreviewFormat.RAR
                || format == PreviewFormat.GZIP;
    }

    private static boolean isArchive(PreviewFormat format) {
        return format == PreviewFormat.ZIP
                || format == PreviewFormat.JAR
                || format == PreviewFormat.TAR
                || format == PreviewFormat.SEVEN_Z
                || format == PreviewFormat.RAR
                || format == PreviewFormat.GZIP;
    }

    private static boolean isProxyBacked(PreviewFormat format) {
        return format == PreviewFormat.IMAGE
                || format == PreviewFormat.HTML;
    }

    static boolean usesNativeMedia(PreviewFormat format) {
        return format == PreviewFormat.VIDEO || format == PreviewFormat.AUDIO;
    }

    private static boolean isText(PreviewFormat format) {
        return format == PreviewFormat.TEXT
                || format == PreviewFormat.JSON
                || format == PreviewFormat.XML
                || format == PreviewFormat.MARKDOWN
                || format == PreviewFormat.CODE;
    }

    private static String guessContentType(String fileName) {
        String guessed = URLConnection.guessContentTypeFromName(fileName);
        return guessed == null ? "application/octet-stream" : guessed;
    }

    private void releaseActivePreview() {
        cancelPreviewTask();
        cancelDownloadTask();
        releaseActiveObjectOnly();
        stopNativeMedia();
        webView.getEngine().load(null);
    }

    private void releaseActiveObjectOnly() {
        stopNativeMedia();
        if (activeRegisteredObject != null && previewServer != null) {
            previewServer.unregister(activeRegisteredObject.getObjectId());
        }
        activeRegisteredObject = null;
        if (activeLocalContent != null && previewServer != null) {
            previewServer.unregisterLocal(activeLocalContent.getContentId());
        }
        activeLocalContent = null;
        activeEntry = null;
        activeArchiveEntry = null;
        downloadButton.setDisable(true);
    }

    private void cancelBucketTask() {
        if (bucketTask != null && bucketTask.isRunning()) {
            bucketTask.cancel(true);
        }
        bucketTask = null;
    }

    private void cancelPreviewTask() {
        if (previewTask != null && previewTask.isRunning()) {
            previewTask.cancel(true);
        }
        previewTask = null;
    }

    private void cancelDownloadTask() {
        if (downloadTask != null && downloadTask.isRunning()) {
            downloadTask.cancel(true);
        }
        downloadTask = null;
    }

    private void cancelBulkDownloadTask() {
        Task<BulkDownloadResult> active = bulkDownloadTask;
        bulkDownloadTask = null;
        if (active != null && active.isRunning()) {
            active.cancel(true);
        }
    }

    private void cancelFfmpegSettingsTask() {
        if (ffmpegSettingsTask != null && ffmpegSettingsTask.isRunning()) {
            ffmpegSettingsTask.cancel(true);
        }
        ffmpegSettingsTask = null;
    }

    private void cancelSearchTask() {
        Task<SearchExecution> active = searchTask;
        searchTask = null;
        if (active != null && active.isRunning()) {
            active.cancel(true);
        }
    }

    /**
     * Called by the Application on window shutdown.
     */
    public void shutdown() {
        cancelBucketTask();
        cancelPreviewTask();
        cancelDownloadTask();
        cancelBulkDownloadTask();
        cancelFfmpegSettingsTask();
        cancelSearchTask();
        stopNativeMedia();
        if (previewServer != null) {
            previewServer.close();
            previewServer = null;
        }
    }

    private static void downloadFromProxy(URL proxyUrl, Path target, Task<?> task)
            throws IOException {
        Path parent = target.toAbsolutePath().getParent();
        if (parent == null) {
            throw new IOException("下载目标目录无效");
        }
        Files.createDirectories(parent);
        Path partial = Files.createTempFile(parent,
                "." + safeFileName(target.getFileName().toString()) + "-", ".part");
        boolean completed = false;
        URLConnection connection = proxyUrl.openConnection();
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(30_000);
        if (connection instanceof HttpURLConnection) {
            ((HttpURLConnection) connection).setInstanceFollowRedirects(false);
        }
        try (InputStream input = connection.getInputStream();
             OutputStream output = Files.newOutputStream(partial)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (task.isCancelled()) {
                    throw new CancellationException("download cancelled");
                }
                if (read > 0) {
                    output.write(buffer, 0, read);
                }
            }
            output.flush();
            try {
                Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
            }
            completed = true;
        } finally {
            if (connection instanceof HttpURLConnection) {
                ((HttpURLConnection) connection).disconnect();
            }
            if (!completed) {
                Files.deleteIfExists(partial);
            }
        }
    }

    private static void writeBytesAtomically(byte[] bytes,
                                             Path target,
                                             Task<?> task) throws IOException {
        Path parent = target.toAbsolutePath().getParent();
        if (parent == null) {
            throw new IOException("下载目标目录无效");
        }
        Files.createDirectories(parent);
        Path partial = Files.createTempFile(parent,
                "." + safeFileName(target.getFileName().toString())
                        + "-", ".part");
        boolean completed = false;
        try (OutputStream output = Files.newOutputStream(partial)) {
            int offset = 0;
            while (offset < bytes.length) {
                if (task.isCancelled()) {
                    throw new CancellationException("download cancelled");
                }
                int length = Math.min(64 * 1024, bytes.length - offset);
                output.write(bytes, offset, length);
                offset += length;
            }
            output.flush();
            try {
                Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
            }
            completed = true;
        } finally {
            if (!completed) {
                Files.deleteIfExists(partial);
            }
        }
    }

    private void loadInfoPage(String title, String message) {
        showWebView();
        String body = "<div class=\"card\"><h1>" + HtmlPreviewRenderer.escape(title)
                + "</h1><p>" + HtmlPreviewRenderer.escape(message) + "</p></div>";
        webView.getEngine().loadContent(
                HtmlPreviewRenderer.document(title, body), "text/html");
    }

    private void loadErrorPage(String title, Throwable failure) {
        showWebView();
        String message = userMessage(failure);
        String logLocation = RuntimeDiagnostics.getLogFile() == null
                ? "" : "<p class=\"muted\">详细日志："
                + HtmlPreviewRenderer.escape(
                RuntimeDiagnostics.getLogFile().toString())
                + "</p>";
        String body = "<div class=\"card danger\"><h1>"
                + HtmlPreviewRenderer.escape(title) + "</h1><p>"
                + HtmlPreviewRenderer.escape(message) + "</p>"
                + logLocation + "</div>";
        webView.getEngine().loadContent(
                HtmlPreviewRenderer.document(title, body), "text/html");
    }

    private static String statusText(PreviewEntry entry, PreviewResult result) {
        return statusText(entry.object.getKey(), result);
    }

    private static String statusText(String displayName, PreviewResult result) {
        return (result.isDownloadSuggested() ? "预览受限 · " : "预览完成 · ")
                + displayName;
    }

    private static URI validateListingUri(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("请输入存储桶地址");
        }
        URI uri;
        try {
            uri = URI.create(value.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("存储桶地址格式错误", e);
        }
        String scheme = uri.getScheme();
        if (scheme == null || (!"http".equalsIgnoreCase(scheme)
                && !"https".equalsIgnoreCase(scheme)) || uri.getHost() == null) {
            throw new IllegalArgumentException("存储桶地址必须使用 HTTP 或 HTTPS");
        }
        if (uri.getUserInfo() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("存储桶地址不接受用户信息或片段");
        }
        return uri;
    }

    private Map<String, String> snapshotCustomRequestHeaders() {
        return Collections.unmodifiableMap(
                new LinkedHashMap<>(collectCustomRequestHeaders()));
    }

    private boolean extensionAllowed(String key) {
        if (allowedExtensions.isEmpty()) {
            return true;
        }
        String lower = key.toLowerCase(Locale.ROOT);
        int query = lower.indexOf('?');
        if (query >= 0) {
            lower = lower.substring(0, query);
        }
        int dot = lower.lastIndexOf('.');
        String extension = dot < 0 ? "" : lower.substring(dot);
        return allowedExtensions.contains(extension);
    }

    private static Set<String> loadAllowedExtensions() {
        String configured = ConfigLoader.getProperty("allow.extensions");
        if (configured == null || configured.trim().isEmpty()) {
            return Collections.emptySet();
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String value : configured.split(",")) {
            String extension = value.trim().toLowerCase(Locale.ROOT);
            if (!extension.isEmpty()) {
                result.add(extension.startsWith(".") ? extension : "." + extension);
            }
        }
        return Collections.unmodifiableSet(result);
    }

    private static void removeKeyIgnoreCase(Map<String, String> map, String requested) {
        String match = null;
        for (String name : map.keySet()) {
            if (name.equalsIgnoreCase(requested)) {
                match = name;
                break;
            }
        }
        if (match != null) {
            map.remove(match);
        }
    }

    private static String safeFileName(String key) {
        String normalized = key == null ? "download" : key.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        String name = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        name = name.replaceAll("[<>:\"/\\\\|?*\\x00-\\x1F]", "_").trim();
        return name.isEmpty() ? "download" : name;
    }

    private static String text(TextField field) {
        return field.getText() == null ? "" : field.getText();
    }

    private static long normalizedSize(long declared, long fallback) {
        return declared >= 0 ? declared : fallback;
    }

    private static String humanSize(long bytes) {
        if (bytes < 0) {
            return "未知大小";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024L * 1024) {
            return String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f MiB", bytes / 1048576.0);
        }
        return String.format(Locale.ROOT, "%.1f GiB", bytes / 1073741824.0);
    }

    private static String userMessage(Throwable failure) {
        return RuntimeDiagnostics.userMessage(failure);
    }

    private void setStatus(String value) {
        previewStatusLabel.setText(value);
    }

    @FXML
    protected void ToggleMediaPlayback() {
        JavaCvMediaPlayer javaCvPlayer = activeJavaCvPlayer;
        if (javaCvPlayer != null) {
            javaCvPlayer.togglePlayback();
            return;
        }
        MediaPlayer player = activeMediaPlayer;
        if (player == null) {
            return;
        }
        MediaPlayer.Status status = player.getStatus();
        if (status == MediaPlayer.Status.PLAYING) {
            player.pause();
        } else if (status == MediaPlayer.Status.READY
                || status == MediaPlayer.Status.PAUSED
                || status == MediaPlayer.Status.STOPPED) {
            if (status == MediaPlayer.Status.STOPPED
                    && player.getCurrentTime().greaterThanOrEqualTo(
                    player.getTotalDuration())) {
                player.seek(Duration.ZERO);
            }
            player.play();
        }
    }

    private void showNativeMedia(String displayName,
                                 URI sourceUri,
                                 URI mediaUri,
                                 PreviewFormat format) {
        stopNativeMedia();
        long requestGeneration = mediaRequestGeneration;
        webView.getEngine().load(null);
        webView.setManaged(false);
        webView.setVisible(false);
        mediaContainer.setManaged(true);
        mediaContainer.setVisible(true);
        boolean video = format == PreviewFormat.VIDEO;
        javaCvImageView.setImage(null);
        javaCvImageView.setManaged(video);
        javaCvImageView.setVisible(video);
        mediaView.setManaged(false);
        mediaView.setVisible(false);
        mediaTitleLabel.setText((video ? "视频预览 · " : "音频预览 · ")
                + displayName);
        mediaPlayPauseButton.setText("播放");
        mediaPlayPauseButton.setDisable(true);
        mediaProgressSlider.setDisable(true);
        mediaProgressSlider.setMin(0);
        mediaProgressSlider.setMax(1);
        mediaProgressSlider.setValue(0);
        mediaTimeLabel.setText("00:00 / 00:00");
        setStatus("正在初始化媒体：" + displayName);
        System.out.println("[媒体] 使用 JavaCV Platform 内置 FFmpeg 解码");
        System.out.println("[媒体] 原始 URL：" + sourceUri);
        System.out.println("[媒体] 本机播放地址：" + mediaUri);
        initializeJavaCvMedia(displayName, sourceUri, mediaUri, format,
                requestGeneration);
    }

    private void initializeJavaCvMedia(String displayName,
                                       URI sourceUri,
                                       URI mediaUri,
                                       PreviewFormat format,
                                       long requestGeneration) {
        if (requestGeneration != mediaRequestGeneration) {
            return;
        }
        JavaCvMediaPlayer player = new JavaCvMediaPlayer(
                mediaUri.toASCIIString(),
                new JavaCvMediaPlayer.ListenerAdapter() {
                    @Override
                    public void onReady(JavaCvMediaPlayer.MediaInfo info) {
                        runOnFxThread(() -> {
                            if (requestGeneration != mediaRequestGeneration
                                    || activeJavaCvPlayer == null) {
                                return;
                            }
                            if (format == PreviewFormat.AUDIO
                                    && info.getAudioChannels() > 0
                                    && !info.isAudioOutputAvailable()) {
                                handleJavaCvFailure(
                                        displayName, sourceUri, mediaUri,
                                        format,
                                        new IllegalStateException(
                                                "Java Sound 音频输出设备未就绪"),
                                        requestGeneration);
                                return;
                            }
                            if (info.getDurationMicros() > 0L) {
                                mediaProgressSlider.setMax(
                                        info.getDurationMicros()
                                                / 1_000_000.0);
                            }
                            mediaPlayPauseButton.setDisable(false);
                            mediaProgressSlider.setDisable(
                                    info.getDurationMicros() <= 0L);
                            updateMediaTimeMicros(0L,
                                    info.getDurationMicros());
                            mediaTitleLabel.setText(
                                    (format == PreviewFormat.VIDEO
                                            ? "视频预览 · "
                                            : "音频预览 · ")
                                            + displayName
                                            + " · JavaCV/FFmpeg");
                            String status = "媒体已就绪 · JavaCV · "
                                    + displayName;
                            if (!info.isAudioOutputAvailable()
                                    && info.getAudioChannels() > 0) {
                                status += " · 当前音频输出设备未就绪";
                            }
                            setStatus(status);
                            System.out.println(
                                    "[JavaCV] 初始化完成；"
                                            + info.describe()
                                            + "；源地址=" + sourceUri);
                        });
                    }

                    @Override
                    public void onFrame(BufferedImage image,
                                        long timestampMicros) {
                        if (requestGeneration != mediaRequestGeneration
                                || !javaCvFramePending.compareAndSet(
                                false, true)) {
                            return;
                        }
                        BufferedImage snapshot;
                        try {
                            snapshot = JavaCvMediaPlayer.copyFrame(image);
                        } catch (RuntimeException copyFailure) {
                            javaCvFramePending.set(false);
                            throw copyFailure;
                        }
                        Platform.runLater(() -> {
                            try {
                                if (requestGeneration
                                        == mediaRequestGeneration
                                        && activeJavaCvPlayer != null) {
                                    javaCvImageView.setImage(
                                            SwingFXUtils.toFXImage(
                                                    snapshot, null));
                                }
                            } finally {
                                javaCvFramePending.set(false);
                            }
                        });
                    }

                    @Override
                    public void onProgress(long currentMicros,
                                           long durationMicros) {
                        runOnFxThread(() -> {
                            if (requestGeneration != mediaRequestGeneration
                                    || activeJavaCvPlayer == null) {
                                return;
                            }
                            if (!mediaProgressSlider.isValueChanging()) {
                                mediaProgressSlider.setValue(
                                        currentMicros / 1_000_000.0);
                            }
                            updateMediaTimeMicros(
                                    currentMicros, durationMicros);
                        });
                    }

                    @Override
                    public void onPlaying() {
                        runOnFxThread(() -> {
                            if (requestGeneration == mediaRequestGeneration
                                    && activeJavaCvPlayer != null) {
                                mediaPlayPauseButton.setText("暂停");
                                setStatus("正在播放 · JavaCV · "
                                        + displayName);
                            }
                        });
                    }

                    @Override
                    public void onPaused() {
                        runOnFxThread(() -> {
                            if (requestGeneration == mediaRequestGeneration
                                    && activeJavaCvPlayer != null) {
                                mediaPlayPauseButton.setText("播放");
                                setStatus("已暂停 · JavaCV · "
                                        + displayName);
                            }
                        });
                    }

                    @Override
                    public void onFinished() {
                        runOnFxThread(() -> {
                            if (requestGeneration == mediaRequestGeneration
                                    && activeJavaCvPlayer != null) {
                                mediaPlayPauseButton.setText("重播");
                                setStatus("播放完成 · JavaCV · "
                                        + displayName);
                            }
                        });
                    }

                    @Override
                    public void onError(Throwable failure) {
                        runOnFxThread(() -> handleJavaCvFailure(
                                displayName, sourceUri, mediaUri, format,
                                failure, requestGeneration));
                    }
                });
        activeJavaCvPlayer = player;
        player.start();
    }

    private void handleJavaCvFailure(String displayName,
                                     URI sourceUri,
                                     URI mediaUri,
                                     PreviewFormat format,
                                     Throwable failure,
                                     long requestGeneration) {
        if (requestGeneration != mediaRequestGeneration
                || activeJavaCvPlayer == null) {
            return;
        }
        System.err.println("[JavaCV] 初始化或播放失败；源地址="
                + sourceUri + "；本机地址=" + mediaUri);
        if (failure != null) {
            failure.printStackTrace(System.err);
        }
        disposeActiveJavaCvPlayer();
        setStatus("JavaCV 播放失败，正在切换 JavaFX · " + displayName);
        System.out.println("[媒体] 切换 JavaFX MediaView 回退播放器");
        initializeNativeMedia(displayName, sourceUri, mediaUri, format,
                requestGeneration, 1);
    }

    private void initializeNativeMedia(String displayName,
                                       URI sourceUri,
                                       URI mediaUri,
                                       PreviewFormat format,
                                       long requestGeneration,
                                       int attempt) {
        if (requestGeneration != mediaRequestGeneration) {
            return;
        }
        boolean video = format == PreviewFormat.VIDEO;
        javaCvImageView.setManaged(false);
        javaCvImageView.setVisible(false);
        mediaView.setManaged(video);
        mediaView.setVisible(video);
        mediaPlayPauseButton.setDisable(true);
        mediaProgressSlider.setDisable(true);
        System.out.println("[媒体] 初始化尝试 "
                + attempt + "/" + MAX_MEDIA_INITIALIZATION_ATTEMPTS
                + "；播放器=JavaFX；源地址=" + sourceUri);
        try {
            Media media = new Media(mediaUri.toASCIIString());
            MediaPlayer player = new MediaPlayer(media);
            activeMediaPlayer = player;
            mediaView.setMediaPlayer(player);

            media.setOnError(() -> handleMediaFailure(
                    displayName, sourceUri, mediaUri, format, player,
                    media.getError(), requestGeneration, attempt));
            player.setOnError(() -> handleMediaFailure(
                    displayName, sourceUri, mediaUri, format, player,
                    player.getError(), requestGeneration, attempt));
            player.setOnReady(() -> {
                if (requestGeneration != mediaRequestGeneration
                        || activeMediaPlayer != player) {
                    return;
                }
                Duration total = player.getTotalDuration();
                if (usableDuration(total)) {
                    mediaProgressSlider.setMax(total.toSeconds());
                }
                mediaPlayPauseButton.setDisable(false);
                mediaProgressSlider.setDisable(!usableDuration(total));
                updateMediaTime(player.getCurrentTime(), total);
                System.out.println("[媒体] 初始化完成；时长="
                        + formatDuration(total)
                        + "；源地址=" + sourceUri);
                setStatus("媒体已就绪 · " + displayName);
            });
            player.setOnPlaying(() -> {
                if (activeMediaPlayer == player) {
                    mediaPlayPauseButton.setText("暂停");
                    setStatus("正在播放 · " + displayName);
                }
            });
            Runnable paused = () -> {
                if (activeMediaPlayer == player) {
                    mediaPlayPauseButton.setText("播放");
                }
            };
            player.setOnPaused(paused);
            player.setOnStopped(paused);
            player.setOnEndOfMedia(() -> {
                if (activeMediaPlayer == player) {
                    mediaPlayPauseButton.setText("重播");
                    setStatus("播放完成 · " + displayName);
                }
            });
            player.currentTimeProperty().addListener(
                    (observable, oldTime, newTime) -> {
                        if (activeMediaPlayer == player
                                && !mediaProgressSlider.isValueChanging()) {
                            if (usableDuration(newTime)) {
                                mediaProgressSlider.setValue(newTime.toSeconds());
                            }
                            updateMediaTime(newTime, player.getTotalDuration());
                        }
                    });
        } catch (MediaException failure) {
            handleMediaFailure(displayName, sourceUri, mediaUri, format,
                    null, failure, requestGeneration, attempt);
        } catch (RuntimeException failure) {
            handleMediaFailure(displayName, sourceUri, mediaUri, format,
                    null, failure, requestGeneration, attempt);
        }
    }

    private void handleMediaFailure(String displayName,
                                    URI sourceUri,
                                    URI mediaUri,
                                    PreviewFormat format,
                                    MediaPlayer player,
                                    Throwable failure,
                                    long requestGeneration,
                                    int attempt) {
        if (requestGeneration != mediaRequestGeneration
                || (player != null && activeMediaPlayer != player)) {
            return;
        }
        System.err.println("[媒体] 初始化失败；尝试=" + attempt
                + "/" + MAX_MEDIA_INITIALIZATION_ATTEMPTS
                + "；源地址=" + sourceUri
                + "；本机地址=" + mediaUri);
        if (failure != null) {
            failure.printStackTrace(System.err);
        }
        disposeActiveMediaPlayer();
        if (attempt < MAX_MEDIA_INITIALIZATION_ATTEMPTS) {
            setStatus("媒体初始化失败，正在重试 · " + displayName);
            PauseTransition retryDelay =
                    new PauseTransition(Duration.millis(350));
            retryDelay.setOnFinished(event -> initializeNativeMedia(
                    displayName, sourceUri, mediaUri, format,
                    requestGeneration, attempt + 1));
            retryDelay.play();
            return;
        }
        setStatus("媒体预览失败 · " + displayName);
        RuntimeDiagnostics.logFailure(
                "媒体初始化重试结束：" + displayName, failure);
        loadMediaFailurePage(displayName, sourceUri, failure);
    }

    private void loadMediaFailurePage(String displayName,
                                      URI sourceUri,
                                      Throwable failure) {
        showWebView();
        String logLocation = RuntimeDiagnostics.getLogFile() == null
                ? "" : "<p class=\"muted\">详细日志："
                + HtmlPreviewRenderer.escape(
                RuntimeDiagnostics.getLogFile().toString()) + "</p>";
        String body = "<div class=\"card danger\"><h1>媒体预览失败</h1>"
                + "<p><strong>" + HtmlPreviewRenderer.escape(displayName)
                + "</strong> 已尝试 JavaCV/FFmpeg，并完成两次 "
                + "JavaFX 回退播放器初始化。</p>"
                + "<p>远端对象可继续使用“下载选中文件”保存；"
                + "结果通常取决于对象完整性、容器索引、编码参数以及 "
                + "音频输出设备状态。</p>"
                + "<p class=\"muted\">源地址："
                + HtmlPreviewRenderer.escape(sourceUri.toASCIIString())
                + "</p><p class=\"muted\">播放器信息："
                + HtmlPreviewRenderer.escape(userMessage(failure))
                + "</p>" + logLocation + "</div>";
        webView.getEngine().loadContent(
                HtmlPreviewRenderer.document("媒体预览失败", body),
                "text/html");
    }

    private void seekMediaFromSlider() {
        JavaCvMediaPlayer javaCvPlayer = activeJavaCvPlayer;
        if (javaCvPlayer != null && mediaProgressSlider.getMax() > 1) {
            javaCvPlayer.seekSeconds(mediaProgressSlider.getValue());
            return;
        }
        MediaPlayer player = activeMediaPlayer;
        if (player != null && mediaProgressSlider.getMax() > 1) {
            player.seek(Duration.seconds(mediaProgressSlider.getValue()));
        }
    }

    private void updateMediaTimeMicros(long currentMicros,
                                       long totalMicros) {
        updateMediaTime(
                Duration.millis(Math.max(0L, currentMicros) / 1000.0),
                Duration.millis(Math.max(0L, totalMicros) / 1000.0));
    }

    private void updateMediaTime(Duration current, Duration total) {
        mediaTimeLabel.setText(formatDuration(current)
                + " / " + formatDuration(total));
    }

    private static String formatDuration(Duration value) {
        if (!usableDuration(value)) {
            return "00:00";
        }
        long seconds = Math.max(0, Math.round(value.toSeconds()));
        long hours = seconds / 3600;
        long minutes = seconds % 3600 / 60;
        long remainingSeconds = seconds % 60;
        if (hours > 0) {
            return String.format(Locale.ROOT, "%d:%02d:%02d",
                    hours, minutes, remainingSeconds);
        }
        return String.format(Locale.ROOT, "%02d:%02d",
                minutes, remainingSeconds);
    }

    private static boolean usableDuration(Duration value) {
        return value != null && !value.isUnknown() && !value.isIndefinite()
                && !Double.isNaN(value.toMillis())
                && !Double.isInfinite(value.toMillis());
    }

    private void showWebView() {
        stopNativeMedia();
        webView.setManaged(true);
        webView.setVisible(true);
    }

    private void stopNativeMedia() {
        mediaRequestGeneration++;
        disposeActiveJavaCvPlayer();
        disposeActiveMediaPlayer();
        mediaContainer.setVisible(false);
        mediaContainer.setManaged(false);
    }

    private void disposeActiveJavaCvPlayer() {
        JavaCvMediaPlayer player = activeJavaCvPlayer;
        activeJavaCvPlayer = null;
        javaCvFramePending.set(false);
        javaCvImageView.setImage(null);
        javaCvImageView.setManaged(false);
        javaCvImageView.setVisible(false);
        if (player != null) {
            player.close();
        }
    }

    private void disposeActiveMediaPlayer() {
        MediaPlayer player = activeMediaPlayer;
        activeMediaPlayer = null;
        mediaView.setMediaPlayer(null);
        if (player != null) {
            try {
                player.stop();
            } catch (RuntimeException ignored) {
                // Dispose still releases native media resources.
            }
            player.dispose();
        }
    }

    private static void runOnFxThread(Runnable action) {
        if (Platform.isFxApplicationThread()) {
            action.run();
        } else {
            Platform.runLater(action);
        }
    }

    private static void startDaemon(Task<?> task, String threadName) {
        Thread thread = new Thread(task, threadName);
        thread.setDaemon(true);
        thread.start();
    }

    public static void showAlert(Alert.AlertType type,
                                 String title,
                                 String headerText,
                                 String contentText) {
        Alert alert = new Alert(type);
        alert.setTitle(title);
        alert.setHeaderText(headerText);
        alert.setContentText(contentText);
        alert.showAndWait();
    }

    private static final class HeaderRow {
        private final TextField nameField;
        private final TextField valueField;

        private HeaderRow(TextField nameField, TextField valueField) {
            this.nameField = nameField;
            this.valueField = valueField;
        }
    }

    static final class FileTypeFilter {
        static final String HEADER = "__header__";
        static final String ALL = "*";
        static final String OTHER = "other";

        private final String extension;
        private final String displayName;
        private final int count;

        private FileTypeFilter(String extension,
                               String displayName,
                               int count) {
            this.extension = extension;
            this.displayName = displayName;
            this.count = count;
        }

        private static FileTypeFilter header() {
            return new FileTypeFilter(HEADER, "文件类型", -1);
        }

        private static FileTypeFilter all(int count) {
            return new FileTypeFilter(ALL, "全部", count);
        }

        private static FileTypeFilter extension(String extension, int count) {
            return new FileTypeFilter(extension, extension, count);
        }

        boolean isHeader() {
            return HEADER.equals(extension);
        }

        boolean matches(String requestedExtension) {
            return !isHeader() && (ALL.equals(extension)
                    || extension.equals(requestedExtension));
        }

        String getExtension() {
            return extension;
        }

        String getDisplayName() {
            return displayName;
        }

        int getCount() {
            return count;
        }

        @Override
        public String toString() {
            return displayName + " (" + count + ")";
        }
    }

    private static final class SearchExecution {
        private final SearchReport report;
        private final Path reportPath;

        private SearchExecution(SearchReport report, Path reportPath) {
            this.report = report;
            this.reportPath = reportPath;
        }
    }

    private static final class BulkDownloadResult {
        private final Path downloadRoot;
        private final int completedFiles;

        private BulkDownloadResult(Path downloadRoot,
                                   int completedFiles) {
            this.downloadRoot = downloadRoot;
            this.completedFiles = completedFiles;
        }
    }

    private static final class PreparedBucket {
        private final BucketListing listing;
        private final List<PreviewEntry> entries;

        private PreparedBucket(BucketListing listing,
                               List<PreviewEntry> entries) {
            this.listing = listing;
            this.entries = Collections.unmodifiableList(
                    new ArrayList<PreviewEntry>(entries));
        }
    }

    private static final class PreviewEntry {
        private final BucketObject object;
        private final URI uri;
        private TreeItem<String> treeItem;

        private PreviewEntry(BucketObject object, URI uri) {
            this.object = object;
            this.uri = uri;
        }
    }

    private static final class ArchiveTreeEntry {
        private final PreviewEntry owner;
        private final ArchiveBrowser browser;
        private final ArchiveBrowser.Entry entry;
        private final TreeItem<String> treeItem;
        private final String nestedPath;

        private ArchiveTreeEntry(PreviewEntry owner,
                                 ArchiveBrowser browser,
                                 ArchiveBrowser.Entry entry,
                                 TreeItem<String> treeItem,
                                 String nestedPath) {
            this.owner = owner;
            this.browser = browser;
            this.entry = entry;
            this.treeItem = treeItem;
            this.nestedPath = nestedPath;
        }
    }

    private static final class PreviewOutcome {
        private final PreviewResult result;
        private final RegisteredLocalContent localContent;
        private final URI mediaUri;
        private final PreviewFormat mediaFormat;
        private final ArchiveBrowser archiveBrowser;

        private PreviewOutcome(PreviewResult result,
                               RegisteredLocalContent localContent,
                               URI mediaUri,
                               PreviewFormat mediaFormat,
                               ArchiveBrowser archiveBrowser) {
            this.result = result;
            this.localContent = localContent;
            this.mediaUri = mediaUri;
            this.mediaFormat = mediaFormat;
            this.archiveBrowser = archiveBrowser;
        }

        private static PreviewOutcome of(PreviewResult result) {
            return new PreviewOutcome(result, null, null, null, null);
        }

        private static PreviewOutcome local(PreviewResult result,
                                            RegisteredLocalContent localContent) {
            return new PreviewOutcome(
                    result, localContent, null, null, null);
        }

        private static PreviewOutcome media(URI mediaUri,
                                            PreviewFormat mediaFormat) {
            return new PreviewOutcome(
                    null, null, mediaUri, mediaFormat, null);
        }

        private static PreviewOutcome localMedia(
                RegisteredLocalContent localContent,
                URI mediaUri,
                PreviewFormat mediaFormat) {
            return new PreviewOutcome(
                    null, localContent, mediaUri, mediaFormat, null);
        }

        private static PreviewOutcome archive(PreviewResult result,
                                              ArchiveBrowser browser) {
            return new PreviewOutcome(
                    result, null, null, null, browser);
        }
    }
}
