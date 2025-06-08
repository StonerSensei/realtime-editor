package com.collabeditor.realtime_editor;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.*;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(new CodeWebSocketHandler(), "/ws/{roomId}")
                .setAllowedOrigins("*");

        // Add the execution WebSocket handler
        registry.addHandler(new CodeExecutionWebSocketHandler(), "/ws/exec")
                .setAllowedOrigins("*");
    }
}