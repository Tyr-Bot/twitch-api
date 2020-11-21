package com.github.tyrbot.twitchapi;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.github.tyrbot.twitchdatamodels.api.helix.streams.GetStreamsResponse;
import com.github.tyrbot.twitchdatamodels.api.helix.users.GetUsersFollowsResponse;
import com.github.tyrbot.twitchdatamodels.api.helix.users.GetUsersResponse;
import com.google.common.io.Resources;
import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

public class TwitchApi {

    public static final String VERSION;

    static {
        String versionBuffer;
        try {
            List<String> gradleConfigLines = Resources.readLines(Resources.getResource("gradleConfig"),
                    StandardCharsets.UTF_8);
            Map<String, String> configStore = new HashMap<>();
            gradleConfigLines.forEach(line -> {
                String[] values = line.split("=");
                configStore.put(values[0], values[1]);
            });

            versionBuffer = configStore.get("version");
        } catch (IOException ex) {
            ex.printStackTrace();
            versionBuffer = "UNKNOWN";
        }

        VERSION = versionBuffer;
    }

    private static final int RATELIMIT_POINTS_MAX = 800;
    private static final long RATELIMIT_PERIOD_LENGTH = 60000L;
    private static final int RATELIMIT_CHECK_INTERVAL = 10;

    private final String clientId;
    private final String authToken;

    private long ratelimitPeriodStart = System.currentTimeMillis();
    private int usedRatelimitPoints;
    private Gson gson;

    public TwitchApi(String clientId, String authToken) {
        this.clientId = clientId;
        this.authToken = authToken;
        gson = new GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES).create();
    }

    public GetStreamsResponse getStreams(String... userLogins) {
        String response = makeApiGetRequest(new StringBuilder("streams?").append(Arrays.stream(userLogins)
                .map(name -> new StringBuilder("user_login=").append(name)).collect(Collectors.joining("&")))
                .toString(), 1);

        return gson.fromJson(response, GetStreamsResponse.class);
    }

    public GetUsersResponse getUsers(String... userLogins) {
        String response = makeApiGetRequest(new StringBuilder("users?").append(Arrays.stream(userLogins)
                .map(name -> new StringBuilder("login=").append(name)).collect(Collectors.joining("&"))).toString(), 1);

        return gson.fromJson(response, GetUsersResponse.class);
    }

    public GetUsersFollowsResponse getFollowersFrom(String userId) {
        String response = makeApiGetRequest(new StringBuilder("users/follows?from_id=").append(userId).toString(), 1);

        return gson.fromJson(response, GetUsersFollowsResponse.class);
    }

    public GetUsersFollowsResponse getFollowersTo(String userId) {
        String response = makeApiGetRequest(new StringBuilder("users/follows?to_id=").append(userId).toString(), 1);

        return gson.fromJson(response, GetUsersFollowsResponse.class);
    }

    public GetUsersFollowsResponse getFollowRelationship(String fromId, String toId) {
        String response = makeApiGetRequest(new StringBuilder("users/follows?from_id=").append(fromId).append("&to_id=").append(toId).toString(), 1);

        return gson.fromJson(response, GetUsersFollowsResponse.class);
    }

    private String makeApiGetRequest(String endpoint, int ratelimitCost) {
        if (usedRatelimitPoints + ratelimitCost >= RATELIMIT_POINTS_MAX) {
            System.err.println("Twitch helix web api ratelimit reached!");
        }

        while (usedRatelimitPoints + ratelimitCost >= RATELIMIT_POINTS_MAX) {
            try {
                long currentTime = System.currentTimeMillis();
                if (currentTime > ratelimitPeriodStart + RATELIMIT_PERIOD_LENGTH) {
                    ratelimitPeriodStart = currentTime;
                    usedRatelimitPoints = 0;
                    break;
                }

                Thread.sleep(RATELIMIT_CHECK_INTERVAL);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }

        String url = new StringBuilder("https://api.twitch.tv/helix/").append(endpoint).toString();
        try {
            HttpRequest request = HttpRequest.newBuilder().uri(new URI(url))
                    .headers("Client-ID", clientId, "Authorization", String.format("Bearer %s", authToken)).GET()
                    .build();

            HttpClient httpClient = HttpClient.newBuilder().build();
            HttpResponse<String> response = httpClient.send(request, BodyHandlers.ofString());

            usedRatelimitPoints += ratelimitCost;

            if (response.statusCode() != 200) {
                System.err.println("Error on twitch api fetch! Status: " + response.statusCode() + "\nBody: "
                        + response.body() + "\nURL: " + endpoint);
                return "";
            }

            return response.body();

        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return "";
        } catch (URISyntaxException | IOException ex) {
            ex.printStackTrace();
            return "";
        }
    }

}