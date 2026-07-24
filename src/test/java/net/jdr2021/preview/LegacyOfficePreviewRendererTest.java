package net.jdr2021.preview;

import org.apache.poi.hslf.usermodel.HSLFSlide;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hslf.usermodel.HSLFTextBox;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.junit.Test;

import java.io.ByteArrayOutputStream;

import static org.junit.Assert.assertTrue;

public class LegacyOfficePreviewRendererTest {
    @Test
    public void extractsLegacyExcelTextInMemory() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        HSSFWorkbook workbook = new HSSFWorkbook();
        try {
            workbook.createSheet("季度数据").createRow(0)
                    .createCell(0).setCellValue("收入 123");
            workbook.write(bytes);
        } finally {
            workbook.close();
        }

        String html = new LegacyOfficePreviewRenderer().render(
                PreviewRequest.inMemory("report.xls", "application/vnd.ms-excel",
                        bytes.toByteArray())).bodyAsUtf8();

        assertTrue(html.contains("季度数据"));
        assertTrue(html.contains("收入 123"));
        assertTrue(html.contains("纯文本近似预览"));
    }

    @Test
    public void extractsLegacyPowerPointTextInMemory() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        HSLFSlideShow presentation = new HSLFSlideShow();
        try {
            HSLFSlide slide = presentation.createSlide();
            HSLFTextBox text = new HSLFTextBox();
            text.setText("旧版演示文稿");
            slide.addShape(text);
            presentation.write(bytes);
        } finally {
            presentation.close();
        }

        String html = new LegacyOfficePreviewRenderer().render(
                PreviewRequest.inMemory("slides.ppt",
                        "application/vnd.ms-powerpoint", bytes.toByteArray()))
                .bodyAsUtf8();

        assertTrue(html.contains("旧版演示文稿"));
        assertTrue(html.contains("PowerPoint 97–2003"));
    }
}
