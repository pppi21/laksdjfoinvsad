package org.nodriver4j.services;

import java.util.function.Supplier;

public class AutoSolveAISolverService {

    private static final String LOCAL_SOLVE_URL = "http://localhost:8080/api/v1/solve";
    private static final String SOLVE_URL = "https://autosolve-ai-api.aycd.io/api/v1/solve";

    private final CoreHttpClient _httpClient;
    private final Supplier<String> _aiKeySupplier;

    public AutoSolveAISolverService(Supplier<String> aiKeySupplier) {
        _httpClient = CoreOkHttpClient.ofIsolated(CoreHttpClientOptions.defaultOptions()
                .withMaxConcurrentRequests(64)
                .withMaxConcurrentRequestsPerHost(64));
        _aiKeySupplier = aiKeySupplier;
    }

    /**
     * Sends a solve request to the AutoSolve AI service.
     *
     * @param captchaType   The type of captcha to solve.
     * @param description   The description of the captcha challenge.
     * @param exampleImages Example images for the captcha challenge. Base64 encoded strings. No data url prefix.
     * @param captchaImages The images to solve. Base64 encoded strings. No data url prefix.
     * @param test          If true, sends the request to the local test server.
     * @return The response from the AutoSolve AI service.
     */
    public AutoSolveAISolverResponse solve(AutoSolveAISolverCaptchaType captchaType,
                                           String description,
                                           List<String> exampleImages,
                                           List<String> captchaImages,
                                           boolean test) throws AutoSolveAISolverException {
        Solver solver = new Solver(captchaType,
                description,
                exampleImages,
                captchaImages,
                test);
        return solver.solve();
    }

    @With
    private record SolveRequestBody(
            @SerializedName("taskId") String taskId,
            @SerializedName("version") Integer version,
            @SerializedName("description") String description,
            @SerializedName("exampleImages") String[] exampleImages,
            @SerializedName("imageData") String[] imageData) {
    }

    private class Solver {

        private final String _id;
        private final int _version;
        private final String _description;
        private final List<String> _exampleImages;
        private final List<String> _captchaImages;
        private final boolean _test;
        private int _attempts = 0;

        private Solver(AutoSolveAISolverCaptchaType captchaType,
                       String description,
                       List<String> exampleImages,
                       List<String> captchaImages,
                       boolean test) {
            _id = UUID.randomUUID().toString();
            _version = captchaType.version();
            _description = description;
            _captchaImages = captchaImages;
            _exampleImages = exampleImages;
            _test = test;
        }

        public AutoSolveAISolverResponse solve() throws AutoSolveAISolverException {
            int attempts = _attempts++;
            SolveRequestBody solveRequestBody = new SolveRequestBody(
                    _id,
                    _version,
                    _description,
                    _exampleImages.toArray(new String[0]),
                    _captchaImages.toArray(new String[0]));
            String accessToken = _aiKeySupplier.get();
            CoreHttpRequest httpRequest = CoreHttpRequest.ofPost(_test ?
                                    LOCAL_SOLVE_URL :
                                    SOLVE_URL,
                            CoreHttpContentBody.ofJson(solveRequestBody))
                    .withHeaders(
                            "Authorization", "Token " + accessToken
                    );
            try {
                CoreHttpResponse httpResponse = _httpClient.call(httpRequest);
                if (httpResponse.successfulAndNotEmptyBody()) {
                    long responseTime = httpResponse.duration().toMillis();
                    LOG.info("Received response: {} in {}ms.", httpResponse.body(), responseTime);
                    return (AutoSolveAISolverResponse) httpResponse.body()
                            .json(AutoSolveAISolverResponse.class)
                            .orElseThrow(() -> new AutoSolveAISolverException("Failed to parse response: " + httpResponse.body()));
                } else {
                    throw new AutoSolveAISolverException("Failed to send request: " + httpResponse);
                }
            } catch (InterruptedException e) {
                return JxThreads.interrupt(e);
            } catch (IOException e) {
                if (e instanceof UnknownHostException) {
                    if (attempts < 3) {
                        LOG.warn("Failed to send request due to UnknownHostException. Retrying with request={}.",
                                solveRequestBody);
                        SneakyConcurrency.threadSleep(1, TimeUnit.SECONDS);
                        return solve();
                    }
                }
                throw new AutoSolveAISolverException("Failed to send request: " + solveRequestBody, e);
            }
        }
    }

    /*
    Response fields:

    @SerializedName("id")
    private final String id;

    @SerializedName("squares")
    private final boolean[][] squares;

    @SerializedName("values")
    private final float[] values;

    @SerializedName("message")
    private final String message;

    @SerializedName("success")
    private final boolean success;

    @SerializedName("remaining")
    private final long remaining;
     */
}