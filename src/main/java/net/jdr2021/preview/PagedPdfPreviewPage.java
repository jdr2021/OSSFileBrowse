package net.jdr2021.preview;

import net.jdr2021.preview.http.RegisteredLocalContent;

/**
 * Generates the lightweight page-navigation shell for an on-demand PDF.
 */
public final class PagedPdfPreviewPage {
    private PagedPdfPreviewPage() {
    }

    public static PreviewResult create(String fileName,
                                       int pageCount,
                                       RegisteredLocalContent localContent) {
        if (pageCount < 1 || localContent == null) {
            throw new IllegalArgumentException("PDF page session is incomplete");
        }
        String firstPage = localContent.resolve("page/0.png").toASCIIString();
        String escapedFile = HtmlPreviewRenderer.escape(fileName);
        String body = "<h1>" + escapedFile + "</h1>"
                + "<div class=\"card\" style=\"display:flex;gap:8px;align-items:center;"
                + "position:sticky;top:0;background:#fff;z-index:2\">"
                + "<button id=\"previous\" onclick=\"move(-1)\">上一页</button>"
                + "<span id=\"pageLabel\">第 1 / " + pageCount + " 页</span>"
                + "<button id=\"next\" onclick=\"move(1)\">下一页</button>"
                + "<button onclick=\"zoom(-0.1)\">缩小</button>"
                + "<button onclick=\"zoom(0.1)\">放大</button></div>"
                + "<div class=\"card\" style=\"text-align:center;overflow:auto\">"
                + "<img id=\"pdfPage\" alt=\"PDF 第 1 页\" style=\"width:100%;height:auto\""
                + " src=\"" + HtmlPreviewRenderer.attribute(firstPage) + "\"></div>"
                + "<script>(function(){'use strict';"
                + "var current=0,total=" + pageCount + ",scale=1;"
                + "var image=document.getElementById('pdfPage');"
                + "var label=document.getElementById('pageLabel');"
                + "var template='" + javascriptString(firstPage) + "';"
                + "window.move=function(delta){var target=current+delta;"
                + "if(target<0||target>=total){return;}current=target;"
                + "image.src=template.replace('/0.png','/'+current+'.png');"
                + "image.alt='PDF 第 '+(current+1)+' 页';"
                + "label.textContent='第 '+(current+1)+' / '+total+' 页';};"
                + "window.zoom=function(delta){scale=Math.max(0.3,Math.min(3,scale+delta));"
                + "image.style.width=Math.round(scale*100)+'%';};"
                + "})();</script>";
        return PreviewResult.html(HtmlPreviewRenderer.document(fileName, body));
    }

    private static String javascriptString(String value) {
        return value.replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("</", "<\\/");
    }
}
