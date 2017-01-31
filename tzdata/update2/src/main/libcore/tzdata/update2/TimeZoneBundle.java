/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package libcore.tzdata.update2;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * A time zone bundle. This is a thin wrapper around some in-memory bytes representing a zip
 * archive and logic for its safe extraction.
 */
public final class TimeZoneBundle {

    /** The name of the file inside the bundle containing bionic/libcore TZ data. */
    public static final String TZDATA_FILE_NAME = "tzdata";

    /** The name of the file inside the bundle containing ICU TZ data. */
    public static final String ICU_DATA_FILE_NAME = "icu/icu_tzdata.dat";

    /**
     * The name of the file inside the bundle containing the bundle version information.
     * The content is ASCII bytes representing a set of version numbers. See {@link BundleVersion}.
     */
    public static final String BUNDLE_VERSION_FILE_NAME = "bundle_version";

    private static final int BUFFER_SIZE = 8192;

    /**
     * Maximum size of entry getEntryContents() will pull into a byte array. To avoid exhausting
     * heap memory when encountering unexpectedly large entries. 128k should be enough for anyone.
     */
    private static final long MAX_GET_ENTRY_CONTENTS_SIZE = 128 * 1024;

    private final byte[] bytes;

    public TimeZoneBundle(byte[] bytes) {
        this.bytes = bytes;
    }

    public byte[] getBytes() {
        return bytes;
    }

    public BundleVersion getBundleVersion() throws BundleException, IOException {
        byte[] contents = getEntryContents(
                new ByteArrayInputStream(bytes), BUNDLE_VERSION_FILE_NAME);
        if (contents == null) {
            throw new BundleException("Bundle version file entry not found");
        }
        return BundleVersion.fromBytes(contents);
    }

    private static byte[] getEntryContents(InputStream is, String entryName) throws IOException {
        try (ZipInputStream zipInputStream = new ZipInputStream(is)) {
            ZipEntry entry;
            while ((entry = zipInputStream.getNextEntry()) != null) {
                String name = entry.getName();

                if (!entryName.equals(name)) {
                    continue;
                }
                // Guard against massive entries consuming too much heap memory.
                if (entry.getSize() > MAX_GET_ENTRY_CONTENTS_SIZE) {
                    throw new IOException("Entry " + entryName + " too large: " + entry.getSize());
                }
                byte[] buffer = new byte[BUFFER_SIZE];
                try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                    int count;
                    while ((count = zipInputStream.read(buffer)) != -1) {
                        baos.write(buffer, 0, count);
                    }
                    return baos.toByteArray();
                }
            }
            // Entry not found.
            return null;
        }
    }

    public void extractTo(File targetDir) throws IOException {
        extractZipSafely(new ByteArrayInputStream(bytes), targetDir, true /* makeWorldReadable */);
    }

    /** Visible for testing */
    static void extractZipSafely(InputStream is, File targetDir, boolean makeWorldReadable)
            throws IOException {

        // Create the extraction dir, if needed.
        FileUtils.ensureDirectoriesExist(targetDir, makeWorldReadable);

        try (ZipInputStream zipInputStream = new ZipInputStream(is)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            ZipEntry entry;
            while ((entry = zipInputStream.getNextEntry()) != null) {
                // Validate the entry name: make sure the unpacked file will exist beneath the
                // targetDir.
                String name = entry.getName();
                // Note, we assume that nothing will quickly insert a symlink after createSubFile()
                // that might invalidate the guarantees about name existing beneath targetDir.
                File entryFile = FileUtils.createSubFile(targetDir, name);

                if (entry.isDirectory()) {
                    FileUtils.ensureDirectoriesExist(entryFile, makeWorldReadable);
                } else {
                    // Create the path if there was no directory entry.
                    if (!entryFile.getParentFile().exists()) {
                        FileUtils.ensureDirectoriesExist(
                                entryFile.getParentFile(), makeWorldReadable);
                    }

                    try (FileOutputStream fos = new FileOutputStream(entryFile)) {
                        int count;
                        while ((count = zipInputStream.read(buffer)) != -1) {
                            fos.write(buffer, 0, count);
                        }
                        // sync to disk
                        fos.getFD().sync();
                    }
                    // mark entryFile -rw-r--r--
                    if (makeWorldReadable) {
                        FileUtils.makeWorldReadable(entryFile);
                    }
                }
            }
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        TimeZoneBundle that = (TimeZoneBundle) o;

        if (!Arrays.equals(bytes, that.bytes)) {
            return false;
        }

        return true;
    }
}
