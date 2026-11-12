package org.gradle.wrapper;

import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Transparent minimal Gradle bootstrap included because the original wrapper JAR was not in the APK.
 * It is not the official Gradle wrapper. To use the official wrapper, run `gradle wrapper` after setup.
 */
public final class GradleWrapperMain {
    public static void main(String[] args) throws Exception {
        Path project = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(project.resolve("gradle/wrapper/gradle-wrapper.properties"))) { props.load(in); }
        String url = props.getProperty("distributionUrl", "").replace("\\:", ":");
        if (!url.matches("https://services\\.gradle\\.org/distributions/gradle-[0-9.]+-bin\\.zip")) {
            throw new IOException("Only pinned official HTTPS Gradle distributions are allowed");
        }
        String base = url.substring(url.lastIndexOf('/') + 1).replace("-bin.zip", "");
        Path root = Paths.get(System.getProperty("user.home"), ".gradle", "wrapper", "cortex-bootstrap", base);
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows");
        Path executable = root.resolve(base).resolve("bin").resolve(windows ? "gradle.bat" : "gradle");
        if (!Files.isRegularFile(executable)) {
            Files.createDirectories(root);
            Path archive = root.resolve(base + "-bin.zip");
            if (!Files.isRegularFile(archive)) {
                System.out.println("Downloading official Gradle distribution: " + url);
                Path partial = root.resolve(base + "-bin.zip.part");
                try (InputStream in = URI.create(url).toURL().openStream()) { Files.copy(in, partial, StandardCopyOption.REPLACE_EXISTING); }
                Files.move(partial, archive, StandardCopyOption.REPLACE_EXISTING);
            }
            // Verify against the official checksum published alongside the Gradle distribution.
            String expected;
            try (InputStream checksum = URI.create(url + ".sha256").toURL().openStream()) {
                expected = new String(checksum.readAllBytes(), java.nio.charset.StandardCharsets.US_ASCII).trim().split("\\s+")[0];
            }
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            try (InputStream data = Files.newInputStream(archive)) {
                byte[] block = new byte[65536]; int count;
                while ((count = data.read(block)) >= 0) digest.update(block, 0, count);
            }
            StringBuilder actual = new StringBuilder();
            for (byte b : digest.digest()) actual.append(String.format("%02x", b & 255));
            if (!actual.toString().equalsIgnoreCase(expected)) {
                Files.deleteIfExists(archive);
                throw new IOException("Gradle archive checksum mismatch; deleted invalid download");
            }
            try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    Path out = root.resolve(entry.getName()).normalize();
                    if (!out.startsWith(root)) throw new IOException("Unsafe archive path: " + entry.getName());
                    if (entry.isDirectory()) Files.createDirectories(out);
                    else {
                        Files.createDirectories(out.getParent());
                        Files.copy(zip, out, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
            if (!windows) executable.toFile().setExecutable(true, true);
        }
        List<String> command = new ArrayList<>();
        command.add(executable.toString()); command.addAll(Arrays.asList(args));
        Process proc = new ProcessBuilder(command).directory(project.toFile()).inheritIO().start();
        System.exit(proc.waitFor());
    }
}
