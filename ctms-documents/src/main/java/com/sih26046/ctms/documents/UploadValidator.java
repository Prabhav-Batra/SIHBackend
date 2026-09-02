package com.sih26046.ctms.documents;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.tika.detect.DefaultDetector;
import org.apache.tika.detect.Detector;
import org.apache.tika.metadata.Metadata;
import org.springframework.stereotype.Component;

/**
 * The layered upload checks of §16.5.
 *
 * <p>Content sniffing is the one that matters. Extension and {@code Content-Type} are both
 * attacker-controlled; magic bytes are a property of the file itself. So detection here runs on
 * content <em>only</em> — Tika is never given the filename as a hint, because a detector told
 * what to expect will agree with an attacker's chosen extension and the check becomes a mirror.
 *
 * <p>A mismatch is rejected outright rather than corrected: a file whose extension, declared
 * type and true type disagree is not one this platform should store under any of them.
 */
@Component
public class UploadValidator {

    private static final String OOXML_CONTAINER = "application/x-tika-ooxml";
    private static final String OOXML_PREFIX =
            "application/vnd.openxmlformats-officedocument.";

    /**
     * What each allowed extension may actually be.
     *
     * <p>OOXML is the awkward case. Detected from content alone, {@code .docx} and {@code .xlsx}
     * are both a ZIP container, because telling them apart means reading entries inside the
     * archive. That is an acceptable limit: the threat this check exists to stop is an
     * executable wearing a document's extension, and a container is unambiguously not one.
     *
     * <p>CSV is the other. Tika cannot distinguish it from any other delimited text and says
     * {@code text/plain}; accepting that is the honest reading, and the allowlist still keeps
     * executables out.
     */
    private static final Map<String, AllowedType> ALLOWLIST =
            Map.of(
                    "pdf", new AllowedType(Set.of("application/pdf"), "application/pdf", "raw"),
                    "png", new AllowedType(Set.of("image/png"), "image/png", "image"),
                    "jpg", new AllowedType(Set.of("image/jpeg"), "image/jpeg", "image"),
                    "jpeg", new AllowedType(Set.of("image/jpeg"), "image/jpeg", "image"),
                    "csv",
                            new AllowedType(
                                    Set.of("text/csv", "text/plain"), "text/csv", "raw"),
                    "docx",
                            new AllowedType(
                                    Set.of(
                                            OOXML_CONTAINER,
                                            OOXML_PREFIX + "wordprocessingml.document"),
                                    OOXML_PREFIX + "wordprocessingml.document",
                                    "raw"),
                    "xlsx",
                            new AllowedType(
                                    Set.of(OOXML_CONTAINER, OOXML_PREFIX + "spreadsheetml.sheet"),
                                    OOXML_PREFIX + "spreadsheetml.sheet",
                                    "raw"));

    private final Detector detector = new DefaultDetector();
    private final DocumentProperties properties;

    public UploadValidator(DocumentProperties properties) {
        this.properties = properties;
    }

    /** What validation concluded about a file, and how it should be stored. */
    public record ValidatedUpload(String fileName, String mimeType, String resourceType) {}

    private record AllowedType(Set<String> sniffed, String canonical, String resourceType) {}

    public ValidatedUpload validate(String originalFileName, long sizeBytes, InputStream content)
            throws IOException {

        if (sizeBytes <= 0) {
            throw new RejectedUploadException("The file is empty");
        }
        if (sizeBytes > properties.maxFileSizeBytes()) {
            throw new RejectedUploadException(
                    "The file exceeds the %d byte limit".formatted(properties.maxFileSizeBytes()));
        }

        String fileName = sanitise(originalFileName);
        String extension = extensionOf(fileName);
        AllowedType allowed = ALLOWLIST.get(extension);
        if (allowed == null) {
            throw new RejectedUploadException(
                    "Files of type '%s' are not accepted".formatted(extension));
        }

        String sniffed = sniff(content);
        if (!allowed.sniffed().contains(sniffed)) {
            // Names what was found, not what would have been accepted: the latter is a
            // probing aid.
            throw new RejectedUploadException(
                    "The file's content is %s, which does not match its .%s extension"
                            .formatted(sniffed, extension));
        }

        // Store the extension's canonical type rather than the sniffed one where the sniff was
        // a generic container. This is not correcting a mismatch — the two already agree; it
        // records the more specific of two consistent answers.
        return new ValidatedUpload(fileName, allowed.canonical(), allowed.resourceType());
    }

    private String sniff(InputStream content) throws IOException {
        // Tika needs mark support so it can rewind after reading the signature.
        InputStream markable = content.markSupported() ? content : new BufferedInputStream(content);
        return detector.detect(markable, new Metadata()).getBaseType().toString();
    }

    /**
     * Strips everything that could make a filename mean something to a filesystem: directory
     * components, control characters, and parent references. Taking only the final segment
     * defeats traversal regardless of separator style.
     */
    static String sanitise(String originalFileName) {
        if (originalFileName == null || originalFileName.isBlank()) {
            throw new RejectedUploadException("The file has no name");
        }
        String name = originalFileName.replace(CHR_BACKSLASH, CHR_SLASH);
        name = name.substring(name.lastIndexOf(CHR_SLASH) + 1);
        StringBuilder cleaned = new StringBuilder(name.length());
        for (char c : name.toCharArray()) {
            if (!Character.isISOControl(c)) {
                cleaned.append(c);
            }
        }
        name = cleaned.toString().replace("..", "").trim();
        if (name.isBlank()) {
            throw new RejectedUploadException("The file has no usable name");
        }
        return name;
    }

    private static final char CHR_BACKSLASH = 92;
    private static final char CHR_SLASH = 47;

    private static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            throw new RejectedUploadException("The file has no extension");
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
