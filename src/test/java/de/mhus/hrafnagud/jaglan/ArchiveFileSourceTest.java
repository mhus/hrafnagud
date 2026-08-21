package de.mhus.hrafnagud.jaglan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.hrafnagud.munin.article.ArticleDocument;
import de.mhus.hrafnagud.munin.article.ArticleQuery;
import de.mhus.hrafnagud.munin.article.ArticleService;
import de.mhus.hrafnagud.munin.image.ImageDocument;
import de.mhus.hrafnagud.munin.image.ImageService;
import de.mhus.hrafnagud.munin.image.ImageStatus;
import de.mhus.vance.ode.jaglan.OdeFileAccess;
import de.mhus.vance.ode.jaglan.OdeFileEntry;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The tree, and the two rules the mount contract turns on: a listing is
 * authoritative for its folder, and {@code stat} distinguishes "gone" from
 * "broken".
 */
class ArchiveFileSourceTest {

    private static final Instant SEEN = Instant.parse("2026-08-21T14:37:02Z");
    private static final String ARTICLE_ID = "68a7c1f2e4b09d3a5c6e7f80";
    private static final String IMAGE_ID =
            "276a1ac00ba4f0ea47eeeafca24284f41bc78dc593af1f048615aceba44ab9d9";

    private final ArticleService articles = mock(ArticleService.class);
    private final ImageService images = mock(ImageService.class);
    private ArchiveFileSource source;

    @BeforeEach
    void setUp() {
        source = new ArchiveFileSource(articles, images);
        when(articles.oldestArticleAt()).thenReturn(Optional.of(SEEN));
        when(articles.newestArticleAt()).thenReturn(Optional.of(SEEN));
    }

    private static ArticleDocument article(Instant firstSeenAt) {
        return ArticleDocument.builder()
                .id(ARTICLE_ID)
                .title("Rat beschliesst Plan")
                .url("https://example.com/story")
                .firstSeenAt(firstSeenAt)
                .lastSourceAddedAt(firstSeenAt)
                .build();
    }

    private static List<String> paths(List<OdeFileEntry> entries) {
        return entries.stream().map(OdeFileEntry::path).toList();
    }

    // ─── Capabilities ───

    @Test
    void theMountIsReadOnlyAndSearchable() {
        when(articles.countAll()).thenReturn(83_646L);

        var capabilities = source.capabilities();

        assertThat(capabilities.access()).isEqualTo(OdeFileAccess.READ_ONLY);
        assertThat(capabilities.canSearch()).isTrue();
        assertThat(capabilities.metadataTtl()).isPositive();
    }

    @Test
    void theItemCount_coversBothSubtrees() {
        // Articles alone understate the tree by every image copy in it.
        when(articles.countAll()).thenReturn(83_646L);
        when(images.countStored()).thenReturn(12_400L);

        assertThat(source.capabilities().itemCount()).isEqualTo(96_046L);
    }

    @Test
    void writingAndDeleting_areRefused() {
        // The declaration says read-only, so these are only reached if
        // declaration and implementation disagree. They must still refuse.
        assertThatThrownBy(() -> source.write("article/x", null))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> source.delete("article/x"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ─── The tree ───

    @Test
    void root_holdsTheTwoSubtrees() {
        assertThat(paths(source.list(""))).containsExactly("article", "img");
    }

    @Test
    void years_areClampedToWhatTheArchiveHolds() {
        // Not the publication range: feed windows reach back years, and a
        // fresh archive would otherwise show sixteen empty year folders.
        assertThat(paths(source.list("article"))).containsExactly("article/2026");
    }

    @Test
    void months_areClampedWithinTheBoundaryYear() {
        when(articles.oldestArticleAt()).thenReturn(Optional.of(Instant.parse("2026-06-01T00:00:00Z")));

        assertThat(paths(source.list("article/2026")))
                .containsExactly("article/2026/06", "article/2026/07", "article/2026/08");
    }

    @Test
    void days_followTheLengthOfTheMonth() {
        assertThat(source.list("article/2026/02")).hasSize(28);
        assertThat(source.list("article/2026/08")).hasSize(31);
    }

    @Test
    void hoursAndMinutes_areFixedAndComplete() {
        assertThat(source.list("article/2026/08/21")).hasSize(24);
        assertThat(source.list("article/2026/08/21/14")).hasSize(60);
    }

    @Test
    void emptyArchive_listsNoFolders() {
        when(articles.oldestArticleAt()).thenReturn(Optional.empty());
        when(articles.newestArticleAt()).thenReturn(Optional.empty());

        assertThat(source.list("article")).isEmpty();
    }

    @Test
    void theLeaf_listsTheArticlesOfThatMinute() {
        when(articles.count(any(ArticleQuery.class))).thenReturn(1L);
        when(articles.search(any(ArticleQuery.class), anyInt(), anyInt()))
                .thenReturn(List.of(article(SEEN)));

        assertThat(paths(source.list("article/2026/08/21/14/37")))
                .containsExactly("article/2026/08/21/14/37/" + ARTICLE_ID + ".md");
    }

    @Test
    void anUnknownSubtree_listsNothing() {
        assertThat(source.list("audio/2026")).isEmpty();
        assertThat(source.list("article/2026/aug")).isEmpty();
    }

    @Test
    void aFilePath_isNotAFolder() {
        assertThat(source.list("article/2026/08/21/14/37/" + ARTICLE_ID + ".md")).isEmpty();
    }

    @Test
    void animpossibleDate_listsNothing() {
        // 31 February parses as numbers and is not a date.
        assertThat(source.list("article/2026/02/31/14/37")).isEmpty();
    }

    @Test
    void aLevelOutsideItsRange_listsNothingInsteadOfThrowing() {
        // All numeric, none of them a date level — and a caller types these.
        // Uncaught, they were a DateTimeException and a NumberFormatException,
        // which the contract reads as "this source is broken" rather than
        // "there is no such folder".
        assertThat(source.list("article/2026/13")).isEmpty();
        assertThat(source.list("article/2026/00")).isEmpty();
        assertThat(source.list("article/2026/08/32")).isEmpty();
        assertThat(source.list("article/2026/08/21/24")).isEmpty();
        assertThat(source.list("article/2026/08/21/14/60")).isEmpty();
        assertThat(source.list("article/99999999999")).isEmpty();
    }

    @Test
    void aLevelSpelledUncanonically_isNotAFolder() {
        // Otherwise article/2026/8 is a second working address for the folder
        // article/2026/08 already names, and stable paths are the one promise
        // the mount contract rests on.
        assertThat(source.list("article/2026/8")).isEmpty();
        assertThat(source.list("article/026/08")).isEmpty();
        assertThat(source.stat("article/2026/8")).isEmpty();
        assertThat(source.stat("article/2026/08")).isPresent();
    }

    @Test
    void theLeaf_readsEveryBodyInOneQuery() {
        // Not tidiness: the busiest minute so far holds 2,009 articles, and one
        // query per entry is 2,009 round trips for a listing's metadata.
        when(articles.count(any(ArticleQuery.class))).thenReturn(2L);
        when(articles.search(any(ArticleQuery.class), anyInt(), anyInt()))
                .thenReturn(List.of(article(SEEN), article(SEEN)));

        source.list("article/2026/08/21/14/37");

        verify(articles).findContent(anyCollection());
        verify(articles, never()).findContent(anyString());
    }

    @Test
    void imageLeaf_asksTheImageService() {
        when(images.storedBetween(any(), any())).thenReturn(List.of(ImageDocument.builder()
                .id(IMAGE_ID).status(ImageStatus.STORED).mime("image/jpeg")
                .size(94_000).firstSeenAt(SEEN).contentHash("abc").build()));

        assertThat(paths(source.list("img/2026/08/21/14/37")))
                .containsExactly("img/2026/08/21/14/37/" + IMAGE_ID + ".jpg");
        verify(articles, never()).search(any(), anyInt(), anyInt());
    }

    // ─── stat: gone versus broken ───

    @Test
    void statOfAFolder_needsNoQuery() {
        assertThat(source.stat("article/2026/08")).isPresent();
        assertThat(source.stat("")).isPresent();
        verify(articles, never()).findById(any());
    }

    @Test
    void statOfAKnownArticle_describesTheRendering() {
        when(articles.findById(ARTICLE_ID)).thenReturn(Optional.of(article(SEEN)));

        OdeFileEntry entry = source.stat("article/2026/08/21/14/37/" + ARTICLE_ID + ".md")
                .orElseThrow();

        assertThat(entry.folder()).isFalse();
        assertThat(entry.mimeType()).isEqualTo("text/markdown");
        assertThat(entry.size()).isPositive();
        assertThat(entry.etag()).isNotBlank();
        assertThat(entry.title()).isEqualTo("Rat beschliesst Plan");
    }

    @Test
    void statOfAnUnknownArticle_isEmptyNotAnError() {
        // Empty is an answer the reader acts on — it forgets the file. An
        // exception would be the other message entirely.
        when(articles.findById(ARTICLE_ID)).thenReturn(Optional.empty());

        assertThat(source.stat("article/2026/08/21/14/37/" + ARTICLE_ID + ".md")).isEmpty();
    }

    @Test
    void aPathClaimingTheWrongMinute_isNotThatFile() {
        // Otherwise one article answers under every date, and the reader
        // creates a document per spelling.
        when(articles.findById(ARTICLE_ID)).thenReturn(Optional.of(article(SEEN)));

        assertThat(source.stat("article/2026/08/21/14/38/" + ARTICLE_ID + ".md")).isEmpty();
    }

    @Test
    void anImageThatIsNotStoredYet_isNotAFile() {
        when(images.statById(IMAGE_ID)).thenReturn(Optional.of(ImageDocument.builder()
                .id(IMAGE_ID).status(ImageStatus.PENDING).firstSeenAt(SEEN).build()));

        assertThat(source.stat("img/2026/08/21/14/37/" + IMAGE_ID + ".jpg")).isEmpty();
    }

    // ─── open ───

    @Test
    void openingAnArticle_yieldsItsMarkdown() throws Exception {
        when(articles.findById(ARTICLE_ID)).thenReturn(Optional.of(article(SEEN)));
        when(articles.findContent(ARTICLE_ID)).thenReturn(Optional.empty());

        String content;
        try (var stream = source.open("article/2026/08/21/14/37/" + ARTICLE_ID + ".md")) {
            content = new String(stream.readAllBytes());
        }

        assertThat(content).startsWith("---\n").contains("# Rat beschliesst Plan");
    }

    @Test
    void openingAMalformedPath_throws() {
        assertThatThrownBy(() -> source.open("article/nonsense"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void openingAnUnknownArticle_throws() {
        // The endpoint turns this into a 404 after stat, so the common case
        // never reaches here — but it must not return an empty file.
        when(articles.findById(ARTICLE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> source.open("article/2026/08/21/14/37/" + ARTICLE_ID + ".md"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ─── search ───

    @Test
    void search_ordersByRelevanceNotByDate() {
        // One page, no cursor: a result sorted by collection date puts the best
        // match wherever it happens to fall, and the caller cannot page past it.
        when(articles.searchByRelevance(any(ArticleQuery.class), any(), anyInt(), anyBoolean()))
                .thenReturn(List.of(article(SEEN)));

        assertThat(paths(source.search("wall street", 5)))
                .containsExactly("article/2026/08/21/14/37/" + ARTICLE_ID + ".md");
        verify(articles, never()).search(any(), anyInt(), anyInt());
    }

    @Test
    void search_alsoLooksInsideBodies() {
        // A mount holds the whole article, so a phrase in the fifth paragraph
        // is inside the file the caller is after.
        when(articles.searchByRelevance(any(ArticleQuery.class), any(), anyInt(), anyBoolean()))
                .thenReturn(List.of());

        source.search("wall street", 5);

        verify(articles).searchByRelevance(any(ArticleQuery.class), isNull(), eq(5), eq(true));
    }

    @Test
    void blankSearch_asksNothing() {
        assertThat(source.search("  ", 5)).isEmpty();
        verify(articles, never()).searchByRelevance(any(), any(), anyInt(), anyBoolean());
    }
}
