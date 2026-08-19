package de.mhus.hrafnagud.munin.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.hrafnagud.api.source.SourceListType;
import de.mhus.hrafnagud.munin.net.HttpFetchResult;
import de.mhus.hrafnagud.munin.net.HttpFetcher;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

/**
 * The reader for collections published without a standard.
 *
 * <p>What matters here is that it stays <b>generic</b>: nothing in it knows
 * about {@code awesome-rss-feeds}, so the tests describe a repository shape
 * rather than that repository.
 */
class GithubOpmlReaderTest {

    private static final String LISTING = """
            [
              {"name":"Germany.opml","path":"countries/Germany.opml","type":"file",
               "download_url":"https://raw.githubusercontent.com/o/r/master/countries/Germany.opml"},
              {"name":"France.opml","path":"countries/France.opml","type":"file",
               "download_url":"https://raw.githubusercontent.com/o/r/master/countries/France.opml"},
              {"name":"README.md","path":"countries/README.md","type":"file",
               "download_url":"https://raw.githubusercontent.com/o/r/master/countries/README.md"},
              {"name":"nested","path":"countries/nested","type":"dir","download_url":null}
            ]""";

    private HttpFetcher fetcher;
    private GithubOpmlReader reader;

    @BeforeEach
    void setUp() {
        fetcher = mock(HttpFetcher.class);
        reader = new GithubOpmlReader(fetcher, new ObjectMapper());
    }

    @Test
    void opml_files_become_entries_keyed_by_their_path() {
        serve(LISTING);

        CatalogReadResult result = reader.read(catalog("countries"));

        assertThat(result.entries()).extracting(CatalogEntry::key)
                .containsExactly("countries/Germany.opml", "countries/France.opml");
        assertThat(result.entries()).extracting(CatalogEntry::title)
                .containsExactly("Germany", "France");
    }

    /** Anything that is not a list is not ours: a README is not a subscription list. */
    @Test
    void other_files_and_directories_are_ignored() {
        serve(LISTING);

        assertThat(reader.read(catalog("countries")).entries()).hasSize(2);
    }

    @Test
    void a_txt_file_becomes_a_text_list() {
        serve("""
                [{"name":"feeds.txt","path":"x/feeds.txt","type":"file",
                  "download_url":"https://raw.githubusercontent.com/o/r/master/x/feeds.txt"}]""");

        assertThat(reader.read(catalog("x")).entries())
                .singleElement()
                .extracting(CatalogEntry::type)
                .isEqualTo(SourceListType.TEXT);
    }

    @Test
    void each_configured_path_is_one_api_call() {
        serve("[]");

        reader.read(catalog("countries/with_category,recommended/with_category"));

        ArgumentCaptor<String> urls = ArgumentCaptor.forClass(String.class);
        verify(fetcher, times(2)).get(urls.capture());
        assertThat(urls.getAllValues()).containsExactly(
                "https://api.github.com/repos/o/r/contents/countries/with_category",
                "https://api.github.com/repos/o/r/contents/recommended/with_category");
    }

    @Test
    void a_ref_is_passed_through() {
        serve("[]");
        SourceCatalogDocument catalog = catalog("countries");
        catalog.getParams().put(GithubOpmlReader.PARAM_REF, "master");

        reader.read(catalog);

        verify(fetcher).get("https://api.github.com/repos/o/r/contents/countries?ref=master");
    }

    /** A directory name with a space has to survive the URL, not break it. */
    @Test
    void path_segments_are_encoded() {
        serve("[]");

        reader.read(catalog("with category"));

        verify(fetcher).get("https://api.github.com/repos/o/r/contents/with%20category");
    }

    @Test
    void no_path_reads_the_repository_root() {
        serve("[]");

        reader.read(catalog(""));

        verify(fetcher).get("https://api.github.com/repos/o/r/contents/");
    }

    @Test
    void a_url_that_is_not_github_is_refused_before_any_request() {
        SourceCatalogDocument catalog = catalog("x");
        catalog.setUrl("https://gitlab.com/o/r");

        assertThatThrownBy(() -> reader.read(catalog))
                .isInstanceOf(CatalogReadException.class)
                .hasMessageContaining("github.com");
    }

    @Test
    void a_url_without_a_repository_is_refused() {
        SourceCatalogDocument catalog = catalog("x");
        catalog.setUrl("https://github.com/plenaryapp");

        assertThatThrownBy(() -> reader.read(catalog))
                .isInstanceOf(CatalogReadException.class)
                .hasMessageContaining("<owner>/<repo>");
    }

    /** The rate limit is the failure people will actually hit, so it is named. */
    @Test
    void a_403_says_what_it_probably_is() {
        when(fetcher.get(anyString())).thenReturn(HttpFetchResult.builder()
                .status(403).error("Forbidden").build());

        assertThatThrownBy(() -> reader.read(catalog("countries")))
                .isInstanceOf(CatalogReadException.class)
                .hasMessageContaining("rate limit");
    }

    @Test
    void a_file_where_a_directory_was_expected_is_a_read_failure() {
        serve("{\"name\":\"Germany.opml\",\"type\":\"file\"}");

        assertThatThrownBy(() -> reader.read(catalog("countries/Germany.opml")))
                .isInstanceOf(CatalogReadException.class)
                .hasMessageContaining("directory listing");
    }

    private void serve(String json) {
        when(fetcher.get(anyString())).thenReturn(HttpFetchResult.builder()
                .status(200)
                .body(json.getBytes(StandardCharsets.UTF_8))
                .build());
    }

    private static SourceCatalogDocument catalog(String paths) {
        SourceCatalogDocument catalog = SourceCatalogDocument.builder()
                .name("repo")
                .type(GithubOpmlReader.TYPE)
                .url("https://github.com/o/r")
                .build();
        catalog.setParams(new java.util.LinkedHashMap<>(
                Map.of(GithubOpmlReader.PARAM_PATHS, paths)));
        return catalog;
    }
}
