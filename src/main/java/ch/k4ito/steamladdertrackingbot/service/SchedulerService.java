package ch.k4ito.steamladdertrackingbot.service;

import ch.k4ito.steamladdertrackingbot.configuration.DiscordBotConfiguration;
import discord4j.common.util.Snowflake;
import discord4j.core.GatewayDiscordClient;
import discord4j.core.object.entity.User;
import discord4j.core.object.entity.channel.TextChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

@Service
public class SchedulerService {
    private static final Logger LOGGER = LoggerFactory.getLogger(SchedulerService.class);

    private static final Pattern PATTERN_REGEX = Pattern.compile(
            "<div class=\"lbentry\">(?s)(?:(?!<div class=\"lbentry\">).)*?" +
                    "<div class=\"rR\">#(\\d+)</div>(?:(?!<div class=\"lbentry\">).)*?" +
                    "<a class=\"playerName\" href=\"[^\"]*\">Cone</a>.*?" +
                    "<div class=\"score\">([\\d,]+)"
    );
    private static final Pattern LAST_MESSAGE_PATTERN = Pattern.compile("Ranking: (\\d+) \\| Points: ([\\d,]+)");
    private static final String URL = "https://steamcommunity.com/stats/2217000/leaderboards/14800950?sr=";


    private final GatewayDiscordClient discordClient;
    private final DiscordBotConfiguration configuration;
    private final WebClient webClient;
    private final int indexToJump = 15;
    
    
    private LeaderBoardData lastResult = null;

    public SchedulerService(final GatewayDiscordClient discordClient, final DiscordBotConfiguration configuration) {
        this.discordClient = discordClient;
        this.configuration = configuration;
        this.webClient = WebClient.create();

        // init lastResult if available
        if (lastResult == null) {
            var result = getTextChannel().
                    flatMap(channel -> channel.getMessagesBefore(Snowflake.of(Instant.now()))
                            .filter(message -> message.getAuthor().map(User::isBot).orElse(false))
                            .next())
                    .flatMap(lastResult -> {
                        var lastMatcher = LAST_MESSAGE_PATTERN.matcher(lastResult.getContent());
                        if (lastMatcher.find()) {
                            String ranking = lastMatcher.group(1);
                            String points = lastMatcher.group(2);
                            return Mono.just(new LeaderBoardData(ranking, points));
                        }
                        return Mono.empty();
                    });
            lastResult = result.block();
        }

    }

    @Scheduled(fixedRate = 15, timeUnit = TimeUnit.SECONDS)
    private void checkAndSendMessageIfRankChange() {
        AtomicInteger startIndex = new AtomicInteger(1);

        Mono.defer(() -> fetchAndProcessLeaderboard(startIndex))
                .repeat(() -> startIndex.get() < 100) // Limit to prevent infinite loop
                .takeUntil(Objects::nonNull)
                .last()
                .flatMap(result -> {
                    if (result == null) {
                        return Mono.error(new RuntimeException("No match found after multiple attempts"));
                    }
                    return Mono.just(result);
                })
                .doOnNext(this::processLeaderboardUpdate)
                .subscribe(null, error -> LOGGER.error("Error processing leaderboard: ", error));
    }

    private Mono<LeaderBoardData> fetchAndProcessLeaderboard(AtomicInteger startIndex) {
        return webClient.get().uri(URL + startIndex.get())
                .retrieve().bodyToMono(String.class)
                .flatMap(this::extractLeaderboardData)
                .doOnNext(_ -> LOGGER.info("Match found at index: {}", startIndex.get()))
                .switchIfEmpty(Mono.fromRunnable(() -> LOGGER.info("No match found, increasing index to {}", startIndex.addAndGet(indexToJump))));
    }

    private Mono<LeaderBoardData> extractLeaderboardData(String response) {
        var matcher = PATTERN_REGEX.matcher(response);
        if (matcher.find()) {
            String ranking = matcher.group(1);
            String points = matcher.group(2);
            return Mono.just(new LeaderBoardData(ranking, points));
        }
        return Mono.empty();
    }

    private void processLeaderboardUpdate(LeaderBoardData currentResult) {
        if (!currentResult.equals(lastResult)) {
            String message = generateHumorousMessage(lastResult, currentResult);
            if (message != null) {
                lastResult = currentResult;
                getTextChannel()
                        .flatMap(channel -> channel.createMessage(message))
                        .subscribe();
            } else {
                LOGGER.info("No change in leaderboard, skipping message");
            }
        } else {
            LOGGER.info("No change in leaderboard, skipping message");
        }

    }

    private Mono<TextChannel> getTextChannel() {
        return getDiscordClient().getChannelById(Snowflake.of(getConfiguration().getChannelId()))
                .ofType(TextChannel.class);
    }

    private String generateHumorousMessage(LeaderBoardData lastResult, LeaderBoardData currentResult) {
        if (lastResult != null &&
                lastResult.ranking().equals(currentResult.ranking()) &&
                lastResult.points().equals(currentResult.points())) {
            return null; // No changes, don't send a message
        }
        StringBuilder message = new StringBuilder("🎮 Cone's Leaderboard Update! 🎮\n\n");

        if (lastResult == null) {
            message.append("Cone has magically appeared on the leaderboard! ");
            message.append("Ranking: ").append(currentResult.ranking()).append(" | ");
            message.append("Points: ").append(currentResult.points()).append("\n");
            message.append("Did someone feed Cone after midnight? 🌙🍔");
            return message.toString();
        }

        int rankChange = Integer.parseInt(lastResult.ranking()) - Integer.parseInt(currentResult.ranking());
        int pointChange = Integer.parseInt(currentResult.points().replace(",", ""))
                - Integer.parseInt(lastResult.points().replace(",", ""));

        message.append("Cone is on the move! ");

        if (rankChange > 0) {
            message.append("Climbing ").append(rankChange).append(" spots like a caffeinated squirrel! 🐿️☕\n");
        } else if (rankChange < 0) {
            message.append("Slipped ").append(-rankChange).append(" spots. Did someone oil the leaderboard? 🛢️😅\n");
        } else {
            message.append("Holding steady. Cone's got a death grip on that spot! 💪\n");
        }

        message.append("New Ranking: ").append(currentResult.ranking()).append(" | ");
        message.append("Points: ").append(currentResult.points()).append("\n");

        if (pointChange > 0) {
            message.append("Gained ").append(pointChange).append(" points! ");
            message.append("Is Cone secretly a points-eating monster? 👾🍴\n");
        } else if (pointChange < 0) {
            message.append("Lost ").append(-pointChange).append(" points. ");
            message.append("Did Cone trade them for magic beans? 🌱🤔\n");
        } else {
            message.append("Points unchanged. Cone's playing it cooler than a penguin's picnic! 🐧❄️\n");
        }

        LOGGER.info("Sending message: {}", message);

        return message.toString();
    }

    public GatewayDiscordClient getDiscordClient() {
        return discordClient;
    }

    public DiscordBotConfiguration getConfiguration() {
        return configuration;
    }

    record LeaderBoardData(String ranking, String points) {
        @Override
        public String toString() {
            return "ranking=" + ranking + ", points=" + points;
        }
    }
}
