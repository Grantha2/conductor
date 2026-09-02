package conductor.agents;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Scripted HttpClient for exercising send()/retry paths without a network:
 * answers each request with the next queued status+body (or IOException) and
 * records every request it saw. Running out of script throws, so a test that
 * makes more calls than it expected fails loudly.
 */
final class StubHttpClient extends HttpClient {

    private record Reply(int status, String body, Map<String, String> headers, IOException failure) {}

    final List<HttpRequest> requests = new ArrayList<>();
    private final Deque<Reply> script = new ArrayDeque<>();

    StubHttpClient reply(int status, String body) { return reply(status, body, Map.of()); }

    StubHttpClient reply(int status, String body, Map<String, String> headers) {
        script.add(new Reply(status, body, headers, null));
        return this;
    }

    StubHttpClient fail(String message) {
        script.add(new Reply(0, null, Map.of(), new IOException(message)));
        return this;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) throws IOException {
        requests.add(request);
        Reply next = script.poll();
        if (next == null) throw new IllegalStateException("StubHttpClient: no scripted reply for request #" + requests.size());
        if (next.failure() != null) throw next.failure();
        return (HttpResponse<T>) new Response(request, next);
    }

    private record Response(HttpRequest request, Reply reply) implements HttpResponse<String> {
        @Override public int statusCode() { return reply.status(); }
        @Override public Optional<HttpResponse<String>> previousResponse() { return Optional.empty(); }
        @Override public HttpHeaders headers() {
            Map<String, List<String>> h = new HashMap<>();
            reply.headers().forEach((k, v) -> h.put(k, List.of(v)));
            return HttpHeaders.of(h, (a, b) -> true);
        }
        @Override public String body() { return reply.body(); }
        @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
        @Override public URI uri() { return request.uri(); }
        @Override public Version version() { return Version.HTTP_1_1; }
    }

    // The rest of HttpClient's abstract surface; nothing under test touches it.
    @Override public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }
    @Override public Optional<Duration> connectTimeout() { return Optional.empty(); }
    @Override public Redirect followRedirects() { return Redirect.NEVER; }
    @Override public Optional<ProxySelector> proxy() { return Optional.empty(); }
    @Override public SSLContext sslContext() { return null; }
    @Override public SSLParameters sslParameters() { return new SSLParameters(); }
    @Override public Optional<Authenticator> authenticator() { return Optional.empty(); }
    @Override public Version version() { return Version.HTTP_1_1; }
    @Override public Optional<Executor> executor() { return Optional.empty(); }
    @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r, HttpResponse.BodyHandler<T> h) {
        throw new UnsupportedOperationException();
    }
    @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r, HttpResponse.BodyHandler<T> h,
                                                                       HttpResponse.PushPromiseHandler<T> p) {
        throw new UnsupportedOperationException();
    }
}
