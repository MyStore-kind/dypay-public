/*
 * Copyright (c) 2021-2031, 河北计全科技有限公司 (https://www.jeequan.com & jeequan@126.com).
 * <p>
 * Licensed under the GNU LESSER GENERAL PUBLIC LICENSE 3.0;
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.gnu.org/licenses/lgpl.html
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jeequan.jeepay.mgr.secruity;

import com.jeequan.jeepay.mgr.config.SystemYmlConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import static org.springframework.security.config.Customizer.withDefaults;

/*
* Spring Security 配置项
*
* @author terrfly
* @site https://www.jeequan.com
* @date 2021/6/8 17:11
*/
@Configuration
@EnableWebSecurity
public class WebSecurityConfig{

    @Autowired private UserDetailsService userDetailsService;
    @Autowired private JeeAuthenticationEntryPoint unauthorizedHandler;
    @Autowired private SystemYmlConfig systemYmlConfig;

    // 安全加固 S3/S4: CORS 白名单从配置注入；逗号分隔；生产必须显式指定，禁止使用 "*"
    // 默认值仅供本地开发，部署时由 JEEPAY_CORS_ORIGINS / jeepay.cors.allowed-origins 覆盖
    @Value("${jeepay.cors.allowed-origins:http://localhost,http://localhost:8083,http://localhost:8082}")
    private String corsAllowedOrigins;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // 安全加固 S4: 当前为前后端分离 + Token（Authorization Header）认证体系，CSRF 保护可禁用。
                // 防护策略改为：CORS 白名单（S3）+ JWT/iToken 双重校验，避免跨站请求伪造。
                // 若未来引入 Cookie 会话，必须重新启用 CSRF。
                .csrf(AbstractHttpConfigurer::disable)
                .cors(withDefaults())
                .addFilter(corsFilter())
                .headers(httpSecurityHeadersConfigurer -> httpSecurityHeadersConfigurer.cacheControl(HeadersConfigurer.CacheControlConfig::disable))
                // 基于token，所以不需要session
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // 认证失败处理方式
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(unauthorizedHandler))
                .authenticationProvider(authenticationProvider())
                // 添加JWT filter
                .addFilterBefore(new JeeAuthenticationTokenFilter(), UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests((auth) -> {
                    auth.anyRequest().authenticated();
                });

        // 构建过滤链并返回
        return http.build();
    }

    @Bean
    public WebSecurityCustomizer ignoringCustomizer() {
        return (web) -> web.ignoring()
                .requestMatchers(HttpMethod.GET,
                        "/",
                        "/*.html",
                        "/favicon.ico",
                        "/*/*.html",
                        "/*/*.css",
                        "/*/*.js",
                        "/*/*.png",
                        "/*/*.jpg",
                        "/*/*.jpeg",
                        "/*/*.svg",
                        "/*/*.ico",
                        "/*/*.webp",
                        "/*.txt",
                        "/*/*.xls",
                        "/*/*.mp4"   //支持mp4格式的文件匿名访问
                )
                .requestMatchers(
                        // 安全加固 M7 TODO: /api/anon/** 为匿名通配，当前 anon 包仅含 AuthController（登录/验证码）。
                        // 新增匿名 controller 时务必人工审核，避免敏感接口被纳入。
                        "/api/anon/**", //匿名访问接口
                        "/cashier/**", //跨站托管收银台（公开访问，纯静态 + token 验证）
                        "/webjars/**","/v3/api-docs/**", "/doc.html", "/knife4j/**", "/swagger-ui/**", "/swagger-resources/**" // swagger相关
                );
    }

    @Bean
    public UserDetailsService userDetailsService() {
        return username -> userDetailsService.loadUserByUsername(username);
    }

    /**
     * 使用BCrypt强哈希函数 实现PasswordEncoder
     * **/
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 调用loadUserByUsername获得UserDetail信息，在AbstractUserDetailsAuthenticationProvider里执行用户状态检查
     */
    @Bean
    public AuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider authProvider = new DaoAuthenticationProvider();
        // DaoAuthenticationProvider 从自定义的 userDetailsService.loadUserByUsername 方法获取UserDetails
        authProvider.setUserDetailsService(userDetailsService());
        // 设置密码编辑器
        authProvider.setPasswordEncoder(passwordEncoder());
        return authProvider;
    }

    @Bean
    public AuthenticationManager authenticationManager() {
        DaoAuthenticationProvider authProvider = new DaoAuthenticationProvider();
        // DaoAuthenticationProvider 从自定义的 userDetailsService.loadUserByUsername 方法获取UserDetails
        authProvider.setUserDetailsService(userDetailsService());
        // 设置密码编辑器
        authProvider.setPasswordEncoder(passwordEncoder());
        return new ProviderManager(authProvider);
    }

    /** 允许跨域请求 **/
    @Bean
    public CorsFilter corsFilter() {

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        if(systemYmlConfig.getAllowCors()){
            CorsConfiguration config = new CorsConfiguration();
            // 安全加固 S3: 保留 allowCredentials(true) 因前后端通过 Authorization Header 携带 token，
            // 不再使用通配符 "*"，改为读取 jeepay.cors.allowed-origins 白名单
            config.setAllowCredentials(true);
            // 安全加固 S3: 解析逗号分隔配置，逐项加入显式白名单。空配置时不放行任何来源。
            if (corsAllowedOrigins != null && !corsAllowedOrigins.trim().isEmpty()) {
                for (String origin : corsAllowedOrigins.split(",")) {
                    String trimmed = origin.trim();
                    if (!trimmed.isEmpty()) {
                        // 支持通配符匹配（如 https://*.example.com），且与 allowCredentials=true 兼容
                        config.addAllowedOriginPattern(trimmed);
                    }
                }
            }
            config.addAllowedHeader(CorsConfiguration.ALL);   //允许任何请求头
            config.addAllowedMethod(CorsConfiguration.ALL);   //允许任何方法（post、get等）
            source.registerCorsConfiguration("/**", config); // CORS 配置对所有接口都有效
        }
        return new CorsFilter(source);
    }

}
