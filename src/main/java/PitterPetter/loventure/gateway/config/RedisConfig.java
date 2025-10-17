package PitterPetter.loventure.gateway.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import jakarta.annotation.PostConstruct;

@Configuration
@Slf4j
public class RedisConfig {

    @Value("${spring.redis.host:localhost}")
    private String redisHost;

    @Value("${spring.redis.port:6379}")
    private int redisPort;

    @Value("${spring.redis.password:}")
    private String redisPassword;

    @Value("${spring.redis.database:0}")
    private int redisDatabase;

    @PostConstruct
    public void logRedisConfiguration() {
        log.info("🔧 Redis 설정 정보:");
        log.info("   - 호스트: {}", redisHost);
        log.info("   - 포트: {}", redisPort);
        log.info("   - 데이터베이스: {}", redisDatabase);
        log.info("   - 비밀번호 설정: {}", redisPassword != null && !redisPassword.isEmpty() ? "✅ 설정됨" : "❌ 설정되지 않음");
        log.info("   - 연결 풀: Lettuce (논블로킹)");
        log.info("   - 시리얼라이저: JSON (GenericJackson2JsonRedisSerializer)");
    }

    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration();
        config.setHostName(redisHost);
        config.setPort(redisPort);
        config.setDatabase(redisDatabase);
        
        if (redisPassword != null && !redisPassword.isEmpty()) {
            config.setPassword(redisPassword);
            log.debug("🔐 Redis 비밀번호 설정됨");
        } else {
            log.debug("🔓 Redis 비밀번호 없음 (인증 없이 연결)");
        }
        
        LettuceConnectionFactory factory = new LettuceConnectionFactory(config);
        log.info("🏭 Redis ConnectionFactory 생성 완료 - {}:{}", redisHost, redisPort);
        return factory;
    }

    @Bean
    public ReactiveRedisConnectionFactory reactiveRedisConnectionFactory() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration();
        config.setHostName(redisHost);
        config.setPort(redisPort);
        config.setDatabase(redisDatabase);
        
        if (redisPassword != null && !redisPassword.isEmpty()) {
            config.setPassword(redisPassword);
            log.debug("🔐 Reactive Redis 비밀번호 설정됨");
        } else {
            log.debug("🔓 Reactive Redis 비밀번호 없음 (인증 없이 연결)");
        }
        
        LettuceConnectionFactory factory = new LettuceConnectionFactory(config);
        log.info("⚡ ReactiveRedisConnectionFactory 생성 완료 - {}:{}", redisHost, redisPort);
        return factory;
    }

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        
        // Key serializer
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        
        // Value serializer
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.setHashValueSerializer(new GenericJackson2JsonRedisSerializer());
        
        template.afterPropertiesSet();
        log.info("📦 RedisTemplate Bean 생성 완료 - JSON 시리얼라이저 적용");
        return template;
    }

    @Bean
    public ReactiveRedisTemplate<String, Object> reactiveRedisTemplate(ReactiveRedisConnectionFactory connectionFactory) {
        StringRedisSerializer keySerializer = new StringRedisSerializer();
        GenericJackson2JsonRedisSerializer valueSerializer = new GenericJackson2JsonRedisSerializer();
        
        RedisSerializationContext.RedisSerializationContextBuilder<String, Object> builder =
                RedisSerializationContext.newSerializationContext(keySerializer);
        
        RedisSerializationContext<String, Object> context = builder
                .value(valueSerializer)
                .hashKey(keySerializer)
                .hashValue(valueSerializer)
                .build();
        
        log.info("⚡ ReactiveRedisTemplate Bean 생성 완료 - 논블로킹 Redis 클라이언트");
        return new ReactiveRedisTemplate<>(connectionFactory, context);
    }

    @Bean
    public ReactiveStringRedisTemplate reactiveStringRedisTemplate(ReactiveRedisConnectionFactory connectionFactory) {
        log.info("🔤 ReactiveStringRedisTemplate Bean 생성 완료 - Gateway Rate Limiter용");
        return new ReactiveStringRedisTemplate(connectionFactory);
    }
}
