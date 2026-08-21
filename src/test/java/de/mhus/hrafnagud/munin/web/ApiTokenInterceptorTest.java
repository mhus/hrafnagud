package de.mhus.hrafnagud.munin.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The gate in front of the operator API.
 *
 * <p>Every case here is a way in that should not exist — a token that only
 * shares a prefix, a scheme that is not Bearer, a query parameter standing in
 * for the header. The one case that must keep working is the unconfigured
 * one: an empty token means no check, because that is how every installation
 * of this service has run so far.
 */
class ApiTokenInterceptorTest {

    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @Test
    void unset_token_lets_everything_through() {
        assertThat(new ApiTokenInterceptor("").isSecured()).isFalse();
        assertThat(new ApiTokenInterceptor("").preHandle(request(null), response, null)).isTrue();
        assertThat(new ApiTokenInterceptor("   ").preHandle(request(null), response, null))
                .isTrue();
    }

    @Test
    void correct_token_is_accepted() {
        assertThat(interceptor().preHandle(request("s3cret"), response, null)).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void wrong_token_is_refused_with_401() {
        assertThat(interceptor().preHandle(request("wrong"), response, null)).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("WWW-Authenticate")).isEqualTo("Bearer");
    }

    /** A shared prefix is not a match — this is a comparison, not a startsWith. */
    @Test
    void a_prefix_of_the_token_is_refused() {
        assertThat(interceptor().preHandle(request("s3cre"), response, null)).isFalse();
        assertThat(interceptor().preHandle(request("s3cretplus"), response, null)).isFalse();
    }

    @Test
    void a_missing_header_is_refused() {
        assertThat(interceptor().preHandle(request(null), response, null)).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void another_auth_scheme_is_refused() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/stats");
        request.addHeader("Authorization", "Basic czNjcmV0");

        assertThat(interceptor().preHandle(request, response, null)).isFalse();
    }

    /**
     * Not accepted on purpose: a token in the query string lands in every
     * access log and proxy cache key between the browser and here.
     */
    @Test
    void a_query_parameter_is_not_a_credential() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/stats");
        request.setParameter("token", "s3cret");

        assertThat(interceptor().preHandle(request, response, null)).isFalse();
    }

    /** The refused caller learns nothing about which half was wrong. */
    @Test
    void a_refusal_carries_no_body() throws Exception {
        interceptor().preHandle(request("wrong"), response, null);

        assertThat(response.getContentAsString()).isEmpty();
    }

    private static ApiTokenInterceptor interceptor() {
        return new ApiTokenInterceptor("s3cret");
    }

    private static MockHttpServletRequest request(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/stats");
        if (token != null) {
            request.addHeader("Authorization", "Bearer " + token);
        }
        return request;
    }
}
