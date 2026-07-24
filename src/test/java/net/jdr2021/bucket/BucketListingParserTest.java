package net.jdr2021.bucket;

import org.junit.Test;

import java.net.URI;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class BucketListingParserTest {
    @Test
    public void parsesNamespacedS3ListingAndMetadata() {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<ListBucketResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">"
                + "<Name>demo</Name><Prefix>docs/</Prefix><IsTruncated>true</IsTruncated>"
                + "<NextContinuationToken>NEXT</NextContinuationToken>"
                + "<Contents><Key>docs/报告 1.pdf</Key>"
                + "<LastModified>2026-07-23T01:02:03Z</LastModified>"
                + "<ETag>\"abc\"</ETag><Size>12345</Size></Contents>"
                + "<Contents><Key>docs/folder/</Key><Size>0</Size></Contents>"
                + "</ListBucketResult>";

        BucketListing listing = BucketListingParser.parse(xml);

        assertEquals("demo", listing.getName());
        assertEquals("docs/", listing.getPrefix());
        assertTrue(listing.isTruncated());
        assertEquals("NEXT", listing.getNextContinuationToken());
        assertEquals(2, listing.getObjects().size());
        assertEquals(12345L, listing.getObjects().get(0).getSize());
        assertEquals("abc", listing.getObjects().get(0).getEtag());
        assertFalse(listing.getObjects().get(0).isDirectoryMarker());
        assertTrue(listing.getObjects().get(1).isDirectoryMarker());
    }

    @Test
    public void rejectsDoctype() {
        assertThrows(IllegalArgumentException.class,
                () -> BucketListingParser.parse("<!DOCTYPE x [<!ENTITY y SYSTEM \"file:///tmp/x\">]>"
                        + "<ListBucketResult><Name>&y;</Name></ListBucketResult>"));
    }

    @Test
    public void buildsEncodedObjectUrlAndDropsListingQuery() {
        URI result = ObjectUrlBuilder.build(
                URI.create("https://example.test/bucket/?list-type=2&prefix=docs"),
                "文档/a+b.pdf");

        assertEquals("https://example.test/bucket/%E6%96%87%E6%A1%A3/a%2Bb.pdf",
                result.toASCIIString());
    }

    @Test
    public void buildsContinuationPageAndPreservesListingOptions() {
        BucketListing page = new BucketListing("demo", "docs/", true, null,
                "next/令牌+", Arrays.asList(
                new BucketObject("docs/a.txt", 1, null, null)));

        URI result = BucketPageRequestBuilder.nextPage(
                URI.create("https://example.test/?list-type=2&prefix=docs%2F&max-keys=100"),
                page);

        assertEquals("https://example.test/?list-type=2&prefix=docs%2F&max-keys=100"
                        + "&continuation-token=next%2F%E4%BB%A4%E7%89%8C%2B",
                result.toASCIIString());
    }

    @Test
    public void extractsEveryContentsFromOneLargeResponseAndPreservesKeySpaces() {
        StringBuilder xml = new StringBuilder(
                "\r\n<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                        + "<ListBucketResult><Name>large</Name><MaxKeys>1000</MaxKeys>"
                        + "<IsTruncated>true</IsTruncated>");
        for (int index = 0; index < 1000; index++) {
            String key = index == 51
                    ? "file/diaochang/20250324/1742795257638.docx"
                    : (index == 0 ? " leading-space.jpg" : "object/" + index);
            xml.append("<Contents><Key>").append(key)
                    .append("</Key><Size>").append(index)
                    .append("</Size></Contents>");
        }
        xml.append("<NextMarker>object/999</NextMarker></ListBucketResult>");

        BucketListing listing = BucketListingParser.parse(xml.toString());

        assertEquals(1000, listing.getObjects().size());
        assertEquals(" leading-space.jpg", listing.getObjects().get(0).getKey());
        assertEquals("file/diaochang/20250324/1742795257638.docx",
                listing.getObjects().get(51).getKey());
        assertEquals("object/999", listing.getObjects().get(999).getKey());
        assertTrue(listing.isTruncated());
        assertEquals("object/999", listing.getNextMarker());
    }
}
