package conductor.agents;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The one HTTP path every provider client uses: POST JSON, retry transient
 * failures with backoff, and scrub API keys out of anything we might show
 * to a user. Kept separate so retry policy never diverges between providers.
 */
final class Http {

    private static final Duration TIMEOUT = Duration.ofMinutes(5);
    private static final int MAX_RETRIES = 3;
    private static final long MAX_RETRY_AFTER_MS = 60_000;
    private static final Pattern KEY = Pattern.compile("(sk-|AIza)[A-Za-z0-9_-]{8,}");

    private Http() {}

    /**
     * Retries IOExceptions and 408/409/429/5xx (incl. 529) up to
     * {@value #MAX_RETRIES} times with 1s, 2s, 4s backoff, preferring the
     * server's {@code retry-after} (whole seconds, capped at 60s; the HTTP-date
     * form is ignored) when it sends one. Other 4xx are returned immediately:
     * they will not fix themselves. Worst case one call blocks for
     * (MAX_RETRIES + 1) x TIMEOUT + MAX_RETRIES x 60s, i.e. about 23 minutes.
     */
    static HttpResponse<String> postJson(HttpClient http, URI uri,
                                         Map<String, String> headers, String body) throws IOException {
        var builder = HttpRequest.newBuilder(uri)
                .timeout(TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(body));
        try {
            headers.forEach(builder::header);
        } catch (IllegalArgumentException e) {   // the JDK's message quotes the offending value, i.e. the key
            throw new IOException("A configured API key or token contains a character that is not allowed in an HTTP header");
        }
        builder.setHeader("content-type", "application/json");
        var request = builder.build();

        for (int attempt = 0; ; attempt++) {
            HttpResponse<String> response = null;
            try {
                response = http.send(request, HttpResponse.BodyHandlers.ofString());
                if (!retryable(response.statusCode()) || attempt == MAX_RETRIES) return response;
            } catch (IOException e) {
                if (attempt == MAX_RETRIES) throw e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while calling " + uri.getHost(), e);
            }
            sleep(backoffMillis(attempt, response));
        }
    }

    /** Masks Anthropic/OpenAI ({@code sk-...}) and Google ({@code AIza...}) keys. */
    static String redactKeys(String text) {
        return redactKeys(text, null);
    }

    /** As above, plus every occurrence of the exact configured {@code secret}: covers OpenClaw bearer tokens and any key format the pattern misses. */
    static String redactKeys(String text, String secret) {
        if (text == null) return "";
        if (secret != null && !secret.isBlank()) text = text.replace(secret, "[redacted]");
        return KEY.matcher(text).replaceAll("$1[redacted]");
    }

    private static boolean retryable(int status) {
        return status == 408 || status == 409 || status == 429 || status >= 500;
    }

    private static long backoffMillis(int attempt, HttpResponse<String> response) {
        if (response != null) {
            var retryAfter = response.headers().firstValue("retry-after");
            if (retryAfter.isPresent()) {
                try {
                    long seconds = Long.parseLong(retryAfter.get().trim());
                    return Math.min(Math.max(seconds, 0) * 1000, MAX_RETRY_AFTER_MS);
                } catch (NumberFormatException ignored) {
                    // HTTP-date form; fall through to exponential backoff.
                }
            }
        }
        return 1000L << attempt;
    }

    private static void sleep(long millis) throws IOException {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted during retry backoff", e);
        }
    }
}
