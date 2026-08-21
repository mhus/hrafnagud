package de.mhus.hrafnagud.jaglan;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The mount's addressing.
 *
 * <p>Every stored link in a brain document contains one of these strings, so
 * the tests here are less about behaviour than about permanence: a change that
 * makes them fail is a change that breaks links somebody saved.
 */
class ArchivePathTest {

    private static final Instant SEEN = Instant.parse("2026-08-21T14:37:02Z");
    private static final String ARTICLE_ID = "68a7c1f2e4b09d3a5c6e7f80";
    private static final String IMAGE_ID =
            "276a1ac00ba4f0ea47eeeafca24284f41bc78dc593af1f048615aceba44ab9d9";

    // ─── Building ───

    @Test
    void article_isPartitionedByHour() {
        assertThat(ArchivePath.ofArticle(ARTICLE_ID, SEEN))
                .isEqualTo("article/2026/08/21/14/68a7c1f2e4b09d3a5c6e7f80.md");
    }

    @Test
    void image_takesItsExtensionFromTheMediaType() {
        assertThat(ArchivePath.ofImage(IMAGE_ID, SEEN, "image/jpeg"))
                .contains("img/2026/08/21/14/" + IMAGE_ID + ".jpg");
        assertThat(ArchivePath.ofImage(IMAGE_ID, SEEN, "image/png"))
                .contains("img/2026/08/21/14/" + IMAGE_ID + ".png");
        assertThat(ArchivePath.ofImage(IMAGE_ID, SEEN, "IMAGE/WEBP "))
                .contains("img/2026/08/21/14/" + IMAGE_ID + ".webp");
    }

    @Test
    void unknownMediaType_yieldsNoPath() {
        // Rather than an extension-less name: the fetcher only stores types the
        // map knows, so a miss means the two drifted apart, and inventing a
        // name would hide that behind a file nothing opens.
        assertThat(ArchivePath.ofImage(IMAGE_ID, SEEN, "image/svg+xml")).isEmpty();
        assertThat(ArchivePath.ofImage(IMAGE_ID, SEEN, null)).isEmpty();
        assertThat(ArchivePath.ofImage(IMAGE_ID, SEEN, "")).isEmpty();
    }

    @Test
    void partitioning_isUtcRegardlessOfTheHost() {
        // 23:30 UTC is the next day in Berlin. If the local zone leaked in
        // here, the same object would live at two paths depending on where the
        // service runs.
        assertThat(ArchivePath.ofArticle(ARTICLE_ID, Instant.parse("2026-08-21T23:30:00Z")))
                .contains("2026/08/21/23");
    }

    @Test
    void midnight_andEndOfYear_keepTheirZeros() {
        assertThat(ArchivePath.ofArticle(ARTICLE_ID, Instant.parse("2026-01-01T00:00:00Z")))
                .isEqualTo("article/2026/01/01/00/" + ARTICLE_ID + ".md");
    }

    // ─── Resolving ───

    @Test
    void roundTrip_yieldsTheId() {
        Optional<ArchivePath.Ref> ref =
                ArchivePath.parse(ArchivePath.ofArticle(ARTICLE_ID, SEEN));

        assertThat(ref).isPresent();
        assertThat(ref.get().kind()).isEqualTo(ArchivePath.Kind.ARTICLE);
        assertThat(ref.get().id()).isEqualTo(ARTICLE_ID);
    }

    @Test
    void imageRoundTrip_dropsTheExtension() {
        ArchivePath.Ref ref =
                ArchivePath.parse(ArchivePath.ofImage(IMAGE_ID, SEEN, "image/jpeg").orElseThrow())
                        .orElseThrow();

        assertThat(ref.kind()).isEqualTo(ArchivePath.Kind.IMAGE);
        assertThat(ref.id()).isEqualTo(IMAGE_ID);
    }

    @Test
    void leadingSlash_isTolerated() {
        assertThat(ArchivePath.parse("/article/2026/08/21/14/" + ARTICLE_ID + ".md")).isPresent();
    }

    @Test
    void theDateIsVerifiedAgainstTheObject() {
        // Without this the same file answers under every date, and Jaglan
        // creates a metadata row — a visible duplicate document — per spelling.
        ArchivePath.Ref ref =
                ArchivePath.parse(ArchivePath.ofArticle(ARTICLE_ID, SEEN)).orElseThrow();

        assertThat(ref.matches(SEEN)).isTrue();
        assertThat(ref.matches(SEEN.plusSeconds(3600)))
                .as("an hour later is a different partition")
                .isFalse();
        assertThat(ref.matches(SEEN.plusSeconds(60)))
                .as("a minute later is the same partition")
                .isTrue();
    }

    // ─── Rejecting ───

    @Test
    void unknownSubtree_isRejected() {
        assertThat(ArchivePath.parse("audio/2026/08/21/14/" + ARTICLE_ID + ".md")).isEmpty();
    }

    @Test
    void wrongDepth_isRejected() {
        assertThat(ArchivePath.parse("article/2026/08/21/" + ARTICLE_ID + ".md")).isEmpty();
        assertThat(ArchivePath.parse("article/2026/08/21/14/15/" + ARTICLE_ID + ".md")).isEmpty();
        assertThat(ArchivePath.parse("article")).isEmpty();
        assertThat(ArchivePath.parse("")).isEmpty();
    }

    @Test
    void nonNumericPartition_isRejected() {
        assertThat(ArchivePath.parse("article/2026/aug/21/14/" + ARTICLE_ID + ".md")).isEmpty();
        assertThat(ArchivePath.parse("article/26/08/21/14/" + ARTICLE_ID + ".md")).isEmpty();
    }

    @Test
    void traversalAttempts_areRejected() {
        // The id shape is what stops a path segment from reaching a query as
        // anything other than hex.
        assertThat(ArchivePath.parse("article/2026/08/21/14/../../../etc/passwd")).isEmpty();
        assertThat(ArchivePath.parse("article/2026/08/21/14/..%2f..%2fpasswd.md")).isEmpty();
        assertThat(ArchivePath.parse("img/2026/08/21/14/*.jpg")).isEmpty();
    }

    @Test
    void nonHexId_isRejected() {
        assertThat(ArchivePath.parse("article/2026/08/21/14/ZZZ7c1f2e4b09d3a5c6e7f80.md"))
                .isEmpty();
        assertThat(ArchivePath.parse("article/2026/08/21/14/68A7C1F2E4B09D3A5C6E7F80.md"))
                .as("upper case is not how either id is written")
                .isEmpty();
    }

    @Test
    void wrongIdLength_isRejected() {
        assertThat(ArchivePath.parse("article/2026/08/21/14/abc.md")).isEmpty();
        assertThat(ArchivePath.parse("img/2026/08/21/14/" + IMAGE_ID + "ff.jpg")).isEmpty();
    }

    @Test
    void folderPartition_isTheSameFormat() {
        // The listing side has to agree with the building side, or a folder
        // lists files that claim to be elsewhere.
        assertThat(ArchivePath.partitionOf(SEEN)).isEqualTo("2026/08/21/14");
        assertThat(ArchivePath.ofArticle(ARTICLE_ID, SEEN))
                .contains(ArchivePath.partitionOf(SEEN));
    }
}
