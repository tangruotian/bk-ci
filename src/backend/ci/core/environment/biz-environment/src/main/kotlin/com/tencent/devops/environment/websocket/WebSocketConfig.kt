package com.tencent.devops.environment.websocket

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.messaging.simp.config.MessageBrokerRegistry
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker
import org.springframework.web.socket.config.annotation.StompEndpointRegistry
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer


@Configuration
@EnableWebSocketMessageBroker
class WebSocketConfig @Autowired constructor(
    private val agentHandshake: AgentHandshakeInterceptor,
) : WebSocketMessageBrokerConfigurer {

    @Value("\${ws.cacheLimit:3600}")
    private val cacheLimit: Int = 3600

    override fun registerStompEndpoints(registry: StompEndpointRegistry) {
        registry.addEndpoint("/env/agent").addInterceptors(agentHandshake).setAllowedOriginPatterns("*").withSockJS()
    }

    override fun configureMessageBroker(config: MessageBrokerRegistry) {
        config.setCacheLimit(cacheLimit)
        config.enableSimpleBroker("/topic")
        config.setApplicationDestinationPrefixes("/env")
    }
}
