package org.thornex.musicparty.config;

import io.netty.channel.ChannelOption;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

@Configuration
public class WebClientConfig {

    /**
     * 两次网络读之间的最长静默时间。
     *
     * <p>上游（网易云网关 / CDN / B 站）偶尔会「连接建立成功但永远不回包」，
     * 没有这个上限时请求会一直挂着。下载队列是串行的，一个这样的请求就能把
     * 后面所有点歌堵到外层 TASK_TIMEOUT（实测堵满 4 分钟）。
     * 20s 对正常请求（含音频流，读间隔远小于 20s）都足够宽松。
     */
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(20);

    /** 连接超时：DNS 或 TCP 握手卡住也要快速失败，而不是无限等。 */
    private static final int CONNECT_TIMEOUT_MILLIS = 5_000;

    @Bean
    public WebClient webClient() {
        // 配置 ExchangeStrategies 来增加缓冲区大小
        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(10 * 1024 * 1024)) // 设置为 10MB
                .build();

        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MILLIS)
                .responseTimeout(RESPONSE_TIMEOUT);

        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .exchangeStrategies(strategies) // 应用配置
                .defaultHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .build();
    }
}