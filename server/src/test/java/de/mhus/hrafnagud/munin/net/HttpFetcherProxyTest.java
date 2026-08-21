package de.mhus.hrafnagud.munin.net;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.hrafnagud.config.MuninProperties;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class HttpFetcherProxyTest {

    private static MuninProperties.Proxy proxy(String host, int port) {
        MuninProperties.Proxy proxy = new MuninProperties.Proxy();
        proxy.setHost(host);
        proxy.setPort(port);
        return proxy;
    }

    @Test
    void noHost_meansDirectConnections() {
        assertThat(HttpFetcher.proxySelector(proxy("", 0))).isEmpty();
    }

    @Test
    void blankHost_meansDirectConnections() {
        // An empty value from an unset environment variable placeholder is
        // the normal way "no proxy" arrives, not a misconfiguration.
        assertThat(HttpFetcher.proxySelector(proxy("   ", 8888))).isEmpty();
    }

    @Test
    void hostAndPort_selectThatProxyForEveryRequest() {
        Optional<ProxySelector> selector =
                HttpFetcher.proxySelector(proxy("10.42.10.24", 8888));

        assertThat(selector).isPresent();
        assertThat(selector.get().select(URI.create("https://example.com/feed")))
                .singleElement()
                .satisfies(entry -> assertThat(entry.address())
                        .isEqualTo(new InetSocketAddress("10.42.10.24", 8888)));
    }

    @Test
    void plainHttpTargetsUseTheSameProxy() {
        Optional<ProxySelector> selector =
                HttpFetcher.proxySelector(proxy("10.42.10.24", 8888));

        assertThat(selector.orElseThrow().select(URI.create("http://example.com/feed")))
                .isNotEmpty();
    }

    @Test
    void hostWithoutPort_failsLoudlyInsteadOfGoingDirect() {
        // Falling back to a direct connection is the worst of the three
        // outcomes: in an environment that requires the proxy every fetch
        // would fail, far away from the actual mistake.
        assertThatThrownBy(() -> HttpFetcher.proxySelector(proxy("10.42.10.24", 0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("munin.http.proxy.port");
    }

    @Test
    void portOutOfRange_isRejected() {
        assertThatThrownBy(() -> HttpFetcher.proxySelector(proxy("10.42.10.24", 70000)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void hostIsTrimmed() {
        Optional<ProxySelector> selector =
                HttpFetcher.proxySelector(proxy("  10.42.10.24  ", 8888));

        assertThat(selector.orElseThrow().select(URI.create("https://example.com")))
                .singleElement()
                .satisfies(entry -> assertThat(entry.address())
                        .isEqualTo(new InetSocketAddress("10.42.10.24", 8888)));
    }
}
