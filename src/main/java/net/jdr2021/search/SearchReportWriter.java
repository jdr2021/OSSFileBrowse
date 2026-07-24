package net.jdr2021.search;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.Set;
import net.jdr2021.utils.BucketArtifactPaths;

/** Writes a standalone UTF-8 HTML report with client-side source filtering. */
public final class SearchReportWriter {
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private SearchReportWriter() {
    }

    public static Path write(SearchReport report,
                             Path workingDirectory,
                             String bucketHostOrIp) throws IOException {
        if (report == null) {
            throw new IllegalArgumentException("搜索报告为空");
        }
        if (workingDirectory == null) {
            throw new IllegalArgumentException("工作目录为空");
        }
        Path directory = workingDirectory.toAbsolutePath().normalize();
        Files.createDirectories(directory);
        Path target = directory.resolve(reportFileName(bucketHostOrIp));
        Path temporary = Files.createTempFile(directory,
                ".search-report-", ".tmp");
        boolean completed = false;
        try {
            Files.write(temporary, html(report).getBytes(
                    StandardCharsets.UTF_8));
            Files.move(temporary, target,
                    StandardCopyOption.REPLACE_EXISTING);
            completed = true;
            return target;
        } finally {
            if (!completed) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    public static String reportFileName(String bucketHostOrIp) {
        return BucketArtifactPaths.leakReportFileName(bucketHostOrIp);
    }

    public static String html(SearchReport report) {
        Set<String> sources = new LinkedHashSet<String>();
        for (SearchMatch match : report.getMatches()) {
            sources.add(match.getSourceLink());
        }

        StringBuilder body = new StringBuilder(16 * 1024);
        body.append("<!doctype html><html lang=\"zh-CN\"><head>")
                .append("<meta charset=\"UTF-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
                .append("<title>OSSFileBrowse 搜索报告</title>")
                .append("<style>")
                .append("body{margin:0;background:#f3f6f9;color:#17324d;font-family:\"Microsoft YaHei\",Arial,sans-serif}")
                .append(".wrap{max-width:1500px;margin:24px auto;padding:0 18px}")
                .append(".card{background:#fff;border:1px solid #d9e2ec;border-radius:8px;box-shadow:0 4px 16px #17324d18}")
                .append("h1{margin:0 0 10px;font-size:24px}.meta{margin:0 0 18px;color:#526b7f}")
                .append(".summary{display:flex;gap:12px;flex-wrap:wrap;margin-bottom:14px}")
                .append(".badge{background:#eaf2f8;border-radius:16px;padding:6px 12px;font-weight:bold}")
                .append("table{width:100%;border-collapse:collapse;table-layout:fixed}")
                .append("th,td{border-bottom:1px solid #e2e8ef;padding:10px 12px;text-align:left;vertical-align:top}")
                .append("th{position:sticky;top:0;background:#17324d;color:#fff;z-index:2}")
                .append("th:nth-child(1){width:18%}th:nth-child(2){width:36%}th:nth-child(3){width:46%}")
                .append("td.sample{white-space:pre-wrap;word-break:break-all;font-family:Consolas,monospace}")
                .append("td a{color:#0069a8;word-break:break-all}.group{display:block;color:#6b7f90;font-size:12px}")
                .append("button{margin-left:8px;border:1px solid #fff8;background:#fff;color:#17324d;border-radius:4px;padding:3px 8px;cursor:pointer}")
                .append(".filter{position:absolute;right:10px;top:42px;background:#fff;color:#17324d;border:1px solid #9fb3c8;border-radius:6px;padding:10px;box-shadow:0 5px 18px #0003;min-width:360px}")
                .append(".filter select{width:100%;padding:6px}.hidden{display:none}.empty{padding:30px;text-align:center;color:#6b7f90}")
                .append(".warnings{margin-top:14px;padding:12px 18px}.warnings li{margin:4px 0;word-break:break-all}")
                .append("</style></head><body><div class=\"wrap\">")
                .append("<h1>OSSFileBrowse 搜索报告</h1>")
                .append("<p class=\"meta\">生成时间：")
                .append(escape(LocalDateTime.now().format(TIME)))
                .append(" · 查询：")
                .append(escape(report.getQuery().isEmpty()
                        ? "Rules.yml" : report.getQuery()))
                .append("</p><div class=\"summary\">")
                .append(badge("规则", report.getRuleCount()))
                .append(badge("远程文件", report.getScannedFiles()))
                .append(badge("压缩包条目",
                        report.getScannedArchiveEntries()))
                .append(badge("匹配", report.getMatches().size()))
                .append("</div><div class=\"card\"><table id=\"resultTable\"><thead><tr>")
                .append("<th>正则规则名称</th><th>采集到的样例数据</th>")
                .append("<th style=\"position:sticky\">所属文件链接")
                .append("<button id=\"filterButton\" type=\"button\" onclick=\"toggleFilter()\">筛选 ▾</button>")
                .append("<div id=\"filterPanel\" class=\"filter hidden\">")
                .append("<select id=\"sourceFilter\" onchange=\"applyFilter()\">")
                .append("<option value=\"\">全部文件链接</option>");
        for (String source : sources) {
            body.append("<option value=\"").append(attribute(source))
                    .append("\">").append(escape(source))
                    .append("</option>");
        }
        body.append("</select></div></th></tr></thead><tbody>");
        if (report.getMatches().isEmpty()) {
            body.append("<tr><td class=\"empty\" colspan=\"3\">当前条件没有匹配数据</td></tr>");
        } else {
            for (SearchMatch match : report.getMatches()) {
                body.append("<tr data-source=\"")
                        .append(attribute(match.getSourceLink()))
                        .append("\"><td><strong>")
                        .append(escape(match.getRuleName()))
                        .append("</strong><span class=\"group\">")
                        .append(escape(match.getRuleGroup()))
                        .append("</span></td><td class=\"sample\">")
                        .append(escape(match.getSample()))
                        .append("</td><td><a target=\"_blank\" rel=\"noopener noreferrer\" href=\"")
                        .append(attribute(match.getHref()))
                        .append("\" title=\"打开所属远程文件\">")
                        .append(escape(match.getSourceLink()))
                        .append("</a></td></tr>");
            }
        }
        body.append("</tbody></table></div>");
        if (!report.getWarnings().isEmpty()) {
            body.append("<div class=\"card warnings\"><strong>扫描提示</strong><ul>");
            for (String warning : report.getWarnings()) {
                body.append("<li>").append(escape(warning)).append("</li>");
            }
            body.append("</ul></div>");
        }
        body.append("</div><script>")
                .append("function toggleFilter(){document.getElementById('filterPanel').classList.toggle('hidden');}")
                .append("function applyFilter(){var value=document.getElementById('sourceFilter').value;")
                .append("var rows=document.querySelectorAll('#resultTable tbody tr[data-source]');")
                .append("for(var i=0;i<rows.length;i++){var row=rows[i];")
                .append("row.style.display=!value||row.getAttribute('data-source')===value?'':'none';}")
                .append("document.getElementById('filterPanel').classList.add('hidden');}")
                .append("document.addEventListener('click',function(e){var p=document.getElementById('filterPanel');")
                .append("if(!p.contains(e.target)&&e.target.id!=='filterButton'){p.classList.add('hidden');}});")
                .append("</script></body></html>");
        return body.toString();
    }

    private static String badge(String name, int value) {
        return "<span class=\"badge\">" + escape(name) + "："
                + value + "</span>";
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static String attribute(String value) {
        return escape(value).replace("\r", "&#13;")
                .replace("\n", "&#10;");
    }

}
