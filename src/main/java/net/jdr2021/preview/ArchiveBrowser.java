package net.jdr2021.preview;

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * In-memory archive directory and lazy single-entry reader.
 *
 * <p>The archive itself is read once from the remote object. Directory metadata
 * is retained for the tree, while an entry is expanded only after the user
 * selects that leaf. Paths and expansion sizes are validated before they are
 * exposed to the UI.</p>
 */
public final class ArchiveBrowser {
    public static final int MAX_ENTRIES = 10_000;
    public static final long MAX_EXPANDED_BYTES = 512L * 1024 * 1024;
    public static final long MAX_ENTRY_BYTES = 128L * 1024 * 1024;
    public static final int MAX_COMPRESSION_RATIO = 200;

    private final PreviewFormat format;
    private final byte[] archiveBytes;
    private final List<Entry> entries;
    private final int rejectedCount;
    private final long totalExpandedBytes;

    private ArchiveBrowser(PreviewFormat format,
                           byte[] archiveBytes,
                           Directory directory) {
        this.format = format;
        this.archiveBytes = archiveBytes;
        this.entries = Collections.unmodifiableList(directory.entries);
        this.rejectedCount = directory.rejected;
        this.totalExpandedBytes = directory.totalExpanded;
    }

    public static ArchiveBrowser open(String fileName,
                                      PreviewFormat format,
                                      byte[] archiveBytes) throws IOException {
        byte[] bytes = archiveBytes == null ? new byte[0] : archiveBytes;
        if (!isArchive(format)) {
            throw new IllegalArgumentException("Unsupported archive type: " + format);
        }
        Directory directory;
        switch (format) {
            case ZIP:
            case JAR:
                directory = listZip(bytes);
                break;
            case TAR:
                directory = listTar(bytes);
                break;
            case SEVEN_Z:
                directory = listSevenZ(bytes);
                break;
            case RAR:
                directory = listRar(bytes);
                break;
            case GZIP:
                directory = listGzip(fileName, bytes);
                break;
            default:
                throw new IllegalArgumentException("Unsupported archive type: " + format);
        }
        directory.enforceArchiveRatio(bytes.length);
        return new ArchiveBrowser(format, bytes, directory);
    }

    public List<Entry> getEntries() {
        return entries;
    }

    public int getRejectedCount() {
        return rejectedCount;
    }

    public long getTotalExpandedBytes() {
        return totalExpandedBytes;
    }

    public PreviewFormat getFormat() {
        return format;
    }

    public byte[] readEntry(Entry requested) throws IOException {
        if (requested == null || requested.directory || !requested.readable) {
            throw new IOException("Archive entry is not a readable file");
        }
        if (!entries.contains(requested)) {
            throw new IOException("Archive entry does not belong to this archive");
        }
        switch (format) {
            case ZIP:
            case JAR:
                return readZip(requested);
            case TAR:
                return readTar(requested);
            case SEVEN_Z:
                return readSevenZ(requested);
            case RAR:
                return readRar(requested);
            case GZIP:
                return readGzip(requested);
            default:
                throw new IOException("Unsupported archive type");
        }
    }

    private static Directory listZip(byte[] bytes) throws IOException {
        Directory directory = new Directory();
        ZipFile zip = ZipFile.builder()
                .setSeekableByteChannel(new SeekableInMemoryByteChannel(bytes))
                .get();
        try {
            Enumeration<ZipArchiveEntry> values = zip.getEntries();
            while (values.hasMoreElements()) {
                ZipArchiveEntry value = values.nextElement();
                boolean link = (value.getUnixMode() & 0170000) == 0120000;
                directory.add(value.getName(), value.getSize(),
                        value.getCompressedSize(),
                        value.isDirectory() ? "目录" : link ? "符号链接" : "文件",
                        value.isDirectory(), !value.isDirectory() && !link);
            }
        } finally {
            zip.close();
        }
        return directory;
    }

    private static Directory listTar(byte[] bytes) throws IOException {
        Directory directory = new Directory();
        TarArchiveInputStream tar =
                new TarArchiveInputStream(new ByteArrayInputStream(bytes));
        try {
            TarArchiveEntry value;
            while ((value = tar.getNextEntry()) != null) {
                boolean link = value.isSymbolicLink() || value.isLink();
                String type = value.isDirectory() ? "目录"
                        : value.isSymbolicLink() ? "符号链接"
                        : value.isLink() ? "硬链接" : "文件";
                directory.add(value.getName(), value.getSize(), value.getSize(),
                        type, value.isDirectory(), !value.isDirectory() && !link);
            }
        } finally {
            tar.close();
        }
        return directory;
    }

    private static Directory listSevenZ(byte[] bytes) throws IOException {
        Directory directory = new Directory();
        SevenZFile sevenZ = SevenZFile.builder()
                .setSeekableByteChannel(new SeekableInMemoryByteChannel(bytes))
                .setMaxMemoryLimitKiB(64 * 1024)
                .get();
        try {
            SevenZArchiveEntry value;
            while ((value = sevenZ.getNextEntry()) != null) {
                directory.add(value.getName(), value.getSize(), -1,
                        value.isDirectory() ? "目录" : "文件",
                        value.isDirectory(), !value.isDirectory());
            }
        } finally {
            sevenZ.close();
        }
        return directory;
    }

    @SuppressWarnings("unchecked")
    private static Directory listRar(byte[] bytes) throws IOException {
        Object archive = null;
        try {
            archive = openRar(bytes);
            Method getFileHeaders = archive.getClass().getMethod("getFileHeaders");
            Iterable<Object> headers =
                    (Iterable<Object>) getFileHeaders.invoke(archive);
            Directory directory = new Directory();
            for (Object header : headers) {
                String name = rarName(header);
                long size = longMethod(header, "getFullUnpackSize");
                long packed = longMethod(header, "getFullPackSize");
                boolean isDirectory = booleanMethod(header, "isDirectory");
                directory.add(name, size, packed,
                        isDirectory ? "目录" : "文件",
                        isDirectory, !isDirectory);
            }
            return directory;
        } catch (ReflectiveOperationException failure) {
            throw reflectionFailure("RAR directory reader initialization failed", failure);
        } finally {
            closeQuietly(archive);
        }
    }

    private static Directory listGzip(String fileName, byte[] bytes) throws IOException {
        if (bytes.length < 18) {
            throw new IOException("GZIP header/trailer is incomplete");
        }
        long size = ((long) bytes[bytes.length - 4] & 0xFF)
                | (((long) bytes[bytes.length - 3] & 0xFF) << 8)
                | (((long) bytes[bytes.length - 2] & 0xFF) << 16)
                | (((long) bytes[bytes.length - 1] & 0xFF) << 24);
        String source = fileName == null ? "gzip-data" : fileName;
        String name = source.replaceFirst("(?i)\\.tgz$", ".tar")
                .replaceFirst("(?i)\\.(gz|gzip)$", "");
        if (name.equals(source) || name.isEmpty()) {
            name = "gzip-data";
        }
        Directory directory = new Directory();
        directory.add(name, size, bytes.length,
                "GZIP 数据流", false, true);
        return directory;
    }

    private byte[] readZip(Entry requested) throws IOException {
        ZipFile zip = ZipFile.builder()
                .setSeekableByteChannel(new SeekableInMemoryByteChannel(archiveBytes))
                .get();
        try {
            Enumeration<ZipArchiveEntry> values = zip.getEntries();
            int sourceIndex = 0;
            while (values.hasMoreElements()) {
                ZipArchiveEntry value = values.nextElement();
                if (requested.sourceIndex == sourceIndex++) {
                    InputStream input = zip.getInputStream(value);
                    try {
                        return readBounded(input, requested.size);
                    } finally {
                        input.close();
                    }
                }
            }
            throw missing(requested);
        } finally {
            zip.close();
        }
    }

    private byte[] readTar(Entry requested) throws IOException {
        TarArchiveInputStream tar =
                new TarArchiveInputStream(new ByteArrayInputStream(archiveBytes));
        try {
            TarArchiveEntry value;
            int sourceIndex = 0;
            while ((value = tar.getNextEntry()) != null) {
                if (requested.sourceIndex == sourceIndex++) {
                    return readBounded(tar, requested.size);
                }
            }
            throw missing(requested);
        } finally {
            tar.close();
        }
    }

    private byte[] readSevenZ(Entry requested) throws IOException {
        SevenZFile sevenZ = SevenZFile.builder()
                .setSeekableByteChannel(new SeekableInMemoryByteChannel(archiveBytes))
                .setMaxMemoryLimitKiB(64 * 1024)
                .get();
        try {
            SevenZArchiveEntry value;
            int sourceIndex = 0;
            while ((value = sevenZ.getNextEntry()) != null) {
                if (requested.sourceIndex == sourceIndex++) {
                    return readSevenZBounded(sevenZ, requested.size);
                }
            }
            throw missing(requested);
        } finally {
            sevenZ.close();
        }
    }

    @SuppressWarnings("unchecked")
    private byte[] readRar(Entry requested) throws IOException {
        Object archive = null;
        try {
            archive = openRar(archiveBytes);
            Method getFileHeaders = archive.getClass().getMethod("getFileHeaders");
            Iterable<Object> headers =
                    (Iterable<Object>) getFileHeaders.invoke(archive);
            int sourceIndex = 0;
            for (Object header : headers) {
                if (requested.sourceIndex == sourceIndex++) {
                    BoundedOutputStream output =
                            new BoundedOutputStream(requested.size);
                    Method extract = findRarExtractMethod(archive.getClass());
                    extract.invoke(archive, header, output);
                    return output.toByteArray();
                }
            }
            throw missing(requested);
        } catch (ReflectiveOperationException failure) {
            throw reflectionFailure("RAR entry extraction failed", failure);
        } finally {
            closeQuietly(archive);
        }
    }

    private byte[] readGzip(Entry requested) throws IOException {
        if (entries.isEmpty() || requested != entries.get(0)) {
            throw missing(requested);
        }
        GZIPInputStream gzip =
                new GZIPInputStream(new ByteArrayInputStream(archiveBytes));
        try {
            return readBounded(gzip, requested.size);
        } finally {
            gzip.close();
        }
    }

    private static byte[] readBounded(InputStream input, long declaredSize)
            throws IOException {
        validateDeclaredSize(declaredSize);
        int initial = (int) Math.min(
                Math.max(1024L, declaredSize), 1024L * 1024);
        ByteArrayOutputStream output = new ByteArrayOutputStream(initial);
        byte[] buffer = new byte[32 * 1024];
        long total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (read == 0) {
                continue;
            }
            total += read;
            if (total > MAX_ENTRY_BYTES) {
                throw new IOException("Archive entry exceeds 128 MiB");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static byte[] readSevenZBounded(SevenZFile sevenZ, long declaredSize)
            throws IOException {
        validateDeclaredSize(declaredSize);
        int initial = (int) Math.min(
                Math.max(1024L, declaredSize), 1024L * 1024);
        ByteArrayOutputStream output = new ByteArrayOutputStream(initial);
        byte[] buffer = new byte[32 * 1024];
        long total = 0;
        int read;
        while ((read = sevenZ.read(buffer)) >= 0) {
            if (read == 0) {
                continue;
            }
            total += read;
            if (total > MAX_ENTRY_BYTES) {
                throw new IOException("Archive entry exceeds 128 MiB");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static void validateDeclaredSize(long size) throws IOException {
        if (size > MAX_ENTRY_BYTES) {
            throw new IOException("Archive entry exceeds 128 MiB");
        }
    }

    private static Object openRar(byte[] bytes) throws ReflectiveOperationException {
        Class<?> archiveClass = Class.forName("com.github.junrar.Archive");
        Constructor<?> constructor =
                archiveClass.getConstructor(java.io.InputStream.class);
        return constructor.newInstance(new ByteArrayInputStream(bytes));
    }

    private static Method findRarExtractMethod(Class<?> archiveClass)
            throws NoSuchMethodException {
        for (Method method : archiveClass.getMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if ("extractFile".equals(method.getName())
                    && parameters.length == 2
                    && OutputStream.class.isAssignableFrom(parameters[1])) {
                return method;
            }
        }
        throw new NoSuchMethodException("RAR extractFile");
    }

    private static String rarName(Object header) {
        String name = stringMethod(header, "getFileNameString");
        if (name == null || name.isEmpty()) {
            name = stringMethod(header, "getFileNameW");
        }
        return name;
    }

    private static String stringMethod(Object target, String name) {
        try {
            Object value = target.getClass().getMethod(name).invoke(target);
            return value == null ? null : String.valueOf(value);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static long longMethod(Object target, String name) {
        try {
            return ((Number) target.getClass().getMethod(name).invoke(target))
                    .longValue();
        } catch (ReflectiveOperationException ignored) {
            return -1;
        }
    }

    private static boolean booleanMethod(Object target, String name) {
        try {
            return Boolean.TRUE.equals(
                    target.getClass().getMethod(name).invoke(target));
        } catch (ReflectiveOperationException ignored) {
            return false;
        }
    }

    private static IOException reflectionFailure(String message,
                                                 ReflectiveOperationException failure) {
        Throwable cause = failure;
        if (failure instanceof InvocationTargetException
                && ((InvocationTargetException) failure).getCause() != null) {
            cause = ((InvocationTargetException) failure).getCause();
        }
        if (cause instanceof IOException) {
            return (IOException) cause;
        }
        return new IOException(message, cause);
    }

    private static void closeQuietly(Object value) throws IOException {
        if (value instanceof Closeable) {
            ((Closeable) value).close();
        }
    }

    private static IOException missing(Entry requested) {
        return new IOException("Archive entry is missing: " + requested.path);
    }

    private static boolean isArchive(PreviewFormat format) {
        return format == PreviewFormat.ZIP || format == PreviewFormat.JAR
                || format == PreviewFormat.TAR || format == PreviewFormat.SEVEN_Z
                || format == PreviewFormat.RAR || format == PreviewFormat.GZIP;
    }

    static String normalizePath(String path) {
        if (path == null) {
            return "";
        }
        StringBuilder result = new StringBuilder();
        for (String segment : path.replace('\\', '/').split("/")) {
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if (result.length() > 0) {
                result.append('/');
            }
            result.append(segment);
        }
        return result.toString();
    }

    private static boolean safePath(String path) {
        if (path == null || path.isEmpty() || path.indexOf('\0') >= 0) {
            return false;
        }
        String normalized = path.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.matches("^[A-Za-z]:.*")) {
            return false;
        }
        for (String segment : normalized.split("/")) {
            if ("..".equals(segment)) {
                return false;
            }
        }
        return !normalizePath(path).isEmpty();
    }

    public static final class Entry {
        private final int sourceIndex;
        private final String path;
        private final long size;
        private final long compressedSize;
        private final String type;
        private final boolean directory;
        private final boolean readable;

        private Entry(int sourceIndex,
                      String path,
                      long size,
                      long compressedSize,
                      String type,
                      boolean directory,
                      boolean readable) {
            this.sourceIndex = sourceIndex;
            this.path = path;
            this.size = size;
            this.compressedSize = compressedSize;
            this.type = type == null ? "文件" : type;
            this.directory = directory;
            this.readable = readable;
        }

        public String getPath() {
            return path;
        }

        public long getSize() {
            return size;
        }

        public long getCompressedSize() {
            return compressedSize;
        }

        public String getType() {
            return type;
        }

        public boolean isDirectory() {
            return directory;
        }

        public boolean isReadable() {
            return readable;
        }
    }

    private static final class Directory {
        private final List<Entry> entries = new ArrayList<Entry>();
        private long totalExpanded;
        private int rejected;
        private int sourceCount;

        private void add(String archiveName,
                         long size,
                         long compressedSize,
                         String type,
                         boolean directory,
                         boolean readable) throws IOException {
            if (sourceCount >= MAX_ENTRIES) {
                throw new IOException("Archive entry count exceeds " + MAX_ENTRIES);
            }
            int sourceIndex = sourceCount++;
            long normalizedSize = Math.max(0, size);
            long normalizedCompressed = Math.max(0, compressedSize);
            boolean accepted = safePath(archiveName)
                    && normalizedSize <= MAX_ENTRY_BYTES
                    && (normalizedCompressed == 0 || normalizedSize == 0
                    || normalizedSize / Math.max(1, normalizedCompressed)
                    <= MAX_COMPRESSION_RATIO);
            if (!accepted) {
                rejected++;
                return;
            }
            if (totalExpanded + normalizedSize > MAX_EXPANDED_BYTES) {
                throw new IOException("Archive expanded size exceeds 512 MiB");
            }
            totalExpanded += normalizedSize;
            entries.add(new Entry(sourceIndex, normalizePath(archiveName),
                    size, compressedSize, type, directory, readable));
        }

        private void enforceArchiveRatio(long archiveSize) throws IOException {
            if (archiveSize > 0
                    && totalExpanded / Math.max(1, archiveSize)
                    > MAX_COMPRESSION_RATIO) {
                throw new IOException("Archive total compression ratio exceeds limit");
            }
        }
    }

    private static final class BoundedOutputStream extends OutputStream {
        private final ByteArrayOutputStream delegate;
        private long count;

        private BoundedOutputStream(long declaredSize) throws IOException {
            validateDeclaredSize(declaredSize);
            int initial = (int) Math.min(
                    Math.max(1024L, declaredSize), 1024L * 1024);
            this.delegate = new ByteArrayOutputStream(initial);
        }

        @Override
        public void write(int value) throws IOException {
            ensureCapacity(1);
            delegate.write(value);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            ensureCapacity(length);
            delegate.write(bytes, offset, length);
        }

        private void ensureCapacity(int additional) throws IOException {
            count += additional;
            if (count > MAX_ENTRY_BYTES) {
                throw new IOException("Archive entry exceeds 128 MiB");
            }
        }

        private byte[] toByteArray() {
            return delegate.toByteArray();
        }
    }
}
