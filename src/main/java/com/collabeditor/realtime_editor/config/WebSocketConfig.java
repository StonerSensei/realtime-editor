package com.collabeditor.realtime_editor.config;

import com.collabeditor.realtime_editor.websocket.ChatWebSocketHandler;
import com.collabeditor.realtime_editor.websocket.CodeExecutionWebSocketHandler;
import com.collabeditor.realtime_editor.websocket.YjsRelayWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.*;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final CodeExecutionWebSocketHandler codeExecutionWebSocketHandler;
    private final YjsRelayWebSocketHandler yjsRelayWebSocketHandler;
    private final ChatWebSocketHandler chatWebSocketHandler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(codeExecutionWebSocketHandler, "/ws/exec")
                .setAllowedOrigins("*");

        registry.addHandler(yjsRelayWebSocketHandler, "/yjs/{roomId}")
                .setAllowedOrigins("*");

        registry.addHandler(chatWebSocketHandler, "/ws/chat/{roomId}")
                .setAllowedOrigins("*");
    }

  
    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxBinaryMessageBufferSize(2 * 1024 * 1024); 
        container.setMaxTextMessageBufferSize(1024 * 1024);      
        return container;
    }
}
