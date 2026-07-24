package net.jdr2021.preview;

import org.apache.poi.hslf.usermodel.HSLFShape;
import org.apache.poi.hslf.usermodel.HSLFSlide;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hslf.usermodel.HSLFTextShape;
import org.apache.poi.hssf.extractor.ExcelExtractor;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.hwpf.extractor.WordExtractor;

import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * Apache POI based text preview for OLE2 Word, Excel and PowerPoint documents.
 * The source document remains in memory and active content is never executed.
 */
public final class LegacyOfficePreviewRenderer extends HtmlPreviewRenderer {
    private static final int MAX_EXTRACTED_CHARS = 8 * 1024 * 1024;

    @Override
    public boolean supports(PreviewFormat format) {
        return format == PreviewFormat.DOC || format == PreviewFormat.XLS
                || format == PreviewFormat.PPT;
    }

    @Override
    public PreviewResult render(PreviewRequest request) throws IOException {
        String extracted;
        String type;
        switch (request.getFormat()) {
            case DOC:
                type = "Word 97–2003";
                extracted = extractWord(request.getData());
                break;
            case XLS:
                type = "Excel 97–2003";
                extracted = extractExcel(request.getData());
                break;
            case PPT:
                type = "PowerPoint 97–2003";
                extracted = extractPowerPoint(request.getData());
                break;
            default:
                throw new IllegalArgumentException("Unsupported legacy Office format");
        }
        boolean truncated = extracted.length() > MAX_EXTRACTED_CHARS;
        if (truncated) {
            extracted = extracted.substring(0, MAX_EXTRACTED_CHARS);
        }
        String body = "<h1>" + escape(request.getFileName()) + "</h1>"
                + "<div class=\"muted\">" + escape(type)
                + " · 纯文本近似预览 · 宏和嵌入对象未执行</div>"
                + (truncated ? "<div class=\"card danger\">提取文本超过 8 MiB，当前显示前段内容。</div>" : "")
                + "<pre>" + escape(extracted) + "</pre>";
        return page(request.getFileName(), body);
    }

    private static String extractWord(byte[] data) throws IOException {
        try (WordExtractor extractor =
                     new WordExtractor(new ByteArrayInputStream(data))) {
            return extractor.getText();
        }
    }

    private static String extractExcel(byte[] data) throws IOException {
        try (HSSFWorkbook workbook =
                     new HSSFWorkbook(new ByteArrayInputStream(data));
             ExcelExtractor extractor = new ExcelExtractor(workbook)) {
            extractor.setIncludeSheetNames(true);
            extractor.setFormulasNotResults(false);
            extractor.setIncludeCellComments(true);
            extractor.setIncludeHeadersFooters(true);
            return extractor.getText();
        }
    }

    private static String extractPowerPoint(byte[] data) throws IOException {
        try (HSLFSlideShow presentation =
                     new HSLFSlideShow(new ByteArrayInputStream(data))) {
            StringBuilder text = new StringBuilder();
            int slideNumber = 0;
            for (HSLFSlide slide : presentation.getSlides()) {
                text.append("幻灯片 ").append(++slideNumber).append('\n');
                for (HSLFShape shape : slide.getShapes()) {
                    if (shape instanceof HSLFTextShape) {
                        String value = ((HSLFTextShape) shape).getText();
                        if (value != null && !value.trim().isEmpty()) {
                            text.append(value.trim()).append('\n');
                        }
                    }
                }
                text.append('\n');
                if (text.length() > MAX_EXTRACTED_CHARS) {
                    break;
                }
            }
            return text.toString();
        }
    }
}
