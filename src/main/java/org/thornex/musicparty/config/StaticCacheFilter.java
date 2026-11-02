package org.thornex.musicparty.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 静态资源缓存策略。
 *
 * 问题：index.html 原先没有任何 Cache-Control，浏览器会启发式缓存它；
 * 部署新版本后用户仍拿旧 index.html，指向旧 hash 的 JS，看起来「没更新」。
 *
 * 规则：
 *  - /assets/**  （文件名带 hash）→ 一年 immutable；
 *  - index.html 与所有 SPA 路由 → no-cache（每次回源校验）；
 *  - /api、/ws、/media 不在此处干预。
 */
@Component
public class StaticCacheFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();

        if ("GET".equalsIgnoreCase(request.getMethod())
                && !path.startsWith("/api/")
                && !path.startsWith("/ws")
                && !path.startsWith("/media/")) {

            if (path.startsWith("/assets/")) {
                response.setHeader("Cache-Control", "public, max-age=31536000, immutable");
            } else if (path.equals("/") || path.endsWith(".html") || !path.contains(".")) {
                // SPA 路由最终 forward 到 index.html，故按 HTML 处理
                response.setHeader("Cache-Control", "no-cache, must-revalidate");
            }
        }

        chain.doFilter(request, response);
    }
}
