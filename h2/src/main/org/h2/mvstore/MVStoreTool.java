/*
 * Copyright 2004-2025 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.mvstore;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;

import org.h2.compress.CompressDeflate;
import org.h2.compress.CompressLZF;
import org.h2.compress.Compressor;
import org.h2.engine.Constants;
import org.h2.mvstore.tx.TransactionStore;
import org.h2.mvstore.type.BasicDataType;
import org.h2.mvstore.type.StringDataType;
import org.h2.store.fs.FilePath;
import org.h2.store.fs.FileUtils;
import org.h2.util.Utils;

/**
 * Utility methods used in combination with the MVStore.
 */
public class MVStoreTool {

    public static final int MAX_NB_FILE_HEADERS_PER_FILE = 2;

    /**
     * Runs this tool.
     * Options are case sensitive. Supported options are:
     * <table>
     * <caption>Command line options</caption>
     * <tr><td>[-dump &lt;fileName&gt;]</td>
     * <td>Dump the contends of the file</td></tr>
     * <tr><td>[-info &lt;fileName&gt;]</td>
     * <td>Get summary information about a file</td></tr>
     * <tr><td>[-compact &lt;fileName&gt;]</td>
     * <td>Compact a store</td></tr>
     * <tr><td>[-compress &lt;fileName&gt;]</td>
     * <td>Compact a store with compression enabled</td></tr>
     * </table>
     *
     * @param args the command line arguments
     */
    public static void main(String... args) {
        for (int i = 0; i < args.length; i++) {
            if ("-dump".equals(args[i])) {
                String fileName = args[++i];
                dump(fileName, new PrintWriter(System.out), true);
            } else if ("-info".equals(args[i])) {
                String fileName = args[++i];
                info(fileName, new PrintWriter(System.out));
            } else if ("-compact".equals(args[i])) {
                String fileName = args[++i];
                compact(fileName, false);
            } else if ("-compress".equals(args[i])) {
                String fileName = args[++i];
                compact(fileName, true);
            } else if ("-rollback".equals(args[i])) {
                String fileName = args[++i];
                long targetVersion = Long.decode(args[++i]);
                rollback(fileName, targetVersion, new PrintWriter(System.out));
            } else if ("-repair".equals(args[i])) {
                String fileName = args[++i];
                repair(fileName);
            }
        }
    }

    /**
     * Read the contents of the file and write them to system out.
     *
     * @param fileName the name of the file
     * @param details whether to print details
     */
    public static void dump(String fileName, boolean details) {
        dump(fileName, new PrintWriter(System.out), details);
    }

    /**
     * Read the summary information of the file and write them to system out.
     *
     * @param fileName the name of the file
     */
    public static void info(String fileName) {
        info(fileName, new PrintWriter(System.out));
    }

    /**
     * Read the contents of the file and display them in a human-readable
     * format.
     *
     * @param fileName the name of the file
     * @param writer the print writer
     * @param details print the page details
     */
    public static void dump(String fileName, Writer writer, boolean details) {
        PrintWriter pw = new PrintWriter(writer, true);
        if (!FilePath.get(fileName).exists()) {
            pw.println("File not found: " + fileName);
            return;
        }
        long size = FileUtils.size(fileName);
        pw.printf("File %s, %d bytes, %d MB\n", fileName, size, size / 1024 / 1024);
        int blockSize = FileStore.BLOCK_SIZE;
        TreeMap<Integer, Long> mapSizesTotal =
                new TreeMap<>();
        long pageSizeTotal = 0;
        try (FileChannel file = FilePath.get(fileName).open("r")) {
            long fileSize = file.size();
            int len = Long.toHexString(fileSize).length();
            ByteBuffer buffer = ByteBuffer.allocate(4096);
            long pageCount = 0;
            int readFileHeaderCount = 0;
            for (long pos = 0; pos < fileSize; ) {
                buffer.rewind();
                // Bugfix - An MVStoreException that wraps EOFException is
                // thrown when partial writes happens in the case of power off
                // or file system issues.
                // So we should skip the broken block at end of the DB file.
                try {
                    DataUtils.readFully(file, pos, buffer);
                } catch (MVStoreException e) {
                    pos += blockSize;
                    pw.printf("ERROR illegal position %d%n", pos);
                    continue;
                }
                buffer.rewind();
                int headerType = buffer.get();
                if (headerType == 'H' && readFileHeaderCount < MAX_NB_FILE_HEADERS_PER_FILE) {
                    String header = new String(buffer.array(), StandardCharsets.ISO_8859_1).trim();
                    pw.printf("%0" + len + "x fileHeader %s%n",
                            pos, header);
                    pos += blockSize;
                    readFileHeaderCount++;
                    continue;
                }
                if (headerType != 'c') {
                    pos += blockSize;
                    continue;
                }
                buffer.position(0);
                Chunk c;
                try {
                    c = new SFChunk(Chunk.readChunkHeader(buffer));
                    c.block = pos / blockSize;
                } catch (MVStoreException e) {
                    // Chunks are not always contiguous (due to chunk compaction/move/drop and space re-use)
                    // Blocks following a chunk can therefore contain something else than a valid chunk header
                    // In that case, let's move to the next block
                    pos += blockSize;
                    continue;
                }
                if (c.len <= 0) {
                    // not a chunk
                    pos += blockSize;
                    continue;
                }
                int length = c.len * FileStore.BLOCK_SIZE;
                pw.printf("%n%0" + len + "x chunkHeader %s%n", pos, c);
                ByteBuffer chunk = ByteBuffer.allocate(length);
                DataUtils.readFully(file, pos, chunk);
                int p = buffer.position();
                pos += length;
                int remaining = c.pageCount;
                pageCount += c.pageCount;
                TreeMap<Integer, Integer> mapSizes =
                        new TreeMap<>();
                int pageSizeSum = 0;
                while (remaining > 0) {
                    int start = p;
                    try {
                        chunk.position(p);
                    } catch (IllegalArgumentException e) {
                        // too far
                        pw.printf("ERROR illegal position %d%n", p);
                        break;
                    }
                    int pageSize = chunk.getInt();
                    // check value (ignored)
                    chunk.getShort();
                    /*int pageNo =*/ DataUtils.readVarInt(chunk);
                    int mapId = DataUtils.readVarInt(chunk);
                    int entries = DataUtils.readVarInt(chunk);
                    int type = chunk.get();
                    boolean compressed = (type & DataUtils.PAGE_COMPRESSED) != 0;
                    boolean node = (type & DataUtils.PAGE_TYPE_NODE) != 0;
                    if (details) {
                        pw.printf(
                                "+%0" + len +
                                        "x %s, map %x, %d entries, %d bytes, maxLen %x%n",
                                p,
                                (node ? "node" : "leaf") +
                                        (compressed ? " compressed" : ""),
                                mapId,
                                node ? entries + 1 : entries,
                                pageSize,
                                DataUtils.getPageMaxLength(DataUtils.composePagePos(0, 0, pageSize, 0))
                        );
                    }
                    p += pageSize;
                    Integer mapSize = mapSizes.get(mapId);
                    if (mapSize == null) {
                        mapSize = 0;
                    }
                    mapSizes.put(mapId, mapSize + pageSize);
                    Long total = mapSizesTotal.get(mapId);
                    if (total == null) {
                        total = 0L;
                    }
                    mapSizesTotal.put(mapId, total + pageSize);
                    pageSizeSum += pageSize;
                    pageSizeTotal += pageSize;
                    remaining--;
                    long[] children = null;
                    long[] counts = null;
                    if (node) {
                        children = new long[entries + 1];
                        for (int i = 0; i <= entries; i++) {
                            children[i] = chunk.getLong();
                        }
                        counts = new long[entries + 1];
                        for (int i = 0; i <= entries; i++) {
                            long s = DataUtils.readVarLong(chunk);
                            counts[i] = s;
                        }
                    }
                    String[] keys = new String[entries];
                    if (mapId == 0 && details) {
                        ByteBuffer data;
                        if (compressed) {
                            boolean fast = (type & DataUtils.PAGE_COMPRESSED_HIGH) != DataUtils.PAGE_COMPRESSED_HIGH;
                            Compressor compressor = getCompressor(fast);
                            int lenAdd = DataUtils.readVarInt(chunk);
                            int compLen = pageSize + start - chunk.position();
                            byte[] comp = Utils.newBytes(compLen);
                            chunk.get(comp);
                            int l = compLen + lenAdd;
                            data = ByteBuffer.allocate(l);
                            compressor.expand(comp, 0, compLen, data.array(), 0, l);
                        } else {
                            data = chunk;
                        }
                        for (int i = 0; i < entries; i++) {
                            String k = StringDataType.INSTANCE.read(data);
                            keys[i] = k;
                        }
                        if (node) {
                            // meta map node
                            for (int i = 0; i < entries; i++) {
                                long cp = children[i];
                                pw.printf("    %d children < %s @ " +
                                                "chunk %x +%0" +
                                                len + "x%n",
                                        counts[i],
                                        keys[i],
                                        DataUtils.getPageChunkId(cp),
                                        DataUtils.getPageOffset(cp));
                            }
                            long cp = children[entries];
                            pw.printf("    %d children >= %s @ chunk %x +%0" +
                                            len + "x%n",
                                    counts[entries],
                                    entries <= keys.length ? null : keys[entries],
                                    DataUtils.getPageChunkId(cp),
                                    DataUtils.getPageOffset(cp));
                        } else {
                            // meta map leaf
                            String[] values = new String[entries];
                            for (int i = 0; i < entries; i++) {
                                String v = StringDataType.INSTANCE.read(data);
                                values[i] = v;
                            }
                            for (int i = 0; i < entries; i++) {
                                pw.println("    " + keys[i] +
                                        " = " + values[i]);
                            }
                        }
                    } else {
                        if (node && details) {
                            for (int i = 0; i <= entries; i++) {
                                long cp = children[i];
                                pw.printf("    %d children @ chunk %x +%0" +
                                                len + "x%n",
                                        counts[i],
                                        DataUtils.getPageChunkId(cp),
                                        DataUtils.getPageOffset(cp));
                            }
                        }
                    }
                }
                pageSizeSum = Math.max(1, pageSizeSum);
                for (Integer mapId : mapSizes.keySet()) {
                    int percent = 100 * mapSizes.get(mapId) / pageSizeSum;
                    pw.printf("map %x: %d bytes, %d%%%n", mapId, mapSizes.get(mapId), percent);
                }
                int footerPos = chunk.limit() - Chunk.FOOTER_LENGTH;
                try {
                    chunk.position(footerPos);
                    pw.printf(
                            "+%0" + len + "x chunkFooter %s%n",
                            footerPos,
                            new String(chunk.array(), chunk.position(),
                                    Chunk.FOOTER_LENGTH, StandardCharsets.ISO_8859_1).trim());
                } catch (IllegalArgumentException e) {
                    // too far
                    pw.printf("ERROR illegal footer position %d%n", footerPos);
                }
            }
            pw.printf("%n%0" + len + "x eof%n", fileSize);
            pw.printf("\n");
            pageCount = Math.max(1, pageCount);
            pw.printf("page size total: %d bytes, page count: %d, average page size: %d bytes\n",
                    pageSizeTotal, pageCount, pageSizeTotal / pageCount);
            pageSizeTotal = Math.max(1, pageSizeTotal);
            for (Integer mapId : mapSizesTotal.keySet()) {
                int percent = (int) (100 * mapSizesTotal.get(mapId) / pageSizeTotal);
                pw.printf("map %x: %d bytes, %d%%%n", mapId, mapSizesTotal.get(mapId), percent);
            }
        } catch (IOException e) {
            pw.println("ERROR: " + e);
            e.printStackTrace(pw);
        }
        // ignore
        pw.flush();
    }

    private static Compressor getCompressor(boolean fast) {
        return fast ? new CompressLZF() : new CompressDeflate();
    }

    /**
     * Read the summary information of the file and write them to system out.
     *
     * @param fileName the name of the file
     * @param writer the print writer
     * @return null if successful (if there was no error), otherwise the error
     *         message
     */
    public static String info(String fileName, Writer writer) {
        PrintWriter pw = new PrintWriter(writer, true);
        if (!FilePath.get(fileName).exists()) {
            pw.println("File not found: " + fileName);
            return "File not found: " + fileName;
        }
        long fileLength = FileUtils.size(fileName);
        try (MVStore store = new MVStore.Builder().
                fileName(fileName).recoveryMode().
                readOnly().open()) {
            Map<String, String> layout = store.getLayoutMap();
            Map<String, Object> header = store.getStoreHeader();
            long fileCreated = DataUtils.readHexLong(header, "created", 0L);
            TreeMap<Integer, Chunk<?>> chunks = new TreeMap<>();
            long chunkLength = 0;
            long maxLength = 0;
            long maxLengthLive = 0;
            long maxLengthNotEmpty = 0;
            for (Entry<String, String> e : layout.entrySet()) {
                String k = e.getKey();
                if (k.startsWith(DataUtils.LAYOUT_CHUNK)) {
                    Chunk<?> c = store.getFileStore().createChunk(e.getValue());
                    chunks.put(c.id, c);
                    chunkLength += (long)c.len * FileStore.BLOCK_SIZE;
                    maxLength += c.maxLen;
                    maxLengthLive += c.maxLenLive;
                    if (c.maxLenLive > 0) {
                        maxLengthNotEmpty += c.maxLen;
                    }
                }
            }
            pw.printf("Created: %s\n", formatTimestamp(fileCreated, fileCreated));
            pw.printf("Last modified: %s\n",
                    formatTimestamp(FileUtils.lastModified(fileName), fileCreated));
            pw.printf("File length: %d\n", fileLength);
            pw.printf("The last chunk is not listed\n");
            pw.printf("Chunk length: %d\n", chunkLength);
            pw.printf("Chunk count: %d\n", chunks.size());
            pw.printf("Used space: %d%%\n", getPercent(chunkLength, fileLength));
            pw.printf("Chunk fill rate: %d%%\n", maxLength == 0 ? 100 :
                getPercent(maxLengthLive, maxLength));
            pw.printf("Chunk fill rate excluding empty chunks: %d%%\n",
                maxLengthNotEmpty == 0 ? 100 :
                getPercent(maxLengthLive, maxLengthNotEmpty));
            for (Entry<Integer, Chunk<?>> e : chunks.entrySet()) {
                Chunk<?> c = e.getValue();
                long created = fileCreated + c.time;
                pw.printf("  Chunk %d: %s, %d%% used, %d blocks",
                        c.id, formatTimestamp(created, fileCreated),
                        getPercent(c.maxLenLive, c.maxLen),
                        c.len
                        );
                if (c.maxLenLive == 0) {
                    pw.printf(", unused: %s",
                            formatTimestamp(fileCreated + c.unused, fileCreated));
                }
                pw.printf("\n");
            }
            pw.printf("\n");
        } catch (Exception e) {
            pw.println("ERROR: " + e);
            e.printStackTrace(pw);
            return e.getMessage();
        }
        pw.flush();
        return null;
    }

    private static String formatTimestamp(long t, long start) {
        String x = new Timestamp(t).toString();
        String s = x.substring(0, 19);
        s += " (+" + ((t - start) / 1000) + " s)";
        return s;
    }

    private static int getPercent(long value, long max) {
        if (value == 0) {
            return 0;
        } else if (value == max) {
            return 100;
        }
        return (int) (1 + 98 * value / Math.max(1, max));
    }

    /**
     * Compress the store by creating a new file and copying the live pages
     * there. Temporarily, a file with the suffix ".tempFile" is created. This
     * file is then renamed, replacing the original file, if possible. If not,
     * the new file is renamed to ".newFile", then the old file is removed, and
     * the new file is renamed. This might be interrupted, so it's better to
     * compactCleanUp before opening a store, in case this method was used.
     *
     * @param fileName the file name
     * @param compress whether to compress the data
     */
    public static void compact(String fileName, boolean compress) {
        String tempName = fileName + Constants.SUFFIX_MV_STORE_TEMP_FILE;
        FileUtils.delete(tempName);
        compact(fileName, tempName, compress);
        moveAtomicReplace(tempName, fileName);
    }

    /**
     * Rename a file(s) of the named store, and try to atomically replace an
     * existing file(s) of another store.
     *
     * @param sourceName the old fully qualified file name of the store
     * @param destinationName the new fully qualified file name of the store
     */
    public static void moveAtomicReplace(String sourceName, String destinationName) {
        try {
            FileUtils.moveAtomicReplace(sourceName, destinationName);
        } catch (MVStoreException e) {
            String newName = destinationName + Constants.SUFFIX_MV_STORE_NEW_FILE;
            FileUtils.delete(newName);
            FileUtils.move(sourceName, newName);
            FileUtils.delete(destinationName);
            FileUtils.move(newName, destinationName);
        }
    }

    /**
     * Clean up if needed, in a case a compact operation was interrupted due to
     * killing the process or a power failure. This will delete temporary files
     * (if any), and in case atomic file replacements were not used, rename the
     * new file.
     *
     * @param fileName the file name
     */
    public static void compactCleanUp(String fileName) {
        String tempName = fileName + Constants.SUFFIX_MV_STORE_TEMP_FILE;
        if (FileUtils.exists(tempName)) {
            FileUtils.delete(tempName);
        }
        String newName = fileName + Constants.SUFFIX_MV_STORE_NEW_FILE;
        if (FileUtils.exists(newName)) {
            if (FileUtils.exists(fileName)) {
                FileUtils.delete(newName);
            } else {
                FileUtils.move(newName, fileName);
            }
        }
    }

    /**
     * Copy all live pages from the source store to the target store.
     *
     * @param sourceFileName the name of the source store
     * @param targetFileName the name of the target store
     * @param compress whether to compress the data
     */
    public static void compact(String sourceFileName, String targetFileName, boolean compress) {
        try (MVStore source = new MVStore.Builder().
                fileName(sourceFileName).readOnly().open()) {
            // Bugfix - Add double "try-finally" statements to close source and target stores for
            //releasing lock and file resources in these stores even if OOM occurs.
            // Fix issues such as "Cannot delete file "/h2/data/test.mv.db.tempFile" [90025-197]"
            //when client connects to this server and reopens this store database in this process.
            // @since 2018-09-13 little-pan
            FileUtils.delete(targetFileName);
            MVStore.Builder b = new MVStore.Builder().
                fileName(targetFileName);
            if (compress) {
                b.compress();
            }
            try (MVStore target = b.open()) {
                compact(source, target);
            }
        }
    }

    /**
     * Copy all live pages from the source store to the target store.
     *
     * @param source the source store
     * @param target the target store
     */
    public static void compact(MVStore source, MVStore target) {
        target.setCurrentVersion(source.getCurrentVersion());
        target.adjustLastMapId(source.getLastMapId());
        int autoCommitDelay = target.getAutoCommitDelay();
        boolean reuseSpace = target.isSpaceReused();
        try {
            target.setReuseSpace(false);  // disable unused chunks collection
            target.setAutoCommitDelay(0); // disable autocommit
            MVMap<String, String> sourceMeta = source.getMetaMap();
            MVMap<String, String> targetMeta = target.getMetaMap();
            for (Entry<String, String> m : sourceMeta.entrySet()) {
                String key = m.getKey();
                if (key.startsWith(DataUtils.META_MAP)) {
                    // ignore
                } else if (key.startsWith(DataUtils.META_NAME)) {
                    // ignore
                } else {
                    targetMeta.put(key, m.getValue());
                }
            }
            // We are going to cheat a little bit in the copyFrom() by employing "incomplete" pages,
            // which would be spared of saving, but save completed pages underneath,
            // and those may appear as dead (non-reachable).
            // That's why it is important to preserve all chunks
            // created in the process, especially if retention time
            // is set to a lower value, or even 0.
            for (String mapName : source.getMapNames()) {
                MVMap.Builder<Object, Object> mp = getGenericMapBuilder();
                // This is a hack to preserve chunks occupancy rate accounting.
                // It exposes design deficiency flaw in MVStore related to lack of
                // map's type metadata.
                // TODO: Introduce type metadata which will allow to open any store
                // TODO: without prior knowledge of keys / values types and map implementation
                // TODO: (MVMap vs MVRTreeMap, regular vs. singleWriter etc.)
                if (mapName.startsWith(TransactionStore.UNDO_LOG_NAME_PREFIX)) {
                    mp.singleWriter();
                }
                MVMap<Object, Object> sourceMap = source.openMap(mapName, mp);
                MVMap<Object, Object> targetMap = target.openMap(mapName, mp);
                targetMap.copyFrom(sourceMap);
                targetMeta.put(MVMap.getMapKey(targetMap.getId()), sourceMeta.get(MVMap.getMapKey(sourceMap.getId())));
            }
            // this will end hacky mode of operation with incomplete pages
            // end ensure that all pages are saved
            target.commit();
        } finally {
            target.setAutoCommitDelay(autoCommitDelay);
            target.setReuseSpace(reuseSpace);
        }
    }

    /**
     * Repair a store by rolling back to the newest good version.
     *
     * @param fileName the file name
     */
    public static void repair(String fileName) {
        PrintWriter pw = new PrintWriter(System.out);
        long version = Long.MAX_VALUE;
        OutputStream ignore = new OutputStream() {
            @Override
            public void write(int b) {
                // ignore
            }
        };
        while (version >= 0) {
            pw.println(version == Long.MAX_VALUE ? "Trying latest version"
                    : ("Trying version " + version));
            pw.flush();
            version = rollback(fileName, version, new PrintWriter(ignore));
            try {
                String error = info(fileName + ".temp", new PrintWriter(ignore));
                if (error == null) {
                    FilePath.get(fileName).moveTo(FilePath.get(fileName + ".back"), true);
                    FilePath.get(fileName + ".temp").moveTo(FilePath.get(fileName), true);
                    pw.println("Success");
                    break;
                }
                pw.println("    ... failed: " + error);
            } catch (Exception e) {
                pw.println("Fail: " + e.getMessage());
                pw.flush();
            }
            version--;
        }
        pw.flush();
    }

    /**
     * Roll back to a given revision into a file called *.temp.
     *
     * @param fileName the file name
     * @param targetVersion the version to roll back to (Long.MAX_VALUE for the
     *            latest version)
     * @param writer the log writer
     * @return the version rolled back to (-1 if no version)
     */
    public static long rollback(String fileName, long targetVersion, Writer writer) {
        long newestVersion = -1;
        PrintWriter pw = new PrintWriter(writer, true);
        if (!FilePath.get(fileName).exists()) {
            pw.println("File not found: " + fileName);
            return newestVersion;
        }
        FileChannel file = null;
        FileChannel target = null;
        int blockSize = FileStore.BLOCK_SIZE;
        try {
            file = FilePath.get(fileName).open("r");
            FilePath.get(fileName + ".temp").delete();
            target = FilePath.get(fileName + ".temp").open("rw");
            long fileSize = file.size();
            ByteBuffer buffer = ByteBuffer.allocate(4096);
            Chunk newestChunk = null;
            for (long pos = 0; pos < fileSize;) {
                buffer.rewind();
                DataUtils.readFully(file, pos, buffer);
                buffer.rewind();
                int headerType = buffer.get();
                buffer.rewind();
                if (headerType == 'H') {
                    target.write(buffer, pos);
                    pos += blockSize;
                    continue;
                }
                if (headerType != 'c') {
                    pos += blockSize;
                    continue;
                }
                Chunk c;
                try {
                    c = new SFChunk(Chunk.readChunkHeader(buffer));
                } catch (MVStoreException e) {
                    pos += blockSize;
                    continue;
                }
                if (c.len <= 0) {
                    // not a chunk
                    pos += blockSize;
                    continue;
                }
                int length = c.len * FileStore.BLOCK_SIZE;
                ByteBuffer chunk = ByteBuffer.allocate(length);
                DataUtils.readFully(file, pos, chunk);
                if (c.version > targetVersion) {
                    // newer than the requested version
                    pos += length;
                    continue;
                }
                chunk.rewind();
                target.write(chunk, pos);
                if (newestChunk == null || c.version > newestChunk.version) {
                    newestChunk = c;
                    newestVersion = c.version;
                }
                pos += length;
            }
            int length = newestChunk.len * FileStore.BLOCK_SIZE;
            ByteBuffer chunk = ByteBuffer.allocate(length);
            DataUtils.readFully(file, newestChunk.block * FileStore.BLOCK_SIZE, chunk);
            chunk.rewind();
            target.write(chunk, fileSize);
        } catch (IOException e) {
            pw.println("ERROR: " + e);
            e.printStackTrace(pw);
        } finally {
            if (file != null) {
                try {
                    file.close();
                } catch (IOException e) {
                    // ignore
                }
            }
            if (target != null) {
                try {
                    target.close();
                } catch (IOException e) {
                    // ignore
                }
            }
        }
        pw.flush();
        return newestVersion;
    }

    @SuppressWarnings({"rawtypes","unchecked"})
    static MVMap.Builder<Object,Object> getGenericMapBuilder() {
        return (MVMap.Builder)new MVMap.Builder<byte[],byte[]>().
                keyType(GenericDataType.INSTANCE).
                valueType(GenericDataType.INSTANCE);
    }

    /**
     * A data type that can read any data that is persisted, and converts it to
     * a byte array.
     */
    private static class GenericDataType extends BasicDataType<byte[]> {
        static GenericDataType INSTANCE = new GenericDataType();

        private GenericDataType() {}

        @Override
        public boolean isMemoryEstimationAllowed() {
            return false;
        }

        @Override
        public int getMemory(byte[] obj) {
            return obj == null ? 0 : obj.length * 8;
        }

        @Override
        public byte[][] createStorage(int size) {
            return new byte[size][];
        }

        @Override
        public void write(WriteBuffer buff, byte[] obj) {
            if (obj != null) {
                buff.put(obj);
            }
        }

        @Override
        public byte[] read(ByteBuffer buff) {
            int len = buff.remaining();
            if (len == 0) {
                return null;
            }
            byte[] data = new byte[len];
            buff.get(data);
            return data;
        }
    }
}
