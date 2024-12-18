package ch.k4ito.steamladdertrackingbot.configuration;

import discord4j.core.DiscordClientBuilder;
import discord4j.core.GatewayDiscordClient;
import discord4j.core.event.domain.Event;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.List;

@Configuration
@EnableScheduling
public class DiscordBotConfiguration {

    @Value("${token}")
    private String token;
    
    @Value("${channelId}")
    private String channelId;

    @Bean
    public GatewayDiscordClient gatewayDiscordClient() {
        return DiscordClientBuilder.create(getToken())
                .build()
                .login()
                .block();
    }

    public String getToken() {
        return token;
    }


    public String getChannelId() {
        return channelId;
    }

}