/*
 * This file is part of ClassGraph.
 *
 * Author: Luke Hutchison
 *
 * Hosted at: https://github.com/classgraph/classgraph
 *
 * --
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2019 Luke Hutchison
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without
 * limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO
 * EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN
 * AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE
 * OR OTHER DEALINGS IN THE SOFTWARE.
 */
package nonapi.io.github.classgraph.fastzipfilereader;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;

import nonapi.io.github.classgraph.concurrency.SingletonMap;
import nonapi.io.github.classgraph.concurrency.SingletonMap.NullSingletonException;
import nonapi.io.github.classgraph.scanspec.ScanSpec;
import nonapi.io.github.classgraph.utils.FileUtils;
import nonapi.io.github.classgraph.utils.LogNode;

/** Resources for a mapped file. */
public class MappedByteBufferResources {
    /** If true, a file was mapped from a {@link FileChannel}. */
    private File mappedFile;

    /** If true, the mapped file was created as a temp file when the InputStream wouldn't fit in RAM. */
    private boolean mappedFileIsTempFile;

    /** The raf. */
    private RandomAccessFile raf;

    /** The file channel. */
    private FileChannel fileChannel;

    /** The total length. */
    private final AtomicLong length = new AtomicLong();

    /** The cached mapped byte buffers for each 2GB chunk. */
    private AtomicReferenceArray<ByteBufferWrapper> byteBufferChunksCached;

    /** A singleton map from chunk index to byte buffer, ensuring that any given chunk is only mapped once. */
    private SingletonMap<Integer, ByteBufferWrapper, IOException> chunkIdxToByteBufferSingletonMap;

    /** The nested jar handler. */
    private final NestedJarHandler nestedJarHandler;

    /** Set to true once {@link #close()} has been called. */
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * Read all the bytes in an {@link InputStream}, with spillover to a temporary file on disk if a maximum buffer
     * size is exceeded.
     *
     * @param inputStream
     *            The {@link InputStream}.
     * @param inputStreamLengthHint
     *            The number of bytes to read in inputStream, or -1 if unknown.
     * @param tempFileBaseName
     *            the source URL or zip entry that inputStream was opened from (used to name temporary file, if
     *            needed).
     * @param nestedJarHandler
     *            the nested jar handler
     * @param log
     *            the log.
     * @throws IOException
     *             If the contents could not be read.
     */
    public MappedByteBufferResources(final InputStream inputStream, final int inputStreamLengthHint,
            final String tempFileBaseName, final NestedJarHandler nestedJarHandler, final LogNode log)
            throws IOException {
        this.nestedJarHandler = nestedJarHandler;
        final ScanSpec scanSpec = nestedJarHandler.scanSpec;
        byte[] buf;
        boolean spillToDisk;
        if (inputStreamLengthHint != -1 && inputStreamLengthHint <= scanSpec.maxBufferedJarRAMSize) {
            // inputStreamLengthHint indicates that inputStream is longer than scanSpec.maxJarRamSize,
            // so try downloading to RAM
            buf = new byte[inputStreamLengthHint == -1 ? scanSpec.maxBufferedJarRAMSize
                    : Math.min(scanSpec.maxBufferedJarRAMSize, inputStreamLengthHint)];
            final int bufLength = buf.length;

            int totBytesRead = 0;
            int bytesRead = 0;
            while ((bytesRead = inputStream.read(buf, totBytesRead, bufLength - totBytesRead)) > 0) {
                // Fill buffer until nothing more can be read
                totBytesRead += bytesRead;
            }
            if (bytesRead < 0) {
                // Successfully reached end of stream -- wrap array buffer with ByteBuffer
                if (totBytesRead < buf.length) {
                    // Trim array
                    buf = Arrays.copyOf(buf, totBytesRead);
                }
                // Wrap array in a RAM-backed ByteBuffer
                wrapByteBuffer(new ByteBufferWrapper(buf));
                spillToDisk = false;
            } else {
                // Didn't reach end of inputStream after buf was filled, so inputStreamLengthHint underestimated
                // the length of the stream -- spill to disk, since we don't know how long the stream is now
                spillToDisk = true;
            }
        } else {
            // inputStreamLengthHint indicates that inputStream is longer than scanSpec.maxJarRamSize,
            // so immediately spill to disk
            buf = null;
            spillToDisk = true;
        }
        if (spillToDisk) {
            // bytesRead == 0 => ran out of buffer space, spill over to disk
            if (log != null) {
                log.log("Could not fit InputStream content into max RAM buffer size of "
                        + scanSpec.maxBufferedJarRAMSize + " bytes, saving to temporary file: " + tempFileBaseName
                        + " -> " + this.mappedFile);
            }
            try {
                this.mappedFile = nestedJarHandler.makeTempFile(tempFileBaseName, /* onlyUseLeafname = */ true);
            } catch (final IOException e) {
                if (log != null) {
                    log.log("Could not create temporary file: " + e);
                }
                throw e;
            }
            this.mappedFileIsTempFile = true;

            if (buf != null) {
                // If any content was already read from inputStream, flush it out to the temporary file
                Files.write(this.mappedFile.toPath(), buf, StandardOpenOption.WRITE);
            } else {
                // Buffer was never allocated -- allocate one for the copy operation below
                buf = new byte[8192];
            }

            // Copy the rest of the InputStream to the end of the temporary file
            try (OutputStream os = new BufferedOutputStream(
                    new FileOutputStream(this.mappedFile, /* append = */ true))) {
                for (int bytesReadCtd; (bytesReadCtd = inputStream.read(buf, 0, buf.length)) > 0;) {
                    os.write(buf, 0, bytesReadCtd);
                }
            }

            // Map the file to a MappedByteBuffer
            mapFile(scanSpec.disableMemoryMapping, log);
        }
    }

    /**
     * Map a {@link File} to a {@link MappedByteBuffer}.
     *
     * @param file
     *            the file
     * @param nestedJarHandler
     *            the nested jar handler
     * @param log
     *            the log
     * @throws IOException
     *             If the contents could not be read.
     */
    public MappedByteBufferResources(final File file, final NestedJarHandler nestedJarHandler, final LogNode log)
            throws IOException {
        this.nestedJarHandler = nestedJarHandler;
        this.mappedFile = file;
        // Map the file to a MappedByteBuffer
        mapFile(nestedJarHandler.scanSpec.disableMemoryMapping, log);
    }

    /**
     * Wrap an existing ByteBuffer.
     *
     * @param byteBuffer
     *            the byte buffer
     * @param nestedJarHandler
     *            the nested jar handler
     * @throws IOException
     *             If the contents could not be read.
     */
    public MappedByteBufferResources(final ByteBuffer byteBuffer, final NestedJarHandler nestedJarHandler)
            throws IOException {
        this.nestedJarHandler = nestedJarHandler;
        // Wrap the existing byte buffer
        wrapByteBuffer(new ByteBufferWrapper(byteBuffer));
    }

    /**
     * Wrap an existing single-chunk {@link ByteBuffer}.
     *
     * @param byteBuffer
     *            the {@link ByteBuffer}.
     */
    private void wrapByteBuffer(final ByteBufferWrapper byteBuffer) {
        length.set(byteBuffer.remaining());
        // Put the ByteBuffer into the cache, so that the singleton map code for file mapping is never called
        byteBufferChunksCached = new AtomicReferenceArray<ByteBufferWrapper>(1);
        byteBufferChunksCached.set(0, byteBuffer);
        // Don't set mappedFile, fileChannel or raf, they are unneeded.
    }

    /**
     * Map a {@link File} to a {@link MappedByteBuffer} or {@link RandomAccessFile}.
     *
     * @param disableMemoryMapping
     *            If true, use a {@link RandomAccessFile} rather than a {@link MappedByteBuffer} so that virtual
     *            memory space is not used when reading jarfiles.
     * @param log
     *            the log.
     * @throws IOException
     *             Signals that an I/O exception has occurred.
     */
    private void mapFile(final boolean disableMemoryMapping, final LogNode log) throws IOException {
        // If memory mapping is enabled, share one instance of RandomAccessFile and FileChannel across
        // all memory-mapped chunks, to avoid opening a new file or channel for every new chunk mapping
        if (!disableMemoryMapping) {
            try {
                raf = new RandomAccessFile(mappedFile, "r");
                fileChannel = raf.getChannel();
            } catch (final IOException e) {
                close(log);
                throw e;
            } catch (final IllegalArgumentException | SecurityException e) {
                close(log);
                throw new IOException(e);
            }
        }
        length.set(mappedFile.length());

        // Implement an array of MappedByteBuffers to support jarfiles >2GB in size:
        // https://bugs.java.com/bugdatabase/view_bug.do?bug_id=6347833
        final int numByteBufferChunks = (int) ((length.get() + FileUtils.MAX_BUFFER_SIZE)
                / FileUtils.MAX_BUFFER_SIZE);
        byteBufferChunksCached = new AtomicReferenceArray<ByteBufferWrapper>(numByteBufferChunks);
        chunkIdxToByteBufferSingletonMap = new SingletonMap<Integer, ByteBufferWrapper, IOException>() {
            @Override
            public ByteBufferWrapper newInstance(final Integer chunkIdxI, final LogNode log) throws IOException {
                // Map the indexed 2GB chunk of the file to a MappedByteBuffer
                final long pos = chunkIdxI.longValue() * FileUtils.MAX_BUFFER_SIZE;
                final long chunkSize = Math.min(FileUtils.MAX_BUFFER_SIZE, length.get() - pos);

                ByteBufferWrapper byteBuffer = null;
                if (!disableMemoryMapping) {
                    if (fileChannel == null) {
                        // Should not happen
                        throw new IOException("Cannot map a null FileChannel");
                    }

                    try {
                        // Try memory-mapping the file channel
                        byteBuffer = new ByteBufferWrapper(fileChannel, pos, chunkSize);

                    } catch (final IOException e) {
                        // Should not happen, since if raf was opened, the file must exist.
                        // (TODO: I saw mention somewhere that on some operating systems, the JRE
                        // may throw IOException and not OutOfMemoryError if memory mapping runs
                        // out of virtual address space -- need to investigate this.)
                        MappedByteBufferResources.this.close(log);
                        throw e;

                    } catch (final OutOfMemoryError e) {
                        if (log != null) {
                            log.log("Out of memory when trying to memory map file " + mappedFile);
                        }
                        // Failover to non-memory-mapped mode
                        // (fileChannel and raf will be closed when this MappedByteBufferResources object is closed)
                    }
                }

                if (byteBuffer == null) {
                    // Memory mapping was disabled or failed -- use a RandomAccessFile to access the file instead
                    if (log != null) {
                        log.log("Memory mapping is disabled for file " + mappedFile
                                + " -- using slower RandomAccessFile method to open file instead");
                    }

                    // If memory mapping was disabled or failed, every duplicate of this ByteBufferWrapper
                    // needs to open its own RAF, for thread safety
                    byteBuffer = new ByteBufferWrapper(mappedFile, pos, chunkSize);
                }

                // Record that the byte buffer has been mapped
                nestedJarHandler.addMappedByteBuffer(byteBuffer);

                return byteBuffer;
            }
        };
    }

    /**
     * Get a mmap'd chunk of the file, where chunkIdx denotes which 2GB chunk of the file to return (0 for the first
     * 2GB of the file, or for files smaller than 2GB; 1 for the 2-4GB chunk, etc.).
     * 
     * @param chunkIdx
     *            The index of the 2GB chunk to read, between 0 and {@link #numChunks()} - 1.
     * @return The {@link MappedByteBuffer} for the requested file chunk, up to 2GB in size.
     * @throws IOException
     *             If the chunk could not be mmap'd.
     * @throws InterruptedException
     *             If the thread was interrupted.
     */
    public ByteBufferWrapper getByteBuffer(final int chunkIdx) throws IOException, InterruptedException {
        if (closed.get()) {
            throw new IOException(getClass().getSimpleName() + " already closed");
        }
        if (chunkIdx < 0 || chunkIdx >= numChunks()) {
            throw new IOException("Chunk index out of range");
        }
        // Fast path: only look up singleton map if mappedByteBuffersCached is null
        ByteBufferWrapper cachedBuf = byteBufferChunksCached.get(chunkIdx);
        if (cachedBuf == null) {
            // This 2GB chunk has not yet been read -- mmap it and cache it.
            // (Use a singleton map so that the mmap doesn't happen more than once)
            if (chunkIdxToByteBufferSingletonMap == null) {
                // Should not happen
                throw new IOException("chunkIdxToByteBufferSingletonMap is null");
            }
            try {
                cachedBuf = chunkIdxToByteBufferSingletonMap.get(chunkIdx, /* log = */ null);
                byteBufferChunksCached.set(chunkIdx, cachedBuf);
            } catch (final NullSingletonException e) {
                throw new IOException("Cannot get ByteBuffer chunk " + chunkIdx + " : " + e);
            }
        }
        return cachedBuf;
    }

    /**
     * Get the mapped file (or null if an in-memory {@link ByteBuffer} was wrapped instead).
     *
     * @return the mapped file
     */
    public File getMappedFile() {
        return mappedFile;
    }

    /**
     * Get the length of the mapped file, or the initial remaining bytes in the wrapped ByteBuffer if a buffer was
     * wrapped.
     */
    public long length() {
        return length.get();
    }

    /**
     * Get the number of 2GB chunks that are available in this mapped file or wrapped ByteBuffer.
     */
    public int numChunks() {
        return byteBufferChunksCached == null ? 0 : byteBufferChunksCached.length();
    }

    /**
     * Free resources.
     *
     * @param log
     *            the log
     */
    public void close(final LogNode log) {
        if (!closed.getAndSet(true)) {
            if (chunkIdxToByteBufferSingletonMap != null) {
                chunkIdxToByteBufferSingletonMap.clear();
                chunkIdxToByteBufferSingletonMap = null;
            }
            if (byteBufferChunksCached != null) {
                // Only unmap bytebuffers if they came from a mapped file
                if (mappedFile != null) {
                    for (int i = 0; i < byteBufferChunksCached.length(); i++) {
                        final ByteBufferWrapper mappedByteBuffer = byteBufferChunksCached.get(i);
                        if (mappedByteBuffer != null) {
                            nestedJarHandler.unmapByteBuffer(mappedByteBuffer, log);
                            byteBufferChunksCached.set(i, null);
                        }
                    }
                }
                byteBufferChunksCached = null;
            }
            if (fileChannel != null) {
                try {
                    fileChannel.close();
                } catch (final IOException e) {
                    // Ignore
                }
                fileChannel = null;
            }
            if (raf != null) {
                try {
                    raf.close();
                } catch (final IOException e) {
                    // Ignore
                }
                raf = null;
            }
            if (mappedFile != null) {
                // If mapped file was a temp file, remove it
                if (mappedFileIsTempFile) {
                    try {
                        nestedJarHandler.removeTempFile(mappedFile);
                    } catch (IOException | SecurityException e) {
                        if (log != null) {
                            log.log("Removing temporary file failed: " + mappedFile);
                        }
                    }
                }
                mappedFile = null;
            }
        }
    }
}