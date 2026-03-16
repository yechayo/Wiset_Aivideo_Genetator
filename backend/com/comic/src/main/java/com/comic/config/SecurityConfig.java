package com.comic.config;

import com.comic.security.JwtFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.Collections;

/**
 * Spring Security 配置（Spring Boot 2.7.x / Java 8 版本）
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtFilter jwtFilter;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            // 禁用 CSRF（前后端分离 + JWT 不需要）
            .csrf().disable()

            // 启用 CORS
            .cors().configurationSource(corsConfigurationSource())

            .and()

            // 无状态 Session
            .sessionManagement()
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS)

            .and()

            // 接口权限规则
            .authorizeHttpRequests()
                // 认证接口白名单
                .antMatchers("/api/auth/register",
                             "/api/auth/login",
                             "/api/auth/refresh").permitAll()
                // 测试接口白名单
                .antMatchers("/api/test/**").permitAll()
                // Swagger UI 白名单
                .antMatchers("/swagger-ui.html",
                             "/swagger-ui/**",
                             "/v3/api-docs/**",
                             "/swagger-resources/**",
                             "/webjars/**").permitAll()
                // OPTIONS 预检请求放行
                .antMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                // 其他请求需要认证
                .anyRequest().authenticated()

            .and()

            // 在默认过滤器前插入 JWT 过滤器
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(
            AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    /**
     * CORS 配置
     * 开发期放开所有来源，上线后把 allowedOrigins 改为具体域名
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(Collections.singletonList("*"));
        config.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(Collections.singletonList("*"));
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
