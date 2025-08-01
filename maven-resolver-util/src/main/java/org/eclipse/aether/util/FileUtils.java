/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.eclipse.aether.util;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.util.Objects.requireNonNull;

/**
 * A utility class to write files.
 *
 * @since 1.9.0
 */
public final class FileUtils {
    // Logic borrowed from Commons-Lang3: we really need only this, to decide do we "atomic move" or not
    private static final boolean IS_WINDOWS =
            System.getProperty("os.name", "unknown").startsWith("Windows");

    private FileUtils() {
        // hide constructor
    }

    /**
     * A temporary file, that is removed when closed.
     */
    public interface TempFile extends Closeable {
        /**
         * Returns the path of the created temp file.
         */
        Path getPath();
    }

    /**
     * A collocated temporary file, that resides next to a "target" file, and is removed when closed.
     */
    public interface CollocatedTempFile extends TempFile {
        /**
         * Upon close, atomically moves temp file to target file it is collocated with overwriting target (if exists).
         * Invocation of this method merely signals that caller ultimately wants temp file to replace the target
         * file, but when this method returns, the move operation did not yet happen, it will happen when this
         * instance is closed.
         * <p>
         * Invoking this method <em>without writing to temp file</em> {@link #getPath()} (thus, not creating a temp
         * file to be moved) is considered a bug, a mistake of the caller. Caller of this method should ensure
         * that this method is invoked ONLY when the temp file is created and moving it to its final place is
         * required.
         */
        void move() throws IOException;
    }

    /**
     * Creates a {@link TempFile} instance and backing temporary file on file system. It will be located in the default
     * temporary-file directory. Returned instance should be handled in try-with-resource construct and created
     * temp file is removed (if exists) when returned instance is closed.
     * <p>
     * This method uses {@link Files#createTempFile(String, String, java.nio.file.attribute.FileAttribute[])} to create
     * the temporary file on file system.
     */
    public static TempFile newTempFile() throws IOException {
        Path tempFile = Files.createTempFile("resolver", "tmp");
        return new TempFile() {
            @Override
            public Path getPath() {
                return tempFile;
            }

            @Override
            public void close() throws IOException {
                Files.deleteIfExists(tempFile);
            }
        };
    }

    /**
     * Creates a {@link CollocatedTempFile} instance for given file without backing file. The path will be located in
     * same directory where given file is, and will reuse its name for generated (randomized) name. Returned instance
     * should be handled in try-with-resource and created temp path is removed (if exists) when returned instance is
     * closed. The {@link CollocatedTempFile#move()} makes possible to atomically replace passed in file with the
     * processed content written into a file backing the {@link CollocatedTempFile} instance.
     * <p>
     * The {@code file} nor it's parent directories have to exist. The parent directories are created if needed.
     * <p>
     * This method uses {@link Path#resolve(String)} to create the temporary file path in passed in file parent
     * directory, but it does NOT create backing file on file system.
     */
    public static CollocatedTempFile newTempFile(Path file) throws IOException {
        Path parent = requireNonNull(file.getParent(), "file must have parent");
        Files.createDirectories(parent);
        Path tempFile = parent.resolve(file.getFileName() + "."
                + Long.toUnsignedString(ThreadLocalRandom.current().nextLong()) + ".tmp");
        return new CollocatedTempFile() {
            private final AtomicBoolean wantsMove = new AtomicBoolean(false);

            @Override
            public Path getPath() {
                return tempFile;
            }

            @Override
            public void move() {
                wantsMove.set(true);
            }

            @Override
            public void close() throws IOException {
                if (wantsMove.get()) {
                    if (IS_WINDOWS) {
                        copy(tempFile, file);
                    } else {
                        Files.move(tempFile, file, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
                Files.deleteIfExists(tempFile);
            }
        };
    }

    /**
     * On Windows we use pre-NIO2 way to copy files, as for some reason it works. Beat me why.
     */
    private static void copy(Path source, Path target) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(1024 * 32);
        byte[] array = buffer.array();
        try (InputStream is = Files.newInputStream(source);
                OutputStream os = Files.newOutputStream(target)) {
            while (true) {
                int bytes = is.read(array);
                if (bytes < 0) {
                    break;
                }
                os.write(array, 0, bytes);
            }
        }
    }

    /**
     * A file writer, that accepts a {@link Path} to write some content to. Note: the file denoted by path may exist,
     * hence implementation have to ensure it is able to achieve its goal ("replace existing" option or equivalent
     * should be used).
     */
    @FunctionalInterface
    public interface FileWriter {
        void write(Path path) throws IOException;
    }

    /**
     * Writes file without backup.
     *
     * @param target that is the target file (must be file, the path must have parent).
     * @param writer the writer that will accept a {@link Path} to write content to.
     * @throws IOException if at any step IO problem occurs.
     */
    public static void writeFile(Path target, FileWriter writer) throws IOException {
        writeFile(target, writer, false);
    }

    /**
     * Writes file with backup copy (appends ".bak" extension).
     *
     * @param target that is the target file (must be file, the path must have parent).
     * @param writer the writer that will accept a {@link Path} to write content to.
     * @throws IOException if at any step IO problem occurs.
     */
    public static void writeFileWithBackup(Path target, FileWriter writer) throws IOException {
        writeFile(target, writer, true);
    }

    /**
     * Utility method to write out file to disk in "atomic" manner, with optional backups (".bak") if needed. This
     * ensures that no other thread or process will be able to read not fully written files. Finally, this method
     * may create the needed parent directories, if the passed in target parents does not exist.
     *
     * @param target   that is the target file (must be an existing or non-existing file, the path must have parent).
     * @param writer   the writer that will accept a {@link Path} to write content to.
     * @param doBackup if {@code true}, and target file is about to be overwritten, a ".bak" file with old contents will
     *                 be created/overwritten.
     * @throws IOException if at any step IO problem occurs.
     */
    private static void writeFile(Path target, FileWriter writer, boolean doBackup) throws IOException {
        requireNonNull(target, "target is null");
        requireNonNull(writer, "writer is null");
        Path parent = requireNonNull(target.getParent(), "target must have parent");

        try (CollocatedTempFile tempFile = newTempFile(target)) {
            writer.write(tempFile.getPath());
            if (doBackup && Files.isRegularFile(target)) {
                Files.copy(target, parent.resolve(target.getFileName() + ".bak"), StandardCopyOption.REPLACE_EXISTING);
            }
            tempFile.move();
        }
    }
}
